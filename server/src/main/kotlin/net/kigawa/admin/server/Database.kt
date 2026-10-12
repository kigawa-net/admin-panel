package net.kigawa.admin.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection

/**
 * admin-panel専用のMariaDB(admin-panel#64)。CI向けトークン発行ブローカーの呼び出し元
 * リポジトリ別許可設定(ci_token_policy)を、コードのハードコードから管理画面経由の
 * DB管理に変えるために導入した。設定未投入(MARIADB_PASSWORD未設定)の環境では機能
 * 全体を無効化し、他の機能には影響させない。
 */
private val mariadbHost = System.getenv("MARIADB_HOST") ?: "admin-panel-mariadb"
private val mariadbPort = System.getenv("MARIADB_PORT") ?: "3306"
private val mariadbDatabase = System.getenv("MARIADB_DATABASE") ?: "admin_panel"
private val mariadbUser = System.getenv("MARIADB_USER") ?: "admin_panel"
private val mariadbPassword = System.getenv("MARIADB_PASSWORD")

internal val isDatabaseConfigured: Boolean get() = !mariadbPassword.isNullOrBlank()

private val dataSource: HikariDataSource by lazy {
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:mariadb://$mariadbHost:$mariadbPort/$mariadbDatabase"
            username = mariadbUser
            password = mariadbPassword
            maximumPoolSize = 5
        }
    )
}

internal suspend fun <T> withDbConnection(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

/**
 * A legacy production database has the V1 tables but no Flyway history.
 * Before enabling baselineOnMigrate, verify it is EXACTLY the known legacy
 * schema: otherwise automatically baselining an arbitrary DB is dangerous.
 */
private data class LegacyColumn(
    val type: String,
    val notNull: Boolean,
    val primary: Boolean,
    val charLength: Long? = null,
)

private val legacyTables = mapOf(
    "ci_token_policy" to mapOf(
        "caller_repository" to LegacyColumn("varchar", true, true, 255),
        "allowed_owner" to LegacyColumn("varchar", true, false, 255),
        "allowed_repositories" to LegacyColumn("text", true, false),
        "allowed_permissions" to LegacyColumn("text", true, false),
    ),
    "watchdog_ping" to mapOf(
        "id" to LegacyColumn("int", true, true),
        "last_ping_at" to LegacyColumn("timestamp", true, false),
    ),
)

/**
 * No DDL is performed during preflight. A history-managed database is always
 * checked by Flyway validation; a legacy schema without history must exactly
 * match V1 before we record baseline version 0.
 */
private fun checkLegacySchemaBeforeBaseline(connection: Connection) {
    val tables = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT TABLE_NAME FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'"
        ).use { rows ->
            buildSet {
                while (rows.next()) add(rows.getString(1))
            }
        }
    }

    if ("flyway_schema_history" in tables || tables.isEmpty()) return
    check(tables == legacyTables.keys) {
        "Unexpected legacy admin-panel tables; refusing Flyway baseline"
    }

    for ((table, expected) in legacyTables) {
        val actual = connection.prepareStatement(
            "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY, CHARACTER_MAXIMUM_LENGTH " +
                "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val length = rows.getLong("CHARACTER_MAXIMUM_LENGTH")
                            .let { if (rows.wasNull()) null else it }
                        put(
                            rows.getString("COLUMN_NAME"),
                            LegacyColumn(
                                type = rows.getString("DATA_TYPE").lowercase(),
                                notNull = rows.getString("IS_NULLABLE") == "NO",
                                primary = rows.getString("COLUMN_KEY") == "PRI",
                                charLength = length.takeIf { rows.getString("DATA_TYPE") == "varchar" },
                            )
                        )
                    }
                }
            }
        }
        check(actual == expected) {
            "Unexpected legacy column layout for $table; refusing Flyway baseline"
        }
    }
}

/**
 * #228: run versioned migrations and verify legacy schema before baseline.
 * Migrating at startup is fail-fast: a schema mismatch must never silently
 * leave the CI token broker running against a partially migrated database.
 * Flyway's schema history lock serializes concurrent application startups.
 */
internal fun initDatabaseSchema() {
    dataSource.connection.use(::checkLegacySchemaBeforeBaseline)
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .baselineOnMigrate(true)
        .validateOnMigrate(true)
        .load()
    flyway.migrate()
    flyway.validate()
}

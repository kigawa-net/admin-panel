package net.kigawa.admin.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** 起動時に一度だけ呼ぶ。テーブルが無ければ作成する(マイグレーションツールを入れるほどの規模ではないため素朴なDDLで済ませる)。 */
internal fun initDatabaseSchema() {
    dataSource.connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS ci_token_policy (
                    caller_repository VARCHAR(255) PRIMARY KEY,
                    allowed_owner VARCHAR(255) NOT NULL,
                    allowed_repositories TEXT NOT NULL,
                    allowed_permissions TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}

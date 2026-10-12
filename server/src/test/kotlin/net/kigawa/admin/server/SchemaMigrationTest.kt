package net.kigawa.admin.server

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CI provisions a disposable MariaDB 11.4 with the legacy production tables
 * and a seeded policy. This test verifies that baseline + V1..V3 preserve data.
 * On developer machines without MariaDB credentials the integration scenario
 * is skipped, while CI always sets MARIADB_PASSWORD.
 */
class SchemaMigrationTest {
    @Test
    fun `legacy schema migrates with intact policy and is repeatable`() = runBlocking {
        if (!isDatabaseConfigured) {
            check(System.getenv("MARIADB_MIGRATION_TEST_REQUIRED") != "true") {
                "MariaDB migration integration test is required in CI but DB credentials are not configured"
            }
            return@runBlocking // Optional only for developer machines without a DB fixture.
        }

        val before = withDbConnection { conn ->
            val policy = conn.prepareStatement(
                "SELECT allowed_owner, allowed_repositories, allowed_permissions " +
                    "FROM ci_token_policy WHERE caller_repository = ?"
            ).use { statement ->
                statement.setString(1, "OneServerMC/RpgCore")
                statement.executeQuery().use { result ->
                    assertTrue(result.next(), "CI fixture is required for this migration test")
                    listOf(result.getString(1), result.getString(2), result.getString(3))
                }
            }
            val watchdog = conn.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, last_ping_at FROM watchdog_ping ORDER BY id"
                ).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getInt(1).toString() to rows.getTimestamp(2).toString())
                        }
                    }
                }
            }
            policy to watchdog
        }

        initDatabaseSchema()
        initDatabaseSchema() // validates a no-op repeat migration

        withDbConnection { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank"
                ).use { rows ->
                    val versions = buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                    assertEquals(listOf("1", "2", "3"), versions.filterNot { it == "0" })
                    assertTrue(versions.first() == "0" || versions.first() == "1")
                }
            }
            conn.prepareStatement(
                "SELECT allowed_owner, allowed_repositories, allowed_permissions, " +
                    "version, oidc_constraints FROM ci_token_policy WHERE caller_repository = ?"
            ).use { statement ->
                statement.setString(1, "OneServerMC/RpgCore")
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(before.first, listOf(rows.getString(1), rows.getString(2), rows.getString(3)))
                    assertEquals(1L, rows.getLong("version"))
                    assertEquals(null, rows.getString("oidc_constraints"))
                }
            }
            val watchdogAfter = conn.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, last_ping_at FROM watchdog_ping ORDER BY id"
                ).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getInt(1).toString() to rows.getTimestamp(2).toString())
                        }
                    }
                }
            }
            assertEquals(before.second, watchdogAfter)
        }
    }
}

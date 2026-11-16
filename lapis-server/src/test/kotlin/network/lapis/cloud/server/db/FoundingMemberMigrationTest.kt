package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * V154 `member_founding_member`: four additive columns on `member`. Existing rows get FALSE/NULL, and the three CHECKs reject a date or a consent
 * without the marker and a half-recorded consent. The same scenarios run on H2 and on PostgreSQL.
 */
abstract class FoundingMemberMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rows(sql: String): List<List<String>> =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
                    }
                }
            }

        fun fails(sql: String): Boolean = runCatching { exec(sql) }.isFailure

        val existing = UUID.randomUUID()

        fun insertMember(id: UUID) =
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$id', 'M $id', 'fm-$id@example.org', 'ACTIVE', DATE '2024-01-01')",
            )

        beforeSpec {
            h = newHarness()
            h.flyway("153").migrate()
            insertMember(existing)
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("existing rows are not founding members and carry no date and no consent") {
            rows(
                "SELECT CASE WHEN founding_member THEN 1 ELSE 0 END, founding_member_since, " +
                    "founding_member_consent_granted_at, founding_member_consent_version " +
                    "FROM member WHERE id = '$existing'",
            ) shouldBe listOf(listOf("0", "null", "null", "null"))
        }

        test("a founding member may carry a date and a complete consent") {
            exec(
                "UPDATE member SET founding_member = TRUE, founding_member_since = DATE '2020-05-01', " +
                    "founding_member_consent_granted_at = TIMESTAMP '2026-01-01 10:00:00', founding_member_consent_version = '1' " +
                    "WHERE id = '$existing'",
            )
            rows("SELECT founding_member_consent_version FROM member WHERE id = '$existing'") shouldBe listOf(listOf("1"))
        }

        test("the CHECKs reject a date without the marker, a consent without the marker and a half-recorded consent") {
            val id = UUID.randomUUID()
            insertMember(id)
            fails("UPDATE member SET founding_member_since = DATE '2020-05-01' WHERE id = '$id'") shouldBe true
            fails(
                "UPDATE member SET founding_member_consent_granted_at = TIMESTAMP '2026-01-01 10:00:00', " +
                    "founding_member_consent_version = '1' WHERE id = '$id'",
            ) shouldBe true
            exec("UPDATE member SET founding_member = TRUE WHERE id = '$id'")
            fails("UPDATE member SET founding_member_consent_granted_at = TIMESTAMP '2026-01-01 10:00:00' WHERE id = '$id'") shouldBe true
            fails("UPDATE member SET founding_member_consent_version = '1' WHERE id = '$id'") shouldBe true
            rows("SELECT founding_member_consent_version FROM member WHERE id = '$id'") shouldBe listOf(listOf("null"))
        }
    })

class FoundingMemberMigrationTest : FoundingMemberMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class FoundingMemberMigrationPostgresTest : FoundingMemberMigrationScenarios({ PostgresMigrationHarness() })

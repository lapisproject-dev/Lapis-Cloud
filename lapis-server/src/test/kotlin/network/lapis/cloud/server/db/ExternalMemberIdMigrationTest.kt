package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * V156 `member_external_id`: two additive columns on `member`, a pair CHECK and a unique index on (source, id). Existing rows get NULL/NULL, several
 * NULL rows coexist, the same id may exist under another source. The same scenarios run on H2 and on PostgreSQL, on the upgrade path V155 -> V156.
 */
abstract class ExternalMemberIdMigrationScenarios(
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
                    "('$id', 'M $id', 'ex-$id@example.org', 'ACTIVE', DATE '2024-01-01')",
            )

        beforeSpec {
            h = newHarness()
            h.flyway("155").migrate()
            insertMember(existing)
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("existing rows carry neither an id nor a source after the upgrade from V155") {
            rows("SELECT external_member_id, external_member_id_source FROM member WHERE id = '$existing'") shouldBe
                listOf(listOf("null", "null"))
        }

        test("several members without an id coexist (NULLs are distinct in the unique index)") {
            insertMember(UUID.randomUUID())
            insertMember(UUID.randomUUID())
            rows("SELECT COUNT(*) FROM member WHERE external_member_id IS NULL")[0][0].toInt() shouldBe 3
        }

        test("the pair CHECK rejects an id without a source and a source without an id") {
            val id = UUID.randomUUID()
            insertMember(id)
            fails("UPDATE member SET external_member_id = 'A1' WHERE id = '$id'") shouldBe true
            fails("UPDATE member SET external_member_id_source = 'Wabeo' WHERE id = '$id'") shouldBe true
            exec("UPDATE member SET external_member_id = 'A1', external_member_id_source = 'Wabeo' WHERE id = '$id'")
            rows("SELECT external_member_id, external_member_id_source FROM member WHERE id = '$id'") shouldBe listOf(listOf("A1", "Wabeo"))
        }

        test("the unique index rejects the same id of the same source and allows it under another source") {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val c = UUID.randomUUID()
            insertMember(a)
            insertMember(b)
            insertMember(c)
            exec("UPDATE member SET external_member_id = 'U-77', external_member_id_source = 'Wabeo' WHERE id = '$a'")
            fails("UPDATE member SET external_member_id = 'U-77', external_member_id_source = 'Wabeo' WHERE id = '$b'") shouldBe true
            exec("UPDATE member SET external_member_id = 'U-77', external_member_id_source = 'LupaBB' WHERE id = '$c'")
            // case is significant: the database does not fold it
            exec("UPDATE member SET external_member_id = 'u-77', external_member_id_source = 'Wabeo' WHERE id = '$b'")
        }
    })

class ExternalMemberIdMigrationTest : ExternalMemberIdMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ExternalMemberIdMigrationPostgresTest : ExternalMemberIdMigrationScenarios({ PostgresMigrationHarness() })

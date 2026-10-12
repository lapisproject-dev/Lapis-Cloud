package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.111 -- `V91__mailing_list_dynamic.sql` on the upgrade path V90 -> V91, on H2 AND on PostgreSQL: an existing list keeps working as a
 * MANUAL list, the CHECKs tie kind, rule and mandatory flag together, the opt-out table is unique per (list, member) and goes with its list, the
 * audit-log CHECK accepts the two new entity types, and a re-run is a no-op.
 */
abstract class MailingListDynamicMigrationScenarios(
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

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val memberId = UUID.randomUUID()
        val oldList = UUID.randomUUID()
        val oldMessage = UUID.randomUUID()
        var seq = 9900
        val dummyHash = "e".repeat(64)
        val rule = """{"v":1,"op":"ALL","conditions":[]}"""

        fun listInsert(
            kind: String,
            ruleJson: String?,
            mandatory: Boolean,
            id: UUID = UUID.randomUUID(),
        ) = "INSERT INTO mailing_list (id, name, created_by, kind, rule_json, mandatory) VALUES ('$id', 'L-$id', '$memberId', '$kind', " +
            "${ruleJson?.let { "'$it'" } ?: "NULL"}, ${if (mandatory) "TRUE" else "FALSE"})"

        fun auditInsert(entityType: String) =
            "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                "VALUES ('${UUID.randomUUID()}', ${seq++}, TIMESTAMP '2026-10-12 10:00:00', '$entityType', " +
                "'${UUID.randomUUID()}', 'POST', '$dummyHash')"

        beforeSpec {
            h = newHarness()
            h.flyway("90").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'dyn-mig-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec("INSERT INTO mailing_list (id, name, created_by) VALUES ('$oldList', 'Alt', '$memberId')")
            exec(
                "INSERT INTO mailing_message (id, subject, body_text, sent_by, status, mailing_list_id) " +
                    "VALUES ('$oldMessage', 'Alt', 'Text', '$memberId', 'DRAFT', '$oldList')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing list is a MANUAL, non-mandatory list without a rule; an existing message is not 'resolved'") {
            // H2 prints a boolean as TRUE/FALSE, PostgreSQL as t/f or true/false depending on the driver path
            val row =
                rows(
                    "SELECT kind, rule_json, mandatory, rule_updated_at FROM mailing_list WHERE id = '$oldList'",
                ).single().map { it.lowercase() }
            row[0] shouldBe "manual"
            row[1] shouldBe "null"
            (row[2] in listOf("false", "f")) shouldBe true
            row[3] shouldBe "null"
            rows("SELECT recipients_resolved_at FROM mailing_message WHERE id = '$oldMessage'").single().single() shouldBe "null"
        }

        test("the CHECKs tie kind, rule and mandatory flag together") {
            rejection(listInsert(kind = "MANUAL", ruleJson = null, mandatory = false)) shouldBe null
            rejection(listInsert(kind = "DYNAMIC", ruleJson = rule, mandatory = false)) shouldBe null
            rejection(listInsert(kind = "DYNAMIC", ruleJson = rule, mandatory = true)) shouldBe null
            (rejection(listInsert(kind = "DYNAMIC", ruleJson = null, mandatory = false)) != null) shouldBe true
            (rejection(listInsert(kind = "MANUAL", ruleJson = rule, mandatory = false)) != null) shouldBe true
            (rejection(listInsert(kind = "MANUAL", ruleJson = null, mandatory = true)) != null) shouldBe true
            (rejection(listInsert(kind = "OTHER", ruleJson = rule, mandatory = false)) != null) shouldBe true
        }

        test("an opt-out is unique per (list, member) and goes with its list") {
            val dyn = UUID.randomUUID()
            exec(listInsert(kind = "DYNAMIC", ruleJson = rule, mandatory = false, id = dyn))

            fun optOut() =
                "INSERT INTO mailing_list_opt_out (id, mailing_list_id, member_id, opted_out_at) VALUES ('${UUID.randomUUID()}', '$dyn', '$memberId', TIMESTAMP '2026-10-12 10:00:00')"
            rejection(optOut()) shouldBe null
            (rejection(optOut()) != null) shouldBe true
            rows("SELECT COUNT(*) FROM mailing_list_opt_out WHERE mailing_list_id = '$dyn'").single().single() shouldBe "1"
            exec("DELETE FROM mailing_list WHERE id = '$dyn'")
            rows("SELECT COUNT(*) FROM mailing_list_opt_out WHERE mailing_list_id = '$dyn'").single().single() shouldBe "0"
        }

        test("the audit-log CHECK accepts MAILING_LIST and MAILING_MESSAGE, keeps earlier types and rejects an unknown one") {
            rejection(auditInsert("MAILING_LIST")) shouldBe null
            rejection(auditInsert("MAILING_MESSAGE")) shouldBe null
            listOf("ARTICLE_IMPORT_IMAGES_APPLIED", "COMMITTEE_MEMBERSHIP", "MEMBER", "JOURNAL_ENTRY").forEach {
                rejection(auditInsert(it)) shouldBe
                    null
            }
            (rejection(auditInsert("BOGUS")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class MailingListDynamicMigrationTest : MailingListDynamicMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MailingListDynamicMigrationPostgresTest : MailingListDynamicMigrationScenarios({ PostgresMigrationHarness() })

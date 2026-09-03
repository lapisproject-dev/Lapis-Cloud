package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The actual enforcement mechanism referenced throughout the `dsgvo` package's KDoc (see
 * [PersonalDataRegistry]): walks the `information_schema` for every foreign key that targets a
 * [PersonalDataRegistry.subjectRootTables] table and asserts the referencing table is either
 * covered by some [PersonalDataContributor] in [PersonalDataRegistry.contributors] or explicitly
 * listed in [PersonalDataRegistry.noPersonalDataAllowlist] with a written reason. A future wave that
 * adds e.g. `event_registration.member_id` without registering a contributor (or allowlisting it)
 * goes red here — `./gradlew clean check` catches the rot a hand-maintained list alone could not.
 *
 * **Welle V1.4.2 "Interessenten-/Sympathisanten-CRM"**: the walk now covers every table in
 * [PersonalDataRegistry.subjectRootTables] (`member` AND `crm_contact`), not just `member` — see
 * that object's KDoc for why `crm_contact` needed to become a subject root rather than a leaf.
 *
 * Runs against the H2 in-memory test database (house rule: tests never touch a real deployment).
 * The ANSI `information_schema` views queried here (`table_constraints`, `key_column_usage`,
 * `referential_constraints`) resolve the same way on H2 (`MODE=PostgreSQL`) as on real Postgres,
 * so this walk reflects what a Postgres deployment's schema would show too.
 */
class PersonalDataCoverageTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("every table with a FK to a subject-root table is covered by a contributor or allowlisted") {
            val uncovered =
                transaction {
                    tablesReferencingSubjectRoots(PersonalDataRegistry.subjectRootTables).filterNot { tableName ->
                        tableName in PersonalDataRegistry.coveredTableNames() ||
                            tableName in PersonalDataRegistry.noPersonalDataAllowlist.keys
                    }
                }
            uncovered.shouldBeEmpty()
        }

        test("every subject-root table is itself covered even though it is the PK side, not the FK side, of every relationship") {
            PersonalDataRegistry.subjectRootTables.forEach { root ->
                (root in PersonalDataRegistry.coveredTableNames()) shouldBe true
            }
        }

        test("no table is covered by more than one contributor (regression guard for PersonalDataRegistry's init check)") {
            val allCoveredTables = PersonalDataRegistry.contributors.flatMap { it.coveredTables.map { table -> table.tableName } }
            allCoveredTables.size shouldBe allCoveredTables.toSet().size
        }

        test("every noPersonalDataAllowlist entry carries a non-blank written reason") {
            PersonalDataRegistry.noPersonalDataAllowlist.values.all { it.isNotBlank() } shouldBe true
        }

        // ── Welle V1.4.2: knownUncoveredSubjectRoots / nonMemberPiiTables ────────────────────

        test("knownUncoveredSubjectRoots is disjoint from subjectRootTables") {
            (PersonalDataRegistry.knownUncoveredSubjectRoots.keys intersect PersonalDataRegistry.subjectRootTables).shouldBeEmpty()
        }

        test("every knownUncoveredSubjectRoots reason is non-blank and names a wave number") {
            val waveRegex = Regex("""V\d+\.\d+""")
            PersonalDataRegistry.knownUncoveredSubjectRoots.forEach { (table, reason) ->
                (reason.isNotBlank() && waveRegex.containsMatchIn(reason)) shouldBe true
            }
        }

        test("every non-member PII table is either a subject root or a documented, wave-numbered gap -- never both, never neither") {
            PersonalDataRegistry.nonMemberPiiTables.forEach { table ->
                val isRoot = table in PersonalDataRegistry.subjectRootTables
                val isKnownGap = table in PersonalDataRegistry.knownUncoveredSubjectRoots
                (isRoot xor isKnownGap) shouldBe true
            }
        }

        test("dsgvo_audit_log rows never carry payload -- only the columns declared on DsgvoAuditLogTable exist") {
            // Structural guard, not a runtime-content check (that is exercised end-to-end in
            // DsgvoServiceTest): the table's column set itself has no free-text payload column
            // (email/message body/file name) to begin with, only counters/UUIDs/enums.
            val columnNames =
                transaction {
                    network.lapis.cloud.server.db.generated.DsgvoAuditLogTable.columns
                        .map { it.name }
                        .toSet()
                }
            val allowedColumns =
                setOf(
                    "id",
                    "occurred_at",
                    "actor_member_id",
                    "actor_role",
                    "action",
                    "subject_member_id",
                    "request_id",
                    "outcome_summary",
                    "legal_basis",
                    "subject_kind",
                )
            columnNames shouldBe allowedColumns
        }
    })

/**
 * ANSI `information_schema` walk (see class KDoc) — table names of every FK column whose target
 * table is one of [roots]. Deliberately does not rely on constraint-naming conventions: joins
 * `table_constraints` (the FK side) through `referential_constraints` to the target
 * `table_constraints` row (the unique/PK constraint on the referenced table) instead, which is
 * portable across H2 and real Postgres. A root that references itself (e.g. a future
 * `crm_contact.referred_by -> crm_contact`) would be reported as referencing a subject root too —
 * harmless for `PersonalDataRegistry.coveredTableNames()`'s membership check (the root already
 * covers itself), and no such self-reference exists today.
 */
private fun JdbcTransaction.tablesReferencingSubjectRoots(roots: Set<String>): Set<String> {
    val tables = mutableSetOf<String>()
    exec(
        """
        SELECT tc.table_name AS fk_table, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            if (rs.getString("ref_table") in roots) {
                tables += rs.getString("fk_table")
            }
        }
    }
    return tables
}

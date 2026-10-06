package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.PostgresTestSupport
import network.lapis.cloud.shared.domain.MemberMembershipTierSnapshot
import network.lapis.cloud.shared.domain.MemberRegionalChapterSnapshot
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/** A migratable database the scenarios drive with raw JDBC (no Exposed, no application module). */
interface MigrationHarness {
    fun flyway(target: String?): Flyway

    fun <T> connection(block: (Connection) -> T): T

    fun close() = Unit
}

internal class H2MigrationHarness : MigrationHarness {
    private val url = "jdbc:h2:mem:member-status-history-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

    override fun flyway(target: String?): Flyway {
        val configuration = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    override fun <T> connection(block: (Connection) -> T): T = DriverManager.getConnection(url, "sa", "").use(block)
}

internal class PostgresMigrationHarness : MigrationHarness {
    private val pg = PostgresTestSupport.createDatabase(migrated = false, timeouts = DbSessionTimeouts.DISABLED)
    private val dataSource: DataSource = pg.dataSource

    override fun flyway(target: String?): Flyway {
        val configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    override fun <T> connection(block: (Connection) -> T): T = pg.rawConnection().use(block)

    override fun close() = pg.close()
}

/**
 * Welle V1.9.59 -- `V72__member_status_history.sql`: the constraints bite and the SQL-only backfill reconstructs each member's status
 * chain from the evidence the database really holds (audit log, acknowledgments, `reviewed_at`, `date_of_death`), never guessing.
 * The same scenarios run on H2 and on PostgreSQL. The audit rows are raw INSERTs (dummy hashes): the migration only READS the audit log,
 * so the test pins that `audit_log_entry` and `member` are byte-identical across V71 -> V72 instead of re-verifying a hash chain.
 */
abstract class MemberStatusHistoryMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun <T> query(
            sql: String,
            read: (java.sql.ResultSet) -> T,
        ): T = h.connection { c -> c.createStatement().use { st -> st.executeQuery(sql).use(read) } }

        fun rows(sql: String): List<List<String>> =
            query(sql) { rs ->
                val n = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
            }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val ids = List(12) { UUID.randomUUID() }
        val a = ids[0]
        val b = ids[1]
        val c = ids[2]
        val d = ids[3]
        val e = ids[4]
        val f = ids[5]
        val g = ids[6]
        val hh = ids[7]
        val i = ids[8]
        val j = ids[9]
        val k = ids[10]
        val l = ids[11]
        var auditSeq = 0L

        fun member(
            id: UUID,
            status: String,
            joined: String,
            extra: String = "",
        ) {
            val cols = StringBuilder("id, display_name, email, status, joined_at")
            val vals = StringBuilder("'$id', 'M', 'msh-$id@example.org', '$status', DATE '$joined'")
            if (extra.isNotEmpty()) {
                // extra = "col=literal;col=literal"
                extra.split(";").forEach {
                    val (col, lit) = it.split("=", limit = 2)
                    cols.append(", $col")
                    vals.append(", $lit")
                }
            }
            exec("INSERT INTO member ($cols) VALUES ($vals)")
        }

        fun audit(
            member: UUID,
            at: String,
            action: String,
            before: String?,
            after: String?,
        ) {
            auditSeq++

            fun lit(s: String?) = s?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
            exec(
                "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, before_snapshot, " +
                    "after_snapshot, entry_hash) VALUES ('${UUID.randomUUID()}', $auditSeq, TIMESTAMP '$at', 'MEMBER', '$member', " +
                    "'$action', ${lit(before)}, ${lit(after)}, '${"0".repeat(63)}$auditSeq')",
            )
        }

        fun snap(status: String) = """{"displayNameChanged":false,"emailChanged":false,"status":"$status","role":null}"""

        fun ack(
            table: String,
            member: UUID,
            at: String,
            versionColumn: String,
            shaColumn: String,
        ) = exec(
            "INSERT INTO $table (id, member_id, acknowledged_at, $versionColumn, $shaColumn) VALUES " +
                "('${UUID.randomUUID()}', '$member', TIMESTAMP '$at', 'v1', '${"a".repeat(64)}')",
        )

        fun friendAck(
            m: UUID,
            at: String,
        ) = ack("friend_terms_acknowledgment", m, at, "terms_version", "terms_sha256")

        fun agreementAck(
            m: UUID,
            at: String,
        ) = ack("membership_agreement_acknowledgment", m, at, "agreement_version", "agreement_sha256")

        fun history(m: UUID) =
            rows(
                "SELECT CAST(effective_from AS VARCHAR(30)), status, COALESCE(previous_status, '-'), source, " +
                    "CASE WHEN recorded_at IS NULL THEN 'null' ELSE 'set' END FROM member_status_history " +
                    "WHERE member_id = '$m' ORDER BY effective_from",
            ).map { r -> listOf(r[0].take(19), r[1], r[2], r[3], r[4]).joinToString(" | ") }

        fun dump(table: String) = rows("SELECT * FROM $table ORDER BY 1, 2").joinToString("\n")

        lateinit var memberBefore: String
        lateinit var auditBefore: String

        beforeSpec {
            h = newHarness()
            h.flyway("71").migrate()
            member(a, "ACTIVE", "2020-03-15")
            member(b, "ACTIVE", "2024-02-01", "reviewed_at=TIMESTAMP '2024-02-20 10:00:00'")
            agreementAck(b, "2024-02-10 09:00:00")
            member(c, "FRIEND", "2023-04-01", "reviewed_at=TIMESTAMP '2023-06-10 10:00:00'")
            friendAck(c, "2023-05-01 08:00:00")
            agreementAck(c, "2023-06-01 08:00:00")
            member(d, "WITHDRAWN", "2021-01-01")
            audit(d, "2025-01-10 10:00:00", "UPDATE", snap("ACTIVE"), snap("DONOR"))
            audit(d, "2025-02-01 10:00:00", "UPDATE", snap("DONOR"), snap("DONOR"))
            audit(d, "2025-03-10 10:00:00", "UPDATE", snap("DONOR"), snap("ACTIVE"))
            audit(d, "2025-06-10 10:00:00", "UPDATE", snap("ACTIVE"), snap("WITHDRAWN"))
            member(e, "DECEASED", "2019-01-01", "date_of_death=DATE '2025-04-04'")
            member(f, "WITHDRAWN", "2021-12-01", "reviewed_at=TIMESTAMP '2022-01-10 10:00:00'")
            agreementAck(f, "2022-01-05 09:00:00")
            member(g, "GUEST", "2024-08-01")
            member(hh, "ACTIVE", "2018-01-01", "anonymized_at=TIMESTAMP '2025-01-01 00:00:00'")
            // i: free text that LOOKS like a status change inside a JSON-escaped reason must never be read as one
            member(i, "ACTIVE", "2022-02-02")
            audit(
                i,
                "2025-01-01 10:00:00",
                "UPDATE",
                snap("ACTIVE"),
                """{"displayNameChanged":false,"emailChanged":false,"status":"ACTIVE","role":null,"reason":"\"status\":\"DONOR\""}""",
            )
            // j: a CREATE audit (no before snapshot) is the creation itself
            member(j, "ACTIVE", "2026-01-05")
            audit(j, "2026-01-05 10:00:00", "CREATE", null, snap("ACTIVE"))
            // k: only the decision on an application is known -> APPLICATION before it is proven
            member(k, "ACTIVE", "2023-09-01", "reviewed_at=TIMESTAMP '2023-09-09 10:00:00'")
            // l: only a friend_since date (no acknowledgment)
            member(l, "FRIEND", "2023-01-01", "friend_since=DATE '2023-02-02'")
            memberBefore = dump("member")
            auditBefore = dump("audit_log_entry")
            (h.flyway(null).migrate().migrationsExecuted >= 1) shouldBe true
        }
        afterSpec { h.close() }

        test("CSV-style ACTIVE member without evidence: one assumed row at the join date, noon UTC") {
            history(a) shouldBe listOf("2020-03-15 12:00:00 | ACTIVE | - | BACKFILL_ASSUMED | null")
        }

        test("applicant: acknowledgment then decision, no anchor needed") {
            history(b) shouldBe
                listOf(
                    "2024-02-10 09:00:00 | APPLICATION | - | BACKFILL_RECORD | null",
                    "2024-02-20 10:00:00 | ACTIVE | APPLICATION | BACKFILL_RECORD | null",
                )
        }

        test("friend rejected back to FRIEND after applying") {
            history(c) shouldBe
                listOf(
                    "2023-05-01 08:00:00 | FRIEND | - | BACKFILL_RECORD | null",
                    "2023-06-01 08:00:00 | APPLICATION | FRIEND | BACKFILL_RECORD | null",
                    "2023-06-10 10:00:00 | FRIEND | APPLICATION | BACKFILL_RECORD | null",
                )
        }

        test("real audit changes: the proven before status anchors the chain, an unchanged-status entry adds nothing") {
            history(d) shouldBe
                listOf(
                    "2021-01-01 12:00:00 | ACTIVE | - | BACKFILL_AUDIT | null",
                    "2025-01-10 10:00:00 | DONOR | ACTIVE | BACKFILL_AUDIT | null",
                    "2025-03-10 10:00:00 | ACTIVE | DONOR | BACKFILL_AUDIT | null",
                    "2025-06-10 10:00:00 | WITHDRAWN | ACTIVE | BACKFILL_AUDIT | null",
                )
        }

        test("DECEASED with a death date and nothing else counts from the death date only") {
            history(e) shouldBe listOf("2025-04-04 12:00:00 | DECEASED | - | BACKFILL_RECORD | null")
        }

        test("a voluntary withdrawal leaves no trace: the chain is closed one second after the last evidence") {
            history(f) shouldBe
                listOf(
                    "2022-01-05 09:00:00 | APPLICATION | - | BACKFILL_RECORD | null",
                    "2022-01-10 10:00:00 | ACTIVE | APPLICATION | BACKFILL_RECORD | null",
                    "2022-01-10 10:00:01 | WITHDRAWN | ACTIVE | BACKFILL_ASSUMED | null",
                )
        }

        test("GUEST and anonymised members get one assumed row each") {
            history(g) shouldBe listOf("2024-08-01 12:00:00 | GUEST | - | BACKFILL_ASSUMED | null")
            history(hh) shouldBe listOf("2018-01-01 12:00:00 | ACTIVE | - | BACKFILL_ASSUMED | null")
        }

        test("a status-looking string inside a JSON-escaped reason is never read as a status change") {
            history(i) shouldBe listOf("2022-02-02 12:00:00 | ACTIVE | - | BACKFILL_ASSUMED | null")
        }

        test("a CREATE audit entry is the creation itself, without an anchor") {
            history(j) shouldBe listOf("2026-01-05 10:00:00 | ACTIVE | - | BACKFILL_AUDIT | null")
        }

        test("only a decision known: APPLICATION is anchored before it (decision is only possible from APPLICATION)") {
            history(k) shouldBe
                listOf(
                    "2023-09-01 12:00:00 | APPLICATION | - | BACKFILL_RECORD | null",
                    "2023-09-09 10:00:00 | ACTIVE | APPLICATION | BACKFILL_RECORD | null",
                )
        }

        test("friend_since without an acknowledgment is a FRIEND row at noon UTC") {
            history(l) shouldBe listOf("2023-02-02 12:00:00 | FRIEND | - | BACKFILL_RECORD | null")
        }

        test("every member's latest row equals member.status, nothing repeats its predecessor, previous_status is the real predecessor") {
            rows(
                "SELECT count(*) FROM member m WHERE NOT EXISTS (SELECT 1 FROM member_status_history x WHERE x.member_id = m.id) " +
                    "OR (SELECT y.status FROM member_status_history y WHERE y.member_id = m.id AND y.effective_from = " +
                    "(SELECT MAX(z.effective_from) FROM member_status_history z WHERE z.member_id = m.id)) <> m.status",
            ) shouldBe listOf(listOf("0"))
            rows("SELECT count(*) FROM member_status_history WHERE previous_status = status") shouldBe listOf(listOf("0"))
            rows(
                "SELECT count(*) FROM member_status_history x WHERE COALESCE(x.previous_status, '-') <> COALESCE((SELECT p.status " +
                    "FROM member_status_history p WHERE p.member_id = x.member_id AND p.effective_from = " +
                    "(SELECT MAX(q.effective_from) FROM member_status_history q WHERE q.member_id = x.member_id " +
                    "AND q.effective_from < x.effective_from)), '-')",
            ) shouldBe listOf(listOf("0"))
        }

        test("member and audit_log_entry are byte-identical after the migration, a re-run is a no-op") {
            dump("member") shouldBe memberBefore
            dump("audit_log_entry") shouldBe auditBefore
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test("the primary key, the CHECK constraints and the foreign key reject wrong rows") {
            fun row(
                member: UUID = a,
                at: String = "2030-01-01 00:00:00",
                status: String = "ACTIVE",
                previous: String? = null,
                source: String = "LIVE",
                recorded: String? = "TIMESTAMP '2030-01-01 00:00:00'",
            ) = "INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at) VALUES " +
                "('$member', TIMESTAMP '$at', '$status', ${previous?.let { "'$it'" } ?: "NULL"}, '$source', ${recorded ?: "NULL"})"
            rejection(row()) shouldBe null
            // duplicate (member_id, effective_from)
            rejection(row())!!.lowercase() shouldContain "pk_member_status_history"
            (rejection(row(at = "2030-01-02 00:00:00", status = "BOGUS")) != null) shouldBe true
            (rejection(row(at = "2030-01-02 00:00:00", previous = "BOGUS")) != null) shouldBe true
            (rejection(row(at = "2030-01-02 00:00:00", source = "BOGUS")) != null) shouldBe true
            // backfill rows carry no recorded_at, live rows must
            (rejection(row(at = "2030-01-02 00:00:00", source = "BACKFILL_AUDIT")) != null) shouldBe true
            (rejection(row(at = "2030-01-02 00:00:00", recorded = null)) != null) shouldBe true
            rejection(row(at = "2030-01-02 00:00:00", source = "BACKFILL_ASSUMED", recorded = null)) shouldBe null
            // unknown member
            (rejection(row(member = UUID.randomUUID(), at = "2030-01-03 00:00:00")) != null) shouldBe true
            // no CASCADE: a member with history cannot be deleted
            (rejection("DELETE FROM member WHERE id = '$a'") != null) shouldBe true
        }

        test("the audit snapshots for tier and regional chapter carry no \"status\" key (the backfill's extraction cannot misread them)") {
            val tier = Json.encodeToString(MemberMembershipTierSnapshot(membershipTierId = "x", familyId = "y", reason = "z"))
            val chapter = Json.encodeToString(MemberRegionalChapterSnapshot(regionalChapterId = "x"))
            listOf(tier, chapter).forEach { it.contains("\"status\"") shouldBe false }
        }
    })

class MemberStatusHistoryMigrationTest : MemberStatusHistoryMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberStatusHistoryMigrationPostgresTest : MemberStatusHistoryMigrationScenarios({ PostgresMigrationHarness() })

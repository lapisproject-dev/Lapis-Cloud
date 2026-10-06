package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.61 -- `V73__encounter_space.sql` on the upgrade path V72 -> V73, on H2 AND on PostgreSQL (same scenarios, see
 * [EncounterSpaceMigrationTest] / [EncounterSpaceMigrationPostgresTest]): data that existed before is byte-identical afterwards,
 * every new constraint bites, the audit CHECK accepts the new literal AND all the old ones, and a re-run is a no-op.
 */
abstract class EncounterSpaceMigrationScenarios(
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

        val creator = UUID.randomUUID()
        val other = UUID.randomUUID()
        val oldRoom = UUID.randomUUID()
        val space = UUID.randomUUID()
        var auditSeq = 0L

        fun member(id: UUID) =
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$id', 'M', 'enc-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
            )

        fun audit(entityType: String): String? {
            auditSeq++
            return rejection(
                "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) VALUES " +
                    "('${UUID.randomUUID()}', ${900_000 + auditSeq}, TIMESTAMP '2026-10-06 10:00:00', '$entityType', " +
                    "'${UUID.randomUUID()}', " +
                    "'CREATE', '${"0".repeat(63)}${auditSeq % 10}')",
            )
        }

        fun spaceInsert(
            id: UUID = UUID.randomUUID(),
            theme: String = "CHURCH",
            mode: String = "SERVICE",
            policy: String = "MEMBERS_ONLY",
            max: String = "NULL",
            by: UUID = creator,
        ) = "INSERT INTO encounter_space (id, title, description, theme_key, mode, guest_policy, max_participants, created_at, " +
            "created_by_member_id) " +
            "VALUES ('$id', 'T', '', '$theme', '$mode', '$policy', $max, TIMESTAMP '2026-10-06 10:00:00', '$by')"

        val roomColumns =
            "id, title, description, livekit_room_name, created_by_member_id, created_at, ended_at, max_participants, allow_federation_guests, meeting_id"

        fun dump(
            table: String,
            columns: String = "*",
        ) = rows("SELECT $columns FROM $table ORDER BY 1, 2").joinToString("\n")

        lateinit var roomBefore: String
        lateinit var participationBefore: String
        lateinit var auditBefore: String
        lateinit var memberBefore: String

        beforeSpec {
            h = newHarness()
            h.flyway("72").migrate()
            member(creator)
            member(other)
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$oldRoom', 'Alt', 'beschreibung', 'lc-$oldRoom', '$creator', TIMESTAMP '2026-09-01 10:00:00', 25)",
            )
            exec(
                "INSERT INTO conference_participation (id, room_id, member_id, role, joined_at, left_at) VALUES " +
                    "('${UUID.randomUUID()}', '$oldRoom', '$other', 'PARTICIPANT', TIMESTAMP '2026-09-01 10:01:00', TIMESTAMP '2026-09-01 11:00:00')",
            )
            audit("POLL") shouldBe null
            audit("CONFERENCE_ROOM") shouldBe null
            roomBefore = dump("conference_room", roomColumns)
            participationBefore = dump("conference_participation")
            auditBefore = dump("audit_log_entry")
            memberBefore = dump("member")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("rows that existed before are byte-identical afterwards; the old room has no encounter space; the ordinary history is kept") {
            dump("conference_room", roomColumns) shouldBe roomBefore
            dump("conference_participation") shouldBe participationBefore
            dump("audit_log_entry") shouldBe auditBefore
            dump("member") shouldBe memberBefore
            rows("SELECT encounter_space_id FROM conference_room WHERE id = '$oldRoom'") shouldBe listOf(listOf("null"))
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test("encounter_space: the CHECKs reject an unknown theme, mode and guest policy and a maximum below 2 (NULL and 2 are fine)") {
            rejection(spaceInsert(id = space)) shouldBe null
            (rejection(spaceInsert(theme = "MOSQUE")) != null) shouldBe true
            (rejection(spaceInsert(mode = "CONCERT")) != null) shouldBe true
            (rejection(spaceInsert(policy = "EVERYBODY")) != null) shouldBe true
            (rejection(spaceInsert(max = "1")) != null) shouldBe true
            (rejection(spaceInsert(max = "0")) != null) shouldBe true
            rejection(spaceInsert(max = "2")) shouldBe null
            rejection(spaceInsert(policy = "MEMBERS_AND_GUESTS")) shouldBe null
            (rejection(spaceInsert(by = UUID.randomUUID())) != null) shouldBe true
        }

        test("encounter_space_role: composite key, role CHECK, both foreign keys, one index on member_id") {
            fun role(
                s: UUID = space,
                m: UUID = other,
                r: String = "PULPIT",
            ) = "INSERT INTO encounter_space_role (space_id, member_id, role) VALUES ('$s', '$m', '$r')"
            rejection(role()) shouldBe null
            rejection(role())!!.lowercase() shouldContain "pk_encounter_space_role"
            // one role per member per space: a second ROLE for the same pair is the same key
            (rejection(role(r = "STEWARD")) != null) shouldBe true
            (rejection(role(m = creator, r = "DEACON")) != null) shouldBe true
            (rejection(role(s = UUID.randomUUID(), m = creator)) != null) shouldBe true
            (rejection(role(m = UUID.randomUUID())) != null) shouldBe true
            // a member who holds an office cannot be deleted (no CASCADE)
            (rejection("DELETE FROM member WHERE id = '$other'") != null) shouldBe true
            rows("SELECT count(*) FROM encounter_space_role") shouldBe listOf(listOf("1"))
        }

        test("encounter_consent_acknowledgment: composite key, foreign key, and NO room/space/timestamp column -- only a DATE") {
            fun consent(
                m: UUID = other,
                v: String = "v1",
            ) = "INSERT INTO encounter_consent_acknowledgment (member_id, consent_version, consent_sha256, acknowledged_on) VALUES " +
                "('$m', '$v', '${"a".repeat(64)}', DATE '2026-10-06')"
            rejection(consent()) shouldBe null
            rejection(consent())!!.lowercase() shouldContain "pk_encounter_consent_ack"
            rejection(consent(v = "v2")) shouldBe null
            (rejection(consent(m = UUID.randomUUID())) != null) shouldBe true
            rows(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'encounter_consent_acknowledgment' ORDER BY column_name",
            ).map { it[0].lowercase() } shouldBe listOf("acknowledged_on", "consent_sha256", "consent_version", "member_id")
            rows(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'encounter_consent_acknowledgment' AND column_name = 'acknowledged_on'",
            ).single()[0].uppercase() shouldBe "DATE"
        }

        test("conference_room.encounter_space_id: nullable foreign key; a space with a session cannot be deleted") {
            val session = UUID.randomUUID()
            val insert =
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants, encounter_space_id) " +
                    "VALUES ('$session', 'Sitzung', '', 'lc-$session', '$creator', TIMESTAMP '2026-10-06 10:00:00', 25, %s)"
            (rejection(insert.format("'${UUID.randomUUID()}'")) != null) shouldBe true
            rejection(insert.format("'$space'")) shouldBe null
            (rejection("DELETE FROM encounter_space WHERE id = '$space'") != null) shouldBe true
            rows("SELECT count(*) FROM conference_room WHERE encounter_space_id IS NULL") shouldBe listOf(listOf("1"))
        }

        test("audit_log_entry.entity_type: the new literal and every old one are accepted, an unknown one is rejected") {
            audit("ENCOUNTER_SPACE") shouldBe null
            listOf("POLL", "MEMBERSHIP_TIER", "JOURNAL_ENTRY", "CONFERENCE_ROOM", "MEMBER", "REGIONAL_CHAPTER_OFFICER").forEach {
                audit(it) shouldBe
                    null
            }
            (audit("ENCOUNTER_SPACES") != null) shouldBe true
            (audit("BOGUS") != null) shouldBe true
        }
    })

class EncounterSpaceMigrationTest : EncounterSpaceMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EncounterSpaceMigrationPostgresTest : EncounterSpaceMigrationScenarios({ PostgresMigrationHarness() })

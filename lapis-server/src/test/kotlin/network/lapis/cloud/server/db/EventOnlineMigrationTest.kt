package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.113 -- `V93__event_online_participation.sql` on the upgrade path V92 -> V93, on H2 AND on PostgreSQL: an event with a link becomes
 * EXTERNAL_LINK and every other one NONE (backfill), a "public link" without a link (which the form never stored) is made consistent so the
 * new constraint can never fail an existing instance, `chk_event_online_kind`/`chk_event_online_shape`/`chk_event_venue` accept exactly the
 * intended combinations, the encounter-space reference is a real foreign key, `event_conference` ties status, room and window together and
 * allows one conference per room, and a re-run is a no-op.
 */
abstract class EventOnlineMigrationScenarios(
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
        val linkEvent = UUID.randomUUID()
        val plainEvent = UUID.randomUUID()
        val oddEvent = UUID.randomUUID()
        val space = UUID.randomUUID()

        fun eventInsert(
            id: UUID = UUID.randomUUID(),
            location: String? = "Saal",
            url: String? = null,
            urlPublic: Boolean = false,
            kind: String? = null,
            spaceId: UUID? = null,
        ): String {
            val columns =
                "id, slug, title, description, location_text, online_url, online_url_public, starts_at, ends_at, " +
                    "fee_amount, fee_currency, " +
                    "status, visibility, created_at, created_by" + (if (kind != null) ", online_kind" else "") +
                    (if (spaceId != null) ", encounter_space_id" else "")
            val values =
                "'$id', 'e-$id', 'T', 'D', ${location?.let { "'$it'" } ?: "NULL"}, ${url?.let { "'$it'" } ?: "NULL"}, $urlPublic, " +
                    "TIMESTAMP '2030-06-15 18:00:00', TIMESTAMP '2030-06-15 20:00:00', 0, 'EUR', 'DRAFT', 'PUBLIC', " +
                    "TIMESTAMP '2026-01-01 00:00:00', '$memberId'" +
                    (if (kind != null) ", '$kind'" else "") + (if (spaceId != null) ", '$spaceId'" else "")
            return "INSERT INTO event ($columns) VALUES ($values)"
        }

        fun conferenceInsert(
            eventId: UUID,
            status: String,
            room: UUID? = null,
            opens: String = "2030-06-15 21:45:00",
            closes: String = "2030-06-16 01:00:00",
        ) = "INSERT INTO event_conference (event_id, conference_room_id, livekit_room_name, status, claim_token, claimed_at, " +
            "window_opens_at, " +
            "window_closes_at, created_at) VALUES ('$eventId', ${room?.let {
                "'$it'"
            } ?: "NULL"}, ${room?.let { "'lc-$it'" } ?: "NULL"}, '$status', " +
            "'${UUID.randomUUID()}', TIMESTAMP '2030-06-15 21:50:00', TIMESTAMP '$opens', TIMESTAMP '$closes', TIMESTAMP '2030-06-15 21:50:00')"

        fun roomInsert(id: UUID) =
            "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, max_participants) " +
                "VALUES ('$id', 'R', '', 'lc-$id', '$memberId', TIMESTAMP '2030-06-15 21:50:00', 25)"

        beforeSpec {
            h = newHarness()
            h.flyway("92").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'ev-online-mig-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(eventInsert(id = linkEvent, url = "https://meet.example/x", urlPublic = true))
            exec(eventInsert(id = plainEvent))
            // A state the form never stores (a public link flag without a link): the migration makes it consistent instead of failing on it.
            exec(eventInsert(id = oddEvent, urlPublic = true))
            exec(
                "INSERT INTO encounter_space (id, title, description, theme_key, mode, guest_policy, created_at, created_by_member_id) " +
                    "VALUES ('$space', 'T', '', 'CHURCH', 'SERVICE', 'MEMBERS_ONLY', TIMESTAMP '2026-10-06 10:00:00', '$memberId')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("backfill: an event with a link is EXTERNAL_LINK (its link and its public flag untouched), every other one is NONE") {
            rows(
                "SELECT online_kind, online_url, CASE WHEN online_url_public THEN 'Y' ELSE 'N' END FROM event WHERE id = '$linkEvent'",
            ).single() shouldBe
                listOf("EXTERNAL_LINK", "https://meet.example/x", "Y")
            rows("SELECT online_kind, encounter_space_id FROM event WHERE id = '$plainEvent'").single() shouldBe listOf("NONE", "null")
        }

        test("a public flag without a link is made consistent (so the new shape constraint cannot fail an existing instance)") {
            rows(
                "SELECT online_kind, CASE WHEN online_url_public THEN 'Y' ELSE 'N' END FROM event WHERE id = '$oddEvent'",
            ).single() shouldBe
                listOf("NONE", "N")
        }

        test("chk_event_online_kind accepts the four kinds and rejects anything else") {
            rejection(eventInsert(kind = "NONE")) shouldBe null
            rejection(eventInsert(kind = "LAPIS_CONFERENCE")) shouldBe null
            (rejection(eventInsert(kind = "ZOOM")) != null) shouldBe true
        }

        test("chk_event_online_shape: an encounter entry needs its space, every other kind must not carry one") {
            rejection(eventInsert(kind = "LAPIS_ENCOUNTER", spaceId = space)) shouldBe null
            (rejection(eventInsert(kind = "LAPIS_ENCOUNTER")) != null) shouldBe true
            (rejection(eventInsert(kind = "LAPIS_CONFERENCE", spaceId = space)) != null) shouldBe true
            (rejection(eventInsert(kind = "NONE", spaceId = space)) != null) shouldBe true
        }

        test("chk_event_online_shape: only the external kind carries a link, and only it may be public") {
            rejection(eventInsert(kind = "EXTERNAL_LINK", url = "https://meet.example/y", urlPublic = true)) shouldBe null
            (rejection(eventInsert(kind = "LAPIS_CONFERENCE", url = "https://meet.example/y")) != null) shouldBe true
            (rejection(eventInsert(kind = "NONE", url = "https://meet.example/y")) != null) shouldBe true
            (rejection(eventInsert(kind = "LAPIS_CONFERENCE", urlPublic = true)) != null) shouldBe true
        }

        test("chk_event_venue: a Lapis online kind is a venue of its own; without a place, a link or such a kind it is still refused") {
            rejection(eventInsert(location = null, kind = "LAPIS_CONFERENCE")) shouldBe null
            rejection(eventInsert(location = null, kind = "LAPIS_ENCOUNTER", spaceId = space)) shouldBe null
            rejection(eventInsert(location = null, kind = "EXTERNAL_LINK", url = "https://meet.example/z")) shouldBe null
            (rejection(eventInsert(location = null, kind = "NONE")) != null) shouldBe true
        }

        test("encounter_space_id is a real foreign key") {
            (rejection(eventInsert(kind = "LAPIS_ENCOUNTER", spaceId = UUID.randomUUID())) != null) shouldBe true
        }

        test(
            "event_conference: status vocabulary, ACTIVE needs a room, the window must be ordered, one conference per event and per room",
        ) {
            val e1 = UUID.randomUUID()
            val e2 = UUID.randomUUID()
            val e3 = UUID.randomUUID()
            val room = UUID.randomUUID()
            exec(eventInsert(id = e1, kind = "LAPIS_CONFERENCE"))
            exec(eventInsert(id = e2, kind = "LAPIS_CONFERENCE"))
            exec(eventInsert(id = e3, kind = "LAPIS_CONFERENCE"))
            exec(roomInsert(room))
            rejection(conferenceInsert(e1, status = "PENDING")) shouldBe null
            (rejection(conferenceInsert(e1, status = "PENDING")) != null) shouldBe true // one row per event
            (rejection(conferenceInsert(e2, status = "BOGUS")) != null) shouldBe true
            (rejection(conferenceInsert(e2, status = "ACTIVE")) != null) shouldBe true // ACTIVE without a room
            (
                rejection(
                    conferenceInsert(e2, status = "PENDING", opens = "2030-06-16 01:00:00", closes = "2030-06-16 01:00:00"),
                ) != null
            ) shouldBe
                true
            rejection(conferenceInsert(e2, status = "ACTIVE", room = room)) shouldBe null
            (rejection(conferenceInsert(e3, status = "ACTIVE", room = room)) != null) shouldBe true // one conference per room
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class EventOnlineMigrationTest : EventOnlineMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventOnlineMigrationPostgresTest : EventOnlineMigrationScenarios({ PostgresMigrationHarness() })

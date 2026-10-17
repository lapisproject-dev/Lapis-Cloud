package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import network.lapis.cloud.server.events.EventCaptureAcknowledgments
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- [EventCapturePersonalData]: the export lists the member's OWN proofs (event title, version label, day) and nobody else's, never a hash or a guest
 * key; the proof is retained on erasure (the organization's accountability record); deleting an event deletes its proofs first.
 */
class EventCapturePersonalDataTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        lateinit var organizer: Uuid

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            organizer = fx.member(name = "Veranstalterin")
        }
        afterSpec { fx.cleanUp() }

        fun proof(
            event: Uuid,
            member: Uuid?,
            guestKey: String? = null,
            version: String = "v1",
        ) = transaction {
            if (member != null) {
                EventCaptureAcknowledgments.recordMember(
                    eventId = event,
                    memberId = member,
                    consentVersion = version,
                    consentSha256 = "a".repeat(64),
                    today = LocalDate(2030, 6, 15),
                )
            } else {
                EventCaptureAcknowledgments.recordGuest(
                    eventId = event,
                    guestKey = guestKey!!,
                    consentVersion = version,
                    consentSha256 = "a".repeat(64),
                    today = LocalDate(2030, 6, 15),
                )
            }
        }

        test("the contributor covers exactly the proof table") {
            EventCapturePersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("event_capture_acknowledgment")
        }

        test("export: only the member's own proofs -- event title, version, day -- never a hash, a guest key or somebody else's row") {
            val event = fx.event(createdBy = organizer, title = "Mitgliederversammlung")
            val mine = fx.member()
            val other = fx.member()
            proof(event, mine)
            proof(event, other, version = "v9")
            proof(event, null, guestKey = "f".repeat(64))
            val exported = transaction { EventCapturePersonalData.exportMember(mine) }
            val rows = exported["acknowledgments"]!!.jsonArray
            rows.size shouldBe 1
            rows
                .single()
                .jsonObject["eventTitle"]!!
                .jsonPrimitive.content shouldBe "Mitgliederversammlung"
            rows
                .single()
                .jsonObject["consentVersion"]!!
                .jsonPrimitive.content shouldBe "v1"
            rows
                .single()
                .jsonObject["acknowledgedOn"]!!
                .jsonPrimitive.content shouldBe "2030-06-15"
            exported.toString().contains("a".repeat(64)) shouldBe false
            exported.toString().contains("f".repeat(64)) shouldBe false
            exported["truncated"]!!.jsonPrimitive.content shouldBe "false"
        }

        test("erase retains the proof whatever the mode (accountability, Art. 5(2) and 7(1) GDPR) and touches nothing") {
            val event = fx.event(createdBy = organizer)
            val member = fx.member()
            proof(event, member)
            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val outcome = transaction { EventCapturePersonalData.eraseMember(memberId = member, mode = mode) }.single()
                outcome.table shouldBe "event_capture_acknowledgment"
                outcome.rowsRetained shouldBe 1
                outcome.rowsDeleted shouldBe 0
                outcome.rowsAnonymized shouldBe 0
                (outcome.retentionReason?.isNotBlank() ?: false) shouldBe true
            }
            transaction {
                EventCaptureAcknowledgmentTable.selectAll().where { EventCaptureAcknowledgmentTable.eventId eq event }.count()
            } shouldBe
                1L
        }

        test("deleting an event deletes its proofs first (no cascade) and leaves the proofs of other events") {
            val doomed = fx.event(createdBy = organizer)
            val kept = fx.event(createdBy = organizer)
            val member = fx.member()
            proof(doomed, member)
            proof(kept, member)
            transaction { EventStore.deleteEventHard(doomed) }
            fx.eventIds.remove(doomed)
            transaction {
                EventCaptureAcknowledgmentTable.selectAll().where { EventCaptureAcknowledgmentTable.eventId eq doomed }.count()
            } shouldBe
                0L
            transaction {
                EventCaptureAcknowledgmentTable.selectAll().where { EventCaptureAcknowledgmentTable.eventId eq kept }.count()
            } shouldBe
                1L
        }
    })

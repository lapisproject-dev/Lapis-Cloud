package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.EventOnlineKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * V1.9.204 -- the registrant message in the data-subject export and in the erasure: the member's own message is exported, erasure sets it to
 * NULL (the row itself stays, as before) and reports it as anonymised, and another registrant's message is never touched.
 */
class EventRegistrationMessagePersonalDataTest :
    FunSpec({
        val fx = EventOnlineFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction {
                EventRegistrationTable.deleteWhere { eventId inList fx.eventIds }
                EventTable.deleteWhere { id inList fx.eventIds }
            }
            fx.cleanUp()
        }

        fun setMessage(
            registration: kotlin.uuid.Uuid,
            text: String,
        ) = transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq registration }) { it[message] = text } }

        test("the export carries the member's own message, in a fixed order") {
            val creator = fx.member(role = AccountRole.BOARD)
            val member = fx.member()
            val e1 = fx.event(createdBy = creator, kind = EventOnlineKind.NONE)
            val e2 = fx.event(createdBy = creator, kind = EventOnlineKind.NONE)
            setMessage(fx.registration(eventId = e1, memberId = member), "Erste Nachricht")
            fx.registration(eventId = e2, memberId = member)
            val exported = transaction { EventPersonalData.exportMember(member) }
            val rows = exported["registrations"]!!.jsonArray.map { it.jsonObject }
            rows.size shouldBe 2
            rows.mapNotNull { it["message"]?.jsonPrimitive?.content } shouldBe listOf("Erste Nachricht")
            val again = transaction { EventPersonalData.exportMember(member) }["registrations"]!!.jsonArray.map { it.jsonObject["id"] }
            again shouldBe rows.map { it["id"] }
        }

        test("erasure sets the message to NULL, keeps the row, reports the anonymised count and spares other people's messages") {
            val creator = fx.member(role = AccountRole.BOARD)
            val member = fx.member()
            val other = fx.member()
            val event = fx.event(createdBy = creator, kind = EventOnlineKind.NONE)
            val mine = fx.registration(eventId = event, memberId = member)
            val theirs = fx.registration(eventId = event, memberId = other)
            setMessage(mine, "Privat")
            setMessage(theirs, "Fremd")
            val outcomes = transaction { EventPersonalData.eraseMember(memberId = member, mode = ErasureMode.ANONYMIZE) }
            val reg = outcomes.single { it.table == "event_registration" }
            reg.rowsAnonymized shouldBe 1
            reg.rowsRetained shouldBe 1

            fun messageOf(id: kotlin.uuid.Uuid) =
                transaction {
                    EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq id }.single()[EventRegistrationTable.message]
                }
            messageOf(mine) shouldBe null
            messageOf(theirs) shouldBe "Fremd"
        }
    })

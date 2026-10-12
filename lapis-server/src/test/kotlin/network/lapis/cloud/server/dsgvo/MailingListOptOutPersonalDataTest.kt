package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.mail.newsletter.DynamicFixture
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.111 -- the opt-outs of dynamic lists are part of the Art. 15 export and the Art. 17 erasure of a member. */
class MailingListOptOutPersonalDataTest :
    FunSpec({
        val fx = DynamicFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("the opt-out table is covered by the communication contributor") {
            (CommunicationPersonalData.coveredTables.contains(MailingListOptOutTable)) shouldBe true
        }

        test("the export lists the member's own opt-outs, the erasure deletes them and reports the count") {
            val c = fx.committee()
            val sender = fx.member()
            val subject = fx.member()
            val other = fx.member()
            fx.seat(committeeId = c, memberId = subject)
            fx.seat(committeeId = c, memberId = other)
            val list1 = fx.dynamicList(committeeId = c, createdBy = sender)
            val list2 = fx.dynamicList(committeeId = c, createdBy = sender)
            fx.optOut(listId = list1, memberId = subject)
            fx.optOut(listId = list2, memberId = subject)
            fx.optOut(listId = list1, memberId = other)

            val export = transaction { CommunicationPersonalData.exportMember(subject) }
            val optOuts = export["mailingListOptOuts"]!!.jsonArray
            optOuts.size shouldBe 2
            optOuts.map { (it.jsonObject["mailingListId"] as JsonPrimitive).content }.toSet() shouldBe
                setOf(list1.toString(), list2.toString())
            optOuts.forEach { (it.jsonObject["optedOutAt"] as JsonPrimitive).content.isNotBlank() shouldBe true }

            val outcomes =
                transaction {
                    CommunicationPersonalData.eraseMember(
                        memberId = subject,
                        mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
                    )
                }
            outcomes.single { it.table == "mailing_list_opt_out" }.rowsDeleted shouldBe 2
            transaction { MailingListOptOutTable.selectAll().where { MailingListOptOutTable.memberId eq subject }.count() } shouldBe 0L
            transaction { MailingListOptOutTable.selectAll().where { MailingListOptOutTable.memberId eq other }.count() } shouldBe 1L
        }
    })

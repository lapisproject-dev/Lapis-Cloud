package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.CarpoolMessageTable
import network.lapis.cloud.server.db.generated.CarpoolMessageThreadTable
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.shared.domain.CarpoolMessageSide
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.125 -- the GDPR contributor of the carpool centre covers BOTH tables: the export lists the person's own rides and the meeting note of their
 * own offers, and only the NUMBER of passengers on their offers, never the name of a third person; the erasure removes the person's own rides
 * (their seats are released) and their offers together with everybody else's participations on them.
 */
class CarpoolPersonalDataTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        test("it covers all four carpool tables (PersonalDataCoverageTest relies on this)") {
            CarpoolPersonalData.coveredTables shouldBe
                setOf(CarpoolPostingTable, CarpoolParticipationTable, CarpoolMessageTable, CarpoolMessageThreadTable)
        }

        test("the export names the own rides and the passenger COUNT of own offers, never a foreign name") {
            val driver = data.member(label = "pd-driver", displayName = "Fahrerin Export")
            val guest = data.member(label = "pd-guest", status = MemberStatus.GUEST, displayName = "Dritte Person Geheim")
            val offer = data.posting(author = driver, seats = 2, meetingNote = "Parkplatz Süd")
            data.participation(postingId = offer, personId = guest, status = CarpoolParticipationStatus.CONFIRMED)

            val driverExport = transaction { CarpoolPersonalData.exportMember(driver) }.jsonObject
            val posting =
                driverExport
                    .getValue("carpoolPostings")
                    .jsonArray
                    .single()
                    .jsonObject
            posting.getValue("meetingNote").jsonPrimitive.content shouldBe "Parkplatz Süd"
            posting.getValue("participantCount").jsonPrimitive.content shouldBe "1"
            driverExport.toString() shouldNotContain "Dritte Person Geheim"

            val guestExport = transaction { CarpoolPersonalData.exportMember(guest) }.jsonObject
            val ride =
                guestExport
                    .getValue("carpoolParticipations")
                    .jsonArray
                    .single()
                    .jsonObject
            ride.getValue("role").jsonPrimitive.content shouldBe "PASSENGER"
            ride.getValue("status").jsonPrimitive.content shouldBe "CONFIRMED"
            ride.getValue("fromPlace").jsonPrimitive.content shouldContain "Braunschweig"
        }

        test("the erasure of a passenger releases the seat; the erasure of a driver removes the offer and the foreign participations") {
            val driver = data.member(label = "pd2-driver")
            val guest = data.member(label = "pd2-guest", status = MemberStatus.GUEST)
            val other = data.member(label = "pd2-other", status = MemberStatus.GUEST)
            val offer = data.posting(author = driver, seats = 2)
            data.participation(postingId = offer, personId = guest, status = CarpoolParticipationStatus.CONFIRMED)
            data.participation(postingId = offer, personId = other, status = CarpoolParticipationStatus.OPEN)

            val guestOutcome = transaction { CarpoolPersonalData.eraseMember(memberId = guest, mode = ErasureMode.ANONYMIZE) }
            guestOutcome.first { it.table == "carpool_participation" }.rowsDeleted shouldBe 1
            data.participationRows(offer).size shouldBe 1

            val driverOutcome = transaction { CarpoolPersonalData.eraseMember(memberId = driver, mode = ErasureMode.ANONYMIZE) }
            driverOutcome.first { it.table == "carpool_participation" }.rowsDeleted shouldBe 1
            driverOutcome.first { it.table == "carpool_posting" }.rowsDeleted shouldBe 1
            data.participationRows(offer).size shouldBe 0
        }

        test(
            "V1.9.139: the export lists only the person's OWN sent ride messages; the erasure removes every history the person is part of",
        ) {
            val driver = data.member(label = "pd3-driver", displayName = "Fahrer Nachrichten")
            val guest = data.member(label = "pd3-guest", status = MemberStatus.GUEST, displayName = "Gast Nachrichten")
            val offer = data.posting(author = driver, seats = 2)
            val pid = data.participation(postingId = offer, personId = guest, status = CarpoolParticipationStatus.CONFIRMED)
            data.message(participationId = pid, side = CarpoolMessageSide.PASSENGER, body = "Text des Gastes")
            data.message(participationId = pid, side = CarpoolMessageSide.DRIVER, body = "Text des Fahrers")
            transaction { CarpoolMessageThreadTable.insert { it[participationId] = pid } }

            fun sent(memberId: kotlin.uuid.Uuid) =
                transaction { CarpoolPersonalData.exportMember(memberId) }
                    .jsonObject
                    .getValue("carpoolMessagesSent")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("body")
                            .jsonPrimitive.content
                    }
            sent(guest) shouldBe listOf("Text des Gastes")
            sent(driver) shouldBe listOf("Text des Fahrers")
            transaction { CarpoolPersonalData.exportMember(guest) }.toString() shouldNotContain "Text des Fahrers"
            transaction { CarpoolPersonalData.exportMember(guest) }.toString() shouldNotContain "Fahrer Nachrichten"

            val outcome = transaction { CarpoolPersonalData.eraseMember(memberId = guest, mode = ErasureMode.ANONYMIZE) }
            outcome.first { it.table == "carpool_message" }.rowsDeleted shouldBe 2
            outcome.first { it.table == "carpool_message_thread" }.rowsDeleted shouldBe 1
            data.messageCount(pid) shouldBe 0
            data.threadCount(pid) shouldBe 0

            // as a driver: the histories of the own offers go as well
            val driver2 = data.member(label = "pd4-driver")
            val guest2 = data.member(label = "pd4-guest", status = MemberStatus.GUEST)
            val pid2 =
                data.participation(
                    postingId = data.posting(author = driver2),
                    personId = guest2,
                    status = CarpoolParticipationStatus.CONFIRMED,
                )
            data.message(participationId = pid2, side = CarpoolMessageSide.DRIVER, body = "weg")
            transaction { CarpoolPersonalData.eraseMember(memberId = driver2, mode = ErasureMode.ANONYMIZE) }
            data.messageCount(pid2) shouldBe 0
        }
    })

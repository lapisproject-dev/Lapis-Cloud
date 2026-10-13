package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
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

        test("it covers both carpool tables (PersonalDataCoverageTest relies on this)") {
            CarpoolPersonalData.coveredTables shouldBe setOf(CarpoolPostingTable, CarpoolParticipationTable)
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
    })

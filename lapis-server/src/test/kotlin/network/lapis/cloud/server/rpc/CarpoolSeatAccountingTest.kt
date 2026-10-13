package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.dsgvo.CarpoolPersonalData
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.125 -- seat accounting through the real service: the free seats follow the confirmations, the last seat cannot be taken twice, an update
 * cannot undercut the confirmed seats (it can reach them exactly), a type change with participants is refused, a withdrawal and a GDPR erasure
 * release a seat, and deleting the offer removes every participation.
 */
class CarpoolSeatAccountingTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        fun input(
            seats: Int?,
            type: CarpoolPostingType = CarpoolPostingType.OFFER,
        ) = CarpoolPostingInput(
            type = type,
            fromPlace = "Braunschweig",
            toPlace = "Hannover",
            departureDate = data.today().plus(DatePeriod(days = 10)),
            seatsOffered = seats,
        )

        test(
            "seatsFree follows the confirmations; a confirmation at zero free seats is refused; update below the confirmed number is refused, to it is allowed",
        ) {
            carpoolTestApplication {
                val driver = data.member(label = "seat-driver")
                val offer = data.posting(author = driver, seats = 2)
                val guests = (1..3).map { data.member(label = "seat-guest$it", status = MemberStatus.GUEST) }
                val asked = guests.map { call(member = it) { requestParticipation(offer.toString()) } }
                call(member = driver) { confirmParticipation(asked[0].id) }
                call(member = driver) { confirmParticipation(asked[1].id) }
                call(member = guests[2]) { listPostings(null) }.single { it.id == offer.toString() }.seatsFree shouldBe 0
                shouldThrow<ConflictException> { call(member = driver) { confirmParticipation(asked[2].id) } }.message shouldBe
                    "Für diese Fahrt ist kein Platz mehr frei."
                shouldThrow<ConflictException> {
                    call(
                        member = driver,
                    ) { updatePosting(id = offer.toString(), input = input(seats = 1)) }
                }.message shouldBe
                    "Es sind bereits 2 Mitfahrten bestätigt."
                call(member = driver) { updatePosting(id = offer.toString(), input = input(seats = 2)) }.seatsFree shouldBe 0
                call(member = driver) { updatePosting(id = offer.toString(), input = input(seats = 4)) }.seatsFree shouldBe 2
                // A withdrawal releases the seat.
                call(member = guests[0]) { withdrawParticipation(asked[0].id) }
                call(member = driver) { listMyPostings() }.single { it.id == offer.toString() }.seatsFree shouldBe 3
            }
        }

        test("a type change OFFER -> REQUEST with open or confirmed participants is refused") {
            carpoolTestApplication {
                val driver = data.member(label = "type-driver")
                val offer = data.posting(author = driver, seats = 2)
                val guest = data.member(label = "type-guest", status = MemberStatus.GUEST)
                call(member = guest) { requestParticipation(offer.toString()) }
                shouldThrow<ConflictException> {
                    call(
                        member = driver,
                    ) { updatePosting(id = offer.toString(), input = input(seats = null, type = CarpoolPostingType.REQUEST)) }
                }
            }
        }

        test("a GDPR erasure of a confirmed passenger releases the seat; erasing the driver removes the offer with every participation") {
            carpoolTestApplication {
                val driver = data.member(label = "erase-driver")
                val offer = data.posting(author = driver, seats = 1)
                val guest = data.member(label = "erase-guest", status = MemberStatus.GUEST)
                val asked = call(member = guest) { requestParticipation(offer.toString()) }
                call(member = driver) { confirmParticipation(asked.id) }
                transaction { CarpoolPersonalData.eraseMember(memberId = guest, mode = ErasureMode.ANONYMIZE) }
                call(member = driver) { listMyPostings() }.single { it.id == offer.toString() }.seatsFree shouldBe 1
                val guest2 = data.member(label = "erase-guest2", status = MemberStatus.GUEST)
                call(member = guest2) { requestParticipation(offer.toString()) }
                transaction { CarpoolPersonalData.eraseMember(memberId = driver, mode = ErasureMode.ANONYMIZE) }
                transaction { CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq offer }.count() } shouldBe 0L
                data.participationRows(offer).size shouldBe 0
            }
        }

        test("deleting an offer takes its participations along and the meeting note is limited to 200 characters") {
            carpoolTestApplication {
                val driver = data.member(label = "del-driver")
                val offer = data.posting(author = driver, seats = 2)
                val guest = data.member(label = "del-guest", status = MemberStatus.GUEST)
                call(member = guest) { requestParticipation(offer.toString()) }
                shouldThrow<ConflictException> {
                    call(
                        member = driver,
                    ) { setMeetingNote(postingId = offer.toString(), note = "x".repeat(201)) }
                }
                call(member = driver) { setMeetingNote(postingId = offer.toString(), note = "  Bahnhof 7:30  ") }.meetingNote shouldBe
                    "Bahnhof 7:30"
                call(member = driver) { setMeetingNote(postingId = offer.toString(), note = "   ") }.meetingNote shouldBe null
                call(member = driver) { deletePosting(offer.toString()) }
                data.participationRows(offer).size shouldBe 0
                call(member = guest) { listMyParticipations() }.size shouldBe 0
            }
        }

        test("getPassengerHint counts only confirmed passengers on the caller's own offers, as numbers") {
            carpoolTestApplication {
                val driver = data.member(label = "hint-driver")
                val offer = data.posting(author = driver, seats = 3, daysAhead = 5)
                val offer2 = data.posting(author = driver, seats = 3, daysAhead = 6)
                val today = data.today()
                call(
                    member = driver,
                ) { getPassengerHint(from = today, to = today.plus(DatePeriod(days = 20))) }.confirmedPassengers shouldBe
                    0
                repeat(2) {
                    val guest = data.member(label = "hint-guest$it", status = MemberStatus.GUEST)
                    call(member = driver) { confirmParticipation(call(member = guest) { requestParticipation(offer.toString()) }.id) }
                }
                val hint = call(member = driver) { getPassengerHint(from = today, to = today.plus(DatePeriod(days = 20))) }
                hint.confirmedPassengers shouldBe 2
                hint.multipleTrips shouldBe false
                val guest3 = data.member(label = "hint-guest3", status = MemberStatus.GUEST)
                call(member = driver) { confirmParticipation(call(member = guest3) { requestParticipation(offer2.toString()) }.id) }
                call(member = driver) { getPassengerHint(from = today, to = today.plus(DatePeriod(days = 20))) }.multipleTrips shouldBe true
                // Somebody else's offers never count.
                call(member = data.member(label = "hint-other")) {
                    getPassengerHint(from = today, to = today.plus(DatePeriod(days = 20)))
                }.confirmedPassengers shouldBe
                    0
                shouldThrow<ConflictException> {
                    call(
                        member = driver,
                    ) { getPassengerHint(from = today, to = today.plus(DatePeriod(days = 63))) }
                }
            }
        }
    })

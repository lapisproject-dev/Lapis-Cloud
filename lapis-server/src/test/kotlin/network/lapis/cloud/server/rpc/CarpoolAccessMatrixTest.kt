package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.CarpoolPrivacy
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.uuid.Uuid

/**
 * V1.9.125 -- the access matrix of the carpool centre after the change of its access rule: every status against ALL sixteen RPCs. The six
 * methods of the passenger allowlist pass the guard for ACTIVE, GUEST and FRIEND; the other ten stay ACTIVE-only. A call that passes the guard
 * fails afterwards with something else (a bogus id: not found, ...) -- the test only asks "Forbidden or not". Also the shape of the feed a
 * guest sees (offers only, no free text, no meeting note until confirmed).
 */
class CarpoolAccessMatrixTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        val allowlist =
            setOf(
                "listPostings",
                "requestParticipation",
                "confirmParticipation",
                "declineParticipation",
                "withdrawParticipation",
                "listMyParticipations",
            )

        fun calls(): Map<String, suspend CarpoolService.() -> Any?> {
            val bogus = Uuid.random().toString()
            val today = data.today()
            val pastInput =
                CarpoolPostingInput(
                    type = CarpoolPostingType.OFFER,
                    fromPlace = "Braunschweig",
                    toPlace = "Hannover",
                    departureDate = today.plus(DatePeriod(years = -5)),
                    seatsOffered = 2,
                )
            return linkedMapOf(
                "listPostings" to { listPostings(null) },
                "listMyPostings" to { listMyPostings() },
                "createPosting" to { createPosting(pastInput) },
                "updatePosting" to { updatePosting(id = bogus, input = pastInput) },
                "deletePosting" to { deletePosting(bogus) },
                "contactAuthor" to { contactAuthor(postingId = bogus, message = "Hallo") },
                "requestParticipation" to { requestParticipation(bogus) },
                "findInvitee" to { findInvitee(firstName = "Maria", lastName = "Muster") },
                "inviteParticipant" to { inviteParticipant(postingId = bogus, personRef = "token") },
                "confirmParticipation" to { confirmParticipation(bogus) },
                "declineParticipation" to { declineParticipation(bogus) },
                "withdrawParticipation" to { withdrawParticipation(bogus) },
                "listMyParticipations" to { listMyParticipations() },
                "listParticipants" to { listParticipants(bogus) },
                "setMeetingNote" to { setMeetingNote(postingId = bogus, note = "Bahnhof") },
                "getPassengerHint" to { getPassengerHint(from = today, to = today.plus(DatePeriod(days = 90))) },
            )
        }

        test("the service has exactly the sixteen RPC methods of the matrix") {
            calls().keys.size shouldBe 16
        }

        test(
            "every status against all sixteen methods: ACTIVE nothing forbidden, GUEST/FRIEND exactly the non-allowlist, others everything",
        ) {
            carpoolTestApplication {
                MemberStatus.entries.forEach { status ->
                    val caller = data.member(label = "matrix-$status", status = status)
                    val forbidden =
                        calls().filter { (_, op) -> attempt(member = caller, op = op).exceptionOrNull() is ForbiddenException }.keys
                    val expected =
                        when (status) {
                            MemberStatus.ACTIVE -> emptySet()
                            MemberStatus.GUEST, MemberStatus.FRIEND -> calls().keys - allowlist
                            else -> calls().keys
                        }
                    withClue(clue = status.name) { forbidden shouldBe expected }
                }
            }
        }

        test("a guest sees offers only, no free text, no meeting note; a confirmed seat reveals the meeting note, never the notes") {
            carpoolTestApplication {
                val driver = data.member(label = "feed-driver", displayName = "Fahrer Sichtbar")
                val guest = data.member(label = "feed-guest", status = MemberStatus.GUEST)
                val friend = data.member(label = "feed-friend", status = MemberStatus.FRIEND)
                val offer = data.posting(author = driver, seats = 2, notes = "Telefon 0171 123456", meetingNote = "Vor dem Bahnhof, 7:30")
                val wanted = data.posting(author = driver, type = CarpoolPostingType.REQUEST)

                val feed = call(member = guest) { listPostings(CarpoolPostingType.REQUEST) } // the type filter is ignored for a guest
                feed.none { it.id == wanted.toString() } shouldBe true
                val seen = feed.single { it.id == offer.toString() }
                seen.notes shouldBe null
                seen.meetingNote shouldBe null
                seen.seatsFree shouldBe 2
                seen.myParticipation shouldBe null
                seen.authorDisplayName shouldBe CarpoolPrivacy.HIDDEN_NAME // anti-enumeration: no member name before the seat is confirmed

                val mine = call(member = guest) { requestParticipation(offer.toString()) }
                call(member = driver) { confirmParticipation(mine.id) }
                val after = call(member = guest) { listPostings(null) }.single { it.id == offer.toString() }
                after.meetingNote shouldBe "Vor dem Bahnhof, 7:30"
                after.notes shouldBe null
                after.authorDisplayName shouldBe "Fahrer Sichtbar" // revealed only once the seat is confirmed
                after.seatsFree shouldBe 1
                // Another guest, not confirmed, still sees no meeting note and no foreign participation.
                val other = call(member = friend) { listPostings(null) }.single { it.id == offer.toString() }
                other.meetingNote shouldBe null
                other.myParticipation shouldBe null
                other.seatsFree shouldBe 1
                // The driver sees everything on the own offer.
                val own = call(member = driver) { listMyPostings() }.single { it.id == offer.toString() }
                own.notes shouldBe "Telefon 0171 123456"
                own.meetingNote shouldBe "Vor dem Bahnhof, 7:30"
                call(member = driver) { listPostings(null) }.filter { it.id == wanted.toString() } shouldHaveSize 1
            }
        }

        test(
            "a full offer reports no free seat and a guest's 'my participations' lists the ride with the meeting note only when confirmed",
        ) {
            carpoolTestApplication {
                val driver = data.member(label = "full-driver")
                val guest = data.member(label = "full-guest", status = MemberStatus.GUEST)
                val guest2 = data.member(label = "full-guest2", status = MemberStatus.GUEST)
                val offer = data.posting(author = driver, seats = 1, meetingNote = "Parkplatz Nord")
                val first = call(member = guest) { requestParticipation(offer.toString()) }
                val second = call(member = guest2) { requestParticipation(offer.toString()) }
                call(member = guest) { listMyParticipations() }.single().meetingNote shouldBe null // still OPEN
                call(member = driver) { confirmParticipation(first.id) }
                call(member = guest) { listMyParticipations() }.single().meetingNote shouldBe "Parkplatz Nord"
                call(member = guest) { listPostings(null) }.single { it.id == offer.toString() }.seatsFree shouldBe 0
                call(member = guest2) { listMyParticipations() }.single().id shouldBe second.id
                call(member = driver) { listParticipants(offer.toString()) } shouldHaveSize 2
                call(member = driver) { listMyParticipations() }.shouldBeEmpty()
            }
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }

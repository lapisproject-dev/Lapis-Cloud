package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.carpool.CarpoolAction
import network.lapis.cloud.server.carpool.CarpoolDeps
import network.lapis.cloud.server.carpool.CarpoolInviteeRefStore
import network.lapis.cloud.server.carpool.CarpoolInviteeSearch
import network.lapis.cloud.server.carpool.CarpoolParticipations
import network.lapis.cloud.server.carpool.CarpoolRateLimiter
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.125 -- IDOR and enumeration resistance. Foreign, unknown, malformed and REQUEST-posting ids all produce the byte-identical answer; the
 * invitee search finds only an exact (normalised) full name, never a prefix, shows at most three hits, hides the member id behind a token
 * bound to the searching driver, and answers "nobody" and "rate limited" with the same empty list.
 */
class CarpoolIdorAndEnumerationTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        test("foreign, unknown, malformed ids and a REQUEST posting give the byte-identical not-found answer") {
            carpoolTestApplication {
                val driver = data.member(label = "idor-driver")
                val guest = data.member(label = "idor-guest", status = MemberStatus.GUEST)
                val stranger = data.member(label = "idor-stranger", status = MemberStatus.GUEST)
                val offer = data.posting(author = driver)
                val wanted = data.posting(author = driver, type = CarpoolPostingType.REQUEST)
                val asked = call(member = guest) { requestParticipation(offer.toString()) }

                val messages =
                    listOf(
                        attempt(member = stranger) { confirmParticipation(asked.id) }, // somebody else's participation
                        attempt(member = stranger) { withdrawParticipation(asked.id) },
                        attempt(member = stranger) { declineParticipation(Uuid.random().toString()) }, // unknown
                        attempt(member = stranger) { confirmParticipation("not-a-uuid") }, // malformed
                        attempt(member = guest) { requestParticipation(Uuid.random().toString()) },
                        attempt(member = guest) { requestParticipation("../etc/passwd") },
                        attempt(member = guest) { requestParticipation(wanted.toString()) }, // a REQUEST posting
                    ).map { result ->
                        result.exceptionOrNull().let {
                            it.shouldBeInstanceOf<NotFoundException>()
                            it.message
                        }
                    }
                messages.toSet() shouldBe setOf(CarpoolParticipations.NOT_FOUND_MESSAGE)
                messages.none { it!!.contains(asked.id) || it.contains(offer.toString()) } shouldBe true
                // The driver's participant list: somebody else's posting is the same answer.
                attempt(
                    member = data.member(label = "idor-other"),
                ) { listParticipants(offer.toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("findInvitee: exact normalised full name only; umlauts, case and spacing are equal; a prefix finds nothing") {
            carpoolTestApplication {
                val driver = data.member(label = "find-driver")
                val target = data.member(label = "find-target", status = MemberStatus.GUEST, displayName = "Jürgen Müller")
                // wrong status: never found
                data.member(label = "find-applicant", status = MemberStatus.APPLICATION, displayName = "Jürgen Müller")
                data.member(label = "find-anon", status = MemberStatus.FRIEND, displayName = "Jürgen Müller", anonymized = true)
                data.member(label = "find-other", displayName = "Jürgensen Müllerin")

                val hits = call(member = driver) { findInvitee(firstName = "Jürgen", lastName = "Müller") }
                hits.map { it.displayName } shouldBe listOf("Jürgen Müller")
                call(member = driver) { findInvitee(firstName = "juergen", lastName = "mueller") } shouldHaveSize 1
                call(member = driver) { findInvitee(firstName = "JÜRGEN ", lastName = "  MÜLLER") } shouldHaveSize 1
                call(member = driver) { findInvitee(firstName = "Jürg", lastName = "Müller") }.shouldBeEmpty() // a prefix is no match
                call(member = driver) { findInvitee(firstName = "Jü", lastName = "Mü") }.shouldBeEmpty() // too short
                call(member = driver) { findInvitee(firstName = "x".repeat(101), lastName = "Müller") }.shouldBeEmpty()
                // The wire object carries no member id: only an opaque token and the display name.
                hits.single().personRef.contains(target.toString()) shouldBe false
            }
        }

        test("findInvitee returns at most three hits, never the searching driver, and the 11th call looks exactly like 'nobody'") {
            carpoolTestApplication(deps = CarpoolDeps()) {
                val driver = data.member(label = "many-driver", displayName = "Erika Gleichnamig")
                repeat(4) { data.member(label = "many-$it", status = MemberStatus.GUEST, displayName = "Erika Gleichnamig") }
                call(member = driver) { findInvitee(firstName = "Erika", lastName = "Gleichnamig") } shouldHaveSize
                    CarpoolInviteeSearch.MAX_HITS
                repeat(9) { call(member = driver) { findInvitee(firstName = "Erika", lastName = "Gleichnamig") } }
                // over the limit: same shape as nobody
                call(member = driver) { findInvitee(firstName = "Erika", lastName = "Gleichnamig") }.shouldBeEmpty()
            }
        }

        test("the invitee token is bound to the searching driver and expires after 15 minutes") {
            var clock = Instant.parse("2026-10-13T10:00:00Z")
            val store = CarpoolInviteeRefStore(now = { clock })
            val driver = Uuid.random()
            val person = Uuid.random()
            val token = store.issue(driverId = driver, personId = person)
            store.resolve(driverId = driver, token = token) shouldBe person
            store.resolve(driverId = Uuid.random(), token = token) shouldBe null // another driver
            store.resolve(driverId = driver, token = "garbage") shouldBe null
            clock += 16.minutes
            store.resolve(driverId = driver, token = token) shouldBe null
        }

        test("inviting through a token of another driver is the constant not-found answer; the real flow works end to end") {
            val deps = CarpoolDeps()
            carpoolTestApplication(deps = deps) {
                val driver = data.member(label = "tok-driver")
                val rival = data.member(label = "tok-rival")
                val guest = data.member(label = "tok-guest", status = MemberStatus.GUEST, displayName = "Gustav Gastmann")
                val offer = data.posting(author = driver)
                val rivalOffer = data.posting(author = rival)
                val hit = call(member = driver) { findInvitee(firstName = "Gustav", lastName = "Gastmann") }.single()
                attempt(
                    member = rival,
                ) {
                    inviteParticipant(postingId = rivalOffer.toString(), personRef = hit.personRef)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                val participant = call(member = driver) { inviteParticipant(postingId = offer.toString(), personRef = hit.personRef) }
                participant.displayName shouldBe "Gustav Gastmann"
                call(member = guest) { listMyParticipations() }.single().awaitingMe shouldBe true
                call(member = guest) { confirmParticipation(participant.id) }
                call(member = driver) { listParticipants(offer.toString()) }.single().displayName shouldBe "Gustav Gastmann"
                // Not the author of the offer: not found, not forbidden (no oracle for other people's trips).
                val second = call(member = driver) { findInvitee(firstName = "Gustav", lastName = "Gastmann") }.single()
                attempt(
                    member = driver,
                ) {
                    inviteParticipant(postingId = rivalOffer.toString(), personRef = second.personRef)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("the rate limiter counts per member and action in a sliding hour and refuses the next call with a user-facing conflict") {
            var clock = Instant.parse("2026-10-13T10:00:00Z")
            val limiter = CarpoolRateLimiter(now = { clock })
            val member = Uuid.random()
            repeat(CarpoolAction.REQUEST.limit) { limiter.tryAcquire(memberId = member, action = CarpoolAction.REQUEST) shouldBe true }
            limiter.tryAcquire(memberId = member, action = CarpoolAction.REQUEST) shouldBe false
            limiter.tryAcquire(memberId = member, action = CarpoolAction.INVITE) shouldBe true // another action: another budget
            limiter.tryAcquire(memberId = Uuid.random(), action = CarpoolAction.REQUEST) shouldBe true // another member
            shouldThrow<ConflictException> { limiter.checkAndRecord(memberId = member, action = CarpoolAction.REQUEST) }
            clock += 61.minutes
            limiter.tryAcquire(memberId = member, action = CarpoolAction.REQUEST) shouldBe true
            // confirm, decline and withdraw share ONE budget of 60.
            repeat(CarpoolAction.DECIDE.limit) { limiter.tryAcquire(memberId = member, action = CarpoolAction.DECIDE) shouldBe true }
            limiter.tryAcquire(memberId = member, action = CarpoolAction.DECIDE) shouldBe false
            CarpoolAction.FIND.limit shouldBe 10
            CarpoolAction.INVITE.limit shouldBe 20
        }

        test("V1.9.139: the message budget is ten per minute, the eleventh is refused and a minute later it works again") {
            var clock = Instant.parse("2026-10-13T10:00:00Z")
            val limiter = CarpoolRateLimiter(now = { clock })
            val member = Uuid.random()
            repeat(10) { limiter.tryAcquire(memberId = member, action = CarpoolAction.MESSAGE) shouldBe true }
            limiter.tryAcquire(memberId = member, action = CarpoolAction.MESSAGE) shouldBe false
            limiter.tryAcquire(memberId = member, action = CarpoolAction.REQUEST) shouldBe true // own budget
            clock += 61.seconds
            limiter.tryAcquire(memberId = member, action = CarpoolAction.MESSAGE) shouldBe true
        }

        test("normalize folds case, umlauts, accents and spacing") {
            CarpoolInviteeSearch.normalize("  JÜRGEN   Müller ") shouldBe "juergen mueller"
            CarpoolInviteeSearch.normalize("Straße") shouldBe "strasse"
            CarpoolInviteeSearch.normalize("José Álvarez") shouldBe "jose alvarez"
            CarpoolInviteeSearch.normalize("İbrahim") shouldBe "ibrahim"
        }
    })

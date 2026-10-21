package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- [decideEventOnlineAccess], the ONE answer to "may this caller take part online right now": the matrix of who gets in under
 * which event (deadline or none, public or members only), that every refusal of an unknown / hidden / cancelled / not-online / unauthorized
 * event is the SAME outcome, that the window is only revealed to somebody who would be admitted, and the ticket path of the account-less.
 * The organization zone is New York, so a wall-clock that would be read in the process zone gives the wrong window.
 */
class EventOnlineAccessTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val newYork = TimeZone.of("America/New_York")
        lateinit var organizer: Uuid

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(role = AccountRole.MEMBER)
        }
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        // The event runs 2030-06-15 18:00-20:00 New York = 22:00Z-00:00Z; the window is 21:30Z-01:00Z.
        val open = Instant.parse("2030-06-15T22:30:00Z")
        val tooEarly = Instant.parse("2030-06-15T21:15:00Z")
        val over = Instant.parse("2030-06-16T01:30:00Z")

        fun decide(
            eventId: Uuid?,
            caller: EventOnlineCaller,
            now: Instant = open,
        ) = transaction {
            val row = eventId?.let { id -> EventTable.selectAll().where { EventTable.id eq id }.singleOrNull() }
            decideEventOnlineAccess(eventRow = row, caller = caller, now = now, orgZone = newYork)
        }

        fun session(
            memberId: Uuid,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
        ) = EventOnlineCaller.Session(fx.current(memberId = memberId, role = role, status = status))

        test("an open public event without a deadline admits every active member as participant and the organizer as moderator") {
            val event = fx.event(createdBy = organizer)
            val participant = fx.member()
            decide(event, session(participant)).outcome shouldBe EventOnlineOutcome.JOINED
            decide(event, session(participant)).role shouldBe ConferenceRole.PARTICIPANT
            decide(event, session(organizer)).role shouldBe ConferenceRole.MODERATOR
        }

        test("BOARD and ADMIN are moderators of any online event, also of a members-only one with a deadline they did not register for") {
            val event =
                fx.event(
                    createdBy = organizer,
                    visibility = EventVisibility.MEMBERS_ONLY,
                    registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0),
                )
            val board = fx.member(role = AccountRole.BOARD)
            val admin = fx.member(role = AccountRole.ADMIN)
            decide(event, session(board, AccountRole.BOARD)).role shouldBe ConferenceRole.MODERATOR
            decide(event, session(admin, AccountRole.ADMIN)).role shouldBe ConferenceRole.MODERATOR
        }

        test(
            "with a registration deadline only a CONFIRMED registration admits: pending, waitlisted, cancelled and no registration do not",
        ) {
            val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val confirmed = fx.member()
            val pending = fx.member()
            val waitlisted = fx.member()
            val cancelled = fx.member()
            val none = fx.member()
            fx.registration(eventId = event, memberId = confirmed, status = EventRegistrationStatus.CONFIRMED)
            fx.registration(eventId = event, memberId = pending, status = EventRegistrationStatus.PENDING_PAYMENT)
            fx.registration(eventId = event, memberId = waitlisted, status = EventRegistrationStatus.WAITLISTED)
            fx.registration(eventId = event, memberId = cancelled, status = EventRegistrationStatus.CANCELLED)
            decide(event, session(confirmed)).outcome shouldBe EventOnlineOutcome.JOINED
            listOf(pending, waitlisted, cancelled, none).forEach {
                decide(event, session(it)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
            }
        }

        test("a members-only event without a deadline admits members, never a person without an account and never a GUEST/FRIEND account") {
            val event = fx.event(createdBy = organizer, visibility = EventVisibility.MEMBERS_ONLY)
            decide(event, session(fx.member())).outcome shouldBe EventOnlineOutcome.JOINED
            decide(event, EventOnlineCaller.Accountless(ticketCodeSha256 = null)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
            val friend = fx.member(status = MemberStatus.FRIEND)
            val guest = fx.member(status = MemberStatus.GUEST)
            decide(event, session(friend, status = MemberStatus.FRIEND)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
            decide(event, session(guest, status = MemberStatus.GUEST)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
        }

        test(
            "a public event admits a GUEST/FRIEND account as a guest (the notice is then required) and a person without an account as a guest",
        ) {
            val event = fx.event(createdBy = organizer)
            val friend = fx.member(status = MemberStatus.FRIEND)
            val asFriend = decide(event, session(friend, status = MemberStatus.FRIEND))
            asFriend.outcome shouldBe EventOnlineOutcome.JOINED
            asFriend.isGuest shouldBe true
            val anonymous = decide(event, EventOnlineCaller.Accountless(ticketCodeSha256 = null))
            anonymous.outcome shouldBe EventOnlineOutcome.JOINED
            anonymous.isGuest shouldBe true
            anonymous.role shouldBe ConferenceRole.PARTICIPANT
        }

        test("an APPLICATION/WITHDRAWN account is refused like every other unauthorized caller") {
            val event = fx.event(createdBy = organizer)
            val applicant = fx.member(status = MemberStatus.APPLICATION)
            decide(event, session(applicant, status = MemberStatus.APPLICATION)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
        }

        test("a person without an account needs the ticket of a confirmed registration of THIS event when there is a deadline") {
            val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val other = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val goodHash = sha256Hex("GOODCODE12345678".toByteArray(Charsets.US_ASCII))
            val pendingHash = sha256Hex("PENDINGCODE123456".toByteArray(Charsets.US_ASCII))
            val otherHash = sha256Hex("OTHERCODE12345678".toByteArray(Charsets.US_ASCII))
            fx.registration(eventId = event, status = EventRegistrationStatus.CONFIRMED, ticketHash = goodHash)
            fx.registration(eventId = event, status = EventRegistrationStatus.PENDING_PAYMENT, ticketHash = pendingHash)
            fx.registration(eventId = other, status = EventRegistrationStatus.CONFIRMED, ticketHash = otherHash)
            decide(event, EventOnlineCaller.Accountless(goodHash)).outcome shouldBe EventOnlineOutcome.JOINED
            decide(event, EventOnlineCaller.Accountless(pendingHash)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
            decide(event, EventOnlineCaller.Accountless(otherHash)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
            decide(event, EventOnlineCaller.Accountless(sha256Hex("UNKNOWN".toByteArray()))).outcome shouldBe EventOnlineOutcome.FORBIDDEN
            decide(event, EventOnlineCaller.Accountless(null)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
        }

        test("an encounter-space event admits a member but never a person without an account (the entry there needs an account)") {
            val space = fx.space()
            val event = fx.event(createdBy = organizer, kind = EventOnlineKind.LAPIS_ENCOUNTER, encounterSpaceId = space)
            decide(event, session(fx.member())).outcome shouldBe EventOnlineOutcome.JOINED
            decide(event, EventOnlineCaller.Accountless(null)).outcome shouldBe EventOnlineOutcome.FORBIDDEN
        }

        test("an unknown, draft, cancelled, on-site-only and external-link event all give the identical refusal") {
            val member = session(fx.member())
            val refusals =
                listOf(
                    decide(Uuid.random(), member),
                    decide(null, member),
                    decide(fx.event(createdBy = organizer, status = EventStatus.DRAFT), member),
                    decide(fx.event(createdBy = organizer, status = EventStatus.CANCELLED), member),
                    decide(fx.event(createdBy = organizer, kind = EventOnlineKind.NONE), member),
                    decide(fx.event(createdBy = organizer, kind = EventOnlineKind.EXTERNAL_LINK), member),
                )
            refusals.forEach { it shouldBe refusals.first() }
            refusals.first().outcome shouldBe EventOnlineOutcome.FORBIDDEN
        }

        test("the window: not yet before it, open inside it (both ends inclusive), over after it") {
            val event = fx.event(createdBy = organizer)
            val member = session(fx.member())
            decide(event, member, now = tooEarly).outcome shouldBe EventOnlineOutcome.NOT_YET
            decide(event, member, now = Instant.parse("2030-06-15T21:30:00Z")).outcome shouldBe EventOnlineOutcome.JOINED
            decide(event, member, now = Instant.parse("2030-06-16T01:00:00Z")).outcome shouldBe EventOnlineOutcome.JOINED
            decide(event, member, now = over).outcome shouldBe EventOnlineOutcome.ENDED
        }

        test("an unauthorized caller never learns the window: a stranger to a deadline event gets FORBIDDEN, not NOT_YET or ENDED") {
            val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
            val stranger = session(fx.member())
            decide(event, stranger, now = tooEarly).outcome shouldBe EventOnlineOutcome.FORBIDDEN
            decide(event, stranger, now = over).outcome shouldBe EventOnlineOutcome.FORBIDDEN
        }

        test("the window is computed from the organization zone (New York), not the process zone") {
            // 18:00 read as UTC would put the window at 17:30Z-.. and make 17:50Z "open".
            val event = fx.event(createdBy = organizer)
            decide(event, session(fx.member()), now = Instant.parse("2030-06-15T17:50:00Z")).outcome shouldBe EventOnlineOutcome.NOT_YET
        }
    })

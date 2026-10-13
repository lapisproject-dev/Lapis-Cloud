package network.lapis.cloud.server.carpool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.125 -- the participation state machine of [CarpoolParticipations], driven directly (no HTTP, no rate limiter) with an explicit `now`:
 * every legal transition in both directions, every illegal one, the revival of the SAME row, the 24-hour lock, the open-request limits, an
 * expired trip, a REQUEST posting, the own trip, and the audit entry (ids and enums only).
 */
class CarpoolParticipationStateMachineTest :
    FunSpec({
        val data = CarpoolTestData()
        val now = LocalDateTime(2026, 10, 13, 12, 0)

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        fun <T> tx(block: () -> T): T = transaction { block() }

        fun request(
            person: Uuid,
            posting: Uuid,
            at: LocalDateTime = now,
            status: MemberStatus = MemberStatus.GUEST,
        ) = tx {
            CarpoolParticipations.request(
                actor = data.actor(memberId = person, status = status),
                postingId = posting,
                today = data.today(),
                now = at,
            )
        }

        fun invite(
            driver: Uuid,
            posting: Uuid,
            person: Uuid,
            at: LocalDateTime = now,
        ) = tx {
            CarpoolParticipations.invite(
                actor = data.actor(memberId = driver),
                postingId = posting,
                personId = person,
                today = data.today(),
                now = at,
            )
        }

        fun confirm(
            actor: Uuid,
            participation: Uuid,
            at: LocalDateTime = now,
        ) = tx {
            CarpoolParticipations.confirm(
                actor = data.actor(memberId = actor),
                participationId = participation,
                today = data.today(),
                now = at,
            )
        }

        fun decline(
            actor: Uuid,
            participation: Uuid,
            at: LocalDateTime = now,
        ) = tx {
            CarpoolParticipations.decline(
                actor = data.actor(memberId = actor),
                participationId = participation,
                today = data.today(),
                now = at,
            )
        }

        fun withdraw(
            actor: Uuid,
            participation: Uuid,
            at: LocalDateTime = now,
        ) = tx {
            CarpoolParticipations.withdraw(
                actor = data.actor(memberId = actor),
                participationId = participation,
                today = data.today(),
                now = at,
            )
        }

        fun statusOf(id: Uuid) =
            tx {
                CarpoolParticipationTable
                    .selectAll()
                    .where {
                        CarpoolParticipationTable.id eq id
                    }.single()[CarpoolParticipationTable.status]
            }

        test("passenger asks, driver confirms: OPEN -> CONFIRMED, the seat is taken and the driver is notified of the request") {
            val driver = data.member(label = "driver")
            val guest = data.member(label = "guest", status = MemberStatus.GUEST)
            val posting = data.posting(author = driver, seats = 2)
            val asked = request(guest, posting)
            asked.status shouldBe CarpoolParticipationStatus.OPEN
            asked.initiator shouldBe CarpoolParticipationInitiator.PASSENGER
            asked.notices.single().recipientId shouldBe driver
            asked.notices.single().kind shouldBe CarpoolNoticeKind.REQUESTED
            val confirmed = confirm(driver, asked.participationId)
            confirmed.status shouldBe CarpoolParticipationStatus.CONFIRMED
            confirmed.notices.single().recipientId shouldBe guest
            tx { CarpoolParticipations.confirmedCount(posting) } shouldBe 1
        }

        test("driver invites, passenger confirms: the other direction") {
            val driver = data.member(label = "driver2")
            val friend = data.member(label = "friend2", status = MemberStatus.FRIEND)
            val posting = data.posting(author = driver)
            val invited = invite(driver, posting, friend)
            invited.initiator shouldBe CarpoolParticipationInitiator.DRIVER
            invited.notices.single().recipientId shouldBe friend
            invited.notices.single().kind shouldBe CarpoolNoticeKind.INVITED
            confirm(friend, invited.participationId).status shouldBe CarpoolParticipationStatus.CONFIRMED
        }

        test("only the counter-party may confirm or decline: the initiator and a third party cannot") {
            val driver = data.member(label = "driver3")
            val guest = data.member(label = "guest3", status = MemberStatus.GUEST)
            val other = data.member(label = "other3")
            val posting = data.posting(author = driver)
            val asked = request(guest, posting)
            // The person who asked cannot confirm their own request ...
            shouldThrow<ConflictException> { confirm(guest, asked.participationId) }
            shouldThrow<ConflictException> { decline(guest, asked.participationId) }
            // ... and a third person gets the constant not-found answer.
            shouldThrow<NotFoundException> { confirm(other, asked.participationId) }.message shouldBe
                CarpoolParticipations.NOT_FOUND_MESSAGE
            shouldThrow<NotFoundException> { withdraw(other, asked.participationId) }
            statusOf(asked.participationId) shouldBe CarpoolParticipationStatus.OPEN
            // An invitation: the DRIVER initiated, so the driver cannot confirm it.
            val invitedPerson = data.member(label = "invited3", status = MemberStatus.FRIEND)
            val invitation = invite(driver, posting, invitedPerson)
            shouldThrow<ConflictException> { confirm(driver, invitation.participationId) }
            confirm(invitedPerson, invitation.participationId).status shouldBe CarpoolParticipationStatus.CONFIRMED
        }

        test("illegal transitions: confirm twice, decline after confirm, confirm a declined or withdrawn one, withdraw a final one") {
            val driver = data.member(label = "driver4")
            val guest = data.member(label = "guest4", status = MemberStatus.GUEST)
            val posting = data.posting(author = driver)
            val asked = request(guest, posting)
            confirm(driver, asked.participationId)
            shouldThrow<ConflictException> { confirm(driver, asked.participationId) }
            shouldThrow<ConflictException> { decline(driver, asked.participationId) }
            withdraw(guest, asked.participationId).status shouldBe CarpoolParticipationStatus.WITHDRAWN
            shouldThrow<ConflictException> { confirm(driver, asked.participationId) }
            shouldThrow<ConflictException> { withdraw(guest, asked.participationId) }
            val guest2 = data.member(label = "guest4b", status = MemberStatus.GUEST)
            val declinedOne = request(guest2, posting)
            decline(driver, declinedOne.participationId).status shouldBe CarpoolParticipationStatus.DECLINED
            shouldThrow<ConflictException> { withdraw(guest2, declinedOne.participationId) }
            shouldThrow<ConflictException> { confirm(driver, declinedOne.participationId) }
        }

        test("withdrawing from OPEN and from CONFIRMED works for both sides and notifies the other side") {
            val driver = data.member(label = "driver5")
            val guest = data.member(label = "guest5", status = MemberStatus.GUEST)
            val posting = data.posting(author = driver)
            val asked = request(guest, posting)
            val byDriver = withdraw(driver, asked.participationId)
            byDriver.status shouldBe CarpoolParticipationStatus.WITHDRAWN
            byDriver.notices.single().recipientId shouldBe guest
            val again = request(guest, posting)
            again.participationId shouldBe asked.participationId // the SAME row is revived, never a second one
            confirm(driver, again.participationId)
            val byGuest = withdraw(guest, again.participationId)
            byGuest.notices.single().recipientId shouldBe driver
            data.participationRows(posting).filter { it[CarpoolParticipationTable.personId] == guest } shouldHaveSize 1
        }

        test("a request is idempotent while OPEN or CONFIRMED and creates no second row") {
            val driver = data.member(label = "driver6")
            val guest = data.member(label = "guest6", status = MemberStatus.GUEST)
            val posting = data.posting(author = driver)
            val first = request(guest, posting)
            val second = request(guest, posting)
            second.participationId shouldBe first.participationId
            second.notices shouldHaveSize 0
            confirm(driver, first.participationId)
            request(guest, posting).status shouldBe CarpoolParticipationStatus.CONFIRMED
            data.participationRows(posting) shouldHaveSize 1
        }

        test("after a decline by the driver the same person may ask again only after 24 hours; withdrawing has no lock") {
            val driver = data.member(label = "driver7")
            val guest = data.member(label = "guest7", status = MemberStatus.GUEST)
            val posting = data.posting(author = driver)
            val asked = request(guest, posting, at = now)
            decline(driver, asked.participationId, at = now)
            shouldThrow<ConflictException> { request(guest, posting, at = now.plusHours(23)) }
            val revived = request(guest, posting, at = now.plusHours(25))
            revived.participationId shouldBe asked.participationId
            revived.status shouldBe CarpoolParticipationStatus.OPEN
            tx {
                CarpoolParticipationTable
                    .selectAll()
                    .where {
                        CarpoolParticipationTable.id eq asked.participationId
                    }.single()[CarpoolParticipationTable.decidedAt]
            } shouldBe null
            withdraw(guest, revived.participationId, at = now.plusHours(26))
            // no lock after a self-withdrawal
            request(guest, posting, at = now.plusHours(26)).status shouldBe CarpoolParticipationStatus.OPEN
        }

        test("the 24-hour lock is symmetric: a driver cannot re-invite a person who declined within 24 hours") {
            val driver = data.member(label = "driver8")
            val friend = data.member(label = "friend8", status = MemberStatus.FRIEND)
            val posting = data.posting(author = driver)
            val invitation = invite(driver, posting, friend, at = now)
            decline(friend, invitation.participationId, at = now)
            shouldThrow<ConflictException> { invite(driver, posting, friend, at = now.plusHours(23)) }
            invite(driver, posting, friend, at = now.plusHours(25)).status shouldBe CarpoolParticipationStatus.OPEN
            // ... and the person who declined an invitation may still ASK themselves right away (the lock only covers the counter-party).
            val friend2 = data.member(label = "friend8b", status = MemberStatus.FRIEND)
            val invitation2 = invite(driver, posting, friend2, at = now)
            decline(friend2, invitation2.participationId, at = now)
            request(friend2, posting, at = now.plusHours(1)).initiator shouldBe CarpoolParticipationInitiator.PASSENGER
        }

        test("limits: at most 10 open requests per person and 20 open per posting; the invite refusal is the same generic text") {
            val person = data.member(label = "busy", status = MemberStatus.GUEST)
            val driver = data.member(label = "driver9")
            repeat(CarpoolParticipations.MAX_OPEN_PER_PERSON) { request(person, data.posting(author = data.member(label = "d9-$it"))) }
            shouldThrow<ConflictException> { request(person, data.posting(author = driver)) }
            // Inviting the same busy person is refused with the generic text (no hint WHY).
            val driverPosting = data.posting(author = driver)
            val invite = shouldThrow<ConflictException> { invite(driver, driverPosting, person) }
            invite.message shouldBe "Einladung nicht möglich."
            // 20 open on one posting.
            val crowded = data.posting(author = driver, seats = 8)
            repeat(
                CarpoolParticipations.MAX_OPEN_PER_POSTING,
            ) { request(data.member(label = "c$it", status = MemberStatus.GUEST), crowded) }
            shouldThrow<ConflictException> { request(data.member(label = "c-extra", status = MemberStatus.GUEST), crowded) }
        }

        test("expired trip, a REQUEST posting, an unknown posting and the own trip are refused") {
            val driver = data.member(label = "driver10")
            val guest = data.member(label = "guest10", status = MemberStatus.GUEST)
            val expired = data.posting(author = driver, daysAhead = -1)
            val wanted = data.posting(author = driver, type = CarpoolPostingType.REQUEST)
            shouldThrow<NotFoundException> { request(guest, expired) }.message shouldBe CarpoolParticipations.NOT_FOUND_MESSAGE
            shouldThrow<NotFoundException> { request(guest, wanted) }.message shouldBe CarpoolParticipations.NOT_FOUND_MESSAGE
            shouldThrow<NotFoundException> { request(guest, Uuid.random()) }.message shouldBe CarpoolParticipations.NOT_FOUND_MESSAGE
            val own = data.posting(author = driver)
            shouldThrow<ConflictException> { request(driver, own, status = MemberStatus.ACTIVE) }
            shouldThrow<ConflictException> { invite(driver, own, driver) }
        }

        test("the last seat cannot be confirmed twice; a status change of the person before the answer is refused") {
            val driver = data.member(label = "driver11")
            val posting = data.posting(author = driver, seats = 1)
            val a = request(data.member(label = "a11", status = MemberStatus.GUEST), posting)
            val b = request(data.member(label = "b11", status = MemberStatus.GUEST), posting)
            confirm(driver, a.participationId)
            shouldThrow<ConflictException> { confirm(driver, b.participationId) }.message shouldBe
                "Für diese Fahrt ist kein Platz mehr frei."
            // A person who left the organisation meanwhile (status WITHDRAWN) cannot be confirmed any more.
            val posting2 = data.posting(author = driver)
            val leaver = data.member(label = "leaver11", status = MemberStatus.GUEST)
            val c = request(leaver, posting2)
            data.setStatus(memberId = leaver, status = MemberStatus.WITHDRAWN)
            shouldThrow<NotFoundException> { confirm(driver, c.participationId) }
        }

        test("the audit entry carries ids and enums only: no name, no place, no meeting note") {
            val driver = data.member(label = "driver12", displayName = "Sehr Geheimer Fahrername")
            val guest = data.member(label = "guest12", status = MemberStatus.GUEST)
            val posting = data.posting(author = driver, fromPlace = "Geheimort", toPlace = "Zielstadt", meetingNote = "Hinter dem Bahnhof")
            val asked = request(guest, posting)
            confirm(driver, asked.participationId)
            val entries =
                tx {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId eq asked.participationId }
                        .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() to it[AuditLogEntryTable.entityType] }
                }
            entries shouldHaveSize 2
            entries.forEach { (snapshot, type) ->
                type shouldBe AuditEntityType.CARPOOL_PARTICIPATION
                snapshot shouldNotContain "Geheim"
                snapshot shouldNotContain "Zielstadt"
                snapshot shouldNotContain "Bahnhof"
            }
        }

        test("the helpers behind posting update and delete: active participants, confirmed count, posting lock, explicit delete") {
            val driver = data.member(label = "driver13")
            val posting = data.posting(author = driver, seats = 2)
            val a = request(data.member(label = "a13", status = MemberStatus.GUEST), posting)
            confirm(driver, a.participationId)
            tx { CarpoolParticipations.activeParticipants(posting) } shouldHaveSize 1
            tx { CarpoolParticipations.confirmedCount(posting) } shouldBe 1
            // The lock helper sees the posting and the cascade (explicit delete) removes everything.
            tx { CarpoolParticipations.lockPosting(posting) }!![CarpoolPostingTable.seatsOffered] shouldBe 2
            tx { CarpoolParticipations.deleteAllOf(posting) }
            data.participationRows(posting) shouldHaveSize 0
        }

        test("a malformed id is the same constant not-found answer as an unknown one") {
            shouldThrow<NotFoundException> { CarpoolParticipations.parseId("not-a-uuid") }.message shouldBe
                CarpoolParticipations.NOT_FOUND_MESSAGE
        }
    })

private fun LocalDateTime.plusHours(hours: Int): LocalDateTime = (toInstant(TimeZone.UTC) + hours.hours).toLocalDateTime(TimeZone.UTC)

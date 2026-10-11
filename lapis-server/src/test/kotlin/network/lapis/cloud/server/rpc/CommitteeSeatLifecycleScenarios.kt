package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val STICHTAG = LocalDate(2026, 10, 11)
private val NOW = LocalDateTime(2026, 10, 11, 10, 0)

/**
 * Welle V1.9.102 -- [CommitteeSeatLifecycle] against a real database (H2 and PostgreSQL run the same assertions): which seats end, what
 * stays untouched, the audit entries of phase 2, the vacant-chair result, idempotency, the eligibility semantics of the inclusive `until`
 * and the rollback behaviour.
 */
abstract class CommitteeSeatLifecycleScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = CommitteeSeatFixture()

        beforeSpec { db.activate() }
        beforeTest { db.assertActive() }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun endSeats(
            memberId: kotlin.uuid.Uuid,
            newStatus: MemberStatus = MemberStatus.WITHDRAWN,
            reason: MemberStatus = newStatus,
            record: Boolean = true,
            actorMemberId: kotlin.uuid.Uuid? = null,
            actorRole: AccountRole? = null,
        ): CommitteeSeatEndResult =
            transaction {
                val result =
                    CommitteeSeatLifecycle.endSeatsForStatusChange(
                        memberId = memberId,
                        newStatus = newStatus,
                        reason = reason,
                        stichtag = STICHTAG,
                        now = NOW,
                        actorMemberId = actorMemberId,
                        actorRole = actorRole,
                    )
                if (record) result.recordAudits()
                result
            }

        test("every open seat of the member ends on the stichtag with the reason; other members' seats stay open") {
            val member = fx.member()
            val other = fx.member()
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val group = fx.committee(type = CommitteeType.WORKING_GROUP)
            val commission = fx.committee(type = CommitteeType.COMMISSION)
            val s1 = fx.seat(committee = board, member = member, role = CommitteeRole.SECRETARY)
            val s2 = fx.seat(committee = group, member = member)
            val s3 = fx.seat(committee = commission, member = member)
            val otherSeat = fx.seat(committee = group, member = other)

            val result = endSeats(memberId = member, reason = MemberStatus.WITHDRAWN)

            result.endedCount shouldBe 3
            result.endedMembershipIds.toSet() shouldBe setOf(s1, s2, s3)
            listOf(s1, s2, s3).forEach {
                fx.seatRow(it)[CommitteeMembershipTable.until] shouldBe STICHTAG
                fx.seatRow(it)[CommitteeMembershipTable.autoEndReason] shouldBe MemberStatus.WITHDRAWN
            }
            fx.seatRow(otherSeat)[CommitteeMembershipTable.until] shouldBe null
            fx.seatRow(otherSeat)[CommitteeMembershipTable.autoEndReason] shouldBe null
        }

        test("each reason of the ending set is stored: WITHDRAWN, REJECTED, DECEASED, DONOR") {
            listOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.DONOR).forEach { reason ->
                val member = fx.member()
                val seat = fx.seat(committee = fx.committee(), member = member)
                endSeats(memberId = member, reason = reason)
                fx.seatRow(seat)[CommitteeMembershipTable.autoEndReason] shouldBe reason
            }
        }

        test("a seat that already ended on or before the stichtag is not touched at all (byte-identical)") {
            val member = fx.member()
            val committee = fx.committee()
            val past =
                fx.seat(
                    committee = committee,
                    member = member,
                    since = LocalDate(2025, 1, 1),
                    until = LocalDate(2026, 3, 1),
                    autoEndReason = MemberStatus.DONOR,
                )
            val onStichtag = fx.seat(committee = committee, member = member, since = LocalDate(2025, 1, 1), until = STICHTAG)
            val mark = fx.auditMark()

            val result = endSeats(memberId = member)

            result.endedCount shouldBe 0
            fx.seatRow(past)[CommitteeMembershipTable.until] shouldBe LocalDate(2026, 3, 1)
            fx.seatRow(past)[CommitteeMembershipTable.autoEndReason] shouldBe MemberStatus.DONOR
            fx.seatRow(onStichtag)[CommitteeMembershipTable.until] shouldBe STICHTAG
            fx.seatRow(onStichtag)[CommitteeMembershipTable.autoEndReason] shouldBe null
            fx.auditSince(mark = mark).shouldBeEmpty()
        }

        test("a seat that runs beyond the stichtag is shortened; a seat that has not started yet gets an empty interval and is kept") {
            val member = fx.member()
            val committee = fx.committee()
            val future = fx.seat(committee = committee, member = member, since = LocalDate(2026, 1, 1), until = LocalDate(2027, 1, 1))
            val notStarted = fx.seat(committee = committee, member = member, since = LocalDate(2026, 12, 1))

            endSeats(memberId = member, reason = MemberStatus.DECEASED)

            fx.seatRow(future)[CommitteeMembershipTable.until] shouldBe STICHTAG
            fx.seatRow(notStarted)[CommitteeMembershipTable.until] shouldBe STICHTAG
            fx.seatRow(notStarted)[CommitteeMembershipTable.since] shouldBe LocalDate(2026, 12, 1)
            val row = fx.committeeRow(committee)
            val eligibleOn: (
                LocalDate,
            ) -> Boolean = { day -> transaction { isCommitteeEligible(committeeRow = row, scheduledDate = day, memberId = member) } }
            // the not-started seat has an empty interval: the person is eligible on none of its days
            eligibleOn(LocalDate(2026, 12, 1)) shouldBe false
            eligibleOn(LocalDate(2026, 12, 2)) shouldBe false
            fx.seatsOf(member).filter { it[CommitteeMembershipTable.id] == notStarted }.single().let {
                (it[CommitteeMembershipTable.since] > it[CommitteeMembershipTable.until]!!) shouldBe true
            }
        }

        test("a member without seats: nothing happens, no audit entry") {
            val member = fx.member()
            val mark = fx.auditMark()
            val result = endSeats(memberId = member)
            result.endedCount shouldBe 0
            result.vacatedChairBoardIds.shouldBeEmpty()
            fx.auditSince(mark = mark).shouldBeEmpty()
        }

        test("idempotent: a second call ends nothing and writes no second audit entry") {
            val member = fx.member()
            val seat = fx.seat(committee = fx.committee(), member = member)
            val mark = fx.auditMark()
            endSeats(memberId = member).endedCount shouldBe 1
            endSeats(memberId = member).endedCount shouldBe 0
            fx
                .auditSince(
                    mark = mark,
                    type = AuditEntityType.COMMITTEE_MEMBERSHIP,
                ).filter { it[AuditLogEntryTable.entityId] == seat } shouldHaveSize
                1
        }

        test("the audit entry per seat: actor set, ids/enums/dates only, no name, no mail; the hash chain stays valid") {
            val member = fx.member(displayName = "Geheimname Beispiel")
            val seat = fx.seat(committee = fx.committee(), member = member, role = CommitteeRole.ASSESSOR)
            val actor = fx.member(role = AccountRole.ADMIN)
            val mark = fx.auditMark()

            endSeats(
                memberId = member,
                newStatus = MemberStatus.DECEASED,
                reason = MemberStatus.DECEASED,
                actorMemberId = actor,
                actorRole = AccountRole.ADMIN,
            )

            val entries = fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP)
            entries shouldHaveSize 1
            val entry = entries.single()
            entry[AuditLogEntryTable.entityId] shouldBe seat
            entry[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
            entry[AuditLogEntryTable.actorMemberId] shouldBe actor
            entry[AuditLogEntryTable.actorRole] shouldBe AccountRole.ADMIN
            val before = entry[AuditLogEntryTable.beforeSnapshot]!!
            val after = entry[AuditLogEntryTable.afterSnapshot]!!
            before shouldContain "\"memberId\":\"$member\""
            after shouldContain "\"until\":\"$STICHTAG\""
            after shouldContain "\"autoEndReason\":\"DECEASED\""
            after shouldContain "\"trigger\":\"MEMBER_STATUS_CHANGE\""
            after shouldContain "\"newMemberStatus\":\"DECEASED\""
            listOf(before, after).forEach {
                it shouldNotContain "Geheimname"
                it shouldNotContain "example.org"
                it shouldNotContain "dateOfDeath"
            }
            fx.chainProblemSince(mark) shouldBe null
        }

        test(
            "an EXECUTIVE_BOARD seat cascades onto the open board membership (ended on the stichtag), BOARD_MEMBERSHIP audit has the actor",
        ) {
            val member = fx.member()
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            fx.seat(committee = board, member = member, role = CommitteeRole.SECRETARY)
            val boardMembership = fx.boardMembership(member = member, role = CommitteeRole.SECRETARY)
            val actor = fx.member(role = AccountRole.ADMIN)
            val mark = fx.auditMark()

            endSeats(memberId = member, reason = MemberStatus.WITHDRAWN, actorMemberId = actor, actorRole = AccountRole.ADMIN)

            fx.boardRow(boardMembership)[BoardMembershipTable.endedAt] shouldBe STICHTAG
            val boardAudits = fx.auditSince(mark = mark, type = AuditEntityType.BOARD_MEMBERSHIP)
            boardAudits shouldHaveSize 1
            boardAudits.single()[AuditLogEntryTable.actorMemberId] shouldBe actor
            boardAudits.single()[AuditLogEntryTable.actorRole] shouldBe AccountRole.ADMIN
            boardAudits.single()[AuditLogEntryTable.entityId] shouldBe boardMembership
            fx.chainProblemSince(mark) shouldBe null
        }

        test("the only CHAIR of an active executive board leaves: the board is reported as vacated, the call does not throw") {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val chair = fx.member()
            fx.seat(committee = board, member = chair, role = CommitteeRole.CHAIR)
            val result = endSeats(memberId = chair, reason = MemberStatus.WITHDRAWN)
            result.vacatedChairBoardIds shouldContainExactly listOf(board)
            transaction { CommitteeSeatLifecycle.chairedExecutiveBoardIds(LocalDate(2026, 10, 12)).contains(board) } shouldBe false
        }

        test("a second CHAIR keeps the board chaired: nothing is reported as vacated") {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val first = fx.member()
            val second = fx.member()
            fx.seat(committee = board, member = first, role = CommitteeRole.CHAIR)
            fx.seat(committee = board, member = second, role = CommitteeRole.CHAIR)
            endSeats(memberId = first, reason = MemberStatus.REJECTED).vacatedChairBoardIds.shouldBeEmpty()
        }

        test("a CHAIR who leaves a working group (not an executive board) reports nothing") {
            val group = fx.committee(type = CommitteeType.WORKING_GROUP)
            val chair = fx.member()
            fx.seat(committee = group, member = chair, role = CommitteeRole.CHAIR)
            endSeats(memberId = chair).vacatedChairBoardIds.shouldBeEmpty()
        }

        test("until is inclusive: the person still counts on the stichtag (known limitation) and no longer from the next day") {
            val member = fx.member()
            val committee = fx.committee()
            fx.seat(committee = committee, member = member)
            endSeats(memberId = member)
            val row = fx.committeeRow(committee)
            transaction {
                isCommitteeEligible(committeeRow = row, scheduledDate = STICHTAG, memberId = member) shouldBe true
                isCommitteeEligible(committeeRow = row, scheduledDate = LocalDate(2026, 10, 12), memberId = member) shouldBe false
                eligibleMemberIds(committeeRow = row, scheduledDate = STICHTAG).contains(member) shouldBe true
                eligibleMemberIds(committeeRow = row, scheduledDate = LocalDate(2026, 10, 12)).contains(member) shouldBe false
            }
        }

        test("a reason outside the ending set, and a call without a transaction, are programming errors") {
            val member = fx.member()
            shouldThrow<IllegalArgumentException> {
                endSeats(
                    memberId = member,
                    newStatus = MemberStatus.ACTIVE,
                    reason = MemberStatus.ACTIVE,
                )
            }
            shouldThrow<IllegalStateException> {
                CommitteeSeatLifecycle.endSeatsForStatusChange(
                    memberId = member,
                    newStatus = MemberStatus.WITHDRAWN,
                    reason = MemberStatus.WITHDRAWN,
                    stichtag = STICHTAG,
                    now = NOW,
                )
            }
        }

        test("rollback: an exception after the hook in the same transaction leaves seats open and writes no audit") {
            val member = fx.member()
            val seat = fx.seat(committee = fx.committee(), member = member)
            val mark = fx.auditMark()
            shouldThrow<IllegalStateException> {
                transaction {
                    val result =
                        CommitteeSeatLifecycle.endSeatsForStatusChange(
                            memberId = member,
                            newStatus = MemberStatus.WITHDRAWN,
                            reason = MemberStatus.WITHDRAWN,
                            stichtag = STICHTAG,
                            now = NOW,
                        )
                    result.recordAudits()
                    error("simulated failure after the hook")
                }
            }
            fx.seatRow(seat)[CommitteeMembershipTable.until] shouldBe null
            fx.seatRow(seat)[CommitteeMembershipTable.autoEndReason] shouldBe null
            fx.auditSince(mark = mark).shouldBeEmpty()
            // a retry is safe and ends the seat
            endSeats(memberId = member).endedCount shouldBe 1
        }

        test("the MANUAL end of a seat resets auto_end_reason and keeps the human as actor; the hook never runs for it") {
            val member = fx.member()
            val committee = fx.committee()
            val seat =
                fx.seat(
                    committee = committee,
                    member = member,
                    until = LocalDate(2026, 9, 1),
                    autoEndReason = MemberStatus.WITHDRAWN,
                )
            val actor = fx.member(role = AccountRole.BOARD)
            transaction {
                endCommitteeMembershipRow(
                    membershipRow = fx.seatRow(seat),
                    until = LocalDate(2026, 9, 15),
                    current = CurrentMember(memberId = actor, role = AccountRole.BOARD, status = MemberStatus.ACTIVE),
                )
            }
            fx.seatRow(seat)[CommitteeMembershipTable.until] shouldBe LocalDate(2026, 9, 15)
            fx.seatRow(seat)[CommitteeMembershipTable.autoEndReason] shouldBe null
        }
    })

class CommitteeSeatLifecycleTest : CommitteeSeatLifecycleScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CommitteeSeatLifecyclePostgresTest : CommitteeSeatLifecycleScenarios(TestDatabase.Postgres())

package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.ElectionSeatOutcomeTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.server.dsgvo.ElectionPersonalData
import network.lapis.cloud.server.dsgvo.GovernancePersonalData
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.IncumbentPolicy
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SeatOutcome
import network.lapis.cloud.shared.domain.SeatOutcomeReason
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private fun today(): LocalDate = OrganizationTimeZone.dateOf(DbClock.nowLocalDateTime())

/**
 * Welle V1.9.103 -- the tally-time succession ([ElectionSeatSuccession]) against a real database (H2 and PostgreSQL run the same assertions).
 * Elections are seeded as CLOSED rows and tallied through the live core [tallyElectionLocked].
 */
abstract class ElectionSeatSuccessionScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = CommitteeSeatFixture()
        val es = ElectionSuccessionFixture(fx)

        beforeSpec { db.activate() }
        beforeTest { db.assertActive() }
        afterSpec {
            es.cleanUp()
            fx.cleanUp()
            db.deactivate()
        }

        fun outcomesOf(electionId: Uuid): Map<Pair<Uuid, SeatOutcome>, SeatOutcomeReason?> =
            es.outcomes(electionId).associate {
                (it[ElectionSeatOutcomeTable.memberId] to it[ElectionSeatOutcomeTable.outcome]) to it[ElectionSeatOutcomeTable.reason]
            }

        fun openSeats(
            committee: Uuid,
            role: CommitteeRole? = null,
        ): List<Uuid> =
            transaction {
                CommitteeMembershipTable
                    .selectAll()
                    .where {
                        (CommitteeMembershipTable.committeeId eq committee) and CommitteeMembershipTable.until.isNull()
                    }.filter { role == null || it[CommitteeMembershipTable.role] == role }
                    .map { it[CommitteeMembershipTable.memberId] }
            }

        fun boardRows(member: Uuid) =
            transaction { BoardMembershipTable.selectAll().where { BoardMembershipTable.memberId eq member }.toList() }

        fun reminders(
            member: Uuid,
            type: BoardChangeType,
        ): Long =
            transaction {
                TransparenzregisterReminderTable
                    .selectAll()
                    .where {
                        (TransparenzregisterReminderTable.memberId eq member) and (TransparenzregisterReminderTable.changeType eq type)
                    }.count()
            }

        test(
            "REPLACE, one seat, chair of the executive board: the predecessor ends on the stichtag, the winner is seated, the board follows",
        ) {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val old = fx.member(displayName = "Alter Vorsitzender")
            val winner = fx.member(displayName = "Neue Vorsitzende")
            val oldSeat = fx.seat(committee = board, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val oldBoard = fx.boardMembership(member = old, role = CommitteeRole.CHAIR)
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            val mark = fx.auditMark()

            val result = es.tally(election)
            val stichtag = today()

            result.incumbentPolicy shouldBe IncumbentPolicy.REPLACE
            fx.seatRow(oldSeat)[CommitteeMembershipTable.until] shouldBe stichtag
            fx.seatRow(oldSeat)[CommitteeMembershipTable.endedByElectionId] shouldBe election.electionId
            fx.seatRow(oldSeat)[CommitteeMembershipTable.autoEndReason] shouldBe null
            val seated = es.seatsStartedBy(election.electionId).single()
            seated[CommitteeMembershipTable.memberId] shouldBe winner
            seated[CommitteeMembershipTable.role] shouldBe CommitteeRole.CHAIR
            seated[CommitteeMembershipTable.since] shouldBe stichtag
            seated[CommitteeMembershipTable.until] shouldBe null
            // BoardMembership + Transparenzregister reminders: exactly one LEFT for the old holder, one JOINED for the winner
            fx.boardRow(oldBoard)[BoardMembershipTable.endedAt] shouldBe stichtag
            boardRows(winner).single()[BoardMembershipTable.endedAt] shouldBe null
            reminders(old, BoardChangeType.LEFT) shouldBe 1L
            reminders(winner, BoardChangeType.JOINED) shouldBe 1L
            outcomesOf(election.electionId).keys shouldContainExactlyInAnyOrder
                setOf(old to SeatOutcome.ENDED, winner to SeatOutcome.SEATED)
            result.seatOutcomes.map { it.outcome }.toSet() shouldBe setOf(SeatOutcome.ENDED, SeatOutcome.SEATED)
            result.pendingAdminReview shouldBe false
            // GoBD: one CREATE for the new seat, one UPDATE for the old one, one BOARD_MEMBERSHIP end and one create
            fx
                .auditSince(
                    mark = mark,
                    type = AuditEntityType.COMMITTEE_MEMBERSHIP,
                ).map { it[AuditLogEntryTable.action] } shouldContainExactlyInAnyOrder
                listOf(AuditAction.CREATE, AuditAction.UPDATE)
            fx.auditSince(mark = mark, type = AuditEntityType.BOARD_MEMBERSHIP) shouldHaveSize 2
            fx.chainProblemSince(mark) shouldBe null
        }

        test(
            "REPLACE, chair of the executive board whose predecessor's seat starts later: the winner is skipped and the board stays untouched",
        ) {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val future = fx.member()
            val winner = fx.member()
            val futureSince = today().plus(10, DateTimeUnit.DAY)
            val futureSeat = fx.seat(committee = board, member = future, role = CommitteeRole.CHAIR, since = futureSince)
            val futureBoard = fx.boardMembership(member = future, role = CommitteeRole.CHAIR)
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            val mark = fx.auditMark()

            es.tally(election)

            fx.seatRow(futureSeat)[CommitteeMembershipTable.until] shouldBe null
            fx.boardRow(futureBoard)[BoardMembershipTable.endedAt] shouldBe null
            openSeats(board) shouldBe listOf(future)
            es.seatsStartedBy(election.electionId).shouldBeEmpty()
            boardRows(winner).shouldBeEmpty()
            val outcomes = outcomesOf(election.electionId)
            outcomes[winner to SeatOutcome.SKIPPED] shouldBe SeatOutcomeReason.SEAT_STARTS_LATER
            outcomes[future to SeatOutcome.PREDECESSOR_KEPT] shouldBe SeatOutcomeReason.SEAT_STARTS_LATER
            fx.auditSince(mark = mark, type = AuditEntityType.BOARD_MEMBERSHIP).shouldBeEmpty()
        }

        test("KEEP (Nachwahl): the previous holders stay and get no log row") {
            val group = fx.committee(type = CommitteeType.WORKING_GROUP)
            val old = fx.member()
            val winner = fx.member()
            val oldSeat = fx.seat(committee = group, member = old, role = CommitteeRole.MEMBER, since = SUCCESSION_OLD_SINCE)
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.MEMBER,
                    policy = IncumbentPolicy.KEEP,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )

            es.tally(election)

            fx.seatRow(oldSeat)[CommitteeMembershipTable.until] shouldBe null
            fx.seatRow(oldSeat)[CommitteeMembershipTable.endedByElectionId] shouldBe null
            openSeats(group).toSet() shouldBe setOf(old, winner)
            outcomesOf(election.electionId).keys shouldBe setOf(winner to SeatOutcome.SEATED)
        }

        test("REPLACE with three assessor seats: every assessor who is not re-elected ends, other roles stay") {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val chair = fx.member()
            val chairSeat = fx.seat(committee = board, member = chair, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val staying = fx.member()
            val leaving1 = fx.member()
            val leaving2 = fx.member()
            listOf(staying, leaving1, leaving2).forEach {
                fx.seat(committee = board, member = it, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE)
            }
            val new1 = fx.member()
            val new2 = fx.member()
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.ASSESSOR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates = listOf(staying to 4, new1 to 3, new2 to 2, fx.member() to 1),
                    type = ElectionType.MULTI_CHOICE,
                    seatCount = 3,
                )

            es.tally(election)

            openSeats(board, CommitteeRole.ASSESSOR).toSet() shouldBe setOf(staying, new1, new2)
            fx.seatRow(chairSeat)[CommitteeMembershipTable.until] shouldBe null
            outcomesOf(election.electionId).keys shouldContainExactlyInAnyOrder
                setOf(
                    staying to SeatOutcome.CONTINUED,
                    new1 to SeatOutcome.SEATED,
                    new2 to SeatOutcome.SEATED,
                    leaving1 to SeatOutcome.ENDED,
                    leaving2 to SeatOutcome.ENDED,
                )
        }

        test("re-election: CONTINUED keeps the seat row, writes no seat audit and does not touch the board membership") {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val holder = fx.member()
            val seat = fx.seat(committee = board, member = holder, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val boardMembership = fx.boardMembership(member = holder, role = CommitteeRole.CHAIR)
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            holder to 3,
                            fx.member() to 1,
                        ),
                )
            val mark = fx.auditMark()

            es.tally(election)

            fx.seatRow(seat)[CommitteeMembershipTable.until] shouldBe null
            fx.seatRow(seat)[CommitteeMembershipTable.since] shouldBe SUCCESSION_OLD_SINCE
            es.seatsStartedBy(election.electionId).shouldBeEmpty()
            fx.boardRow(boardMembership)[BoardMembershipTable.endedAt] shouldBe null
            reminders(holder, BoardChangeType.LEFT) shouldBe 0L
            reminders(holder, BoardChangeType.JOINED) shouldBe 0L
            fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP).shouldBeEmpty()
            fx.auditSince(mark = mark, type = AuditEntityType.BOARD_MEMBERSHIP).shouldBeEmpty()
            outcomesOf(election.electionId).keys shouldBe setOf(holder to SeatOutcome.CONTINUED)
        }

        test(
            "role change (assessor becomes chair): the old seat ends, the new one starts, the executive board gets one JOINED and no LEFT",
        ) {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val person = fx.member()
            val assessorSeat = fx.seat(committee = board, member = person, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE)
            fx.boardMembership(member = person, role = CommitteeRole.ASSESSOR)
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            person to 3,
                            fx.member() to 1,
                        ),
                )
            val mark = fx.auditMark()

            es.tally(election)

            fx.seatRow(assessorSeat)[CommitteeMembershipTable.until] shouldBe today()
            fx.seatRow(assessorSeat)[CommitteeMembershipTable.endedByElectionId] shouldBe election.electionId
            es.seatsStartedBy(election.electionId).single()[CommitteeMembershipTable.role] shouldBe CommitteeRole.CHAIR
            openSeats(board).filter { it == person } shouldHaveSize 1
            reminders(person, BoardChangeType.LEFT) shouldBe 0L
            reminders(person, BoardChangeType.JOINED) shouldBe 1L
            outcomesOf(election.electionId).keys shouldBe setOf(person to SeatOutcome.SEATED)
            val updateAudit =
                fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP).single {
                    it[AuditLogEntryTable.action] ==
                        AuditAction.UPDATE
                }
            updateAudit[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"trigger\":\"ELECTION_ROLE_CHANGE\""
        }

        test("a winner who is no longer an active member is SKIPPED, the previous holders are KEPT, the tally still completes") {
            val group = fx.committee(type = CommitteeType.WORKING_GROUP)
            val old1 = fx.member()
            val old2 = fx.member()
            listOf(
                old1,
                old2,
            ).forEach { fx.seat(committee = group, member = it, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE) }
            val good = fx.member()
            val gone = fx.member(status = MemberStatus.WITHDRAWN)
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.ASSESSOR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates = listOf(good to 4, gone to 3, fx.member() to 1),
                    type = ElectionType.MULTI_CHOICE,
                    seatCount = 2,
                )

            val result = es.tally(election)

            es.electionRow(election.electionId)[ElectionTable.status] shouldBe ElectionStatus.TALLIED
            outcomesOf(election.electionId) shouldBe
                mapOf(
                    (good to SeatOutcome.SEATED) to null,
                    (gone to SeatOutcome.SKIPPED) to SeatOutcomeReason.MEMBER_NOT_ACTIVE,
                    (old1 to SeatOutcome.PREDECESSOR_KEPT) to SeatOutcomeReason.WINNER_SKIPPED,
                    (old2 to SeatOutcome.PREDECESSOR_KEPT) to SeatOutcomeReason.WINNER_SKIPPED,
                )
            openSeats(group, CommitteeRole.ASSESSOR).toSet() shouldBe setOf(old1, old2, good)
            // the tallying actor is an administrator: the full view
            result.seatOutcomes.map { it.outcome }.toSet() shouldBe
                setOf(SeatOutcome.SEATED, SeatOutcome.SKIPPED, SeatOutcome.PREDECESSOR_KEPT)
        }

        test("a tie at the cut-off and a single-choice result without a majority seat nobody and write no log row") {
            val group = fx.committee()
            val old = fx.member()
            val oldSeat = fx.seat(committee = group, member = old, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE)
            val a = fx.member()
            val b = fx.member()
            val tie =
                es.seed(
                    committee = group,
                    role = CommitteeRole.ASSESSOR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates = listOf(a to 2, b to 2),
                    type = ElectionType.MULTI_CHOICE,
                    seatCount = 1,
                )
            val tieResult = es.tally(tie)
            tieResult.tie shouldBe true
            tieResult.seatOutcomes.shouldBeEmpty()
            tieResult.incumbentPolicy shouldBe IncumbentPolicy.REPLACE
            es.outcomes(tie.electionId).shouldBeEmpty()

            val noMajority =
                es.seed(
                    committee = group,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            a to 1,
                            b to 1,
                            fx.member() to 1,
                        ),
                )
            es.tally(noMajority).tie shouldBe true
            es.outcomes(noMajority.electionId).shouldBeEmpty()

            fx.seatRow(oldSeat)[CommitteeMembershipTable.until] shouldBe null
            openSeats(group) shouldBe listOf(old)
        }

        test("REPLACE with fewer winners than seats: every previous holder ends and the free seats stay free") {
            val group = fx.committee()
            val olds = (1..3).map { fx.member() }
            olds.forEach { fx.seat(committee = group, member = it, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE) }
            val a = fx.member()
            val b = fx.member()
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.ASSESSOR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates = listOf(a to 3, b to 2),
                    type = ElectionType.MULTI_CHOICE,
                    seatCount = 3,
                )

            es.tally(election)

            openSeats(group, CommitteeRole.ASSESSOR).toSet() shouldBe setOf(a, b)
            outcomesOf(election.electionId)
                .keys
                .filter { it.second == SeatOutcome.ENDED }
                .map { it.first }
                .toSet() shouldBe olds.toSet()
        }

        test(
            "an election without a policy (opened before V1.9.103) keeps the legacy behaviour, including the refusal of an inactive winner",
        ) {
            val group = fx.committee()
            val old = fx.member()
            val oldSeat = fx.seat(committee = group, member = old, role = CommitteeRole.MEMBER, since = SUCCESSION_OLD_SINCE)
            val gone = fx.member(status = MemberStatus.WITHDRAWN)
            val refused =
                es.seed(
                    committee = group,
                    role = CommitteeRole.MEMBER,
                    policy = null,
                    candidates =
                        listOf(
                            gone to 3,
                            fx.member() to 1,
                        ),
                )
            val before = transaction { CommitteeMembershipTable.selectAll().count() }
            shouldThrow<ForbiddenException> { es.tally(refused) }
            transaction { CommitteeMembershipTable.selectAll().count() } shouldBe before
            es.electionRow(refused.electionId)[ElectionTable.status] shouldBe ElectionStatus.CLOSED

            val winner = fx.member()
            val legacy =
                es.seed(
                    committee = group,
                    role = CommitteeRole.MEMBER,
                    policy = null,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            val result = es.tally(legacy)
            result.incumbentPolicy shouldBe null
            result.seatOutcomes.shouldBeEmpty()
            es.outcomes(legacy.electionId).shouldBeEmpty()
            val legacySeat = openSeats(group)
            legacySeat.toSet() shouldBe setOf(old, winner)
            fx.seatRow(oldSeat)[CommitteeMembershipTable.until] shouldBe null
            es.seatsStartedBy(legacy.electionId).shouldBeEmpty() // the legacy path never writes the provenance columns
        }

        test("a second tally of the same election is refused and changes nothing") {
            val group = fx.committee()
            val winner = fx.member()
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.MEMBER,
                    policy = IncumbentPolicy.KEEP,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            es.tally(election)
            val mark = fx.auditMark()
            shouldThrow<ConflictException> { es.tally(election) }
            openSeats(group) shouldBe listOf(winner)
            es.outcomes(election.electionId) shouldHaveSize 1
            fx.auditSince(mark = mark).shouldBeEmpty()
            transaction { ResolutionTable.selectAll().where { ResolutionTable.electionId eq election.electionId }.count() } shouldBe 1L
        }

        test(
            "closed seats stay byte-identical, a predecessor whose seat starts later is never touched, a winner with a later seat is skipped",
        ) {
            val group = fx.committee()
            val closed = fx.member()
            val closedSeat =
                fx.seat(
                    committee = group,
                    member = closed,
                    role = CommitteeRole.ASSESSOR,
                    since = SUCCESSION_OLD_SINCE,
                    until = LocalDate(2025, 6, 1),
                )
            val later = fx.member()
            val laterSeat =
                fx.seat(
                    committee = group,
                    member = later,
                    role = CommitteeRole.ASSESSOR,
                    since = today().plus(10, DateTimeUnit.DAY),
                )
            val winnerWithLaterSeat = fx.member()
            fx.seat(committee = group, member = winnerWithLaterSeat, role = CommitteeRole.MEMBER, since = today().plus(5, DateTimeUnit.DAY))
            val ok = fx.member()
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.ASSESSOR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates = listOf(ok to 4, winnerWithLaterSeat to 3, fx.member() to 1),
                    type = ElectionType.MULTI_CHOICE,
                    seatCount = 2,
                )

            es.tally(election)

            fx.seatRow(closedSeat)[CommitteeMembershipTable.until] shouldBe LocalDate(2025, 6, 1)
            fx.seatRow(closedSeat)[CommitteeMembershipTable.endedByElectionId] shouldBe null
            fx.seatRow(laterSeat)[CommitteeMembershipTable.until] shouldBe null
            val outcomes = outcomesOf(election.electionId)
            outcomes[winnerWithLaterSeat to SeatOutcome.SKIPPED] shouldBe SeatOutcomeReason.SEAT_STARTS_LATER
            outcomes[later to SeatOutcome.PREDECESSOR_KEPT] shouldBe SeatOutcomeReason.SEAT_STARTS_LATER
        }

        test("the audit entries: ids/enums/dates only (exhaustive key set), the tallying actor, a valid hash chain") {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val old = fx.member(displayName = "Geheimname Alt")
            val winner = fx.member(displayName = "Geheimname Neu")
            fx.seat(committee = board, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            val mark = fx.auditMark()

            es.tally(election)

            val entries = fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP)
            entries shouldHaveSize 2
            entries.forEach { it[AuditLogEntryTable.actorMemberId] shouldBe es.admin }
            val create = entries.single { it[AuditLogEntryTable.action] == AuditAction.CREATE }
            val update = entries.single { it[AuditLogEntryTable.action] == AuditAction.UPDATE }
            val createKeys = Json.parseToJsonElement(create[AuditLogEntryTable.afterSnapshot]!!).jsonObject.keys
            createKeys shouldBe
                setOf(
                    "memberId",
                    "committeeId",
                    "committeeType",
                    "role",
                    "since",
                    "until",
                    "autoEndReason",
                    "trigger",
                    "startedByElectionId",
                )
            val updateKeys = Json.parseToJsonElement(update[AuditLogEntryTable.afterSnapshot]!!).jsonObject.keys
            updateKeys shouldBe
                setOf(
                    "memberId",
                    "committeeId",
                    "committeeType",
                    "role",
                    "since",
                    "until",
                    "autoEndReason",
                    "trigger",
                    "endedByElectionId",
                )
            create[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"trigger\":\"ELECTION_SEATED\""
            update[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"trigger\":\"ELECTION_SUCCESSION\""
            entries.forEach { entry ->
                listOfNotNull(entry[AuditLogEntryTable.beforeSnapshot], entry[AuditLogEntryTable.afterSnapshot]).forEach {
                    it shouldNotContain "Geheimname"
                    it shouldNotContain "example.org"
                }
            }
            fx.chainProblemSince(mark) shouldBe null
        }

        test("rollback: a failure after the tally leaves seats, log, board and the CLOSED election untouched") {
            val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
            val old = fx.member()
            val winner = fx.member()
            val oldSeat = fx.seat(committee = board, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val oldBoard = fx.boardMembership(member = old, role = CommitteeRole.CHAIR)
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            val mark = fx.auditMark()

            shouldThrow<IllegalStateException> {
                transaction {
                    val motionRow = MotionDecisionLock.lockMotion(election.motionId)
                    val electionRow = lockElectionRow(election.electionId)
                    tallyElectionLocked(
                        electionMotionRow = motionRow,
                        electionRow = electionRow,
                        actor = CurrentMember(memberId = es.admin, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                    )
                    error("simulated failure after the tally")
                }
            }

            fx.seatRow(oldSeat)[CommitteeMembershipTable.until] shouldBe null
            fx.boardRow(oldBoard)[BoardMembershipTable.endedAt] shouldBe null
            es.seatsStartedBy(election.electionId).shouldBeEmpty()
            es.outcomes(election.electionId).shouldBeEmpty()
            es.electionRow(election.electionId)[ElectionTable.status] shouldBe ElectionStatus.CLOSED
            fx.auditSince(mark = mark).shouldBeEmpty()
            // a retry succeeds
            es.tally(election)
            es.seatsStartedBy(election.electionId) shouldHaveSize 1
        }

        test("eligibility after the tally: on the stichtag both count (inclusive until), from the next day only the winner") {
            val group = fx.committee()
            val old = fx.member()
            val winner = fx.member()
            fx.seat(committee = group, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )

            es.tally(election)
            val stichtag = today()

            val row = fx.committeeRow(group)
            val tomorrow = stichtag.plus(1, DateTimeUnit.DAY)
            transaction {
                isCommitteeEligible(committeeRow = row, scheduledDate = stichtag, memberId = old) shouldBe true
                isCommitteeEligible(committeeRow = row, scheduledDate = stichtag, memberId = winner) shouldBe true
                isCommitteeEligible(committeeRow = row, scheduledDate = tomorrow, memberId = old) shouldBe false
                isCommitteeEligible(committeeRow = row, scheduledDate = tomorrow, memberId = winner) shouldBe true
            }
        }

        test("the tally itself is unchanged by the succession: resolution, motion and election are finished, ballots stay") {
            val group = fx.committee()
            val winner = fx.member()
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.MEMBER,
                    policy = IncumbentPolicy.KEEP,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            val result = es.tally(election)
            result.winnerOptionIds shouldBe listOf(election.optionIdByMember.getValue(winner).toString())
            result.tie shouldBe false
            es.electionRow(election.electionId)[ElectionTable.status] shouldBe ElectionStatus.TALLIED
            es.electionRow(election.electionId)[ElectionTable.resolutionId] shouldBe
                transaction {
                    ResolutionTable.selectAll().where { ResolutionTable.electionId eq election.electionId }.single()[ResolutionTable.id]
                }
        }

        test("a seat ended by an election and then ended or re-ended by hand loses ended_by_election_id (one cause only)") {
            val board = fx.committee(type = CommitteeType.WORKING_GROUP)
            val old = fx.member()
            val winner = fx.member()
            val oldSeat = fx.seat(committee = board, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val election =
                es.seed(
                    committee = board,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            es.tally(election)
            fx.seatRow(oldSeat)[CommitteeMembershipTable.endedByElectionId] shouldBe election.electionId
            val actor = fx.member(role = AccountRole.BOARD)

            transaction {
                endCommitteeMembershipRow(
                    membershipRow = fx.seatRow(oldSeat),
                    until = LocalDate(2026, 9, 15),
                    current = CurrentMember(memberId = actor, role = AccountRole.BOARD, status = MemberStatus.ACTIVE),
                )
            }

            fx.seatRow(oldSeat)[CommitteeMembershipTable.until] shouldBe LocalDate(2026, 9, 15)
            fx.seatRow(oldSeat)[CommitteeMembershipTable.endedByElectionId] shouldBe null
            // the provenance of the seat the election started stays
            es.seatsStartedBy(election.electionId).single()[CommitteeMembershipTable.memberId] shouldBe winner
        }

        test("the DSGVO exports carry the succession log and the provenance columns of the person's own seats") {
            val group = fx.committee()
            val old = fx.member()
            val winner = fx.member()
            fx.seat(committee = group, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
            val election =
                es.seed(
                    committee = group,
                    role = CommitteeRole.CHAIR,
                    policy = IncumbentPolicy.REPLACE,
                    candidates =
                        listOf(
                            winner to 3,
                            fx.member() to 1,
                        ),
                )
            es.tally(election)

            val winnerElections = transaction { ElectionPersonalData.exportMember(winner) }.toString()
            winnerElections shouldContain "\"electionSeatOutcomes\""
            winnerElections shouldContain "\"outcome\":\"SEATED\""
            val oldGovernance = transaction { GovernancePersonalData.exportMember(old) }.toString()
            oldGovernance shouldContain "\"endedByElectionId\":\"${election.electionId}\""
            val winnerGovernance = transaction { GovernancePersonalData.exportMember(winner) }.toString()
            winnerGovernance shouldContain "\"startedByElectionId\":\"${election.electionId}\""
        }
    })

class ElectionSeatSuccessionTest : ElectionSeatSuccessionScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ElectionSeatSuccessionPostgresTest : ElectionSeatSuccessionScenarios(TestDatabase.Postgres())

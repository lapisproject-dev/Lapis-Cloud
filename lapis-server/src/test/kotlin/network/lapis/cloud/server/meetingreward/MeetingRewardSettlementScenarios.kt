package network.lapis.cloud.server.meetingreward

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.tender.TenderApp
import network.lapis.cloud.server.tender.TenderFixtures
import network.lapis.cloud.server.tender.tenderTestApplication
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MeetingRewardLineReason
import network.lapis.cloud.shared.domain.MeetingRewardSettlementAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import network.lapis.cloud.shared.domain.MeetingRewardSettlementViewDto
import network.lapis.cloud.shared.domain.MeetingRewardUnavailableReason
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private fun amount(s: String) = BigDecimal(s)

private fun MeetingRewardSettlementViewDto.line(member: Uuid) = lines.single { it.memberId == member.toString() }

/**
 * V1.9.316 -- the settlement of one Sitzung: preview, preparation, release (the ONLY place LTR is written), discarding. One credit row MEETING_REWARD per
 * recognised person, four eyes, idempotent, the yearly cap holds under concurrency. Runs on H2 and, as the proof of the concurrency claims, on PostgreSQL.
 */
abstract class MeetingRewardSettlementScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = TenderFixtures()

        beforeSpec {
            db.activate()
            TimeTestSupport.setOrganizationZone("Europe/Berlin")
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterTest { fx.resetRewards() }
        afterSpec {
            TimeTestSupport.resetOrganizationZone()
            db.deactivate()
        }

        class World(
            val adminA: Uuid,
            val adminB: Uuid,
            val adminC: Uuid,
            val committee: Uuid,
            val resolution: Uuid,
            val meeting: Uuid,
            val chair: Uuid,
            val plain: Uuid,
            val excused: Uuid,
            val unexcused: Uuid,
            val represented: Uuid,
            val guest: Uuid,
        )

        var auditBaseline = 0L

        /** A held meeting of an enabled committee: a chair (15.00), a plain member (10.00) and four people who get nothing; the Protokoll is confirmed. */
        fun displayNameOf(member: Uuid): String =
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq member }
                    .single()[MemberTable.displayName]
            }

        fun world(
            enabled: Boolean = true,
            committeeEnabled: Boolean = true,
            held: Boolean = true,
            protocol: Boolean = true,
            capYear: String = "240.00",
            capMeeting: String = "1000.00",
        ): World {
            auditBaseline = transaction { AuditLogEntryTable.selectAll().maxOfOrNull { it[AuditLogEntryTable.sequenceNumber] } ?: 0L }
            val a = fx.member(label = "adminA", role = AccountRole.ADMIN)
            val b = fx.member(label = "adminB", role = AccountRole.ADMIN)
            val c = fx.member(label = "adminC", role = AccountRole.ADMIN)
            val committee = fx.committee()
            val ga = fx.generalAssembly()
            val resolution = fx.resolutionIn(committee = ga, recordedBy = a)
            val meeting = fx.heldMeeting(committee = committee, status = if (held) MeetingStatus.HELD else MeetingStatus.PLANNED)
            val chair = fx.member(label = "chair")
            val plain = fx.member(label = "plain")
            val excused = fx.member(label = "excused")
            val unexcused = fx.member(label = "unexcused")
            val represented = fx.member(label = "represented")
            val guest = fx.member(label = "guest")
            seatWithRole(committee = committee, member = chair, role = CommitteeRole.CHAIR)
            setAttendance(meeting = meeting, member = chair, status = AttendanceStatus.PRESENT)
            setAttendance(meeting = meeting, member = plain, status = AttendanceStatus.PRESENT)
            setAttendance(meeting = meeting, member = excused, status = AttendanceStatus.EXCUSED)
            setAttendance(meeting = meeting, member = unexcused, status = AttendanceStatus.UNEXCUSED)
            setAttendance(meeting = meeting, member = represented, status = AttendanceStatus.REPRESENTED)
            setAttendance(meeting = meeting, member = guest, status = AttendanceStatus.GUEST)
            seedPolicy(
                proposer = a,
                approver = b,
                resolution = resolution,
                committees = if (committeeEnabled) setOf(committee) else setOf(fx.committee()),
                enabled = enabled,
                capYear = capYear,
                capMeeting = capMeeting,
            )
            if (protocol) confirmProtocol(meeting = meeting, by = a)
            return World(a, b, c, committee, resolution, meeting, chair, plain, excused, unexcused, represented, guest)
        }

        /** Another held meeting of the committee of [w] with a confirmed Protokoll and [attendees] present. */
        fun anotherMeeting(
            w: World,
            attendees: List<Uuid>,
            at: LocalDateTime = LocalDateTime(2026, 11, 20, 18, 0),
        ): Uuid {
            val meeting = fx.heldMeeting(committee = w.committee, at = at)
            attendees.forEach { setAttendance(meeting = meeting, member = it, status = AttendanceStatus.PRESENT) }
            confirmProtocol(meeting = meeting, by = w.adminA)
            return meeting
        }

        suspend fun TenderApp.prepareAndRelease(
            w: World,
            meeting: Uuid = w.meeting,
            preparer: Uuid = w.adminA,
            releaser: Uuid = w.adminB,
        ): MeetingRewardSettlementViewDto {
            val preview = reward(member = preparer) { previewMeetingRewardSettlement(meeting.toString()) }
            reward(
                member = preparer,
            ) { prepareMeetingRewardSettlement(meetingId = meeting.toString(), previewHash = preview.previewHash!!) }
            return reward(
                member = releaser,
            ) { releaseMeetingRewardSettlement(meetingId = meeting.toString(), previewHash = preview.previewHash!!) }
        }

        fun balance(member: Uuid): BigDecimal {
            val total = LtrLedgerEntryTable.amountLtr.sum()
            return transaction {
                LtrLedgerEntryTable.select(total).where { LtrLedgerEntryTable.memberId eq member }.single()[total] ?: BigDecimal.ZERO
            }
        }

        fun settlementAudit(): List<MeetingRewardSettlementAuditSnapshot> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.MEETING_REWARD_SETTLEMENT) and
                            (AuditLogEntryTable.sequenceNumber greater auditBaseline)
                    }.orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                    .map {
                        Json.decodeFromString(
                            MeetingRewardSettlementAuditSnapshot.serializer(),
                            it[AuditLogEntryTable.afterSnapshot]!!,
                        )
                    }
            }

        test("the preview lists every attendee with the amount or the reason, and totals the figures") {
            tenderTestApplication {
                val w = world()
                val view = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                view.unavailable shouldBe null
                view.status shouldBe null
                view.canPrepare shouldBe true
                view.lines.size shouldBe 6
                view.line(w.chair).amount.compareTo(amount("15.00")) shouldBe 0
                view.line(w.chair).bonusAmount.compareTo(amount("5.00")) shouldBe 0
                view.line(w.chair).functionRole shouldBe CommitteeRole.CHAIR
                view.line(w.plain).amount.compareTo(amount("10.00")) shouldBe 0
                view.line(w.excused).reason shouldBe MeetingRewardLineReason.EXCUSED
                view.line(w.unexcused).reason shouldBe MeetingRewardLineReason.NOT_PRESENT
                view.line(w.represented).reason shouldBe MeetingRewardLineReason.REPRESENTED
                view.line(w.guest).reason shouldBe MeetingRewardLineReason.GUEST
                view.total.compareTo(amount("25.00")) shouldBe 0
                // a preview writes nothing
                transaction { MeetingRewardSettlementTable.selectAll().count() } shouldBe 0
                ledgerRows(w.meeting).size shouldBe 0
            }
        }

        test(
            "the settlement is unavailable (and refused) while the rules, the committee, the meeting, the Protokoll or the attendance do not fit",
        ) {
            tenderTestApplication {
                val cases =
                    listOf(
                        MeetingRewardUnavailableReason.FEATURE_DISABLED to FEATURE_DISABLED,
                        MeetingRewardUnavailableReason.COMMITTEE_NOT_ENABLED to COMMITTEE_NOT_ENABLED,
                        MeetingRewardUnavailableReason.MEETING_NOT_HELD to MEETING_NOT_HELD,
                        MeetingRewardUnavailableReason.PROTOCOL_NOT_CONFIRMED to PROTOCOL_NOT_CONFIRMED,
                        MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL to ATTENDANCE_CHANGED_AFTER_PROTOCOL,
                    )
                cases.forEach { (reason, text) ->
                    val w =
                        when (reason) {
                            MeetingRewardUnavailableReason.FEATURE_DISABLED -> world(enabled = false)
                            MeetingRewardUnavailableReason.COMMITTEE_NOT_ENABLED -> world(committeeEnabled = false)
                            MeetingRewardUnavailableReason.MEETING_NOT_HELD -> world(held = false)
                            MeetingRewardUnavailableReason.PROTOCOL_NOT_CONFIRMED -> world(protocol = false)
                            MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL ->
                                world().also { setAttendance(meeting = it.meeting, member = it.excused, status = AttendanceStatus.PRESENT) }
                        }
                    val view = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                    view.unavailable shouldBe reason
                    view.canPrepare shouldBe false
                    view.lines.isEmpty() shouldBe true
                    shouldThrow<ConflictException> {
                        reward(
                            member = w.adminA,
                        ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = "any") }
                    }.message shouldBe
                        text
                    shouldThrow<ConflictException> {
                        reward(member = w.adminB) { releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = "any") }
                    }
                    ledgerRows(w.meeting).size shouldBe 0
                    fx.resetRewards()
                }
            }
        }

        test("a swap of present and absent with the same head count after the Protokoll is detected too") {
            tenderTestApplication {
                val w = world()
                setAttendance(meeting = w.meeting, member = w.plain, status = AttendanceStatus.UNEXCUSED)
                setAttendance(meeting = w.meeting, member = w.unexcused, status = AttendanceStatus.PRESENT)
                reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }.unavailable shouldBe
                    MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL
            }
        }

        test("a rename plus swap of present and excused (same counts, same present names) after the Protokoll is detected") {
            tenderTestApplication {
                val w = world()
                val plainName = displayNameOf(w.plain)
                // the excused member takes over the display name of the present one, and the two rows are swapped
                transaction { MemberTable.update({ MemberTable.id eq w.excused }) { it[displayName] = plainName } }
                setAttendance(meeting = w.meeting, member = w.plain, status = AttendanceStatus.EXCUSED)
                setAttendance(meeting = w.meeting, member = w.excused, status = AttendanceStatus.PRESENT)
                val view = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                view.unavailable shouldBe MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL
                view.canPrepare shouldBe false
                ledgerRows(w.meeting).size shouldBe 0
            }
        }

        test("two members with identical display names swapped after the Protokoll are detected without any rename") {
            tenderTestApplication {
                val w = world()
                val plainName = displayNameOf(w.plain)
                transaction { MemberTable.update({ MemberTable.id eq w.excused }) { it[displayName] = plainName } }
                dropProtocol(w.meeting)
                confirmProtocol(meeting = w.meeting, by = w.adminA)
                // sanity: the confirmed Protokoll matches as it stands
                reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }.unavailable shouldBe null
                setAttendance(meeting = w.meeting, member = w.plain, status = AttendanceStatus.EXCUSED)
                setAttendance(meeting = w.meeting, member = w.excused, status = AttendanceStatus.PRESENT)
                reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }.unavailable shouldBe
                    MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL
            }
        }

        test("prepare then release by a second administrator books exactly one MEETING_REWARD credit per recognised person") {
            tenderTestApplication {
                val w = world()
                val released = prepareAndRelease(w)
                released.status shouldBe MeetingRewardSettlementStatus.RELEASED
                released.releasedByName shouldBe "Tender-Test adminB"
                released.canRelease shouldBe false
                val rows = ledgerRows(w.meeting)
                rows.size shouldBe 2
                rows.forEach {
                    it[LtrLedgerEntryTable.entryType] shouldBe LtrLedgerEntryType.MEETING_REWARD
                    it[LtrLedgerEntryTable.referenceType] shouldBe LtrLedgerReferenceType.MEETING
                    it[LtrLedgerEntryTable.referenceId] shouldBe w.meeting
                    it[LtrLedgerEntryTable.createdBy] shouldBe w.adminB
                    it[LtrLedgerEntryTable.note] shouldBe null
                }
                balance(w.chair).compareTo(amount("15.00")) shouldBe 0
                balance(w.plain).compareTo(amount("10.00")) shouldBe 0
                balance(w.excused).compareTo(BigDecimal.ZERO) shouldBe 0
                // every paid line points at its ledger row, an unpaid one at nothing
                transaction {
                    MeetingRewardLineTable.selectAll().filter { it[MeetingRewardLineTable.ledgerEntryId] != null }.size
                } shouldBe 2
                val audit = settlementAudit()
                audit.map { it.event } shouldBe listOf("PREPARED", "RELEASED")
                audit.last().paidCount shouldBe 2
                audit.last().lineCount shouldBe 6
                audit.last().releaserIsBeneficiary shouldBe false
                (audit.last().totalAmount ?: "") shouldBe "25.00"
            }
        }

        test("a person sees only their own recognition, with the committee, the date and the ledger link") {
            tenderTestApplication {
                val w = world()
                prepareAndRelease(w)
                val mine = reward(member = w.chair) { listMyMeetingRewardLines() }
                mine.size shouldBe 1
                mine.single().amount.compareTo(amount("15.00")) shouldBe 0
                mine.single().bonusAmount.compareTo(amount("5.00")) shouldBe 0
                mine.single().functionRole shouldBe CommitteeRole.CHAIR
                mine.single().meetingId shouldBe w.meeting.toString()
                mine.single().meetingDate.toString() shouldBe "2026-10-20"
                (mine.single().ledgerEntryId in ledgerRows(w.meeting).map { it[LtrLedgerEntryTable.id].toString() }) shouldBe true
                reward(member = w.plain) { listMyMeetingRewardLines() }.single().amount.compareTo(amount("10.00")) shouldBe 0
                reward(member = w.excused) { listMyMeetingRewardLines() } shouldBe emptyList()
                reward(member = w.adminA) { listMyMeetingRewardLines(limit = 1_000_000) } shouldBe emptyList()
            }
        }

        test("the preparer cannot release (four eyes); nothing is booked until a second administrator does") {
            tenderTestApplication {
                val w = world()
                val preview = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                val prepared =
                    reward(
                        member = w.adminA,
                    ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                prepared.status shouldBe MeetingRewardSettlementStatus.PREPARED
                prepared.canRelease shouldBe false
                prepared.blockedReason shouldBe network.lapis.cloud.shared.domain.MeetingRewardBlockedReason.FOUR_EYES
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminA,
                    ) { releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                }.message shouldBe FOUR_EYES_REQUIRED
                ledgerRows(w.meeting).size shouldBe 0
                reward(member = w.adminB) { previewMeetingRewardSettlement(w.meeting.toString()) }.canRelease shouldBe true
                reward(member = w.adminB) {
                    releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!)
                }.status shouldBe
                    MeetingRewardSettlementStatus.RELEASED
            }
        }

        test("a wrong hash is refused on prepare and on release, nothing is booked") {
            tenderTestApplication {
                val w = world()
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminA,
                    ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = "nope") }
                }.message shouldBe
                    PREVIEW_STALE
                val preview = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                reward(
                    member = w.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminB,
                    ) { releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = "nope") }
                }.message shouldBe
                    PREVIEW_STALE
                ledgerRows(w.meeting).size shouldBe 0
            }
        }

        test("the attendance, a member status or the rules changing between preparation and release stop the release; nothing is booked") {
            tenderTestApplication {
                // the attendance moved away from the confirmed Protokoll
                val a = world()
                val previewA = reward(member = a.adminA) { previewMeetingRewardSettlement(a.meeting.toString()) }
                reward(
                    member = a.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = a.meeting.toString(), previewHash = previewA.previewHash!!) }
                setAttendance(meeting = a.meeting, member = a.plain, status = AttendanceStatus.UNEXCUSED)
                shouldThrow<ConflictException> {
                    reward(
                        member = a.adminB,
                    ) { releaseMeetingRewardSettlement(meetingId = a.meeting.toString(), previewHash = previewA.previewHash!!) }
                }
                ledgerRows(a.meeting).size shouldBe 0
                fx.resetRewards()
                // a recognised member was withdrawn meanwhile
                val b = world()
                val previewB = reward(member = b.adminA) { previewMeetingRewardSettlement(b.meeting.toString()) }
                reward(
                    member = b.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = b.meeting.toString(), previewHash = previewB.previewHash!!) }
                transaction { MemberTable.update({ MemberTable.id eq b.plain }) { it[status] = MemberStatus.WITHDRAWN } }
                shouldThrow<ConflictException> {
                    reward(
                        member = b.adminB,
                    ) { releaseMeetingRewardSettlement(meetingId = b.meeting.toString(), previewHash = previewB.previewHash!!) }
                }.message shouldBe
                    PREVIEW_STALE
                ledgerRows(b.meeting).size shouldBe 0
                fx.resetRewards()
                // new rules were confirmed meanwhile
                val c = world()
                val previewC = reward(member = c.adminA) { previewMeetingRewardSettlement(c.meeting.toString()) }
                reward(
                    member = c.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = c.meeting.toString(), previewHash = previewC.previewHash!!) }
                seedPolicy(
                    proposer = c.adminA,
                    approver = c.adminB,
                    resolution = c.resolution,
                    committees = setOf(c.committee),
                    base = "11.00",
                )
                shouldThrow<ConflictException> {
                    reward(
                        member = c.adminB,
                    ) { releaseMeetingRewardSettlement(meetingId = c.meeting.toString(), previewHash = previewC.previewHash!!) }
                }.message shouldBe
                    PREVIEW_STALE
                ledgerRows(c.meeting).size shouldBe 0
            }
        }

        test("a second release is idempotent: still one set of ledger rows and no second audit entry") {
            tenderTestApplication {
                val w = world()
                val first = prepareAndRelease(w)
                val auditAfterFirst = settlementAudit().size
                val again =
                    reward(
                        member = w.adminB,
                    ) { releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = first.previewHash!!) }
                val byThird =
                    reward(
                        member = w.adminC,
                    ) { releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = first.previewHash!!) }
                again.status shouldBe MeetingRewardSettlementStatus.RELEASED
                byThird.status shouldBe MeetingRewardSettlementStatus.RELEASED
                ledgerRows(w.meeting).size shouldBe 2
                settlementAudit().size shouldBe auditAfterFirst
            }
        }

        test("a settlement can be discarded before the release and prepared again; a released one is final") {
            tenderTestApplication {
                val w = world()
                val preview = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                reward(
                    member = w.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminB,
                    ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                }
                val discarded = reward(member = w.adminB) { discardMeetingRewardSettlement(w.meeting.toString()) }
                discarded.status shouldBe null
                transaction { MeetingRewardLineTable.selectAll().count() } shouldBe 0
                settlementAudit().map { it.event } shouldBe listOf("PREPARED", "DISCARDED")
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminA,
                    ) { discardMeetingRewardSettlement(w.meeting.toString()) }
                }.message shouldBe
                    NOT_AVAILABLE
                val released = prepareAndRelease(w)
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminA,
                    ) { discardMeetingRewardSettlement(w.meeting.toString()) }
                }.message shouldBe
                    ALREADY_RELEASED
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminA,
                    ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = released.previewHash!!) }
                }.message shouldBe ALREADY_RELEASED
                ledgerRows(w.meeting).size shouldBe 2
            }
        }

        test("the limit per person and year reduces to the rest, then stops; a new year starts again") {
            tenderTestApplication {
                val w = world(capYear = "20.00")
                // meeting 1: chair 15, plain 10
                prepareAndRelease(w)
                // meeting 2: chair has 5 left (reduced), plain has 10 left
                val m2 = anotherMeeting(w, listOf(w.chair, w.plain))
                val second = prepareAndRelease(w, meeting = m2)
                second.line(w.chair).amount.compareTo(amount("5.00")) shouldBe 0
                second.line(w.chair).reduced shouldBe true
                second.line(w.chair).reason shouldBe MeetingRewardLineReason.CAP_REDUCED
                second.line(w.plain).amount.compareTo(amount("10.00")) shouldBe 0
                // meeting 3: both are at the limit now
                val m3 = anotherMeeting(w, listOf(w.chair, w.plain), at = LocalDateTime(2026, 12, 31, 18, 0))
                val third = prepareAndRelease(w, meeting = m3)
                third.line(w.chair).reason shouldBe MeetingRewardLineReason.CAP_REACHED
                third.line(w.plain).reason shouldBe MeetingRewardLineReason.CAP_REACHED
                ledgerRows(m3).size shouldBe 0
                balance(w.chair).compareTo(amount("20.00")) shouldBe 0
                balance(w.plain).compareTo(amount("20.00")) shouldBe 0
                // 1 January is the next year
                val m4 = anotherMeeting(w, listOf(w.chair), at = LocalDateTime(2027, 1, 1, 10, 0))
                val fourth = prepareAndRelease(w, meeting = m4)
                fourth.line(w.chair).amount.compareTo(amount("15.00")) shouldBe 0
                balance(w.chair).compareTo(amount("35.00")) shouldBe 0
            }
        }

        test("a total above the limit per meeting is refused, not reduced") {
            tenderTestApplication {
                val w = world(capMeeting = "20.00")
                val preview = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                shouldThrow<ConflictException> {
                    reward(
                        member = w.adminA,
                    ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                }.message shouldBe
                    MEETING_CAP_EXCEEDED
                transaction { MeetingRewardSettlementTable.selectAll().count() } shouldBe 0
            }
        }

        test("a guest, a friend and a withdrawn member are never recognised even when marked present") {
            tenderTestApplication {
                val w = world()
                val ineligible =
                    listOf(
                        MemberStatus.GUEST,
                        MemberStatus.FRIEND,
                        MemberStatus.WITHDRAWN,
                    ).map { fx.member(label = "x-$it", status = it) }
                ineligible.forEach { setAttendance(meeting = w.meeting, member = it, status = AttendanceStatus.PRESENT) }
                dropProtocol(w.meeting)
                confirmProtocol(meeting = w.meeting, by = w.adminA)
                val view = prepareAndRelease(w)
                ineligible.forEach {
                    view.line(it).reason shouldBe MeetingRewardLineReason.MEMBER_NOT_ELIGIBLE
                    balance(it).compareTo(BigDecimal.ZERO) shouldBe 0
                }
                ledgerRows(w.meeting).size shouldBe 2
            }
        }

        test("the function bonus follows the role on the date of the meeting") {
            tenderTestApplication {
                val w = world()
                val formerChair = fx.member(label = "formerChair")
                val futureChair = fx.member(label = "futureChair")
                seatWithRole(
                    committee = w.committee,
                    member = formerChair,
                    role = CommitteeRole.CHAIR,
                    since = kotlinx.datetime.LocalDate(2026, 1, 1),
                    until = kotlinx.datetime.LocalDate(2026, 10, 19),
                )
                seatWithRole(
                    committee = w.committee,
                    member = futureChair,
                    role = CommitteeRole.CHAIR,
                    since = kotlinx.datetime.LocalDate(2026, 10, 21),
                )
                val treasurer = fx.member(label = "treasurer")
                seatWithRole(committee = w.committee, member = treasurer, role = CommitteeRole.TREASURER)
                listOf(
                    formerChair,
                    futureChair,
                    treasurer,
                ).forEach { setAttendance(meeting = w.meeting, member = it, status = AttendanceStatus.PRESENT) }
                dropProtocol(w.meeting)
                confirmProtocol(meeting = w.meeting, by = w.adminA)
                val view = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                view.line(formerChair).bonusAmount.compareTo(BigDecimal.ZERO) shouldBe 0
                view.line(futureChair).bonusAmount.compareTo(BigDecimal.ZERO) shouldBe 0
                view.line(treasurer).bonusAmount.compareTo(BigDecimal.ZERO) shouldBe 0
                view.line(treasurer).amount.compareTo(amount("10.00")) shouldBe 0
                view.line(w.chair).bonusAmount.compareTo(amount("5.00")) shouldBe 0
            }
        }

        test("an administrator who is recognised themselves may release; the audit entry says so") {
            tenderTestApplication {
                val w = world()
                setAttendance(meeting = w.meeting, member = w.adminB, status = AttendanceStatus.PRESENT)
                dropProtocol(w.meeting)
                confirmProtocol(meeting = w.meeting, by = w.adminA)
                val view = reward(member = w.adminB) { previewMeetingRewardSettlement(w.meeting.toString()) }
                view.callerIsBeneficiary shouldBe true
                reward(
                    member = w.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = view.previewHash!!) }
                reward(
                    member = w.adminB,
                ) { releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = view.previewHash!!) }
                settlementAudit().last().releaserIsBeneficiary shouldBe true
                balance(w.adminB).compareTo(amount("10.00")) shouldBe 0
            }
        }

        test("two parallel releases of the same settlement book exactly once") {
            tenderTestApplication {
                val w = world()
                val preview = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                reward(
                    member = w.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                val results =
                    coroutineScope {
                        listOf(w.adminB, w.adminC)
                            .map { who ->
                                async(
                                    Dispatchers.IO,
                                ) {
                                    rewardAttempt(
                                        member = who,
                                    ) {
                                        releaseMeetingRewardSettlement(
                                            meetingId = w.meeting.toString(),
                                            previewHash = preview.previewHash!!,
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                ledgerRows(w.meeting).size shouldBe 2
                if (db.isPostgres) {
                    results.forEach { it.isSuccess shouldBe true }
                } else {
                    results.filter { it.isFailure }.forEach { it.exceptionOrNull().shouldBeInstanceOf<ConflictException>() }
                    (results.count { it.isSuccess } >= 1) shouldBe true
                }
                balance(w.chair).compareTo(amount("15.00")) shouldBe 0
            }
        }

        test("two parallel preparations leave exactly one settlement") {
            tenderTestApplication {
                val w = world()
                val preview = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                val results =
                    coroutineScope {
                        listOf(w.adminA, w.adminB)
                            .map { who ->
                                async(
                                    Dispatchers.IO,
                                ) {
                                    rewardAttempt(
                                        member = who,
                                    ) {
                                        prepareMeetingRewardSettlement(
                                            meetingId = w.meeting.toString(),
                                            previewHash = preview.previewHash!!,
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                transaction { MeetingRewardSettlementTable.selectAll().count() } shouldBe 1
                (results.count { it.isSuccess } >= 1) shouldBe true
                results.filter { it.isFailure }.forEach { it.exceptionOrNull().shouldBeInstanceOf<ConflictException>() }
            }
        }

        test("two parallel releases of two meetings with the same person keep the yearly limit") {
            tenderTestApplication {
                val w = world(capYear = "15.00")
                val m2 = anotherMeeting(w, listOf(w.plain))
                val p1 = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                reward(
                    member = w.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = p1.previewHash!!) }
                val p2 = reward(member = w.adminA) { previewMeetingRewardSettlement(m2.toString()) }
                reward(member = w.adminA) { prepareMeetingRewardSettlement(meetingId = m2.toString(), previewHash = p2.previewHash!!) }
                val results =
                    coroutineScope {
                        listOf(w.adminB to (w.meeting to p1.previewHash!!), w.adminC to (m2 to p2.previewHash!!))
                            .map { (who, target) ->
                                async(
                                    Dispatchers.IO,
                                ) {
                                    rewardAttempt(
                                        member = who,
                                    ) { releaseMeetingRewardSettlement(meetingId = target.first.toString(), previewHash = target.second) }
                                }
                            }.awaitAll()
                    }
                results.filter { it.isFailure }.forEach { it.exceptionOrNull().shouldBeInstanceOf<ConflictException>() }
                (results.count { it.isSuccess } >= 1) shouldBe true
                // whatever happened, the person never got more than the limit of the year
                (balance(w.plain) <= amount("15.00")) shouldBe true
                (balance(w.plain) >= amount("10.00")) shouldBe true
                if (db.isPostgres) {
                    // the loser recomputed 5.00 on the second look: its stored figures are stale, so it was refused, never booked above the limit
                    results.count { it.isSuccess } shouldBe 1
                    balance(w.plain).compareTo(amount("10.00")) shouldBe 0
                }
            }
        }

        test("a rule confirmation in parallel with a release causes no deadlock and no half state") {
            tenderTestApplication {
                val w = world()
                val preview = reward(member = w.adminA) { previewMeetingRewardSettlement(w.meeting.toString()) }
                reward(
                    member = w.adminA,
                ) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!) }
                val open =
                    reward(member = w.adminA) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = w.resolution, values = valuesDto(committees = listOf(w.committee), base = "12.00")),
                        )
                    }.openProposal!!
                val results =
                    coroutineScope {
                        listOf(
                            async(
                                Dispatchers.IO,
                            ) {
                                rewardAttempt(member = w.adminB) {
                                    releaseMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = preview.previewHash!!)
                                }
                            },
                            async(Dispatchers.IO) { rewardAttempt(member = w.adminC) { confirmMeetingRewardPolicyChange(open.id) } },
                        ).awaitAll()
                    }
                results.filter { it.isFailure }.forEach { it.exceptionOrNull().shouldBeInstanceOf<ConflictException>() }
                val booked = ledgerRows(w.meeting).size
                (booked == 0 || booked == 2) shouldBe true
                transaction { MeetingRewardSettlementTable.selectAll().single()[MeetingRewardSettlementTable.status] }.let {
                    (it == MeetingRewardSettlementStatus.PREPARED || it == MeetingRewardSettlementStatus.RELEASED) shouldBe true
                }
            }
        }

        test("no log-relevant text of a failed call carries a name or an amount") {
            tenderTestApplication {
                val w = world(enabled = false)
                val e =
                    shouldThrow<ConflictException> {
                        reward(member = w.adminA) { prepareMeetingRewardSettlement(meetingId = w.meeting.toString(), previewHash = "x") }
                    }
                e.message!! shouldNotContain "Tender-Test"
                e.message!! shouldNotContain "10.00"
            }
        }
    })

class MeetingRewardSettlementTest : MeetingRewardSettlementScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MeetingRewardSettlementPostgresTest : MeetingRewardSettlementScenarios(TestDatabase.Postgres())

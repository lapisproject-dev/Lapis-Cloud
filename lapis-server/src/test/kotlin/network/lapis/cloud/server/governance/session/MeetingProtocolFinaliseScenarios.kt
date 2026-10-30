package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolConfirmOutcome
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolFinaliseOutcome
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * V1.9.240 -- finalising, confirming, correcting, the approval item and the tasks, on H2 and on PostgreSQL: the state machine, the roles (one
 * person holding both), races (one version only, one confirmation per role), immutability, the PDF and its link, the audit entries (no text, no
 * name), the tasks of a person and the one-time approval item.
 */
abstract class MeetingProtocolFinaliseScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = SessionFixture()
        val storage = newStorageRoot()
        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fx.cleanup()
            storage.deleteRecursively()
            db.deactivate()
        }

        fun finalOps() = fx.finalOps(storage)

        fun SessionScene.id() = meetingId.toString()

        test("V130 runs a second time on the migrated schema without an error") {
            val text =
                MeetingProtocolFinaliseScenarios::class.java
                    .getResourceAsStream("/db/migration/V130__meeting_protocol_versions.sql")!!
                    .bufferedReader()
                    .readText()
            val statements =
                text
                    .lines()
                    .filterNot { it.trim().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            transaction { statements.forEach { exec(it) } }
        }

        test("the chair finalises, the minute taker confirms: AWAITING, then CONFIRMED with a PDF that is linked and downloadable") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            val first = s.finaliseAs(final = f, who = s.lead)
            first.outcome shouldBe ProtocolFinaliseOutcome.FINALISED
            first.version!!.status shouldBe ProtocolVersionStatus.AWAITING_CONFIRMATION
            first.version!!.missingRoles shouldBe setOf(ProtocolConfirmationRole.MINUTES)
            first.version!!.pdfAvailable shouldBe false
            val versionId = first.version!!.versionId
            // The draft is locked; every write refuses.
            val p = fx.protocolOps()
            shouldThrow<ConflictException> {
                p.saveBlock(current = s.lead, meetingId = s.id(), agendaItemId = s.agenda[0].toString(), expectedVersion = 1, body = "neu")
            }
            val confirmed = f.confirm(current = s.minutes, meetingId = s.id(), versionId = versionId)
            confirmed.outcome shouldBe ProtocolConfirmOutcome.FULLY_CONFIRMED
            confirmed.version!!.status shouldBe ProtocolVersionStatus.CONFIRMED
            confirmed.version!!.pdfAvailable shouldBe true
            confirmed.version!!.confirmations shouldHaveSize 2
            val location = f.pdfLocation(current = s.participant, versionId = versionId)!!
            val bytes = storage.resolve(location.storageKey).readBytes()
            String(bytes, 0, 5, Charsets.ISO_8859_1) shouldBe "%PDF-"
            transaction {
                val meeting = MeetingTable.selectAll().where { MeetingTable.id eq s.meetingId }.single()
                (meeting[MeetingTable.protocolDocumentId] != null) shouldBe true
                DocumentTable
                    .selectAll()
                    .where { DocumentTable.id eq meeting[MeetingTable.protocolDocumentId]!! }
                    .single()[DocumentTable.title] shouldBe "Protokoll ${transaction {
                    network.lapis.cloud.server.db.generated.CommitteeTable
                        .selectAll()
                        .where { network.lapis.cloud.server.db.generated.CommitteeTable.id eq s.committee }
                        .single()[network.lapis.cloud.server.db.generated.CommitteeTable.name]
                }} 04.05.2030"
            }
            // Confirming again is an ordinary answer, not a second row.
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = versionId).outcome shouldBe ProtocolConfirmOutcome.NOT_AWAITING
        }

        test("the minute taker finalises, the chair confirms; a participant and a stranger can do neither") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            val hash = f.preview(current = s.minutes, meetingId = s.id()).contentSha256
            hash.length shouldBe 64
            f.preview(current = s.participant, meetingId = s.id()).contentSha256 shouldBe ""
            shouldThrow<ForbiddenException> { f.finalise(current = s.participant, meetingId = s.id(), expectedContentSha256 = hash) }
            val stranger = fx.newMember()
            shouldThrow<NotFoundException> { f.finalise(current = stranger, meetingId = s.id(), expectedContentSha256 = hash) }
            shouldThrow<NotFoundException> { f.preview(current = stranger, meetingId = s.id()) }
            val result = f.finalise(current = s.minutes, meetingId = s.id(), expectedContentSha256 = hash)
            result.outcome shouldBe ProtocolFinaliseOutcome.FINALISED
            result.version!!.missingRoles shouldBe setOf(ProtocolConfirmationRole.LEAD)
            shouldThrow<ForbiddenException> {
                f.confirm(current = s.participant, meetingId = s.id(), versionId = result.version!!.versionId)
            }
            f.confirm(current = s.lead, meetingId = s.id(), versionId = result.version!!.versionId).outcome shouldBe
                ProtocolConfirmOutcome.FULLY_CONFIRMED
        }

        test("not opened, empty, stale and already finalised are answers, not exceptions") {
            val s = fx.scene()
            val f = finalOps()
            val hash = "0".repeat(64)
            f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = hash).outcome shouldBe
                ProtocolFinaliseOutcome.NOT_OPENED
            f.preview(current = s.lead, meetingId = s.id()).blockedReason shouldBe ProtocolFinaliseOutcome.NOT_OPENED
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            val emptyHash = f.preview(current = s.lead, meetingId = s.id()).contentSha256
            f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = emptyHash).outcome shouldBe
                ProtocolFinaliseOutcome.EMPTY
            shouldThrow<BadRequestException> { f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = "nonsense") }
            val seen = f.preview(current = s.lead, meetingId = s.id()).contentSha256
            fx.protocolOps().saveBlock(
                current = s.lead,
                meetingId = s.id(),
                agendaItemId = s.agenda[0].toString(),
                expectedVersion = 0,
                body = "MSX Text",
            )
            val stale = f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = seen)
            stale.outcome shouldBe ProtocolFinaliseOutcome.STALE
            val current = stale.currentSha256!!
            f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = current).outcome shouldBe
                ProtocolFinaliseOutcome.FINALISED
            f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = current).outcome shouldBe
                ProtocolFinaliseOutcome.ALREADY_FINALISED
            versionRows(s.meetingId) shouldHaveSize 1
        }

        test("the preview of an empty draft says EMPTY and cannot be finalised; a body text lifts it; the other blocked reasons win") {
            val s = fx.scene()
            val f = finalOps()
            // NOT_OPENED wins over EMPTY: the Sitzung is not open and the draft is empty as well.
            val notOpened = f.preview(current = s.lead, meetingId = s.id())
            notOpened.blockedReason shouldBe ProtocolFinaliseOutcome.NOT_OPENED
            notOpened.canFinalise shouldBe false
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            val empty = f.preview(current = s.lead, meetingId = s.id())
            empty.blockedReason shouldBe ProtocolFinaliseOutcome.EMPTY
            empty.canFinalise shouldBe false
            // The server stays the authority: finalising an empty draft is still refused.
            f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = empty.contentSha256).outcome shouldBe
                ProtocolFinaliseOutcome.EMPTY
            fx.protocolOps().saveBlock(
                current = s.lead,
                meetingId = s.id(),
                agendaItemId = s.agenda[0].toString(),
                expectedVersion = 0,
                body = "MSX Text fuer die Vorschau",
            )
            val filled = f.preview(current = s.lead, meetingId = s.id())
            filled.blockedReason shouldBe null
            filled.canFinalise shouldBe true
            val result = f.finalise(current = s.lead, meetingId = s.id(), expectedContentSha256 = filled.contentSha256)
            result.outcome shouldBe ProtocolFinaliseOutcome.FINALISED
            // ALREADY_FINALISED wins over any content check.
            f.preview(current = s.lead, meetingId = s.id()).blockedReason shouldBe ProtocolFinaliseOutcome.ALREADY_FINALISED
        }

        test("two parallel finalise calls with the same hash produce exactly one version") {
            val s = fx.scene()
            val f = finalOps()
            val hash = s.openAndWrite(final = f)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val gate = CountDownLatch(1)
                val results =
                    listOf(s.lead, s.minutes).map { who ->
                        pool.submit<ProtocolFinaliseOutcome> {
                            gate.await()
                            f.finalise(current = who, meetingId = s.id(), expectedContentSha256 = hash).outcome
                        }
                    }
                gate.countDown()
                val outcomes = results.map { it.get(60, TimeUnit.SECONDS) }
                outcomes.count { it == ProtocolFinaliseOutcome.FINALISED } shouldBe 1
                outcomes.count { it == ProtocolFinaliseOutcome.ALREADY_FINALISED } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
            versionRows(s.meetingId) shouldHaveSize 1
        }

        test("two parallel confirmations of the same role leave one row") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            val versionId = s.finaliseAs(final = f, who = s.lead).version!!.versionId
            val pool = Executors.newFixedThreadPool(2)
            try {
                val gate = CountDownLatch(1)
                val outcomes =
                    (1..2)
                        .map {
                            pool.submit<ProtocolConfirmOutcome> {
                                gate.await()
                                f.confirm(current = s.minutes, meetingId = s.id(), versionId = versionId).outcome
                            }
                        }.also { gate.countDown() }
                        .map { it.get(60, TimeUnit.SECONDS) }
                outcomes.count { it == ProtocolConfirmOutcome.FULLY_CONFIRMED } shouldBe 1
                // The loser sees either the already confirmed role or an already confirmed version -- an answer, never a second row or an error.
                (
                    outcomes.single { it != ProtocolConfirmOutcome.FULLY_CONFIRMED } in
                        setOf(ProtocolConfirmOutcome.NOT_AWAITING, ProtocolConfirmOutcome.ALREADY_CONFIRMED)
                ) shouldBe true
            } finally {
                pool.shutdownNow()
            }
            confirmationCount(Uuid.parse(versionId)) shouldBe 2
        }

        test("somebody who is chair AND minute taker confirms both roles at once; with no minute taker the chair alone is enough") {
            val s = fx.scene()
            val (both, items) =
                fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.lead, agenda = listOf("A"))
            val room = fx.newRoom(meetingId = both, creator = s.lead)
            val f = finalOps()
            runBlocking { s.ops.open(current = s.lead, roomId = room, meetingId = both) }
            fx.protocolOps().saveBlock(
                current = s.lead,
                meetingId = both.toString(),
                agendaItemId = items[0].toString(),
                expectedVersion = 0,
                body = "MSX beide Rollen",
            )
            val hash = f.preview(current = s.lead, meetingId = both.toString()).contentSha256
            val result = f.finalise(current = s.lead, meetingId = both.toString(), expectedContentSha256 = hash)
            result.outcome shouldBe ProtocolFinaliseOutcome.CONFIRMED
            result.version!!
                .confirmations
                .map { it.role }
                .toSet() shouldBe
                setOf(ProtocolConfirmationRole.LEAD, ProtocolConfirmationRole.MINUTES)

            val (solo, soloItems) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = null, agenda = listOf("A"))
            val soloRoom = fx.newRoom(meetingId = solo, creator = s.lead)
            runBlocking { s.ops.open(current = s.lead, roomId = soloRoom, meetingId = solo) }
            fx.protocolOps().saveBlock(
                current = s.lead,
                meetingId = solo.toString(),
                agendaItemId = soloItems[0].toString(),
                expectedVersion = 0,
                body = "MSX ohne Protokollfuehrung",
            )
            val soloHash = f.preview(current = s.lead, meetingId = solo.toString()).contentSha256
            val soloResult = f.finalise(current = s.lead, meetingId = solo.toString(), expectedContentSha256 = soloHash)
            soloResult.outcome shouldBe ProtocolFinaliseOutcome.CONFIRMED
            soloResult.version!!.minutesRequired shouldBe false
        }

        test("a version of another Sitzung is as unknown as a random id") {
            val a = fx.scene()
            val b = fx.scene()
            val f = finalOps()
            a.openAndWrite(final = f)
            b.openAndWrite(final = f)
            val versionOfB = b.finaliseAs(final = f, who = b.lead).version!!.versionId
            shouldThrow<NotFoundException> { f.confirm(current = a.minutes, meetingId = a.id(), versionId = versionOfB) }
            shouldThrow<NotFoundException> { f.confirm(current = a.minutes, meetingId = a.id(), versionId = Uuid.random().toString()) }
            shouldThrow<NotFoundException> { f.confirm(current = a.minutes, meetingId = a.id(), versionId = "nope") }
            f.pdfLocation(current = a.lead, versionId = versionOfB) shouldBe null
            f.pdfLocation(current = b.lead, versionId = versionOfB) shouldBe null // no PDF yet (AWAITING)
        }

        test("after finalising, later changes to the live rows do not change the version or the read model") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f, text = "MSX unveraenderlich")
            val result = s.finaliseAs(final = f, who = s.lead)
            val before = transaction { ProtocolVersionTable.selectAll().where { ProtocolVersionTable.meetingId eq s.meetingId }.single() }
            fx.putAttendance(
                meetingId = s.meetingId,
                member = s.peers[0],
                status = network.lapis.cloud.shared.domain.AttendanceStatus.PRESENT,
            )
            val after = transaction { ProtocolVersionTable.selectAll().where { ProtocolVersionTable.meetingId eq s.meetingId }.single() }
            after[ProtocolVersionTable.contentJson] shouldBe before[ProtocolVersionTable.contentJson]
            after[ProtocolVersionTable.contentSha256] shouldBe result.version!!.contentSha256
            val read = fx.protocolOps().get(current = s.participant, meetingId = s.id())
            read.phase shouldBe ProtocolDraftPhase.LOCKED
            read.attendance.present.size shouldBe 0 // the snapshot, not the live attendance
            read.items[0].body shouldBe "MSX unveraenderlich"
            read.editable shouldBe false
        }

        test("a correction starts from the newest version; its note and number are kept; version 1 stays") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f, text = "MSX Fassung eins")
            val v1 = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v1.versionId)
            shouldThrow<BadRequestException> { f.startCorrection(current = s.lead, meetingId = s.id(), note = "zu kurz") }
            shouldThrow<ForbiddenException> { f.startCorrection(current = s.participant, meetingId = s.id(), note = "N".repeat(30)) }
            val draft = f.startCorrection(current = s.lead, meetingId = s.id(), note = "Der Verlauf von TOP 1 war unvollstaendig.")
            draft.phase shouldBe ProtocolDraftPhase.DRAFT
            draft.correctionOfVersionNo shouldBe 1
            draft.items[0].body shouldBe "MSX Fassung eins"
            draft.editable shouldBe true
            shouldThrow<ConflictException> { f.startCorrection(current = s.lead, meetingId = s.id(), note = "N".repeat(30)) }
            val saved =
                fx.protocolOps().saveBlock(
                    current = s.lead,
                    meetingId = s.id(),
                    agendaItemId = s.agenda[0].toString(),
                    expectedVersion = draft.items[0].version,
                    body = "MSX Fassung zwei",
                )
            saved.saved shouldBe true
            val v2 = s.finaliseAs(final = f, who = s.lead)
            v2.outcome shouldBe ProtocolFinaliseOutcome.FINALISED
            v2.version!!.versionNo shouldBe 2
            v2.version!!.correctionNote shouldBe "Der Verlauf von TOP 1 war unvollstaendig."
            val rows = versionRows(s.meetingId).sortedBy { it[ProtocolVersionTable.versionNo] }
            rows.map { it[ProtocolVersionTable.status] } shouldBe
                listOf(ProtocolVersionStatus.CONFIRMED, ProtocolVersionStatus.AWAITING_CONFIRMATION)
            rows[0][ProtocolVersionTable.contentJson] shouldNotContain "Fassung zwei"
            f.versions(current = s.participant, meetingId = s.id()).versions.map { it.versionNo } shouldBe listOf(2, 1)
        }

        test("a correction may be started while a version waits for a confirmation that never comes: that version is superseded") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            val v1 = s.finaliseAs(final = f, who = s.lead).version!!
            f.startCorrection(current = s.minutes, meetingId = s.id(), note = "Die Protokollfuehrung ist ausgeschieden.")
            versionRows(s.meetingId).single()[ProtocolVersionTable.status] shouldBe ProtocolVersionStatus.SUPERSEDED
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v1.versionId).outcome shouldBe
                ProtocolConfirmOutcome.NOT_AWAITING
            confirmationCount(Uuid.parse(v1.versionId)) shouldBe 1
        }

        test("the audit entries of finalising and confirming hold ids and the hash, never a text or a name") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f, text = "MSX geheimer Verlaufstext")
            val v = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v.versionId)
            val joined = fx.auditSnapshots(s.meetingId).joinToString("\n")
            joined shouldNotContain "geheimer Verlaufstext"
            joined shouldNotContain "MSX Chair"
            joined shouldNotContain "MSX Minutes"
            joined.contains("PROTOCOL_FINALISED") shouldBe true
            joined.contains("PROTOCOL_CONFIRMED") shouldBe true
            joined.contains("PROTOCOL_PDF_ARCHIVED") shouldBe true
            joined.contains(v.contentSha256) shouldBe true
        }

        test("tasks: a person sees only their own, can tick them off, and a task dropped by a correction disappears") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            val p = fx.protocolOps()
            p.addTask(
                current = s.lead,
                meetingId = s.id(),
                input =
                    ProtocolTaskInput(
                        agendaItemId = s.agenda[0].toString(),
                        assigneeMemberId = s.peers[0].memberId.toString(),
                        description = "MSX Aufgabe eins",
                        dueDate = LocalDate(2030, 6, 1),
                    ),
            )
            val v1 = s.finaliseAs(final = f, who = s.lead).version!!
            f.myTasks(s.peers[0]) shouldHaveSize 0 // not CONFIRMED yet
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v1.versionId)
            val mine = f.myTasks(s.peers[0])
            mine shouldHaveSize 1
            mine[0].done shouldBe false
            f.myTasks(s.peers[1]) shouldHaveSize 0
            f.myTasks(s.lead) shouldHaveSize 0
            shouldThrow<NotFoundException> { f.setTaskDone(current = s.peers[1], taskKey = mine[0].taskKey, done = true) }
            shouldThrow<NotFoundException> { f.setTaskDone(current = s.peers[0], taskKey = Uuid.random().toString(), done = true) }
            f.setTaskDone(current = s.peers[0], taskKey = mine[0].taskKey, done = true).done shouldBe true
            f.setTaskDone(current = s.peers[0], taskKey = mine[0].taskKey, done = false).done shouldBe false
            // The correction keeps the task's key and drops it again.
            val draft = f.startCorrection(current = s.lead, meetingId = s.id(), note = "Die Aufgabe wird entfernt, sie war falsch.")
            draft.items[0].tasks shouldHaveSize 1
            p.removeTask(current = s.lead, meetingId = s.id(), taskId = draft.items[0].tasks[0].taskId)
            val v2 = s.finaliseAs(final = f, who = s.lead).version!!
            f.myTasks(s.peers[0]) shouldHaveSize 1 // v2 is not confirmed yet: v1 is still the newest confirmed
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v2.versionId)
            f.myTasks(s.peers[0]) shouldHaveSize 0
        }

        test("approval item: put on the next Sitzung exactly once, in either order of events; the result is recorded by the lead") {
            val s = fx.scene()
            val f = finalOps()
            // A later Sitzung that exists BEFORE the Protokoll is confirmed.
            val (next, nextItems) =
                fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes, agenda = listOf("Z"))
            reschedule(meetingId = next, at = LocalDateTime(2030, 8, 1, 19, 0))
            s.openAndWrite(final = f)
            val v1 = s.finaliseAs(final = f, who = s.lead).version!!
            approvalItemsOf(next) shouldHaveSize 0 // not confirmed yet
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v1.versionId)
            val items = approvalItemsOf(next)
            items shouldHaveSize 1
            items[0].second shouldBe 2 // position 1 is taken by "Z"
            // A correction and its confirmation do not offer the same Protokoll again.
            f.startCorrection(current = s.lead, meetingId = s.id(), note = "Eine Korrektur ohne Folgen fuer die Genehmigung.")
            val v2 = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v2.versionId)
            approvalItemsOf(next) shouldHaveSize 1
            // A Sitzung created AFTER the confirmation: the (already offered) Protokoll is not offered a second time.
            val (later, _) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes)
            reschedule(meetingId = later, at = LocalDateTime(2030, 9, 1, 19, 0))
            transaction { ProtocolApprovalItems.onMeetingCreated(later) } shouldHaveSize 0
            approvalItemsOf(later) shouldHaveSize 0
            // Record the result in the next Sitzung.
            val itemId = items[0].first
            shouldThrow<ConflictException> {
                f.recordApproval(current = s.lead, agendaItemId = itemId.toString(), result = ProtocolApprovalResult.APPROVED)
            }
            val room = fx.newRoom(meetingId = next, creator = s.lead)
            runBlocking { s.ops.open(current = s.lead, roomId = room, meetingId = next) }
            shouldThrow<ForbiddenException> {
                f.recordApproval(current = s.participant, agendaItemId = itemId.toString(), result = ProtocolApprovalResult.APPROVED)
            }
            shouldThrow<NotFoundException> {
                f.recordApproval(current = s.lead, agendaItemId = nextItems[0].toString(), result = ProtocolApprovalResult.APPROVED)
            }
            val recorded =
                f.recordApproval(
                    current = s.lead,
                    agendaItemId = itemId.toString(),
                    result = ProtocolApprovalResult.APPROVED_WITH_CORRECTIONS,
                )
            recorded.result shouldBe ProtocolApprovalResult.APPROVED_WITH_CORRECTIONS
            recorded.versionNo shouldBe 2
            recorded.correctionPending shouldBe true
        }

        test("a Sitzung created after a confirmed Protokoll gets the approval item from the creation hook") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            val v1 = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v1.versionId)
            val (next, _) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes)
            reschedule(meetingId = next, at = LocalDateTime(2030, 8, 1, 19, 0))
            transaction { ProtocolApprovalItems.onMeetingCreated(next) } shouldHaveSize 1
            approvalItemsOf(next) shouldHaveSize 1
            transaction { ProtocolApprovalItems.onMeetingCreated(next) } shouldHaveSize 0
            approvalItemsOf(next) shouldHaveSize 1
        }

        test("cancelling the Sitzung that carries the approval item releases it: the Protokoll is offered again to the next Sitzung") {
            val s = fx.scene()
            val f = finalOps()
            val (target, _) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes, agenda = listOf("Z"))
            reschedule(meetingId = target, at = LocalDateTime(2031, 3, 1, 19, 0))
            s.openAndWrite(final = f)
            val v = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v.versionId)
            approvalItemsOf(target) shouldHaveSize 1
            // Cancelled with no replacement yet: item and tombstone are gone.
            fx.setStatus(meetingId = target, status = network.lapis.cloud.shared.domain.MeetingStatus.CANCELLED)
            transaction { ProtocolApprovalItems.onMeetingCancelled(target) } shouldHaveSize 0
            approvalItemsOf(target) shouldHaveSize 0
            transaction {
                MeetingTable.selectAll().where { MeetingTable.id eq target }.single()[MeetingTable.protocolApprovalSourceMeetingId] shouldBe
                    null
            }
            // A new Sitzung now gets the approval item (before the fix the cancelled tombstone blocked it for good).
            val (later, _) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes, agenda = listOf("Y"))
            reschedule(meetingId = later, at = LocalDateTime(2031, 4, 1, 19, 0))
            transaction { ProtocolApprovalItems.onMeetingCreated(later) } shouldHaveSize 1
            approvalItemsOf(later) shouldHaveSize 1
            // Cancelling with another upcoming Sitzung already there re-offers at once.
            fx.setStatus(meetingId = later, status = network.lapis.cloud.shared.domain.MeetingStatus.CANCELLED)
            val (third, _) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes, agenda = listOf("X"))
            reschedule(meetingId = third, at = LocalDateTime(2031, 5, 1, 19, 0))
            transaction { ProtocolApprovalItems.onMeetingCancelled(later) } shouldHaveSize 1
            approvalItemsOf(later) shouldHaveSize 0
            approvalItemsOf(third) shouldHaveSize 1
        }

        test("an older confirmed version without a PDF is not archived late: it would become the newest document version") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            val v1 = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v1.versionId)
            // Simulate a failed archiving of F1: detach the PDF link, then correct and confirm F2 (which is archived).
            transaction {
                ProtocolVersionTable.update({ ProtocolVersionTable.id eq v1.versionId.let(Uuid::parse) }) {
                    it[pdfDocumentVersionId] = null
                }
            }
            f.startCorrection(current = s.lead, meetingId = s.id(), note = "Eine Korrektur fuer den Test der Archivierung.")
            val v2 = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.id(), versionId = v2.versionId).version!!.pdfAvailable shouldBe true
            // Retrying F1 does not attach anything.
            val retry = f.confirm(current = s.minutes, meetingId = s.id(), versionId = v1.versionId)
            retry.outcome shouldBe ProtocolConfirmOutcome.NOT_AWAITING
            retry.version!!.pdfAvailable shouldBe false
            retry.version!!.pdfPending shouldBe true
        }

        test("the draft row keeps working: LOCKED after finalising, the correction fields are cleared again") {
            val s = fx.scene()
            val f = finalOps()
            s.openAndWrite(final = f)
            s.finaliseAs(final = f, who = s.lead)
            val draft = transaction { ProtocolDraftTable.selectAll().where { ProtocolDraftTable.meetingId eq s.meetingId }.single() }
            draft[ProtocolDraftTable.phase] shouldBe ProtocolDraftPhase.LOCKED
            draft[ProtocolDraftTable.correctionOfVersionNo] shouldBe null
            transaction { ProtocolDraftTable.update({ ProtocolDraftTable.meetingId eq s.meetingId }) { it[correctionNote] = null } }
        }
    })

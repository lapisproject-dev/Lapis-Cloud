package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import network.lapis.cloud.shared.domain.PROTOCOL_BLOCK_MAX_LENGTH
import network.lapis.cloud.shared.domain.PROTOCOL_MAX_TASKS
import network.lapis.cloud.shared.domain.PROTOCOL_TASK_MAX_LENGTH
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException

/** V1.9.239 -- the writes of the Protokoll draft: versions and races, limits, plain text, tasks, and what the audit entries hold. */
class MeetingProtocolDraftWriteTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun opened(): Triple<SessionScene, MeetingProtocolDraftOperations, String> {
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            return Triple(s, p, s.meetingId.toString())
        }

        fun SessionScene.input(
            description: String = "MSX Aufgabe",
            index: Int = 0,
        ) = ProtocolTaskInput(
            agendaItemId = agenda[index].toString(),
            assigneeMemberId = peers[0].memberId.toString(),
            description = description,
            dueDate = null,
        )

        test("the first save needs version 0 and creates the draft with version 1; a read creates nothing") {
            val (s, p, meeting) = opened()
            val item = s.agenda[0].toString()
            p.get(current = s.lead, meetingId = meeting).items[0].version shouldBe 0
            protocolDraftCount(s.meetingId) shouldBe 0
            val stale = p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 5, body = "x")
            stale.saved shouldBe false
            stale.version shouldBe 0
            protocolDraftCount(s.meetingId) shouldBe 0
            val first = p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 0, body = "Erster Text")
            first.saved shouldBe true
            first.version shouldBe 1
            protocolDraftCount(s.meetingId) shouldBe 1
            val read = p.get(current = s.participant, meetingId = meeting).items[0]
            read.body shouldBe "Erster Text"
            read.version shouldBe 1
        }

        test("an empty first text changes nothing: no draft, no audit entry") {
            val (s, p, meeting) = opened()
            val before = auditCount(s.meetingId)
            val result =
                p.saveBlock(
                    current = s.lead,
                    meetingId = meeting,
                    agendaItemId = s.agenda[0].toString(),
                    expectedVersion = 0,
                    body = "",
                )
            result.saved shouldBe true
            result.version shouldBe 0
            protocolDraftCount(s.meetingId) shouldBe 0
            auditCount(s.meetingId) shouldBe before
        }

        test("a stale version is an ordinary answer with the current text; an unchanged text makes no new version and no audit entry") {
            val (s, p, meeting) = opened()
            val item = s.agenda[0].toString()
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 0, body = "A")
            p.saveBlock(current = s.minutes, meetingId = meeting, agendaItemId = item, expectedVersion = 1, body = "B").version shouldBe 2
            val lost = p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 1, body = "C")
            lost.saved shouldBe false
            lost.version shouldBe 2
            lost.currentBody shouldBe "B"
            val audits = auditCount(s.meetingId)
            val same = p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 2, body = "B")
            same.saved shouldBe true
            same.version shouldBe 2
            auditCount(s.meetingId) shouldBe audits
            // a deliberate overwrite names the newer version
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 2, body = "C").version shouldBe 3
        }

        test("two simultaneous first saves: exactly one wins, the other gets the current text, one draft row exists") {
            val (s, p, meeting) = opened()
            val item = s.agenda[0].toString()
            val results =
                runBlocking {
                    coroutineScope {
                        listOf("von der Leitung", "vom Protokoll")
                            .mapIndexed { index, text ->
                                async(Dispatchers.IO) {
                                    p.saveBlock(
                                        current = if (index == 0) s.lead else s.minutes,
                                        meetingId = meeting,
                                        agendaItemId = item,
                                        expectedVersion = 0,
                                        body = text,
                                    )
                                }
                            }.awaitAll()
                    }
                }
            results.count { it.saved } shouldBe 1
            results.single { !it.saved }.currentBody shouldBe
                p.get(current = s.lead, meetingId = meeting).items[0].body
            protocolDraftCount(s.meetingId) shouldBe 1
            protocolBlockCount(s.meetingId) shouldBe 1
        }

        test("two simultaneous first writes of different kinds create exactly one draft row") {
            val (s, p, meeting) = opened()
            runBlocking {
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) {
                            p.saveBlock(
                                current = s.lead,
                                meetingId = meeting,
                                agendaItemId = s.agenda[0].toString(),
                                expectedVersion = 0,
                                body = "T",
                            )
                        },
                        async(Dispatchers.IO) { p.addTask(current = s.minutes, meetingId = meeting, input = s.input()) },
                    ).awaitAll()
                }
            }
            protocolDraftCount(s.meetingId) shouldBe 1
            protocolTaskCount(s.meetingId) shouldBe 1
        }

        test("the length limit is 8 000 UTF-16 units AFTER cleaning; a character outside the BMP counts twice") {
            val (s, p, meeting) = opened()
            val item = s.agenda[0].toString()
            val max = PROTOCOL_BLOCK_MAX_LENGTH
            p
                .saveBlock(
                    current = s.lead,
                    meetingId = meeting,
                    agendaItemId = item,
                    expectedVersion = 0,
                    body = "a".repeat(max),
                ).saved shouldBe
                true
            shouldThrow<BadRequestException> {
                p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 1, body = "a".repeat(max + 1))
            }
            // control characters do not count: 8 000 letters between zero-width and bidi characters are accepted
            val padded = "​" + "b".repeat(max) + "‮\u0000"
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 1, body = padded).saved shouldBe true
            p.get(current = s.lead, meetingId = meeting).items[0].body shouldBe "b".repeat(max)
            // the unit count is the bound of BOTH engines: a surrogate pair costs two, so 4 000 emoji fit and 4 001 do not
            val emoji = String(Character.toChars(0x1F600))
            p
                .saveBlock(
                    current = s.lead,
                    meetingId = meeting,
                    agendaItemId = item,
                    expectedVersion = 2,
                    body = emoji.repeat(max / 2),
                ).saved shouldBe
                true
            shouldThrow<BadRequestException> {
                p.saveBlock(
                    current = s.lead,
                    meetingId = meeting,
                    agendaItemId = item,
                    expectedVersion = 3,
                    body = emoji.repeat(max / 2 + 1),
                )
            }
            p.get(current = s.lead, meetingId = meeting).items[0].body shouldBe emoji.repeat(max / 2)
        }

        test(
            "text is plain text: tags and wiki syntax stay as typed, control and bidi characters are removed, line breaks are normalised",
        ) {
            val (s, p, meeting) = opened()
            val item = s.agenda[0].toString()
            val raw = "<script>alert(1)</script><img src=x onerror=alert(2)>\r\n[[Beschluss:B-1]]‮evil\u0000\rletzte\tZeile"
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 0, body = raw).saved shouldBe true
            val read = p.get(current = s.participant, meetingId = meeting).items[0].body
            read shouldBe "<script>alert(1)</script><img src=x onerror=alert(2)>\n[[Beschluss:B-1]]evil\nletzte\tZeile"
        }

        test("tasks: description limits, empty description, the 51st task, and removal") {
            val (s, p, meeting) = opened()
            p.addTask(current = s.lead, meetingId = meeting, input = s.input("a".repeat(PROTOCOL_TASK_MAX_LENGTH)))
            shouldThrow<BadRequestException> {
                p.addTask(current = s.lead, meetingId = meeting, input = s.input("a".repeat(PROTOCOL_TASK_MAX_LENGTH + 1)))
            }
            shouldThrow<BadRequestException> { p.addTask(current = s.lead, meetingId = meeting, input = s.input("  ​  ")) }
            repeat(PROTOCOL_MAX_TASKS - 1) { p.addTask(current = s.lead, meetingId = meeting, input = s.input("MSX $it")) }
            protocolTaskCount(s.meetingId) shouldBe PROTOCOL_MAX_TASKS.toLong()
            shouldThrow<ConflictException> { p.addTask(current = s.lead, meetingId = meeting, input = s.input("zu viel")) }
            val first =
                p
                    .get(current = s.lead, meetingId = meeting)
                    .items[0]
                    .tasks
                    .first()
            p.removeTask(current = s.minutes, meetingId = meeting, taskId = first.taskId)
            protocolTaskCount(s.meetingId) shouldBe (PROTOCOL_MAX_TASKS - 1).toLong()
            p.addTask(current = s.lead, meetingId = meeting, input = s.input("wieder Platz"))
        }

        test("a task is shown with the name of the person and in a fixed order") {
            val (s, p, meeting) = opened()
            p.addTask(current = s.lead, meetingId = meeting, input = s.input("MSX erste"))
            p.addTask(current = s.lead, meetingId = meeting, input = s.input("MSX zweite", index = 1))
            val draft = p.get(current = s.participant, meetingId = meeting)
            draft.items[0]
                .tasks
                .single()
                .assigneeName shouldBe "MSX Peer 01"
            draft.items[1]
                .tasks
                .single()
                .description shouldBe "MSX zweite"
            draft.taskCount shouldBe 2
        }

        test("audit entries hold ids and a version only -- never the text, a name or a due date") {
            val (s, p, meeting) = opened()
            val item = s.agenda[0].toString()
            val secretText = "MSX GEHEIMER PROTOKOLLTEXT"
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 0, body = secretText)
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 1, body = "$secretText 2")
            val task = p.addTask(current = s.lead, meetingId = meeting, input = s.input("MSX GEHEIME AUFGABE"))
            p.removeTask(current = s.lead, meetingId = meeting, taskId = task.taskId)
            val snapshots = fx.auditSnapshots(s.meetingId)
            val kinds = snapshots.map { Json.decodeFromString(MeetingSessionAuditSnapshot.serializer(), it).kind }
            kinds.drop(1) shouldBe
                listOf(
                    MeetingSessionAuditKind.PROTOCOL_DRAFT_CREATED,
                    MeetingSessionAuditKind.PROTOCOL_BLOCK_UPDATED,
                    MeetingSessionAuditKind.PROTOCOL_BLOCK_UPDATED,
                    MeetingSessionAuditKind.PROTOCOL_TASK_ADDED,
                    MeetingSessionAuditKind.PROTOCOL_TASK_REMOVED,
                )
            val joined = snapshots.joinToString("\n")
            joined shouldNotContain "MSX GEHEIM"
            joined shouldNotContain "MSX Peer"
            val updated =
                snapshots
                    .map { Json.decodeFromString(MeetingSessionAuditSnapshot.serializer(), it) }
                    .filter { it.kind == MeetingSessionAuditKind.PROTOCOL_BLOCK_UPDATED }
            updated.map { it.blockVersion } shouldBe listOf(1L, 2L)
            updated.all { it.agendaItemId == item } shouldBe true
        }
    })

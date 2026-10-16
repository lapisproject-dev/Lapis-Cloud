package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.testing.ApplicationTestBuilder
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.PulpitRecordingDocumentTable
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRecordingSignal
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.encounterRecordingPacket
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

/**
 * V1.9.143 -- the clock of the pulpit recording ([PulpitRecordingCoordinator.tick]): every precondition that disappears stops the recording (with an
 * automatic audit entry that has no actor), a failing LiveKit never switches the banner off, a restart stops what it does not own, late arrivals learn
 * the state, and a finished file is archived as a BOARD_ONLY document with a marker row.
 */
class PulpitRecordingPollerTest :
    FunSpec({
        val fx = EncounterFixtures()
        val worlds = mutableListOf<PulpitWorld>()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            worlds.forEach { it.close() }
            fx.cleanUp()
            PulpitSettingsFixture.reset()
        }

        fun newWorld(time: TestTimeSource = TestTimeSource()): PulpitWorld =
            PulpitWorld(
                w = fx.tableWorld(tablesEnabled = false, profile = EncounterProfile.CHURCH_SERVICE),
                state = PulpitRecordingState(time),
            ).also { worlds += it }

        fun PulpitWorld.autoReasons(): List<String> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.PULPIT_RECORDING) and (AuditLogEntryTable.entityId eq w.space)
                    }.mapNotNull { row ->
                        row[AuditLogEntryTable.afterSnapshot]
                            ?.takeIf { it.contains("STOPPED_AUTOMATICALLY") && row[AuditLogEntryTable.actorMemberId] == null }
                            ?.substringAfter("\"reason\":\"")
                            ?.substringBefore('"')
                    }
            }

        suspend fun ApplicationTestBuilder.running(
            p: PulpitWorld,
            vararg people: Uuid,
        ) {
            p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit, *people)
            p.asMember(client = client, member = p.w.steward) { it.startPulpitRecording(p.w.spaceId) }.getOrThrow()
        }

        test("the pulpit person loses the office: the file is stopped, the banner goes off, the stop is audited without an actor") {
            val p = newWorld()
            encounterApp {
                running(p, p.w.a)
                transaction {
                    network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
                        .deleteWhere { memberId eq p.w.pulpit }
                }
                p.coordinator.tick()
                p.egress.stopped shouldHaveSize 1
                p.coordinator.isActive(p.roomId) shouldBe false
                p.autoReasons() shouldBe listOf("SOURCE_NOT_PULPIT")
                encounterRecordingPacket(fromParticipant = false, payload = p.recordingPackets().last().third) shouldBe
                    EncounterRecordingSignal(active = false)
            }
        }

        test("instance switched off, room option off and a floor for somebody of the congregation each end the recording") {
            val reasons = listOf("INSTANCE_DISABLED", "ROOM_OPTION_OFF", "FLOOR_GRANTED")
            reasons.forEach { reason ->
                val p = newWorld()
                encounterApp {
                    running(p, p.w.a)
                    when (reason) {
                        "INSTANCE_DISABLED" -> PulpitSettingsFixture.set(enabled = false, noticeVersion = null)
                        "ROOM_OPTION_OFF" -> PulpitSettingsFixture.allowInSpace(spaceId = p.w.space, allowed = false)
                        else ->
                            p.w.rig.floorState
                                .tryGrant(roomId = p.roomId, livekitRoomName = p.roomName, memberId = p.w.a, displayName = "A")
                    }
                    p.coordinator.tick()
                    p.coordinator.isActive(p.roomId) shouldBe false
                    p.egress.stopped shouldHaveSize 1
                    p.autoReasons() shouldBe listOf(reason)
                }
            }
        }

        test("a raised notice version blocks new starts only: a running recording is not interrupted") {
            val p = newWorld()
            encounterApp {
                running(p, p.w.a)
                PulpitSettingsFixture.set(enabled = true, noticeVersion = "0")
                p.coordinator.tick()
                p.coordinator.isActive(p.roomId) shouldBe true
                p.egress.stopped.shouldBeEmpty()
            }
        }

        test("the maximum duration ends a recording") {
            val time = TestTimeSource()
            val p = newWorld(time)
            encounterApp {
                running(p)
                time += 239.minutes
                p.coordinator.tick()
                p.coordinator.isActive(p.roomId) shouldBe true
                time += 2.minutes
                p.coordinator.tick()
                p.coordinator.isActive(p.roomId) shouldBe false
                p.autoReasons() shouldBe listOf("MAX_DURATION")
            }
        }

        test("a closed session ends the recording") {
            val p = newWorld()
            encounterApp {
                running(p)
                p.w.rig
                    .asMember(client = client, member = p.w.steward) { it.closeSpace(p.w.spaceId) }
                    .getOrThrow()
                p.coordinator.tick()
                p.coordinator.isActive(p.roomId) shouldBe false
                p.autoReasons() shouldBe listOf("SESSION_CLOSED")
            }
        }

        test("an unreachable egress service never switches the banner off while a recording runs") {
            val p = newWorld()
            encounterApp {
                running(p)
                val packetsBefore = p.recordingPackets().size
                p.egress.failList = true
                repeat(3) { p.coordinator.tick() }
                p.coordinator.isActive(p.roomId) shouldBe true
                p.recordingPackets() shouldHaveSize packetsBefore
                p.egress.stopped.shouldBeEmpty()
            }
        }

        test("a stop that LiveKit does not confirm keeps the recording on until it does") {
            val p = newWorld()
            encounterApp {
                running(p)
                p.egress.failStop = true
                p.egress.failList = true
                p
                    .asMember(client = client, member = p.w.steward) {
                        it.stopPulpitRecording(p.w.spaceId)
                    }.failure<network.lapis.cloud.shared.rpc.PulpitRecordingUnavailableException>()
                p.coordinator.isActive(p.roomId) shouldBe true
                p.egress.failStop = false
                p.egress.failList = false
                p.asMember(client = client, member = p.w.steward) { it.stopPulpitRecording(p.w.spaceId) }.getOrThrow().active shouldBe false
            }
        }

        test("after a restart the file egresses nobody owns are stopped, streams are left alone and every open room hears 'off'") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                val orphan =
                    LiveKitEgressInfo(
                        egressId = "EG_ORPHAN",
                        roomName = p.roomName,
                        status = "EGRESS_ACTIVE",
                        fileResults =
                            listOf(
                                network.lapis.cloud.server.conference
                                    .LiveKitEgressFileInfo(filename = "/out/pulpit/x/y.mp4"),
                            ),
                    )
                val stream =
                    LiveKitEgressInfo(
                        egressId = "EG_STREAM",
                        roomName = p.roomName,
                        status = "EGRESS_ACTIVE",
                        streamResults =
                            listOf(
                                network.lapis.cloud.server.conference
                                    .LiveKitStreamInfo(url = "rtmp://example"),
                            ),
                    )
                p.egress.foreign += listOf(orphan, stream)
                p.coordinator.tick()
                p.egress.stopped shouldBe listOf("EG_ORPHAN")
                encounterRecordingPacket(fromParticipant = false, payload = p.recordingPackets().single().third) shouldBe
                    EncounterRecordingSignal(active = false)
                p.autoReasons() shouldBe listOf("RESTART_ORPHAN")
            }
        }

        fun orphanEgress(room: String) =
            LiveKitEgressInfo(
                egressId = "EG_ORPHAN",
                roomName = room,
                status = "EGRESS_ACTIVE",
                fileResults =
                    listOf(
                        network.lapis.cloud.server.conference
                            .LiveKitEgressFileInfo(filename = "/out/pulpit/x/y.mp4"),
                    ),
            )

        test("a start-up sweep that could not list the egresses is retried and tells the room nothing meanwhile") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                p.egress.foreign += orphanEgress(p.roomName)
                p.egress.failList = true
                p.coordinator.tick()
                p.egress.stopped.shouldBeEmpty()
                p.recordingPackets().shouldBeEmpty()
                p.egress.failList = false
                p.coordinator.tick()
                p.egress.stopped shouldBe listOf("EG_ORPHAN")
                encounterRecordingPacket(fromParticipant = false, payload = p.recordingPackets().single().third) shouldBe
                    EncounterRecordingSignal(active = false)
                p.autoReasons() shouldBe listOf("RESTART_ORPHAN")
            }
        }

        test("a start-up sweep whose stop failed keeps the room's banner alone and stops the orphan on the next run") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                p.egress.foreign += orphanEgress(p.roomName)
                p.egress.failStop = true
                p.coordinator.tick()
                p.recordingPackets().shouldBeEmpty()
                p.egress.failStop = false
                p.coordinator.tick()
                p.egress.stopped shouldBe listOf("EG_ORPHAN")
                p.recordingPackets() shouldHaveSize 1
            }
        }

        test("somebody who arrives later is told the state point to point, a new pulpit person who has agreed is recorded") {
            val p = newWorld()
            val second = fx.createMember().also { fx.setRole(spaceId = p.w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            encounterApp {
                running(p, p.w.a)
                p.coordinator.tick()
                val before = p.recordingPacketsTo().size
                // a newcomer connects; the state reaches exactly this identity
                p.w.rig.liveKit
                    .connect(room = p.roomName, identity = p.w.b.toString(), canPublish = false, canPublishData = true)
                p.coordinator.tick()
                val told = p.recordingPacketsTo().drop(before)
                told.single().destinations shouldBe listOf(p.w.b.toString())
                encounterRecordingPacket(fromParticipant = false, payload = told.single().payload) shouldBe
                    EncounterRecordingSignal(active = true)
                // a second pulpit person enters (no confirmation required here): the next tick records them as well
                with(this) {
                    p.w.rig
                        .asMember(client = client, member = second) { it.enterSpace(spaceId = p.w.spaceId, consent = null) }
                        .getOrThrow()
                }
                p.w.rig.liveKit
                    .connect(room = p.roomName, identity = second.toString(), canPublish = true, canPublishData = true)
                p.coordinator.tick()
                p.egress.started
                    .map { it.identity }
                    .toSet() shouldBe setOf(p.w.pulpit.toString(), second.toString())
            }
        }

        test(
            "a finished file is archived as a BOARD_ONLY document in a BOARD_ONLY folder, marked, without a name in the title, and the raw file is gone",
        ) {
            val p = newWorld()
            encounterApp {
                running(p)
                val started = p.egress.started.single()
                val fileId = started.path.substringAfterLast('/').removeSuffix(".mp4")
                val file =
                    p.hostDir
                        .resolve("pulpit")
                        .resolve(p.w.space.toString())
                        .resolve("$fileId.mp4")
                file.parentFile.mkdirs()
                file.writeBytes(ByteArray(2048) { it.toByte() })
                p.asMember(client = client, member = p.w.steward) { it.stopPulpitRecording(p.w.spaceId) }.getOrThrow()
                p.coordinator.tick()
                val marker =
                    transaction {
                        PulpitRecordingDocumentTable.selectAll().where { PulpitRecordingDocumentTable.spaceId eq p.w.space }.single()
                    }
                val documentId = marker[PulpitRecordingDocumentTable.documentId]
                transaction {
                    val document = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()
                    document[DocumentTable.accessLevel] shouldBe DocumentAccessLevel.BOARD_ONLY
                    document[DocumentTable.createdBy] shouldBe p.w.steward
                    document[DocumentTable.title] shouldContain "Kanzel-Aufzeichnung"
                    document[DocumentTable.title] shouldNotContain p.w.pulpit.toString()
                    DocumentFolderTable
                        .selectAll()
                        .where { DocumentFolderTable.id eq document[DocumentTable.folderId] }
                        .single()[DocumentFolderTable.accessLevel] shouldBe DocumentAccessLevel.BOARD_ONLY
                }
                file.exists() shouldBe false
                transaction {
                    network.lapis.cloud.server.db.generated.DocumentVersionTable
                        .selectAll()
                        .where { network.lapis.cloud.server.db.generated.DocumentVersionTable.documentId eq documentId }
                        .single()[network.lapis.cloud.server.db.generated.DocumentVersionTable.fileName] shouldContain fileId
                }
            }
        }

        test("a discarded start never becomes a document, and an unclaimed file on the volume is never deleted") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                val orphanFile =
                    p.hostDir
                        .resolve("pulpit")
                        .resolve(Uuid.random().toString())
                        .resolve("${Uuid.random()}.mp4")
                orphanFile.parentFile.mkdirs()
                orphanFile.writeBytes(ByteArray(10))
                orphanFile.setLastModified(System.currentTimeMillis() - 24 * 3_600_000L)
                p.coordinator.tick()
                orphanFile.exists() shouldBe true
                transaction {
                    PulpitRecordingDocumentTable.selectAll().where { PulpitRecordingDocumentTable.spaceId eq p.w.space }.count()
                } shouldBe
                    0L
            }
        }

        test("a volume that runs full while recording ends the recording (DISK_LOW)") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                p.asMember(client = client, member = p.w.steward) { it.startPulpitRecording(p.w.spaceId) }.getOrThrow()
                p.coordinator.isActive(p.roomId) shouldBe true
                p.freeBytes = 0L
                p.coordinator.tick()
                p.coordinator.isActive(p.roomId) shouldBe false
                p.egress.stopped shouldHaveSize 1
            }
        }
    })

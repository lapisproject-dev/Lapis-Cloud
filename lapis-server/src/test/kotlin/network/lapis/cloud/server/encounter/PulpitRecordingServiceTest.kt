package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.ApplicationTestBuilder
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRecordingSignal
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.encounterRecordingPacket
import network.lapis.cloud.shared.rpc.AgreementTextOutdatedException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.PulpitRecordingAwaitingConfirmationException
import network.lapis.cloud.shared.rpc.PulpitRecordingFloorActiveException
import network.lapis.cloud.shared.rpc.PulpitRecordingUnavailableException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.143 -- the pulpit recording through the real service and coordinator, with fake LiveKit and egress: the rights matrix (one bare Forbidden
 * for every refusal), who is recorded (the PULPIT office, never a steward, never the congregation), the confirmation of the pulpit, the per-room limit,
 * the floor rule, and what the audit entries contain.
 */
class PulpitRecordingServiceTest :
    FunSpec({
        val fx = EncounterFixtures()
        val worlds = mutableListOf<PulpitWorld>()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            worlds.forEach { it.close() }
            fx.cleanUp()
            PulpitSettingsFixture.reset()
        }

        fun newWorld(): PulpitWorld =
            PulpitWorld(w = fx.tableWorld(tablesEnabled = false, profile = EncounterProfile.CHURCH_SERVICE)).also { worlds += it }

        fun pulpitAudit(space: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.PULPIT_RECORDING) and (AuditLogEntryTable.entityId eq space)
                    }.toList()
            }

        suspend fun ApplicationTestBuilder.start(
            p: PulpitWorld,
            by: Uuid,
        ) = p.asMember(client = client, member = by) { it.startPulpitRecording(p.w.spaceId) }

        test("start by the steward records exactly the PULPIT person, with a path that names nobody, tells the room and audits once") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit, p.w.a, p.w.b)
                val dto = start(p, p.w.steward).getOrThrow()
                dto.active shouldBe true
                p.egress.started shouldHaveSize 1
                val started = p.egress.started.single()
                started.identity shouldBe p.w.pulpit.toString()
                started.room shouldBe p.roomName
                started.path shouldContain "/out/pulpit/${p.w.space}/"
                started.path.endsWith(".mp4") shouldBe true
                started.path shouldNotContain p.w.pulpit.toString()
                encounterRecordingPacket(fromParticipant = false, payload = p.recordingPackets().single().third) shouldBe
                    EncounterRecordingSignal(active = true)
                val audit = pulpitAudit(p.w.space)
                audit shouldHaveSize 1
                audit.single()[AuditLogEntryTable.actorMemberId] shouldBe p.w.steward
                audit.single()[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"event\":\"STARTED\""
            }
        }

        test("a steward alone is never a source and nobody of the congregation is recorded") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.a, p.w.b)
                start(p, p.w.steward).failure<ConflictException>()
                p.egress.started.shouldBeEmpty()
            }
        }

        test("every refusal of a control is the same bare ForbiddenException and starts nothing") {
            val p = newWorld()
            val otherWorld = newWorld()
            val absentPulpit = fx.createMember().also { fx.setRole(spaceId = p.w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit, p.w.a, p.w.board)
                val refused =
                    listOf(p.w.a, p.w.board, otherWorld.w.pulpit, absentPulpit).map { who ->
                        start(p, who).failure<ForbiddenException>().message
                    }
                refused.toSet().size shouldBe 1
                p.egress.started.shouldBeEmpty()
                // an id that is no space, and a malformed one
                p
                    .asMember(
                        client = client,
                        member = p.w.steward,
                    ) { it.startPulpitRecording(Uuid.random().toString()) }
                    .failure<ForbiddenException>()
                p.asMember(client = client, member = p.w.steward) { it.startPulpitRecording("no-uuid") }.failure<ForbiddenException>()
                p.asMember(client = client, member = p.w.a) { it.pulpitRecordingState(p.w.spaceId) }.failure<ForbiddenException>()
                p.asMember(client = client, member = p.w.a) { it.pulpitRecordingTexts(p.w.spaceId) }.failure<ForbiddenException>()
            }
        }

        test("instance switch off, a stale notice version, the room option off and a closed room each refuse a start the same way") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                PulpitSettingsFixture.set(enabled = false, noticeVersion = null)
                val off = start(p, p.w.steward).failure<ForbiddenException>().message
                PulpitSettingsFixture.set(enabled = true, noticeVersion = "0")
                val stale = start(p, p.w.steward).failure<ForbiddenException>().message
                PulpitSettingsFixture.set(enabled = true)
                PulpitSettingsFixture.allowInSpace(spaceId = p.w.space, allowed = false)
                val noOption = start(p, p.w.steward).failure<ForbiddenException>().message
                PulpitSettingsFixture.allowInSpace(spaceId = p.w.space, allowed = true)
                setOf(off, stale, noOption).size shouldBe 1
                p.egress.started.shouldBeEmpty()
                // a stale notice blocks only NEW starts: a stop is still possible, and an unfinished switch-off must not strand a recording
                PulpitSettingsFixture.set(enabled = true, noticeVersion = "0")
                p.asMember(client = client, member = p.w.steward) { it.stopPulpitRecording(p.w.spaceId) }.getOrThrow().active shouldBe false
                PulpitSettingsFixture.set(enabled = true)
                p.w.rig
                    .asMember(client = client, member = p.w.steward) { it.closeSpace(p.w.spaceId) }
                    .getOrThrow()
                start(p, p.w.steward).failure<ForbiddenException>()
            }
        }

        test("with confirmation required the pulpit is asked, nothing is recorded before it agrees, and a stale hash is refused") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit, p.w.a)
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                p.egress.started.shouldBeEmpty()
                val ask = p.recordingPacketsTo().single()
                ask.destinations shouldBe listOf(p.w.pulpit.toString())
                encounterRecordingPacket(fromParticipant = false, payload = ask.payload) shouldBe
                    EncounterRecordingSignal(active = false, ask = true)
                val texts = p.asMember(client = client, member = p.w.pulpit) { it.pulpitRecordingTexts(p.w.spaceId) }.getOrThrow()
                texts.pulpitVersionLabel shouldBe
                    transaction { AgreementTextResolver.current(kind = AgreementTextKind.REC_PULPIT).versionLabel }
                p
                    .asMember(client = client, member = p.w.pulpit) {
                        it.confirmPulpitRecording(spaceId = p.w.spaceId, versionLabel = texts.pulpitVersionLabel, sha256 = "0".repeat(64))
                    }.failure<AgreementTextOutdatedException>()
                p
                    .asMember(client = client, member = p.w.pulpit) {
                        it.confirmPulpitRecording(spaceId = p.w.spaceId, versionLabel = "other-label", sha256 = texts.pulpitSha256)
                    }.failure<AgreementTextOutdatedException>()
                // a steward cannot agree for the pulpit
                p
                    .asMember(client = client, member = p.w.steward) {
                        it.confirmPulpitRecording(
                            spaceId = p.w.spaceId,
                            versionLabel = texts.pulpitVersionLabel,
                            sha256 = texts.pulpitSha256,
                        )
                    }.failure<ForbiddenException>()
                p.egress.started.shouldBeEmpty()
                val confirmed =
                    p
                        .asMember(client = client, member = p.w.pulpit) {
                            it.confirmPulpitRecording(
                                spaceId = p.w.spaceId,
                                versionLabel = texts.pulpitVersionLabel,
                                sha256 = texts.pulpitSha256,
                            )
                        }.getOrThrow()
                confirmed.active shouldBe true
                confirmed.confirmedByMe shouldBe true
                p.egress.started
                    .single()
                    .identity shouldBe p.w.pulpit.toString()
                val audit = pulpitAudit(p.w.space).single()
                audit[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"pulpitConfirmed\":true"
                // The start is audited with the person who REQUESTED it (the steward), not the confirming pulpit and not without an actor.
                audit[AuditLogEntryTable.actorMemberId] shouldBe p.w.steward
                audit[AuditLogEntryTable.actorRole] shouldBe AccountRole.MEMBER
            }
        }

        test("a confirmation that arrives when the disk has filled meanwhile starts nothing, and starts once there is room again") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit)
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                val texts = p.asMember(client = client, member = p.w.pulpit) { it.pulpitRecordingTexts(p.w.spaceId) }.getOrThrow()

                suspend fun confirm() =
                    p
                        .asMember(client = client, member = p.w.pulpit) {
                            it.confirmPulpitRecording(
                                spaceId = p.w.spaceId,
                                versionLabel = texts.pulpitVersionLabel,
                                sha256 = texts.pulpitSha256,
                            )
                        }.getOrThrow()
                p.freeBytes = 0L
                confirm().active shouldBe false
                p.egress.started.shouldBeEmpty()
                pulpitAudit(p.w.space).shouldBeEmpty()
                p.freeBytes = Long.MAX_VALUE
                confirm().active shouldBe true
                p.egress.started shouldHaveSize 1
                pulpitAudit(p.w.space) shouldHaveSize 1
            }
        }

        test("somebody who enters a room that is being recorded is told so by the entry answer itself") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)

                suspend fun enterAs(member: Uuid) =
                    p.w.rig
                        .asMember(client = client, member = member) { it.enterSpace(spaceId = p.w.spaceId, consent = null) }
                        .getOrThrow()
                enterAs(p.w.a).pulpitRecordingActive shouldBe false
                p.w.rig.pulpitRecording.recordingActive = true
                enterAs(p.w.b).pulpitRecordingActive shouldBe true
                p.w.rig.pulpitRecording.recordingActive = false
            }
        }

        test("declining discards the open request and records nothing") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit)
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                p.coordinator.view(roomId = p.roomId, callerId = p.w.pulpit).pendingConfirmation shouldBe true
                p.asMember(client = client, member = p.w.pulpit) { it.declinePulpitRecording(p.w.spaceId) }.getOrThrow()
                p.coordinator.view(roomId = p.roomId, callerId = p.w.pulpit).pendingConfirmation shouldBe false
                p.egress.started.shouldBeEmpty()
            }
        }

        test("a stop ends the recording, switches the banner off and audits by hand") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                start(p, p.w.steward).getOrThrow()
                val stopped = p.asMember(client = client, member = p.w.steward) { it.stopPulpitRecording(p.w.spaceId) }.getOrThrow()
                stopped.active shouldBe false
                p.egress.stopped shouldHaveSize 1
                encounterRecordingPacket(fromParticipant = false, payload = p.recordingPackets().last().third) shouldBe
                    EncounterRecordingSignal(active = false)
                val after = pulpitAudit(p.w.space).map { it[AuditLogEntryTable.afterSnapshot]!! }
                after.any { it.contains("\"event\":\"STOPPED\"") } shouldBe true
                // idempotent
                p.asMember(client = client, member = p.w.steward) { it.stopPulpitRecording(p.w.spaceId) }.getOrThrow().active shouldBe false
            }
        }

        test("at most two pulpit persons are recorded at the same time, the stable order decides, the rest is reported") {
            val p = newWorld()
            val second = fx.createMember().also { fx.setRole(spaceId = p.w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            val third = fx.createMember().also { fx.setRole(spaceId = p.w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit, second, third)
                val dto = start(p, p.w.steward).getOrThrow()
                p.egress.started shouldHaveSize 2
                dto.maxReached shouldBe true
                val expected =
                    listOf(p.w.pulpit, second, third)
                        .map { it.toString() }
                        .sorted()
                        .take(2)
                        .toSet()
                p.egress.started
                    .map { it.identity }
                    .toSet() shouldBe expected
            }
        }

        test("somebody of the congregation holding the floor blocks a start and a floor granted later is announced to the engine") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit, p.w.a)
                p.w.rig
                    .asMember(client = client, member = p.w.steward) { it.grantFloor(spaceId = p.w.spaceId, memberId = p.w.a.toString()) }
                    .getOrThrow()
                start(p, p.w.steward).failure<PulpitRecordingFloorActiveException>()
                p.egress.started.shouldBeEmpty()
                p.w.rig.pulpitRecording.stoppedSessions
                    .map { it.second } shouldBe listOf(PulpitStopReason.FLOOR_GRANTED)
            }
        }

        test("a failing egress is reported as unavailable, leaves nothing running and writes no audit entry") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit)
                p.egress.failStart = true
                start(p, p.w.steward).failure<PulpitRecordingUnavailableException>()
                p.coordinator.isActive(p.roomId) shouldBe false
                pulpitAudit(p.w.space).shouldBeEmpty()
                p.egress.failStart = false
                start(p, p.w.steward).getOrThrow().active shouldBe true
            }
        }

        test("no audit snapshot names a person, a count or a path; the acting person is only the actor of the entry") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = false, p.w.steward, p.w.pulpit, p.w.a, p.w.b)
                start(p, p.w.steward).getOrThrow()
                p.asMember(client = client, member = p.w.steward) { it.stopPulpitRecording(p.w.spaceId) }.getOrThrow()
                val entries = pulpitAudit(p.w.space)
                entries.size shouldBe 2
                entries.forEach { row ->
                    val text = (row[AuditLogEntryTable.beforeSnapshot].orEmpty() + row[AuditLogEntryTable.afterSnapshot].orEmpty())
                    listOf(p.w.pulpit, p.w.a, p.w.b, p.w.steward).forEach { text shouldNotContain it.toString() }
                    listOf("/out/", ".mp4", "count", "participants", "present").forEach { text shouldNotContain it }
                    row[AuditLogEntryTable.actorMemberId] shouldBe p.w.steward
                    row[AuditLogEntryTable.actorRole] shouldBe AccountRole.MEMBER
                }
            }
        }

        test("consent is per recording: after a stop and a new start the pulpit is asked again") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit)
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                val texts = p.asMember(client = client, member = p.w.pulpit) { it.pulpitRecordingTexts(p.w.spaceId) }.getOrThrow()
                p
                    .asMember(client = client, member = p.w.pulpit) {
                        it.confirmPulpitRecording(
                            spaceId = p.w.spaceId,
                            versionLabel = texts.pulpitVersionLabel,
                            sha256 = texts.pulpitSha256,
                        )
                    }.getOrThrow()
                p.egress.started shouldHaveSize 1
                p.asMember(client = client, member = p.w.steward) { it.stopPulpitRecording(p.w.spaceId) }.getOrThrow().active shouldBe false
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                p.egress.started shouldHaveSize 1
                p.coordinator.view(roomId = p.roomId, callerId = p.w.pulpit).confirmedByMe shouldBe false
            }
        }

        test("declining after an earlier agreement stops the person's file and prevents a recording") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit)
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                val texts = p.asMember(client = client, member = p.w.pulpit) { it.pulpitRecordingTexts(p.w.spaceId) }.getOrThrow()
                p
                    .asMember(client = client, member = p.w.pulpit) {
                        it.confirmPulpitRecording(
                            spaceId = p.w.spaceId,
                            versionLabel = texts.pulpitVersionLabel,
                            sha256 = texts.pulpitSha256,
                        )
                    }.getOrThrow()
                p.egress.started shouldHaveSize 1
                p.asMember(client = client, member = p.w.pulpit) { it.declinePulpitRecording(p.w.spaceId) }.getOrThrow()
                p.egress.stopped shouldHaveSize 1
                p.coordinator.isActive(p.roomId) shouldBe false
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                p.egress.started shouldHaveSize 1
            }
        }
        test("an agreement given after the start request expired is not kept: the next start asks again") {
            val time = kotlin.time.TestTimeSource()
            val p =
                PulpitWorld(
                    w = fx.tableWorld(tablesEnabled = false, profile = EncounterProfile.CHURCH_SERVICE),
                    state = PulpitRecordingState(time),
                )
            worlds += p
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit)
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                time += (PulpitRecordingState.PENDING_VALIDITY_MINUTES + 1).minutes
                val texts = p.asMember(client = client, member = p.w.pulpit) { it.pulpitRecordingTexts(p.w.spaceId) }.getOrThrow()
                p
                    .asMember(client = client, member = p.w.pulpit) {
                        it.confirmPulpitRecording(
                            spaceId = p.w.spaceId,
                            versionLabel = texts.pulpitVersionLabel,
                            sha256 = texts.pulpitSha256,
                        )
                    }.getOrThrow()
                p.egress.started.shouldBeEmpty()
                p.coordinator.view(roomId = p.roomId, callerId = p.w.pulpit).confirmedByMe shouldBe false
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                p.egress.started.shouldBeEmpty()
            }
        }

        test("an agreement stored for another text is dropped: a start and a tick ask again") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit)
                // an agreement that was given for a text that is no longer the current one
                p.state.confirm(roomId = p.roomId, memberId = p.w.pulpit, pulpitTextSha256 = "f".repeat(64))
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                p.egress.started.shouldBeEmpty()
                p.state.confirm(roomId = p.roomId, memberId = p.w.pulpit, pulpitTextSha256 = "e".repeat(64))
                p.state.hasConfirmations(p.roomId) shouldBe true
                p.coordinator.tick()
                p.state.hasConfirmations(p.roomId) shouldBe false
                p.egress.started.shouldBeEmpty()
            }
        }

        test("a confirmation that carries a hash other than the current text is refused and stores nothing") {
            val p = newWorld()
            encounterApp {
                p.open(builder = this, confirmation = true, p.w.steward, p.w.pulpit)
                start(p, p.w.steward).failure<PulpitRecordingAwaitingConfirmationException>()
                val target =
                    PulpitTarget(
                        spaceId = p.w.space,
                        roomId = p.roomId,
                        livekitRoomName = p.roomName,
                        profile = EncounterProfile.CHURCH_SERVICE,
                        requireConfirmation = true,
                    )
                // the coordinator compares the checked hash with the current text inside its own lock
                runCatching {
                    p.coordinator.confirm(target = target, memberId = p.w.pulpit, checkedPulpitTextSha256 = "0".repeat(64), reverify = {})
                }.exceptionOrNull().shouldBeInstanceOf<AgreementTextOutdatedException>()
                p.state.hasConfirmations(p.roomId) shouldBe false
                p.egress.started.shouldBeEmpty()
            }
        }
    })

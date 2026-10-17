package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.agreementtexts.EncounterChurchConsentDefault
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.143 -- the per-room option "the pulpit may be recorded": who may set it, that it needs the instance switch, that it can only be switched ON
 * while the room is closed (the non-members' consent depends on it) but always OFF (which stops a running recording), that `null` leaves it alone,
 * that it is audited as part of the room configuration, and that it changes the consent text a non-member has to acknowledge.
 */
class EncounterSpacePulpitOptionTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            fx.cleanUp()
            PulpitSettingsFixture.reset()
        }
        afterTest { PulpitSettingsFixture.reset() }

        fun board() = fx.createMember(role = AccountRole.BOARD)

        fun input(allowed: Boolean?) =
            EncounterSpaceInput(title = "Gottesdienst", profile = EncounterProfile.CHURCH_SERVICE, pulpitRecordingAllowed = allowed)

        test("BOARD and ADMIN set the option, a MEMBER and an office holder without a global role cannot") {
            PulpitSettingsFixture.set(enabled = true)
            val rig = EncounterRig()
            val admin = fx.createMember(role = AccountRole.ADMIN)
            val member = fx.createMember()
            encounterApp {
                val created = rig.asMember(client = client, member = board()) { it.createSpace(input(true)) }.getOrThrow()
                created.pulpitRecordingAllowed shouldBe true
                fx.spaceIds += Uuid.parse(created.id)
                rig
                    .asMember(client = client, member = admin) {
                        it.updateSpace(spaceId = created.id, input = input(false))
                    }.getOrThrow()
                    .pulpitRecordingAllowed shouldBe
                    false
                rig.asMember(client = client, member = member) { it.createSpace(input(true)) }.failure<ForbiddenException>()
                val steward =
                    fx.createMember().also {
                        fx.setRole(
                            spaceId = Uuid.parse(created.id),
                            memberId = it,
                            role = EncounterSpaceRole.STEWARD,
                        )
                    }
                rig
                    .asMember(
                        client = client,
                        member = steward,
                    ) { it.updateSpace(spaceId = created.id, input = input(true)) }
                    .failure<ForbiddenException>()
            }
        }

        test("switching it on needs the instance switch; a created room defaults to off and a null input changes nothing") {
            val rig = EncounterRig()
            val b = board()
            encounterApp {
                rig.asMember(client = client, member = b) { it.createSpace(input(true)) }.failure<ConflictException>()
                val room = rig.asMember(client = client, member = b) { it.createSpace(input(null)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(room.id)
                room.pulpitRecordingAllowed shouldBe false
                PulpitSettingsFixture.set(enabled = true)
                rig
                    .asMember(client = client, member = b) {
                        it.updateSpace(spaceId = room.id, input = input(true))
                    }.getOrThrow()
                    .pulpitRecordingAllowed shouldBe
                    true
                // an old client sends no value: the option stays
                rig
                    .asMember(client = client, member = b) {
                        it.updateSpace(spaceId = room.id, input = input(null))
                    }.getOrThrow()
                    .pulpitRecordingAllowed shouldBe
                    true
                // the instance switch going off later does not make an unchanged option an error
                PulpitSettingsFixture.set(enabled = false, noticeVersion = null)
                rig
                    .asMember(client = client, member = b) {
                        it.updateSpace(spaceId = room.id, input = input(true))
                    }.getOrThrow()
                    .pulpitRecordingAllowed shouldBe
                    true
            }
        }

        test("switching it on while the room is open is refused; switching it off is allowed and stops a running recording") {
            PulpitSettingsFixture.set(enabled = true)
            val rig = EncounterRig()
            val world = fx.tableWorld(rig = rig, tablesEnabled = false, profile = EncounterProfile.CHURCH_SERVICE)
            encounterApp {
                openAndEnter(w = world, world.a)
                rig
                    .asMember(
                        client = client,
                        member = world.board,
                    ) { it.updateSpace(spaceId = world.spaceId, input = input(true).copy(profile = null)) }
                    .failure<ConflictException>()
                PulpitSettingsFixture.allowInSpace(spaceId = world.space, allowed = true)
                rig
                    .asMember(client = client, member = world.board) {
                        it.updateSpace(spaceId = world.spaceId, input = input(false).copy(profile = null))
                    }.getOrThrow()
                rig.pulpitRecording.stoppedSpaces shouldBe listOf(world.space to PulpitStopReason.ROOM_OPTION_OFF)
            }
        }

        test("the option is part of the audited room configuration") {
            PulpitSettingsFixture.set(enabled = true)
            val rig = EncounterRig()
            val b = board()
            encounterApp {
                val room = rig.asMember(client = client, member = b) { it.createSpace(input(true)) }.getOrThrow()
                fx.spaceIds += Uuid.parse(room.id)
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ENCOUNTER_SPACE) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(room.id))
                            }.first()
                    }
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"pulpitRecordingAllowed\":true"
            }
        }

        test("a room with the option on shows its room notice and asks non-members for the recording variant of the consent") {
            PulpitSettingsFixture.set(enabled = true)
            val rig = EncounterRig()
            val b = board()
            val guest = fx.createMember(status = network.lapis.cloud.shared.domain.MemberStatus.FRIEND)
            encounterApp {
                val withRecording =
                    rig
                        .asMember(client = client, member = b) {
                            it.createSpace(
                                input(true).copy(guestPolicy = network.lapis.cloud.shared.domain.EncounterGuestPolicy.MEMBERS_AND_GUESTS),
                            )
                        }.getOrThrow()
                val without =
                    rig
                        .asMember(client = client, member = b) {
                            it.createSpace(
                                input(false).copy(guestPolicy = network.lapis.cloud.shared.domain.EncounterGuestPolicy.MEMBERS_AND_GUESTS),
                            )
                        }.getOrThrow()
                fx.spaceIds += listOf(Uuid.parse(withRecording.id), Uuid.parse(without.id))
                val infoWith: EncounterEntryInfoDto =
                    rig
                        .asMember(
                            client = client,
                            member = guest,
                        ) { it.getEntryInfo(withRecording.id) }
                        .getOrThrow()
                val infoWithout: EncounterEntryInfoDto =
                    rig
                        .asMember(
                            client = client,
                            member = guest,
                        ) { it.getEntryInfo(without.id) }
                        .getOrThrow()
                infoWith.pulpitRecordingRoomNotice shouldBe "Die Kanzel wird aufgezeichnet, die Gemeinde nicht."
                infoWithout.pulpitRecordingRoomNotice shouldBe null
                // V1.9.148: the variant is the placeholder with the system suffix; the code appends the recording sentence as the last key point.
                infoWith.disclaimer!!.version shouldBe EncounterChurchConsentDefault.VERSION + "+rec"
                infoWithout.disclaimer!!.version shouldBe EncounterChurchConsentDefault.VERSION
                infoWith.disclaimer!!.sha256 shouldBe
                    transaction {
                        encounterConsentFor(
                            profile = network.lapis.cloud.shared.domain.EncounterProfile.CHURCH_SERVICE,
                            pulpitRecordingAllowed = true,
                        )
                    }.sha256
                infoWith.disclaimer!!.keyPoints.last() shouldContain "kann die Kanzel aufgezeichnet werden"
                infoWithout.disclaimer!!.keyPoints.none { it.contains("kann die Kanzel aufgezeichnet werden") } shouldBe true
            }
        }
    })

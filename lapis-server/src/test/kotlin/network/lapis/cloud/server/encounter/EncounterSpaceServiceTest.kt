package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val CONSENT =
    EncounterConsentInput(
        consentVersion = network.lapis.cloud.server.encounter.EncounterConsentDisclaimer.VERSION,
        consentSha256 = network.lapis.cloud.server.encounter.EncounterConsentDisclaimer.SHA256,
    )

/**
 * Welle V1.9.61 "Begegnungsraum" -- [network.lapis.cloud.server.rpc.EncounterSpaceService] end to end on H2 with a fake LiveKit:
 * configuration, session lifecycle, presence, moderation and the participant ceilings. The access matrix is pinned separately in
 * [EncounterRoleMatrixTest], the privacy rules in [EncounterPrivacyWatchTest], races in [EncounterSpaceConcurrencyTest].
 */
class EncounterSpaceServiceTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun board() = fx.createMember(role = AccountRole.BOARD)

        // ── configuration ─────────────────────────────────────────────────

        test("createSpace: BOARD creates a space, the audit log gets one CREATE entry with the board member as actor") {
            val rig = EncounterRig()
            val boardId = board()
            encounterApp {
                val dto =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(
                                EncounterSpaceInput(
                                    title = "  Sonntagsgottesdienst  ",
                                    description = "Jeden Sonntag",
                                    guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS,
                                    closedNotice = "Beginnt um 10:15 Uhr",
                                    maxParticipants = 10,
                                ),
                            )
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                dto.title shouldBe "Sonntagsgottesdienst"
                dto.open shouldBe false
                dto.guestPolicy shouldBe EncounterGuestPolicy.MEMBERS_AND_GUESTS
                dto.closedNotice shouldBe "Beginnt um 10:15 Uhr"
                dto.maxParticipants shouldBe 10
                dto.canModerate shouldBe true
                dto.myRole shouldBe null
                val audit = auditEntriesOf(Uuid.parse(dto.id))
                audit.map { it.first } shouldContainExactly listOf("CREATE")
                audit.single().third shouldBe boardId
            }
        }

        test("createSpace: input validation, and the instance maximum clamps a larger maxParticipants") {
            val rig = EncounterRig()
            val boardId = board()
            encounterApp {
                rig
                    .asMember(
                        client = client,
                        member = boardId,
                    ) { it.createSpace(EncounterSpaceInput(title = "  ")) }
                    .failure<BadRequestException>()
                rig
                    .asMember(client = client, member = boardId) {
                        it.createSpace(EncounterSpaceInput(title = "x".repeat(201)))
                    }.failure<BadRequestException>()
                rig
                    .asMember(
                        client = client,
                        member = boardId,
                    ) { it.createSpace(EncounterSpaceInput(title = "ok", description = "x".repeat(1001))) }
                    .failure<BadRequestException>()
                rig
                    .asMember(
                        client = client,
                        member = boardId,
                    ) { it.createSpace(EncounterSpaceInput(title = "ok", closedNotice = "x".repeat(201))) }
                    .failure<BadRequestException>()
                rig
                    .asMember(client = client, member = boardId) { it.createSpace(EncounterSpaceInput(title = "ok", maxParticipants = 1)) }
                    .failure<BadRequestException>()
                val big =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(EncounterSpaceInput(title = "gross", maxParticipants = 500))
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(big.id)
                big.maxParticipants shouldBe 25
            }
        }

        test("createSpace/updateSpace/archiveSpace/setSpaceRoles/listSpaceRoles are BOARD/ADMIN only") {
            val rig = EncounterRig()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            encounterApp {
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.createSpace(EncounterSpaceInput(title = "x")) }
                    .failure<ForbiddenException>()
                rig
                    .asMember(client = client, member = member) {
                        it.updateSpace(spaceId = space.toString(), input = EncounterSpaceInput(title = "y"))
                    }.failure<ForbiddenException>()
                rig.asMember(client = client, member = member) { it.archiveSpace(space.toString()) }.failure<ForbiddenException>()
                rig
                    .asMember(client = client, member = member) {
                        it.setSpaceRoles(spaceId = space.toString(), assignments = emptyList())
                    }.failure<ForbiddenException>()
                rig.asMember(client = client, member = member) { it.listSpaceRoles(space.toString()) }.failure<ForbiddenException>()
            }
        }

        test("updateSpace changes the configuration and audits before/after; the guest policy is frozen while a session is open") {
            val rig = EncounterRig()
            val boardId = board()
            val pulpit = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            encounterApp {
                val updated =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.updateSpace(
                                spaceId = space.toString(),
                                input = EncounterSpaceInput(title = "Neuer Titel", guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS),
                            )
                        }.getOrThrow()
                updated.title shouldBe "Neuer Titel"
                updated.guestPolicy shouldBe EncounterGuestPolicy.MEMBERS_AND_GUESTS
                auditEntriesOf(space).map { it.first } shouldContainExactly listOf("UPDATE")

                rig.asMember(client = client, member = pulpit) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = space.toString(),
                            input = EncounterSpaceInput(title = "Noch ein Titel", guestPolicy = EncounterGuestPolicy.MEMBERS_ONLY),
                        )
                    }.failure<ConflictException>()
                // the title alone may change while open
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = space.toString(),
                            input = EncounterSpaceInput(title = "Noch ein Titel", guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS),
                        )
                    }.getOrThrow()
                    .title shouldBe "Noch ein Titel"
            }
        }

        test("archiveSpace: refused while a session is open, idempotent once closed, then invisible to ordinary members and not openable") {
            val rig = EncounterRig()
            val boardId = board()
            val member = fx.createMember()
            val pulpit = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            encounterApp {
                rig.asMember(client = client, member = pulpit) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = boardId) { it.archiveSpace(space.toString()) }.failure<ConflictException>()
                rig.asMember(client = client, member = pulpit) { it.closeSpace(space.toString()) }.getOrThrow()

                rig.asMember(client = client, member = boardId) { it.archiveSpace(space.toString()) }.getOrThrow().archived shouldBe true
                rig.asMember(client = client, member = boardId) { it.archiveSpace(space.toString()) }.getOrThrow().archived shouldBe true
                // exactly ONE archive audit entry although archive was called twice
                auditEntriesOf(space).map { it.first } shouldContainExactly listOf("UPDATE", "UPDATE", "UPDATE")

                rig.asMember(client = client, member = member) { it.getSpace(space.toString()) }.failure<NotFoundException>()
                rig.asMember(client = client, member = member) { it.listSpaces() }.getOrThrow().none { it.id == space.toString() } shouldBe
                    true
                rig.asMember(client = client, member = boardId) { it.listSpaces() }.getOrThrow().any {
                    it.id == space.toString() &&
                        it.archived
                } shouldBe
                    true
                rig.asMember(client = client, member = pulpit) { it.openSpace(space.toString()) }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = board()) {
                        it.updateSpace(spaceId = space.toString(), input = EncounterSpaceInput(title = "x"))
                    }.failure<ConflictException>()
            }
        }

        test("setSpaceRoles: replace-all, at most 20, no duplicates, ACTIVE members only; the audit entry names the office holders") {
            val rig = EncounterRig()
            val boardId = board()
            val a = fx.createMember()
            val b = fx.createMember()
            val guest = fx.createMember(status = MemberStatus.GUEST)
            val space = fx.createSpace(createdBy = boardId)
            encounterApp {
                val roles =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.setSpaceRoles(
                                spaceId = space.toString(),
                                assignments =
                                    listOf(
                                        EncounterSpaceRoleAssignmentInput(memberId = a.toString(), role = EncounterSpaceRole.PULPIT),
                                        EncounterSpaceRoleAssignmentInput(memberId = b.toString(), role = EncounterSpaceRole.STEWARD),
                                    ),
                            )
                        }.getOrThrow()
                roles.map { it.role } shouldContainExactly listOf(EncounterSpaceRole.PULPIT, EncounterSpaceRole.STEWARD)

                // replace-all: only b remains
                rig
                    .asMember(client = client, member = boardId) {
                        it.setSpaceRoles(
                            spaceId = space.toString(),
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(memberId = b.toString(), role = EncounterSpaceRole.PULPIT),
                                ),
                        )
                    }.getOrThrow()
                    .map { it.memberId } shouldContainExactly listOf(b.toString())
                transaction { EncounterSpaceRoleTable.selectAll().where { EncounterSpaceRoleTable.spaceId eq space }.count() } shouldBe 1L

                rig
                    .asMember(client = client, member = boardId) {
                        it.setSpaceRoles(
                            spaceId = space.toString(),
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(memberId = a.toString(), role = EncounterSpaceRole.PULPIT),
                                    EncounterSpaceRoleAssignmentInput(memberId = a.toString(), role = EncounterSpaceRole.STEWARD),
                                ),
                        )
                    }.failure<BadRequestException>()
                rig
                    .asMember(client = client, member = boardId) {
                        it.setSpaceRoles(
                            spaceId = space.toString(),
                            assignments =
                                List(
                                    21,
                                ) {
                                    EncounterSpaceRoleAssignmentInput(
                                        memberId = Uuid.random().toString(),
                                        role = EncounterSpaceRole.PULPIT,
                                    )
                                },
                        )
                    }.failure<BadRequestException>()
                rig
                    .asMember(client = client, member = boardId) {
                        it.setSpaceRoles(
                            spaceId = space.toString(),
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(memberId = guest.toString(), role = EncounterSpaceRole.PULPIT),
                                ),
                        )
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = boardId) {
                        it.setSpaceRoles(
                            spaceId = space.toString(),
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(
                                        memberId = Uuid.random().toString(),
                                        role = EncounterSpaceRole.PULPIT,
                                    ),
                                ),
                        )
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = boardId) {
                        it.setSpaceRoles(
                            spaceId = space.toString(),
                            assignments = listOf(EncounterSpaceRoleAssignmentInput(memberId = "no-uuid", role = EncounterSpaceRole.PULPIT)),
                        )
                    }.failure<BadRequestException>()

                // two successful changes -> two entries; the snapshot names the office holders (open question F5 of the plan: IDs vs. counts)
                auditEntriesOf(space).size shouldBe 2
                auditEntriesOf(space).first().second!!.contains(a.toString()) shouldBe true
                auditEntriesOf(space).first().third shouldBe boardId
            }
        }

        // ── session lifecycle ─────────────────────────────────────────────

        test(
            "openSpace/closeSpace: the steward opens (LiveKit room with the right limits), a second open is idempotent, close tears everything down",
        ) {
            val rig = EncounterRig()
            val boardId = board()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                val opened = rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                opened.open shouldBe true
                (opened.openedAt != null) shouldBe true
                rig.liveKit.createdRooms shouldHaveSize 1
                rig.liveKit.createRoomArgs.single().let { (name, max, emptyTimeout) ->
                    name.startsWith("lc-") shouldBe true
                    max shouldBe 25
                    emptyTimeout shouldBe 1800
                }
                // empty_timeout only covers a never-joined room: the departure timeout keeps it alive after the pulpit left
                rig.liveKit.createRoomDepartureTimeouts.single() shouldBe 1800
                val room = fx.openSessionRoom(space)!!
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single()[ConferenceRoomTable.encounterSpaceId]
                } shouldBe space

                // idempotent
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow().open shouldBe true
                rig.liveKit.createdRooms shouldHaveSize 1
                fx.sessionRoomIds(space) shouldHaveSize 1

                rig.asMember(client = client, member = member) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                fx.participationCount(room) shouldBe 1L

                val closed = rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                closed.open shouldBe false
                rig.liveKit.deletedRooms shouldContainExactly listOf(rig.liveKit.createdRooms.single())
                fx.participationCount(room) shouldBe 0L
                fx.openSessionRoom(space) shouldBe null
                // idempotent
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow().open shouldBe false
                rig.liveKit.deletedRooms shouldHaveSize 1

                val audit = auditEntriesOf(space)
                audit.map { it.second } shouldContainExactly listOf("""{"state":"OPEN"}""", """{"state":"CLOSED","reason":"MANUAL"}""")
                audit.map { it.third } shouldContainExactly listOf(steward, steward)

                // a space can be opened again: a NEW session room
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow().open shouldBe true
                fx.sessionRoomIds(space) shouldHaveSize 2
            }
        }

        test("openSpace: a failing LiveKit createRoom leaves no session behind; the disabled feature gate is a conflict") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.liveKit.failAll = true
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.failure<ConflictException>()
                fx.sessionRoomIds(space) shouldHaveSize 0
                rig.liveKit.failAll = false
                rig
                    .let { r ->
                        client.runAs(
                            member = steward,
                        ) { call -> r.service(call = call, config = ENCOUNTER_CONFIG_DISABLED).openSpace(space.toString()) }
                    }.failure<ConflictException>()
            }
        }

        test("closeSpace: a failing LiveKit deleteRoom does not stop the database cleanup (data protection beats availability)") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                rig.asMember(client = client, member = member) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.liveKit.failDeleteRoom = true
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow().open shouldBe false
                fx.participationCount(room) shouldBe 0L
                fx.openSessionRoom(space) shouldBe null
            }
        }

        // ── entering ──────────────────────────────────────────────────────

        test("enterSpace: the congregation gets a listen-only token, the pulpit a publishing one, BOARD without an office listens only") {
            val rig = EncounterRig()
            val boardId = board()
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!

                val congregation =
                    rig
                        .asMember(
                            client = client,
                            member = member,
                        ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        .getOrThrow()
                congregation.presenceRole shouldBe EncounterPresenceRole.CONGREGATION
                congregation.canPublish shouldBe false
                congregation.canPublishData shouldBe true
                videoGrantOf(congregation.join.token)["canPublish"] shouldBe false
                congregation.join.identity shouldBe member.toString()
                congregation.join.roomId shouldBe room.toString()
                congregation.join.livekitRoomName shouldBe fx.livekitName(room)

                val pulpitEntry =
                    rig
                        .asMember(
                            client = client,
                            member = pulpit,
                        ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        .getOrThrow()
                pulpitEntry.presenceRole shouldBe EncounterPresenceRole.PULPIT
                pulpitEntry.canPublish shouldBe true
                // the join token's role agrees with canModerate of the space DTO and with requireSpaceModerator
                pulpitEntry.join.role.name shouldBe "MODERATOR"
                videoGrantOf(pulpitEntry.join.token)["canPublish"] shouldBe true

                val stewardEntry =
                    rig
                        .asMember(client = client, member = steward) {
                            it.enterSpace(spaceId = space.toString(), consent = null)
                        }.getOrThrow()
                stewardEntry.presenceRole shouldBe EncounterPresenceRole.STEWARD
                stewardEntry.canPublish shouldBe true

                val boardEntry =
                    rig
                        .asMember(
                            client = client,
                            member = boardId,
                        ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        .getOrThrow()
                boardEntry.presenceRole shouldBe EncounterPresenceRole.CONGREGATION
                boardEntry.canPublish shouldBe false
                videoGrantOf(boardEntry.join.token)["canPublish"] shouldBe false
                boardEntry.join.role.name shouldBe "MODERATOR"

                fx.participationCount(room) shouldBe 4L
            }
        }

        test("enterSpace: a reconnect reuses the presence row; a closed, an archived and a disabled space refuse entry") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                repeat(
                    3,
                ) {
                    rig
                        .asMember(
                            client = client,
                            member = member,
                        ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        .getOrThrow()
                }
                fx.participationCount(room) shouldBe 1L
                client
                    .runAs(
                        member = member,
                    ) { call ->
                        rig
                            .service(
                                call = call,
                                config = ENCOUNTER_CONFIG_DISABLED,
                            ).enterSpace(spaceId = space.toString(), consent = null)
                    }.failure<ConflictException>()
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.enterSpace(spaceId = "not-a-uuid", consent = null) }
                    .failure<NotFoundException>()
                rig
                    .asMember(client = client, member = member) {
                        it.enterSpace(spaceId = Uuid.random().toString(), consent = null)
                    }.failure<NotFoundException>()
            }
        }

        test("enterSpace: a non-member needs the CURRENT consent; it is stored once (member, version, hash, DATE) with no room reference") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val guest = fx.createMember(status = MemberStatus.GUEST)
            val friend = fx.createMember(status = MemberStatus.FRIEND)
            val space = fx.createSpace(createdBy = board(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!

                val info = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow()
                info.consentRequired shouldBe true
                info.disclaimer!!.version shouldBe EncounterConsentDisclaimer.VERSION
                info.disclaimer!!.sha256 shouldBe EncounterConsentDisclaimer.SHA256
                info.disclaimer!!.text shouldBe EncounterConsentDisclaimer.TEXT

                rig
                    .asMember(
                        client = client,
                        member = guest,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(spaceId = space.toString(), consent = CONSENT.copy(consentSha256 = "0".repeat(64)))
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(spaceId = space.toString(), consent = CONSENT.copy(consentSha256 = "not hex"))
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(spaceId = space.toString(), consent = CONSENT.copy(consentVersion = "old"))
                    }.failure<ConflictException>()
                fx.participationCount(room) shouldBe 0L
                transaction {
                    EncounterConsentAcknowledgmentTable.selectAll().where { EncounterConsentAcknowledgmentTable.memberId eq guest }.count()
                } shouldBe 0L

                val entry =
                    rig
                        .asMember(
                            client = client,
                            member = guest,
                        ) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }
                        .getOrThrow()
                entry.canPublish shouldBe false
                // a non-member gets the SHORT token (guestTokenTtlMinutes = 15), a member the long one
                tokenLifetimeSeconds(entry.join.token) shouldBe 15.minutes.inWholeSeconds

                val row =
                    transaction {
                        EncounterConsentAcknowledgmentTable
                            .selectAll()
                            .where {
                                EncounterConsentAcknowledgmentTable.memberId eq
                                    guest
                            }.single()
                    }
                row[EncounterConsentAcknowledgmentTable.consentVersion] shouldBe EncounterConsentDisclaimer.VERSION
                row[EncounterConsentAcknowledgmentTable.consentSha256] shouldBe EncounterConsentDisclaimer.SHA256
                row[EncounterConsentAcknowledgmentTable.acknowledgedOn] shouldBe OrganizationTimeZone.today()
                // the legacy conference consent table is never written for an encounter session
                transaction {
                    ConferenceGuestConsentAcknowledgmentTable
                        .selectAll()
                        .where { ConferenceGuestConsentAcknowledgmentTable.roomId eq room }
                        .count()
                } shouldBe 0L

                // the second entry needs no consent any more and writes nothing
                rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow().let {
                    it.consentRequired shouldBe false
                    it.disclaimer shouldBe null
                }
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                transaction {
                    EncounterConsentAcknowledgmentTable.selectAll().where { EncounterConsentAcknowledgmentTable.memberId eq guest }.count()
                } shouldBe 1L

                // a FRIEND is held to the same rule
                rig
                    .asMember(
                        client = client,
                        member = friend,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                rig.asMember(client = client, member = friend) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }.getOrThrow()
            }
        }

        test("enterSpace: an UPPERCASE consent hash is accepted, stored canonically and does not make the consent required again") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val guest = fx.createMember(status = MemberStatus.GUEST)
            val space = fx.createSpace(createdBy = board(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = space.toString(),
                            consent = CONSENT.copy(consentSha256 = EncounterConsentDisclaimer.SHA256.uppercase()),
                        )
                    }.getOrThrow()
                transaction {
                    EncounterConsentAcknowledgmentTable
                        .selectAll()
                        .where { EncounterConsentAcknowledgmentTable.memberId eq guest }
                        .single()[EncounterConsentAcknowledgmentTable.consentSha256]
                } shouldBe EncounterConsentDisclaimer.SHA256
                rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow().consentRequired shouldBe
                    false
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
            }
        }

        test("setSpaceRoles: a withdrawn office holder who is present is disconnected; a remaining one is not") {
            val rig = EncounterRig()
            val boardId = board()
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val name = fx.livekitName(room)
                rig.liveKit.connect(room = name, identity = pulpit.toString())
                rig.liveKit.connect(room = name, identity = steward.toString())
                rig
                    .asMember(client = client, member = boardId) {
                        it.setSpaceRoles(
                            spaceId = space.toString(),
                            assignments =
                                listOf(
                                    EncounterSpaceRoleAssignmentInput(memberId = steward.toString(), role = EncounterSpaceRole.STEWARD),
                                ),
                        )
                    }.getOrThrow()
                rig.liveKit.removed shouldContainExactly listOf(name to pulpit.toString())
                rig.liveKit.isConnected(room = name, identity = steward.toString()) shouldBe true
            }
        }

        test("enterSpace: an ACTIVE member's consent is ignored and nothing is stored for it") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = member) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }.getOrThrow()
                rig.asMember(client = client, member = member) { it.getEntryInfo(space.toString()) }.getOrThrow().consentRequired shouldBe
                    false
                transaction {
                    EncounterConsentAcknowledgmentTable.selectAll().where { EncounterConsentAcknowledgmentTable.memberId eq member }.count()
                } shouldBe 0L
            }
        }

        test("a member-only space is closed to GUEST and FRIEND (no entry, not listed), and a withdrawn member has no access at all") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val guest = fx.createMember(status = MemberStatus.GUEST)
            val withdrawn = fx.createMember(status = MemberStatus.WITHDRAWN)
            val applicant = fx.createMember(status = MemberStatus.APPLICATION)
            val space = fx.createSpace(createdBy = board(), guestPolicy = EncounterGuestPolicy.MEMBERS_ONLY)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(
                        client = client,
                        member = guest,
                    ) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }
                    .failure<ForbiddenException>()
                rig.asMember(client = client, member = guest) { it.getSpace(space.toString()) }.failure<ForbiddenException>()
                rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.failure<ForbiddenException>()
                rig.asMember(client = client, member = guest) { it.listSpaces() }.getOrThrow().none { it.id == space.toString() } shouldBe
                    true
                listOf(withdrawn, applicant).forEach { m ->
                    rig
                        .asMember(
                            client = client,
                            member = m,
                        ) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }
                        .failure<ForbiddenException>()
                    rig.asMember(client = client, member = m) { it.listSpaces() }.failure<ForbiddenException>()
                }
            }
        }

        // ── ceilings ──────────────────────────────────────────────────────

        test("ceilings: the congregation stops 2 short of the maximum, offices and BOARD may fill the reserve, nobody passes the maximum") {
            val rig = EncounterRig(config = ENCOUNTER_CONFIG_SMALL)
            val boardId = board()
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                // 4 congregation members fill the 6 - 2 slots
                repeat(4) { fx.insertParticipation(roomId = room, memberId = fx.createMember()) }
                val late = fx.createMember()
                rig
                    .asMember(
                        client = client,
                        member = late,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                // the pulpit and the steward use the reserve (total 6)
                rig.asMember(client = client, member = pulpit) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.asMember(client = client, member = steward) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                fx.participationCount(room) shouldBe 6L
                // BOARD is exempt from the congregation ceiling but not from the hard maximum
                rig
                    .asMember(
                        client = client,
                        member = boardId,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                rig
                    .asMember(
                        client = client,
                        member = late,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                // an already present person reconnecting is not turned away at the full ceiling
                rig.asMember(client = client, member = pulpit) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                fx.participationCount(room) shouldBe 6L
            }
        }

        test("ceilings: a space's own smaller maximum counts, and at most maxNonMemberParticipants guests are admitted") {
            val rig = EncounterRig(config = ENCOUNTER_CONFIG_SMALL)
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = board(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS, maxParticipants = 5)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.liveKit.createRoomArgs
                    .single()
                    .second shouldBe 5
                val room = fx.openSessionRoom(space)!!
                // 5 - 2 = 3 congregation places; two guests are admitted, the third guest hits the guest ceiling of 2
                val g1 = fx.createMember(status = MemberStatus.GUEST)
                val g2 = fx.createMember(status = MemberStatus.GUEST)
                val g3 = fx.createMember(status = MemberStatus.FRIEND)
                rig.asMember(client = client, member = g1) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }.getOrThrow()
                rig.asMember(client = client, member = g2) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }.getOrThrow()
                rig
                    .asMember(
                        client = client,
                        member = g3,
                    ) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }
                    .failure<ConflictException>()
                fx.participationCount(room) shouldBe 2L
                // the failed entry stored no consent proof either (the transaction rolled back)
                transaction {
                    EncounterConsentAcknowledgmentTable.selectAll().where { EncounterConsentAcknowledgmentTable.memberId eq g3 }.count()
                } shouldBe 0L
            }
        }

        // ── leaving and presence ──────────────────────────────────────────

        test("leaveSpace deletes the presence row (no left_at is kept), is idempotent and only touches the caller") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val a = fx.createMember()
            val b = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                rig.asMember(client = client, member = a) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.asMember(client = client, member = b) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.asMember(client = client, member = a) { it.leaveSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = a) { it.leaveSpace(space.toString()) }.getOrThrow()
                fx.participationCount(room) shouldBe 1L
                // nothing marks a as ever having been there
                transaction {
                    network.lapis.cloud.server.db.generated.ConferenceParticipationTable
                        .selectAll()
                        .where { network.lapis.cloud.server.db.generated.ConferenceParticipationTable.memberId eq a }
                        .count()
                } shouldBe 0L
                // leaving an unknown or closed space is harmless
                rig.asMember(client = client, member = a) { it.leaveSpace(Uuid.random().toString()) }.getOrThrow()
            }
        }

        test("listPresent: only a present person sees the present people, sorted by name, without times; others are refused") {
            val rig = EncounterRig()
            val boardId = board()
            val steward = fx.createMember(name = "Zacharias")
            val a = fx.createMember(name = "Anna")
            val outsider = fx.createMember()
            val guest = fx.createMember(status = MemberStatus.GUEST, name = "Gast")
            val space = fx.createSpace(createdBy = boardId, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.listPresent(space.toString()) }.failure<ForbiddenException>()
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = steward) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.asMember(client = client, member = a) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }.getOrThrow()

                val present = rig.asMember(client = client, member = a) { it.listPresent(space.toString()) }.getOrThrow()
                present.map { it.displayName.substringBefore(' ') } shouldContainExactly listOf("Anna", "Gast", "Zacharias")
                present.single { it.memberId == steward.toString() }.role shouldBe EncounterPresenceRole.STEWARD
                present.single { it.memberId == a.toString() }.role shouldBe EncounterPresenceRole.CONGREGATION
                present.single { it.memberId == guest.toString() }.isGuest shouldBe true

                // not present: refused -- also BOARD, so there is no surveillance from outside
                rig.asMember(client = client, member = outsider) { it.listPresent(space.toString()) }.failure<ForbiddenException>()
                rig.asMember(client = client, member = boardId) { it.listPresent(space.toString()) }.failure<ForbiddenException>()
                // the DTO count of the space is only a number
                rig.asMember(client = client, member = outsider) { it.getSpace(space.toString()) }.getOrThrow().presentCount shouldBe 3
            }
        }

        test("getSpace/listSpaces expose the pulpit names, the caller's own office and the open state") {
            val rig = EncounterRig()
            val pulpit = fx.createMember(name = "Pastor")
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = board(), title = "Andacht")
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            encounterApp {
                val asMember = rig.asMember(client = client, member = member) { it.getSpace(space.toString()) }.getOrThrow()
                asMember.pulpitDisplayNames.map { it.substringBefore(' ') } shouldContainExactly listOf("Pastor")
                asMember.myRole shouldBe null
                asMember.canModerate shouldBe false
                asMember.open shouldBe false
                val asPulpit = rig.asMember(client = client, member = pulpit) { it.getSpace(space.toString()) }.getOrThrow()
                asPulpit.myRole shouldBe EncounterSpaceRole.PULPIT
                asPulpit.canModerate shouldBe true
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.listSpaces() }
                    .getOrThrow()
                    .single { it.id == space.toString() }
                    .title shouldBe
                    "Andacht"
                rig.asMember(client = client, member = member) { it.getSpace(Uuid.random().toString()) }.failure<NotFoundException>()
            }
        }

        // ── moderation ────────────────────────────────────────────────────

        test("removeFromSpace: disconnects, deletes the row, blocks re-entry for the session and writes NO audit entry") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val troll = fx.createMember()
            val other = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val roomName = fx.livekitName(room)
                rig.asMember(client = client, member = troll) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.asMember(client = client, member = other) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.liveKit.connect(room = roomName, identity = troll.toString())
                val auditBefore = auditEntriesOf(space).size

                rig
                    .asMember(
                        client = client,
                        member = steward,
                    ) { it.removeFromSpace(spaceId = space.toString(), memberId = troll.toString()) }
                    .getOrThrow()
                rig.liveKit.removed shouldContainExactly listOf(roomName to troll.toString())
                fx.participationCount(room) shouldBe 1L
                rig
                    .asMember(
                        client = client,
                        member = troll,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ForbiddenException>()
                rig.asMember(client = client, member = other) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                auditEntriesOf(space).size shouldBe auditBefore

                // a new session starts clean: the block is dropped with the old session
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = troll) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
            }
        }

        test("silenceInSpace: the person re-enters without a data channel (canPublishData=false) and stays blocked from nothing else") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val noisy = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val roomName = fx.livekitName(fx.openSessionRoom(space)!!)
                rig
                    .asMember(client = client, member = noisy) {
                        it.enterSpace(spaceId = space.toString(), consent = null)
                    }.getOrThrow()
                    .canPublishData shouldBe
                    true
                rig.liveKit.connect(room = roomName, identity = noisy.toString())
                rig
                    .asMember(
                        client = client,
                        member = steward,
                    ) { it.silenceInSpace(spaceId = space.toString(), memberId = noisy.toString()) }
                    .getOrThrow()
                rig.liveKit.removed.size shouldBe 1
                val again =
                    rig
                        .asMember(
                            client = client,
                            member = noisy,
                        ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        .getOrThrow()
                again.canPublishData shouldBe false
                videoGrantOf(again.join.token)["canPublishData"] shouldBe false
                videoGrantOf(again.join.token)["canPublish"] shouldBe false
            }
        }

        test(
            "moderation: an office holder may not act against another office holder, BOARD/ADMIN or himself; BOARD may; a not-connected target is still blocked",
        ) {
            val rig = EncounterRig()
            val boardId = board()
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = steward) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = pulpit.toString())
                    }.failure<ForbiddenException>()
                rig
                    .asMember(client = client, member = pulpit) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = steward.toString())
                    }.failure<ForbiddenException>()
                rig
                    .asMember(client = client, member = steward) {
                        it.silenceInSpace(spaceId = space.toString(), memberId = boardId.toString())
                    }.failure<ForbiddenException>()
                rig
                    .asMember(client = client, member = steward) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = steward.toString())
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = steward) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = "not-a-uuid")
                    }.failure<BadRequestException>()
                // BOARD may remove an office holder
                rig
                    .asMember(client = client, member = boardId) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = steward.toString())
                    }.getOrThrow()
                rig
                    .asMember(
                        client = client,
                        member = steward,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ForbiddenException>()
                // an ordinary member may not moderate at all
                rig
                    .asMember(client = client, member = member) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = pulpit.toString())
                    }.failure<ForbiddenException>()
                // the target was never connected: still blocked, LiveKit never asked to remove
                rig.liveKit.removed.size shouldBe 0
                rig
                    .asMember(
                        client = client,
                        member = pulpit,
                    ) { it.removeFromSpace(spaceId = space.toString(), memberId = member.toString()) }
                    .getOrThrow()
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ForbiddenException>()
                // a LiveKit outage while removing a CONNECTED person surfaces as a conflict, but the block stays
                val other = fx.createMember()
                rig.asMember(client = client, member = other) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.liveKit.connect(room = fx.livekitName(fx.openSessionRoom(space)!!), identity = other.toString())
                rig.liveKit.failRemoveParticipant = true
                rig
                    .asMember(client = client, member = pulpit) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = other.toString())
                    }.failure<ConflictException>()
                rig.liveKit.failRemoveParticipant = false
                rig
                    .asMember(
                        client = client,
                        member = other,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ForbiddenException>()
            }
        }

        test("moderation refuses a target that is not a member (the bounded list cannot be filled with random UUIDs)") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = steward) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = Uuid.random().toString())
                    }.failure<NotFoundException>()
                rig
                    .asMember(client = client, member = steward) {
                        it.silenceInSpace(spaceId = space.toString(), memberId = Uuid.random().toString())
                    }.failure<NotFoundException>()
            }
        }

        test("moderation needs an open session; a full moderation list is refused instead of dropping an old block") {
            val moderationState = EncounterModerationState()
            val room = Uuid.random()
            repeat(EncounterModerationState.MAX_ENTRIES_PER_SESSION) {
                moderationState.block(sessionRoomId = room, memberId = Uuid.random()) shouldBe
                    true
            }
            moderationState.block(sessionRoomId = room, memberId = Uuid.random()) shouldBe false
            // silence has its own list
            moderationState.silence(sessionRoomId = room, memberId = Uuid.random()) shouldBe true
            moderationState.clear(room)
            moderationState.block(sessionRoomId = room, memberId = Uuid.random()) shouldBe true

            val rig = EncounterRig()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig
                    .asMember(client = client, member = steward) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = member.toString())
                    }.failure<ConflictException>()
            }
        }

        // ── authority follows the database ────────────────────────────────

        test(
            "an office withdrawn (role deleted or status changed) takes effect on the very next call, also for the one who opened the session",
        ) {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val pulpit = fx.createMember()
            val space = fx.createSpace(createdBy = board())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                // the pulpit holder withdraws from the organization: no longer ACTIVE -> no access at all
                fx.setStatus(memberId = pulpit, status = MemberStatus.WITHDRAWN)
                rig.asMember(client = client, member = pulpit) { it.closeSpace(space.toString()) }.failure<ForbiddenException>()
                // the steward's role row is removed while the session runs (the opener has no special right)
                transaction { EncounterSpaceRoleTable.deleteWhere { EncounterSpaceRoleTable.memberId eq steward } }
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.failure<ForbiddenException>()
                // and the token of an ex-pulpit member can no longer be a publishing one
                rig
                    .asMember(client = client, member = steward) {
                        it.enterSpace(spaceId = space.toString(), consent = null)
                    }.getOrThrow()
                    .canPublish shouldBe
                    false
            }
        }

        // ── throttles ─────────────────────────────────────────────────────

        test("every call group is throttled per member (a budget of 2 turns the THIRD call into a conflict)") {
            val rig = EncounterRig(limiter = { FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes) })
            val boardId = board()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                // open/close group: open (1), close (2), open again (3rd) is throttled
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                // enter group
                repeat(
                    2,
                ) {
                    rig
                        .asMember(
                            client = client,
                            member = member,
                        ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        .getOrThrow()
                }
                rig
                    .asMember(
                        client = client,
                        member = member,
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                fx.participationCount(room) shouldBe 1L
                // leave group
                repeat(2) { rig.asMember(client = client, member = member) { it.leaveSpace(space.toString()) }.getOrThrow() }
                rig.asMember(client = client, member = member) { it.leaveSpace(space.toString()) }.failure<ConflictException>()
                // moderation group
                repeat(2) {
                    rig
                        .asMember(client = client, member = steward) {
                            it.removeFromSpace(spaceId = space.toString(), memberId = member.toString())
                        }.getOrThrow()
                }
                rig
                    .asMember(client = client, member = steward) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = member.toString())
                    }.failure<ConflictException>()
                // config group
                repeat(2) {
                    rig
                        .asMember(client = client, member = boardId) {
                            it.updateSpace(spaceId = space.toString(), input = EncounterSpaceInput(title = "t"))
                        }.getOrThrow()
                }
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(spaceId = space.toString(), input = EncounterSpaceInput(title = "t"))
                    }.failure<ConflictException>()
                // list group
                repeat(2) { rig.asMember(client = client, member = member) { it.listSpaces() }.getOrThrow() }
                rig.asMember(client = client, member = member) { it.listSpaces() }.failure<ConflictException>()
                // another member has his own budget
                rig.asMember(client = client, member = fx.createMember()) { it.listSpaces() }.getOrThrow()
                // open/close group, last: close (2), open again (3rd)
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.failure<ConflictException>()
            }
        }
    })

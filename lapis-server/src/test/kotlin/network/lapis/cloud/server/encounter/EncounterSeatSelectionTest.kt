package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ENCOUNTER_SEAT_MAX
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val SEAT_CONSENT =
    EncounterConsentInput(
        consentVersion = EncounterConsentDisclaimer.VERSION,
        consentSha256 = EncounterConsentDisclaimer.SHA256,
    )

/** Welle V1.9.79 -- `selectSeat` end to end on H2 with a fake LiveKit: access, conflicts, throttle, release points, no trace. */
class EncounterSeatSelectionTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        class World(
            val rig: EncounterRig,
            val space: Uuid,
            val steward: Uuid,
            val pulpit: Uuid,
            val a: Uuid,
            val b: Uuid,
        )

        fun world(rig: EncounterRig = EncounterRig()): World {
            val board = fx.createMember(role = AccountRole.BOARD)
            val steward = fx.createMember(name = "Steward")
            val pulpit = fx.createMember(name = "Pulpit")
            val space = fx.createSpace(createdBy = board, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            return World(rig, space, steward, pulpit, fx.createMember(name = "Anna"), fx.createMember(name = "Berta"))
        }

        suspend fun io.ktor.server.testing.ApplicationTestBuilder.openAndEnter(
            w: World,
            vararg people: Uuid,
        ) {
            w.rig.asMember(client = client, member = w.steward) { it.openSpace(w.space.toString()) }.getOrThrow()
            people.forEach { p ->
                w.rig.asMember(client = client, member = p) { it.enterSpace(spaceId = w.space.toString(), consent = null) }.getOrThrow()
            }
        }

        test("a seated person is returned with the seat; an office holder never has a seat; entry starts unseated") {
            val w = world()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.listPresent(w.space.toString()) }
                    .getOrThrow()
                    .all { it.seat == null } shouldBe
                    true
                val list =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.selectSeat(spaceId = w.space.toString(), seat = 4) }
                        .getOrThrow()
                list.single { it.memberId == w.a.toString() }.seat shouldBe 4
                list.single { it.memberId == w.steward.toString() }.seat shouldBe null
                list.single { it.memberId == w.steward.toString() }.role shouldBe EncounterPresenceRole.STEWARD
                // listPresent shows it to the other present person as well
                w.rig
                    .asMember(client = client, member = w.steward) { it.listPresent(w.space.toString()) }
                    .getOrThrow()
                    .single { it.memberId == w.a.toString() }
                    .seat shouldBe 4
                // choosing nothing releases
                val released =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.selectSeat(spaceId = w.space.toString(), seat = null) }
                        .getOrThrow()
                released.single { it.memberId == w.a.toString() }.seat shouldBe null
                // entering again starts unseated
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 4) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.enterSpace(spaceId = w.space.toString(), consent = null) }.getOrThrow()
                w.rig
                    .asMember(client = client, member = w.a) { it.listPresent(w.space.toString()) }
                    .getOrThrow()
                    .single { it.memberId == w.a.toString() }
                    .seat shouldBe null
            }
        }

        test("two people choosing the same seat: the second gets ConflictException; a guest may sit") {
            val w = world()
            val guest = fx.createMember(status = MemberStatus.GUEST, name = "Gast")
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                w.rig
                    .asMember(
                        client = client,
                        member = guest,
                    ) { it.enterSpace(spaceId = w.space.toString(), consent = SEAT_CONSENT) }
                    .getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 2) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.b,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 2) }
                    .failure<ConflictException>()
                w.rig
                    .asMember(client = client, member = guest) { it.selectSeat(spaceId = w.space.toString(), seat = 3) }
                    .getOrThrow()
                    .single { it.memberId == guest.toString() }
                    .seat shouldBe 3
            }
        }

        test("access: not present, closed space, PULPIT and STEWARD are refused") {
            val w = world()
            val outsider = fx.createMember()
            encounterApp {
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }
                    .failure<ForbiddenException>()
                openAndEnter(w = w, w.steward, w.pulpit, w.a)
                w.rig
                    .asMember(
                        client = client,
                        member = outsider,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }
                    .failure<ForbiddenException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }
                    .failure<ForbiddenException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.pulpit,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }
                    .failure<ForbiddenException>()
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.space.toString()) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }
                    .failure<ForbiddenException>()
            }
        }

        test("range: -1, beyond the hard maximum and beyond the current capacity are BadRequest") {
            val w = world()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = -1) }
                    .failure<BadRequestException>()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = ENCOUNTER_SEAT_MAX) }
                    .failure<BadRequestException>()
                // one congregation person: capacity is the minimum of 24 seats
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 24) }
                    .failure<BadRequestException>()
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 23) }.getOrThrow()
            }
        }

        test("throttle: a second change within one second is ServiceBusyException (not Conflict)") {
            val w = world(EncounterRig(seatLimiterOverride = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)))
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.selectSeat(spaceId = w.space.toString(), seat = 2) }
                    .failure<ServiceBusyException>()
            }
            // the production setting really is 1 per second
            FederationInboxRateLimiter(maxRequests = 1, window = 1.seconds).checkAndRecord("x") shouldBe true
        }

        test("the seat is free again after leave, removal, close and a poller close") {
            val w = world()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                val roomId = fx.openSessionRoom(w.space)!!
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 6) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.leaveSpace(w.space.toString()) }.getOrThrow()
                w.rig.asMember(client = client, member = w.b) { it.selectSeat(spaceId = w.space.toString(), seat = 6) }.getOrThrow()
                // removal frees the seat of the removed person
                w.rig
                    .asMember(client = client, member = w.steward) {
                        it.removeFromSpace(spaceId = w.space.toString(), memberId = w.b.toString())
                    }.getOrThrow()
                w.rig.seatState.snapshot(sessionRoomId = roomId, present = setOf(w.b)) shouldBe emptyMap()
                // close clears the session
                w.rig.asMember(client = client, member = w.a) { it.enterSpace(spaceId = w.space.toString(), consent = null) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }.getOrThrow()
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.space.toString()) }.getOrThrow()
                w.rig.seatState.trackedSessions() shouldBe 0
            }
        }

        test("a seat of a person whose presence row vanished is free for the next person") {
            val w = world()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                val roomId = fx.openSessionRoom(w.space)!!
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 8) }.getOrThrow()
                fx.deleteParticipation(roomId = roomId, memberId = w.a)
                w.rig
                    .asMember(client = client, member = w.b) { it.selectSeat(spaceId = w.space.toString(), seat = 8) }
                    .getOrThrow()
                    .single { it.memberId == w.b.toString() }
                    .seat shouldBe 8
            }
        }

        test("choosing a seat writes no audit entry") {
            val w = world()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                val before = fx.auditCount()
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = 1) }.getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.space.toString(), seat = null) }.getOrThrow()
                fx.auditCount() shouldBe before
            }
        }
    })

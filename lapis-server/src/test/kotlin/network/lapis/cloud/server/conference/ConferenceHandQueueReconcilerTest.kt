package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.conference.ConferenceHandQueueState.Change
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventOnlineJoin
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.127 -- [ConferenceHandQueueReconciler]: removes the hand of somebody who is gone for the grace, tells newly arrived people the queue
 * point to point (an account-less event guest has no RPC read access), ignores egress identities, drops ended rooms, never throws.
 */
class ConferenceHandQueueReconcilerTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        var clock = 10_000_000L

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.createdByMemberId inList memberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        fun member(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Handabgleich Test"
                    it[email] = "hand-reconcile-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            memberIds += id
            return id
        }

        fun room(creator: Uuid): Pair<Uuid, String> {
            val id = Uuid.random()
            val name = "lc-$id"
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Handabgleichsraum"
                    it[description] = ""
                    it[livekitRoomName] = name
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
            }
            return id to name
        }

        fun state() = ConferenceHandQueueState(nowMillis = { clock })

        fun reconciler(
            lk: ModerationFakeLiveKit,
            state: ConferenceHandQueueState,
        ) = ConferenceHandQueueReconciler(liveKitAdminClient = lk, state = state, liveKitEnabled = true)

        fun hands(lk: ModerationFakeLiveKit) = lk.packets.filter { it.topic == CONFERENCE_HANDS_TOPIC }

        test("a hand of somebody who is gone for 20 s is removed and the room is told; a short absence keeps the position") {
            val lk = ModerationFakeLiveKit()
            val s = state()
            val creator = member()
            val (roomId, name) = room(creator)
            val (a, b) = member().toString() to member().toString()
            lk.add(room = name, participant = liveParticipant(identity = a))
            lk.add(room = name, participant = liveParticipant(identity = b))
            s.raise(roomId = roomId, identity = a)
            s.raise(roomId = roomId, identity = b)
            val r = reconciler(lk, s)
            r.tick()
            lk.packets.clear()
            // a leaves
            lk.participants[name]!!.removeIf { it.identity == a }
            r.tick()
            clock += 10_000
            r.tick()
            s.snapshot(roomId).identities shouldContainExactly listOf(a, b)
            hands(lk).shouldBeEmpty()
            clock += 10_000
            r.tick()
            s.snapshot(roomId).identities shouldContainExactly listOf(b)
            hands(lk).map { it.payload } shouldContainExactly
                listOf("""{"v":1,"e":"${s.epoch}","seq":${s.snapshot(roomId).seq},"ids":["$b"]}""")
            hands(lk).single().destinations shouldBe null
            hands(lk).single().room shouldBe name
        }

        test("a person who comes back within the grace keeps the place") {
            val lk = ModerationFakeLiveKit()
            val s = state()
            val (roomId, name) = room(member())
            val a = member().toString()
            lk.add(room = name, participant = liveParticipant(identity = a))
            s.raise(roomId = roomId, identity = a)
            val r = reconciler(lk, s)
            r.tick()
            lk.participants[name]!!.clear()
            clock += 15_000
            r.tick()
            lk.add(room = name, participant = liveParticipant(identity = a))
            clock += 15_000
            r.tick()
            clock += 15_000
            r.tick()
            s.snapshot(roomId).identities shouldContainExactly listOf(a)
        }

        test("a newly arrived person, even an account-less event guest, gets the snapshot point to point -- once") {
            val lk = ModerationFakeLiveKit()
            val s = state()
            val (roomId, name) = room(member())
            val a = member().toString()
            lk.add(room = name, participant = liveParticipant(identity = a))
            s.raise(roomId = roomId, identity = a)
            val r = reconciler(lk, s)
            r.tick()
            lk.packets.clear()
            val guest = "evg-${Uuid.random()}"
            lk.add(room = name, participant = liveParticipant(identity = guest))
            lk.add(room = name, participant = liveParticipant(identity = "EG_egress-1"))
            lk.add(room = name, participant = liveParticipant(identity = "not a uuid either"))
            r.tick()
            hands(lk).size shouldBe 1
            hands(lk).single().destinations shouldBe listOf(guest)
            hands(lk).single().payload shouldBe """{"v":1,"e":"${s.epoch}","seq":1,"ids":["$a"]}"""
            r.tick()
            hands(lk).size shouldBe 1
        }

        test("a ticket guest (deterministic evg-<32 hex> identity) gets the snapshot point to point as well") {
            val lk = ModerationFakeLiveKit()
            val s = state()
            val (roomId, name) = room(member())
            val a = member().toString()
            lk.add(room = name, participant = liveParticipant(identity = a))
            s.raise(roomId = roomId, identity = a)
            val r = reconciler(lk, s)
            r.tick()
            lk.packets.clear()
            val ticketGuest = EventOnlineJoin.ticketGuestIdentity(eventId = Uuid.random(), ticketCodeSha256 = "ab".repeat(32))
            HAND_IDENTITY_REGEX.matches(ticketGuest) shouldBe true
            lk.add(room = name, participant = liveParticipant(identity = ticketGuest))
            r.tick()
            hands(lk).size shouldBe 1
            hands(lk).single().destinations shouldBe listOf(ticketGuest)
            r.tick()
            hands(lk).size shouldBe 1
        }

        test("a room without a raised hand is not even listed in LiveKit; an ended room is dropped from the state") {
            val lk = ModerationFakeLiveKit()
            val s = state()
            val (emptyRoom, _) = room(member())
            val (endedRoom, _) = room(member())
            s.snapshot(emptyRoom)
            s.raise(roomId = endedRoom, identity = member().toString())
            transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq endedRoom }) { it[endedAt] = DbClock.nowLocalDateTime() } }
            lk.failList = true // would show up as a warning if it were asked
            reconciler(lk, s).tick()
            s.knownRoomIds() shouldBe setOf(emptyRoom)
        }

        test("an unreachable LiveKit never throws and keeps the queue; a disabled reconciler does nothing") {
            val lk = ModerationFakeLiveKit()
            val s = state()
            val (roomId, _) = room(member())
            val a = member().toString()
            s.raise(roomId = roomId, identity = a)
            lk.failList = true
            reconciler(lk, s).tick()
            s.snapshot(roomId).identities shouldContainExactly listOf(a)
            lk.failList = false
            ConferenceHandQueueReconciler(liveKitAdminClient = lk, state = s, liveKitEnabled = false).tick()
            lk.packets.shouldBeEmpty()
            s.raise(roomId = roomId, identity = member().toString()).let { (it is Change.Changed) shouldBe true }
        }
    })

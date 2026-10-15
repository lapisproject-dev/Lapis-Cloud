package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_TOPIC
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.133 -- [ConferenceTimerReconciler]: tells newly arrived people the timer point to point, leaves OFF rooms alone, ignores egress
 * identities, drops ended rooms, never throws.
 */
class ConferenceTimerReconcilerTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()

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
                    it[displayName] = "Timerabgleich Test"
                    it[email] = "timer-reconcile-$id@example.org"
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
                    it[title] = "Timerabgleichsraum"
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

        fun reconciler(
            lk: ModerationFakeLiveKit,
            state: ConferenceTimerState,
            enabled: Boolean = true,
        ) = ConferenceTimerReconciler(liveKitAdminClient = lk, state = state, liveKitEnabled = enabled)

        fun timer(lk: ModerationFakeLiveKit) = lk.packets.filter { it.topic == CONFERENCE_TIMER_TOPIC }

        test("a new identity gets the snapshot point to point exactly once; egress identities are ignored; OFF rooms are not touched") {
            val lk = ModerationFakeLiveKit()
            val s = ConferenceTimerState()
            val creator = member()
            val (roomId, name) = room(creator)
            val (offId, offName) = room(creator)
            val a = member().toString()
            val guest = "evg-${Uuid.random()}"
            lk.add(room = name, participant = liveParticipant(identity = a))
            lk.add(room = name, participant = liveParticipant(identity = "EG_recorder"))
            lk.add(room = offName, participant = liveParticipant(identity = a))
            s.start(roomId = roomId, durationMs = 60_000)
            s.snapshot(offId)
            val r = reconciler(lk, s)
            r.tick()
            timer(lk).size shouldBe 1
            timer(lk)[0].room shouldBe name
            timer(lk)[0].destinations shouldContainExactly listOf(a)
            timer(lk)[0].payload.startsWith("{\"v\":1,\"e\":\"${s.epoch}\",\"seq\":1,\"s\":\"R\",\"d\":60000,\"rem\":") shouldBe true
            // nothing new -> nothing sent
            r.tick()
            timer(lk).size shouldBe 1
            // a guest arrives -> only the guest is told
            lk.add(room = name, participant = liveParticipant(identity = guest))
            r.tick()
            timer(lk).size shouldBe 2
            timer(lk)[1].destinations shouldContainExactly listOf(guest)
        }

        test("a paused and an expired timer are announced too; rooms that ended are dropped from the state") {
            val lk = ModerationFakeLiveKit()
            val s = ConferenceTimerState()
            val creator = member()
            val (roomId, name) = room(creator)
            lk.add(room = name, participant = liveParticipant(identity = member().toString()))
            s.start(roomId = roomId, durationMs = 60_000)
            s.pause(roomId = roomId)
            val r = reconciler(lk, s)
            r.tick()
            timer(lk).single().payload.contains("\"s\":\"P\"") shouldBe true
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[endedAt] = DbClock.nowLocalDateTime() }
            }
            r.tick()
            s.knownRoomIds().contains(roomId) shouldBe false
        }

        test("tick never throws on a LiveKit failure and does nothing when LiveKit is disabled") {
            val lk = ModerationFakeLiveKit()
            val s = ConferenceTimerState()
            val creator = member()
            val (roomId, name) = room(creator)
            lk.add(room = name, participant = liveParticipant(identity = member().toString()))
            s.start(roomId = roomId, durationMs = 60_000)
            lk.failList = true
            reconciler(lk, s).tick()
            timer(lk).shouldBeEmpty()
            lk.failList = false
            lk.failSendTo = true
            reconciler(lk, s).tick()
            timer(lk).shouldBeEmpty()
            lk.failSendTo = false
            reconciler(lk, s, enabled = false).tick()
            timer(lk).shouldBeEmpty()
        }

        test("the same identity with a new participant sid (reload) is told again") {
            val lk = ModerationFakeLiveKit()
            val s = ConferenceTimerState()
            val (roomId, name) = room(member())
            val guest = "evg-${Uuid.random()}"
            lk.add(room = name, participant = liveParticipant(identity = guest, sid = "PA_1"))
            s.start(roomId = roomId, durationMs = 60_000)
            val r = reconciler(lk, s)
            r.tick()
            timer(lk).size shouldBe 1
            r.tick()
            timer(lk).size shouldBe 1
            lk.participants[name]!!.clear()
            lk.add(room = name, participant = liveParticipant(identity = guest, sid = "PA_2"))
            r.tick()
            timer(lk).size shouldBe 2
            timer(lk)[1].destinations shouldContainExactly listOf(guest)
        }

        test("a failed send is retried on the next tick") {
            val lk = ModerationFakeLiveKit()
            val s = ConferenceTimerState()
            val (roomId, name) = room(member())
            val a = member().toString()
            lk.add(room = name, participant = liveParticipant(identity = a, sid = "PA_1"))
            s.start(roomId = roomId, durationMs = 60_000)
            val r = reconciler(lk, s)
            lk.failSendTo = true
            r.tick()
            timer(lk).shouldBeEmpty()
            lk.failSendTo = false
            r.tick()
            timer(lk).single().destinations shouldContainExactly listOf(a)
            r.tick()
            timer(lk).size shouldBe 1
        }

        test("a change of the timer tells everybody present again") {
            val lk = ModerationFakeLiveKit()
            val s = ConferenceTimerState()
            val (roomId, name) = room(member())
            val a = member().toString()
            lk.add(room = name, participant = liveParticipant(identity = a, sid = "PA_1"))
            s.start(roomId = roomId, durationMs = 60_000)
            val r = reconciler(lk, s)
            r.tick()
            timer(lk).size shouldBe 1
            s.pause(roomId = roomId)
            r.tick()
            timer(lk).size shouldBe 2
        }
    })

package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.108 -- [ConferenceAudioPolicyReconciler]: corrects a microphone permission and mutes an open microphone of a non-moderator in a LOCKED
 * room, leaves moderators / released people / unlocked / ended rooms alone, skips identities that are no member UUID, never throws, and
 * logs counts only.
 */
class ConferenceAudioPolicyReconcilerTest :
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

        fun member(role: AccountRole = AccountRole.MEMBER): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Abgleich Test"
                    it[email] = "reconcile-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            memberIds += id
            return id
        }

        fun room(
            creator: Uuid,
            locked: Boolean = true,
            ended: Boolean = false,
        ): Pair<Uuid, String> {
            val id = Uuid.random()
            val name = "lc-$id"
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Abgleichsraum"
                    it[description] = ""
                    it[livekitRoomName] = name
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = if (ended) DbClock.nowLocalDateTime() else null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                    it[participantsMayUnmute] = !locked
                }
            }
            return id to name
        }

        fun reconciler(
            lk: ModerationFakeLiveKit,
            grants: ConferenceAudioGrantState = ConferenceAudioGrantState(),
        ) = ConferenceAudioPolicyReconciler(liveKitAdminClient = lk, grants = grants, liveKitEnabled = true)

        test("a stale permission (microphone still in it) is replaced and an open microphone is muted -- for a non-moderator only") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val board = member(AccountRole.BOARD)
            val participant = member()
            val (_, name) = room(creator)
            lk.add(room = name, participant = liveParticipant(identity = creator.toString()))
            lk.add(room = name, participant = liveParticipant(identity = board.toString()))
            lk.add(room = name, participant = liveParticipant(identity = participant.toString()))
            reconciler(lk).tick()
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(participant.toString())
            lk.permissionUpdates
                .single()
                .permission.canPublishSources shouldContainExactly
                listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO")
            lk.mutes.map { it.identity } shouldContainExactly listOf(participant.toString())
        }

        test("a permission that names MICROPHONE explicitly is corrected too") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val participant = member()
            val (_, name) = room(creator)
            lk.add(
                room = name,
                participant =
                    liveParticipant(
                        identity = participant.toString(),
                        microphoneOpen = false,
                        permissionSources = listOf("CAMERA", "MICROPHONE"),
                    ),
            )
            reconciler(lk).tick()
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(participant.toString())
            lk.mutes.shouldBeEmpty()
        }

        test("a person released by a moderator is left alone, and a second tick after a correction does nothing (idempotent)") {
            val lk = ModerationFakeLiveKit()
            val grants = ConferenceAudioGrantState()
            val creator = member()
            val released = member()
            val other = member()
            val (roomId, name) = room(creator)
            grants.grant(roomId = roomId, memberId = released)
            lk.add(room = name, participant = liveParticipant(identity = released.toString()))
            lk.add(room = name, participant = liveParticipant(identity = other.toString()))
            val reconciler = reconciler(lk, grants)
            reconciler.tick()
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(other.toString())
            val updates = lk.permissionUpdates.size
            val mutes = lk.mutes.size
            reconciler.tick()
            lk.permissionUpdates.size shouldBe updates
            lk.mutes.size shouldBe mutes
        }

        test("unlocked rooms, ended rooms and identities that are no member UUID are never touched") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val a = member()
            val b = member()
            val (_, unlocked) = room(creator, locked = false)
            val (_, ended) = room(creator, ended = true)
            val (_, locked) = room(creator)
            lk.add(room = unlocked, participant = liveParticipant(identity = a.toString()))
            lk.add(room = ended, participant = liveParticipant(identity = b.toString()))
            lk.add(room = locked, participant = liveParticipant(identity = "EG_egress-1"))
            lk.add(room = locked, participant = liveParticipant(identity = "not a uuid either"))
            reconciler(lk).tick()
            lk.permissionUpdates.shouldBeEmpty()
            lk.mutes.shouldBeEmpty()
        }

        test("an account-less event guest (evg- identity) in a locked room is corrected and muted like any non-moderator") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val (_, name) = room(creator)
            val guest = "evg-${Uuid.random()}"
            lk.add(room = name, participant = liveParticipant(identity = creator.toString()))
            lk.add(room = name, participant = liveParticipant(identity = guest))
            reconciler(lk).tick()
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(guest)
            lk.permissionUpdates
                .single()
                .permission.canPublishSources shouldContainExactly
                listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO")
            lk.mutes.map { it.identity } shouldContainExactly listOf(guest)
        }

        test("a person who is already correct (no microphone permission, microphone muted) is not touched") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val a = member()
            val (_, name) = room(creator)
            lk.add(
                room = name,
                participant =
                    liveParticipant(
                        identity = a.toString(),
                        microphoneOpen = false,
                        permissionSources = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"),
                    ),
            )
            reconciler(lk).tick()
            lk.permissionUpdates.shouldBeEmpty()
            lk.mutes.shouldBeEmpty()
        }

        test("an unreachable LiveKit or a failing call never throws and the logs name counts only, never an id") {
            val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender =
                ch.qos.logback.core.read
                    .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
            appender.start()
            root.addAppender(appender)
            try {
                val creator = member()
                val a = member()
                val (roomId, name) = room(creator)
                val lk = ModerationFakeLiveKit()
                lk.add(room = name, participant = liveParticipant(identity = a.toString()))
                lk.failList = true
                reconciler(lk).tick() // must not throw
                lk.failList = false
                lk.failPermission = true
                reconciler(lk).tick() // must not throw
                lk.failPermission = false
                lk.failMute = true
                reconciler(lk).tick()
                val logged = appender.list.joinToString("\n") { it.formattedMessage }
                listOf(creator, a, roomId).forEach { logged shouldNotContain it.toString() }
                logged shouldNotContain name
            } finally {
                root.detachAppender(appender)
            }
        }

        test("a disabled reconciler does nothing") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val a = member()
            val (_, name) = room(creator)
            lk.add(room = name, participant = liveParticipant(identity = a.toString()))
            ConferenceAudioPolicyReconciler(liveKitAdminClient = lk, grants = ConferenceAudioGrantState(), liveKitEnabled = false).tick()
            lk.permissionUpdates.shouldBeEmpty()
        }

        test(
            "a room whose unlock did not reach LiveKit gets the microphone permission back (permission only, no unmute), then stays quiet",
        ) {
            val lk = ModerationFakeLiveKit()
            val grants = ConferenceAudioGrantState()
            val creator = member()
            val a = member()
            val (roomId, name) = room(creator, locked = false)
            val reconciler = reconciler(lk, grants)
            reconciler.tick() // consumes the one-off startup heal pass (nobody in the room yet)
            lk.add(
                room = name,
                participant =
                    liveParticipant(
                        identity = a.toString(),
                        microphoneOpen = false,
                        permissionSources = listOf("CAMERA", "SCREEN_SHARE", "SCREEN_SHARE_AUDIO"),
                    ),
            )
            reconciler.tick() // not marked -> untouched
            lk.permissionUpdates.shouldBeEmpty()
            grants.markUnlockPending(roomId)
            reconciler.tick()
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(a.toString())
            lk.permissionUpdates
                .single()
                .permission.canPublishSources
                .contains("MICROPHONE") shouldBe true
            lk.mutes.shouldBeEmpty()
            grants.isUnlockPending(roomId) shouldBe false
            reconciler.tick()
            lk.permissionUpdates.size shouldBe 1
        }

        test("after a restart the first tick heals an unlocked room whose in-memory unlock mark was lost, once per process") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val a = member()
            val (_, name) = room(creator, locked = false)
            lk.add(
                room = name,
                participant = liveParticipant(identity = a.toString(), microphoneOpen = false, permissionSources = listOf("CAMERA")),
            )
            val grants = ConferenceAudioGrantState() // fresh process: nothing is marked
            val reconciler = reconciler(lk, grants)
            reconciler.tick()
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(a.toString())
            lk.mutes.shouldBeEmpty()
            reconciler.tick()
            lk.permissionUpdates.size shouldBe 1
        }

        test("a room that ended while marked, and its mutex, are dropped by the next tick") {
            val lk = ModerationFakeLiveKit()
            val grants = ConferenceAudioGrantState()
            val creator = member()
            val (roomId, _) = room(creator, locked = false)
            grants.markUnlockPending(roomId)
            grants.withRoomLock(roomId = roomId) { }
            grants.grant(roomId = roomId, memberId = member())
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[endedAt] = DbClock.nowLocalDateTime() }
            }
            reconciler(lk, grants).tick()
            grants.isUnlockPending(roomId) shouldBe false
            grants.pendingUnlockRooms().contains(roomId) shouldBe false
            grants.hasRoomLock(roomId) shouldBe false
        }

        test("a failing heal keeps the mark so the next tick retries") {
            val lk = ModerationFakeLiveKit()
            val grants = ConferenceAudioGrantState()
            val creator = member()
            val a = member()
            val (roomId, name) = room(creator, locked = false)
            lk.add(room = name, participant = liveParticipant(identity = a.toString(), permissionSources = listOf("CAMERA")))
            grants.markUnlockPending(roomId)
            lk.failPermission = true
            val reconciler = reconciler(lk, grants)
            reconciler.tick()
            grants.isUnlockPending(roomId) shouldBe true
            lk.failPermission = false
            reconciler.tick()
            grants.isUnlockPending(roomId) shouldBe false
            lk.permissionUpdates.map { it.identity } shouldContainExactly listOf(a.toString())
        }

        test("a moderator unlocking after the room query of the tick is never undone (lock re-read after the listing)") {
            val lk = ModerationFakeLiveKit()
            val creator = member()
            val a = member()
            val (roomId, name) = room(creator, locked = true)
            // everybody already holds the microphone permission, as after setRoomAudioPolicy(unlock) ...
            lk.add(room = name, participant = liveParticipant(identity = a.toString(), microphoneOpen = false))
            // ... which commits while the reconciler's ListParticipants is in flight (after it read the room as locked)
            lk.afterListSnapshot = {
                transaction {
                    ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[participantsMayUnmute] = true }
                }
            }
            reconciler(lk).tick()
            lk.permissionUpdates.shouldBeEmpty()
            lk.mutes.shouldBeEmpty()
        }

        test("the per-room lock holds the reconciler back while a policy change is in progress") {
            val lk = ModerationFakeLiveKit()
            val grants = ConferenceAudioGrantState()
            val creator = member()
            val a = member()
            val (roomId, name) = room(creator, locked = true)
            lk.add(room = name, participant = liveParticipant(identity = a.toString()))
            val inside = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            kotlinx.coroutines.coroutineScope {
                val holder =
                    launch {
                        grants.withRoomLock(roomId = roomId) {
                            inside.complete(Unit)
                            release.await()
                        }
                    }
                inside.await()
                val tick = launch { reconciler(lk, grants).tick() }
                kotlinx.coroutines.delay(300)
                lk.permissionUpdates.shouldBeEmpty() // blocked on the lock
                // the "moderator" unlocks while holding the lock, then lets go
                transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[participantsMayUnmute] = true } }
                release.complete(Unit)
                holder.join()
                tick.join()
            }
            lk.permissionUpdates.shouldBeEmpty()
            lk.mutes.shouldBeEmpty()
        }
    })

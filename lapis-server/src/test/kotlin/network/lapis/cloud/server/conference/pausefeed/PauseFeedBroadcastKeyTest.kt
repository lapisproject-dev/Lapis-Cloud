package network.lapis.cloud.server.conference.pausefeed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.conference.BroadcastKeys
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * V1.9.274 -- the pause feed sends to the SAME URL the live stream used: for a per-broadcast destination that is the key of this broadcast, taken from
 * the target row; a target whose key is gone is left out of the feed (a stream without any usable target gets no feed).
 */
class PauseFeedBroadcastKeyTest :
    FunSpec({
        val box = SecretBox(Base64.getDecoder().decode(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })))
        val ids = mutableListOf<Uuid>() // members
        val rooms = mutableListOf<Uuid>()
        val destinations = mutableListOf<Uuid>()
        val streams = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                streams.forEach { s -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq s } }
                streams.forEach { s -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq s } }
                destinations.forEach { d -> ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id eq d } }
                rooms.forEach { r -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq r } }
                ids.forEach { m -> AccountTable.deleteWhere { AccountTable.memberId eq m } }
                ids.forEach { m -> MemberTable.deleteWhere { MemberTable.id eq m } }
            }
        }

        test(
            "the pause feed gets the per-broadcast key's URL; a persistent target uses its stored key; a target without a key is left out",
        ) {
            val memberId = Uuid.random()
            val roomId = Uuid.random()
            val perBroadcast = Uuid.random()
            val persistent = Uuid.random()
            val lost = Uuid.random()
            val streamId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val targetIds = listOf(Uuid.random(), Uuid.random(), Uuid.random())
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = "Pause Feed Key"
                    it[email] = "pf-key-$memberId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.MEMBER
                }
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "PF-Key-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-pfkey-$roomId"
                    it[createdByMemberId] = memberId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[capturePausedAt] = now
                    it[maxParticipants] = 25
                }

                fun destination(
                    id: Uuid,
                    kind: ConferenceStreamKeyKind,
                    base: String,
                    storedKey: String?,
                ) = ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[label] = "pfkey-$id"
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = base
                    it[keyKind] = kind
                    it[streamKeyCiphertext] = storedKey?.let { k -> box.seal(plaintext = k, aad = id.toString()) }
                    it[streamKeySetAt] = if (storedKey != null) now else null
                    it[createdByMemberId] = memberId
                    it[createdAt] = now
                    it[enabled] = true
                }
                destination(perBroadcast, ConferenceStreamKeyKind.PER_BROADCAST, "rtmp://a.example.org/live", null)
                destination(persistent, ConferenceStreamKeyKind.PERSISTENT, "rtmp://b.example.org/live", "stored-key-777")
                destination(lost, ConferenceStreamKeyKind.PER_BROADCAST, "rtmp://c.example.org/live", null)
                ConferenceStreamTable.insert {
                    it[id] = streamId
                    it[ConferenceStreamTable.roomId] = roomId
                    it[startedByMemberId] = memberId
                    it[status] = ConferenceStreamStatus.PAUSED
                    it[pauseReason] = ConferenceStreamPauseReason.ROOM_PAUSE
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[startedAt] = now
                    it[restartCount] = 0
                }

                fun target(
                    id: Uuid,
                    destination: Uuid,
                    key: String?,
                ) = ConferenceStreamTargetTable.insert {
                    it[ConferenceStreamTargetTable.id] = id
                    it[ConferenceStreamTargetTable.streamId] = streamId
                    it[destinationId] = destination
                    it[status] = ConferenceStreamTargetStatus.PENDING
                    it[urlFingerprint] = "fp-$id"
                    it[broadcastKeyCiphertext] = key?.let { k -> BroadcastKeys.seal(targetId = id, key = k, box = box) }
                    it[retries] = 0
                }
                target(targetIds[0], perBroadcast, "this-broadcast-key-1")
                target(targetIds[1], persistent, null)
                target(targetIds[2], lost, null)
            }
            ids += memberId
            rooms += roomId
            destinations += listOf(perBroadcast, persistent, lost)
            streams += streamId

            val wish = transaction { PauseFeedWishes.wish(streamId = streamId, secretBox = box) }!!
            wish.targetUrls.sorted() shouldBe
                listOf(
                    "rtmp://a.example.org/live/this-broadcast-key-1",
                    "rtmp://b.example.org/live/stored-key-777",
                ).sorted()
            wish.toString() shouldBe "PauseFeedWish(targets=2)"

            // Without any usable target there is no feed at all.
            transaction {
                ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq targetIds[0] }) { it[broadcastKeyCiphertext] = null }
                ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id eq persistent }) {
                    it[streamKeyCiphertext] =
                        null
                }
            }
            transaction { PauseFeedWishes.wish(streamId = streamId, secretBox = box) } shouldBe null
        }
    })

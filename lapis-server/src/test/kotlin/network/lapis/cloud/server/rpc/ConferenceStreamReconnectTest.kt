package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.conference.BroadcastKeys
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamBroadcastInput
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.275 -- `reconnectStreamTarget`: who may call it (role first, before the limit counts), the states it accepts, the key rules of both key kinds,
 * `UpdateStream` on the running egress for ONE target (never a second egress), the refusal path, the rate limit with an injected clock and the new
 * retry window. A fake egress, dummy keys, zero network.
 */
class ConferenceStreamReconnectTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val roomIds = mutableListOf<Uuid>()
        val destinationIds = mutableListOf<Uuid>()
        val box = SecretBox(Base64.getDecoder().decode(BK_KEY_B64))

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
                val streams =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            ConferenceStreamTable.roomId inList roomIds
                        }.map { it[ConferenceStreamTable.id] }
                streams.forEach { s -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq s } }
                streams.forEach { s -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq s } }
                ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.destinationId inList destinationIds }
                ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList destinationIds }
                roomIds.forEach { r -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq r } }
                memberIds.forEach { m -> AccountTable.deleteWhere { AccountTable.memberId eq m } }
                memberIds.forEach { m -> MemberTable.deleteWhere { MemberTable.id eq m } }
            }
        }

        fun member(role: AccountRole = AccountRole.MEMBER): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Reconnect Test"
                    it[email] = "rc-$id@example.org"
                    it[MemberTable.status] = MemberStatus.ACTIVE
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

        fun room(creator: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Reconnect-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-rc-$id"
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = null
                    it[maxParticipants] = 25
                }
            }
            roomIds += id
            return id
        }

        fun destination(
            creator: Uuid,
            kind: ConferenceStreamKeyKind,
            host: String,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[label] = "rc-dest-$id"
                    it[platform] = network.lapis.cloud.shared.domain.ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmp://$host:1935/live"
                    it[keyKind] = kind
                    it[streamKeyCiphertext] =
                        if (kind ==
                            ConferenceStreamKeyKind.PERSISTENT
                        ) {
                            box.seal(plaintext = "persistent-dummy-1", aad = id.toString())
                        } else {
                            null
                        }
                    it[streamKeySetAt] = if (kind == ConferenceStreamKeyKind.PERSISTENT) now else null
                    it[createdByMemberId] = creator
                    it[createdAt] = now
                    it[enabled] = true
                }
            }
            destinationIds += id
            return id
        }

        fun targetRow(
            streamId: String,
            destination: Uuid,
        ) = transaction {
            ConferenceStreamTargetTable
                .selectAll()
                .where {
                    (ConferenceStreamTargetTable.streamId eq Uuid.parse(streamId)) and
                        (ConferenceStreamTargetTable.destinationId eq destination)
                }.single()
        }

        fun failTarget(
            streamId: String,
            destination: Uuid,
        ) {
            transaction {
                ConferenceStreamTargetTable.update({
                    (ConferenceStreamTargetTable.streamId eq Uuid.parse(streamId)) and
                        (ConferenceStreamTargetTable.destinationId eq destination)
                }) {
                    it[status] = ConferenceStreamTargetStatus.FAILED
                    it[failureKind] = ConferenceStreamFailureKind.REJECTED
                    it[failureReason] = "fixed text"
                    it[autoRetryAttempt] = 3
                }
            }
        }

        suspend fun Harness.start(
            who: Uuid,
            room: Uuid,
            destinations: List<Uuid>,
            keys: Map<Uuid, String> = emptyMap(),
        ) = call(member = who) {
            it.startStream(
                roomId = room.toString(),
                destinationIds = destinations.map(Uuid::toString),
                layout = ConferenceStreamLayout.GRID,
                latencyMode = ConferenceStreamLatencyMode.STANDARD,
                participantIdentity = null,
                broadcastInputs = keys.map { (d, k) -> ConferenceStreamBroadcastInput(destinationId = d.toString(), streamKey = k) },
            )
        }

        suspend fun Harness.reconnect(
            who: Uuid,
            streamId: String,
            destination: Uuid,
            key: ConferenceStreamBroadcastInput? = null,
        ) = call(member = who) { it.reconnectStreamTarget(streamId = streamId, destinationId = destination.toString(), broadcastKey = key) }

        fun keyInput(
            destination: Uuid,
            key: String?,
            viewerUrl: String? = null,
        ) = ConferenceStreamBroadcastInput(destinationId = destination.toString(), streamKey = key, viewerUrl = viewerUrl)

        test(
            "a persistent target is re-added on the RUNNING egress: old and new output are the same URL, no second egress, the other target is untouched",
        ) {
            val owner = member()
            val room = room(owner)
            val a = destination(owner, ConferenceStreamKeyKind.PERSISTENT, "a.example.org")
            val b = destination(owner, ConferenceStreamKeyKind.PERSISTENT, "b.example.org")
            val egress = BkEgress()
            runBkHarness(egress = egress) {
                val dto = start(owner, room, listOf(a, b))
                failTarget(dto.id, a)
                val bBefore = targetRow(dto.id, b)
                val result = reconnect(owner, dto.id, a)
                egress.startedUrls.size shouldBe 1
                egress.updates.flatMap { it.second } shouldBe listOf("rtmp://a.example.org:1935/live/persistent-dummy-1")
                egress.updates.flatMap { it.first } shouldBe listOf("rtmp://a.example.org:1935/live/persistent-dummy-1")
                val row = targetRow(dto.id, a)
                row[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
                row[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 0
                row[ConferenceStreamTargetTable.failureKind] shouldBe null
                row[ConferenceStreamTargetTable.autoRetryIssuedAt] shouldNotBe null
                row[ConferenceStreamTargetTable.retryWindowStartedAt] shouldNotBe null
                targetRow(dto.id, b)[ConferenceStreamTargetTable.status] shouldBe bBefore[ConferenceStreamTargetTable.status]
                result.targets.single { it.destinationId == a.toString() }.status shouldBe ConferenceStreamTargetStatus.PENDING
                result.status shouldBe ConferenceStreamStatus.LIVE
            }
        }

        test("a persistent destination takes no new key") {
            val owner = member()
            val room = room(owner)
            val a = destination(owner, ConferenceStreamKeyKind.PERSISTENT, "a.example.org")
            runBkHarness {
                val dto = start(owner, room, listOf(a))
                failTarget(dto.id, a)
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, a, keyInput(a, "other-key-1")) }.name shouldBe
                    "ConferenceStreamTargetKeyPersistentException"
                targetRow(dto.id, a)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            }
        }

        test(
            "a per-broadcast target with a new key: ciphertext and fingerprint replaced, old URL removed, new URL added; without a key the old one is reused",
        ) {
            val owner = member()
            val room = room(owner)
            val d = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST, "fb.example.org")
            val egress = BkEgress()
            runBkHarness(egress = egress) {
                val dto = start(owner, room, listOf(d), mapOf(d to "old-key-0001"))
                val before = targetRow(dto.id, d)
                failTarget(dto.id, d)
                reconnect(owner, dto.id, d, keyInput(d, "new-key-0002"))
                egress.updates.flatMap { it.second } shouldBe listOf("rtmp://fb.example.org:1935/live/old-key-0001")
                egress.updates.flatMap { it.first } shouldBe listOf("rtmp://fb.example.org:1935/live/new-key-0002")
                val after = targetRow(dto.id, d)
                after[ConferenceStreamTargetTable.urlFingerprint] shouldNotBe before[ConferenceStreamTargetTable.urlFingerprint]
                val sealed = after[ConferenceStreamTargetTable.broadcastKeyCiphertext]!!
                sealed.contains("new-key-0002") shouldBe false
                box.open(sealed = sealed, aad = BroadcastKeys.aad(after[ConferenceStreamTargetTable.id])) shouldBe "new-key-0002"
                // Fail again and reconnect without a key (blank counts as none): the stored key is reused.
                failTarget(dto.id, d)
                egress.updates.clear()
                reconnect(owner, dto.id, d, keyInput(d, "  ".trim()))
                egress.updates.flatMap { it.first } shouldBe listOf("rtmp://fb.example.org:1935/live/new-key-0002")
                egress.updates.flatMap { it.second } shouldBe listOf("rtmp://fb.example.org:1935/live/new-key-0002")
            }
        }

        test("key errors: invalid key, missing stored key, a key for another destination, a viewer address") {
            val owner = member()
            val room = room(owner)
            val d = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST, "fb.example.org")
            runBkHarness {
                val dto = start(owner, room, listOf(d), mapOf(d to "old-key-0001"))
                failTarget(dto.id, d)
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, d, keyInput(d, "two words")) }.name shouldBe
                    "ConferenceStreamBroadcastKeyRejectedException"
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, d, keyInput(Uuid.random(), "some-key-1")) }.name shouldBe
                    "BadRequestException"
                shouldThrow<RemoteFailure> {
                    reconnect(
                        owner,
                        dto.id,
                        d,
                        keyInput(d, null, viewerUrl = "https://example.org/"),
                    )
                }.name shouldBe
                    "BadRequestException"
                transaction {
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.destinationId eq d }) {
                        it[broadcastKeyCiphertext] =
                            null
                    }
                }
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, d) }.name shouldBe "ConferenceStreamBroadcastKeyMissingException"
                targetRow(dto.id, d)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            }
        }

        test("states: only a FAILED target of a LIVE stream; a paused stream and an active target are refused") {
            val owner = member()
            val room = room(owner)
            val a = destination(owner, ConferenceStreamKeyKind.PERSISTENT, "a.example.org")
            runBkHarness {
                val dto = start(owner, room, listOf(a))
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, a) }.name shouldBe "ConferenceStreamTargetNotFailedException"
                failTarget(dto.id, a)
                call(member = owner) { it.pauseStream(dto.id) }.status shouldBe ConferenceStreamStatus.PAUSED
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, a) }.name shouldBe "ConferenceStreamReconnectNotLiveException"
            }
        }

        test("roles: the room creator and BOARD may, an ordinary member may not -- and a refused caller does not use up the limit") {
            val owner = member()
            val stranger = member()
            val board = member(AccountRole.BOARD)
            val room = room(owner)
            val a = destination(owner, ConferenceStreamKeyKind.PERSISTENT, "a.example.org")
            val limiter = FederationInboxRateLimiter(maxRequests = 5, window = 10.minutes)
            runBkHarness(reconnectLimiter = limiter) {
                val dto = start(owner, room, listOf(a))
                failTarget(dto.id, a)
                repeat(8) { shouldThrow<RemoteFailure> { reconnect(stranger, dto.id, a) }.name shouldBe "ForbiddenException" }
                reconnect(board, dto.id, a).status shouldBe ConferenceStreamStatus.LIVE
                failTarget(dto.id, a)
                reconnect(owner, dto.id, a).status shouldBe ConferenceStreamStatus.LIVE
            }
        }

        test(
            "rate limit: five per ten minutes per member and stream, then the sixth is refused; the window passes with an injected clock",
        ) {
            val owner = member()
            val room = room(owner)
            val a = destination(owner, ConferenceStreamKeyKind.PERSISTENT, "a.example.org")
            val now = arrayOf(Instant.parse("2026-11-04T10:00:00Z"))
            val clock =
                object : Clock {
                    override fun now(): Instant = now[0]
                }
            val limiter = FederationInboxRateLimiter(maxRequests = 5, window = 10.minutes, clock = clock)
            runBkHarness(reconnectLimiter = limiter) {
                val dto = start(owner, room, listOf(a))
                repeat(5) {
                    failTarget(dto.id, a)
                    reconnect(owner, dto.id, a)
                }
                failTarget(dto.id, a)
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, a) }.name shouldBe "ConflictException"
                now[0] = now[0] + 601.seconds
                reconnect(owner, dto.id, a).status shouldBe ConferenceStreamStatus.LIVE
            }
        }

        test(
            "when LiveKit refuses the add the target is FAILED again with a fixed reason, the new key stays sealed, and the call reports the typed failure",
        ) {
            val owner = member()
            val room = room(owner)
            val d = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST, "fb.example.org")
            val egress = BkEgress()
            runBkHarness(egress = egress) {
                val dto = start(owner, room, listOf(d), mapOf(d to "old-key-0001"))
                failTarget(dto.id, d)
                egress.failAdd = true
                shouldThrow<RemoteFailure> { reconnect(owner, dto.id, d, keyInput(d, "new-key-0002")) }.name shouldBe
                    "ConferenceStreamReconnectFailedException"
                val row = targetRow(dto.id, d)
                row[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
                row[ConferenceStreamTargetTable.failureReason] shouldBe "Das Ziel konnte nicht neu verbunden werden."
                row[ConferenceStreamTargetTable.autoRetryIssuedAt] shouldBe null
                box.open(
                    sealed = row[ConferenceStreamTargetTable.broadcastKeyCiphertext]!!,
                    aad = BroadcastKeys.aad(row[ConferenceStreamTargetTable.id]),
                ) shouldBe
                    "new-key-0002"
                egress.startedUrls.size shouldBe 1
            }
        }
    })

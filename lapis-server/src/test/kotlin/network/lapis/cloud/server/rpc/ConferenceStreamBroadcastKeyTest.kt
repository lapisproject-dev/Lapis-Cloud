package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.conference.BroadcastKeys
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
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
import network.lapis.cloud.shared.domain.ConferenceStreamKeyHint
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal const val BK_ADMIN_ID = "00000000-0000-0000-0000-000000000001"

internal val BK_CONFERENCE_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }
internal val BK_KEY_B64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
internal val BK_STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> BK_KEY_B64
            "LAPIS_STREAM_MAX_DESTINATIONS" -> "3"
            else -> null
        }
    }

/** The type name of the exception the service threw, as the throwaway route reports it. */
internal class RemoteFailure(
    val name: String,
) : RuntimeException(name)

internal class BkEgress(
    private val failStart: Boolean = false,
) : LiveKitEgressClient {
    private val counter = AtomicInteger(0)
    val startedUrls = Collections.synchronizedList(mutableListOf<List<String>>())

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo = LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> = emptyList()

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        startedUrls += rtmpUrls
        if (failStart) throw LiveKitAdminException(message = "simulated egress start failure")
        return LiveKitEgressInfo(egressId = "EG_bk_${counter.incrementAndGet()}", status = "EGRESS_STARTING")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun startParticipantFileEgress(
        roomName: String,
        identity: String,
        outputFilepath: String,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("unused")
}

internal class Harness(
    private val client: HttpClient,
    private val setPending: (suspend (ConferenceStreamingService) -> Any?) -> Unit,
    private val takeResult: () -> Any?,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> call(
        member: Uuid,
        block: suspend (ConferenceStreamingService) -> T,
    ): T = callAs(memberHeader = member.toString(), block = block)

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> callAs(
        memberHeader: String,
        block: suspend (ConferenceStreamingService) -> T,
    ): T {
        setPending { svc -> block(svc) }
        val response = client.post("/run") { header("X-Member-Id", memberHeader) }
        if (response.status != HttpStatusCode.OK) throw RemoteFailure(response.bodyAsText())
        return takeResult() as T
    }
}

/**
 * V1.9.274 -- the key of ONE broadcast: start/pause/resume/stop with a per-broadcast destination, deletion of the key at every end of a stream, the
 * kind switch, `setBroadcastKey`/`setViewerUrl`, who may call what, the rate limit and what the DTOs reveal to whom. Zero real network (a fake egress
 * client); every key is a dummy value.
 */
class ConferenceStreamBroadcastKeyTest :
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
                        .where { ConferenceStreamTable.roomId inList roomIds }
                        .map { it[ConferenceStreamTable.id] }
                streams.forEach { s -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq s } }
                streams.forEach { s -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq s } }
                ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.destinationId inList destinationIds }
                ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList destinationIds }
                roomIds.forEach { r -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq r } }
                memberIds.forEach { m -> AccountTable.deleteWhere { AccountTable.memberId eq m } }
                memberIds.forEach { m -> MemberTable.deleteWhere { MemberTable.id eq m } }
            }
        }

        fun member(
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Schluessel Test"
                    it[email] = "bk-$id@example.org"
                    it[MemberTable.status] = status
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
                    it[title] = "Schluessel-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-bk-$id"
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
            storedKey: String? = if (kind == ConferenceStreamKeyKind.PERSISTENT) "persistent-dummy-key-1" else null,
            platform: ConferenceStreamPlatform = ConferenceStreamPlatform.GENERIC_RTMP,
            rtmpUrl: String = "rtmp://sink.example.org:1935/live",
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[label] = "bk-dest-$id"
                    it[ConferenceStreamDestinationTable.platform] = platform
                    it[ConferenceStreamDestinationTable.rtmpUrl] = rtmpUrl
                    it[keyKind] = kind
                    it[streamKeyCiphertext] = storedKey?.let { k -> box.seal(plaintext = k, aad = id.toString()) }
                    it[streamKeySetAt] = if (storedKey != null) now else null
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

        fun brokenKeyOf(
            streamId: String,
            destination: Uuid,
        ): String? = targetRow(streamId, destination)[ConferenceStreamTargetTable.broadcastKeyCiphertext]

        fun streamStatus(streamId: String): ConferenceStreamStatus =
            transaction {
                ConferenceStreamTable
                    .selectAll()
                    .where {
                        ConferenceStreamTable.id eq
                            Uuid.parse(
                                streamId,
                            )
                    }.single()[ConferenceStreamTable.status]
            }

        suspend fun harness(
            egress: LiveKitEgressClient = BkEgress(),
            inputLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
            body: suspend Harness.() -> Unit,
        ) = runBkHarness(egress = egress, inputLimiter = inputLimiter, body = body)

        suspend fun Harness.start(
            who: Uuid,
            room: Uuid,
            destinations: List<Uuid>,
            inputs: List<ConferenceStreamBroadcastInput> = emptyList(),
        ) = call(member = who) {
            it.startStream(
                roomId = room.toString(),
                destinationIds = destinations.map(Uuid::toString),
                layout = ConferenceStreamLayout.GRID,
                latencyMode = ConferenceStreamLatencyMode.STANDARD,
                participantIdentity = null,
                broadcastInputs = inputs,
            )
        }

        fun input(
            destination: Uuid,
            key: String? = null,
            viewerUrl: String? = null,
        ) = ConferenceStreamBroadcastInput(destinationId = destination.toString(), streamKey = key, viewerUrl = viewerUrl)

        // ── start ───────────────────────────────────────────────────────

        test("a per-broadcast destination cannot be started without its key, and no stream row is created") {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            harness {
                shouldThrow<RemoteFailure> { start(owner, room, listOf(dest)) }.name shouldBe "ConferenceStreamBroadcastKeyMissingException"
                shouldThrow<RemoteFailure> {
                    start(owner, room, listOf(dest), listOf(input(dest, key = null, viewerUrl = null)))
                }.name shouldBe "ConferenceStreamBroadcastKeyMissingException"
            }
            transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq room }.count() } shouldBe 0
        }

        test("an invalid key (space, line break, too long) is refused at the start with the key-rejected exception") {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            harness {
                listOf("two words", "line\nbreak", "x".repeat(513)).forEach { bad ->
                    shouldThrow<RemoteFailure> {
                        start(owner, room, listOf(dest), listOf(input(dest, key = bad)))
                    }.name shouldBe "ConferenceStreamBroadcastKeyRejectedException"
                }
            }
        }

        test("a persistent destination takes no one-time key (bad request) and a persistent destination without a stored key is refused") {
            val owner = member()
            val room = room(owner)
            val persistent = destination(owner, ConferenceStreamKeyKind.PERSISTENT)
            val lost = destination(owner, ConferenceStreamKeyKind.PERSISTENT, storedKey = null)
            harness {
                shouldThrow<RemoteFailure> {
                    start(owner, room, listOf(persistent), listOf(input(persistent, key = "other-key")))
                }.name shouldBe "BadRequestException"
                shouldThrow<RemoteFailure> { start(owner, room, listOf(lost)) }.name shouldBe "ConferenceStreamStoredKeyMissingException"
            }
        }

        test("the typed key reaches the egress request as <rtmpUrl>/<key>, is stored sealed (not the key) and is bound to its target") {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val egress = BkEgress()
            harness(egress) {
                val dto = start(owner, room, listOf(dest), listOf(input(dest, key = "FB-live-key-4711")))
                egress.startedUrls.single() shouldBe listOf("rtmp://sink.example.org:1935/live/FB-live-key-4711")
                val sealed = brokenKeyOf(dto.id, dest)!!
                sealed.contains("FB-live-key-4711") shouldBe false
                val row = targetRow(dto.id, dest)
                box.open(sealed = sealed, aad = BroadcastKeys.aad(row[ConferenceStreamTargetTable.id])) shouldBe "FB-live-key-4711"
                dto.targets.single().keyKind shouldBe ConferenceStreamKeyKind.PER_BROADCAST
                // The destination row itself never holds a key.
                transaction {
                    ConferenceStreamDestinationTable
                        .selectAll()
                        .where { ConferenceStreamDestinationTable.id eq dest }
                        .single()[ConferenceStreamDestinationTable.streamKeyCiphertext]
                } shouldBe null
            }
        }

        test("a viewer address given at the start is normalised and stored; a rejected one names the destination and creates nothing") {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST, platform = ConferenceStreamPlatform.YOUTUBE)
            harness {
                shouldThrow<RemoteFailure> {
                    start(
                        owner,
                        room,
                        listOf(dest),
                        listOf(input(dest, key = "yt-key-1", viewerUrl = "https://evil.example.org/watch?v=dQw4w9WgXcQ")),
                    )
                }.name shouldBe "ConferenceStreamViewerUrlRejectedException"
                transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq room }.count() } shouldBe 0
                val dto =
                    start(owner, room, listOf(dest), listOf(input(dest, key = "yt-key-1", viewerUrl = "https://youtu.be/dQw4w9WgXcQ")))
                dto.targets.single().viewerUrl shouldBe "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ"
            }
        }

        // ── pause / resume ──────────────────────────────────────────────

        test("pause and resume use the same key: the second egress request carries the identical URL") {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val egress = BkEgress()
            harness(egress) {
                val dto = start(owner, room, listOf(dest), listOf(input(dest, key = "same-key-0001")))
                call(member = owner) { it.pauseStream(dto.id) }.status shouldBe ConferenceStreamStatus.PAUSED
                brokenKeyOf(dto.id, dest) shouldNotBe null
                val resumed = call(member = owner) { it.resumeStream(dto.id) }
                resumed.restartCount shouldBe 1
                egress.startedUrls.size shouldBe 2
                egress.startedUrls[1] shouldBe egress.startedUrls[0]
            }
        }

        test(
            "a resume with one target without a key marks it FAILED with the fixed reason and restarts the others; with none left the stream stays PAUSED",
        ) {
            val owner = member()
            val room = room(owner)
            val withKey = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val lostKey = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val egress = BkEgress()
            harness(egress) {
                val dto =
                    start(
                        owner,
                        room,
                        listOf(withKey, lostKey),
                        listOf(input(withKey, key = "key-with-0001"), input(lostKey, key = "key-lost-0002")),
                    )
                call(member = owner) { it.pauseStream(dto.id) }
                // The key of one target vanishes (as after a restore).
                transaction {
                    ConferenceStreamTargetTable.update({
                        (ConferenceStreamTargetTable.streamId eq Uuid.parse(dto.id)) and
                            (ConferenceStreamTargetTable.destinationId eq lostKey)
                    }) { it[broadcastKeyCiphertext] = null }
                }
                call(member = owner) { it.resumeStream(dto.id) }.status shouldBe ConferenceStreamStatus.LIVE
                egress.startedUrls.last() shouldBe listOf("rtmp://sink.example.org:1935/live/key-with-0001")
                val failed = targetRow(dto.id, lostKey)
                failed[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
                failed[ConferenceStreamTargetTable.failureReason] shouldBe "Stream-Schlüssel dieser Sendung fehlt."

                // Now the last usable key vanishes too: no endless retry, the stream stays PAUSED with MANUAL.
                call(member = owner) { it.pauseStream(dto.id) }
                transaction {
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq Uuid.parse(dto.id) }) {
                        it[broadcastKeyCiphertext] = null
                    }
                }
                val callsBefore = egress.startedUrls.size
                call(member = owner) { it.resumeStream(dto.id) }
                egress.startedUrls.size shouldBe callsBefore
                transaction {
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq Uuid.parse(dto.id) }.single()
                }.let {
                    it[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                    it[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
                }
            }
        }

        // ── the key is deleted when the stream ends ─────────────────────

        test("stop, a failed start and a repeated stop delete the key of the stream") {
            val owner = member()
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            // stop
            val room1 = room(owner)
            harness {
                val dto = start(owner, room1, listOf(dest), listOf(input(dest, key = "stop-key-0001")))
                brokenKeyOf(dto.id, dest) shouldNotBe null
                call(member = owner) { it.stopStream(dto.id) }.status shouldBe ConferenceStreamStatus.ENDED
                brokenKeyOf(dto.id, dest) shouldBe null
                // a key that was left behind somehow is removed by the idempotent second stop
                transaction {
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq Uuid.parse(dto.id) }) {
                        it[broadcastKeyCiphertext] = "v1:leftover:leftover"
                    }
                }
                call(member = owner) { it.stopStream(dto.id) }
                brokenKeyOf(dto.id, dest) shouldBe null
            }
            // failed start
            val room2 = room(owner)
            harness(BkEgress(failStart = true)) {
                val dto = start(owner, room2, listOf(dest), listOf(input(dest, key = "fail-key-0002")))
                dto.status shouldBe ConferenceStreamStatus.FAILED
                brokenKeyOf(dto.id, dest) shouldBe null
            }
        }

        // ── key kind switch ─────────────────────────────────────────────

        test(
            "switching a destination to per-broadcast deletes the stored key; switching back needs a key; neither is possible while a stream uses it",
        ) {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PERSISTENT)
            val admin = Uuid.parse(BK_ADMIN_ID)
            harness {
                val toPerBroadcast =
                    call(member = admin) {
                        it.updateDestination(
                            destinationId = dest.toString(),
                            label = "bk-dest-$dest",
                            rtmpUrl = "rtmp://sink.example.org:1935/live",
                            newStreamKey = null,
                            keyKind = ConferenceStreamKeyKind.PER_BROADCAST,
                        )
                    }
                toPerBroadcast.keyKind shouldBe ConferenceStreamKeyKind.PER_BROADCAST
                toPerBroadcast.streamKeySetAt shouldBe null
                transaction {
                    ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq dest }.single()
                }[ConferenceStreamDestinationTable.streamKeyCiphertext] shouldBe null

                // per-broadcast must not carry a key
                shouldThrow<RemoteFailure> {
                    call(member = admin) {
                        it.updateDestination(
                            destinationId = dest.toString(),
                            label = "bk-dest-$dest",
                            rtmpUrl = "rtmp://sink.example.org:1935/live",
                            newStreamKey = "some-key",
                            keyKind = null,
                        )
                    }
                }.name shouldBe "BadRequestException"
                // back to persistent: the key is mandatory
                shouldThrow<RemoteFailure> {
                    call(member = admin) {
                        it.updateDestination(
                            destinationId = dest.toString(),
                            label = "bk-dest-$dest",
                            rtmpUrl = "rtmp://sink.example.org:1935/live",
                            newStreamKey = null,
                            keyKind = ConferenceStreamKeyKind.PERSISTENT,
                        )
                    }
                }.name shouldBe "BadRequestException"
                val back =
                    call(member = admin) {
                        it.updateDestination(
                            destinationId = dest.toString(),
                            label = "bk-dest-$dest",
                            rtmpUrl = "rtmp://sink.example.org:1935/live",
                            newStreamKey = "new-persistent-key",
                            keyKind = ConferenceStreamKeyKind.PERSISTENT,
                        )
                    }
                back.keyKind shouldBe ConferenceStreamKeyKind.PERSISTENT
                (back.streamKeySetAt != null) shouldBe true

                // a running stream blocks the switch
                start(owner, room, listOf(dest))
                shouldThrow<RemoteFailure> {
                    call(member = admin) {
                        it.updateDestination(
                            destinationId = dest.toString(),
                            label = "bk-dest-$dest",
                            rtmpUrl = "rtmp://sink.example.org:1935/live",
                            newStreamKey = null,
                            keyKind = ConferenceStreamKeyKind.PER_BROADCAST,
                        )
                    }
                }.name shouldBe "ConflictException"
            }
        }

        test("createDestination: persistent needs a key, per-broadcast refuses one, a bad key is refused, non-ADMIN is forbidden") {
            val plain = member()
            harness {
                val admin = Uuid.parse(BK_ADMIN_ID)
                shouldThrow<RemoteFailure> {
                    call(
                        member = admin,
                    ) {
                        it.createDestination(
                            label = "bk-new-a",
                            platform = ConferenceStreamPlatform.GENERIC_RTMP,
                            rtmpUrl = "rtmp://a.example.org/live",
                            streamKey = null,
                        )
                    }
                }.name shouldBe "BadRequestException"
                shouldThrow<RemoteFailure> {
                    call(member = admin) {
                        it.createDestination(
                            label = "bk-new-b",
                            platform = ConferenceStreamPlatform.GENERIC_RTMP,
                            rtmpUrl = "rtmp://a.example.org/live",
                            streamKey = "key",
                            keyKind = ConferenceStreamKeyKind.PER_BROADCAST,
                        )
                    }
                }.name shouldBe "BadRequestException"
                shouldThrow<RemoteFailure> {
                    call(
                        member = admin,
                    ) {
                        it.createDestination(
                            label = "bk-new-c",
                            platform = ConferenceStreamPlatform.GENERIC_RTMP,
                            rtmpUrl = "rtmp://a.example.org/live",
                            streamKey = "has space",
                        )
                    }
                }.name shouldBe "ConferenceStreamBroadcastKeyRejectedException"
                val created =
                    call(member = admin) {
                        it.createDestination(
                            label = "bk-new-d-${Uuid.random()}",
                            platform = ConferenceStreamPlatform.GENERIC_RTMP,
                            rtmpUrl = "rtmp://a.example.org/live",
                            streamKey = null,
                            keyKind = ConferenceStreamKeyKind.PER_BROADCAST,
                        )
                    }
                destinationIds += Uuid.parse(created.id)
                created.keyKind shouldBe ConferenceStreamKeyKind.PER_BROADCAST
                created.streamKeySetAt shouldBe null
                shouldThrow<RemoteFailure> {
                    call(member = plain) {
                        it.createDestination(
                            label = "bk-new-e",
                            platform = ConferenceStreamPlatform.GENERIC_RTMP,
                            rtmpUrl = "rtmp://a.example.org/live",
                            streamKey = null,
                            keyKind = ConferenceStreamKeyKind.PER_BROADCAST,
                        )
                    }
                }.name shouldBe "ForbiddenException"
            }
        }

        // ── setBroadcastKey / setViewerUrl ──────────────────────────────

        test("setBroadcastKey stores a new sealed key for a running stream, refuses a persistent target, a bad key and an ended stream") {
            val owner = member()
            val room = room(owner)
            val perBroadcast = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val persistent = destination(owner, ConferenceStreamKeyKind.PERSISTENT)
            harness {
                val dto = start(owner, room, listOf(perBroadcast, persistent), listOf(input(perBroadcast, key = "old-key-000001")))
                call(member = owner) {
                    it.setBroadcastKey(streamId = dto.id, destinationId = perBroadcast.toString(), streamKey = "new-key-000002")
                } shouldBe true
                val row = targetRow(dto.id, perBroadcast)
                box.open(
                    sealed = row[ConferenceStreamTargetTable.broadcastKeyCiphertext]!!,
                    aad = BroadcastKeys.aad(row[ConferenceStreamTargetTable.id]),
                ) shouldBe "new-key-000002"
                shouldThrow<RemoteFailure> {
                    call(
                        member = owner,
                    ) { it.setBroadcastKey(streamId = dto.id, destinationId = persistent.toString(), streamKey = "whatever-key") }
                }.name shouldBe "ConflictException"
                shouldThrow<RemoteFailure> {
                    call(
                        member = owner,
                    ) { it.setBroadcastKey(streamId = dto.id, destinationId = perBroadcast.toString(), streamKey = "bad key") }
                }.name shouldBe "ConferenceStreamBroadcastKeyRejectedException"
                call(member = owner) { it.stopStream(dto.id) }
                listOf(ConferenceStreamStatus.ENDED).forEach { _ ->
                    shouldThrow<RemoteFailure> {
                        call(
                            member = owner,
                        ) { it.setBroadcastKey(streamId = dto.id, destinationId = perBroadcast.toString(), streamKey = "late-key-00003") }
                    }.name shouldBe "ConflictException"
                }
                brokenKeyOf(dto.id, perBroadcast) shouldBe null
                // STOPPING and FAILED are refused as well
                listOf(ConferenceStreamStatus.STOPPING, ConferenceStreamStatus.FAILED).forEach { status ->
                    transaction {
                        ConferenceStreamTable.update({ ConferenceStreamTable.id eq Uuid.parse(dto.id) }) {
                            it[ConferenceStreamTable.status] =
                                status
                        }
                    }
                    shouldThrow<RemoteFailure> {
                        call(
                            member = owner,
                        ) { it.setBroadcastKey(streamId = dto.id, destinationId = perBroadcast.toString(), streamKey = "late-key-00004") }
                    }.name shouldBe "ConflictException"
                }
            }
        }

        test("setViewerUrl sets, replaces and clears the address and refuses a foreign host") {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PERSISTENT, platform = ConferenceStreamPlatform.TWITCH)
            harness {
                val dto = start(owner, room, listOf(dest))
                dto.targets.single().viewerUrl shouldBe null
                call(member = owner) {
                    it.setViewerUrl(streamId = dto.id, destinationId = dest.toString(), viewerUrl = "https://www.twitch.tv/MyChannel")
                }.viewerUrl shouldBe
                    "https://www.twitch.tv/popout/mychannel/chat"
                shouldThrow<RemoteFailure> {
                    call(
                        member = owner,
                    ) { it.setViewerUrl(streamId = dto.id, destinationId = dest.toString(), viewerUrl = "https://example.org/mychannel") }
                }.name shouldBe "ConferenceStreamViewerUrlRejectedException"
                // the stored value is unchanged by the refused call
                targetRow(dto.id, dest)[ConferenceStreamTargetTable.viewerUrl] shouldBe "https://www.twitch.tv/popout/mychannel/chat"
                call(
                    member = owner,
                ) { it.setViewerUrl(streamId = dto.id, destinationId = dest.toString(), viewerUrl = "  ") }.viewerUrl shouldBe
                    null
                call(member = owner) {
                    it.setViewerUrl(streamId = dto.id, destinationId = dest.toString(), viewerUrl = "https://www.twitch.tv/other_one")
                }.viewerUrl shouldBe
                    "https://www.twitch.tv/popout/other_one/chat"
                call(
                    member = owner,
                ) { it.setViewerUrl(streamId = dto.id, destinationId = dest.toString(), viewerUrl = null) }.viewerUrl shouldBe
                    null
            }
        }

        // ── who may do what ─────────────────────────────────────────────

        test(
            "only a moderator of the room may start with inputs, set a key or set a viewer address (member, guest, moderator of another room)",
        ) {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val plainMember = member()
            val guest = member(status = MemberStatus.GUEST)
            val otherModerator = member()
            room(otherModerator)
            harness {
                listOf(plainMember, guest, otherModerator).forEach { stranger ->
                    shouldThrow<RemoteFailure> {
                        start(
                            stranger,
                            room,
                            listOf(dest),
                            listOf(input(dest, key = "stranger-key-1", viewerUrl = "https://example.org/x")),
                        )
                    }.name shouldBe "ForbiddenException"
                }
                val dto = start(owner, room, listOf(dest), listOf(input(dest, key = "owner-key-0001")))
                listOf(plainMember, guest, otherModerator).forEach { stranger ->
                    shouldThrow<RemoteFailure> {
                        call(
                            member = stranger,
                        ) { it.setBroadcastKey(streamId = dto.id, destinationId = dest.toString(), streamKey = "stranger-key-2") }
                    }.name shouldBe "ForbiddenException"
                    shouldThrow<RemoteFailure> {
                        call(
                            member = stranger,
                        ) { it.setViewerUrl(streamId = dto.id, destinationId = dest.toString(), viewerUrl = "https://example.org/x") }
                    }.name shouldBe "ForbiddenException"
                }
                // BOARD/ADMIN may
                call(member = Uuid.parse(BK_ADMIN_ID)) {
                    it.setBroadcastKey(streamId = dto.id, destinationId = dest.toString(), streamKey = "admin-key-00003")
                } shouldBe true
                // an unauthenticated call is refused
                shouldThrow<RemoteFailure> {
                    callAs(memberHeader = "not-a-member") {
                        it.setBroadcastKey(streamId = dto.id, destinationId = dest.toString(), streamKey = "x-key-00004")
                    }
                }
            }
        }

        test(
            "the rate limit of setBroadcastKey/setViewerUrl is 10 per member and stream per minute, independent per stream, and uses an injected clock",
        ) {
            val owner = member()
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val dest2 = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            val now = arrayOf(Instant.parse("2026-11-04T10:00:00Z"))
            val clock =
                object : Clock {
                    override fun now(): Instant = now[0]
                }
            val limiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes, clock = clock)
            harness(inputLimiter = limiter) {
                val room1 = room(owner)
                val room2 = room(owner)
                val s1 = start(owner, room1, listOf(dest), listOf(input(dest, key = "rate-key-00001")))
                val s2 = start(owner, room2, listOf(dest2), listOf(input(dest2, key = "rate-key-00002")))
                repeat(10) { n ->
                    call(member = owner) {
                        it.setBroadcastKey(streamId = s1.id, destinationId = dest.toString(), streamKey = "rate-key-1-$n")
                    } shouldBe true
                }
                shouldThrow<RemoteFailure> {
                    call(
                        member = owner,
                    ) { it.setBroadcastKey(streamId = s1.id, destinationId = dest.toString(), streamKey = "rate-key-1-x") }
                }.name shouldBe
                    "ConflictException"
                // another stream is counted separately
                call(
                    member = owner,
                ) { it.setBroadcastKey(streamId = s2.id, destinationId = dest2.toString(), streamKey = "rate-key-2-0") } shouldBe
                    true
                // the window passes
                now[0] = now[0] + 61.seconds
                call(
                    member = owner,
                ) { it.setBroadcastKey(streamId = s1.id, destinationId = dest.toString(), streamKey = "rate-key-1-y") } shouldBe
                    true
            }
        }

        // ── what the DTOs reveal ────────────────────────────────────────

        test("getActiveStream shows the viewer address and the 'key needed' flag only to moderators") {
            val owner = member()
            val room = room(owner)
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST, platform = ConferenceStreamPlatform.YOUTUBE)
            val viewer = member()
            harness {
                val dto =
                    start(
                        owner,
                        room,
                        listOf(dest),
                        listOf(input(dest, key = "dto-key-000001", viewerUrl = "https://youtu.be/dQw4w9WgXcQ")),
                    )
                // lose the key so that the flag is on for a moderator
                transaction {
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq Uuid.parse(dto.id) }) {
                        it[broadcastKeyCiphertext] = null
                    }
                }
                val forModerator = call(member = owner) { it.getActiveStream(room.toString()) }.single().targets.single()
                forModerator.viewerUrl shouldBe "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ"
                forModerator.needsBroadcastKey shouldBe true
                val forViewer = call(member = viewer) { it.getActiveStream(room.toString()) }.single().targets.single()
                forViewer.viewerUrl shouldBe null
                forViewer.needsBroadcastKey shouldBe false
                forViewer.keyKind shouldBe ConferenceStreamKeyKind.PER_BROADCAST
            }
        }

        test("listStreamTargets reports the key kind, the hint (Facebook ingest host, X) and a missing stored key") {
            val owner = member()
            room(owner)
            val facebook =
                destination(
                    owner,
                    ConferenceStreamKeyKind.PER_BROADCAST,
                    rtmpUrl = "rtmps://live-api-s.facebook.com:443/rtmp/",
                )
            val xDest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST, platform = ConferenceStreamPlatform.X)
            val plain = destination(owner, ConferenceStreamKeyKind.PERSISTENT)
            val lost = destination(owner, ConferenceStreamKeyKind.PERSISTENT, storedKey = null)
            harness {
                val targets = call(member = owner) { it.listStreamTargets() }.associateBy { it.id }
                targets.getValue(facebook.toString()).keyHint shouldBe ConferenceStreamKeyHint.FACEBOOK
                targets.getValue(facebook.toString()).keyKind shouldBe ConferenceStreamKeyKind.PER_BROADCAST
                targets.getValue(xDest.toString()).keyHint shouldBe ConferenceStreamKeyHint.X
                targets.getValue(plain.toString()).keyHint shouldBe ConferenceStreamKeyHint.OTHER
                targets.getValue(plain.toString()).storedKeyMissing shouldBe false
                targets.getValue(lost.toString()).storedKeyMissing shouldBe true
                targets.getValue(facebook.toString()).storedKeyMissing shouldBe false
            }
        }

        test("a destination in the ADMIN list shows 'no key stored' as a null timestamp") {
            val owner = member()
            val dest = destination(owner, ConferenceStreamKeyKind.PER_BROADCAST)
            harness {
                val listed = call(member = Uuid.parse(BK_ADMIN_ID)) { it.listDestinations() }.single { it.id == dest.toString() }
                listed.keyKind shouldBe ConferenceStreamKeyKind.PER_BROADCAST
                listed.streamKeySetAt shouldBe null
                listed.streamKeyMask shouldBe "********"
            }
        }
    })

/** Runs [body] against a throwaway route that calls [ConferenceStreamingService]; every exception is reported by type (and, into [exceptionSink], by its messages). */
internal suspend fun runBkHarness(
    egress: LiveKitEgressClient = BkEgress(),
    inputLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
    exceptionSink: MutableList<String>? = null,
    body: suspend Harness.() -> Unit,
) {
    testApplication {
        var pending: (suspend (ConferenceStreamingService) -> Any?)? = null
        var result: Any? = null
        application {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    exceptionSink?.add(
                        generateSequence(cause) { it.cause }.joinToString(" | ") { "${it::class.simpleName}: ${it.message}" },
                    )
                    call.respondText(cause::class.simpleName.orEmpty(), status = HttpStatusCode.UnprocessableEntity)
                }
            }
            routing {
                post("/run") {
                    val svc =
                        ConferenceStreamingService(
                            call = call,
                            liveKitEgressClient = egress,
                            config = BK_CONFERENCE_CONFIG,
                            streamingConfig = BK_STREAMING_CONFIG,
                            broadcastInputRateLimiter = inputLimiter,
                        )
                    result = pending!!.invoke(svc)
                    call.respondText("OK")
                }
            }
        }
        Harness(client = client, setPending = { pending = it }, takeResult = { result }).body()
    }
}

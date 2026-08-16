package network.lapis.cloud.server.conference

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
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
import network.lapis.cloud.server.rpc.ConferenceStreamingService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Deterministic 32-byte test key -- never a real secret, matches [ConferenceStreamingServiceTest]'s own convention. */
private val TEST_ENCRYPTION_KEY_B64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7).toByte() })

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- opt-in, env-gated
 * live-container integration test for the RTMP-composited-egress pipeline, mirroring
 * [LiveKitEgressLiveIntegrationTest]'s own skip-unless-enabled posture (see that file's KDoc for
 * why Testcontainers was deliberately not introduced for this repository) and complementing
 * [ConferenceStreamingServiceTest]'s `FakeLiveKitEgressClient`-backed hermetic coverage with the
 * real thing: a real [HttpLiveKitEgressClient] against a real `livekit/egress:v1.13.0` worker, a
 * real `bluenviron/mediamtx` (`rtmp-sink`) destination, and a real published participant (a
 * `livekit/livekit-cli` container run via `docker run`, the SAME synthetic-publisher technique
 * `deploy/local/README.adoc`'s own Wave 2 "full pipeline" live verification used manually -- see
 * that file's "Live verification results" for the exact log lines this test's own assertions
 * mirror).
 *
 * **Enable explicitly**: `LAPIS_LIVEKIT_IT=true`, with `deploy/local/docker-compose.yml` up
 * (needs `livekit`/`redis`/`egress`/`rtmp-sink` -- a Wave-1-only `up -d livekit coturn` is not
 * enough) AND a `docker` binary on `PATH` able to run `livekit/livekit-cli:latest` on the SAME
 * compose network (`lapis-conference-dev_default` by default, override via
 * `LAPIS_LIVEKIT_IT_DOCKER_NETWORK`):
 *
 * ```bash
 * docker compose -f deploy/local/docker-compose.yml up -d
 * LAPIS_LIVEKIT_IT=true ./gradlew :lapis-server:test --tests "*.LiveKitStreamEgressLiveIntegrationTest"
 * ```
 *
 * Each test builds its own [ConferenceStreamingService] wired to a REAL [HttpLiveKitEgressClient]
 * (never [FakeLiveKitEgressClient]) via the same `testApplication` + `X-Member-Id` throwaway-route
 * technique [ConferenceStreamingServiceTest] uses, and drives [StreamPoller.tick] directly rather
 * than starting its background loop -- deterministic, no `delay`-based flakiness waiting for a
 * production-cadence poll.
 */
class LiveKitStreamEgressLiveIntegrationTest :
    FunSpec({
        val liveItEnabled = System.getenv("LAPIS_LIVEKIT_IT") == "true"
        val apiUrl = System.getenv("LAPIS_LIVEKIT_API_URL") ?: "http://localhost:7880"
        val apiKey = System.getenv("LAPIS_LIVEKIT_API_KEY") ?: "devkey"
        val apiSecret = System.getenv("LAPIS_LIVEKIT_API_SECRET") ?: "lapis-dev-livekit-secret-32bytes-min!!"
        val dockerNetwork = System.getenv("LAPIS_LIVEKIT_IT_DOCKER_NETWORK") ?: "lapis-conference-dev_default"

        val adminClient: LiveKitAdminClient by lazy { HttpLiveKitAdminClient(apiUrl = apiUrl, apiKey = apiKey, apiSecret = apiSecret) }
        val egressClient: LiveKitEgressClient by lazy { HttpLiveKitEgressClient(apiUrl = apiUrl, apiKey = apiKey, apiSecret = apiSecret) }

        val enabledStreamingConfig =
            ConferenceStreamingConfig.load { key ->
                when (key) {
                    "LAPIS_STREAMING_ENABLED" -> "true"
                    "LAPIS_SECRET_ENCRYPTION_KEY" -> TEST_ENCRYPTION_KEY_B64
                    "LAPIS_STREAM_MAX_DESTINATIONS" -> "3"
                    else -> null
                }
            }
        val enabledConferenceConfig =
            ConferenceConfig.load { key ->
                when (key) {
                    "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
                    "LAPIS_LIVEKIT_API_URL" -> apiUrl
                    "LAPIS_LIVEKIT_API_KEY" -> apiKey
                    "LAPIS_LIVEKIT_API_SECRET" -> apiSecret
                    else -> null
                }
            }

        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDestinationIds = mutableListOf<Uuid>()

        beforeSpec {
            if (!liveItEnabled) return@beforeSpec
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            if (!liveItEnabled) return@afterSpec
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                val streamIds =
                    ConferenceStreamTable
                        .selectAll()
                        .filter { row -> row[ConferenceStreamTable.roomId] in createdRoomIds }
                        .map { it[ConferenceStreamTable.id] }
                streamIds.forEach { id -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq id } }
                streamIds.forEach { id -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq id } }
                if (createdDestinationIds.isNotEmpty()) {
                    ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.destinationId inList createdDestinationIds }
                    ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList createdDestinationIds }
                }
                createdRoomIds.forEach { id -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq id } }
                createdMemberIds.forEach { id -> AccountTable.deleteWhere { AccountTable.memberId eq id } }
                createdMemberIds.forEach { id -> MemberTable.deleteWhere { MemberTable.id eq id } }
            }
        }

        /** Inserts a member row directly -- mirrors [ConferenceStreamingServiceTest]'s own helper. */
        fun createTestMember(
            email: String,
            role: AccountRole = AccountRole.BOARD,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Live-IT Testmitglied"
                    it[MemberTable.email] = email
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
            createdMemberIds += id
            return id
        }

        /** Creates BOTH a `conference_room` row AND the matching real LiveKit room -- returns (roomId, livekitRoomName). */
        suspend fun createTestRoomWithLiveKitRoom(creatorId: Uuid): Pair<Uuid, String> {
            val id = Uuid.random()
            val livekitRoomName = "lc-stream-it-${Uuid.random()}"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Live-IT Stream Test"
                    it[description] = ""
                    it[ConferenceRoomTable.livekitRoomName] = livekitRoomName
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 10
                }
            }
            createdRoomIds += id
            adminClient.createRoom(name = livekitRoomName, maxParticipants = 10, emptyTimeoutSeconds = 300)
            return id to livekitRoomName
        }

        /**
         * Spawns a real, detached `livekit/livekit-cli:latest room join --publish-demo` container on
         * [dockerNetwork] -- the SAME technique `deploy/local/README.adoc`'s Wave 2 "full pipeline"
         * live verification used manually. Returns the container name for later cleanup. Bounded
         * wait via [adminClient.listParticipants] for the identity to actually show up publishing a
         * track before returning, so callers never race the container's own connect/publish handshake.
         */
        suspend fun startSyntheticPublisher(
            livekitRoomName: String,
            identity: String,
        ): String {
            val containerName = "lc-it-synth-${identity.takeLast(12)}"
            val process =
                ProcessBuilder(
                    "docker",
                    "run",
                    "--rm",
                    "-d",
                    "--network",
                    dockerNetwork,
                    "--name",
                    containerName,
                    "livekit/livekit-cli:latest",
                    "room",
                    "join",
                    "--url",
                    "ws://livekit:7880",
                    "--api-key",
                    apiKey,
                    "--api-secret",
                    apiSecret,
                    "--identity",
                    identity,
                    "--room",
                    livekitRoomName,
                    "--publish-demo",
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            check(exit == 0) { "docker run for synthetic publisher '$containerName' failed (exit $exit): $output" }
            // Bounded wait (≤10s) for the real publish to land -- observed live 2026-08-09 at ~1-2s.
            repeat(20) {
                val participant = adminClient.listParticipants(livekitRoomName).firstOrNull { it.identity == identity }
                if (participant != null && participant.tracks.isNotEmpty()) return containerName
                delay(500)
            }
            error("Synthetic publisher '$identity' never showed a published track in '$livekitRoomName' within 10s")
        }

        fun stopSyntheticPublisher(containerName: String) {
            runCatching { ProcessBuilder("docker", "rm", "-f", containerName).start().waitFor() }
        }

        /** id|streamKeyMask -- the ONLY two fields these tests need from the destination DTO. */
        suspend fun createTestDestination(
            adminId: Uuid,
            label: String,
            rtmpUrl: String,
            streamKey: String,
        ): String {
            var result = ""
            testApplication {
                application {
                    routing {
                        post("/test/create-destination") {
                            val q = call.request.queryParameters
                            val dto =
                                ConferenceStreamingService(
                                    call = call,
                                    liveKitEgressClient = egressClient,
                                    config = enabledConferenceConfig,
                                    streamingConfig = enabledStreamingConfig,
                                ).createDestination(
                                    label = q["label"]!!,
                                    platform = ConferenceStreamPlatform.valueOf(q["platform"]!!),
                                    rtmpUrl = q["rtmpUrl"]!!,
                                    streamKey = q["streamKey"]!!,
                                )
                            call.respondText("${dto.id}|${dto.streamKeyMask}")
                        }
                    }
                }
                val response =
                    client.post(
                        "/test/create-destination?label=$label&platform=GENERIC_RTMP&rtmpUrl=$rtmpUrl&streamKey=$streamKey",
                    ) { header("X-Member-Id", adminId.toString()) }
                response.status shouldBe HttpStatusCode.OK
                result = response.bodyAsText()
            }
            val id = result.substringBefore("|")
            createdDestinationIds += Uuid.parse(id)
            return result
        }

        test(
            "real StartParticipantEgress: two rtmp-sink destinations reach LIVE, matched via url_fingerprint " +
                "despite LiveKit's own key redaction + stream_results reordering; stream key is encrypted at rest " +
                "(v1: prefix, plaintext absent from BOTH the DB column and every DTO); pause stops the real egress, " +
                "resume starts a NEW one (different livekit_egress_id, restart_count=1)",
        ).config(enabled = liveItEnabled, timeout = 90.seconds) {
            val adminId = createTestMember("stream-it-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
            val (roomId, livekitRoomName) = createTestRoomWithLiveKitRoom(adminId)
            val identity = "e2e-it-${Uuid.random()}"
            val plaintextKeyOne = "it-key-one-${Uuid.random().toString().take(8)}"
            val plaintextKeyTwo = "it-key-two-${Uuid.random().toString().take(8)}"
            var publisherContainer: String? = null
            try {
                publisherContainer = startSyntheticPublisher(livekitRoomName, identity)

                val destOneResult = createTestDestination(adminId, "rtmp-sink-1", "rtmp://rtmp-sink:1935/live", plaintextKeyOne)
                val destTwoResult = createTestDestination(adminId, "rtmp-sink-2", "rtmp://rtmp-sink:1935/live", plaintextKeyTwo)
                val destOneId = destOneResult.substringBefore("|")
                val destTwoId = destTwoResult.substringBefore("|")

                // ── Encrypted-at-rest proof (direct DB read, both destination rows) ─────────────
                transaction {
                    for ((id, plaintext) in listOf(destOneId to plaintextKeyOne, destTwoId to plaintextKeyTwo)) {
                        val row =
                            ConferenceStreamDestinationTable
                                .selectAll()
                                .where { ConferenceStreamDestinationTable.id eq Uuid.parse(id) }
                                .single()
                        val ciphertext = row[ConferenceStreamDestinationTable.streamKeyCiphertext]
                        ciphertext.shouldStartWithV1()
                        ciphertext shouldNotContainPlaintext plaintext
                        // Round-trip proof: the SAME SecretBox this service uses can decrypt it back
                        // to the exact original plaintext -- not merely "looks encrypted".
                        val secretBox = SecretBox(Base64.getDecoder().decode(TEST_ENCRYPTION_KEY_B64))
                        secretBox.open(sealed = ciphertext, aad = id) shouldBe plaintext
                    }
                }

                // ── startStream -- real, synchronous StartParticipantEgress call ────────────────
                var streamId = ""
                testApplication {
                    application {
                        routing {
                            post("/test/start-stream") {
                                val dto =
                                    ConferenceStreamingService(
                                        call = call,
                                        liveKitEgressClient = egressClient,
                                        config = enabledConferenceConfig,
                                        streamingConfig = enabledStreamingConfig,
                                    ).startStream(
                                        roomId = roomId.toString(),
                                        destinationIds = listOf(destOneId, destTwoId),
                                        layout = ConferenceStreamLayout.SINGLE_PARTICIPANT,
                                        latencyMode = ConferenceStreamLatencyMode.STANDARD,
                                        participantIdentity = identity,
                                    )
                                call.respondText("${dto.id}|${dto.status}")
                            }
                        }
                    }
                    val response = client.post("/test/start-stream") { header("X-Member-Id", adminId.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.bodyAsText()
                    streamId = body.substringBefore("|")
                    // startStream calls LiveKit SYNCHRONOUSLY -- by the time this returns, the real
                    // Twirp round trip already happened (see IConferenceStreamingService KDoc).
                    body.substringAfter("|") shouldBe "LIVE"
                    // The RPC response body itself must never contain either plaintext key, under any name.
                    body shouldNotContainPlaintext plaintextKeyOne
                    body shouldNotContainPlaintext plaintextKeyTwo
                }

                // ── Poller reconciliation -- real ListEgress -> stream_results, matched via fingerprint ──
                val poller = StreamPoller(liveKitEgressClient = egressClient, streamingConfig = enabledStreamingConfig)
                var bothActive = false
                repeat(10) {
                    poller.tick()
                    val statuses =
                        transaction {
                            ConferenceStreamTargetTable
                                .selectAll()
                                .where { ConferenceStreamTargetTable.streamId eq Uuid.parse(streamId) }
                                .map { it[ConferenceStreamTargetTable.status] }
                        }
                    if (statuses.size == 2 && statuses.all { it == ConferenceStreamTargetStatus.ACTIVE }) {
                        bothActive = true
                        return@repeat
                    }
                    delay(2_000)
                }
                bothActive shouldBe true

                val firstEgressId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where { ConferenceStreamTable.id eq Uuid.parse(streamId) }
                            .single()[ConferenceStreamTable.livekitEgressId]
                    }
                firstEgressId shouldNotBe null

                // ── pauseStream -- real StopEgress, stream row -> PAUSED ────────────────────────
                testApplication {
                    application {
                        routing {
                            post("/test/pause-stream") {
                                val dto =
                                    ConferenceStreamingService(
                                        call = call,
                                        liveKitEgressClient = egressClient,
                                        config = enabledConferenceConfig,
                                        streamingConfig = enabledStreamingConfig,
                                    ).pauseStream(call.request.queryParameters["streamId"]!!)
                                call.respondText(dto.status.toString())
                            }
                        }
                    }
                    val response =
                        client.post("/test/pause-stream?streamId=$streamId") { header("X-Member-Id", adminId.toString()) }
                    response.bodyAsText() shouldBe "PAUSED"
                }
                // Real LiveKit-side proof the egress actually stopped (not merely a DB flag flip):
                // ListEgress against the room now reports the SAME egress id in a terminal status.
                var egressTerminal = false
                var lastObservedStatus = ""
                repeat(20) {
                    val info = egressClient.listEgress(livekitRoomName).firstOrNull { it.egressId == firstEgressId }
                    lastObservedStatus = info?.status ?: "<not in ListEgress>"
                    if (info == null || info.status != "EGRESS_ACTIVE") {
                        egressTerminal = true
                        return@repeat
                    }
                    delay(1_500)
                }
                withClue("last observed status for egress $firstEgressId: $lastObservedStatus") {
                    egressTerminal shouldBe true
                }

                // ── resumeStream -- real NEW StartParticipantEgress, restart_count -> 1 ────────
                testApplication {
                    application {
                        routing {
                            post("/test/resume-stream") {
                                val dto =
                                    ConferenceStreamingService(
                                        call = call,
                                        liveKitEgressClient = egressClient,
                                        config = enabledConferenceConfig,
                                        streamingConfig = enabledStreamingConfig,
                                    ).resumeStream(call.request.queryParameters["streamId"]!!)
                                call.respondText("${dto.status}|${dto.restartCount}")
                            }
                        }
                    }
                    val response =
                        client.post("/test/resume-stream?streamId=$streamId") { header("X-Member-Id", adminId.toString()) }
                    val body = response.bodyAsText()
                    body shouldBe "LIVE|1"
                }
                val secondEgressId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where { ConferenceStreamTable.id eq Uuid.parse(streamId) }
                            .single()[ConferenceStreamTable.livekitEgressId]
                    }
                secondEgressId shouldNotBe firstEgressId
                secondEgressId shouldNotBe null
            } finally {
                publisherContainer?.let { stopSyntheticPublisher(it) }
                runCatching { adminClient.deleteRoom(livekitRoomName) }
            }
        }

        test(
            "startStream to an unreachable-host destination surfaces the FIXED sanitized German failureReason " +
                "within one poll tick -- LiveKit's own raw error text (which echoes the destination hostname) " +
                "never reaches the target row or any DTO",
        ).config(enabled = liveItEnabled, timeout = 60.seconds) {
            val adminId = createTestMember("stream-it-wronghost-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
            val (roomId, livekitRoomName) = createTestRoomWithLiveKitRoom(adminId)
            val identity = "e2e-it-wronghost-${Uuid.random()}"
            var publisherContainer: String? = null
            try {
                publisherContainer = startSyntheticPublisher(livekitRoomName, identity)

                val badHost = "definitely-nonexistent-host-${Uuid.random().toString().take(8)}"
                val destResult =
                    createTestDestination(adminId, "kaputtes-ziel", "rtmp://$badHost:1935/live", "wrong-key-value")
                val destId = destResult.substringBefore("|")

                var streamId = ""
                testApplication {
                    application {
                        routing {
                            post("/test/start-stream") {
                                val dto =
                                    ConferenceStreamingService(
                                        call = call,
                                        liveKitEgressClient = egressClient,
                                        config = enabledConferenceConfig,
                                        streamingConfig = enabledStreamingConfig,
                                    ).startStream(
                                        roomId = roomId.toString(),
                                        destinationIds = listOf(destId),
                                        layout = ConferenceStreamLayout.SINGLE_PARTICIPANT,
                                        latencyMode = ConferenceStreamLatencyMode.STANDARD,
                                        participantIdentity = identity,
                                    )
                                call.respondText(dto.id)
                            }
                        }
                    }
                    val response = client.post("/test/start-stream") { header("X-Member-Id", adminId.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                    streamId = response.bodyAsText()
                }

                val poller = StreamPoller(liveKitEgressClient = egressClient, streamingConfig = enabledStreamingConfig)
                var sanitizedReason: String? = null
                // LiveKit's own async connect-failure surfaces ~12s after the call (live-verified,
                // see IConferenceStreamingService KDoc "the async gap") -- bounded at 10 x 2s polls.
                repeat(10) {
                    poller.tick()
                    val row =
                        transaction {
                            ConferenceStreamTargetTable
                                .selectAll()
                                .where { ConferenceStreamTargetTable.streamId eq Uuid.parse(streamId) }
                                .single()
                        }
                    if (row[ConferenceStreamTargetTable.status] == ConferenceStreamTargetStatus.FAILED) {
                        sanitizedReason = row[ConferenceStreamTargetTable.failureReason]
                        return@repeat
                    }
                    delay(2_000)
                }
                sanitizedReason shouldNotBe null
                // The FIXED German vocabulary, never LiveKit's own raw text.
                sanitizedReason shouldBe "Die Verbindung zum Streaming-Ziel konnte nicht hergestellt werden."
                sanitizedReason.orEmpty() shouldNotContainPlaintext badHost
                sanitizedReason.orEmpty() shouldNotContainPlaintext "resolving"
                sanitizedReason.orEmpty() shouldNotContainPlaintext "Name or service not known"
            } finally {
                publisherContainer?.let { stopSyntheticPublisher(it) }
                runCatching { adminClient.deleteRoom(livekitRoomName) }
            }
        }
    })

/** `v1:` prefix assertion -- see [SecretBox] KDoc "Versioned storage format". */
private fun String.shouldStartWithV1() {
    require(this.startsWith("v1:")) { "expected ciphertext to start with 'v1:', was: $this" }
}

/** Case-sensitive substring-absence assertion, mirroring [ConferenceStreamingServiceTest]'s own `shouldNotContainPlaintext`. */
private infix fun String.shouldNotContainPlaintext(plaintext: String) {
    require(!this.contains(plaintext)) { "expected string to NOT contain '$plaintext', but it did: $this" }
}

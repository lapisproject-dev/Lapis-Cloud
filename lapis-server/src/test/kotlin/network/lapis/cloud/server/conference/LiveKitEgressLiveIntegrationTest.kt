package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Opt-in, env-gated live-container integration test for [LiveKitEgressClient] -- same skip-unless-
 * enabled posture as [LiveKitLiveIntegrationTest] (see that file's own KDoc for why Testcontainers
 * was deliberately not introduced for this repository). Complements
 * [LiveKitEgressClientTest]'s `MockEngine`-backed hermetic coverage (which replays the exact wire
 * fixtures captured below) with two cheap, real-container checks that specifically close the
 * "roomRecord grant is actually accepted" question -- distinct from [LiveKitLiveIntegrationTest],
 * which only ever exercises [LiveKitAdminClient]'s `roomCreate`/`roomAdmin` grants, never
 * [LiveKitAccessToken.mintEgressToken]'s `roomRecord` one.
 *
 * **Enable explicitly**: `LAPIS_LIVEKIT_IT=true`, with `deploy/local/docker-compose.yml` up
 * (this spec additionally needs the `redis`/`egress` services Wave 2 added -- a Wave-1-only
 * `up -d livekit coturn` is not enough, [LiveKitEgressClient]'s Twirp calls are served by
 * `livekit-server` itself but return no useful `EgressInfo` transitions without a live `egress`
 * worker attached via Redis).
 *
 * ```bash
 * docker compose -f deploy/local/docker-compose.yml up -d
 * LAPIS_LIVEKIT_IT=true ./gradlew :lapis-server:test --tests "*.LiveKitEgressLiveIntegrationTest"
 * ```
 *
 * **One real, empirically-observed correction to this wave's own plan, recorded here rather than
 * silently adjusted**: the wave plan expected `StartTrackEgress` with a bogus/non-existent track id
 * to fail synchronously with "the expected Twirp error rather than 401". Live-verified (2026-08-09,
 * against this exact stack) that this is NOT what happens -- Track Egress is SDK-based (the egress
 * worker joins the room as a subscribing participant and WAITS for the named track to appear), so
 * `StartTrackEgress` for a track that will never exist returns a completely normal HTTP 200 with a
 * real `EgressInfo` in `EGRESS_STARTING` status, exactly like a request for a real track would. The
 * actual, and arguably STRONGER, proof that the `roomRecord` grant is accepted is therefore "the call
 * did not throw a [LiveKitAdminException] at all" (a `401`/any non-2xx response DOES throw, per
 * [HttpLiveKitEgressClient]'s own `call` helper) -- not a distinguishable error shape. The bogus
 * egress DOES eventually fail on its own (observed: ~30 seconds later, `EGRESS_FAILED` with
 * `error: "track ... not found"`, `error_code: 404`) once the egress worker's own subscribe-timeout
 * elapses -- this test's second half polls for exactly that, which incidentally closes
 * [LiveKitEgressInfo] KDoc's own long-standing "`EGRESS_FAILED`/... remain UNVERIFIED ... until a
 * future wave actually observes one" gap for the `EGRESS_FAILED` literal specifically.
 */
class LiveKitEgressLiveIntegrationTest :
    FunSpec({
        val liveItEnabled = System.getenv("LAPIS_LIVEKIT_IT") == "true"
        val apiUrl = System.getenv("LAPIS_LIVEKIT_API_URL") ?: "http://localhost:7880"
        val apiKey = System.getenv("LAPIS_LIVEKIT_API_KEY") ?: "devkey"
        val apiSecret = System.getenv("LAPIS_LIVEKIT_API_SECRET") ?: "lapis-dev-livekit-secret-32bytes-min!!"

        val adminClient: LiveKitAdminClient by lazy { HttpLiveKitAdminClient(apiUrl = apiUrl, apiKey = apiKey, apiSecret = apiSecret) }
        val egressClient: LiveKitEgressClient by lazy { HttpLiveKitEgressClient(apiUrl = apiUrl, apiKey = apiKey, apiSecret = apiSecret) }

        test("ListEgress on a fresh room with no egresses returns an empty list").config(enabled = liveItEnabled) {
            // Same server-generated room-naming scheme as LiveKitLiveIntegrationTest -- never derived
            // from user-supplied text.
            val roomName = "lc-egress-it-${Uuid.random()}"
            adminClient.createRoom(name = roomName, maxParticipants = 5, emptyTimeoutSeconds = 60)
            try {
                // Proves the roomRecord-scoped ListEgress call itself round-trips against the real
                // server (not just that a room with no egresses happens to have none) -- if the
                // roomRecord grant were rejected, this would throw LiveKitAdminException("... HTTP
                // 401 ...") rather than returning cleanly.
                egressClient.listEgress(roomName).shouldBeEmpty()
            } finally {
                adminClient.deleteRoom(roomName)
            }
        }

        test(
            "StartTrackEgress for a non-existent track id is ACCEPTED (roomRecord grant honoured, not a 401) " +
                "and the resulting egress eventually reports EGRESS_FAILED",
        ).config(enabled = liveItEnabled, timeout = 90.seconds) {
            val roomName = "lc-egress-it-${Uuid.random()}"
            // emptyTimeoutSeconds generously above this test's own ~85s worst-case runtime (see the
            // 16 x 5s poll below) -- an empty room that outlives its own emptyTimeout gets deleted by
            // LiveKit ON ITS OWN, independent of an egress running against it; observed live
            // (2026-08-09) as a real `DeleteRoom` 404 in this test's own cleanup when
            // emptyTimeoutSeconds was originally 60. Room delete below is therefore best-effort too,
            // not just the StopEgress cleanup.
            adminClient.createRoom(name = roomName, maxParticipants = 5, emptyTimeoutSeconds = 300)
            val bogusTrackId = "TR_doesnotexist${Uuid.random().toString().take(8)}"
            var startedEgressId: String? = null
            try {
                // The cheap, hermetic-adjacent proof itself: a rejected/mis-scoped roomRecord grant
                // throws LiveKitAdminException here (see HttpLiveKitEgressClient's call() helper --
                // any non-2xx response, 401 included, is mapped to that exception). A 200 with a real
                // EgressInfo, even for a track that will never exist, IS the proof this test needs.
                lateinit var started: LiveKitEgressInfo
                shouldNotThrowAny {
                    started =
                        egressClient.startTrackEgress(
                            roomName = roomName,
                            trackId = bogusTrackId,
                            outputFilepathWithoutExtension = "/out/live-it-probe/bogus",
                        )
                }
                started.egressId shouldStartWith "EG_"
                started.status shouldBe "EGRESS_STARTING"
                startedEgressId = started.egressId

                // Second half -- poll ListEgress until the egress worker's own subscribe-timeout
                // gives up on a track that will never be published (observed ~30s against this exact
                // stack, 2026-08-09). Bounded at 80s total (16 x 5s) so a stalled/misbehaving egress
                // worker fails this test loudly rather than hanging the whole opt-in suite.
                var finalStatus = started.status
                var finalError = ""
                repeat(16) {
                    delay(5_000)
                    val info = egressClient.listEgress(roomName).firstOrNull { it.egressId == started.egressId }
                    if (info != null) {
                        finalStatus = info.status
                        finalError = info.error
                    }
                    if (finalStatus != "EGRESS_STARTING" && finalStatus != "EGRESS_ACTIVE") return@repeat
                }
                // See class KDoc "One real, empirically-observed correction" -- this closes
                // LiveKitEgressInfo's own "EGRESS_FAILED ... remains UNVERIFIED" disclosure.
                finalStatus shouldBe "EGRESS_FAILED"
                finalError shouldContain "not found"
            } finally {
                // Best-effort cleanup -- StopEgress on an already-terminal (or still-starting)
                // egress is documented as idempotent (see LiveKitEgressClient.stopEgress KDoc), so a
                // plain try/catch swallow here is enough; the room delete below is the real cleanup.
                try {
                    startedEgressId?.let { egressClient.stopEgress(roomName = roomName, egressId = it) }
                } catch (_: Exception) {
                    // Ignored -- best-effort only, see comment above.
                }
                // Also best-effort -- see the emptyTimeoutSeconds comment above for why a 404 here
                // is an expected, benign outcome (LiveKit already reaped the empty room itself), not
                // a real cleanup failure.
                try {
                    adminClient.deleteRoom(roomName)
                } catch (_: Exception) {
                    // Ignored -- best-effort only, see comment above.
                }
            }
        }
    })

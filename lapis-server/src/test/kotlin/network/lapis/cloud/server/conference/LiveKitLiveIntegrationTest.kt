package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.uuid.Uuid

/**
 * Opt-in, env-gated live-container integration test -- **not** part of the hermetic suite
 * `./gradlew clean check` runs, and deliberately so: the Wave 1 plan's `testPlan` explicitly
 * decided AGAINST introducing Testcontainers (verified absent from this repo before this wave;
 * CI runs a bare `./gradlew clean check` on `ubuntu-latest` with no services, and the existing
 * ~1300-test suite is hermetic by design -- adding a Docker dependency to CI for one wave's five
 * Twirp calls would be a disproportionate trade). Every other Wave 1 test
 * ([ConferenceConfigTest], [LiveKitAccessTokenTest], [LiveKitAdminClientTest], `ConferenceServiceTest`)
 * already covers token shape, wire format (against the REAL verified fixtures captured from this
 * exact container, see [HttpLiveKitAdminClient] KDoc), and the full authorization matrix hermetically
 * via `ktor-client-mock`/a fake [LiveKitAdminClient] -- this spec exists only to prove, on demand,
 * that [HttpLiveKitAdminClient] actually round-trips against a REAL running LiveKit server, not just
 * against fixtures a human transcribed from one.
 *
 * **Enable explicitly**: `LAPIS_LIVEKIT_IT=true`, with `deploy/local/docker-compose.yml` up
 * (`docker compose -f deploy/local/docker-compose.yml up -d`). Every other environment --
 * unset/anything else -- makes every test in this spec a no-op (Kotest `enabled = false`, reported
 * as skipped, never failed), so this file is safely committed and safely run in CI/on machines
 * without Docker.
 *
 * ```bash
 * docker compose -f deploy/local/docker-compose.yml up -d
 * LAPIS_LIVEKIT_IT=true ./gradlew :lapis-server:test --tests "*.LiveKitLiveIntegrationTest"
 * ```
 *
 * API URL/key/secret default to `deploy/local/livekit.yaml`'s own dev values (same values
 * [ConferenceConfig]'s own defaults document) but can be overridden via the same
 * `LAPIS_LIVEKIT_API_URL`/`_API_KEY`/`_API_SECRET` env vars [ConferenceConfig.load] reads, in case a
 * future CI job ever points this at a differently-configured container.
 */
class LiveKitLiveIntegrationTest :
    FunSpec({
        val liveItEnabled = System.getenv("LAPIS_LIVEKIT_IT") == "true"
        val apiUrl = System.getenv("LAPIS_LIVEKIT_API_URL") ?: "http://localhost:7880"
        val apiKey = System.getenv("LAPIS_LIVEKIT_API_KEY") ?: "devkey"
        val apiSecret = System.getenv("LAPIS_LIVEKIT_API_SECRET") ?: "lapis-dev-livekit-secret-32bytes-min!!"

        val client: LiveKitAdminClient by lazy { HttpLiveKitAdminClient(apiUrl = apiUrl, apiKey = apiKey, apiSecret = apiSecret) }

        test(
            "CreateRoom -> ListRooms -> DeleteRoom -> ListRooms round trip against a real running LiveKit container",
        ).config(enabled = liveItEnabled) {
            // lc-<uuid4>, same server-generated naming scheme ConferenceService uses for real
            // rooms (see IConferenceService RPC contract "ROOM NAME") -- never derived from
            // user-supplied text, and collision-proof enough that repeated local runs never clash.
            val roomName = "lc-it-${Uuid.random()}"

            // Defensive: a previous failed run of this exact spec could in principle have left the
            // room behind (test crashed between createRoom and deleteRoom). Vanishingly unlikely
            // given the fresh UUID above, but cheap to assert and documents the intent.
            client.listRooms().none { it.name == roomName } shouldBe true

            try {
                val created = client.createRoom(name = roomName, maxParticipants = 5, emptyTimeoutSeconds = 60)
                created.name shouldBe roomName
                created.maxParticipants shouldBe 5
                created.sid shouldStartWith "RM_"
                created.numParticipants shouldBe 0

                val afterCreate = client.listRooms().filter { it.name == roomName }
                afterCreate shouldHaveSize 1
                afterCreate.single().sid shouldBe created.sid

                // No live WebRTC client ever connects in this spec (that is the two-browser manual
                // verification's job, not a hermetic-adjacent JVM test's) -- an empty roster is the
                // correct, expected result here, and itself proves ListParticipants' room-scoped
                // admin token (see LiveKitAccessToken KDoc "empirically verified") is accepted by
                // the real server, not just by LiveKitAdminClientTest's MockEngine fixture.
                client.listParticipants(roomName).shouldBeEmpty()
            } finally {
                // Always attempt cleanup, even if an assertion above failed mid-way -- this spec
                // runs against a shared local dev container a developer may reuse across many runs.
                client.deleteRoom(roomName)
            }

            client.listRooms().none { it.name == roomName } shouldBe true
        }
    })

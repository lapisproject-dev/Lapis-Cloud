package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_TOPIC
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_TOPIC
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import network.lapis.cloud.shared.domain.ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES
import java.util.UUID

private const val ROOM = "lc-11111111-1111-1111-1111-111111111111"

/**
 * V1.9.133 -- one `SendData` size limit per topic. Regression guard for V1.9.127: the raised-hand packet (about 47 bytes even for an empty
 * queue) used to hit the 16-byte encounter limit and fail with an `IllegalArgumentException` in production (the tests used a fake client).
 */
class LiveKitAdminClientPayloadLimitTest :
    FunSpec({
        test("each topic has its own limit; every other topic keeps the 16-byte encounter limit") {
            sendDataPayloadLimit(CONFERENCE_MODERATION_TOPIC) shouldBe CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES
            sendDataPayloadLimit(CONFERENCE_HANDS_TOPIC) shouldBe CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
            sendDataPayloadLimit(CONFERENCE_HANDS_TOPIC) shouldBe 6144
            sendDataPayloadLimit(CONFERENCE_TIMER_TOPIC) shouldBe CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
            sendDataPayloadLimit(CONFERENCE_TIMER_TOPIC) shouldBe 256
            sendDataPayloadLimit("lapis-encounter-blessing") shouldBe ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES
            sendDataPayloadLimit("something-else") shouldBe 16
        }

        fun admin(requests: MutableList<HttpRequestData>) =
            HttpLiveKitAdminClient(
                apiUrl = "http://localhost:7880",
                apiKey = "devkey",
                apiSecret = "lapis-dev-livekit-secret-32bytes-min!!",
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            requests += request
                            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { expectSuccess = false },
            )

        test("the real client sends a raised-hand packet (regression V1.9.127) and a timer packet, to everybody and point to point") {
            val requests = mutableListOf<HttpRequestData>()
            val client = admin(requests)
            val handsPayload =
                ConferenceHandsPackets.snapshot(
                    ConferenceHandQueueState.Snapshot(
                        epoch = "0123456789abcdef",
                        seq = 1,
                        identities = listOf(UUID.randomUUID().toString()),
                    ),
                )
            (handsPayload.size > 16) shouldBe true
            client.sendData(room = ROOM, topic = CONFERENCE_HANDS_TOPIC, payload = handsPayload)
            client.sendDataTo(room = ROOM, topic = CONFERENCE_HANDS_TOPIC, payload = handsPayload, destinationIdentities = listOf("a"))
            val timerPayload =
                ConferenceTimerPackets.snapshot(
                    ConferenceTimerState.Snapshot(
                        epoch = "0123456789abcdef",
                        seq = 1,
                        status = ConferenceTimerStatus.RUNNING,
                        initialDurationMs = 60_000,
                        remainingMs = 60_000,
                        serverNowEpochMs = 1_760_000_000_000,
                    ),
                )
            (timerPayload.size > 16) shouldBe true
            client.sendData(room = ROOM, topic = CONFERENCE_TIMER_TOPIC, payload = timerPayload)
            client.sendDataTo(room = ROOM, topic = CONFERENCE_TIMER_TOPIC, payload = timerPayload, destinationIdentities = listOf("a"))
            requests.size shouldBe 4
            (requests[2].body as TextContent).text shouldContain "\"topic\":\"$CONFERENCE_TIMER_TOPIC\""
        }

        test("a timer packet over 256 bytes and a payload over the topic limit are refused before any request") {
            val requests = mutableListOf<HttpRequestData>()
            val client = admin(requests)
            shouldThrow<IllegalArgumentException> { client.sendData(room = ROOM, topic = CONFERENCE_TIMER_TOPIC, payload = ByteArray(257)) }
            shouldThrow<IllegalArgumentException> {
                client.sendDataTo(
                    room = ROOM,
                    topic = CONFERENCE_TIMER_TOPIC,
                    payload = ByteArray(257),
                    destinationIdentities = listOf("a"),
                )
            }
            shouldThrow<IllegalArgumentException> {
                client.sendData(
                    room = ROOM,
                    topic = CONFERENCE_HANDS_TOPIC,
                    payload = ByteArray(6145),
                )
            }
            shouldThrow<IllegalArgumentException> { client.sendData(room = ROOM, topic = "other", payload = ByteArray(17)) }
            requests.size shouldBe 0
        }
    })

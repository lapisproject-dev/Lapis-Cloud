package network.lapis.cloud.server.conference

import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException

private const val API_KEY = "devkey"
private const val API_SECRET = "lapis-dev-livekit-secret-32bytes-min!!"
private const val API_URL = "http://localhost:7880"
private const val ROOM_NAME = "lc-egress-probe"
private const val TRACK_ID = "TR_VCWo2vnDTWBCuu"
private const val EGRESS_ID = "EG_WfWZUA9KKHxJ"

/** Real, verified sample captured against a live LiveKit v1.13.5 + egress v1.13.0 instance, 2026-08-09 -- see [HttpLiveKitEgressClient] KDoc and [LiveKitEgressInfo] KDoc for the full capture story. Trimmed to the fields this codebase reads plus enough surrounding shape to prove `ignoreUnknownKeys` tolerance. */
private val EGRESS_INFO_JSON =
    """
    {"egress_id":"$EGRESS_ID","room_id":"RM_Ed69chmBzgbN","room_name":"$ROOM_NAME",
     "source_type":"EGRESS_SOURCE_TYPE_SDK","status":"EGRESS_COMPLETE",
     "started_at":"1786260219527104099","ended_at":"1786260242312546575",
     "updated_at":"1786260242312546575",
     "track":{"room_name":"$ROOM_NAME","track_id":"$TRACK_ID",
       "file":{"filepath":"/out/probe-test/egress-probe-publisher__CAMERA__$TRACK_ID","disable_manifest":true},
       "webhooks":[]},
     "file_results":[{"filename":"/out/probe-test/egress-probe-publisher__CAMERA__$TRACK_ID.mp4",
       "started_at":"1786260219805661967","ended_at":"1786260242301779335",
       "duration":"22496117368","size":"3810442","location":"/out/probe-test/....mp4"}],
     "error":"","error_code":0,"details":"End reason: StopEgress API"}
    """.trimIndent()

/**
 * Exercises [HttpLiveKitEgressClient] against a [MockEngine]-backed [HttpClient] -- never the real
 * LiveKit API, same house rule [LiveKitAdminClientTest] establishes for its own [LiveKitAdminClient]
 * coverage. Wire-shape fixtures above are the ACTUAL verified responses from a live
 * `deploy/local/docker-compose.yml` stack (see [HttpLiveKitEgressClient] KDoc), captured via
 * `livekit-cli`'s `room join --publish-demo` to get a real published track, not guesses.
 */
class LiveKitEgressClientTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
            HttpClient(MockEngine(handler)) {
                expectSuccess = false
            }

        fun bodyText(request: HttpRequestData): String = (request.body as TextContent).text

        fun bearerToken(request: HttpRequestData): String = request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")

        fun MockRequestHandleScope.jsonResponse(
            body: String,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

        test("startTrackEgress POSTs the correct Twirp path/body (NO file extension) and parses the verified response shape") {
            var capturedUrl = ""
            var capturedBody = ""
            var capturedToken = ""
            val client =
                mockClient { request ->
                    capturedUrl = request.url.toString()
                    capturedBody = bodyText(request)
                    capturedToken = bearerToken(request)
                    jsonResponse(EGRESS_INFO_JSON)
                }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val info =
                egress.startTrackEgress(
                    roomName = ROOM_NAME,
                    trackId = TRACK_ID,
                    outputFilepathWithoutExtension = "/out/probe-test/egress-probe-publisher__CAMERA__$TRACK_ID",
                )

            capturedUrl shouldBe "$API_URL/twirp/livekit.Egress/StartTrackEgress"
            capturedBody shouldContain "\"room_name\":\"$ROOM_NAME\""
            capturedBody shouldContain "\"track_id\":\"$TRACK_ID\""
            capturedBody shouldContain "\"filepath\":\"/out/probe-test/egress-probe-publisher__CAMERA__$TRACK_ID\""
            capturedBody shouldContain "\"disable_manifest\":true"
            // The filepath in the OUTBOUND request must never carry an extension -- Track Egress
            // picks it based on the track's own codec, see HttpLiveKitEgressClient KDoc.
            capturedBody shouldNotContain ".mp4"

            info.egressId shouldBe EGRESS_ID
            info.roomName shouldBe ROOM_NAME
            info.status shouldBe "EGRESS_COMPLETE"
            info.startedAtEpochNanos shouldBe "1786260219527104099"
            info.endedAtEpochNanos shouldBe "1786260242312546575"
            info.fileResults shouldHaveSize 1
            info.firstFileResult?.filename shouldBe "/out/probe-test/egress-probe-publisher__CAMERA__$TRACK_ID.mp4"
            info.firstFileResult?.duration shouldBe "22496117368"
            info.firstFileResult?.size shouldBe "3810442"

            // The egress token must be a well-formed, verifiable HS256 JWS with EXACTLY the
            // roomRecord grant -- never contains the raw apiSecret as a substring.
            val signed = SignedJWT.parse(capturedToken)
            signed.jwtClaimsSet.issuer shouldBe API_KEY
            val grant = signed.jwtClaimsSet.getJSONObjectClaim("video")
            grant["roomRecord"] shouldBe true
            grant["room"] shouldBe ROOM_NAME
            grant.containsKey("roomJoin") shouldBe false
            grant.containsKey("roomAdmin") shouldBe false
            capturedToken.shouldNotContain(API_SECRET)
        }

        test(
            "firstFileResult is null when the file_results filename is still blank (immediately after StartTrackEgress, before any bytes)",
        ) {
            val startingJson =
                """
                {"egress_id":"$EGRESS_ID","room_name":"$ROOM_NAME","status":"EGRESS_STARTING",
                 "started_at":"1786260219510476124","ended_at":"0",
                 "file_results":[{"filename":"","started_at":"0","ended_at":"0","duration":"0","size":"0"}],
                 "error":""}
                """.trimIndent()
            val client = mockClient { _ -> jsonResponse(startingJson) }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val info = egress.startTrackEgress(ROOM_NAME, TRACK_ID, "/out/x/y")

            info.status shouldBe "EGRESS_STARTING"
            info.firstFileResult shouldBe null
        }

        test("stopEgress POSTs {\"egress_id\":...} to StopEgress, scoped to the given room") {
            var capturedUrl = ""
            var capturedBody = ""
            var capturedToken = ""
            val client =
                mockClient { request ->
                    capturedUrl = request.url.toString()
                    capturedBody = bodyText(request)
                    capturedToken = bearerToken(request)
                    jsonResponse(EGRESS_INFO_JSON)
                }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val info = egress.stopEgress(roomName = ROOM_NAME, egressId = EGRESS_ID)

            capturedUrl shouldBe "$API_URL/twirp/livekit.Egress/StopEgress"
            capturedBody shouldBe """{"egress_id":"$EGRESS_ID"}"""
            info.egressId shouldBe EGRESS_ID
            val grant = SignedJWT.parse(capturedToken).jwtClaimsSet.getJSONObjectClaim("video")
            grant["room"] shouldBe ROOM_NAME
            grant["roomRecord"] shouldBe true
        }

        test("listEgress POSTs {\"room_name\":...} to ListEgress and parses the real 'items' array field name") {
            var capturedUrl = ""
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedUrl = request.url.toString()
                    capturedBody = bodyText(request)
                    jsonResponse("""{"items":[$EGRESS_INFO_JSON],"next_page_token":null}""")
                }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val items = egress.listEgress(ROOM_NAME)

            capturedUrl shouldBe "$API_URL/twirp/livekit.Egress/ListEgress"
            capturedBody shouldBe """{"room_name":"$ROOM_NAME"}"""
            items shouldHaveSize 1
            items.single().egressId shouldBe EGRESS_ID
        }

        test("listEgress on a room with no egresses returns an empty list, not an error") {
            val client = mockClient { _ -> jsonResponse("""{"items":[],"next_page_token":null}""") }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            egress.listEgress(ROOM_NAME).shouldBeEmpty()
        }

        test("a non-2xx HTTP status maps to LiveKitAdminException naming the status code, without leaking the apiSecret") {
            val client = mockClient { _ -> respondError(HttpStatusCode.Unauthorized, "invalid authorization token") }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val exception = shouldThrow<LiveKitAdminException> { egress.listEgress(ROOM_NAME) }

            exception.message shouldContain "401"
            exception.message?.shouldNotContain(API_SECRET)
        }

        test("a network-level exception maps to LiveKitAdminException, no raw exception propagates, apiSecret never leaks") {
            val client = HttpClient(MockEngine { _ -> throw IOException("connection reset") }) { expectSuccess = false }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val exception = shouldThrow<LiveKitAdminException> { egress.listEgress(ROOM_NAME) }

            exception.message?.shouldNotContain(API_SECRET)
            exception.message?.shouldNotContain("connection reset")
        }

        test("a 200 response with an unparseable body maps to LiveKitAdminException, no exception propagates") {
            val client = mockClient { _ -> jsonResponse("not json at all {{{") }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            shouldThrow<LiveKitAdminException> { egress.listEgress(ROOM_NAME) }
        }

        test("a response body exceeding MAX_LIVEKIT_RESPONSE_BYTES maps to LiveKitAdminException, body never fully buffered into a DTO") {
            val oversized = "{\"items\":[" + "1".repeat(MAX_LIVEKIT_RESPONSE_BYTES + 1024) + "]}"
            val client = mockClient { _ -> jsonResponse(oversized) }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val exception = shouldThrow<LiveKitAdminException> { egress.listEgress(ROOM_NAME) }
            exception.message shouldContain "exceeded"
        }

        test("every call mints a fresh egress token (different jti across two calls)") {
            val tokens = mutableListOf<String>()
            val client =
                mockClient { request ->
                    tokens.add(bearerToken(request))
                    jsonResponse("""{"items":[]}""")
                }
            val egress = HttpLiveKitEgressClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            egress.listEgress(ROOM_NAME)
            egress.listEgress(ROOM_NAME)

            tokens shouldHaveSize 2
            val firstJti = SignedJWT.parse(tokens[0]).jwtClaimsSet.jwtid
            val secondJti = SignedJWT.parse(tokens[1]).jwtClaimsSet.jwtid
            (firstJti == secondJti) shouldBe false
        }
    })

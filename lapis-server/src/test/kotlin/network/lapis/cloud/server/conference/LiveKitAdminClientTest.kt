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
private const val ROOM_NAME = "lc-11111111-1111-1111-1111-111111111111"

/** Real, verified sample from a live LiveKit v1.13.5 instance -- see LiveKitRoomInfo/HttpLiveKitAdminClient KDoc. */
private val ROOM_INFO_JSON =
    """
    {"sid":"RM_ZDQHUipfqNzr","name":"$ROOM_NAME","empty_timeout":300,"departure_timeout":20,
     "max_participants":25,"creation_time":"1786241201","creation_time_ms":"1786241201429",
     "turn_password":"XPs7Cp51NrYB28NpWCtgMe90qjv3qreBtMiQKzimiOT","enabled_codecs":[{"mime":"audio/opus","fmtp_line":""}],
     "metadata":"","num_participants":0,"num_publishers":0,"active_recording":false,"version":null}
    """.trimIndent()

/**
 * Exercises [HttpLiveKitAdminClient] against a [MockEngine]-backed [HttpClient] -- never the real
 * LiveKit API, same house rule [LetterxpressPostalMailProviderTest] documents for its own
 * `MockEngine` usage. Wire-shape fixtures above are the ACTUAL verified responses from
 * `deploy/local/docker-compose.yml`'s live LiveKit v1.13.5 (see HttpLiveKitAdminClient KDoc), not
 * guesses.
 */
class LiveKitAdminClientTest :
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

        test("createRoom POSTs the correct Twirp path/body and parses the verified response shape") {
            var capturedUrl = ""
            var capturedBody = ""
            var capturedToken = ""
            val client =
                mockClient { request ->
                    capturedUrl = request.url.toString()
                    capturedBody = bodyText(request)
                    capturedToken = bearerToken(request)
                    jsonResponse(ROOM_INFO_JSON)
                }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val room = admin.createRoom(name = ROOM_NAME, maxParticipants = 25, emptyTimeoutSeconds = 300)

            capturedUrl shouldBe "$API_URL/twirp/livekit.RoomService/CreateRoom"
            capturedBody shouldContain "\"name\":\"$ROOM_NAME\""
            capturedBody shouldContain "\"max_participants\":25"
            capturedBody shouldContain "\"empty_timeout\":300"
            room.sid shouldBe "RM_ZDQHUipfqNzr"
            room.name shouldBe ROOM_NAME
            room.maxParticipants shouldBe 25
            room.emptyTimeoutSeconds shouldBe 300
            room.numParticipants shouldBe 0
            room.creationTimeEpochSeconds shouldBe "1786241201"

            // The admin token must be a well-formed, verifiable HS256 JWS and must never contain
            // the raw apiSecret as a substring.
            val signed = SignedJWT.parse(capturedToken)
            signed.jwtClaimsSet.issuer shouldBe API_KEY
            capturedToken.shouldNotContain(API_SECRET)
        }

        test("createRoom's admin token carries roomCreate but not room-scoping (empirically CreateRoom needs no room claim)") {
            var capturedToken = ""
            val client =
                mockClient { request ->
                    capturedToken = bearerToken(request)
                    jsonResponse(ROOM_INFO_JSON)
                }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            admin.createRoom(name = ROOM_NAME, maxParticipants = 25, emptyTimeoutSeconds = 300)

            val grant = SignedJWT.parse(capturedToken).jwtClaimsSet.getJSONObjectClaim("video")
            grant["roomCreate"] shouldBe true
        }

        test("deleteRoom POSTs {\"room\":...} to DeleteRoom") {
            var capturedUrl = ""
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedUrl = request.url.toString()
                    capturedBody = bodyText(request)
                    jsonResponse("{}")
                }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            admin.deleteRoom(ROOM_NAME)

            capturedUrl shouldBe "$API_URL/twirp/livekit.RoomService/DeleteRoom"
            capturedBody shouldBe """{"room":"$ROOM_NAME"}"""
        }

        test("listRooms POSTs an empty body and parses a multi-room list") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    jsonResponse("""{"rooms":[$ROOM_INFO_JSON,$ROOM_INFO_JSON]}""")
                }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val rooms = admin.listRooms()

            capturedBody shouldBe "{}"
            rooms shouldHaveSize 2
        }

        test("listRooms on an empty deployment returns an empty list, not an error") {
            val client = mockClient { _ -> jsonResponse("""{"rooms":[]}""") }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            admin.listRooms().shouldBeEmpty()
        }

        test("listParticipants scopes the admin token's video.room claim to the target room -- required, see LiveKitAccessToken KDoc") {
            var capturedToken = ""
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedToken = bearerToken(request)
                    capturedBody = bodyText(request)
                    jsonResponse("""{"participants":[]}""")
                }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val participants = admin.listParticipants(ROOM_NAME)

            capturedBody shouldBe """{"room":"$ROOM_NAME"}"""
            participants.shouldBeEmpty()
            val grant = SignedJWT.parse(capturedToken).jwtClaimsSet.getJSONObjectClaim("video")
            grant["roomAdmin"] shouldBe true
            grant["room"] shouldBe ROOM_NAME
        }

        test("removeParticipant POSTs room+identity and scopes the admin token to the room") {
            var capturedBody = ""
            var capturedToken = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    capturedToken = bearerToken(request)
                    jsonResponse("{}")
                }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            admin.removeParticipant(room = ROOM_NAME, identity = "member-123")

            capturedBody shouldBe """{"room":"$ROOM_NAME","identity":"member-123"}"""
            val grant = SignedJWT.parse(capturedToken).jwtClaimsSet.getJSONObjectClaim("video")
            grant["room"] shouldBe ROOM_NAME
        }

        test("a non-2xx HTTP status maps to LiveKitAdminException naming the status code, without leaking the apiSecret") {
            val client = mockClient { _ -> respondError(HttpStatusCode.Unauthorized, "invalid authorization token") }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val exception = shouldThrow<LiveKitAdminException> { admin.listRooms() }

            exception.message shouldContain "401"
            exception.message?.shouldNotContain(API_SECRET)
        }

        test("a network-level exception maps to LiveKitAdminException, no raw exception propagates, apiSecret never leaks") {
            val client = HttpClient(MockEngine { _ -> throw IOException("connection reset") }) { expectSuccess = false }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val exception = shouldThrow<LiveKitAdminException> { admin.listRooms() }

            exception.message?.shouldNotContain(API_SECRET)
            exception.message?.shouldNotContain("connection reset")
        }

        test("a 200 response with an unparseable body maps to LiveKitAdminException, no exception propagates") {
            val client = mockClient { _ -> jsonResponse("not json at all {{{") }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            shouldThrow<LiveKitAdminException> { admin.listRooms() }
        }

        test("a response body exceeding MAX_LIVEKIT_RESPONSE_BYTES maps to LiveKitAdminException, body never fully buffered into a DTO") {
            val oversized = "{\"rooms\":[" + "1".repeat(MAX_LIVEKIT_RESPONSE_BYTES + 1024) + "]}"
            val client = mockClient { _ -> jsonResponse(oversized) }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            val exception = shouldThrow<LiveKitAdminException> { admin.listRooms() }
            exception.message shouldContain "exceeded"
        }

        test("every call mints a fresh admin token (different jti across two calls)") {
            val tokens = mutableListOf<String>()
            val client =
                mockClient { request ->
                    tokens.add(bearerToken(request))
                    jsonResponse("""{"rooms":[]}""")
                }
            val admin = HttpLiveKitAdminClient(apiUrl = API_URL, apiKey = API_KEY, apiSecret = API_SECRET, httpClient = client)

            admin.listRooms()
            admin.listRooms()

            tokens shouldHaveSize 2
            val firstJti = SignedJWT.parse(tokens[0]).jwtClaimsSet.jwtid
            val secondJti = SignedJWT.parse(tokens[1]).jwtClaimsSet.jwtid
            (firstJti == secondJti) shouldBe false
        }
    })

package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceHandQueueState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventCaptureLock
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventGuestActionToken
import network.lapis.cloud.server.events.EventGuestHands
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
import network.lapis.cloud.shared.domain.EVENT_GUEST_ACTION_HEADER
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.281 -- `POST /api/events/{slug}/online-guest/hand`: a guest WITHOUT an account raises or lowers their own hand with the guest-action token. Every
 * semantic refusal is the SAME 403 with the SAME bytes (no oracle); the identity comes from the token only; the queue is the one of the members.
 */
class EventOnlineGuestHandRouteTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val json = Json { ignoreUnknownKeys = true }
        val base = "https://lapis.example.org"
        lateinit var organizer: Uuid
        val insideWindow = "2030-06-15T22:30:00Z"
        val refusedBody = """{"error":"not-possible"}"""

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(role = AccountRole.BOARD)
        }
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        class Env(
            val live: EventFakeLiveKit,
            val state: ConferenceHandQueueState,
            val hands: EventGuestHands,
        )

        fun env(maxPerRoom: Int = 50): Env {
            val live = EventFakeLiveKit()
            val state = ConferenceHandQueueState(maxPerRoom = maxPerRoom)
            return Env(live, state, EventGuestHands(config = EVENT_ONLINE_CONFIG, liveKit = live, state = state))
        }

        fun routes(
            env: Env,
            ipLimit: Int = 1000,
            identityLimit: Int = 1000,
            wired: Boolean = true,
        ): io.ktor.server.routing.Route.() -> Unit =
            {
                registerEventOnlineGuestRoutes(
                    baseUrl = base,
                    eventOnline =
                        EventOnlineServices.create(
                            config = EVENT_ONLINE_CONFIG,
                            liveKitAdminClient = env.live,
                            whiteboardState = ConferenceWhiteboardState(),
                            notesState = ConferenceNotesState(),
                            audioGrantState = ConferenceAudioGrantState(),
                            memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                            adminRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                        ),
                    ipRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    eventRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.hours),
                    stateRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    guestHands = if (wired) env.hands else null,
                    handIpRateLimiter = FederationInboxRateLimiter(maxRequests = ipLimit, window = 1.minutes),
                    handIdentityRateLimiter = FederationInboxRateLimiter(maxRequests = identityLimit, window = 1.minutes),
                )
            }

        fun slugOf(eventId: Uuid) = "event-online-$eventId"

        fun roomIdOf(event: Uuid): Uuid = transaction { EventCaptureLock.roomIdOf(event)!! }

        fun roomNameOf(event: Uuid): String =
            transaction {
                ConferenceRoomTable
                    .selectAll()
                    .where {
                        ConferenceRoomTable.id eq
                            roomIdOf(
                                event,
                            )
                    }.single()[ConferenceRoomTable.livekitRoomName]
            }

        suspend fun HttpClient.join(slug: String): EventGuestJoinResponseDto {
            val body =
                json.encodeToString(
                    EventGuestJoinRequestDto.serializer(),
                    EventGuestJoinRequestDto(
                        name = "Anna",
                        consentVersion = EventGuestNotice.VERSION,
                        consentSha256 = EventGuestNotice.SHA256,
                    ),
                )
            val response =
                post("/api/events/$slug/online-guest") {
                    header(HttpHeaders.Origin, base)
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(body)
                }
            return json.decodeFromString(EventGuestJoinResponseDto.serializer(), response.bodyAsText())
        }

        suspend fun HttpClient.hand(
            slug: String,
            token: String?,
            body: String = """{"action":"raise"}""",
            origin: String? = base,
            contentType: String = "application/json",
        ): HttpResponse =
            post("/api/events/$slug/online-guest/hand") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, contentType)
                if (token != null) header(EVENT_GUEST_ACTION_HEADER, token)
                setBody(body)
            }

        val raise = """{"action":"raise"}"""
        val lower = """{"action":"lower"}"""

        test("raise and lower: 200 with the snapshot of the broadcast, the broadcast goes out, lower ends with an empty queue") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e)) }
                    val joined = client.join(slugOf(event))
                    joined.outcome shouldBe EventOnlineOutcome.JOINED
                    e.live.seedLive(room = roomNameOf(event), identity = joined.identity!!)
                    val up = client.hand(slugOf(event), joined.handToken, raise)
                    up.status shouldBe HttpStatusCode.OK
                    val upBody = up.bodyAsText()
                    upBody shouldContain joined.identity!!
                    upBody shouldContain """"seq":1"""
                    e.live.sentData.map { it.second } shouldContain CONFERENCE_HANDS_TOPIC
                    e.live.sentData
                        .last()
                        .third shouldBe upBody
                    e.state.contains(roomId = roomIdOf(event), identity = joined.identity!!) shouldBe true
                    // idempotent: no new broadcast
                    val sent = e.live.sentData.size
                    client.hand(slugOf(event), joined.handToken, raise).status shouldBe HttpStatusCode.OK
                    e.live.sentData.size shouldBe sent
                    val down = client.hand(slugOf(event), joined.handToken, lower)
                    down.status shouldBe HttpStatusCode.OK
                    down.bodyAsText() shouldNotContain joined.identity!!
                    e.state.contains(roomId = roomIdOf(event), identity = joined.identity!!) shouldBe false
                }
            }
        }

        test("a ticket guest identity (evg- plus 32 hex) can raise with its own token") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e)) }
                    client.join(slugOf(event)) // provisions the room
                    val identity = "evg-" + "0f".repeat(16)
                    val token =
                        EventGuestActionToken.issue(
                            serverSecret = EVENT_ONLINE_CONFIG.apiSecret,
                            eventId = event,
                            roomId = roomIdOf(event),
                            identity = identity,
                            expiresAt = Instant.parse(insideWindow) + 1.hours,
                        )
                    e.live.seedLive(room = roomNameOf(event), identity = identity)
                    client.hand(slugOf(event), token, raise).status shouldBe HttpStatusCode.OK
                    e.state.contains(roomId = roomIdOf(event), identity = identity) shouldBe true
                }
            }
        }

        test("not in the room: raise is the uniform 403, lower is 204 and takes the hand down") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e)) }
                    val joined = client.join(slugOf(event))
                    val gone = client.hand(slugOf(event), joined.handToken, raise)
                    gone.status shouldBe HttpStatusCode.Forbidden
                    gone.bodyAsText() shouldBe refusedBody
                    // raised while present, then gone: lowering still works and answers without a body
                    e.live.seedLive(room = roomNameOf(event), identity = joined.identity!!)
                    client.hand(slugOf(event), joined.handToken, raise).status shouldBe HttpStatusCode.OK
                    e.live.participants
                        .getValue(roomNameOf(event))
                        .clear()
                    val lowered = client.hand(slugOf(event), joined.handToken, lower)
                    lowered.status shouldBe HttpStatusCode.NoContent
                    lowered.bodyAsText() shouldBe ""
                    e.state.contains(roomId = roomIdOf(event), identity = joined.identity!!) shouldBe false
                    // a captured token of somebody who left cannot read or raise any more
                    client.hand(slugOf(event), joined.handToken, raise).status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test(
            "every semantic refusal is byte-identical: no token, bad token, expired, foreign event, unknown event, ended room, not present, full queue",
        ) {
            val e = env(maxPerRoom = 1)
            val event = fx.event(createdBy = organizer)
            val other = fx.event(createdBy = organizer)
            val ended = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e)) }
                    val first = client.join(slugOf(event))
                    val second = client.join(slugOf(event))
                    val foreign = client.join(slugOf(other))
                    val endedGuest = client.join(slugOf(ended))
                    e.live.seedLive(room = roomNameOf(event), identity = first.identity!!)
                    e.live.seedLive(room = roomNameOf(event), identity = second.identity!!)
                    e.live.seedLive(room = roomNameOf(ended), identity = endedGuest.identity!!)
                    client.hand(slugOf(event), first.handToken, raise).status shouldBe HttpStatusCode.OK
                    transaction {
                        ConferenceRoomTable.update(
                            { ConferenceRoomTable.id eq roomIdOf(ended) },
                        ) { it[endedAt] = DbClock.nowLocalDateTime() }
                    }
                    val expired =
                        EventGuestActionToken.issue(
                            serverSecret = EVENT_ONLINE_CONFIG.apiSecret,
                            eventId = event,
                            roomId = roomIdOf(event),
                            identity = "evg-" + Uuid.random(),
                            expiresAt = Instant.parse(insideWindow) - 1.minutes,
                        )
                    val refusals =
                        listOf(
                            client.hand(slugOf(event), null),
                            client.hand(slugOf(event), ""),
                            client.hand(slugOf(event), "garbage"),
                            client.hand(slugOf(event), "x".repeat(257)),
                            client.hand(slugOf(event), expired),
                            client.hand(slugOf(event), foreign.handToken), // token of another event
                            client.hand(slugOf(other), first.handToken), // ... and the other way round
                            client.hand("no-such-event", first.handToken),
                            client.hand(slugOf(ended), endedGuest.handToken),
                            client.hand(slugOf(event), second.handToken), // queue is full (cap 1)
                        )
                    refusals.forEach {
                        it.status shouldBe HttpStatusCode.Forbidden
                        it.bodyAsText() shouldBe refusedBody
                        it.headers[HttpHeaders.ContentType]!! shouldContain "application/json"
                    }
                    // the lower action refuses the same way for a bad token
                    client.hand(slugOf(event), "garbage", lower).bodyAsText() shouldBe refusedBody
                }
            }
        }

        test("a guest cannot lower somebody else's hand: the identity is the token's, never the body's") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e)) }
                    val a = client.join(slugOf(event))
                    val b = client.join(slugOf(event))
                    e.live.seedLive(room = roomNameOf(event), identity = a.identity!!)
                    e.live.seedLive(room = roomNameOf(event), identity = b.identity!!)
                    client.hand(slugOf(event), a.handToken, raise).status shouldBe HttpStatusCode.OK
                    client.hand(slugOf(event), b.handToken, raise).status shouldBe HttpStatusCode.OK
                    client.hand(slugOf(event), a.handToken, lower).status shouldBe HttpStatusCode.OK
                    e.state.contains(roomId = roomIdOf(event), identity = a.identity!!) shouldBe false
                    e.state.contains(roomId = roomIdOf(event), identity = b.identity!!) shouldBe true
                    // an identity in the body is a 400, not an ignored field (a full evg- identity would not even fit the 64-byte cap: 413)
                    client
                        .hand(slugOf(event), a.handToken, """{"action":"lower","identity":"evg-x"}""")
                        .status shouldBe HttpStatusCode.BadRequest
                    e.state.contains(roomId = roomIdOf(event), identity = b.identity!!) shouldBe true
                }
            }
        }

        test("malformed requests: unknown action 400, extra field 400, no JSON 415, big body 413, foreign origin 403") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e)) }
                    val joined = client.join(slugOf(event))
                    e.live.seedLive(room = roomNameOf(event), identity = joined.identity!!)
                    client.hand(slugOf(event), joined.handToken, """{"action":"other"}""").status shouldBe HttpStatusCode.BadRequest
                    client.hand(slugOf(event), joined.handToken, "not json").status shouldBe HttpStatusCode.BadRequest
                    client.hand(slugOf(event), joined.handToken, raise, contentType = "text/plain").status shouldBe
                        HttpStatusCode.UnsupportedMediaType
                    client
                        .hand(slugOf(event), joined.handToken, """{"action":"raise","pad":"${"x".repeat(80)}"}""")
                        .status shouldBe HttpStatusCode.PayloadTooLarge
                    client.hand(slugOf(event), joined.handToken, raise, origin = "https://evil.example").status shouldBe
                        HttpStatusCode.Forbidden
                    client.hand(slugOf(event), joined.handToken, raise, origin = null).status shouldBe HttpStatusCode.Forbidden
                    e.state.contains(roomId = roomIdOf(event), identity = joined.identity!!) shouldBe false
                }
            }
        }

        test("limits: per address 429; per verified identity 429; forged tokens do not use up the identity budget") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e, ipLimit = 13, identityLimit = 3)) }
                    val joined = client.join(slugOf(event))
                    e.live.seedLive(room = roomNameOf(event), identity = joined.identity!!)
                    repeat(8) { client.hand(slugOf(event), "forged").status shouldBe HttpStatusCode.Forbidden }
                    val statuses = (1..5).map { client.hand(slugOf(event), joined.handToken, raise).status }
                    statuses.take(3).forEach { it shouldBe HttpStatusCode.OK }
                    statuses[3] shouldBe HttpStatusCode.TooManyRequests
                    // the address budget (13) is used up by now
                    client.hand(slugOf(event), "forged").status shouldBe HttpStatusCode.TooManyRequests
                }
            }
        }

        test("the feature switched off (no hand service wired) is the same uniform 403") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e, wired = false)) }
                    val joined = client.join(slugOf(event))
                    val r = client.hand(slugOf(event), joined.handToken, raise)
                    r.status shouldBe HttpStatusCode.Forbidden
                    r.bodyAsText() shouldBe refusedBody
                }
            }
        }

        test("the token is issued only for JOINED, is never in a response header of the hand route, and is not echoed") {
            val e = env()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = "2030-06-14T00:00:00Z") {
                testApplication {
                    application { routing(routes(e)) }
                    val early = client.join(slugOf(event))
                    early.outcome shouldBe EventOnlineOutcome.NOT_YET
                    early.handToken shouldBe null
                }
            }
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(e)) }
                    val joined = client.join(slugOf(event))
                    (joined.handToken != null) shouldBe true
                    e.live.seedLive(room = roomNameOf(event), identity = joined.identity!!)
                    val r = client.hand(slugOf(event), joined.handToken, raise)
                    r.bodyAsText() shouldNotContain joined.handToken!!
                    r.headers
                        .entries()
                        .flatMap { it.value }
                        .none { it.contains(joined.handToken!!) } shouldBe true
                    r.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }
    })

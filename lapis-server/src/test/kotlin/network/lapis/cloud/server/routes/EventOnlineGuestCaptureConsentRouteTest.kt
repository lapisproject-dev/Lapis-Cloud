package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAccessToken
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventCaptureAcknowledgments
import network.lapis.cloud.server.events.EventCaptureLock
import network.lapis.cloud.server.events.EventCaptureTestState
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventGuestCaptureConsentRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.280 -- `POST /api/events/{slug}/online-guest/capture-consent`: a guest WITHOUT an account who is inside acknowledges the capture consent that was
 * switched on after they entered. The LiveKit token the server gave them is the proof of who they are (HS256, issuer, expiry, an `evg-` subject, the room
 * of THIS event). Everything that does not fit is ONE uniform 403 (no oracle); a stale text is 409; a throttled caller 429; success 204 and a nameless proof.
 */
class EventOnlineGuestCaptureConsentRouteTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val json = Json { ignoreUnknownKeys = true }
        val base = "https://lapis.example.org"
        lateinit var organizer: Uuid
        val insideWindow = "2030-06-15T22:30:00Z"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(role = AccountRole.BOARD)
        }
        afterEach { EventCaptureTestState.reset() }
        afterSpec {
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun routes(
            ipLimit: Int = 1000,
            eventLimit: Int = 1000,
        ): io.ktor.server.routing.Route.() -> Unit =
            {
                registerEventOnlineGuestRoutes(
                    baseUrl = base,
                    eventOnline =
                        EventOnlineServices.create(
                            config = EVENT_ONLINE_CONFIG,
                            liveKitAdminClient = live,
                            whiteboardState = ConferenceWhiteboardState(),
                            notesState = ConferenceNotesState(),
                            audioGrantState = ConferenceAudioGrantState(),
                            memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                            adminRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                        ),
                    ipRateLimiter = FederationInboxRateLimiter(maxRequests = ipLimit, window = 1.minutes),
                    eventRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.hours),
                    stateRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    consentEventRateLimiter = FederationInboxRateLimiter(maxRequests = eventLimit, window = 1.hours),
                )
            }

        fun slugOf(eventId: Uuid) = "event-online-$eventId"

        suspend fun HttpClient.joinPlain(slug: String): EventGuestJoinResponseDto {
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

        suspend fun HttpClient.consent(
            slug: String,
            token: String,
            version: String,
            sha: String,
            origin: String? = base,
        ): HttpResponse =
            post("/api/events/$slug/online-guest/capture-consent") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, "application/json")
                setBody(
                    json.encodeToString(
                        EventGuestCaptureConsentRequestDto.serializer(),
                        EventGuestCaptureConsentRequestDto(token = token, consentVersion = version, consentSha256 = sha),
                    ),
                )
            }

        fun proofs(event: Uuid) = EventCaptureTestState.acknowledgments(event)

        test("a guest who entered BEFORE the release acknowledges afterwards: 204, one nameless proof under the guest key, idempotent") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    val joined = client.joinPlain(slugOf(event))
                    joined.outcome shouldBe EventOnlineOutcome.JOINED
                    // the release is switched on after the guest is inside
                    EventCaptureTestState.release(eventId = event)
                    val ok = client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, consentInput.consentSha256)
                    ok.status shouldBe HttpStatusCode.NoContent
                    client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.NoContent
                    val rows = proofs(event)
                    rows.size shouldBe 1
                    val key =
                        EventCaptureAcknowledgments.guestKeyOf(
                            identity = joined.identity!!,
                            serverSecret = EVENT_ONLINE_CONFIG.apiSecret,
                        )
                    rows.single()[EventCaptureAcknowledgmentTable.guestKey] shouldBe key
                    rows.single().toString() shouldNotContain "Anna"
                }
            }
        }

        test("a stale text is 409 and writes nothing") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    val joined = client.joinPlain(slugOf(event))
                    EventCaptureTestState.release(eventId = event)
                    client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, "0".repeat(64)).status shouldBe
                        HttpStatusCode.Conflict
                    proofs(event).size shouldBe 0
                }
            }
        }

        test("everything that is not a token this server minted for a guest of THIS room is the same 403") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val other = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    val joined = client.joinPlain(slugOf(event))
                    val otherJoined = client.joinPlain(slugOf(other))
                    EventCaptureTestState.release(eventId = event)
                    val now = Instant.parse(insideWindow)
                    val myRoom = roomNameOf(event)
                    val foreignRoomName = roomNameOf(other)

                    fun mint(
                        identity: String,
                        room: String,
                        secret: String = EVENT_ONLINE_CONFIG.apiSecret,
                        at: Instant = now,
                        ttl: kotlin.time.Duration = 10.minutes,
                    ) = LiveKitAccessToken
                        .mintParticipantToken(
                            apiKey = EVENT_ONLINE_CONFIG.apiKey,
                            apiSecret = secret,
                            roomName = room,
                            identity = identity,
                            displayName = "x",
                            ttl = ttl,
                            canPublish = true,
                            canPublishData = true,
                            microphoneAllowed = false,
                            sourcePolicy = LiveKitAccessToken.ParticipantSourcePolicy.MEMBER,
                            now = at,
                        ).jwt

                    val forged = mint("evg-" + Uuid.random(), myRoom, secret = "x".repeat(40))
                    val expired = mint("evg-" + Uuid.random(), myRoom, at = now - 2.hours, ttl = 1.minutes)
                    val foreignRoom = mint("evg-" + Uuid.random(), foreignRoomName)
                    val member = mint(Uuid.random().toString(), myRoom)
                    val tokens =
                        listOf(forged, expired, foreignRoom, member, "not-a-jwt", otherJoined.token!!, "")
                    tokens.forEach { token ->
                        client.consent(slugOf(event), token, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                            HttpStatusCode.Forbidden
                    }
                    // an unknown slug, a foreign origin and a missing origin are the same refusal
                    client.consent("no-such-event", joined.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.Forbidden
                    client
                        .consent(
                            slugOf(event),
                            joined.token!!,
                            consentInput.consentVersion,
                            consentInput.consentSha256,
                            origin = "https://evil.example",
                        ).status shouldBe
                        HttpStatusCode.Forbidden
                    client
                        .consent(
                            slugOf(event),
                            joined.token!!,
                            consentInput.consentVersion,
                            consentInput.consentSha256,
                            origin = null,
                        ).status shouldBe
                        HttpStatusCode.Forbidden
                    proofs(event).size shouldBe 0
                    // ... while the genuine token still works
                    client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.NoContent
                }
            }
        }

        test(
            "a guest whose token expired while they are still connected can acknowledge; an expired token of somebody who is not connected cannot",
        ) {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            lateinit var joined: EventGuestJoinResponseDto
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    joined = client.joinPlain(slugOf(event))
                    joined.outcome shouldBe EventOnlineOutcome.JOINED
                    EventCaptureTestState.release(eventId = event)
                }
            }
            // 40 minutes later (still inside the window, the 15-minute guest token is long expired)
            val later = Instant.parse(insideWindow) + 40.minutes
            TimeTestSupport.withServerClock(instant = later.toString()) {
                testApplication {
                    application { routing(routes()) }
                    // not connected in LiveKit any more: the expired token proves nothing
                    client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.Forbidden
                    proofs(event).size shouldBe 0
                    // connected: the same expired token is accepted
                    live.seedLive(room = roomNameOf(event), identity = joined.identity!!)
                    client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.NoContent
                    proofs(event).size shouldBe 1
                    // a forged token stays refused even for a connected identity
                    val forged =
                        LiveKitAccessToken
                            .mintParticipantToken(
                                apiKey = EVENT_ONLINE_CONFIG.apiKey,
                                apiSecret = "x".repeat(40),
                                roomName = roomNameOf(event),
                                identity = joined.identity!!,
                                displayName = "x",
                                ttl = 1.minutes,
                                canPublish = true,
                                canPublishData = true,
                                microphoneAllowed = false,
                                sourcePolicy = LiveKitAccessToken.ParticipantSourcePolicy.MEMBER,
                                now = Instant.parse(insideWindow),
                            ).jwt
                    client.consent(slugOf(event), forged, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.Forbidden
                }
            }
        }

        test("an event that is not released (anymore) acknowledges nothing: 204 but no proof is written") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    val joined = client.joinPlain(slugOf(event))
                    client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.NoContent
                    proofs(event).size shouldBe 0
                }
            }
        }

        test("forged-token traffic does not use up the event budget: the real guest can still acknowledge") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(eventLimit = 2)) }
                    val joined = client.joinPlain(slugOf(event))
                    EventCaptureTestState.release(eventId = event)
                    repeat(10) {
                        client.consent(slugOf(event), "x", consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                            HttpStatusCode.Forbidden
                    }
                    client.consent(slugOf(event), joined.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.NoContent
                    proofs(event).size shouldBe 1
                }
            }
        }

        test("a real token of event A against slug B is refused and does not charge the budget of B") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val eventA = fx.event(createdBy = organizer)
            val eventB = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(eventLimit = 2)) }
                    val joinedA = client.joinPlain(slugOf(eventA))
                    val joinedB = client.joinPlain(slugOf(eventB))
                    EventCaptureTestState.release(eventId = eventB)
                    repeat(10) {
                        client
                            .consent(
                                slugOf(eventB),
                                joinedA.token!!,
                                consentInput.consentVersion,
                                consentInput.consentSha256,
                            ).status shouldBe
                            HttpStatusCode.Forbidden
                    }
                    client.consent(slugOf(eventB), joinedB.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.NoContent
                    proofs(eventB).size shouldBe 1
                }
            }
        }

        test("replaying one real token of the same event uses up only that guest's budget, not that of a second real guest") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(eventLimit = 2)) }
                    val first = client.joinPlain(slugOf(event))
                    val second = client.joinPlain(slugOf(event))
                    EventCaptureTestState.release(eventId = event)
                    val statuses =
                        (1..6).map {
                            client.consent(slugOf(event), first.token!!, consentInput.consentVersion, consentInput.consentSha256).status
                        }
                    statuses.last() shouldBe HttpStatusCode.TooManyRequests
                    client.consent(slugOf(event), second.token!!, consentInput.consentVersion, consentInput.consentSha256).status shouldBe
                        HttpStatusCode.NoContent
                    proofs(event).size shouldBe 2
                }
            }
        }

        test("the caller is throttled per address: 429") {
            val consentInput = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes(ipLimit = 2)) }
                    val statuses =
                        (1..4).map {
                            client.consent(slugOf(event), "x", consentInput.consentVersion, consentInput.consentSha256).status
                        }
                    statuses.last() shouldBe HttpStatusCode.TooManyRequests
                }
            }
        }
    })

/** The LiveKit room name of the (already provisioned) conference room of [event]. */
private fun roomNameOf(event: Uuid): String =
    transaction {
        val room = EventCaptureLock.roomIdOf(event)!!
        ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single()[ConferenceRoomTable.livekitRoomName]
    }

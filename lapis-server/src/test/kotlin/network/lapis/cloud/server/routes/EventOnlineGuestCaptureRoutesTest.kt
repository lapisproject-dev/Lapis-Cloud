package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventCaptureAcknowledgments
import network.lapis.cloud.server.events.EventCaptureTestState
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- the two routes of a person WITHOUT an account in an event that may be recorded or streamed: the state route shows the capture consent and the
 * capture variant of the guest notice (never "Es wird nichts aufgezeichnet."), the join route refuses (409, the page reloads) everything that is not exactly
 * what THIS event needs, and writes the nameless proof before it answers. An event that is not released keeps its V1.9.113 behaviour byte for byte.
 */
class EventOnlineGuestCaptureRoutesTest :
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

        fun routes(): io.ktor.server.routing.Route.() -> Unit =
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
                    ipRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    eventRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.hours),
                    stateRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                )
            }

        fun slugOf(eventId: Uuid) = "event-online-$eventId"

        fun request(
            version: String,
            sha: String,
            captureVersion: String? = null,
            captureSha: String? = null,
        ) = json.encodeToString(
            EventGuestJoinRequestDto.serializer(),
            EventGuestJoinRequestDto(
                name = "Anna",
                consentVersion = version,
                consentSha256 = sha,
                captureConsentVersion = captureVersion,
                captureConsentSha256 = captureSha,
            ),
        )

        suspend fun HttpClient.join(
            slug: String,
            payload: String,
        ): HttpResponse =
            post("/api/events/$slug/online-guest") {
                header(HttpHeaders.Origin, base)
                header(HttpHeaders.ContentType, "application/json")
                setBody(payload)
            }

        suspend fun HttpClient.state(slug: String) =
            json.decodeFromString(EventOnlineStateDto.serializer(), get("/api/events/$slug/online-state").bodyAsText())

        test(
            "the state of a released event carries the consent text, the capture variant of the guest notice, and the banner state -- an unreleased one does not",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            val plain = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    val capture = client.state(slugOf(released))
                    capture.outcome shouldBe EventOnlineOutcome.JOINED
                    capture.noticeVersion shouldBe EventGuestNoticeCapture.VERSION
                    capture.noticeSha256 shouldBe EventGuestNoticeCapture.SHA256
                    capture.captureNotice!!.versionLabel shouldBe consent.consentVersion
                    capture.captureNotice!!.sha256 shouldBe consent.consentSha256
                    capture.captureNotice!!.keyPoints.size shouldBe 3
                    capture.captureLive!!.recording shouldBe false
                    val ordinary = client.state(slugOf(plain))
                    ordinary.noticeVersion shouldBe EventGuestNotice.VERSION
                    ordinary.noticeSha256 shouldBe EventGuestNotice.SHA256
                    ordinary.captureNotice shouldBe null
                    ordinary.captureLive shouldBe null
                }
            }
        }

        test(
            "a guest of a released event joins with the capture consent: the answer carries the banner state, the nameless proof is stored",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    val response =
                        client.join(
                            slugOf(event),
                            request(
                                EventGuestNoticeCapture.VERSION,
                                EventGuestNoticeCapture.SHA256,
                                consent.consentVersion,
                                consent.consentSha256,
                            ),
                        )
                    response.status shouldBe HttpStatusCode.OK
                    val answer = json.decodeFromString(EventGuestJoinResponseDto.serializer(), response.bodyAsText())
                    answer.outcome shouldBe EventOnlineOutcome.JOINED
                    answer.captureLive!!.streaming shouldBe false
                    val rows = EventCaptureTestState.acknowledgments(event)
                    rows.size shouldBe 1
                    rows.single()[EventCaptureAcknowledgmentTable.guestKey] shouldBe
                        EventCaptureAcknowledgments.guestKeyOf(identity = answer.identity!!, serverSecret = EVENT_ONLINE_CONFIG.apiSecret)
                    rows.single()[EventCaptureAcknowledgmentTable.memberId] shouldBe null
                    // the answer never repeats the proof
                    response.bodyAsText() shouldNotContain rows.single()[EventCaptureAcknowledgmentTable.guestKey]!!
                }
            }
        }

        test("everything that is not exactly what this event needs is a 409 and creates no room, no token and no proof") {
            val consent = EventCaptureTestState.enabledWithText()
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            val plain = fx.event(createdBy = organizer)
            val creates = live.createCalls.get()
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    val capture = EventGuestNoticeCapture.VERSION to EventGuestNoticeCapture.SHA256
                    val old = EventGuestNotice.VERSION to EventGuestNotice.SHA256
                    listOf(
                        // released event, but the page showed the OLD notice or sent no capture consent at all
                        slugOf(released) to request(old.first, old.second, consent.consentVersion, consent.consentSha256),
                        slugOf(released) to request(capture.first, capture.second),
                        slugOf(released) to request(capture.first, capture.second, consent.consentVersion, null),
                        // a stale or forged capture consent
                        slugOf(released) to request(capture.first, capture.second, consent.consentVersion, "0".repeat(64)),
                        slugOf(released) to request(capture.first, capture.second, "2026-01-01.other", consent.consentSha256),
                        // an unreleased event with the capture variant of the notice
                        slugOf(plain) to request(capture.first, capture.second),
                        // neither variant of the notice
                        slugOf(plain) to request("2026-01-01.v9", "1".repeat(64)),
                        slugOf(released) to request(capture.first, "1".repeat(64), consent.consentVersion, consent.consentSha256),
                    ).forEach { (slug, payload) ->
                        client.join(slug, payload).status shouldBe HttpStatusCode.Conflict
                    }
                    EventCaptureTestState.acknowledgments(released).size shouldBe 0
                    // the plain event still admits the plain notice (V1.9.113)
                    client.join(slugOf(plain), request(old.first, old.second)).status shouldBe HttpStatusCode.OK
                }
            }
            (live.createCalls.get() - creates) shouldBe 1
        }

        test("with the instance switch off a released event is dormant: the plain notice admits, nothing is stored") {
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            EventCaptureTestState.instance(enabled = false)
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(routes()) }
                    client.state(slugOf(event)).captureNotice shouldBe null
                    client.join(slugOf(event), request(EventGuestNotice.VERSION, EventGuestNotice.SHA256)).status shouldBe HttpStatusCode.OK
                    EventCaptureTestState.acknowledgments(event).size shouldBe 0
                }
            }
        }
    })

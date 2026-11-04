package network.lapis.cloud.server.events

import dev.kilua.rpc.types.toDecimal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.encounter.EncounterSourceScan
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.rpc.EventService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.280 -- a NEWLY created event is released for recording by default (`EventCaptureDefaults`): only for a Lapis conference and only while the instance
 * switch is on; an explicit "no" wins; an explicit "yes" while the switch is off is silently "no"; a series applies it to every occurrence; editing never
 * touches it; existing rows are never changed (there is NO database default). The creation audit carries the event id and two flags -- nothing else.
 */
class EventCaptureDefaultTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        lateinit var board: Uuid
        lateinit var services: EventOnlineServices
        val before = "2030-06-15T10:00:00Z"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            board = Uuid.parse("00000000-0000-0000-0000-000000000002")
            services =
                EventOnlineServices.create(
                    config = EVENT_ONLINE_CONFIG,
                    liveKitAdminClient = live,
                    whiteboardState = ConferenceWhiteboardState(),
                    notesState = ConferenceNotesState(),
                    audioGrantState = ConferenceAudioGrantState(),
                    memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    adminRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                )
        }
        afterEach { EventCaptureTestState.reset() }
        afterSpec {
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun service(call: ApplicationCall) =
            EventService(
                call = call,
                checkoutGateways = emptyMap(),
                baseUrl = "https://example.org",
                mailDispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                writeRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                eventOnline = services,
            )

        fun input(
            kind: EventOnlineKind = EventOnlineKind.LAPIS_CONFERENCE,
            captureAllowed: Boolean? = null,
            location: String? = "Saal",
            url: String? = null,
        ) = EventInput(
            title = "Standardfreigabe",
            description = "d",
            locationText = location,
            onlineUrl = url,
            startsAt = LocalDateTime(2030, 6, 15, 18, 0),
            endsAt = LocalDateTime(2030, 6, 15, 20, 0),
            capacity = null,
            feeAmount = 0.0.toDecimal(),
            feeCurrency = "EUR",
            visibility = EventVisibility.PUBLIC,
            onlineKind = kind,
            captureAllowed = captureAllowed,
        )

        fun releasedOf(event: Uuid) =
            transaction { EventTable.selectAll().where { EventTable.id eq event }.single()[EventTable.captureAllowed] }

        fun auditOf(event: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { (AuditLogEntryTable.entityType eq AuditEntityType.EVENT_CAPTURE) and (AuditLogEntryTable.entityId eq event) }
                    .toList()
            }

        /** Creates an event through the real service as the board and registers it for cleanup. */
        suspend fun io.ktor.client.HttpClient.create(i: EventInput): Uuid {
            val dto = runAs(member = board) { call -> service(call).createEvent(i) }.getOrThrow()
            return Uuid.parse(dto.id).also { fx.eventIds += it }
        }

        test("instance switch on + Lapis conference: a new event is released by default and the creation is audited without a name") {
            EventCaptureTestState.enabledWithText()
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val id = client.create(input())
                    releasedOf(id) shouldBe true
                    val entry = auditOf(id).single()
                    entry[AuditLogEntryTable.action] shouldBe AuditAction.CREATE
                    entry[AuditLogEntryTable.actorMemberId] shouldBe board
                    entry[AuditLogEntryTable.beforeSnapshot] shouldBe null
                    entry[AuditLogEntryTable.afterSnapshot] shouldBe "{\"eventId\":\"$id\",\"captureAllowed\":true,\"source\":\"default\"}"
                    entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "Standardfreigabe"
                }
            }
        }

        test("an explicit yes is audited as 'explicit'") {
            EventCaptureTestState.enabledWithText()
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val id = client.create(input(captureAllowed = true))
                    releasedOf(id) shouldBe true
                    auditOf(id).single()[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"source\":\"explicit\""
                }
            }
        }

        test("instance switch off: not released (also for an explicit yes, silently), and no audit entry") {
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val silent = client.create(input())
                    releasedOf(silent) shouldBe false
                    auditOf(silent) shouldBe emptyList()
                    val explicitYes = client.create(input(captureAllowed = true))
                    releasedOf(explicitYes) shouldBe false
                    auditOf(explicitYes) shouldBe emptyList()
                }
            }
        }

        test("an event that is no Lapis conference is never released by default") {
            EventCaptureTestState.enabledWithText()
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val onSite = client.create(input(kind = EventOnlineKind.NONE))
                    releasedOf(onSite) shouldBe false
                    val external =
                        client.create(input(kind = EventOnlineKind.EXTERNAL_LINK, url = "https://example.org/stream", location = null))
                    releasedOf(external) shouldBe false
                    val forced = client.create(input(kind = EventOnlineKind.NONE, captureAllowed = true))
                    releasedOf(forced) shouldBe false
                }
            }
        }

        test("an explicit no wins over the default") {
            EventCaptureTestState.enabledWithText()
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val id = client.create(input(captureAllowed = false))
                    releasedOf(id) shouldBe false
                    auditOf(id) shouldBe emptyList()
                }
            }
        }

        test("a series: every occurrence is released by default (one audit entry each), or none with an explicit no") {
            EventCaptureTestState.enabledWithText()
            val rule = RecurrenceRuleInput(frequency = RecurrenceFrequency.WEEKLY, byWeekdays = setOf(RecurrenceWeekday.SA), count = 3)
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val defaults =
                        client.runAs(member = board) { call -> service(call).createEventSeries(input = input(), rule = rule) }.getOrThrow()
                    val ids = defaults.createdEventIds.map { Uuid.parse(it) }
                    fx.eventIds += ids
                    ids.size shouldBe 3
                    ids.forEach {
                        releasedOf(it) shouldBe true
                        auditOf(it).size shouldBe 1
                    }
                    val declined =
                        client
                            .runAs(
                                member = board,
                            ) { call -> service(call).createEventSeries(input = input(captureAllowed = false), rule = rule) }
                            .getOrThrow()
                    val declinedIds = declined.createdEventIds.map { Uuid.parse(it) }
                    fx.eventIds += declinedIds
                    declinedIds.forEach {
                        releasedOf(it) shouldBe false
                        auditOf(it) shouldBe emptyList()
                    }
                }
            }
        }

        test("updateEvent ignores captureAllowed -- the only way to change a release is setEventCaptureAllowed") {
            EventCaptureTestState.enabledWithText()
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val released = client.create(input())
                    val declined = client.create(input(captureAllowed = false))
                    client
                        .runAs(
                            member = board,
                        ) { call -> service(call).updateEvent(id = released.toString(), input = input(captureAllowed = false)) }
                        .getOrThrow()
                    client
                        .runAs(
                            member = board,
                        ) { call -> service(call).updateEvent(id = declined.toString(), input = input(captureAllowed = true)) }
                        .getOrThrow()
                    releasedOf(released) shouldBe true
                    releasedOf(declined) shouldBe false
                }
            }
        }

        test("a series edit does not change a release either (applyEdit never writes capture_allowed)") {
            val edit = EncounterSourceScan.mainFile("events/series/EventSeriesMaterializer.kt").readText()
            val apply = edit.substringAfter("fun applyEdit(")
            apply shouldNotContain "captureAllowed"
            // ... and neither do the importer, the staging seeds and the update path of the store
            EncounterSourceScan.mainFile("events/EventImporter.kt").readText() shouldNotContain "captureAllowed"
            EncounterSourceScan.mainFile("db/StagingSeedEvents.kt").readText() shouldNotContain "captureAllowed"
            EncounterSourceScan.mainFile("db/StagingSeedCommunity.kt").readText() shouldNotContain "captureAllowed"
            val store = EncounterSourceScan.mainFile("events/EventStore.kt").readText()
            store.substringAfter("fun updateEvent(").substringBefore("\n    fun ") shouldNotContain "captureAllowed"
            store shouldContain "captureAllowed: Boolean = false"
        }

        test("the Sitzung announcement has no explicit choice: it asks the default with null") {
            val ops = EncounterSourceScan.mainFile("governance/meetingevent/MeetingEventLinkOperations.kt").readText()
            ops shouldContain "EventCaptureDefaults.resolve(explicit = null, onlineKind = input.onlineKind)"
            ops shouldContain "captureAllowed = captureAllowed,"
            EventCaptureTestState.enabledWithText()
            transaction { EventCaptureDefaults.resolve(explicit = null, onlineKind = EventOnlineKind.LAPIS_CONFERENCE) } shouldBe true
            transaction { EventCaptureDefaults.resolve(explicit = null, onlineKind = EventOnlineKind.NONE) } shouldBe false
        }

        test("there is no database default: the column keeps DEFAULT FALSE and a directly inserted event is not released") {
            EventCaptureTestState.enabledWithText()
            val direct = fx.event(createdBy = fx.member())
            releasedOf(direct) shouldBe false
            val migration =
                File(SourceScan.mainRoot().parentFile, "resources/db/migration/V110__event_capture.sql").readText()
            migration.lowercase().replace(Regex("""\s+"""), " ") shouldContain "capture_allowed boolean not null default false"
        }
    })

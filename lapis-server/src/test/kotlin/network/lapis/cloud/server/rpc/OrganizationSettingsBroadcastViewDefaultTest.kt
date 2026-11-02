package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING
import network.lapis.cloud.server.events.EventConferenceProvisioner
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

private val SHARED_COUNT_LIMITER = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)
private val SHARED_APPLY_LIMITER = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes)
private val TIGHT_APPLY_LIMITER = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)

/**
 * Welle V1.9.262 -- the organisation default of the recording/stream view: ADMIN-only, audited once per real change, copied into rooms only at
 * creation (conference service and event provisioner), explicit room choices always win, and the bulk "apply" touches exactly the open,
 * non-encounter rooms that differ -- with one audit entry that carries the count and the value but no room id or title.
 */
class OrganizationSettingsBroadcastViewDefaultTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val createdRooms = mutableListOf<Uuid>()
        val createdRecordings = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                if (createdRecordings.isNotEmpty()) ConferenceRecordingTable.deleteWhere { id inList createdRecordings }
                if (createdRooms.isNotEmpty()) ConferenceRoomTable.deleteWhere { id inList createdRooms }
            }
            fx.cleanUp()
        }

        fun setDefault(view: ConferenceBroadcastView) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[conferenceBroadcastViewDefault] = view
                }
            }
        afterTest { setDefault(ConferenceBroadcastView.RASTER) }

        fun orgAuditCount(): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                            (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID)
                    }.count()
            }

        fun latestOrgAudit() =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS }
                    .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                    .first()
            }

        fun insertRoom(
            view: ConferenceBroadcastView,
            ended: Boolean = false,
            encounter: Boolean = false,
            title: String = "Geheimer Raumtitel ${Uuid.random()}",
        ): Uuid {
            val id = Uuid.random()
            val creator = Uuid.parse(ADMIN_ID)
            val space = if (encounter) fx.space() else null
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[ConferenceRoomTable.title] = title
                    it[description] = ""
                    it[livekitRoomName] = "lc-$id"
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = if (ended) DbClock.nowLocalDateTime() else null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                    it[muteOnJoin] = false
                    it[participantsMayUnmute] = true
                    it[encounterSpaceId] = space
                    it[broadcastView] = view
                }
            }
            createdRooms += id
            return id
        }

        fun viewOf(room: Uuid): ConferenceBroadcastView =
            transaction {
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single()[ConferenceRoomTable.broadcastView]
            }

        val fakeLiveKit = EventFakeLiveKit()

        fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerBroadcastViewTestRoutes(fakeLiveKit) }
                }
                block()
            }

        suspend fun HttpClient.set(
            who: String?,
            view: String,
        ) = post("/t/set?view=$view") { if (who != null) header("X-Member-Id", who) }

        suspend fun HttpClient.count(who: String) = get("/t/count") { header("X-Member-Id", who) }

        suspend fun HttpClient.apply(
            who: String,
            path: String = "/t/apply",
        ) = post(path) { header("X-Member-Id", who) }

        test("the factory default is RASTER") {
            withApp { client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "RASTER" }
        }

        test("only the ADMIN may set, count and apply; everybody else is refused and nothing changes") {
            withApp {
                val audits = orgAuditCount()
                for (who in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID)) {
                    client.set(who, "SPEAKER").status shouldBe HttpStatusCode.Forbidden
                    client.count(who).status shouldBe HttpStatusCode.Forbidden
                    client.apply(who).status shouldBe HttpStatusCode.Forbidden
                }
                client.set(null, "SPEAKER").status shouldBe HttpStatusCode.Unauthorized
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "RASTER"
                orgAuditCount() shouldBe audits
            }
        }

        test("a change writes one audit entry with a one-field before/after snapshot; the same value writes nothing") {
            withApp {
                val start = orgAuditCount()
                client.set(ADMIN_ID, "RASTER").status shouldBe HttpStatusCode.OK
                orgAuditCount() shouldBe start

                client.set(ADMIN_ID, "SPEAKER").bodyAsText() shouldBe "SPEAKER"
                orgAuditCount() shouldBe start + 1
                val entry = latestOrgAudit()
                entry[AuditLogEntryTable.beforeSnapshot] shouldBe "{\"conferenceBroadcastViewDefault\":\"RASTER\"}"
                entry[AuditLogEntryTable.afterSnapshot] shouldBe "{\"conferenceBroadcastViewDefault\":\"SPEAKER\"}"

                client.set(ADMIN_ID, "SPEAKER").status shouldBe HttpStatusCode.OK
                orgAuditCount() shouldBe start + 1
            }
        }

        test("the generic updateOrganizationSettings leaves the default untouched") {
            withApp {
                for (preset in ConferenceBroadcastView.entries) {
                    setDefault(preset)
                    val answer = client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }
                    answer.status shouldBe HttpStatusCode.OK
                    answer.bodyAsText() shouldBe preset.name
                }
            }
        }

        test("an unknown view name is rejected when the call is decoded") {
            val decoded = runCatching { Json.decodeFromString<ConferenceBroadcastView>("\"GRID\"") }
            (decoded.exceptionOrNull() is SerializationException) shouldBe true
            Json.decodeFromString<ConferenceBroadcastView>("\"SPEAKER\"") shouldBe ConferenceBroadcastView.SPEAKER
        }

        test("a new room created by the conference service takes the organisation default") {
            withApp {
                for (view in ConferenceBroadcastView.entries) {
                    setDefault(view)
                    val body = client.post("/t/create-room") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                    val roomId = Uuid.parse(body.substringBefore(";"))
                    createdRooms += roomId
                    body.substringAfter(";") shouldBe view.name
                    viewOf(roomId) shouldBe view
                }
            }
        }

        test("an event room takes the organisation default; an explicit room choice is never overwritten by a later change") {
            setDefault(ConferenceBroadcastView.SPEAKER)
            val organizer = fx.member(role = AccountRole.MEMBER)
            val provisioner =
                EventConferenceProvisioner(
                    config = network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG,
                    liveKit = fakeLiveKit,
                    closer =
                        ConferenceRoomCloser(
                            liveKitAdminClient = fakeLiveKit,
                            whiteboardState = ConferenceWhiteboardState(),
                            notesState = ConferenceNotesState(),
                            audioGrantState = ConferenceAudioGrantState(),
                        ),
                )
            val event =
                fx.event(
                    createdBy = organizer,
                    startsAt = LocalDateTime(2031, 5, 1, 18, 0),
                    endsAt = LocalDateTime(2031, 5, 1, 20, 0),
                )
            val ready =
                runBlocking { provisioner.ensureRoom(eventId = event, now = Instant.parse("2031-05-01T17:30:00Z")) }
                    as EventConferenceProvisioner.Ready.Room
            viewOf(ready.roomId) shouldBe ConferenceBroadcastView.SPEAKER

            // the explicit choice of a room (here: set directly, as the moderation does) survives every later org change
            transaction {
                ConferenceRoomTable.update(
                    { ConferenceRoomTable.id eq ready.roomId },
                ) { it[broadcastView] = ConferenceBroadcastView.RASTER }
            }
            for (view in listOf(ConferenceBroadcastView.RASTER, ConferenceBroadcastView.SPEAKER)) {
                withApp { client.set(ADMIN_ID, view.name) }
                viewOf(ready.roomId) shouldBe ConferenceBroadcastView.RASTER
            }
        }

        test("apply: exactly the open, non-encounter, differing rooms change; one audit entry with count and value but no title or id") {
            withApp {
                setDefault(ConferenceBroadcastView.SPEAKER)
                val base = client.count(ADMIN_ID).bodyAsText().toInt()
                val a = insertRoom(ConferenceBroadcastView.RASTER, title = "Streng vertraulich Alpha")
                val b = insertRoom(ConferenceBroadcastView.RASTER)
                val c = insertRoom(ConferenceBroadcastView.RASTER)
                val keep = insertRoom(ConferenceBroadcastView.SPEAKER)
                val ended = insertRoom(ConferenceBroadcastView.RASTER, ended = true)
                val encounter = insertRoom(ConferenceBroadcastView.RASTER, encounter = true)
                val running = insertRoom(ConferenceBroadcastView.RASTER)
                val recording = Uuid.random()
                transaction {
                    ConferenceRecordingTable.insert {
                        it[id] = recording
                        it[roomId] = running
                        it[startedByMemberId] = Uuid.parse(ADMIN_ID)
                        it[startedAt] = DbClock.nowLocalDateTime()
                        it[status] = ConferenceRecordingStatus.RECORDING
                        it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                        it[rawDir] = recording.toString()
                        it[composeAttempts] = 0
                        it[stage] = 0.toShort()
                        it[broadcastView] = ConferenceBroadcastView.RASTER
                    }
                }
                createdRecordings += recording

                client.count(ADMIN_ID).bodyAsText().toInt() shouldBe base + 4
                val audits = orgAuditCount()
                val applied = client.apply(ADMIN_ID).bodyAsText().toInt()
                applied shouldBe base + 4
                for (room in listOf(a, b, c, running)) viewOf(room) shouldBe ConferenceBroadcastView.SPEAKER
                viewOf(keep) shouldBe ConferenceBroadcastView.SPEAKER
                viewOf(ended) shouldBe ConferenceBroadcastView.RASTER
                viewOf(encounter) shouldBe ConferenceBroadcastView.RASTER
                // the running recording keeps the view frozen at its start
                transaction {
                    ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recording }.single()[
                        ConferenceRecordingTable.broadcastView,
                    ]
                } shouldBe ConferenceBroadcastView.RASTER

                orgAuditCount() shouldBe audits + 1
                val after = latestOrgAudit()[AuditLogEntryTable.afterSnapshot]!!
                after shouldContain "\"rooms\":${base + 4}"
                after shouldContain "SPEAKER"
                after shouldNotContain "Streng vertraulich"
                after shouldNotContain a.toString()
                latestOrgAudit()[AuditLogEntryTable.beforeSnapshot] shouldBe null

                // idempotent: nothing differs any more, nothing is written
                client.count(ADMIN_ID).bodyAsText() shouldBe "0"
                client.apply(ADMIN_ID).bodyAsText() shouldBe "0"
                orgAuditCount() shouldBe audits + 1
            }
        }

        test("apply is rate limited per administrator") {
            withApp {
                client.apply(ADMIN_ID, "/t/apply-tight").status shouldBe HttpStatusCode.OK
                client.apply(ADMIN_ID, "/t/apply-tight").status shouldBe HttpStatusCode.Conflict
            }
        }
    })

private fun org(call: io.ktor.server.application.ApplicationCall) =
    OrganizationSettingsService(
        call = call,
        pulpitRecording = NO_PULPIT_RECORDING,
        broadcastViewCountRateLimiter = SHARED_COUNT_LIMITER,
        broadcastViewApplyRateLimiter = SHARED_APPLY_LIMITER,
    )

private fun Route.registerBroadcastViewTestRoutes(liveKit: EventFakeLiveKit) {
    post("/t/set") {
        val view = ConferenceBroadcastView.valueOf(call.request.queryParameters["view"]!!)
        call.respondText(org(call).setConferenceBroadcastViewDefault(view).conferenceBroadcastViewDefault.name)
    }
    get("/t/get") { call.respondText(org(call).getOrganizationSettings().conferenceBroadcastViewDefault.name) }
    get("/t/count") { call.respondText(org(call).countRoomsDifferingFromBroadcastViewDefault().toString()) }
    post("/t/apply") { call.respondText(org(call).applyBroadcastViewDefaultToExistingRooms().toString()) }
    post("/t/apply-tight") {
        val service =
            OrganizationSettingsService(
                call = call,
                pulpitRecording = NO_PULPIT_RECORDING,
                broadcastViewApplyRateLimiter = TIGHT_APPLY_LIMITER,
            )
        call.respondText(service.applyBroadcastViewDefaultToExistingRooms().toString())
    }
    post("/t/generic") {
        val dto = org(call).updateOrganizationSettings(OrganizationSettingsInput(name = call.request.queryParameters["name"]!!))
        call.respondText(dto.conferenceBroadcastViewDefault.name)
    }
    post("/t/create-room") {
        val service =
            ConferenceService(
                call = call,
                liveKitAdminClient = liveKit,
                createRoomRateLimiter = LoginRateLimiter(),
                config = network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG,
                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                roomVotingStateRateLimiter = FederationInboxRateLimiter(),
            )
        val room = service.createRoom(ConferenceRoomInput(title = "Standardansicht-Test"))
        call.respondText("${room.id};${room.broadcastView.name}")
    }
}

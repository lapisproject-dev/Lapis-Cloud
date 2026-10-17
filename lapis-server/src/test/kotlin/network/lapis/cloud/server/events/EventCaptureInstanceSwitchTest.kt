package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.rpc.EventCaptureSettingsStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.OrganizationSettingsService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.rpc.EventCaptureBusyException
import network.lapis.cloud.shared.rpc.EventCaptureChangeLockedException
import network.lapis.cloud.shared.rpc.EventCaptureOperatorNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureTextMissingException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val BOARD_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")
private val MEMBER_ID = Uuid.parse("00000000-0000-0000-0000-000000000004")

/**
 * V1.9.156 -- the instance switch "recording and live streaming in event rooms" ([OrganizationSettingsService.updateEventCaptureEnabled]): ADMIN only; ON
 * needs the CURRENT operator notice AND a published consent text AND no active released event; OFF is refused while an event room records or streams (there is
 * no remote stop); every real change is audited once with only the switch, the notice version and its hash; the generic update never touches the columns.
 */
class EventCaptureInstanceSwitchTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        lateinit var organizer: Uuid

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
        }
        afterEach {
            EventCaptureTestState.reset()
            // a release left behind by one test must not block the next one (a released, alive event blocks the switch)
            transaction { EventTable.update({ EventTable.id inList fx.eventIds }) { it[captureAllowed] = false } }
        }
        afterSpec {
            transaction {
                fx.eventIds.flatMap { fx.roomIdsOf(it) }.forEach { room ->
                    ConferenceRecordingTable.deleteWhere { roomId eq room }
                    ConferenceStreamTable.deleteWhere { roomId eq room }
                }
            }
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun service(call: io.ktor.server.application.ApplicationCall) =
            OrganizationSettingsService(call = call, pulpitRecording = NO_PULPIT_RECORDING, recordingInfrastructureAvailable = true)

        fun entries() =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                            (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID)
                    }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                    .toList()
            }

        test("only an ADMIN may switch it; BOARD and MEMBER are refused and nothing changes") {
            EventCaptureTestState.publishConsent()
            encounterApp {
                listOf(BOARD_ID, MEMBER_ID).forEach { who ->
                    client
                        .runAs(
                            member = who,
                        ) { call ->
                            service(
                                call,
                            ).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = EVENT_CAPTURE_NOTICE_VERSION)
                        }.failure<ForbiddenException>()
                }
                EventCaptureSettingsStore.currentValue().enabled shouldBe false
            }
        }

        test("switching on needs the current operator notice: none, an old and a made-up version are the distinct 'outdated' answer") {
            EventCaptureTestState.publishConsent()
            encounterApp {
                listOf(null, "0", "2").forEach { version ->
                    client
                        .runAs(
                            member = ADMIN_ID,
                        ) { call -> service(call).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = version) }
                        .failure<EventCaptureOperatorNoticeOutdatedException>()
                }
                EventCaptureSettingsStore.currentValue().enabled shouldBe false
                val dto =
                    client
                        .runAs(
                            member = ADMIN_ID,
                        ) { call ->
                            service(
                                call,
                            ).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = EVENT_CAPTURE_NOTICE_VERSION)
                        }.getOrThrow()
                dto.eventCaptureEnabled shouldBe true
                dto.eventCaptureNoticeVersion shouldBe EVENT_CAPTURE_NOTICE_VERSION
                dto.eventCaptureTextIsPlaceholder shouldBe false
            }
        }

        test("switching on needs a PUBLISHED consent text -- the built-in placeholder never suffices") {
            encounterApp {
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call ->
                        service(
                            call,
                        ).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = EVENT_CAPTURE_NOTICE_VERSION)
                    }.failure<EventCaptureTextMissingException>()
                EventCaptureSettingsStore.currentValue().enabled shouldBe false
                client
                    .runAs(member = ADMIN_ID) { call -> service(call).getOrganizationSettings() }
                    .getOrThrow()
                    .eventCaptureTextIsPlaceholder shouldBe true
            }
        }

        test(
            "switching on is refused while a released event is active (people in it acknowledged nothing); the same event without a release does not block",
        ) {
            EventCaptureTestState.publishConsent()
            val inside = "2030-06-15T22:30:00Z"
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            TimeTestSupport.withServerClock(instant = inside) {
                encounterApp {
                    client
                        .runAs(
                            member = ADMIN_ID,
                        ) { call ->
                            service(
                                call,
                            ).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = EVENT_CAPTURE_NOTICE_VERSION)
                        }.failure<EventCaptureChangeLockedException>()
                    EventCaptureSettingsStore.currentValue().enabled shouldBe false
                    EventCaptureTestState.release(eventId = released, allowed = false)
                    client
                        .runAs(
                            member = ADMIN_ID,
                        ) { call ->
                            service(
                                call,
                            ).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = EVENT_CAPTURE_NOTICE_VERSION)
                        }.getOrThrow()
                        .eventCaptureEnabled shouldBe true
                }
            }
        }

        test("switching off is refused while an event room records or streams -- and works once it is over; nothing is stopped remotely") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val room = runRoom(fx = fx, live = live, event = event)
            val recordingId = Uuid.random()
            transaction {
                ConferenceRecordingTable.insert {
                    it[id] = recordingId
                    it[roomId] = room
                    it[startedByMemberId] = organizer
                    it[startedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[stoppedAt] = null
                    it[readyAt] = null
                    it[status] = ConferenceRecordingStatus.RECORDING
                    it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                    it[documentId] = null
                    it[rawDir] = "x"
                    it[durationSeconds] = null
                    it[fileSizeBytes] = null
                    it[failureReason] = null
                    it[composeAttempts] = 0
                }
            }
            encounterApp {
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call -> service(call).updateEventCaptureEnabled(enabled = false, acknowledgedNoticeVersion = null) }
                    .failure<EventCaptureBusyException>()
                EventCaptureSettingsStore.currentValue().enabled shouldBe true
                // a recording that is only being composed (PROCESSING) captures nothing any more
                transaction {
                    ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                        it[status] = ConferenceRecordingStatus.PROCESSING
                    }
                }
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call -> service(call).updateEventCaptureEnabled(enabled = false, acknowledgedNoticeVersion = null) }
                    .getOrThrow()
                    .eventCaptureEnabled shouldBe false
            }
        }

        test("a real change writes one audit entry with only the switch, the version and the hash; the same value writes nothing") {
            EventCaptureTestState.publishConsent()
            encounterApp {
                val start = entries().size
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call ->
                        service(
                            call,
                        ).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = EVENT_CAPTURE_NOTICE_VERSION)
                    }.getOrThrow()
                entries().size shouldBe start + 1
                val entry = entries().first()
                entry[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                entry[AuditLogEntryTable.actorMemberId] shouldBe ADMIN_ID
                entry[AuditLogEntryTable.beforeSnapshot]!! shouldContain "\"eventCaptureEnabled\":false"
                val after = entry[AuditLogEntryTable.afterSnapshot]!!
                after shouldContain "\"eventCaptureEnabled\":true"
                after shouldContain "\"eventCaptureNoticeVersion\":\"$EVENT_CAPTURE_NOTICE_VERSION\""
                after shouldContain EventCaptureNotice.SHA256
                after shouldNotContain "@"
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call ->
                        service(
                            call,
                        ).updateEventCaptureEnabled(enabled = true, acknowledgedNoticeVersion = EVENT_CAPTURE_NOTICE_VERSION)
                    }.getOrThrow()
                entries().size shouldBe start + 1
            }
        }

        test("the generic updateOrganizationSettings leaves both columns untouched") {
            EventCaptureTestState.enabledWithText()
            encounterApp {
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call -> service(call).updateOrganizationSettings(OrganizationSettingsInput(name = "Generisch")) }
                    .getOrThrow()
                EventCaptureSettingsStore.currentValue().enabled shouldBe true
                EventCaptureTestState.instance(enabled = false, noticeVersion = null)
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call -> service(call).updateOrganizationSettings(OrganizationSettingsInput(name = "Generisch")) }
                    .getOrThrow()
                EventCaptureSettingsStore.currentValue().enabled shouldBe false
            }
        }

        test("the store answers off when nothing is set; start is allowed only for the current notice version") {
            EventCaptureTestState.reset()
            EventCaptureSettingsStore.currentValue().startAllowed() shouldBe false
            EventCaptureTestState.instance(enabled = true, noticeVersion = "0")
            EventCaptureSettingsStore.currentValue().startAllowed() shouldBe false
            EventCaptureTestState.instance(enabled = true)
            EventCaptureSettingsStore.currentValue().startAllowed() shouldBe true
        }
    })

private fun runRoom(
    fx: EventOnlineFixtures,
    live: EventFakeLiveKit,
    event: Uuid,
): Uuid {
    val provisioner =
        EventConferenceProvisioner(
            config = EVENT_ONLINE_CONFIG,
            liveKit = live,
            closer =
                network.lapis.cloud.server.rpc.ConferenceRoomCloser(
                    liveKitAdminClient = live,
                    whiteboardState =
                        network.lapis.cloud.server.conference
                            .ConferenceWhiteboardState(),
                    notesState =
                        network.lapis.cloud.server.conference
                            .ConferenceNotesState(),
                    audioGrantState =
                        network.lapis.cloud.server.conference
                            .ConferenceAudioGrantState(),
                ),
            retryDelay = 20.milliseconds,
            maxWaits = 20,
        )
    val ready =
        kotlinx.coroutines.runBlocking { provisioner.ensureRoom(eventId = event, now = kotlin.time.Instant.parse("2030-06-15T22:30:00Z")) }
    check(fx.eventIds.contains(event))
    return (ready as EventConferenceProvisioner.Ready.Room).roomId
}

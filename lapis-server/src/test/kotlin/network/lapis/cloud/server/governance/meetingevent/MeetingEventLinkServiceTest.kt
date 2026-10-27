package network.lapis.cloud.server.governance.meetingevent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.rpc.EventService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventSeriesEditScope
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MeetingEventLinkAuditKind
import network.lapis.cloud.shared.domain.MeetingEventLinkSnapshot
import network.lapis.cloud.shared.domain.MeetingEventUnlinkReason
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"
private const val NAME_PREFIX = "MEL-Test-"
private val MEETING_AT = LocalDateTime(2030, 5, 4, 19, 0)

/**
 * V1.9.227 -- the Sitzung <-> Veranstaltung link end to end over the real RPC services (throwaway `/op/{name}` route + `X-Member-Id`, the house
 * style of [network.lapis.cloud.server.rpc.GovernanceServiceTest]). Every fachlich refusal comes back as `ERR:<exception type>`, because Kilua
 * transports the type only.
 */
class MeetingEventLinkServiceTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val committeeIds = mutableListOf<Uuid>()
        val meetingIds = mutableListOf<Uuid>()
        val eventIds = mutableListOf<Uuid>()
        val seriesIds = mutableListOf<Uuid>()
        val bigLimiter = { FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes) }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                EventRegistrationTable.deleteWhere { EventRegistrationTable.eventId inList eventIds.toList() }
                val titled = EventTable.selectAll().where { EventTable.title like "$NAME_PREFIX%" }.map { it[EventTable.id] }
                val all = (eventIds + titled).distinct()
                if (all.isNotEmpty()) EventTable.deleteWhere { id inList all }
                if (seriesIds.isNotEmpty()) EventSeriesTable.deleteWhere { EventSeriesTable.id inList seriesIds }
                if (memberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
                }
                if (committeeIds.isNotEmpty()) {
                    MeetingTable.deleteWhere { MeetingTable.committeeId inList committeeIds }
                    CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
                    CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
                }
                if (memberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                    MemberTable.deleteWhere { MemberTable.id inList memberIds }
                }
            }
        }

        fun newMember(role: AccountRole = AccountRole.MEMBER): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MEL Testmitglied"
                    it[email] = "mel-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            memberIds += id
            return id
        }

        fun newCommittee(): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[name] = "$NAME_PREFIX${id.toString().take(8)}"
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "MEL"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[membershipMode] = CommitteeMembershipMode.MANUAL
                    it[ruleJson] = null
                }
            }
            committeeIds += id
            return id
        }

        fun seat(
            member: Uuid,
            committee: Uuid,
            role: CommitteeRole,
        ) = transaction {
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[CommitteeMembershipTable.role] = role
                it[since] = LocalDate(2020, 1, 1)
                it[until] = null
                it[committeeId] = committee
                it[memberId] = member
            }
        }

        fun newMeeting(
            committee: Uuid,
            format: MeetingFormat = MeetingFormat.IN_PERSON,
            location: String? = "Vereinsheim",
            at: LocalDateTime = MEETING_AT,
            status: MeetingStatus = MeetingStatus.PLANNED,
            title: String = "Sitzung",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[committeeId] = committee
                    it[MeetingTable.title] = title
                    it[scheduledAt] = at
                    it[MeetingTable.location] = location
                    it[MeetingTable.format] = format
                    it[MeetingTable.status] = status
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            meetingIds += id
            return id
        }

        fun newPublishedEvent(
            startsAt: LocalDateTime = MEETING_AT,
            location: String = "Anderswo",
            status: EventStatus = EventStatus.PUBLISHED,
            createdBy: String = BOARD_ID,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                EventStore.insertEvent(
                    id = id,
                    slug = "mel-${id.toString().take(12)}",
                    title = "$NAME_PREFIX-Ereignis ${id.toString().take(6)}",
                    description = "d",
                    locationText = location,
                    onlineUrl = null,
                    startsAt = startsAt,
                    endsAt = LocalDateTime(startsAt.year, startsAt.month, startsAt.day, startsAt.hour + 2, 0),
                    capacity = null,
                    feeAmount = BigDecimal.ZERO,
                    feeCurrency = "EUR",
                    visibility = EventVisibility.MEMBERS_ONLY,
                    registrationClosesAt = null,
                    createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                    createdBy = uuidOf(createdBy),
                    status = status,
                )
            }
            eventIds += id
            return id
        }

        /** Runs [block] against a throwaway app exposing both services under `/op/{name}`; the reply is the op's text or `ERR:<type>`. */
        fun withOps(block: suspend (suspend (actor: Any, name: String, params: String) -> String) -> Unit) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<Throwable> { call, cause ->
                            call.respondText("ERR:${cause::class.simpleName}")
                        }
                    }
                    routing {
                        post("/op/{name}") {
                            val gs = GovernanceService(call = call, meetingEventLinkRateLimiter = bigLimiter())
                            val es =
                                EventService(
                                    call = call,
                                    checkoutGateways = emptyMap(),
                                    baseUrl = "https://example.org",
                                    mailDispatcher =
                                        MailDispatcher(
                                            transport = QuietMailTransport,
                                            scope =
                                                CoroutineScope(
                                                    SupervisorJob() + Dispatchers.IO,
                                                ),
                                        ),
                                    writeRateLimiter = bigLimiter(),
                                    checkInRateLimiter = bigLimiter(),
                                    seriesPreviewRateLimiter = bigLimiter(),
                                )
                            val q = call.request.queryParameters
                            val out: String =
                                when (call.parameters["name"]) {
                                    "announce" -> gs.announceMeetingAsEvent(q["m"]!!).eventId
                                    "link" -> gs.linkMeetingToEvent(meetingId = q["m"]!!, eventId = q["e"]!!).eventId
                                    "unlink" -> {
                                        gs.unlinkMeetingEvent(meetingId = q["m"]!!, eventId = q["e"]!!)
                                        "ok"
                                    }
                                    "align" -> gs.alignEventToMeeting(meetingId = q["m"]!!, eventId = q["e"]!!).eventId
                                    "linkable" -> gs.listLinkableMeetings(q["e"]!!).joinToString(",") { it.id }
                                    "meetingStatus" ->
                                        gs
                                            .updateMeetingStatus(
                                                meetingId = q["m"]!!,
                                                status = MeetingStatus.valueOf(q["s"]!!),
                                            ).status.name
                                    "meeting" -> {
                                        val ref = gs.getMeetingDetail(q["m"]!!).meeting.linkedEvent
                                        if (ref == null) "none" else "${ref.eventId}|${ref.title}|${ref.canOpen}|${ref.difference}"
                                    }
                                    "listMeeting" -> {
                                        val ref =
                                            gs
                                                .listMeetings(
                                                    committeeId = null,
                                                    status = null,
                                                ).firstOrNull { it.id == q["m"]!! }
                                                ?.linkedEvent
                                        ref?.eventId ?: "none"
                                    }
                                    "getEvent" -> {
                                        val e = es.getEvent(q["e"]!!)
                                        "${e.startsAt}|${e.endsAt}|${e.locationText}|${e.linkedMeeting?.meetingId}|${e.linkedMeeting?.difference}"
                                    }
                                    "listEvents" ->
                                        es
                                            .listEvents(
                                                network.lapis.cloud.shared.domain
                                                    .EventQuery(includePast = true, limit = 200),
                                            ).rows
                                            .joinToString(",") { it.id }
                                    "moveEventRaw" -> {
                                        val start = LocalDateTime.parse(q["start"]!!)
                                        es
                                            .updateEvent(
                                                id = q["e"]!!,
                                                input =
                                                    EventInput(
                                                        title = "x",
                                                        description = "x",
                                                        locationText = "x",
                                                        startsAt = start,
                                                        endsAt = LocalDateTime(start.year, start.month, start.day, start.hour + 2, 0),
                                                        feeAmount = BigDecimal.ZERO,
                                                        visibility = EventVisibility.MEMBERS_ONLY,
                                                    ),
                                            ).status
                                            .name
                                    }
                                    "moveEvent" -> {
                                        val d = es.getEvent(q["e"]!!)
                                        val start = LocalDateTime.parse(q["start"]!!)
                                        val end = LocalDateTime(start.year, start.month, start.day, start.hour + 2, 0)
                                        es
                                            .updateEvent(
                                                id = q["e"]!!,
                                                input =
                                                    EventInput(
                                                        title = d.title,
                                                        description = d.description,
                                                        locationText = q["loc"] ?: d.locationText,
                                                        onlineUrl = d.onlineUrl,
                                                        startsAt = start,
                                                        endsAt = end,
                                                        capacity = d.capacity,
                                                        feeAmount = d.feeAmount,
                                                        feeCurrency = d.feeCurrency,
                                                        visibility = d.visibility,
                                                        registrationClosesAt = d.registrationClosesAt,
                                                        roomId = d.roomId,
                                                        summary = d.summary,
                                                        coverImageAlt = d.coverImageAlt,
                                                        onlineUrlPublic = d.onlineUrlPublic,
                                                        onlineKind = d.onlineKind,
                                                        encounterSpaceId = d.encounterSpaceId,
                                                    ),
                                            ).status
                                            .name
                                    }
                                    "publish" -> es.publishEvent(q["e"]!!).status.name
                                    "cancelSeries" ->
                                        es
                                            .cancelSeriesEvent(
                                                eventId = q["e"]!!,
                                                scope = EventSeriesEditScope.valueOf(q["scope"]!!),
                                                reason = "Test",
                                            ).affectedEventCount
                                            .toString()
                                    "cancelEvent" -> es.cancelEvent(id = q["e"]!!, reason = "Test").status.name
                                    else -> error("unknown op")
                                }
                            call.respondText(out)
                        }
                    }
                }
                val client: HttpClient = client
                block { actor, name, params ->
                    client.post("/op/$name?$params") { header("X-Member-Id", actor.toString()) }.bodyAsText()
                }
            }
        }

        fun linkOf(eventId: Uuid): Uuid? =
            transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.meetingId] }

        fun auditEntries(meetingId: Uuid): List<MeetingEventLinkSnapshot> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.MEETING_EVENT_LINK) and
                            (AuditLogEntryTable.entityId eq meetingId)
                    }.orderBy(AuditLogEntryTable.sequenceNumber)
                    .map { Json.decodeFromString<MeetingEventLinkSnapshot>(it[AuditLogEntryTable.afterSnapshot]!!) }
            }

        test("announce creates a DRAFT, MEMBERS_ONLY event with the fixed text, 2h long, linked; no room is touched; one audit entry") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val meeting = newMeeting(committee, title = "GEHEIM-MARKER-4711")
            transaction {
                // An agenda-like marker lives only on the Sitzung; the announcement must never carry it.
            }
            withOps { call ->
                val eventId = call(chair, "announce", "m=$meeting")
                eventId shouldNotBe ""
                eventId.startsWith("ERR") shouldBe false
                val row = transaction { EventTable.selectAll().where { EventTable.id eq Uuid.parse(eventId) }.single() }
                eventIds += Uuid.parse(eventId)
                row[EventTable.status] shouldBe EventStatus.DRAFT
                row[EventTable.visibility] shouldBe EventVisibility.MEMBERS_ONLY
                row[EventTable.onlineKind] shouldBe EventOnlineKind.NONE
                row[EventTable.startsAt] shouldBe MEETING_AT
                row[EventTable.endsAt] shouldBe LocalDateTime(2030, 5, 4, 21, 0)
                row[EventTable.locationText] shouldBe "Vereinsheim"
                row[EventTable.capacity] shouldBe null
                row[EventTable.meetingId] shouldBe meeting
                val committeeName =
                    transaction { CommitteeTable.selectAll().where { CommitteeTable.id eq committee }.single()[CommitteeTable.name] }
                row[EventTable.title] shouldBe "$committeeName: Sitzung am 04.05.2030"
                row[EventTable.description] shouldBe
                    "Sitzung des Gremiums $committeeName. Die Einladung mit Tagesordnung erhalten die Mitglieder des Gremiums gesondert."
                row[EventTable.title] shouldNotContain "GEHEIM"
                row[EventTable.description] shouldNotContain "GEHEIM"
                transaction {
                    EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq Uuid.parse(eventId) }.count() shouldBe 0L
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.meetingId eq meeting }.count() shouldBe 0L
                }
                val audit = auditEntries(meeting)
                audit.size shouldBe 1
                audit.single().kind shouldBe MeetingEventLinkAuditKind.ANNOUNCED
                audit.single().eventId shouldBe eventId
                call(chair, "announce", "m=$meeting") shouldBe "ERR:MeetingAlreadyLinkedException"
            }
        }

        test("announce: format / location rules and preconditions") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            withOps { call ->
                call(chair, "announce", "m=${newMeeting(committee, MeetingFormat.IN_PERSON, location = null)}") shouldBe
                    "ERR:MeetingLocationMissingException"
                // No video conferences on this test server: ONLINE needs a place, with one it degrades to NONE.
                call(chair, "announce", "m=${newMeeting(committee, MeetingFormat.ONLINE, location = null)}") shouldBe
                    "ERR:MeetingLocationMissingException"
                val hybrid = call(chair, "announce", "m=${newMeeting(committee, MeetingFormat.HYBRID, location = "Saal")}")
                hybrid.startsWith("ERR") shouldBe false
                eventIds += Uuid.parse(hybrid)
                transaction {
                    EventTable
                        .selectAll()
                        .where {
                            EventTable.id eq
                                Uuid.parse(
                                    hybrid,
                                )
                        }.single()[EventTable.onlineKind]
                } shouldBe
                    EventOnlineKind.NONE
                call(chair, "announce", "m=${newMeeting(committee, status = MeetingStatus.CANCELLED)}") shouldBe
                    "ERR:MeetingNotAnnounceableException"
                call(chair, "announce", "m=${newMeeting(committee, status = MeetingStatus.HELD)}") shouldBe
                    "ERR:MeetingNotAnnounceableException"
                call(chair, "announce", "m=${newMeeting(committee, at = LocalDateTime(2020, 1, 1, 10, 0))}") shouldBe
                    "ERR:MeetingNotAnnounceableException"
            }
        }

        test("with video conferences available an online / hybrid Sitzung becomes a LAPIS_CONFERENCE event (no room is created)") {
            val committee = newCommittee()
            val meeting = newMeeting(committee, MeetingFormat.ONLINE, location = null)
            val ops = MeetingEventLinkOperations(conferencesEnabled = { true }, rateLimiter = bigLimiter())
            val ref =
                ops.announce(
                    current = CurrentMember(memberId = uuidOf(BOARD_ID), role = AccountRole.BOARD, status = MemberStatus.ACTIVE),
                    meetingId = meeting,
                )
            eventIds += Uuid.parse(ref.eventId)
            ref.onlineKind shouldBe EventOnlineKind.LAPIS_CONFERENCE
            transaction {
                EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq Uuid.parse(ref.eventId) }.count() shouldBe 0L
            }
        }

        test("link, unlink and the 1:1 rule in both directions; a stale view is refused; a HELD Sitzung may be linked") {
            val committee = newCommittee()
            val meetingA = newMeeting(committee)
            val meetingB = newMeeting(committee)
            val held = newMeeting(committee, status = MeetingStatus.HELD, at = LocalDateTime(2020, 1, 1, 10, 0))
            val event1 = newPublishedEvent()
            val event2 = newPublishedEvent()
            withOps { call ->
                call(BOARD_ID, "link", "m=$meetingA&e=$event1") shouldBe event1.toString()
                linkOf(event1) shouldBe meetingA
                call(BOARD_ID, "link", "m=$meetingA&e=$event2") shouldBe "ERR:MeetingAlreadyLinkedException"
                call(BOARD_ID, "link", "m=$meetingB&e=$event1") shouldBe "ERR:EventAlreadyLinkedException"
                // The unique index backs the rule even against a direct write.
                runCatching {
                    transaction {
                        EventTable.update(
                            { EventTable.id eq event2 },
                        ) { it[meetingId] = meetingA }
                    }
                }.isFailure shouldBe
                    true
                call(BOARD_ID, "unlink", "m=$meetingB&e=$event1") shouldBe "ERR:MeetingEventLinkStaleException"
                call(BOARD_ID, "unlink", "m=$meetingA&e=$event1") shouldBe "ok"
                linkOf(event1) shouldBe null
                transaction { MeetingTable.selectAll().where { MeetingTable.id eq meetingA }.count() } shouldBe 1L
                transaction { EventTable.selectAll().where { EventTable.id eq event1 }.count() } shouldBe 1L
                call(BOARD_ID, "unlink", "m=$meetingA&e=$event1") shouldBe "ERR:MeetingEventLinkStaleException"
                call(BOARD_ID, "link", "m=$held&e=$event1") shouldBe event1.toString()
                val kinds = auditEntries(meetingA).map { it.kind }
                kinds shouldBe listOf(MeetingEventLinkAuditKind.LINKED, MeetingEventLinkAuditKind.UNLINKED)
                auditEntries(meetingA).last().reason shouldBe MeetingEventUnlinkReason.MANUAL
            }
        }

        test("difference and align: minute-exact start, case-insensitive place, only the event's date and place change") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val meeting = newMeeting(committee, location = "Vereinsheim")
            val event = newPublishedEvent(startsAt = MEETING_AT, location = "  VEREINSHEIM ", createdBy = chair.toString())
            withOps { call ->
                call(BOARD_ID, "link", "m=$meeting&e=$event")
                call(chair, "meeting", "m=$meeting") shouldContain "startsAtDiffers=false, locationDiffers=false"
                call(BOARD_ID, "moveEvent", "e=$event&start=2030-05-04T18:30") shouldBe "PUBLISHED"
                linkOf(event) shouldBe meeting // an edit never touches the link
                call(chair, "meeting", "m=$meeting") shouldContain "startsAtDiffers=true"
                call(chair, "meeting", "m=$meeting") shouldContain "alignable=true"
                call(BOARD_ID, "getEvent", "e=$event") shouldContain "startsAtDiffers=true"
                call(chair, "align", "m=$meeting&e=$event") shouldBe event.toString()
                val after = call(BOARD_ID, "getEvent", "e=$event")
                after shouldContain "2030-05-04T19:00"
                after shouldContain "2030-05-04T20:30" // the event keeps its own 1h30 duration
                call(chair, "meeting", "m=$meeting") shouldContain "startsAtDiffers=false"
                auditEntries(meeting).last().kind shouldBe MeetingEventLinkAuditKind.ALIGNED
                // Nothing differs any more -> nothing to align.
                call(chair, "align", "m=$meeting&e=$event") shouldBe "ERR:MeetingNotAnnounceableException"
            }
        }

        test("bridge: an officer re-dates the event of their own Sitzung, but not a public one; BOARD may either") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val meeting = newMeeting(committee, at = LocalDateTime(2030, 6, 1, 10, 0), location = "Irgendwo")
            val event = newPublishedEvent(startsAt = MEETING_AT, location = "Mitgliederversammlung-Saal")
            withOps { call ->
                // V1.9.229: linking an existing event is BOARD/ADMIN only.
                call(chair, "link", "m=$meeting&e=$event") shouldBe "ERR:ForbiddenException"
                call(BOARD_ID, "link", "m=$meeting&e=$event") shouldBe event.toString()
                // The view offers what the server allows: alignable for a members-only event ...
                call(chair, "meeting", "m=$meeting") shouldContain "startsAtDiffers=true"
                call(chair, "meeting", "m=$meeting") shouldContain "alignable=true"
                // ... and not for a public one.
                transaction { EventTable.update({ EventTable.id eq event }) { it[visibility] = EventVisibility.PUBLIC } }
                call(chair, "meeting", "m=$meeting") shouldContain "alignable=false"
                call(BOARD_ID, "meeting", "m=$meeting") shouldContain "alignable=true"
                call(chair, "align", "m=$meeting&e=$event") shouldBe "ERR:MeetingEventVisibilityReservedException"
                val unchanged = call(BOARD_ID, "getEvent", "e=$event")
                unchanged shouldContain "2030-05-04T19:00"
                unchanged shouldContain "Mitgliederversammlung-Saal"
                auditEntries(meeting).none { it.kind == MeetingEventLinkAuditKind.ALIGNED } shouldBe true
                transaction { EventTable.update({ EventTable.id eq event }) { it[visibility] = EventVisibility.MEMBERS_ONLY } }
                call(chair, "align", "m=$meeting&e=$event") shouldBe event.toString()
                call(BOARD_ID, "getEvent", "e=$event") shouldContain "2030-06-01T10:00"
                auditEntries(meeting).last { it.kind == MeetingEventLinkAuditKind.ALIGNED }.officerRole shouldBe CommitteeRole.CHAIR
            }
        }

        test(
            "a series cancellation dissolves the link of a detached, linked occurrence (cancelled with registrations, hard-deleted without)",
        ) {
            val committee = newCommittee()
            val meetingB = newMeeting(committee)
            val meetingC = newMeeting(committee)
            val clicked = newPublishedEvent()
            val withRegistration = newPublishedEvent()
            val withoutRegistration = newPublishedEvent()
            val series = Uuid.random()
            val second = LocalDateTime(2030, 5, 11, 19, 0)
            val third = LocalDateTime(2030, 5, 18, 19, 0)
            transaction {
                EventStore.insertSeries(
                    id = series,
                    rrule = "FREQ=WEEKLY;INTERVAL=1;BYDAY=SA;COUNT=3",
                    dtstart = MEETING_AT,
                    timezone = "Europe/Berlin",
                    durationMinutes = 120,
                    splitFromSeriesId = null,
                    createdBy = uuidOf(BOARD_ID),
                    createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                )
                seriesIds += series
                listOf(Triple(clicked, MEETING_AT, false), Triple(withRegistration, second, true), Triple(withoutRegistration, third, true))
                    .forEach { (id, original, detached) ->
                        EventTable.update({ EventTable.id eq id }) {
                            it[seriesId] = series
                            it[seriesOriginalStart] = original
                            it[seriesDetached] = detached
                        }
                    }
                EventStore.insertRegistration(
                    id = Uuid.random(),
                    eventId = withRegistration,
                    memberId = uuidOf(MEMBER_ID),
                    guestName = null,
                    guestEmail = null,
                    activeParticipantKey = "m:$MEMBER_ID",
                    status = EventRegistrationStatus.CONFIRMED,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = null,
                    waitlistPosition = null,
                    cancelTokenSha256 = null,
                    registeredAt = LocalDateTime(2026, 1, 1, 0, 0),
                    confirmedAt = LocalDateTime(2026, 1, 1, 0, 0),
                )
            }
            withOps { call ->
                call(BOARD_ID, "link", "m=$meetingB&e=$withRegistration") shouldBe withRegistration.toString()
                call(BOARD_ID, "link", "m=$meetingC&e=$withoutRegistration") shouldBe withoutRegistration.toString()
                call(BOARD_ID, "cancelSeries", "e=$clicked&scope=ALL") shouldBe "3"
                transaction { EventTable.selectAll().where { EventTable.id eq withRegistration }.single()[EventTable.status] } shouldBe
                    EventStatus.CANCELLED
                linkOf(withRegistration) shouldBe null
                auditEntries(meetingB).last().kind shouldBe MeetingEventLinkAuditKind.UNLINKED
                auditEntries(meetingB).last().reason shouldBe MeetingEventUnlinkReason.EVENT_CANCELLED
                // Hard-deleted: the row is gone, the FK (ON DELETE SET NULL) never blocks it, and no dangling link remains.
                transaction { EventTable.selectAll().where { EventTable.id eq withoutRegistration }.count() } shouldBe 0L
                transaction { MeetingTable.selectAll().where { MeetingTable.id eq meetingC }.count() } shouldBe 1L
            }
            transaction { EventRegistrationTable.deleteWhere { EventRegistrationTable.eventId eq withRegistration } }
        }

        test("a Sitzung in the past is not alignable") {
            val committee = newCommittee()
            val meeting = newMeeting(committee, at = LocalDateTime(2020, 1, 1, 10, 0))
            val event = newPublishedEvent()
            withOps { call ->
                call(BOARD_ID, "link", "m=$meeting&e=$event")
                call(BOARD_ID, "align", "m=$meeting&e=$event") shouldBe "ERR:MeetingNotAnnounceableException"
            }
        }

        test("lifecycle: cancelling either side dissolves the link in the same step, HELD keeps it, edits keep it") {
            val committee = newCommittee()
            val meetingA = newMeeting(committee)
            val meetingB = newMeeting(committee)
            val meetingC = newMeeting(committee)
            val eventA = newPublishedEvent()
            val eventB = newPublishedEvent()
            val eventC = newPublishedEvent()
            withOps { call ->
                call(BOARD_ID, "link", "m=$meetingA&e=$eventA")
                call(BOARD_ID, "link", "m=$meetingB&e=$eventB")
                call(BOARD_ID, "link", "m=$meetingC&e=$eventC")
                call(BOARD_ID, "meetingStatus", "m=$meetingA&s=CANCELLED") shouldBe "CANCELLED"
                linkOf(eventA) shouldBe null
                transaction { EventTable.selectAll().where { EventTable.id eq eventA }.single()[EventTable.status] } shouldBe
                    EventStatus.PUBLISHED
                auditEntries(meetingA).last().reason shouldBe MeetingEventUnlinkReason.MEETING_CANCELLED
                call(BOARD_ID, "cancelEvent", "e=$eventB") shouldBe "CANCELLED"
                linkOf(eventB) shouldBe null
                transaction { MeetingTable.selectAll().where { MeetingTable.id eq meetingB }.single()[MeetingTable.status] } shouldBe
                    MeetingStatus.PLANNED
                auditEntries(meetingB).last().reason shouldBe MeetingEventUnlinkReason.EVENT_CANCELLED
                call(BOARD_ID, "meetingStatus", "m=$meetingC&s=HELD") shouldBe "HELD"
                linkOf(eventC) shouldBe meetingC
            }
        }

        test(
            "rights matrix: officers of the OWN committee announce and unlink, BOARD/ADMIN also link; other committee, plain members, treasurer may not",
        ) {
            val own = newCommittee()
            val foreign = newCommittee()
            val meeting = newMeeting(own)
            val allowedRoles =
                listOf(CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.GENERAL_SECRETARY, CommitteeRole.SECRETARY)
            val deniedRoles = listOf(CommitteeRole.MEMBER, CommitteeRole.ASSESSOR, CommitteeRole.PRESS_SPOKESPERSON)
            val event = newPublishedEvent()
            withOps { call ->
                deniedRoles.forEach { role ->
                    val m = newMember().also { seat(it, own, role) }
                    call(m, "announce", "m=$meeting") shouldBe "ERR:ForbiddenException"
                    call(m, "link", "m=$meeting&e=$event") shouldBe "ERR:ForbiddenException"
                    call(m, "unlink", "m=$meeting&e=$event") shouldBe "ERR:ForbiddenException"
                    call(m, "align", "m=$meeting&e=$event") shouldBe "ERR:ForbiddenException"
                }
                val foreignChair = newMember().also { seat(it, foreign, CommitteeRole.CHAIR) }
                call(foreignChair, "announce", "m=$meeting") shouldBe "ERR:ForbiddenException"
                call(foreignChair, "link", "m=$meeting&e=$event") shouldBe "ERR:ForbiddenException"
                call(MEMBER_ID, "announce", "m=$meeting") shouldBe "ERR:ForbiddenException"
                call(TREASURER_ID, "announce", "m=$meeting") shouldBe "ERR:ForbiddenException"
                call(uuidOf("00000000-0000-0000-0000-0000000000ff"), "announce", "m=$meeting").startsWith("ERR") shouldBe true
                // Allowed: each role announces / links / unlinks its own committee's Sitzung (a fresh one each time).
                allowedRoles.forEach { role ->
                    val officer = newMember().also { seat(it, own, role) }
                    val m = newMeeting(own)
                    val e = newPublishedEvent()
                    // V1.9.229: an officer no longer links an existing event; the board does, the officer may dissolve it.
                    call(officer, "link", "m=$m&e=$e") shouldBe "ERR:ForbiddenException"
                    call(BOARD_ID, "link", "m=$m&e=$e") shouldBe e.toString()
                    call(officer, "unlink", "m=$m&e=$e") shouldBe "ok"
                    val announced = call(officer, "announce", "m=$m")
                    announced.startsWith("ERR") shouldBe false
                    eventIds += Uuid.parse(announced)
                }
                listOf(BOARD_ID, ADMIN_ID).forEach { actor ->
                    val m = newMeeting(own)
                    val announced = call(actor, "announce", "m=$m")
                    announced.startsWith("ERR") shouldBe false
                    eventIds += Uuid.parse(announced)
                }
            }
        }

        test("bridge: a chair without a board role manages the event of their Sitzung; members see it only once published") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val meeting = newMeeting(committee)
            val other = newMeeting(committee)
            withOps { call ->
                val eventId = call(chair, "announce", "m=$meeting")
                eventIds += Uuid.parse(eventId)
                call(chair, "getEvent", "e=$eventId") shouldContain "2030-05-04T19:00"
                call(MEMBER_ID, "getEvent", "e=$eventId") shouldBe "ERR:NotFoundException"
                call(chair, "moveEventRaw", "e=$eventId&start=2030-05-04T18:30") shouldBe "DRAFT"
                // Linking is the board's; probing an unpublished foreign event reveals nothing.
                call(chair, "link", "m=$other&e=$eventId") shouldBe "ERR:ForbiddenException"
                call(chair, "linkable", "e=$eventId") shouldBe "ERR:ForbiddenException"
                call(MEMBER_ID, "listEvents", "") shouldNotContain eventId
                call(BOARD_ID, "listEvents", "") shouldContain eventId
                // The Sitzung page: the chair sees the real title and may publish, a plain member only the neutral placeholder.
                call(chair, "meeting", "m=$meeting") shouldContain "|true|"
                call(chair, "meeting", "m=$meeting") shouldContain "|x|true|"
                call(MEMBER_ID, "meeting", "m=$meeting") shouldContain "Veranstaltung (Entwurf)|false|null"
                call(MEMBER_ID, "meeting", "m=$meeting") shouldNotContain "|x|"
                call(chair, "publish", "e=$eventId") shouldBe "PUBLISHED"
                call(MEMBER_ID, "meeting", "m=$meeting") shouldContain "|x|true"
                call(MEMBER_ID, "listMeeting", "m=$meeting") shouldBe eventId
                call(chair, "cancelEvent", "e=$eventId") shouldBe "CANCELLED"
                linkOf(Uuid.parse(eventId)) shouldBe null
                // The link is gone, so is the officer's access.
                call(chair, "getEvent", "e=$eventId") shouldBe "ERR:NotFoundException"
            }
        }

        test("listLinkableMeetings: BOARD/ADMIN only; free, not cancelled Sitzungen, newest first") {
            val committee = newCommittee()
            val foreign = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val a = newMeeting(committee, at = LocalDateTime(2030, 6, 1, 10, 0))
            val b = newMeeting(committee, at = LocalDateTime(2030, 7, 1, 10, 0))
            val cancelled = newMeeting(committee, status = MeetingStatus.CANCELLED)
            val linked = newMeeting(committee)
            val foreignMeeting = newMeeting(foreign)
            val linkedEvent = newPublishedEvent()
            val event = newPublishedEvent()
            withOps { call ->
                call(BOARD_ID, "link", "m=$linked&e=$linkedEvent")
                // V1.9.229: BOARD/ADMIN only; an officer or a plain member is refused.
                call(chair, "linkable", "e=$event") shouldBe "ERR:ForbiddenException"
                call(MEMBER_ID, "linkable", "e=$event") shouldBe "ERR:ForbiddenException"
                val list = call(BOARD_ID, "linkable", "e=$event").split(",")
                list shouldContain a.toString()
                list shouldContain b.toString()
                list shouldContain foreignMeeting.toString()
                (cancelled.toString() in list) shouldBe false
                (linked.toString() in list) shouldBe false
                (list.indexOf(b.toString()) < list.indexOf(a.toString())) shouldBe true
                call(BOARD_ID, "linkable", "e=$linkedEvent") shouldBe ""
            }
        }

        test("an event of a running series cannot be linked") {
            val committee = newCommittee()
            val meeting = newMeeting(committee)
            val event = newPublishedEvent()
            transaction {
                val series = Uuid.random()
                // A series row is needed for the FK; reuse the store helper the series tests use.
                EventStore.insertSeries(
                    id = series,
                    rrule = "FREQ=WEEKLY;COUNT=2",
                    dtstart = MEETING_AT,
                    timezone = "Europe/Berlin",
                    durationMinutes = 60,
                    splitFromSeriesId = null,
                    createdBy = uuidOf(BOARD_ID),
                    createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                )
                seriesIds += series
                EventTable.update({ EventTable.id eq event }) {
                    it[seriesId] = series
                    it[seriesOriginalStart] = MEETING_AT
                    it[seriesDetached] = false
                }
            }
            withOps { call ->
                call(BOARD_ID, "link", "m=$meeting&e=$event") shouldBe "ERR:EventNotLinkableException"
            }
            transaction {
                EventTable.update({ EventTable.id eq event }) {
                    it[seriesId] = null
                    it[seriesOriginalStart] = null
                }
            }
        }
    })

private fun uuidOf(value: String): Uuid = Uuid.parse(value)

private object QuietMailTransport : MailTransport {
    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome = MailSendOutcome.Sent
}

package network.lapis.cloud.server.governance.meetingevent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.rpc.EventService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.MeetingEventGrant
import network.lapis.cloud.server.security.activeRecordingRole
import network.lapis.cloud.server.security.meetingEventGrant
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MeetingEventField
import network.lapis.cloud.shared.domain.MeetingEventLinkAuditKind
import network.lapis.cloud.shared.domain.MeetingEventLinkSnapshot
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
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

private const val PREFIX = "MOG-Test-"
private val MEETING_AT = LocalDateTime(2030, 5, 4, 19, 0)

/**
 * V1.9.229 -- an officer (Vorsitz, Schriftfuehrung, ...) of a committee manages the event announcing THEIR Sitzung: rights matrix, the
 * allow-list of `updateEvent`, uniform NotFound for everything outside the grant, the audit entries and the end of the grant (office ended,
 * link dissolved). Same house style as [MeetingEventLinkServiceTest]: a throwaway `/op/{name}` route + `X-Member-Id`, refusals as `ERR:<type>`.
 */
class MeetingEventOfficerGrantTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val committeeIds = mutableListOf<Uuid>()
        val eventIds = mutableListOf<Uuid>()
        val bigLimiter = { FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes) }

        beforeSpec {
            DatabaseConfig.connect()
            // No demo seed: the shared in-memory database may already hold other specs' members (seedIfEmpty would then skip), so this spec
            // creates every member it needs itself.
        }

        afterSpec {
            transaction {
                EventRegistrationTable.deleteWhere { EventRegistrationTable.eventId inList eventIds.toList() }
                val titled = EventTable.selectAll().where { EventTable.title like "$PREFIX%" }.map { it[EventTable.id] }
                val all = (eventIds + titled).distinct()
                if (all.isNotEmpty()) EventTable.deleteWhere { id inList all }
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
                    it[displayName] = "MOG Testmitglied"
                    it[email] = "mog-$id@example.org"
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

        val boardId by lazy { newMember(AccountRole.BOARD) }
        val adminId by lazy { newMember(AccountRole.ADMIN) }
        val treasurerId by lazy { newMember(AccountRole.TREASURER) }
        val plainMemberId by lazy { newMember() }

        fun newCommittee(): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[name] = "$PREFIX${id.toString().take(8)}"
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "MOG"
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
            since: LocalDate = LocalDate(2020, 1, 1),
            until: LocalDate? = null,
        ): Uuid {
            val rowId = Uuid.random()
            transaction {
                CommitteeMembershipTable.insert {
                    it[id] = rowId
                    it[CommitteeMembershipTable.role] = role
                    it[CommitteeMembershipTable.since] = since
                    it[CommitteeMembershipTable.until] = until
                    it[committeeId] = committee
                    it[memberId] = member
                }
            }
            return rowId
        }

        fun newMeeting(committee: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[committeeId] = committee
                    it[title] = "Sitzung"
                    it[scheduledAt] = MEETING_AT
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            return id
        }

        /** An event linked to [meeting] (or unlinked if null). */
        fun newEvent(
            meeting: Uuid?,
            status: EventStatus = EventStatus.DRAFT,
            visibility: EventVisibility = EventVisibility.MEMBERS_ONLY,
            fee: BigDecimal = BigDecimal.ZERO,
            capacity: Int? = null,
            summary: String? = null,
            onlineUrl: String? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                EventStore.insertEvent(
                    id = id,
                    slug = "mog-${id.toString().take(12)}",
                    title = "$PREFIX-Ereignis ${id.toString().take(6)}",
                    description = "Beschreibung",
                    locationText = "Vereinsheim",
                    onlineUrl = onlineUrl,
                    startsAt = MEETING_AT,
                    endsAt = LocalDateTime(2030, 5, 4, 21, 0),
                    capacity = capacity,
                    feeAmount = fee,
                    feeCurrency = "EUR",
                    visibility = visibility,
                    registrationClosesAt = null,
                    createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                    createdBy = boardId,
                    status = status,
                    onlineKind = if (onlineUrl != null) EventOnlineKind.EXTERNAL_LINK else EventOnlineKind.NONE,
                    summary = summary,
                )
                if (meeting != null) MeetingEventLinkStore.setLink(eventId = id, meetingId = meeting)
            }
            eventIds += id
            return id
        }

        fun withOps(block: suspend (suspend (actor: Any, name: String, params: String) -> String) -> Unit) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<Throwable> { call, cause -> call.respondText("ERR:${cause::class.simpleName}|${cause.message}") }
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
                                            transport = OfficerQuietMailTransport,
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
                                    "get" -> es.getEvent(q["e"]!!).let { "${it.status}|${it.manageScope}|${it.visibility}" }
                                    "update" -> {
                                        val d = es.getEvent(q["e"]!!)
                                        val input =
                                            EventInput(
                                                title = q["title"] ?: d.title,
                                                description = q["desc"] ?: d.description,
                                                locationText = q["loc"] ?: d.locationText,
                                                onlineUrl = if (q["url"] != null) q["url"] else d.onlineUrl,
                                                startsAt = d.startsAt,
                                                endsAt = d.endsAt,
                                                capacity = q["cap"]?.toInt() ?: d.capacity,
                                                feeAmount = q["fee"]?.let { BigDecimal(it) } ?: d.feeAmount,
                                                feeCurrency = d.feeCurrency,
                                                visibility = q["vis"]?.let { EventVisibility.valueOf(it) } ?: d.visibility,
                                                registrationClosesAt = d.registrationClosesAt,
                                                roomId = d.roomId,
                                                summary = if (q["summary"] != null) q["summary"] else d.summary,
                                                coverImageAlt = d.coverImageAlt,
                                                onlineUrlPublic = q["urlPublic"]?.toBooleanStrict() ?: d.onlineUrlPublic,
                                                onlineKind = q["kind"]?.let { EventOnlineKind.valueOf(it) } ?: d.onlineKind,
                                                encounterSpaceId = d.encounterSpaceId,
                                            )
                                        es.updateEvent(id = q["e"]!!, input = input).title
                                    }
                                    "publish" -> es.publishEvent(q["e"]!!).status.name
                                    "cancel" -> es.cancelEvent(id = q["e"]!!, reason = "Test").status.name
                                    "align" -> gs.alignEventToMeeting(meetingId = q["m"]!!, eventId = q["e"]!!).eventId
                                    "unlink" -> {
                                        gs.unlinkMeetingEvent(meetingId = q["m"]!!, eventId = q["e"]!!)
                                        "ok"
                                    }
                                    "link" -> gs.linkMeetingToEvent(meetingId = q["m"]!!, eventId = q["e"]!!).eventId
                                    "linkable" -> gs.listLinkableMeetings(q["e"]!!).size.toString()
                                    "listRegistrations" -> es.listRegistrations(q["e"]!!).size.toString()
                                    "sweep" -> es.sweepEvent(q["e"]!!).id
                                    "capture" -> {
                                        es.setEventCaptureAllowed(eventId = q["e"]!!, allowed = true)
                                        "ok"
                                    }
                                    "create" ->
                                        es
                                            .createEvent(
                                                EventInput(
                                                    title = "$PREFIX-neu",
                                                    description = "d",
                                                    startsAt = LocalDateTime(2031, 1, 1, 10, 0),
                                                    endsAt = LocalDateTime(2031, 1, 1, 12, 0),
                                                    feeAmount = BigDecimal.ZERO,
                                                    visibility = EventVisibility.MEMBERS_ONLY,
                                                ),
                                            ).id
                                    else -> error("unknown op")
                                }
                            call.respondText(out)
                        }
                    }
                }
                val client: HttpClient = client
                block { actor, name, params -> client.post("/op/$name?$params") { header("X-Member-Id", actor.toString()) }.bodyAsText() }
            }
        }

        fun status(eventId: Uuid) = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.status] }

        fun row(eventId: Uuid) = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single() }

        fun audit(meetingId: Uuid): List<MeetingEventLinkSnapshot> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.MEETING_EVENT_LINK) and (AuditLogEntryTable.entityId eq meetingId)
                    }.orderBy(AuditLogEntryTable.sequenceNumber)
                    .map { Json.decodeFromString<MeetingEventLinkSnapshot>(it[AuditLogEntryTable.afterSnapshot]!!) }
            }

        val officerRoles =
            listOf(CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY, CommitteeRole.MANAGING_DIRECTOR)

        test(
            "rights matrix: every recording office manages the event of its Sitzung; plain seats, foreign officers, members, treasurer do not",
        ) {
            val own = newCommittee()
            val foreign = newCommittee()
            withOps { call ->
                officerRoles.forEach { role ->
                    val officer = newMember().also { seat(it, own, role) }
                    val meeting = newMeeting(own)
                    val event = newEvent(meeting)
                    call(officer, "get", "e=$event") shouldBe "DRAFT|MEETING_OFFICER|MEMBERS_ONLY"
                    call(officer, "update", "e=$event&title=${PREFIX}Neu") shouldBe "${PREFIX}Neu"
                    call(officer, "publish", "e=$event") shouldBe "PUBLISHED"
                    status(event) shouldBe EventStatus.PUBLISHED
                    call(officer, "cancel", "e=$event") shouldBe "CANCELLED"
                    val kinds = audit(meeting).map { it.kind }
                    kinds shouldBe
                        listOf(
                            MeetingEventLinkAuditKind.OFFICER_UPDATED,
                            MeetingEventLinkAuditKind.OFFICER_PUBLISHED,
                            MeetingEventLinkAuditKind.OFFICER_CANCELLED,
                            MeetingEventLinkAuditKind.UNLINKED,
                        )
                    audit(meeting).first().officerRole shouldBe role
                }
                val meeting = newMeeting(own)
                val event = newEvent(meeting)
                val denied =
                    listOf<Any>(
                        newMember().also { seat(it, own, CommitteeRole.MEMBER) },
                        newMember().also { seat(it, own, CommitteeRole.ASSESSOR) },
                        newMember().also { seat(it, own, CommitteeRole.PRESS_SPOKESPERSON) },
                        newMember().also { seat(it, foreign, CommitteeRole.CHAIR) },
                        plainMemberId,
                        treasurerId,
                    )
                val unknown = call(plainMemberId, "publish", "e=${Uuid.random()}")
                unknown shouldContain "ERR:NotFoundException"
                denied.forEach { actor ->
                    call(actor, "get", "e=$event") shouldBe "ERR:NotFoundException|Event $event not found"
                    call(actor, "update", "e=$event&title=x") shouldContain "ERR:NotFoundException"
                    call(actor, "publish", "e=$event") shouldBe "ERR:NotFoundException|Event $event not found"
                    call(actor, "cancel", "e=$event") shouldContain "ERR:NotFoundException"
                }
                status(event) shouldBe EventStatus.DRAFT
                listOf(boardId, adminId).forEach { call(it, "get", "e=$event") shouldBe "DRAFT|FULL|MEMBERS_ONLY" }
            }
        }

        test("an officer cannot reach any other event method (registrations, sweep, capture, create, link, linkable)") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val meeting = newMeeting(committee)
            val event = newEvent(meeting, status = EventStatus.PUBLISHED)
            val other = newMeeting(committee)
            withOps { call ->
                call(chair, "listRegistrations", "e=$event") shouldContain "ERR:ForbiddenException"
                call(chair, "sweep", "e=$event") shouldContain "ERR:ForbiddenException"
                call(chair, "capture", "e=$event") shouldContain "ERR:ForbiddenException"
                call(chair, "create", "") shouldContain "ERR:ForbiddenException"
                call(chair, "link", "m=$other&e=${newEvent(null, status = EventStatus.PUBLISHED)}") shouldContain "ERR:ForbiddenException"
                call(chair, "linkable", "e=$event") shouldContain "ERR:ForbiddenException"
            }
        }

        test("IDOR: an unlinked event, an event of a foreign committee and a random id are indistinguishable") {
            val own = newCommittee()
            val foreign = newCommittee()
            val chair = newMember().also { seat(it, own, CommitteeRole.CHAIR) }
            val unlinked = newEvent(null)
            val foreignEvent = newEvent(newMeeting(foreign))
            withOps { call ->
                val random = Uuid.random()
                val reference = call(chair, "update", "e=$random&title=x").replace(random.toString(), "ID")
                reference shouldContain "ERR:NotFoundException"
                listOf(unlinked, foreignEvent).forEach { e ->
                    listOf("get", "update", "publish", "cancel").forEach { op ->
                        val params = if (op == "update") "e=$e&title=x" else "e=$e"
                        val ref = call(chair, op, "e=$random" + if (op == "update") "&title=x" else "")
                        call(chair, op, params).replace(e.toString(), "ID") shouldBe ref.replace(random.toString(), "ID")
                    }
                }
                status(unlinked) shouldBe EventStatus.DRAFT
                status(foreignEvent) shouldBe EventStatus.DRAFT
            }
        }

        test("the grant ends with the office and with the link") {
            val committee = newCommittee()
            val chair = newMember()
            val seatId = seat(chair, committee, CommitteeRole.CHAIR)
            val meeting = newMeeting(committee)
            val event = newEvent(meeting)
            withOps { call ->
                call(chair, "get", "e=$event") shouldContain "MEETING_OFFICER"
                transaction {
                    CommitteeMembershipTable.update({ CommitteeMembershipTable.id eq seatId }) {
                        it[until] = LocalDate(2020, 6, 1)
                    }
                }
                call(chair, "get", "e=$event") shouldContain "ERR:NotFoundException"
                call(chair, "publish", "e=$event") shouldContain "ERR:NotFoundException"
                transaction {
                    CommitteeMembershipTable.update({ CommitteeMembershipTable.id eq seatId }) { it[until] = null }
                }
                call(chair, "publish", "e=$event") shouldBe "PUBLISHED"
                // A second actor dissolves the link: the officer has no access any more.
                call(boardId, "unlink", "m=$meeting&e=$event") shouldBe "ok"
                call(chair, "cancel", "e=$event") shouldContain "ERR:NotFoundException"
                // A PUBLISHED, MEMBERS_ONLY event stays readable for members, but not manageable.
                call(chair, "get", "e=$event") shouldBe "PUBLISHED|NONE|MEMBERS_ONLY"
            }
        }

        test("refusals: public visibility, every reserved field, paid cancellation, public events") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val meeting = newMeeting(committee)
            val event = newEvent(meeting, fee = BigDecimal("10.00"), summary = "Kurz", onlineUrl = "https://example.org/x")
            withOps { call ->
                call(chair, "update", "e=$event&vis=PUBLIC") shouldContain "ERR:MeetingEventVisibilityReservedException"
                call(chair, "update", "e=$event&cap=5") shouldContain "ERR:MeetingEventFieldReservedException"
                call(chair, "update", "e=$event&fee=12.00") shouldContain "ERR:MeetingEventFieldReservedException"
                call(chair, "update", "e=$event&summary=Anders") shouldContain "ERR:MeetingEventFieldReservedException"
                call(chair, "update", "e=$event&urlPublic=true") shouldContain "ERR:MeetingEventFieldReservedException"
                call(chair, "update", "e=$event&kind=LAPIS_ENCOUNTER") shouldContain "ERR:MeetingEventFieldReservedException"
                // Same value in a different scale / with blanks is NOT a change.
                call(chair, "update", "e=$event&fee=10.0&summary=%20Kurz%20") shouldContain "MOG-Test"
                row(event)[EventTable.feeAmount].compareTo(BigDecimal("10.00")) shouldBe 0
                // A paid event is cancelled by the board.
                call(chair, "cancel", "e=$event") shouldContain "ERR:MeetingEventCancelReservedException"
                status(event) shouldBe EventStatus.DRAFT
                // A public event: no edit, no publish, no cancel, no align for the officer.
                transaction { EventTable.update({ EventTable.id eq event }) { it[visibility] = EventVisibility.PUBLIC } }
                call(chair, "update", "e=$event&title=x") shouldContain "ERR:MeetingEventVisibilityReservedException"
                call(chair, "publish", "e=$event") shouldContain "ERR:MeetingEventVisibilityReservedException"
                call(chair, "cancel", "e=$event") shouldContain "ERR:MeetingEventVisibilityReservedException"
                call(chair, "align", "m=$meeting&e=$event") shouldContain "ERR:MeetingEventVisibilityReservedException"
                call(chair, "get", "e=$event") shouldBe "DRAFT|MEETING_OFFICER|PUBLIC"
                // The board is not bound by any of this.
                call(boardId, "update", "e=$event&cap=5&fee=12.00") shouldContain "MOG-Test"
                call(boardId, "publish", "e=$event") shouldBe "PUBLISHED"
            }
        }

        test("audit: seat and field names only, no title text, no entry for board actions, the hash chain stays valid") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val meeting = newMeeting(committee)
            val event = newEvent(meeting)
            val firstSequence =
                transaction { (AuditLogEntryTable.selectAll().maxOfOrNull { it[AuditLogEntryTable.sequenceNumber] } ?: 0L) + 1 }
            withOps { call ->
                call(chair, "update", "e=$event&title=${PREFIX}GEHEIMTITEL&loc=Anderswo") shouldBe "${PREFIX}GEHEIMTITEL"
                val entries = audit(meeting)
                entries.size shouldBe 1
                entries.single().kind shouldBe MeetingEventLinkAuditKind.OFFICER_UPDATED
                entries.single().officerRole shouldBe CommitteeRole.CHAIR
                entries.single().meetingId shouldBe meeting.toString()
                entries.single().eventId shouldBe event.toString()
                entries.single().changedFields shouldBe listOf(MeetingEventField.TITLE, MeetingEventField.LOCATION)
                val raw =
                    transaction {
                        AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq meeting }.map {
                            it[AuditLogEntryTable.afterSnapshot].orEmpty() + it[AuditLogEntryTable.beforeSnapshot].orEmpty()
                        }
                    }
                raw.joinToString("") shouldNotContain "GEHEIMTITEL"
                val entryActor =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                AuditLogEntryTable.entityId eq meeting
                            }.single()[AuditLogEntryTable.actorMemberId]
                    }
                entryActor shouldBe chair
                call(boardId, "update", "e=$event&title=${PREFIX}vomBoard") shouldBe "${PREFIX}vomBoard"
                audit(meeting).size shouldBe 1
                // The entries written in this test form a valid chain segment (the first one links back to the entry before it).
                transaction {
                    val rows =
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.sequenceNumber greaterEq firstSequence }
                            .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                            .toList()
                    rows.size shouldBe 1
                    AuditChainVerifier.verify(rows).valid
                } shouldBe true
            }
        }

        test("meetingEventGrant and activeRecordingRole: seat based, today only, most senior office, never TREASURER") {
            val committee = newCommittee()
            val other = newCommittee()
            val m = newMember()
            seat(m, committee, CommitteeRole.SECRETARY)
            seat(m, committee, CommitteeRole.CHAIR)
            seat(m, committee, CommitteeRole.MEMBER)
            val future = newMember().also { seat(it, committee, CommitteeRole.CHAIR, since = LocalDate(2999, 1, 1)) }
            val expired = newMember().also { seat(it, committee, CommitteeRole.CHAIR, until = LocalDate(2021, 1, 1)) }
            val ruleBased = newCommittee()

            fun cm(
                id: Uuid,
                role: AccountRole = AccountRole.MEMBER,
            ) = CurrentMember(memberId = id, role = role, status = MemberStatus.ACTIVE)
            cm(m).activeRecordingRole(committee) shouldBe CommitteeRole.CHAIR
            cm(m).activeRecordingRole(other) shouldBe null
            cm(future).activeRecordingRole(committee) shouldBe null
            cm(expired).activeRecordingRole(committee) shouldBe null
            cm(m, AccountRole.TREASURER).activeRecordingRole(ruleBased) shouldBe null
            val meeting = Uuid.random()
            cm(m).meetingEventGrant(eventMeetingId = meeting, meetingCommitteeId = committee) shouldBe
                MeetingEventGrant.Officer(meetingId = meeting, committeeId = committee, officerRole = CommitteeRole.CHAIR)
            cm(m).meetingEventGrant(eventMeetingId = null, meetingCommitteeId = committee) shouldBe MeetingEventGrant.None
            cm(m).meetingEventGrant(eventMeetingId = meeting, meetingCommitteeId = other) shouldBe MeetingEventGrant.None
            cm(Uuid.random(), AccountRole.BOARD).meetingEventGrant(eventMeetingId = null, meetingCommitteeId = null) shouldBe
                MeetingEventGrant.Full
            cm(Uuid.random(), AccountRole.ADMIN).meetingEventGrant(eventMeetingId = meeting, meetingCommitteeId = other) shouldBe
                MeetingEventGrant.Full
            cm(Uuid.random(), AccountRole.TREASURER).meetingEventGrant(eventMeetingId = meeting, meetingCommitteeId = committee) shouldBe
                MeetingEventGrant.None
        }

        test("the event list shows an officer-published event to members, with scope NONE for them") {
            val committee = newCommittee()
            val chair = newMember().also { seat(it, committee, CommitteeRole.CHAIR) }
            val event = newEvent(newMeeting(committee))
            withOps { call ->
                call(plainMemberId, "get", "e=$event") shouldContain "ERR:NotFoundException"
                call(chair, "publish", "e=$event") shouldBe "PUBLISHED"
                call(plainMemberId, "get", "e=$event") shouldBe "PUBLISHED|NONE|MEMBERS_ONLY"
            }
        }
    })

private object OfficerQuietMailTransport : network.lapis.cloud.server.mail.MailTransport {
    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): network.lapis.cloud.server.mail.MailSendOutcome = network.lapis.cloud.server.mail.MailSendOutcome.Sent
}

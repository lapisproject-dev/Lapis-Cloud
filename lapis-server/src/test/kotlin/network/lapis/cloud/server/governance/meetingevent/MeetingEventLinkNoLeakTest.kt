package network.lapis.cloud.server.governance.meetingevent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.routes.registerEmbedEventsFeedRoutes
import network.lapis.cloud.server.routes.registerEventPublicRoutes
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MARKER = "LEAKMARKER-8842"

/**
 * V1.9.227 -- the No-Leak guarantee: an announced Sitzung published as a PUBLIC event reveals nothing of the Sitzung on any public surface (detail
 * page with share meta and JSON-LD, the ICS feed, the embed feed). The public mappings were not touched by the wave; this test pins that.
 */
class MeetingEventLinkNoLeakTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        test("a published, PUBLIC announcement shows no meeting id, no agenda text and no Sitzung title on any public surface") {
            val committeeId = Uuid.random()
            val meetingId = Uuid.random()
            var eventId: Uuid? = null
            try {
                transaction {
                    CommitteeTable.insert {
                        it[id] = committeeId
                        it[name] = "MEL-Test-NoLeak"
                        it[type] = CommitteeType.WORKING_GROUP
                        it[description] = "x"
                        it[active] = true
                        it[quorumPercent] = 50
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[membershipMode] = CommitteeMembershipMode.MANUAL
                        it[ruleJson] = null
                    }
                    MeetingTable.insert {
                        it[id] = meetingId
                        it[MeetingTable.committeeId] = committeeId
                        it[title] = "Sitzung $MARKER"
                        it[scheduledAt] = LocalDateTime(2030, 5, 4, 19, 0)
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
                    AgendaItemTable.insert {
                        it[id] = Uuid.random()
                        it[position] = 1
                        it[title] = "TOP $MARKER"
                        it[description] = "Beschreibung $MARKER"
                        it[presenterMemberId] = null
                        it[AgendaItemTable.meetingId] = meetingId
                    }
                }
                val ops =
                    MeetingEventLinkOperations(
                        conferencesEnabled = { false },
                        rateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    )
                val ref =
                    ops.announce(
                        current = CurrentMember(memberId = Uuid.parse(BOARD_ID), role = AccountRole.BOARD, status = MemberStatus.ACTIVE),
                        meetingId = meetingId,
                    )
                eventId = Uuid.parse(ref.eventId)
                // The board publishes it and makes it public.
                val slug =
                    transaction {
                        EventTable.update({ EventTable.id eq eventId }) {
                            it[status] = EventStatus.PUBLISHED
                            it[visibility] = EventVisibility.PUBLIC
                        }
                        EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.slug]
                    }
                val limiter = { FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes) }
                val embed =
                    EmbedConfig(
                        enabled = true,
                        allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                        allowInsecureOrigins = false,
                    )
                testApplication {
                    application {
                        routing {
                            registerEventPublicRoutes(
                                checkoutGateways = emptyMap(),
                                baseUrl = "https://example.org",
                                mailDispatcher =
                                    MailDispatcher(
                                        transport = NoOpMailTransport(),
                                        scope =
                                            CoroutineScope(
                                                SupervisorJob() + Dispatchers.IO,
                                            ),
                                    ),
                                branding =
                                    network.lapis.cloud.server.routes
                                        .eventTestBranding(),
                                navAvailability =
                                    network.lapis.cloud.server.routes
                                        .eventTestNav(),
                                embedConfig =
                                    network.lapis.cloud.server.routes
                                        .eventTestEmbedConfig(),
                                pageRateLimiter = limiter(),
                                attemptRateLimiter = limiter(),
                                registrationRateLimiter = limiter(),
                                ticketPageRateLimiter = limiter(),
                                ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                                icsFeedRateLimiter = limiter(),
                            )
                            registerEmbedEventsFeedRoutes(
                                config = embed,
                                baseUrl = "https://lapis.example",
                                feedRateLimiter = limiter(),
                                preflightRateLimiter = limiter(),
                                pastFeedRateLimiter = limiter(),
                            )
                        }
                    }
                    val surfaces =
                        mapOf(
                            "detail page" to "/veranstaltung/$slug",
                            "ics feed" to "/veranstaltung.ics",
                            "embed feed" to "/api/embed/v1/events",
                        )
                    surfaces.forEach { (name, path) ->
                        val response = client.get(path)
                        response.status shouldBe HttpStatusCode.OK
                        val body = response.bodyAsText()
                        // Positive control: the announcement itself IS public ...
                        body shouldContain "MEL-Test-NoLeak"
                        // ... and nothing of the Sitzung is.
                        body shouldNotContain MARKER
                        body shouldNotContain meetingId.toString()
                        body shouldNotContain "meetingId"
                        body shouldNotContain "meeting_id"
                    }
                }
            } finally {
                transaction {
                    eventId?.let { id -> EventTable.deleteWhere { EventTable.id inList listOf(id) } }
                    AgendaItemTable.deleteWhere { AgendaItemTable.meetingId eq meetingId }
                    MeetingTable.deleteWhere { MeetingTable.id eq meetingId }
                    CommitteeTable.deleteWhere { CommitteeTable.id eq committeeId }
                }
            }
        }

        test("an officer-published MEMBERS_ONLY announcement is on no public surface at all (V1.9.229)") {
            val committeeId = Uuid.random()
            val meetingId = Uuid.random()
            var eventId: Uuid? = null
            val boardMember = Uuid.random()
            try {
                transaction {
                    // Own board member: the shared seed is skipped when another spec left members in this JVM's database.
                    MemberTable.insert {
                        it[id] = boardMember
                        it[displayName] = "MEL NoLeak Board"
                        it[email] = "mel-noleak-$boardMember@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = kotlinx.datetime.LocalDate(2026, 1, 1)
                        it[membershipTierId] = null
                    }
                    CommitteeTable.insert {
                        it[id] = committeeId
                        it[name] = "MEL-Test-NoLeak2"
                        it[type] = CommitteeType.WORKING_GROUP
                        it[description] = "x"
                        it[active] = true
                        it[quorumPercent] = 50
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[membershipMode] = CommitteeMembershipMode.MANUAL
                        it[ruleJson] = null
                    }
                    MeetingTable.insert {
                        it[id] = meetingId
                        it[MeetingTable.committeeId] = committeeId
                        it[title] = "Sitzung $MARKER"
                        it[scheduledAt] = LocalDateTime(2030, 5, 4, 19, 0)
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
                val ops =
                    MeetingEventLinkOperations(
                        conferencesEnabled = { false },
                        rateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    )
                val ref =
                    ops.announce(
                        current = CurrentMember(memberId = boardMember, role = AccountRole.BOARD, status = MemberStatus.ACTIVE),
                        meetingId = meetingId,
                    )
                eventId = Uuid.parse(ref.eventId)
                // The state the officer path leaves behind: PUBLISHED, visibility untouched (MEMBERS_ONLY).
                val slug =
                    transaction {
                        EventTable.update({ EventTable.id eq eventId }) { it[status] = EventStatus.PUBLISHED }
                        EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.slug]
                    }
                val limiter = { FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes) }
                val embed =
                    EmbedConfig(
                        enabled = true,
                        allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                        allowInsecureOrigins = false,
                    )
                testApplication {
                    application {
                        routing {
                            registerEventPublicRoutes(
                                checkoutGateways = emptyMap(),
                                baseUrl = "https://example.org",
                                mailDispatcher =
                                    MailDispatcher(
                                        transport = NoOpMailTransport(),
                                        scope =
                                            CoroutineScope(
                                                SupervisorJob() + Dispatchers.IO,
                                            ),
                                    ),
                                branding =
                                    network.lapis.cloud.server.routes
                                        .eventTestBranding(),
                                navAvailability =
                                    network.lapis.cloud.server.routes
                                        .eventTestNav(),
                                embedConfig =
                                    network.lapis.cloud.server.routes
                                        .eventTestEmbedConfig(),
                                pageRateLimiter = limiter(),
                                attemptRateLimiter = limiter(),
                                registrationRateLimiter = limiter(),
                                ticketPageRateLimiter = limiter(),
                                ticketCodeFailureLimiter = LoginRateLimiter(maxFailures = 10_000),
                                icsFeedRateLimiter = limiter(),
                            )
                            registerEmbedEventsFeedRoutes(
                                config = embed,
                                baseUrl = "https://lapis.example",
                                feedRateLimiter = limiter(),
                                preflightRateLimiter = limiter(),
                                pastFeedRateLimiter = limiter(),
                            )
                        }
                    }
                    client.get("/veranstaltung/$slug").status shouldBe HttpStatusCode.NotFound
                    listOf("/veranstaltung.ics", "/api/embed/v1/events").forEach { path ->
                        client.get(path).bodyAsText() shouldNotContain "MEL-Test-NoLeak2"
                    }
                }
            } finally {
                transaction {
                    eventId?.let { id -> EventTable.deleteWhere { EventTable.id inList listOf(id) } }
                    MeetingTable.deleteWhere { MeetingTable.id eq meetingId }
                    CommitteeTable.deleteWhere { CommitteeTable.id eq committeeId }
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId eq boardMember }) { it[actorMemberId] = null }
                    MemberTable.deleteWhere { MemberTable.id eq boardMember }
                }
            }
        }
    })

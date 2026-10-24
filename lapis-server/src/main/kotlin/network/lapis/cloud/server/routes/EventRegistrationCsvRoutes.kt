package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.events.EventRegistrationCsv
import network.lapis.cloud.server.events.EventRegistrationCsvRow
import network.lapis.cloud.server.events.EventRegistrationView
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** This route's OWN role set (never shared): the export carries names, guest addresses and free-text messages. */
private val EVENT_REGISTRATION_EXPORT_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** Hard ceiling of one export; more registrations than this answer 409 instead of building an unbounded file. */
const val MAX_EVENT_REGISTRATION_EXPORT_ROWS = 10_000

private sealed interface ExportOutcome {
    data class Ready(
        val bytes: ByteArray,
        val rowCount: Int,
        val dateStamp: String,
    ) : ExportOutcome

    data object NotFound : ExportOutcome

    data object TooMany : ExportOutcome
}

/**
 * V1.9.204 -- `GET /api/events/{id}/registrations.csv`: every registration of one event (ALL statuses, always unfiltered) as a
 * spreadsheet-ready CSV. BOARD/ADMIN only.
 *
 * Order, and why: identity, then the role check, then the rate limit, and only THEN the id lookup -- so a caller without the role learns
 * nothing about which ids exist. A bad or unknown id is one neutral 404 without echoing the input. The audit entry
 * (`EVENT_REGISTRATION_EXPORT`, row count and format only -- never a name, mail or message) is the LAST operation of the read transaction.
 */
fun Route.registerEventRegistrationCsvRoutes(
    exportRateLimiter: FederationInboxRateLimiter,
    maxRows: Int = MAX_EVENT_REGISTRATION_EXPORT_ROWS,
) {
    get("/api/events/{id}/registrations.csv") {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_REGISTRATION_EXPORT_ROLES)
        if (!exportRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte später erneut versuchen.")
            return@get
        }
        val eventId = runCatching { Uuid.parse(call.parameters["id"].orEmpty()) }.getOrNull()
        if (eventId == null) {
            call.respond(HttpStatusCode.NotFound, "Veranstaltung nicht gefunden.")
            return@get
        }
        val zone = OrganizationTimeZone.current()
        val outcome: ExportOutcome =
            transaction {
                if (EventStore.getEventOrNull(eventId) == null) return@transaction ExportOutcome.NotFound
                val now = DbClock.nowLocalDateTime()
                val rows = EventStore.listByEvent(eventId = eventId, limit = maxRows + 1)
                if (rows.size > maxRows) return@transaction ExportOutcome.TooMany
                val csvRows =
                    rows
                        .map { row ->
                            val memberId = row[EventRegistrationTable.memberId]
                            EventRegistrationCsvRow(
                                name =
                                    (
                                        memberId?.let {
                                            EventStore.memberDisplayNameOrNull(
                                                it,
                                            )
                                        } ?: row[EventRegistrationTable.guestName]
                                    ).orEmpty(),
                                isMember = memberId != null,
                                status = EventRegistrationView.effectiveStatus(row = row, now = now),
                                waitlistPosition = row[EventRegistrationTable.waitlistPosition],
                                registeredAt = row[EventRegistrationTable.registeredAt],
                                fee = row[EventRegistrationTable.feeAmount],
                                paymentState = EventRegistrationView.paymentState(row),
                                guestEmail = row[EventRegistrationTable.guestEmail],
                                message = row[EventRegistrationTable.message],
                            )
                        }
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.EVENT_REGISTRATION_EXPORT,
                    entityId = eventId,
                    action = AuditAction.CREATE,
                    after = "{\"rowCount\":${csvRows.size},\"format\":\"csv\"}",
                )
                ExportOutcome.Ready(
                    bytes = EventRegistrationCsv.render(rows = csvRows, zone = zone),
                    rowCount = csvRows.size,
                    dateStamp = OrganizationTimeZone.dateOf(now).toString(),
                )
            }
        when (outcome) {
            ExportOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, "Veranstaltung nicht gefunden.")
            ExportOutcome.TooMany -> call.respond(HttpStatusCode.Conflict, "Zu viele Anmeldungen für einen Export.")
            is ExportOutcome.Ready -> {
                logger.info { "event registration export by member ${current.memberId}: event $eventId, ${outcome.rowCount} rows" }
                val fileName = "anmeldungen-$eventId-${outcome.dateStamp}.csv"
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"$fileName\"; filename*=UTF-8''$fileName",
                )
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(bytes = outcome.bytes, contentType = ContentType.Text.CSV.withCharset(Charsets.UTF_8))
            }
        }
    }
}

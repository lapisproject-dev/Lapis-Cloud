package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentDisposition
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
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.accounting.datev.DatevBuchungsstapelWriter
import network.lapis.cloud.server.accounting.datev.DatevCharacterSet
import network.lapis.cloud.server.accounting.export.buildJournalExportRequest
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.5.2 "DATEV-Format-Export". [DATEV_FILE_DOWNLOAD_ROLES] is this route's OWN,
 * file-private constant -- NEVER shared with [registerSepaRoutes]'s `SEPA_FILE_DOWNLOAD_ROLES` or
 * [registerDunningRoutes]'s `DUNNING_FILE_DOWNLOAD_ROLES`, same precedent both of those already
 * establish for their own binary-download routes. This file carries the FULL, plaintext
 * `Buchungstext` of every posted journal entry in the requested period, including donation
 * bookings that can name a donor -- BOARD has no treasury function and must not reach it. BOARD
 * sees the Vorschau (`IAccountingService.previewDatevExport`), never the bytes.
 */
private val DATEV_FILE_DOWNLOAD_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/** Outcome of resolving `[from, to]` to either a renderable file or a rejected, blocker-explaining
 * period -- see [registerDatevRoutes]. */
private sealed interface DatevRouteOutcome {
    data class Renderable(
        val bytes: ByteArray,
        val rowCount: Int,
    ) : DatevRouteOutcome

    data class Blocked(
        val summary: String,
    ) : DatevRouteOutcome
}

/**
 * `GET /api/accounting/datev/buchungsstapel.csv?from=...&to=...` -- the byte-serializing twin of
 * [network.lapis.cloud.shared.rpc.IAccountingService.previewDatevExport]. Binary route over plain
 * Ktor (JSON-RPC is the wrong shape for a CP1252-encoded CSV file), mirroring
 * [registerSepaRoutes]/[registerDunningRoutes]'s own idiom for binary payloads.
 *
 * Runs [buildJournalExportRequest] + [DatevBuchungsstapelWriter.plan] -- THE SAME calls
 * `AccountingService.previewDatevExport` makes -- inside its own `transaction {}`, so this route
 * cannot structurally produce a file the preview did not already describe. A non-empty
 * `plan.blockers` never produces a partial file: this route answers 409 with a plaintext listing of
 * every blocker instead (alles-oder-nichts, see [DatevBuchungsstapelWriter] class KDoc).
 */
fun Route.registerDatevRoutes(exportRateLimiter: FederationInboxRateLimiter) {
    get("/api/accounting/datev/buchungsstapel.csv") {
        val from = call.request.queryParameters["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val to = call.request.queryParameters["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (from == null || to == null) {
            call.respond(HttpStatusCode.BadRequest, "Query parameters 'from'/'to' (JJJJ-MM-TT) are required.")
            return@get
        }
        if (from > to) {
            call.respond(HttpStatusCode.BadRequest, "'from' ($from) must not be after 'to' ($to).")
            return@get
        }
        val current = resolveCurrentMember(call)
        current.requireRole(*DATEV_FILE_DOWNLOAD_ROLES)

        if (!exportRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@get
        }

        val outcome: DatevRouteOutcome =
            try {
                transaction {
                    val exportedBy = memberDisplayNameOrEmpty(current.memberId)
                    val request = buildJournalExportRequest(from = from, to = to, exportedBy = exportedBy)
                    val plan = DatevBuchungsstapelWriter.plan(request)
                    if (!plan.exportable) {
                        DatevRouteOutcome.Blocked(plan.blockers.joinToString("\n") { "${it.kind}: ${it.detail}" })
                    } else {
                        DatevRouteOutcome.Renderable(
                            bytes = DatevBuchungsstapelWriter.render(request = request, plan = plan),
                            rowCount = plan.rows.size,
                        )
                    }
                }
            } catch (e: ConflictException) {
                // Security fix (MINOR, DoS/Heap) -- `buildJournalExportRequest`'s MAX_TOTAL_POSTINGS
                // backstop throws [ConflictException] (the RPC-service convention), but this raw
                // Ktor route has no StatusPages mapping for it (see Application.module's
                // StatusPages block, which only maps Unauthenticated/Forbidden) -- same "caught and
                // translated explicitly" idiom `registerDunningRoutes` already establishes for
                // `requireDunningUsable`'s ConflictException.
                DatevRouteOutcome.Blocked(e.message.orEmpty())
            }

        when (outcome) {
            is DatevRouteOutcome.Blocked -> call.respond(HttpStatusCode.Conflict, outcome.summary)
            is DatevRouteOutcome.Renderable -> {
                // Security: never log entry descriptions/donor names -- member id, period and row
                // count only, mirroring registerDunningRoutes'/registerSepaRoutes' own logging
                // posture for financially sensitive downloads.
                logger.info { "DATEV export downloaded by member ${current.memberId}: $from..$to, ${outcome.rowCount} rows" }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, "EXTF_Buchungsstapel_${from}_$to.csv")
                        .toString(),
                )
                // Falle: ContentType.Text.CSV OHNE withCharset laesst Ktor kein Charset anhaengen --
                // Bytes bleiben CP1252-korrekt, aber ein Browser/Client, der UTF-8 raet, zeigt
                // Muell an. Siehe DatevBuchungsstapelWriter Klassen-KDoc.
                call.respondBytes(bytes = outcome.bytes, contentType = ContentType.Text.CSV.withCharset(DatevCharacterSet.CP1252))
            }
        }
    }
}

private fun memberDisplayNameOrEmpty(memberId: Uuid): String =
    MemberTable
        .selectAll()
        .where { MemberTable.id eq memberId }
        .singleOrNull()
        ?.get(MemberTable.displayName)
        .orEmpty()

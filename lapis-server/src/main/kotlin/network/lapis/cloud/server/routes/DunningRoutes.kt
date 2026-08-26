package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.dunning.currentCycleNumber
import network.lapis.cloud.server.payment.dunning.hasDeliveredNoticeInCycle
import network.lapis.cloud.server.payment.dunning.requireDunningUsable
import network.lapis.cloud.server.pdf.MahnungPdfGenerator
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". [DUNNING_FILE_DOWNLOAD_ROLES] is this route's OWN,
 * file-private constant -- NEVER shared with [registerMailmergeRoutes]'s identically-shaped
 * `FINANCIAL_DOC_ROLES` (same Security-Round-1 MAJOR-1 narrowing precedent [registerSepaRoutes]'s
 * own `SEPA_FILE_DOWNLOAD_ROLES` already established). A dunning notice carries no IBAN, but it
 * does carry a member's full postal address and the specific amount they owe -- TREASURER/ADMIN
 * only, no BOARD.
 */
private val DUNNING_FILE_DOWNLOAD_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/**
 * `GET /api/dunning/notices/{noticeId}/notice.pdf` -- serves the archived PDF of one issued
 * dunning notice, via the existing `document`/`document_version` store (mirrors
 * [registerSepaRoutes]'s own binary-download idiom). 404 on an unknown notice, a notice with no
 * `document_id` yet (see `DunningPoller` Phase C self-healing KDoc), or a soft-deleted document.
 *
 * `POST /api/dunning/contributions/{contributionId}/preview.pdf` -- a dry run of the NEXT
 * escalation step's letter: generates the PDF WITHOUT creating a `dunning_notice` row, without any
 * status transition, without archiving, and without an audit entry -- so a treasurer can check the
 * wording before ever "arming" the real escalation. Reuses [network.lapis.cloud.server.payment.dunning.issueDunningNotice]'s
 * OWN level-selection logic is deliberately NOT reused here (that function always writes) --
 * instead this route re-derives the same "next active level after the highest already-issued one
 * in the current cycle" rule directly, read-only.
 */
fun Route.registerDunningRoutes(
    storageRoot: File,
    /**
     * Security review LOW finding -- this route used to have NEITHER the [requireDunningUsable]
     * feature gate NOR any rate limit, unlike every other dunning-mutating path (see
     * [network.lapis.cloud.server.rpc.DunningService] class KDoc "Framework rules"). A treasurer
     * could hammer this endpoint indefinitely, each call paying a full PDF generation (CPU), while
     * the actual issuance path is capped at 10/min. Shares the SAME per-member budget shape as
     * `DunningService.issueRateLimiter` -- a separate instance because this route and the RPC
     * service are wired independently in `Application.module`, not because the budget needs to
     * differ.
     */
    previewRateLimiter: FederationInboxRateLimiter,
) {
    get("/api/dunning/notices/{noticeId}/notice.pdf") {
        val noticeId = call.parameters["noticeId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (noticeId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid noticeId")
            return@get
        }
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_FILE_DOWNLOAD_ROLES)

        val storageKey =
            transaction {
                val noticeRow =
                    DunningNoticeTable.selectAll().where { DunningNoticeTable.id eq noticeId }.singleOrNull() ?: return@transaction null
                val documentId = noticeRow[DunningNoticeTable.documentId] ?: return@transaction null
                val documentRow = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
                if (documentRow == null || documentRow[DocumentTable.isDeleted]) {
                    null
                } else {
                    documentRow[DocumentTable.currentVersionId]?.let { versionId ->
                        DocumentVersionTable.selectAll().where { DocumentVersionTable.id eq versionId }.singleOrNull()?.get(
                            DocumentVersionTable.storageKey,
                        )
                    }
                }
            }
        if (storageKey == null) {
            call.respond(HttpStatusCode.NotFound, "Mahnung nicht gefunden oder noch nicht archiviert.")
            return@get
        }
        val file = storageRoot.resolve(storageKey)
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "File not found on disk")
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "mahnung-$noticeId.pdf").toString(),
        )
        call.respondBytes(bytes = file.readBytes(), contentType = ContentType.Application.Pdf)
    }

    post("/api/dunning/contributions/{contributionId}/preview.pdf") {
        val contributionId = call.parameters["contributionId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (contributionId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid contributionId")
            return@post
        }
        val current = resolveCurrentMember(call)
        current.requireRole(*DUNNING_FILE_DOWNLOAD_ROLES)

        // Security review LOW finding -- this route used to reach straight into the DB, bypassing
        // BOTH `requireDunningUsable` (this route's own `class Route.registerDunningRoutes` KDoc
        // above now documents why) and any rate limit. `requireDunningUsable` throws
        // [ConflictException] (the RPC-service convention) rather than returning a status code
        // directly -- unlike the RPC dispatch path, raw Ktor routes here have no StatusPages mapping
        // for it (see `Application.module`'s `StatusPages` block, which only maps
        // Unauthenticated/Forbidden), so it is caught and translated explicitly, same as every other
        // Conflict this route already reports manually below.
        try {
            requireDunningUsable()
        } catch (e: ConflictException) {
            call.respond(HttpStatusCode.Conflict, e.message)
            return@post
        }
        if (!previewRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@post
        }

        val prepared =
            transaction {
                val contributionRow =
                    ContributionTable
                        .innerJoin(MemberTable)
                        .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
                        .selectAll()
                        .where { ContributionTable.id eq contributionId }
                        .singleOrNull() ?: return@transaction null
                if (contributionRow[ContributionTable.status] !in ContributionStatusSets.DUNNABLE) return@transaction null

                val allNotices = DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq contributionId }.toList()
                val currentCycle = allNotices.currentCycleNumber()
                val liveInCycle =
                    allNotices.filter {
                        it[DunningNoticeTable.cycleNumber] == currentCycle &&
                            it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
                    }
                val lastLevelNumber = liveInCycle.maxOfOrNull { it[DunningNoticeTable.levelNumber] } ?: 0
                val nextLevel =
                    DunningLevelTable
                        .selectAll()
                        .where { DunningLevelTable.active eq true }
                        .orderBy(DunningLevelTable.levelNumber, SortOrder.ASC)
                        .firstOrNull { it[DunningLevelTable.levelNumber] > lastLevelNumber } ?: return@transaction null
                // Mirrors DunningIssuance.issueDunningNotice's own fee guard exactly (see
                // `hasDeliveredNoticeInCycle`'s own KDoc for the round-2 fix: a SKIPPED notice does
                // NOT count as "already delivered", so this preview never shows a fee for what would
                // actually be the member's first REAL letter of the cycle) -- this preview must
                // never show a fee for what issueDunningNotice would itself charge nothing for, or
                // the preview would misrepresent what actually gets charged.
                val effectiveFeeAmount =
                    if (allNotices.hasDeliveredNoticeInCycle(currentCycle)) {
                        nextLevel[DunningLevelTable.feeAmount]
                    } else {
                        null
                    }

                val recipient = loadMailmergeMember(contributionRow[ContributionTable.memberId]) ?: return@transaction null
                val organization = loadOrganizationSettingsDto()
                val today: LocalDate = DbClock.nowLocalDateTime().date
                val respondBy = today.plus(nextLevel[DunningLevelTable.responseDays], DateTimeUnit.DAY)
                val contributionDto =
                    ContributionDto(
                        id = contributionRow[ContributionTable.id].toString(),
                        memberId = contributionRow[ContributionTable.memberId].toString(),
                        memberDisplayName = recipient.displayName,
                        membershipTierId = contributionRow[ContributionTable.membershipTierId].toString(),
                        membershipTierName = contributionRow[MembershipTierTable.name],
                        periodStart = contributionRow[ContributionTable.periodStart],
                        periodEnd = contributionRow[ContributionTable.periodEnd],
                        amountDue = contributionRow[ContributionTable.amountDue],
                        status = contributionRow[ContributionTable.status],
                        paidAt = contributionRow[ContributionTable.paidAt],
                        paidAmount = contributionRow[ContributionTable.paidAmount],
                        note = contributionRow[ContributionTable.note],
                        createdAt = contributionRow[ContributionTable.createdAt],
                        dueDate = contributionRow[ContributionTable.dueDate],
                        paymentMethod = contributionRow[ContributionTable.paymentMethod],
                    )
                PreviewBundle(
                    contribution = contributionDto,
                    recipient = recipient,
                    organization = organization,
                    levelName = nextLevel[DunningLevelTable.name],
                    levelNumber = nextLevel[DunningLevelTable.levelNumber],
                    feeAmount = effectiveFeeAmount,
                    respondBy = respondBy,
                )
            }
        if (prepared == null) {
            call.respond(HttpStatusCode.Conflict, "Kein Beitrag im mahnfaehigen Zustand oder keine weitere Mahnstufe konfiguriert.")
            return@post
        }

        val pdfBytes =
            MahnungPdfGenerator.generate(
                contribution = prepared.contribution,
                member = prepared.recipient,
                organization = prepared.organization,
                levelName = prepared.levelName,
                levelNumber = prepared.levelNumber,
                feeAmount = prepared.feeAmount,
                respondBy = prepared.respondBy,
                issuedOn = DbClock.nowLocalDateTime().date,
            )
        call.respondBytes(bytes = pdfBytes, contentType = ContentType.Application.Pdf)
    }
}

private data class PreviewBundle(
    val contribution: ContributionDto,
    val recipient: MemberDto,
    val organization: OrganizationSettingsDto,
    val levelName: String,
    val levelNumber: Int,
    val feeAmount: BigDecimal?,
    val respondBy: LocalDate,
)

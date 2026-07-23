package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.pdf.BeitragsrechnungPdfGenerator
import network.lapis.cloud.server.pdf.EinladungPdfGenerator
import network.lapis.cloud.server.pdf.SpendenbescheinigungPdfGenerator
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.toOrganizationSettingsDto
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val FINANCIAL_DOC_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
private val GOVERNANCE_DOC_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Hard cap on `recipientMemberId` parts accepted by `/api/mailmerge/invitations` -- DoS guard.
 * Each recipient triggers a synchronous DB lookup inside a blocking `transaction {}` plus at
 * least one full PDF page held in memory by [network.lapis.cloud.server.pdf.LetterPdfBuilder]
 * until `toByteArray()`; without a cap, a BOARD/ADMIN caller could submit an unbounded number of
 * parts and exhaust memory or stall a JDBC thread for a long time. Mirrors [DocumentRoutes]'
 * `MAX_UPLOAD_BYTES` DoS-guard idiom, sized for the realistic case (a general-assembly invite to
 * an entire membership base), not for hypothetical bulk/batch mail-merge (out of scope this wave).
 */
private const val MAX_INVITATION_RECIPIENTS = 1_000

/** Hard cap on `bodyText` length -- generous for a real letter body, well below what would let a single request pin a JDBC/render thread pathologically long via [network.lapis.cloud.server.pdf.LetterPdfBuilder]'s hand-rolled word-wrap. */
private const val MAX_BODY_TEXT_LENGTH = 20_000

/**
 * V0.4.1 Serienbrief/PDF-Engine: PDF bytes travel over these plain Ktor HTTP routes, not Kilua
 * RPC -- exactly mirroring [registerDocumentRoutes]' own idiom for binary payloads.
 *
 * Access tiers: Beitragsrechnung/Spendenbescheinigung ([FINANCIAL_DOC_ROLES] --
 * TREASURER/BOARD/ADMIN) match [network.lapis.cloud.server.rpc.AccountingService]'s own read
 * tier -- deliberately more conservative than [network.lapis.cloud.server.rpc.ContributionService]'s
 * "member can see their own data" carve-out; a member cannot self-serve their own invoice/receipt
 * this wave. Einladung ([GOVERNANCE_DOC_ROLES] -- BOARD/ADMIN) matches
 * [network.lapis.cloud.server.rpc.DocumentService]'s folder/document-creation precedent
 * (governance correspondence).
 *
 * **Completeness guards** (own judgement call, not explicitly mandated by the plan -- comment
 * here so a future maintainer knows these can be relaxed without it reading as an accidental
 * regression): generating a Beitragsrechnung/Spendenbescheinigung for a member with no complete
 * postal address on file, or a Spendenbescheinigung while
 * [OrganizationSettingsDto.taxExemptionAuthority]/[OrganizationSettingsDto.taxExemptionDate] are
 * unset, is rejected with [ConflictException] (409) rather than silently emitting an undeliverable
 * or legally-deficient document. Einladung recipients missing an address are NOT hard-failed --
 * see [EinladungPdfGenerator] KDoc.
 *
 * **Archiving**: Beitragsrechnung/Spendenbescheinigung PDFs are archived into the existing
 * Document/DocumentVersion store via [archiveGeneratedPdf] right after generation (retention/
 * audit argument -- see that function's KDoc); Einladung is not (ephemeral governance
 * correspondence). Archived copies use [DocumentAccessLevel.ADMIN_ONLY] -- deliberately more
 * restrictive than `BOARD_ONLY`, sidestepping a pre-existing, unrelated gap in
 * [network.lapis.cloud.server.security.canAccessDocumentAtLevel]/
 * [network.lapis.cloud.server.security.isPrivileged] where a TREASURER cannot read a `BOARD_ONLY`
 * document at all (`isPrivileged` only recognises ADMIN/BOARD). That gap is flagged here, not
 * fixed -- the primary access path for Treasurer/Board remains these dedicated routes, not the
 * generic Document browser, so the restriction does not block the main workflow.
 *
 * **V0.4.2 (Letterxpress postal-mail dispatch)** reuses [generateBeitragsrechnung]/
 * [generateSpendenbescheinigung]/[loadMailmergeMember]/[loadOrganizationSettingsDto]/
 * [requireCompleteAddress] (all `internal`, not `private`, for exactly this reason) from
 * [network.lapis.cloud.server.rpc.PostalMailService] rather than duplicating PDF generation --
 * see that class's KDoc. Einladung postal dispatch does NOT reuse [generateEinladung] (which
 * renders one combined multi-page PDF for all recipients at once); it calls
 * [network.lapis.cloud.server.pdf.EinladungPdfGenerator.generate] directly, once per recipient.
 *
 * **Out of scope this wave**: no bulk/batch mail-merge UI, no KVision client screens.
 */
fun Route.registerMailmergeRoutes(storageRoot: File) {
    get("/api/mailmerge/contributions/{contributionId}/invoice.pdf") {
        val contributionId = call.parameters["contributionId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (contributionId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid contributionId")
            return@get
        }
        val current = resolveCurrentMember(call)
        current.requireRole(*FINANCIAL_DOC_ROLES)
        try {
            val doc = generateBeitragsrechnung(contributionId, storageRoot, current.memberId)
            call.respondPdf(doc.bytes, doc.fileName)
        } catch (e: NotFoundException) {
            call.respond(HttpStatusCode.NotFound, e.message)
        } catch (e: ConflictException) {
            call.respond(HttpStatusCode.Conflict, e.message)
        }
    }

    get("/api/mailmerge/donations/{journalEntryId}/receipt.pdf") {
        val journalEntryId = call.parameters["journalEntryId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (journalEntryId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid journalEntryId")
            return@get
        }
        val current = resolveCurrentMember(call)
        current.requireRole(*FINANCIAL_DOC_ROLES)
        try {
            val doc = generateSpendenbescheinigung(journalEntryId, storageRoot, current.memberId)
            call.respondPdf(doc.bytes, doc.fileName)
        } catch (e: NotFoundException) {
            call.respond(HttpStatusCode.NotFound, e.message)
        } catch (e: ConflictException) {
            call.respond(HttpStatusCode.Conflict, e.message)
        }
    }

    post("/api/mailmerge/invitations") {
        val current = resolveCurrentMember(call)
        current.requireRole(*GOVERNANCE_DOC_ROLES)

        var title: String? = null
        var eventDateTimeRaw: String? = null
        var location: String? = null
        var bodyText: String? = null
        val recipientMemberIdRaw = mutableListOf<String>()
        var recipientCapExceeded = false

        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FormItem) {
                when (part.name) {
                    "title" -> title = part.value
                    "eventDateTime" -> eventDateTimeRaw = part.value
                    "location" -> location = part.value
                    "bodyText" -> bodyText = part.value
                    "recipientMemberId" ->
                        if (recipientMemberIdRaw.size < MAX_INVITATION_RECIPIENTS) {
                            recipientMemberIdRaw += part.value
                        } else {
                            // Stop growing the list once the cap is hit, but keep draining the
                            // multipart stream (forEachPart continues) -- consistent with
                            // DocumentRoutes' MAX_UPLOAD_BYTES guard, which also keeps consuming
                            // the stream past its cap rather than abandoning the request mid-read.
                            recipientCapExceeded = true
                        }
                }
            }
            part.release()
        }

        if (recipientCapExceeded) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                "At most $MAX_INVITATION_RECIPIENTS recipientMemberId parts are allowed per request",
            )
            return@post
        }
        if ((bodyText?.length ?: 0) > MAX_BODY_TEXT_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, "bodyText exceeds max length of $MAX_BODY_TEXT_LENGTH characters")
            return@post
        }

        val missing =
            listOfNotNull(
                "title".takeIf { title.isNullOrBlank() },
                "eventDateTime".takeIf { eventDateTimeRaw.isNullOrBlank() },
                "location".takeIf { location.isNullOrBlank() },
                "bodyText".takeIf { bodyText.isNullOrBlank() },
            )
        if (missing.isNotEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Missing required field(s): ${missing.joinToString(", ")}")
            return@post
        }
        if (recipientMemberIdRaw.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "At least one recipientMemberId is required")
            return@post
        }
        val eventDateTime =
            runCatching { LocalDateTime.parse(eventDateTimeRaw!!) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, "Invalid eventDateTime (expected ISO-8601 local date-time)")
                return@post
            }

        try {
            val bytes = generateEinladung(title!!, eventDateTime, location!!, bodyText!!, recipientMemberIdRaw)
            call.respondPdf(bytes, "Einladung.pdf")
        } catch (e: NotFoundException) {
            call.respond(HttpStatusCode.NotFound, e.message)
        }
    }
}

/**
 * One PDF produced by [generateBeitragsrechnung]/[generateSpendenbescheinigung], plus the
 * already-resolved recipient [MemberDto] -- V0.4.2's [network.lapis.cloud.server.rpc.PostalMailService]
 * reuses these two generators for postal dispatch and needs the recipient's address without a
 * duplicate DB lookup.
 */
internal data class GeneratedMailmergeDocument(
    val bytes: ByteArray,
    val fileName: String,
    val recipient: MemberDto,
)

internal fun generateBeitragsrechnung(
    contributionId: Uuid,
    storageRoot: File,
    uploadedBy: Uuid,
): GeneratedMailmergeDocument =
    transaction {
        val row =
            ContributionTable
                .innerJoin(MemberTable)
                .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
                .selectAll()
                .where { ContributionTable.id eq contributionId }
                .singleOrNull() ?: throw NotFoundException("Contribution $contributionId not found")

        val contribution =
            ContributionDto(
                id = row[ContributionTable.id].toString(),
                memberId = row[ContributionTable.memberId].toString(),
                memberDisplayName = row[MemberTable.displayName],
                membershipTierId = row[ContributionTable.membershipTierId].toString(),
                membershipTierName = row[MembershipTierTable.name],
                periodStart = row[ContributionTable.periodStart],
                periodEnd = row[ContributionTable.periodEnd],
                amountDue = row[ContributionTable.amountDue],
                status = row[ContributionTable.status],
                paidAt = row[ContributionTable.paidAt],
                paidAmount = row[ContributionTable.paidAmount],
                note = row[ContributionTable.note],
                createdAt = row[ContributionTable.createdAt],
            )
        val member =
            loadMailmergeMember(row[ContributionTable.memberId])
                ?: throw NotFoundException("Member ${contribution.memberId} not found")
        requireCompleteAddress(member)
        val organization = loadOrganizationSettingsDto()

        val bytes = BeitragsrechnungPdfGenerator.generate(contribution, member, organization)
        val fileName = "Beitragsrechnung-${contribution.periodStart}-${contribution.periodEnd}.pdf"
        archiveGeneratedPdf(
            storageRoot = storageRoot,
            folderName = "Beitragsrechnungen",
            fileName = fileName,
            title = "Beitragsrechnung ${member.displayName} ${contribution.periodStart} - ${contribution.periodEnd}",
            bytes = bytes,
            uploadedBy = uploadedBy,
            accessLevel = DocumentAccessLevel.ADMIN_ONLY,
        )
        GeneratedMailmergeDocument(bytes, fileName, member)
    }

internal fun generateSpendenbescheinigung(
    journalEntryId: Uuid,
    storageRoot: File,
    uploadedBy: Uuid,
): GeneratedMailmergeDocument =
    transaction {
        val entryRow =
            JournalEntryTable
                .selectAll()
                .where { JournalEntryTable.id eq journalEntryId }
                .singleOrNull() ?: throw NotFoundException("JournalEntry $journalEntryId not found")
        if (entryRow[JournalEntryTable.status] != JournalEntryStatus.POSTED) {
            throw ConflictException("JournalEntry $journalEntryId is not POSTED")
        }
        val donorMemberId =
            entryRow[JournalEntryTable.donorMemberId]
                ?: throw ConflictException("JournalEntry $journalEntryId has no donor attribution (donorMemberId)")

        val donor = loadMailmergeMember(donorMemberId) ?: throw NotFoundException("Member $donorMemberId not found")
        requireCompleteAddress(donor)

        val organization = loadOrganizationSettingsDto()
        if (organization.taxExemptionAuthority == null || organization.taxExemptionDate == null) {
            throw ConflictException("OrganizationSettings tax-exemption fields (taxExemptionAuthority/taxExemptionDate) are not configured")
        }

        // Net by side, not a raw sum: an INCOME ledger account's balance grows on the CREDIT side
        // (double-entry convention), so a correction/reversal booked as a DEBIT against the same
        // (or another) INCOME account within this entry must reduce the reported donation amount,
        // not inflate it -- see the review finding that caught this over-/under-statement bug.
        val donationAmount =
            (PostingTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { (PostingTable.journalEntryId eq journalEntryId) and (LedgerAccountTable.type eq LedgerAccountType.INCOME) }
                .fold(BigDecimal.ZERO) { acc, postingRow ->
                    when (postingRow[PostingTable.side]) {
                        PostingSide.CREDIT -> acc + postingRow[PostingTable.amount]
                        PostingSide.DEBIT -> acc - postingRow[PostingTable.amount]
                    }
                }
        if (donationAmount <= BigDecimal.ZERO) {
            throw ConflictException("JournalEntry $journalEntryId has no posting against an INCOME LedgerAccount")
        }

        val journalEntry =
            JournalEntryDto(
                id = entryRow[JournalEntryTable.id].toString(),
                entryDate = entryRow[JournalEntryTable.entryDate],
                description = entryRow[JournalEntryTable.description],
                voucherReference = entryRow[JournalEntryTable.voucherReference],
                createdBy = entryRow[JournalEntryTable.createdBy].toString(),
                createdByDisplayName = memberDisplayNameOrEmpty(entryRow[JournalEntryTable.createdBy]),
                status = entryRow[JournalEntryTable.status],
                postedAt = entryRow[JournalEntryTable.postedAt],
                createdAt = entryRow[JournalEntryTable.createdAt],
                postings = emptyList(),
                donorMemberId = donorMemberId.toString(),
                donorMemberDisplayName = donor.displayName,
            )

        val bytes = SpendenbescheinigungPdfGenerator.generate(journalEntry, donationAmount, donor, organization)
        val fileName = "Spendenbescheinigung-${sanitizeForFileName(donor.displayName)}-${entryRow[JournalEntryTable.entryDate]}.pdf"
        archiveGeneratedPdf(
            storageRoot = storageRoot,
            folderName = "Spendenbescheinigungen",
            fileName = fileName,
            title = "Spendenbescheinigung ${donor.displayName} ${entryRow[JournalEntryTable.entryDate]}",
            bytes = bytes,
            uploadedBy = uploadedBy,
            accessLevel = DocumentAccessLevel.ADMIN_ONLY,
        )
        GeneratedMailmergeDocument(bytes, fileName, donor)
    }

private fun generateEinladung(
    title: String,
    eventDateTime: LocalDateTime,
    location: String,
    bodyText: String,
    recipientMemberIdRaw: List<String>,
): ByteArray =
    transaction {
        val recipients =
            recipientMemberIdRaw.map { raw ->
                val id = runCatching { Uuid.parse(raw) }.getOrElse { throw NotFoundException("Invalid recipientMemberId: $raw") }
                loadMailmergeMember(id) ?: throw NotFoundException("Member $id not found")
            }
        val organization = loadOrganizationSettingsDto()
        EinladungPdfGenerator.generate(title, eventDateTime, location, bodyText, recipients, organization)
    }

/** Left-joins [AccountTable] (a member may have no account row at all) -- `role` defaults to `MEMBER` when absent; irrelevant to PDF content either way. */
internal fun loadMailmergeMember(memberId: Uuid): MemberDto? =
    (MemberTable leftJoin AccountTable)
        .selectAll()
        .where { MemberTable.id eq memberId }
        .singleOrNull()
        ?.toMailmergeMemberDto()

private fun ResultRow.toMailmergeMemberDto(): MemberDto =
    MemberDto(
        id = this[MemberTable.id].toString(),
        displayName = this[MemberTable.displayName],
        email = this[MemberTable.email],
        status = this[MemberTable.status],
        joinedAt = this[MemberTable.joinedAt],
        role = this.getOrNull(AccountTable.role) ?: AccountRole.MEMBER,
        street = this[MemberTable.street],
        postalCode = this[MemberTable.postalCode],
        city = this[MemberTable.city],
        country = this[MemberTable.country],
    )

/**
 * Member-controlled data (here: [MemberDto.displayName], which has no length/character-set
 * validation anywhere in the codebase) must never be interpolated verbatim into a generated
 * file name that in turn feeds a `Content-Disposition` header -- Ktor rejects raw CR/LF outright
 * (an uncaught exception -> 500 for that one donor), and other characters (quotes, backslashes,
 * path separators) are not reliably escaped by `ContentDisposition`'s own `quoteIfNeeded` logic,
 * which can produce a malformed/misparsed header. Strips control characters and filename-unsafe
 * characters and caps the length, mirroring how [registerDocumentRoutes] treats client-supplied
 * file names as pure metadata rather than trusting them verbatim.
 */
private fun sanitizeForFileName(raw: String): String {
    val cleaned =
        raw
            .filter { ch -> ch.code >= 0x20 && ch.code != 0x7F && ch !in FILENAME_UNSAFE_CHARS }
            .trim()
            .take(FILENAME_MAX_LENGTH)
    return cleaned.ifBlank { "Empfaenger" }
}

private val FILENAME_UNSAFE_CHARS = charArrayOf('"', '\\', '/', ':', '*', '?', '<', '>', '|')
private const val FILENAME_MAX_LENGTH = 100

/** See [registerMailmergeRoutes] KDoc "Completeness guards". */
internal fun requireCompleteAddress(member: MemberDto) {
    if (member.street == null || member.postalCode == null || member.city == null || member.country == null) {
        throw ConflictException("Member ${member.id} has no complete postal address on file")
    }
}

// Delegates to the single shared mapper (network.lapis.cloud.server.rpc.toOrganizationSettingsDto)
// -- see that function's KDoc for why the field-by-field mapping is not duplicated here anymore.
// internal (not private): network.lapis.cloud.server.rpc.PostalMailService (V0.4.2) reuses this
// too, to avoid a third field-by-field mapping.
internal fun loadOrganizationSettingsDto(): OrganizationSettingsDto =
    OrganizationSettingsTable
        .selectAll()
        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
        .singleOrNull()
        ?.toOrganizationSettingsDto()
        ?: throw NotFoundException("OrganizationSettings row not found -- baseline seed missing?")

private fun memberDisplayNameOrEmpty(memberId: Uuid): String =
    MemberTable
        .selectAll()
        .where { MemberTable.id eq memberId }
        .singleOrNull()
        ?.get(MemberTable.displayName)
        .orEmpty()

private suspend fun ApplicationCall.respondPdf(
    bytes: ByteArray,
    fileName: String,
) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString(),
    )
    respondBytes(bytes, contentType = ContentType.Application.Pdf)
}

package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostalDeliveryLogTable
import network.lapis.cloud.server.pdf.EinladungPdfGenerator
import network.lapis.cloud.server.postal.PostalDispatchOutcome
import network.lapis.cloud.server.postal.PostalMailProvider
import network.lapis.cloud.server.routes.GeneratedMailmergeDocument
import network.lapis.cloud.server.routes.generateBeitragsrechnung
import network.lapis.cloud.server.routes.generateSpendenbescheinigung
import network.lapis.cloud.server.routes.loadMailmergeMember
import network.lapis.cloud.server.routes.loadOrganizationSettingsDto
import network.lapis.cloud.server.routes.requireCompleteAddress
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.PostalDeliveryLogDto
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.PostalInvitationDispatchInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IPostalMailService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val FINANCIAL_DISPATCH_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
private val GOVERNANCE_DISPATCH_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Hard cap on [PostalInvitationDispatchInput.recipientMemberIds] per [dispatchEinladungByPost]
 * call -- deliberately much stricter than [network.lapis.cloud.server.routes.MailmergeRoutes]'
 * own `MAX_INVITATION_RECIPIENTS` (1,000, free PDF-only generation): postal dispatch costs real
 * money per letter and transfers PII to a third party (Letterxpress) for every recipient, so the
 * safe default bound here is far smaller. Exceeding it is rejected up front with
 * [BadRequestException] (400) -- no partial dispatch.
 */
private const val MAX_POSTAL_INVITATION_RECIPIENTS = 50

/**
 * V0.4.2 Letterxpress postal-mail dispatch. Implements [IPostalMailService] -- see that
 * interface's KDoc for the full "explicit human action, not automatic fallback" rationale.
 *
 * ## The `postalMailEnabled` gate
 *
 * Every dispatch method calls [requirePostalMailEnabled] **first**, before any PDF generation,
 * network call, or delivery-log row -- if `OrganizationSettingsDto.postalMailEnabled` is `false`
 * (the default), the call is rejected with [ConflictException] and has zero side effects. See
 * `OrganizationSettingsDto.postalMailEnabled` KDoc for the Data Processing Agreement
 * (Auftragsverarbeitungsvertrag/AVV) requirement this backs -- an organizational/legal
 * precondition this codebase cannot verify, only gate behind an explicit ADMIN opt-in.
 *
 * ## PDF reuse, not reimplementation
 *
 * [dispatchBeitragsrechnungByPost]/[dispatchSpendenbescheinigungByPost] reuse
 * [generateBeitragsrechnung]/[generateSpendenbescheinigung] (made `internal` in
 * `network.lapis.cloud.server.routes.MailmergeRoutes` for exactly this reason) -- the postal path
 * regenerates and re-archives the PDF exactly like the free download route, so the archived
 * Document/DocumentVersion copy always matches what was physically mailed at dispatch time, even
 * if it later differs from an earlier download (accepted, intentional).
 *
 * [dispatchEinladungByPost] does NOT reuse `MailmergeRoutes.generateEinladung` (which renders one
 * combined multi-page PDF for ALL recipients in one job) -- Letterxpress needs one physical letter
 * = one PDF = one address per job, so this calls [EinladungPdfGenerator.generate] directly, once
 * per recipient, with a singleton `recipients` list. Unlike the free-PDF Einladung route (which
 * tolerates a missing address with a placeholder line), postal dispatch requires **every**
 * recipient to have a complete address up front -- if even one is incomplete the whole call is
 * rejected with [ConflictException] before any dispatch happens (fail-closed, no partial-cost
 * surprise). Einladung PDFs are never archived here either, matching the free route's own
 * "ephemeral governance correspondence" judgement call.
 *
 * ## Transaction boundaries around the network call
 *
 * [PostalMailProvider.dispatchLetter] is a `suspend` function doing real outbound network I/O --
 * it is always called *outside* any Exposed `transaction {}` block (Exposed's blocking JDBC
 * `transaction {}` lambda is not `suspend`, and even if it were, holding a JDBC connection open
 * for the duration of an HTTP round-trip to Letterxpress would needlessly exhaust the connection
 * pool under load). Each dispatch is therefore: (1) a self-contained `transaction {}` that
 * generates/archives the PDF and resolves the recipient's address (reusing the existing
 * generators' own transactions, or a short dedicated one for Einladung), (2) the suspend
 * [PostalMailProvider.dispatchLetter] call with no transaction open, (3) a second, short
 * `transaction {}` that inserts the resulting [PostalDeliveryLogTable] row.
 *
 * ## Delivery status
 *
 * This wave's dispatch is synchronous request/response only -- [PostalDeliveryStatus.QUEUED] is
 * reserved for a future async/webhook-based delivery-status-callback follow-up (out of scope this
 * wave, see [IPostalMailService] KDoc); every row written here is either
 * [PostalDeliveryStatus.SENT] or [PostalDeliveryStatus.FAILED]. A [PostalDispatchOutcome.Failed]
 * outcome is a legitimate business outcome, not a precondition violation -- the RPC call still
 * returns normally (a [PostalDeliveryLogDto] with `status=FAILED`) rather than throwing.
 *
 * ## AVV-register advisory (V0.5.5)
 *
 * [requirePostalMailEnabled] additionally logs a non-blocking WARN when `postalMailEnabled` is
 * `true` but the AVV-register (`network.lapis.cloud.server.rpc.DsgvoComplianceService`,
 * `processing_agreement`) has no [network.lapis.cloud.shared.domain.AvvStatus.SIGNED], non-expired
 * row for "Letterxpress". **Deliberately non-blocking, a judgement call**: `postalMailEnabled`
 * itself already encodes an ADMIN's attestation that a Data Processing Agreement is in place (see
 * this class's own "The `postalMailEnabled` gate" KDoc above); a hard register check here would
 * introduce a new fail-closed risk (blocking a legitimate, already-paid-for dispatch merely because
 * the register row was never entered) for no corresponding legal benefit the opt-in gate does not
 * already provide, and would needlessly complicate what is currently a single boolean precondition.
 * The check is wrapped in `runCatching` so a query/table error can never abort a dispatch that would
 * otherwise have succeeded -- this is advisory logging, not a new precondition.
 */
class PostalMailService(
    private val call: ApplicationCall,
    private val storageRoot: File,
    private val postalMailProvider: PostalMailProvider,
) : IPostalMailService {
    private val logger = KotlinLogging.logger {}

    override suspend fun dispatchBeitragsrechnungByPost(contributionId: String): PostalDeliveryLogDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FINANCIAL_DISPATCH_ROLES)
        requirePostalMailEnabled()
        val id = contributionId.toPostalUuid("Contribution")
        val doc = generateBeitragsrechnung(id, storageRoot, current.memberId)
        return dispatchAndLog(doc, doc.fileName)
    }

    override suspend fun dispatchSpendenbescheinigungByPost(journalEntryId: String): PostalDeliveryLogDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FINANCIAL_DISPATCH_ROLES)
        requirePostalMailEnabled()
        val id = journalEntryId.toPostalUuid("JournalEntry")
        val doc = generateSpendenbescheinigung(id, storageRoot, current.memberId)
        return dispatchAndLog(doc, doc.fileName)
    }

    override suspend fun dispatchEinladungByPost(input: PostalInvitationDispatchInput): List<PostalDeliveryLogDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*GOVERNANCE_DISPATCH_ROLES)
        requirePostalMailEnabled()
        if (input.recipientMemberIds.isEmpty()) {
            throw BadRequestException("recipientMemberIds must not be empty")
        }
        if (input.recipientMemberIds.size > MAX_POSTAL_INVITATION_RECIPIENTS) {
            throw BadRequestException(
                "At most $MAX_POSTAL_INVITATION_RECIPIENTS recipientMemberIds are allowed per postal Einladung " +
                    "dispatch (vs. the free PDF-only route's much larger cap) -- postal dispatch costs real money " +
                    "per letter, got ${input.recipientMemberIds.size}",
            )
        }

        // Resolve + validate ALL recipients before dispatching any of them -- fail-closed, no
        // partial dispatch if one recipient turns out to have an incomplete address.
        val (recipients, organization) =
            transaction {
                val loaded =
                    input.recipientMemberIds.map { raw ->
                        val id = raw.toPostalUuid("Member")
                        val member = loadMailmergeMember(id) ?: throw NotFoundException("Member $id not found")
                        requireCompleteAddress(member)
                        member
                    }
                loaded to loadOrganizationSettingsDto()
            }

        return recipients.map { recipient ->
            val pdfBytes =
                EinladungPdfGenerator.generate(
                    title = input.title,
                    eventDateTime = input.eventDateTime,
                    location = input.location,
                    bodyText = input.bodyText,
                    recipients = listOf(recipient),
                    organization = organization,
                )
            dispatchAndLog(pdfBytes, recipient, documentReference = "Einladung: ${input.title}")
        }
    }

    override suspend fun listPostalDeliveryLog(): List<PostalDeliveryLogDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*FINANCIAL_DISPATCH_ROLES)
        return transaction {
            (PostalDeliveryLogTable innerJoin MemberTable)
                .selectAll()
                .orderBy(PostalDeliveryLogTable.dispatchedAt, SortOrder.DESC)
                .map { it.toPostalDeliveryLogDto() }
        }
    }

    /** See class KDoc "The `postalMailEnabled` gate". */
    private fun requirePostalMailEnabled() {
        val enabled =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
                    ?.get(OrganizationSettingsTable.postalMailEnabled)
                    ?: false
            }
        if (!enabled) {
            throw ConflictException(
                "Postal mail dispatch is disabled (OrganizationSettings.postalMailEnabled=false) -- enabling " +
                    "requires a Data Processing Agreement (Auftragsverarbeitungsvertrag/AVV) with Letterxpress to " +
                    "be in place first; see OrganizationSettingsDto.postalMailEnabled KDoc",
            )
        }
        warnIfNoActiveLetterxpressAgreement()
    }

    /** See class KDoc "AVV-register advisory (V0.5.5)". Never throws, never blocks dispatch. */
    private fun warnIfNoActiveLetterxpressAgreement() {
        runCatching {
            DsgvoComplianceService(call).hasActiveProcessingAgreement("Letterxpress")
        }.onSuccess { hasActiveAgreement ->
            if (!hasActiveAgreement) {
                logger.warn {
                    "Postal mail dispatch is proceeding with postalMailEnabled=true, but no SIGNED/non-expired " +
                        "processing_agreement row exists for 'Letterxpress' in the AVV register -- advisory only, " +
                        "dispatch is not blocked (see PostalMailService class KDoc)"
                }
            }
        }.onFailure { cause ->
            logger.warn(cause) { "AVV-register advisory check for 'Letterxpress' failed -- dispatch proceeds unaffected" }
        }
    }

    /** Convenience overload for the Beitragsrechnung/Spendenbescheinigung paths, which already have a [GeneratedMailmergeDocument] in hand. */
    private suspend fun dispatchAndLog(
        doc: GeneratedMailmergeDocument,
        documentReference: String,
    ): PostalDeliveryLogDto = dispatchAndLog(doc.bytes, doc.recipient, documentReference)

    /** See class KDoc "Transaction boundaries around the network call". */
    private suspend fun dispatchAndLog(
        pdfBytes: ByteArray,
        recipient: MemberDto,
        documentReference: String,
    ): PostalDeliveryLogDto {
        val outcome =
            postalMailProvider.dispatchLetter(
                pdfBytes = pdfBytes,
                recipientName = recipient.displayName,
                recipientStreet = recipient.street.orEmpty(),
                recipientPostalCode = recipient.postalCode.orEmpty(),
                recipientCity = recipient.city.orEmpty(),
                recipientCountry = recipient.country.orEmpty(),
            )
        return transaction {
            val id = Uuid.random()
            val now = nowLocalDateTime()
            val status: PostalDeliveryStatus
            val providerReference: String?
            val errorMessage: String?
            when (outcome) {
                is PostalDispatchOutcome.Dispatched -> {
                    status = PostalDeliveryStatus.SENT
                    providerReference = outcome.providerReference
                    errorMessage = null
                }
                is PostalDispatchOutcome.Failed -> {
                    status = PostalDeliveryStatus.FAILED
                    providerReference = null
                    errorMessage = outcome.sanitizedErrorMessage
                }
            }
            PostalDeliveryLogTable.insert {
                it[PostalDeliveryLogTable.id] = id
                it[PostalDeliveryLogTable.recipientMemberId] = Uuid.parse(recipient.id)
                it[PostalDeliveryLogTable.documentReference] = documentReference
                it[PostalDeliveryLogTable.dispatchedAt] = now
                it[PostalDeliveryLogTable.status] = status
                it[PostalDeliveryLogTable.providerReference] = providerReference
                it[PostalDeliveryLogTable.errorMessage] = errorMessage
            }
            PostalDeliveryLogDto(
                id = id.toString(),
                recipientMemberId = recipient.id,
                recipientDisplayName = recipient.displayName,
                documentReference = documentReference,
                dispatchedAt = now,
                status = status,
                providerReference = providerReference,
                errorMessage = errorMessage,
            )
        }
    }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toPostalUuid(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}

private fun ResultRow.toPostalDeliveryLogDto(): PostalDeliveryLogDto =
    PostalDeliveryLogDto(
        id = this[PostalDeliveryLogTable.id].toString(),
        recipientMemberId = this[PostalDeliveryLogTable.recipientMemberId].toString(),
        recipientDisplayName = this[MemberTable.displayName],
        documentReference = this[PostalDeliveryLogTable.documentReference],
        dispatchedAt = this[PostalDeliveryLogTable.dispatchedAt],
        status = this[PostalDeliveryLogTable.status],
        providerReference = this[PostalDeliveryLogTable.providerReference],
        errorMessage = this[PostalDeliveryLogTable.errorMessage],
    )

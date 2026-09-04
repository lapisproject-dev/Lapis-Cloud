package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.psp.PspCheckoutSessions
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.payment.psp.StripeCheckoutResult
import network.lapis.cloud.server.payment.psp.StripeReturnUrls
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CheckoutSessionDto
import network.lapis.cloud.shared.domain.ContributionCheckoutInput
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DonationCheckoutInput
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.PaymentGatewayAvailabilityDto
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.PaymentGatewaySettingsDto
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionDto
import network.lapis.cloud.shared.domain.PaymentTransactionPageDto
import network.lapis.cloud.shared.domain.PaymentTransactionQuery
import network.lapis.cloud.shared.domain.PspConfigStatusDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IPaymentGatewayService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val TREASURY_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** Mirrors `payment_checkout_session.purpose VARCHAR(200)` in `V13__psp_checkout.sql`. */
private const val MAX_DONATION_PURPOSE_LENGTH = 200

/**
 * Implements [IPaymentGatewayService] -- see that interface's KDoc. Welle V1.2.1
 * "Zahlungs-Fundament": ONLY the disclaimer-acknowledgment opt-in gate, exact mirror of
 * [AuctionService]'s own `enableAuction`/`disableAuction`/`getAuctionComplianceDisclaimer`/
 * `getAuctionSettings` shape. Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) adds real
 * checkout/webhook-adjacent functionality, additively, on this SAME class -- see the interface's own
 * KDoc for the full three-part usability gate and the deliberate absence of any secret-writing RPC.
 *
 * **Constructor default exists for tests only -- production MUST pass shared instances**, same
 * "one instance per RPC dispatch, shared collaborators threaded through explicitly" discipline
 * [SepaService] establishes for its own `sepaConfig`/`mandateWriteRateLimiter`.
 *
 * [checkoutCreateRateLimiter] throttles [createDonationCheckout]/[createContributionCheckout] --
 * both are member-reachable and each triggers one outbound Stripe API call
 * ([StripeCheckoutClient.createCheckoutSession]); without a limiter, a single low-privilege member
 * (`AccountRole.MEMBER`, no `requireRole` gate on either method) could loop either call and exhaust
 * Stripe's write quota for every other member. Same per-member "member:\$memberId" keying, reusing
 * [FederationInboxRateLimiter] as a plain per-member counter, as [SepaService.mandateWriteRateLimiter]
 * (security audit finding, Welle V1.2.8, MAJOR).
 */
class PaymentGatewayService(
    private val call: ApplicationCall,
    private val pspConfigState: PspConfigState = PspConfig.load(),
    private val checkoutClient: StripeCheckoutClient? =
        (pspConfigState as? PspConfigState.Configured)?.let { StripeCheckoutClient(pspConfig = it.config) },
    private val checkoutCreateRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
) : IPaymentGatewayService {
    override suspend fun getPaymentGatewayComplianceDisclaimer(): PaymentGatewayComplianceDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return PaymentGatewayComplianceDisclaimerDto(
            version = PaymentGatewayComplianceDisclaimer.VERSION,
            text = PaymentGatewayComplianceDisclaimer.TEXT,
            sha256 = PaymentGatewayComplianceDisclaimer.SHA256,
        )
    }

    override suspend fun enablePaymentGateway(
        provider: PaymentProvider,
        acknowledgment: PaymentGatewayComplianceAcknowledgmentInput,
    ): PaymentGatewaySettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (provider == PaymentProvider.MANUAL) {
            throw BadRequestException("provider must be PAYPAL or STRIPE, never MANUAL")
        }
        // Welle V1.2.8 scope decision -- see IPaymentGatewayService class KDoc.
        if (provider == PaymentProvider.PAYPAL) {
            throw BadRequestException("PayPal ist in dieser Version noch nicht implementiert -- bitte STRIPE waehlen.")
        }
        if (!PaymentGatewayComplianceDisclaimer.matches(
                version = acknowledgment.disclaimerVersion,
                sha256 = acknowledgment.disclaimerSha256,
            )
        ) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 do not match the current PaymentGatewayComplianceDisclaimer -- " +
                    "call getPaymentGatewayComplianceDisclaimer again and submit its CURRENT version/sha256 unmodified",
            )
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            PaymentGatewayComplianceAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[acknowledgedByMemberId] = current.memberId
                it[acknowledgedAt] = now
                it[disclaimerVersion] = acknowledgment.disclaimerVersion
                it[disclaimerSha256] = acknowledgment.disclaimerSha256
                it[PaymentGatewayComplianceAcknowledgmentTable.provider] = provider
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentGatewayEnabled] = true
                it[paymentGatewayProvider] = provider
            }
            loadPaymentGatewaySettingsDto()
        }
    }

    override suspend fun disablePaymentGateway(): PaymentGatewaySettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentGatewayEnabled] = false
                it[paymentGatewayProvider] = null
            }
            loadPaymentGatewaySettingsDto()
        }
    }

    override suspend fun getPaymentGatewaySettings(): PaymentGatewaySettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction { loadPaymentGatewaySettingsDto() }
    }

    // ════════════════════════════════════════════════════════════════════
    // V1.2.8 -- Checkout
    // ════════════════════════════════════════════════════════════════════

    override suspend fun getPaymentGatewayAvailability(): PaymentGatewayAvailabilityDto {
        resolveCurrentMember(call)
        return transaction {
            val settingsRow =
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
            val enabled = settingsRow?.get(OrganizationSettingsTable.paymentGatewayEnabled) ?: false
            val provider = settingsRow?.get(OrganizationSettingsTable.paymentGatewayProvider)
            val isPoliticalParty = settingsRow?.get(OrganizationSettingsTable.isPoliticalParty) ?: false
            val usable =
                enabled &&
                    paymentGatewayDisclaimerIsCurrentlyAcknowledged() &&
                    pspConfigState is PspConfigState.Configured &&
                    provider == PaymentProvider.STRIPE
            PaymentGatewayAvailabilityDto(
                enabled = usable,
                provider = if (usable) provider else null,
                contributionCheckoutAvailable = usable,
                donationCheckoutAvailable = usable,
                donorCategoryRequired = usable && isPoliticalParty,
                // Welle V1.2.9: only when usable -- `usable` already proved pspConfigState is
                // Configured, so this cast cannot fail; a disabled/misconfigured gate reports no
                // ceiling at all rather than a number from a dead config path.
                maxCheckoutAmountEur = if (usable) (pspConfigState as PspConfigState.Configured).config.maxCheckoutAmountEur else null,
            )
        }
    }

    /**
     * Reuses an existing non-expired `CREATED` session for the same contribution instead of
     * minting a second Stripe session -- the amount is read from `contribution.amount_due` server-
     * side, NEVER taken from the caller.
     */
    override suspend fun createContributionCheckout(input: ContributionCheckoutInput): CheckoutSessionDto {
        val current = resolveCurrentMember(call)
        requireWithinCheckoutCreateRate(current.memberId)
        val contributionId = input.contributionId.toContributionUuid()
        val contributionRow =
            transaction { ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.singleOrNull() }
                ?: throw NotFoundException("Contribution ${input.contributionId} not found")
        val ownerMemberId = contributionRow[ContributionTable.memberId]
        if (ownerMemberId != current.memberId && current.role !in TREASURY_READ_ROLES) {
            throw NotFoundException("Contribution ${input.contributionId} not found")
        }
        if (contributionRow[ContributionTable.status] in ContributionStatusSets.SETTLED) {
            throw ConflictException("Contribution ${input.contributionId} is already settled -- no online payment needed.")
        }
        // MAJOR (code review, Welle V1.2.8): a SEPA batch may already be collecting this exact
        // contribution -- reject an online checkout while that debit is in flight, mirroring the
        // ALREADY_IN_FLIGHT exclusion SepaService.buildPreview already applies on the SEPA side.
        // Without this a member could pay online AND be debited by the same running SEPA batch.
        if (contributionRow[ContributionTable.status] in ContributionStatusSets.DEBIT_IN_FLIGHT) {
            throw ConflictException(
                "Fuer diesen Beitrag laeuft bereits eine SEPA-Lastschrift -- online bezahlen ist waehrenddessen nicht moeglich.",
            )
        }
        requirePaymentGatewayUsable()
        val client = requireNotNull(checkoutClient) { "requirePaymentGatewayUsable already guaranteed pspConfigState is Configured" }

        val now = DbClock.nowLocalDateTime()
        val reusable = transaction { PspCheckoutSessions.findReusableForContribution(contributionId = contributionId, now = now) }
        if (reusable != null) {
            return reusable.toCheckoutSessionDto()
        }

        val amount = contributionRow[ContributionTable.amountDue]
        val checkoutSessionId = Uuid.random()
        val stripeResult =
            client.createCheckoutSession(
                checkoutSessionId = checkoutSessionId.toString(),
                amount = amount,
                currency = "EUR",
                description = "Mitgliedsbeitrag",
                returnUrls =
                    StripeReturnUrls.memberSpa(
                        baseUrl = FederationConfig.publicBaseUrl,
                        checkoutSessionId = checkoutSessionId.toString(),
                    ),
            )
        return persistCheckoutSessionOrThrow(
            stripeResult = stripeResult,
            checkoutSessionId = checkoutSessionId,
            intent = PaymentIntent.CONTRIBUTION,
            contributionId = contributionId,
            memberId = ownerMemberId,
            externalDonorId = null,
            embedOrigin = null,
            amount = amount,
            donorCategory = null,
            purpose = null,
            now = now,
        )
    }

    /**
     * When `organization_settings.is_political_party` is `true`, [DonationCheckoutInput.donorCategory]
     * is MANDATORY and a `PROHIBITED` §25 PartG verdict rejects the checkout BEFORE any Stripe call.
     */
    override suspend fun createDonationCheckout(input: DonationCheckoutInput): CheckoutSessionDto {
        val current = resolveCurrentMember(call)
        requireWithinCheckoutCreateRate(current.memberId)
        requirePaymentGatewayUsable()
        val client = requireNotNull(checkoutClient) { "requirePaymentGatewayUsable already guaranteed pspConfigState is Configured" }
        val pspConfig = (pspConfigState as PspConfigState.Configured).config

        val amount = input.amount
        if (amount <= BigDecimal.ZERO) throw BadRequestException("amount must be positive")
        if (amount.scale() > 2) throw BadRequestException("amount must have at most 2 fractional digits")
        if (amount.compareTo(pspConfig.maxCheckoutAmountEur) > 0) {
            throw BadRequestException("amount exceeds the configured maximum of ${pspConfig.maxCheckoutAmountEur} EUR")
        }
        // MAJOR (code review, Welle V1.2.8): validate BEFORE the Stripe call below -- V13__psp_checkout.sql
        // declares payment_checkout_session.purpose as VARCHAR(200); without this check a too-long
        // purpose would mint a real (unpayable) Stripe Checkout Session and only THEN fail the DB
        // insert, leaving an orphaned Stripe session and a bare 500 for the donor. Same
        // length-before-call convention as ConferenceService.createRoom's title/description checks.
        if ((input.purpose?.length ?: 0) > MAX_DONATION_PURPOSE_LENGTH) {
            throw BadRequestException("purpose must be at most $MAX_DONATION_PURPOSE_LENGTH characters")
        }

        val isPoliticalParty =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()[OrganizationSettingsTable.isPoliticalParty]
            }
        if (isPoliticalParty) {
            val donorCategory =
                input.donorCategory ?: throw BadRequestException("donorCategory is required when the organization is a political party")
            val priorTotal =
                transaction {
                    priorPostedDonationTotalThisYear(
                        donorMemberId = current.memberId,
                        externalDonorId = null,
                        year = DbClock.nowLocalDateTime().year,
                        excludeEntryId = null,
                    )
                }
            val verdict =
                PartyDonationComplianceCalculator.check(
                    amount = amount,
                    category = donorCategory,
                    priorPostedTotalThisYear = priorTotal,
                )
            if (verdict.verdict == DonationVerdict.PROHIBITED) {
                throw ConflictException(verdict.reason ?: "Donation prohibited under §25 PartG (donorCategory=$donorCategory)")
            }
        }

        val checkoutSessionId = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        val stripeResult =
            client.createCheckoutSession(
                checkoutSessionId = checkoutSessionId.toString(),
                amount = amount,
                currency = "EUR",
                description = input.purpose?.takeIf { it.isNotBlank() } ?: "Spende",
                returnUrls =
                    StripeReturnUrls.memberSpa(
                        baseUrl = FederationConfig.publicBaseUrl,
                        checkoutSessionId = checkoutSessionId.toString(),
                    ),
            )
        return persistCheckoutSessionOrThrow(
            stripeResult = stripeResult,
            checkoutSessionId = checkoutSessionId,
            intent = PaymentIntent.DONATION,
            contributionId = null,
            memberId = current.memberId,
            externalDonorId = null,
            embedOrigin = null,
            amount = amount,
            donorCategory = input.donorCategory,
            purpose = input.purpose,
            now = now,
        )
    }

    override suspend fun getCheckoutSession(checkoutSessionId: String): CheckoutSessionDto {
        val current = resolveCurrentMember(call)
        val id = checkoutSessionId.toPaymentCheckoutSessionUuid()
        // The whole read, including toCheckoutSessionDto()'s own nested PaymentTransactionTable
        // lookup, must stay inside ONE transaction { } -- that mapper is not self-contained (same
        // "reads outside a transaction" bug class SepaService.requireSepaUsable's own KDoc warns
        // about, see requirePaymentGatewayUsable's KDoc above).
        return transaction {
            val row =
                PaymentCheckoutSessionTable.selectAll().where { PaymentCheckoutSessionTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("Checkout session $checkoutSessionId not found")
            val ownerMemberId = row[PaymentCheckoutSessionTable.memberId]
            if (ownerMemberId != current.memberId && current.role !in TREASURY_READ_ROLES) {
                throw NotFoundException("Checkout session $checkoutSessionId not found")
            }
            row.toCheckoutSessionDto()
        }
    }

    override suspend fun listPaymentTransactions(query: PaymentTransactionQuery): PaymentTransactionPageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_READ_ROLES)
        val effectiveLimit = query.limit.coerceIn(1, 200)
        // MINOR fix (code review, Welle V1.2.8): limit was clamped but offset was passed straight
        // through -- a negative offset reached Exposed's .offset(...) unclamped and raised a raw
        // SQL error (500) instead of a clean result.
        val effectiveOffset = query.offset.coerceAtLeast(0)
        return transaction {
            val statusFilter = query.status
            val intentFilter = query.intent
            val memberIdFilter = query.memberId
            val conditions = mutableListOf<Op<Boolean>>()
            if (statusFilter != null) conditions += (PaymentTransactionTable.status eq statusFilter)
            if (intentFilter != null) conditions += (PaymentTransactionTable.intent eq intentFilter)
            if (memberIdFilter != null) conditions += (PaymentTransactionTable.memberId eq memberIdFilter.toPaymentMemberUuid())
            if (query.unreconciledOnly) conditions += (PaymentTransactionTable.journalEntryId.isNull())

            val baseQuery = PaymentTransactionTable.selectAll()
            val filtered = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            val totalCount = filtered.count()
            val rows =
                PaymentTransactionTable
                    .selectAll()
                    .let { q -> if (conditions.isEmpty()) q else q.where { conditions.reduce { a, b -> a and b } } }
                    .orderBy(PaymentTransactionTable.receivedAt, SortOrder.DESC)
                    .limit(effectiveLimit)
                    .offset(effectiveOffset.toLong())
                    .map { it.toPaymentTransactionDto() }
            PaymentTransactionPageDto(rows = rows, totalCount = totalCount.toInt(), limit = effectiveLimit, offset = effectiveOffset)
        }
    }

    override suspend fun getPspConfigStatus(): PspConfigStatusDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val settingsRow =
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
            PspConfigStatusDto(
                configuredProvider = settingsRow?.get(OrganizationSettingsTable.paymentGatewayProvider),
                secretKeyConfigured = pspConfigState is PspConfigState.Configured,
                webhookSecretConfigured = pspConfigState is PspConfigState.Configured,
                webhookUrl = "${FederationConfig.publicBaseUrl}/api/webhooks/stripe",
                publicBaseUrl = FederationConfig.publicBaseUrl,
                paymentBankAccountConfigured = settingsRow?.get(OrganizationSettingsTable.paymentBankAccountId) != null,
                contributionIncomeAccountConfigured = settingsRow?.get(OrganizationSettingsTable.contributionIncomeAccountId) != null,
                donationIncomeAccountConfigured = settingsRow?.get(OrganizationSettingsTable.donationIncomeAccountId) != null,
                paymentFeeAccountConfigured = settingsRow?.get(OrganizationSettingsTable.paymentFeeAccountId) != null,
                eventIncomeAccountConfigured = settingsRow?.get(OrganizationSettingsTable.eventIncomeAccountId) != null,
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * See class KDoc "[checkoutCreateRateLimiter]" -- throws [ConflictException] once the per-member
     * budget is exceeded. Modelled on [SepaService]'s own `requireWithinRate` helper. Called BEFORE
     * any outbound Stripe call (and before [requirePaymentGatewayUsable]'s own DB reads) so an
     * over-budget caller is rejected as cheaply as possible.
     */
    private fun requireWithinCheckoutCreateRate(memberId: Uuid) {
        if (!checkoutCreateRateLimiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    /**
     * The three-part usability gate -- see [IPaymentGatewayService] class KDoc. Modelled exactly on
     * [SepaService.requireSepaUsable], including its own-`transaction {}` self-containment (that
     * function was non-functional for a whole release because it read outside a transaction --
     * see its own KDoc "why -- fixed").
     */
    private fun requirePaymentGatewayUsable() {
        val settingsRow =
            transaction {
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
            }
        if (!settingsRow[OrganizationSettingsTable.paymentGatewayEnabled]) {
            throw ConflictException("Zahlungsannahme ist fuer diese Organisation nicht aktiviert.")
        }
        if (!paymentGatewayDisclaimerIsCurrentlyAcknowledged()) {
            throw ConflictException("Der aktuelle Zahlungsdienstleister-Rechtshinweis wurde noch nicht (erneut) bestaetigt.")
        }
        when (pspConfigState) {
            is PspConfigState.NotConfigured ->
                throw ConflictException(
                    "Kein Zahlungsdienstleister konfiguriert -- ${PspConfig.ENV_SECRET_KEY}/${PspConfig.ENV_WEBHOOK_SIGNING_SECRET} " +
                        "sind nicht gesetzt.",
                )
            is PspConfigState.Incomplete ->
                throw ConflictException("Die Zahlungsdienstleister-Konfiguration ist unvollstaendig.")
            is PspConfigState.Configured -> Unit
        }
        val provider = settingsRow[OrganizationSettingsTable.paymentGatewayProvider]
        if (provider != PaymentProvider.STRIPE) {
            throw ConflictException("Der konfigurierte Zahlungsdienstleister ist nicht STRIPE.")
        }
    }

    private fun persistCheckoutSessionOrThrow(
        stripeResult: StripeCheckoutResult,
        checkoutSessionId: Uuid,
        intent: PaymentIntent,
        contributionId: Uuid?,
        memberId: Uuid?,
        externalDonorId: Uuid?,
        embedOrigin: String?,
        amount: BigDecimal,
        donorCategory: DonorCategory?,
        purpose: String?,
        now: LocalDateTime,
    ): CheckoutSessionDto {
        val success =
            stripeResult as? StripeCheckoutResult.Success
                ?: throw ConflictException(
                    "Stripe-Checkout konnte nicht erzeugt werden: ${(stripeResult as StripeCheckoutResult.Failure).message}",
                )
        val pspConfig = (pspConfigState as PspConfigState.Configured).config
        val expiresAt = (now.toInstant(TimeZone.UTC) + pspConfig.checkoutTtlMinutes.minutes).toLocalDateTime(TimeZone.UTC)
        return transaction {
            PspCheckoutSessions.create(
                id = checkoutSessionId,
                provider = PaymentProvider.STRIPE,
                providerSessionId = success.sessionId,
                intent = intent,
                contributionId = contributionId,
                memberId = memberId,
                externalDonorId = externalDonorId,
                eventRegistrationId = null,
                embedOrigin = embedOrigin,
                amount = amount.setScale(2, RoundingMode.UNNECESSARY),
                currency = "EUR",
                donorCategory = donorCategory,
                purpose = purpose,
                createdAt = now,
                expiresAt = expiresAt,
                providerIdempotencyKey = success.idempotencyKey,
                redirectUrl = success.redirectUrl,
            )
            val row = PaymentCheckoutSessionTable.selectAll().where { PaymentCheckoutSessionTable.id eq checkoutSessionId }.single()
            row.toCheckoutSessionDto()
        }
    }

    private fun ResultRow.toCheckoutSessionDto(): CheckoutSessionDto {
        val transactionRow =
            PaymentTransactionTable
                .selectAll()
                .where { PaymentTransactionTable.checkoutSessionId eq this@toCheckoutSessionDto[PaymentCheckoutSessionTable.id] }
                .singleOrNull()
        return CheckoutSessionDto(
            id = this[PaymentCheckoutSessionTable.id].toString(),
            provider = this[PaymentCheckoutSessionTable.provider],
            intent = this[PaymentCheckoutSessionTable.intent],
            status = this[PaymentCheckoutSessionTable.status],
            amount = this[PaymentCheckoutSessionTable.amount],
            currency = this[PaymentCheckoutSessionTable.currency],
            contributionId = this[PaymentCheckoutSessionTable.contributionId]?.toString(),
            redirectUrl = this[PaymentCheckoutSessionTable.redirectUrl],
            createdAt = this[PaymentCheckoutSessionTable.createdAt],
            expiresAt = this[PaymentCheckoutSessionTable.expiresAt],
            paymentTransactionId = transactionRow?.get(PaymentTransactionTable.id)?.toString(),
            journalEntryId = transactionRow?.get(PaymentTransactionTable.journalEntryId)?.toString(),
        )
    }

    private fun ResultRow.toPaymentTransactionDto(): PaymentTransactionDto {
        val memberId = this[PaymentTransactionTable.memberId]
        return PaymentTransactionDto(
            id = this[PaymentTransactionTable.id].toString(),
            provider = this[PaymentTransactionTable.provider],
            providerPaymentId = this[PaymentTransactionTable.providerPaymentId],
            status = this[PaymentTransactionTable.status],
            amount = this[PaymentTransactionTable.amount],
            currency = this[PaymentTransactionTable.currency],
            feeAmount = this[PaymentTransactionTable.feeAmount],
            intent = this[PaymentTransactionTable.intent],
            contributionId = this[PaymentTransactionTable.contributionId]?.toString(),
            memberId = memberId?.toString(),
            memberDisplayName = memberId?.let { memberDisplayName(it) },
            donorCategory = this[PaymentTransactionTable.donorCategory],
            receivedAt = this[PaymentTransactionTable.receivedAt],
            journalEntryId = this[PaymentTransactionTable.journalEntryId]?.toString(),
            reconciliationNote = this[PaymentTransactionTable.reconciliationNote],
        )
    }

    private fun loadPaymentGatewaySettingsDto(): PaymentGatewaySettingsDto {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        val lastAck =
            PaymentGatewayComplianceAcknowledgmentTable
                .selectAll()
                .orderBy(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return PaymentGatewaySettingsDto(
            paymentGatewayEnabled = settingsRow[OrganizationSettingsTable.paymentGatewayEnabled],
            paymentGatewayProvider = settingsRow[OrganizationSettingsTable.paymentGatewayProvider],
            lastAcknowledgedByDisplayName =
                lastAck?.let { memberDisplayName(it[PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId]) },
            lastAcknowledgedAt = lastAck?.get(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt),
            lastDisclaimerVersion = lastAck?.get(PaymentGatewayComplianceAcknowledgmentTable.disclaimerVersion),
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]
}

private fun String.toContributionUuid(): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Invalid contribution id: $this") }

private fun String.toPaymentCheckoutSessionUuid(): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid checkout session id: $this") }

private fun String.toPaymentMemberUuid(): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Invalid member id: $this") }

/**
 * Security Round 1 (2026-08-19, SHOULD-3): `true` iff the most recently written
 * [PaymentGatewayComplianceAcknowledgmentTable] row's `disclaimerVersion` equals the CURRENT
 * [PaymentGatewayComplianceDisclaimer.VERSION] -- see [network.lapis.cloud.server.rpc
 * .sepaDisclaimerIsCurrentlyAcknowledged] KDoc for the identical rationale, mechanism, and "no
 * current call site" status this exact mirror shares. Welle V1.2.8: now has real call sites
 * ([PaymentGatewayService.requirePaymentGatewayUsable], `network.lapis.cloud.server.routes
 * .PspWebhookRoutes`).
 */
fun paymentGatewayDisclaimerIsCurrentlyAcknowledged(): Boolean =
    transaction {
        PaymentGatewayComplianceAcknowledgmentTable
            .selectAll()
            .orderBy(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(PaymentGatewayComplianceAcknowledgmentTable.disclaimerVersion) == PaymentGatewayComplianceDisclaimer.VERSION
    }

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- the member who last
 * acknowledged the payment-gateway compliance disclaimer, the verantwortliche Mensch hinter einer
 * systemausgelösten Gateway-Buchung ohne handelndes Mitglied (anonyme Widget-Spende). Exakt
 * dieselbe Rolle, die [network.lapis.cloud.server.payment.dunning.lastComplianceAcknowledgerMemberId]
 * im Mahnwesen spielt -- diese Funktion ist deren `null`-zurückgebende Schwester für den
 * Zahlungsgateway-Disclaimer statt des Mahnwesen-Disclaimers.
 *
 * **transaction-frei** (anders als das benachbarte [paymentGatewayDisclaimerIsCurrentlyAcknowledged],
 * das seine eigene `transaction {}` öffnet) -- der einzige Aufrufer ist [network.lapis.cloud.server
 * .payment.psp.PspWebhookIngestion] INNERHALB seiner einen umschließenden `transaction {}`.
 *
 * `null` statt `error(...)` bei fehlender Zeile -- anders als die Mahnwesen-Schwester. Ein throw
 * hier würde die gesamte Zahlungsaufzeichnung zurückrollen und die Tatsache verlieren, dass das
 * Geld überhaupt angekommen ist; der Aufrufer degradiert stattdessen auf `Unposted` mit
 * `reconciliation_note` (Haus-Posture "degradierend statt scheiternd", [DonationPostingBridge]
 * KDoc).
 */
internal fun lastPaymentGatewayComplianceAcknowledgerMemberIdOrNull(): Uuid? =
    PaymentGatewayComplianceAcknowledgmentTable
        .selectAll()
        .orderBy(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.get(PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId)

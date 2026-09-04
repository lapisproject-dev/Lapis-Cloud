package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.events.EventCapacityGuard
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.WaitlistPromotion
import network.lapis.cloud.server.events.mailPromotion
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.rpc.ContributionPaymentEvents
import network.lapis.cloud.server.rpc.ContributionPostingBridge
import network.lapis.cloud.server.rpc.DonationPostingBridge
import network.lapis.cloud.server.rpc.EventFeePostingBridge
import network.lapis.cloud.server.rpc.lastPaymentGatewayComplianceAcknowledgerMemberIdOrNull
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.server.webhook.WebhookPayloads
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionSnapshot
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Outcome of [PspWebhookIngestion.ingestCheckoutCompleted] -- the caller (`PspWebhookRoutes`) maps this to an HTTP status and a [PspWebhookEventLog] row. */
sealed interface CheckoutCompletedIngestionOutcome {
    data class Processed(
        val paymentTransactionId: Uuid,
        val journalEntryId: Uuid,
    ) : CheckoutCompletedIngestionOutcome

    /** A unique-index violation on `(provider, provider_event_id)`, or a session already `COMPLETED` -- the whole delivery is a no-op. */
    data object Duplicate : CheckoutCompletedIngestionOutcome

    /** The payment genuinely arrived but could not be posted -- see [note] for the treasurer-facing reason. [paymentTransactionId] is `null` only for the "unknown checkout session" branch, where no row was ever inserted. */
    data class Unposted(
        val paymentTransactionId: Uuid?,
        val note: String,
    ) : CheckoutCompletedIngestionOutcome
}

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- the single place a gateway payment
 * becomes money. **`transaction`-free by contract in the sense that it opens exactly ONE
 * `transaction {}` covering the whole ingestion** (unlike the other files in `payment/psp/`, which
 * are transaction-free and rely on their caller). Called from `PspWebhookRoutes` AFTER Stripe
 * signature verification has already succeeded (see that file's handler-ordering KDoc) -- this
 * function itself performs NO signature check.
 *
 * **Idempotency**: the `payment_transaction` INSERT is the idempotency anchor
 * (`uq_payment_transaction_provider_event`, F2) -- attempted, with a unique-index violation treated
 * as [CheckoutCompletedIngestionOutcome.Duplicate]. `FederationReplayGuard` is deliberately NOT
 * reused here -- its own KDoc calls it "per-JVM-instance state", too weak for money.
 *
 * **Ordering inside the transaction (security-review-critical, see class KDoc "audit ordering"
 * below):**
 * 1. Lock the `payment_checkout_session` row (`forUpdate()`) by `(provider, provider_session_id)`.
 *    Absent -> [CheckoutCompletedIngestionOutcome.Unposted] (no `payment_transaction` row is ever
 *    inserted for an unknown session -- there is nothing authoritative to reconcile against).
 *    Already `COMPLETED` -> [CheckoutCompletedIngestionOutcome.Duplicate].
 * 2. Insert `payment_transaction` using the LOCKED session's own `amount`/`currency`/`intent`/
 *    `contributionId`/`memberId`/`donorCategory` -- NEVER the webhook body's own values (those are
 *    used ONLY for the equality check in step 3). Unique violation on
 *    `(provider, provider_event_id)` -> [CheckoutCompletedIngestionOutcome.Duplicate], nothing else
 *    in this transaction commits.
 * 3. Reconcile: convert Stripe's `amount_total` (integer MINOR UNITS) to a scale-2 [BigDecimal]
 *    (`movePointLeft(2)`, NEVER via [Double]) and compare against the session's own `amount`
 *    (`compareTo`, not `equals`); compare `currency` case-insensitively. Any mismatch -> do NOT
 *    post; [CheckoutCompletedIngestionOutcome.Unposted] with an actionable `reconciliation_note`.
 *    This is the amount/currency-tampering defense.
 * 4. Update `payment_checkout_session` -> `COMPLETED`.
 * 5. For `intent = CONTRIBUTION`: flip the contribution with the SAME guarded
 *    `UPDATE ... WHERE status NOT IN SETTLED` idiom `ContributionService.markContributionPaid`
 *    uses, WIDENED to also exclude `ContributionStatusSets.DEBIT_IN_FLIGHT` (a SEPA batch may
 *    already be collecting this exact contribution -- see
 *    [network.lapis.cloud.server.rpc.PaymentGatewayService.createContributionCheckout]'s own
 *    RPC-side rejection of the same case).
 *    Zero rows matched (already settled, or a debit is in flight) -> do NOT post;
 *    [CheckoutCompletedIngestionOutcome.Unposted] (double-collection guard).
 * 6. Post to accounting: [ContributionPostingBridge.postContributionPayment] (CONTRIBUTION) or
 *    [DonationPostingBridge.postDonationPayment] (DONATION). `null` (unconfigured mapping/inactive
 *    account/PROHIBITED §25 PartG verdict, all "degrade, don't throw") ->
 *    [CheckoutCompletedIngestionOutcome.Unposted]. **A thrown [ConflictException] (unbalanced
 *    postings) is NOT caught here** -- it propagates, rolling back this whole transaction; the
 *    caller (`PspWebhookRoutes`) catches it, responds `500` (Stripe retries), and logs the
 *    `psp_webhook_event` row itself, in ITS OWN transaction, with `outcome = REJECTED`,
 *    `reject_reason = "POSTING_UNBALANCED"`.
 * 7. Write the `PAYMENT_TRANSACTION` audit entry.
 * 8. Update `payment_transaction` -> `journalEntryId`/`reconciledAt`/`reconciledBy`.
 *
 * **Audit ordering (deadlock-avoidance contract, see [AuditLogRecorder] KDoc)**: step 6's posting
 * bridge internally locks `ledger_account` rows (`CashRegisterGuard`) BEFORE it makes its own
 * [AuditLogRecorder.record] call (for `JOURNAL_ENTRY`) -- so this function's OWN step-7
 * `PAYMENT_TRANSACTION` audit call must run AFTER step 6, not before, even though step 7 is
 * numbered ahead of step 8 in the plan's prose. Steps 4/5/8 all touch rows already locked/inserted
 * earlier in this same transaction, so they take no NEW lock.
 */
object PspWebhookIngestion {
    /** `internal` -- [StripeWebhookEvent] is itself `internal` (a wire-only type), so this function must stay non-public too; its only caller, `PspWebhookRoutes`, is in the same module. */
    internal fun ingestCheckoutCompleted(
        event: StripeWebhookEvent,
        bodyBytes: ByteArray,
    ): CheckoutCompletedIngestionOutcome =
        transaction {
            val session = event.data.eventObject

            // Step 1 -- lock the checkout session.
            val sessionRow =
                PspCheckoutSessions.findByProviderSessionForUpdate(
                    provider = PaymentProvider.STRIPE,
                    providerSessionId = session.id,
                )
            if (sessionRow == null) {
                logger.warn {
                    "PspWebhookIngestion: no payment_checkout_session found for Stripe session ${session.id} (event ${event.id})"
                }
                return@transaction CheckoutCompletedIngestionOutcome.Unposted(
                    paymentTransactionId = null,
                    note = "Unbekannte Checkout-Session",
                )
            }
            if (sessionRow[PaymentCheckoutSessionTable.status] == PaymentCheckoutSessionStatus.COMPLETED) {
                return@transaction CheckoutCompletedIngestionOutcome.Duplicate
            }

            val sessionAmount = sessionRow[PaymentCheckoutSessionTable.amount]
            val sessionCurrency = sessionRow[PaymentCheckoutSessionTable.currency]
            val sessionIntent = sessionRow[PaymentCheckoutSessionTable.intent]
            val sessionContributionId = sessionRow[PaymentCheckoutSessionTable.contributionId]
            // Welle V1.4.1b -- nullable since V16: an anonymous embed-widget donation has no member.
            val sessionMemberId = sessionRow[PaymentCheckoutSessionTable.memberId]
            val sessionExternalDonorId = sessionRow[PaymentCheckoutSessionTable.externalDonorId]
            val sessionDonorCategory = sessionRow[PaymentCheckoutSessionTable.donorCategory]
            val sessionId = sessionRow[PaymentCheckoutSessionTable.id]
            // Welle V1.4.3.1 -- non-null only for intent = EVENT_FEE.
            val sessionEventRegistrationId = sessionRow[PaymentCheckoutSessionTable.eventRegistrationId]

            // Step 2 -- the idempotency anchor: attempt the INSERT first, a unique violation IS the
            // duplicate detector (F2/§4.4) -- no pre-check SELECT against payment_transaction.
            val paymentTransactionId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val inserted =
                runCatching {
                    PaymentTransactionTable.insert {
                        it[id] = paymentTransactionId
                        it[provider] = PaymentProvider.STRIPE
                        it[providerEventId] = event.id
                        it[providerPaymentId] = session.paymentIntent ?: session.id
                        it[status] = PaymentTransactionStatus.CAPTURED
                        it[amount] = sessionAmount
                        it[currency] = sessionCurrency
                        it[feeAmount] = null
                        it[intent] = sessionIntent
                        it[contributionId] = sessionContributionId
                        it[memberId] = sessionMemberId
                        it[payerReference] = session.customer
                        it[receivedAt] = now
                        it[reconciledAt] = null
                        it[reconciledBy] = null
                        it[journalEntryId] = null
                        it[reconciliationNote] = null
                        it[rawPayloadDigest] = sha256Hex(bodyBytes)
                        it[checkoutSessionId] = sessionId
                        it[donorCategory] = sessionDonorCategory
                    }
                }
            if (inserted.isFailure) {
                val cause = inserted.exceptionOrNull()
                // The uq_payment_transaction_provider_event unique-constraint violation this
                // function's own KDoc documents -- caught here (rather than propagating) exactly as
                // documented, same "ExposedSQLException IS the constraint violation" idiom
                // DunningIssuance/RegistrationService/AccountingService/PoliticianService/
                // ElectionService already establish for their own first-write races.
                if (cause is ExposedSQLException) {
                    return@transaction CheckoutCompletedIngestionOutcome.Duplicate
                }
                throw cause ?: IllegalStateException("payment_transaction insert failed with no exception")
            }

            // Step 3 -- reconcile the webhook's own amount/currency against the server-created session.
            val webhookAmount = session.amountTotal?.let { minorUnitsToDecimal(it) }
            val webhookCurrency = session.currency
            val amountMismatch = webhookAmount == null || webhookAmount.compareTo(sessionAmount) != 0
            val currencyMismatch = webhookCurrency == null || !webhookCurrency.equals(sessionCurrency, ignoreCase = true)
            if (amountMismatch || currencyMismatch) {
                val note =
                    "Betrag/Waehrung weicht von der serverseitig erzeugten Checkout-Session ab " +
                        "(erwartet $sessionAmount $sessionCurrency, erhalten ${webhookAmount ?: "?"} ${webhookCurrency ?: "?"})"
                PaymentTransactionTable.update({ PaymentTransactionTable.id eq paymentTransactionId }) {
                    it[reconciliationNote] = note
                }
                logger.warn { "PspWebhookIngestion: amount/currency mismatch for payment_transaction $paymentTransactionId -- $note" }
                return@transaction CheckoutCompletedIngestionOutcome.Unposted(paymentTransactionId = paymentTransactionId, note = note)
            }
            // Security audit finding (Welle V1.2.8, MINOR/hardening) -- checkout.session.completed
            // fires even for payment_status in {"unpaid", "no_payment_required"} on delayed/async
            // payment methods; NOT reachable today because StripeCheckoutClient hard-codes
            // payment_method_types[0]="card" (completed always implies paid for card), but this makes
            // that assumption explicit here instead of leaving it implicit in a single line of a
            // different file. A null payment_status (older/mocked payloads) is treated as acceptable
            // rather than rejected, to avoid breaking existing fixtures/tests that predate this field.
            if (session.paymentStatus != null && session.paymentStatus != "paid") {
                val note =
                    "Stripe payment_status ist '${session.paymentStatus}', nicht 'paid' -- vermutlich eine " +
                        "verzoegerte Zahlart, deren Geld noch nicht eingetroffen ist."
                PaymentTransactionTable.update({ PaymentTransactionTable.id eq paymentTransactionId }) {
                    it[reconciliationNote] = note
                }
                logger.warn {
                    "PspWebhookIngestion: payment_status '${session.paymentStatus}' != paid for payment_transaction $paymentTransactionId -- $note"
                }
                return@transaction CheckoutCompletedIngestionOutcome.Unposted(paymentTransactionId = paymentTransactionId, note = note)
            }

            // Step 4.
            PspCheckoutSessions.markCompleted(id = sessionId, completedAt = now)

            // Fix (Review MAJOR #1, Welle V1.4.1b): the money has now genuinely arrived (amount/
            // currency reconciled, payment_status acceptable, session flipped COMPLETED above) --
            // promote the anonymous embed-widget donor from PENDING (see AnonymousDonationCheckout
            // KDoc) to a real, visible donor. Unconditional on whether the posting below itself
            // succeeds or degrades: the donor identity is real regardless of an unrelated accounting-
            // configuration gap.
            if (sessionExternalDonorId != null) {
                ExternalDonorTable.update({ ExternalDonorTable.id eq sessionExternalDonorId }) {
                    it[active] = true
                }
            }

            // V1.4.1b -- Abzweig VOR accountRoleFor()/jedem Zugriff auf die Account-Tabelle und VOR
            // jeder Senke, die ein nicht-nullables Mitglied verlangt (journal_entry.created_by ist
            // NOT NULL, siehe V16-Migrationskopf). Ein einziger Elvis-Operator-Ausdruck mit EINER
            // gemeinsamen reconciliation_note fuer beide degradierenden Ursachen (kein Mitglied in
            // der Session, keine Zahlungsgateway-Disclaimer-Bestaetigung mehr vorhanden) -- der
            // Kassenwart erkennt am Notiztext, dass keine verantwortliche Person ermittelbar war,
            // unabhaengig davon, welche der beiden Ursachen zutraf.
            val actorMemberId: Uuid =
                sessionMemberId
                    ?: lastPaymentGatewayComplianceAcknowledgerMemberIdOrNull()
                    ?: return@transaction unpostedWithNote(
                        paymentTransactionId = paymentTransactionId,
                        note =
                            "Anonyme Spende ohne bestaetigten Zahlungsdienstleister-Disclaimer -- " +
                                "keine verantwortliche Person fuer die Buchung ermittelbar.",
                    )
            val actorRole = accountRoleFor(memberId = actorMemberId)

            // Step 5 -- CONTRIBUTION: guarded status flip, same idiom as ContributionService.markContributionPaid.
            if (sessionIntent == PaymentIntent.CONTRIBUTION) {
                // V1.4.1b -- an anonymous CONTRIBUTION-intent session is structurally unreachable:
                // the anonymous embed-widget checkout endpoint (AnonymousDonationCheckout) always
                // sets intent = DONATION, and chk_payment_checkout_session_embed_anonymous requires
                // donor_category = ANONYMOUS whenever embed_origin is set, which in turn only ever
                // pairs with a DONATION-intent session in practice.
                //
                // Fix (Review MINOR/LATENT #5): this invariant used to be asserted with `check(...)`,
                // the only throwing call anywhere in this webhook-ingestion transaction -- an
                // IllegalStateException here would roll back the very payment_transaction INSERT this
                // function's own KDoc calls the idempotency anchor (step 2), leaving NO trace the
                // money ever arrived while Stripe retries the same webhook delivery for days. Same
                // "degradierend statt scheiternd" posture every OTHER branch of this function already
                // follows -- degrade to Unposted with an actionable reconciliation_note instead.
                if (sessionMemberId == null) {
                    return@transaction unpostedWithNote(
                        paymentTransactionId = paymentTransactionId,
                        note =
                            "Zahlungssitzung mit intent=CONTRIBUTION aber ohne Mitglieds-Referenz " +
                                "(unerwarteter Zustand, sollte durch die Checkout-Erzeugung ausgeschlossen sein) " +
                                "-- Zahlung nicht gebucht, manuell pruefen.",
                    )
                }
                val contributionId =
                    sessionContributionId
                        ?: return@transaction unpostedWithNote(
                            paymentTransactionId = paymentTransactionId,
                            note = "Checkout-Session ohne Beitrags-Referenz (inkonsistenter Zustand)",
                        )
                // MAJOR (code review, Welle V1.2.8): widened from ContributionStatusSets.SETTLED
                // alone to also exclude DEBIT_IN_FLIGHT -- a SEPA batch may already be collecting
                // this exact contribution when the webhook lands, and flipping it to PAID here would
                // double-count the money once the SEPA batch itself later settles. Same "not posted,
                // reconciliation_note explains why" degrade-don't-throw posture as the already-
                // settled branch below -- see PaymentGatewayService's own createContributionCheckout
                // DEBIT_IN_FLIGHT rejection for the RPC-side half of this guard.
                val updated =
                    ContributionTable.update({
                        (ContributionTable.id eq contributionId) and
                            (ContributionTable.status notInList (ContributionStatusSets.SETTLED + ContributionStatusSets.DEBIT_IN_FLIGHT))
                    }) {
                        it[status] = ContributionStatus.PAID
                        it[paidAt] = now
                        it[paidAmount] = sessionAmount
                        it[paymentMethod] = ContributionPaymentMethod.GATEWAY
                    }
                if (updated == 0) {
                    return@transaction unpostedWithNote(
                        paymentTransactionId = paymentTransactionId,
                        note =
                            "Beitrag war bereits ausgeglichen oder ist aktuell in einem laufenden SEPA-Lastschrifteinzug " +
                                "-- Zahlung nicht gebucht, Rueckerstattung pruefen.",
                    )
                }
            }

            // Step 5b -- EVENT_FEE (Welle V1.4.3.1): guarded confirm, same "0 rows -> Unposted"
            // idiom as the CONTRIBUTION branch above. **Race the implementer must handle**: the
            // webhook can arrive AFTER the 30-minute hold (EventPolicy.STANDARD_HOLD) already
            // expired -- Stripe pays out regardless. By then the row may already be EXPIRED and the
            // seat possibly re-offered to a waitlist successor. The guarded
            // `WHERE status = 'PENDING_PAYMENT'` update catches exactly that and degrades to
            // Unposted with a refund-pointing note -- NEVER silently flip to CONFIRMED and risk
            // over-committing capacity. The money is still recorded via `payment_transaction`; the
            // refund itself is a human process. This is the documented exception to "never post a
            // payment without confirming the seat", not the normal case.
            if (sessionIntent == PaymentIntent.EVENT_FEE) {
                val registrationId =
                    sessionEventRegistrationId
                        ?: return@transaction unpostedWithNote(
                            paymentTransactionId = paymentTransactionId,
                            note =
                                "Zahlungssitzung mit intent=EVENT_FEE aber ohne Anmeldungs-Referenz " +
                                    "(unerwarteter Zustand) -- Zahlung nicht gebucht, manuell pruefen.",
                        )
                val confirmed = EventStore.confirmRegistrationIfPending(id = registrationId, now = now)
                if (confirmed == 0) {
                    return@transaction unpostedWithNote(
                        paymentTransactionId = paymentTransactionId,
                        note =
                            "Anmeldung war bereits storniert/abgelaufen, als die Zahlung eintraf -- " +
                                "Zahlung nicht gebucht, Rueckerstattung pruefen.",
                    )
                }
            }

            // Step 6 -- post to accounting. A thrown ConflictException (unbalanced) is deliberately
            // NOT caught here, see class KDoc.
            val journalEntryId =
                when (sessionIntent) {
                    PaymentIntent.CONTRIBUTION ->
                        requireNotNull(sessionContributionId).let { contributionId ->
                            ContributionPostingBridge.postContributionPayment(
                                contributionId = contributionId,
                                paidAmount = sessionAmount,
                                paidAt = now,
                                source = ContributionPaymentMethod.GATEWAY,
                                providerFee = null,
                                actorMemberId = actorMemberId,
                                actorRole = actorRole,
                                voucherReference = "PSP-STRIPE-${session.paymentIntent ?: session.id}",
                            )
                        }
                    PaymentIntent.DONATION ->
                        DonationPostingBridge.postDonationPayment(
                            paymentTransactionId = paymentTransactionId,
                            paidAmount = sessionAmount,
                            paidAt = now,
                            providerFee = null,
                            // V1.4.1b -- sessionMemberId (NOT actorMemberId): a real donor's own
                            // identity for the member path, null for an anonymous embed-widget
                            // donation, where sessionExternalDonorId carries the donor identity
                            // instead. actorMemberId is the responsible-human bookkeeping actor,
                            // never the donor -- these two diverge exactly for the anonymous case.
                            donorMemberId = sessionMemberId,
                            externalDonorId = sessionExternalDonorId,
                            donorCategory = sessionDonorCategory,
                            actorMemberId = actorMemberId,
                            actorRole = actorRole,
                            voucherReference = "PSP-STRIPE-${session.paymentIntent ?: session.id}",
                        )
                    PaymentIntent.EVENT_FEE ->
                        EventFeePostingBridge.postEventFeePayment(
                            paymentTransactionId = paymentTransactionId,
                            eventRegistrationId = requireNotNull(sessionEventRegistrationId),
                            paidAmount = sessionAmount,
                            paidAt = now,
                            providerFee = null,
                            actorMemberId = actorMemberId,
                            actorRole = actorRole,
                            voucherReference = "PSP-STRIPE-${session.paymentIntent ?: session.id}",
                        )
                }

            if (journalEntryId == null) {
                return@transaction unpostedWithNote(
                    paymentTransactionId = paymentTransactionId,
                    note = "Kontenzuordnung unvollstaendig -- bitte in der Zahlungs-Konfiguration nachziehen.",
                )
            }

            // Welle V1.3.2 "Webhooks" (ausgehend) -- fires only once the payment is actually
            // posted to accounting (journalEntryId confirmed non-null above), for BOTH intents.
            // Placed here (not immediately after Step 5's ContributionTable.update) so a
            // CONTRIBUTION whose posting fails for an unconfigured-mapping reason (still `Unposted`,
            // see the branch above) does not fire a webhook for a payment this org's own ledger
            // never actually recorded.
            when (sessionIntent) {
                PaymentIntent.CONTRIBUTION ->
                    requireNotNull(sessionContributionId).let { contributionId ->
                        ContributionPaymentEvents.publishPaid(
                            contributionId = contributionId,
                            paidAt = now,
                            amount = sessionAmount,
                            transactionId = paymentTransactionId.toString(),
                        )
                    }
                PaymentIntent.DONATION ->
                    WebhookEventPublisher.publish(
                        eventType = WebhookEventType.DONATION_RECEIVED,
                        entityId = paymentTransactionId,
                        occurredAt = now,
                        payment =
                            WebhookPayloads.PaymentEventDetails(
                                amount = sessionAmount,
                                currency = sessionCurrency,
                                transactionId = paymentTransactionId.toString(),
                            ),
                    )
                PaymentIntent.EVENT_FEE ->
                    WebhookEventPublisher.publish(
                        eventType = WebhookEventType.EVENT_REGISTRATION_PAID,
                        entityId = paymentTransactionId,
                        occurredAt = now,
                        payment =
                            WebhookPayloads.PaymentEventDetails(
                                amount = sessionAmount,
                                currency = sessionCurrency,
                                transactionId = paymentTransactionId.toString(),
                            ),
                    )
            }

            // Step 7 -- our own PAYMENT_TRANSACTION audit entry, AFTER the bridge's own ledger_account
            // locking + JOURNAL_ENTRY audit call (see class KDoc "Audit ordering").
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.PAYMENT_TRANSACTION,
                entityId = paymentTransactionId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        PaymentTransactionSnapshot.serializer(),
                        PaymentTransactionSnapshot(
                            provider = PaymentProvider.STRIPE,
                            providerEventId = event.id,
                            providerPaymentId = session.paymentIntent ?: session.id,
                            status = PaymentTransactionStatus.CAPTURED,
                            amount = sessionAmount,
                            currency = sessionCurrency,
                            intent = sessionIntent,
                            contributionId = sessionContributionId?.toString(),
                            memberId = sessionMemberId?.toString(),
                            donorCategory = sessionDonorCategory,
                            journalEntryId = journalEntryId.toString(),
                            providerBodyDigest = sha256Hex(bodyBytes),
                        ),
                    ),
            )

            // Step 8.
            PaymentTransactionTable.update({ PaymentTransactionTable.id eq paymentTransactionId }) {
                it[PaymentTransactionTable.journalEntryId] = journalEntryId
                it[reconciledAt] = now
                it[reconciledBy] = actorMemberId
            }

            CheckoutCompletedIngestionOutcome.Processed(paymentTransactionId = paymentTransactionId, journalEntryId = journalEntryId)
        }

    /**
     * Marks `checkout.session.expired` -- no accounting touched. `internal`, same reason as
     * [ingestCheckoutCompleted].
     *
     * **Fix (Review MAJOR #1, Welle V1.4.1b, orphan `external_donor` growth)**: an anonymous
     * embed-widget donor row is inserted as `active = false` (PENDING) the moment the checkout
     * session is created (see [AnonymousDonationCheckout] KDoc), before Stripe has confirmed
     * anything. If the donor abandons the checkout, Stripe delivers `checkout.session.expired` on
     * ITS OWN schedule (this server never sends Stripe an `expires_at` override -- see
     * [StripeCheckoutClient.createCheckoutSession] KDoc -- so in practice that is Stripe's 24-hour
     * default, NOT [PspConfig.checkoutTtlMinutes]) -- this codebase has NO scheduler/background-job
     * infrastructure to sweep for orphans separately (see
     * [network.lapis.cloud.server.federation.OidcBackChannelLogoutNotifier] KDoc), so this webhook
     * delivery IS the cleanup hook: locking the row here (rather than trusting
     * [PspCheckoutSessions.markExpiredIfStillCreated] blindly) both flips the session AND deletes it
     * together with its paired `external_donor` row in the SAME transaction, rather than leaving a
     * `PENDING` donor stranded forever. **This means an abandoned anonymous checkout stays visible
     * as a PENDING orphan for up to ~24 h, not [PspConfig.checkoutTtlMinutes]** -- the operator-
     * facing docs (README/`.env.example`) name `checkout.session.expired` as a MANDATORY dashboard
     * subscription precisely because this is the only cleanup path.
     *
     * Deleting both rows unconditionally assumes neither has any OTHER FK dependent at this point:
     * true for `external_donor` (nothing else references it before `active` flips true), but for
     * `payment_checkout_session` this holds only as long as [ingestCheckoutCompleted]'s step 2
     * (the `payment_transaction` INSERT with `checkout_session_id` set) never ran while the session
     * stayed `CREATED` -- which is the case for every branch reachable via a real
     * `checkout.session.expired` delivery today, since Stripe never re-expires a session it already
     * reported `completed` for. If a future change makes that reachable, the `deleteWhere` below
     * would violate `fk_payment_transaction_checkout_session_id` and surface as a loud 500/retry
     * loop rather than silently corrupting anything -- fail-closed, not a silent hazard.
     */
    internal fun ingestCheckoutExpired(
        event: StripeWebhookEvent,
        mailDispatcher: MailDispatcher,
    ): Boolean {
        val (handled, promotions) =
            transaction {
                val providerSessionId = event.data.eventObject.id
                val sessionRow =
                    PspCheckoutSessions.findByProviderSessionForUpdate(
                        provider = PaymentProvider.STRIPE,
                        providerSessionId = providerSessionId,
                    )
                // Same "never downgrade a COMPLETED session" guard markExpiredIfStillCreated's own
                // KDoc documents -- re-checked explicitly here because this function now branches on
                // the row's contents instead of trusting a blind conditional UPDATE's affected-row
                // count.
                if (sessionRow == null || sessionRow[PaymentCheckoutSessionTable.status] != PaymentCheckoutSessionStatus.CREATED) {
                    return@transaction false to emptyList<WaitlistPromotion>()
                }
                val sessionId = sessionRow[PaymentCheckoutSessionTable.id]
                val externalDonorId = sessionRow[PaymentCheckoutSessionTable.externalDonorId]
                val eventRegistrationId = sessionRow[PaymentCheckoutSessionTable.eventRegistrationId]
                if (externalDonorId != null) {
                    PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.id eq sessionId }
                    ExternalDonorTable.deleteWhere { ExternalDonorTable.id eq externalDonorId }
                    logger.info {
                        "PspWebhookIngestion: abandoned anonymous checkout session $sessionId (external_donor " +
                            "$externalDonorId) expired unconfirmed -- both rows deleted rather than left as orphans."
                    }
                    true to emptyList()
                } else if (eventRegistrationId != null) {
                    // Welle V1.4.3.1 -- EVENT_FEE: NEVER deletes (a registration is a fachlich
                    // workflow record, not a throwaway donor stub, see 39-events.kuml.kts file
                    // header). Marks the session EXPIRED, then the registration itself EXPIRED
                    // (freeing its seat) and sweeps the waitlist for a successor, all under the event
                    // row lock -- see EventCapacityGuard.expireHoldAndSweep KDoc.
                    //
                    // Note: [KDoc addendum to ingestCheckoutExpired's own "no FK dependents" claim
                    // below] this branch means payment_checkout_session CAN now have an FK dependent
                    // (event_registration, one-directionally referenced FROM this table, not the
                    // other way -- see 33-payments.kuml.kts) that the `externalDonorId != null`
                    // branch's `deleteWhere` never has to worry about, because THIS branch never
                    // deletes the session row at all.
                    PspCheckoutSessions.markExpiredIfStillCreated(provider = PaymentProvider.STRIPE, providerSessionId = providerSessionId)
                    val registrationRow = EventStore.getRegistrationOrNull(eventRegistrationId)
                    val sweepPromotions =
                        if (registrationRow != null) {
                            // Review MAJOR fix: this promotion list used to be discarded outright
                            // ("a webhook handler has no MailDispatcher access") -- the inline
                            // comment claiming "the next registration attempt on this event re-sweeps
                            // anyway" was wrong: `EventWaitlist.promoteWhileCapacityFree` only ever
                            // promotes the CURRENT `findWaitlistHead`, so once THIS sweep has already
                            // promoted that head off the waitlist, a later sweep simply never
                            // generates a `WaitlistPromotion` for them again -- their "a seat freed
                            // up" mail was lost for good, not merely delayed. `PspWebhookRoutes` now
                            // threads a real `MailDispatcher` down to this function so the mail can
                            // be sent AFTER this transaction commits, same discipline every other
                            // `EventCapacityGuard`/`EventWaitlist` trigger already follows.
                            EventCapacityGuard.expireHoldAndSweep(
                                eventId = registrationRow[EventRegistrationTable.eventId],
                                registrationId = eventRegistrationId,
                                now = DbClock.nowLocalDateTime(),
                            )
                        } else {
                            emptyList()
                        }
                    true to sweepPromotions
                } else {
                    PspCheckoutSessions.markExpiredIfStillCreated(provider = PaymentProvider.STRIPE, providerSessionId = providerSessionId)
                    true to emptyList()
                }
            }
        // MailDispatcher.enqueue must never run inside an open transaction {} -- see
        // EventCapacityGuard KDoc -- so this runs only after the transaction {} above has committed.
        promotions.forEach { it.mailPromotion(mailDispatcher) }
        return handled
    }

    private fun unpostedWithNote(
        paymentTransactionId: Uuid,
        note: String,
    ): CheckoutCompletedIngestionOutcome.Unposted {
        PaymentTransactionTable.update({ PaymentTransactionTable.id eq paymentTransactionId }) {
            it[reconciliationNote] = note
        }
        logger.warn { "PspWebhookIngestion: payment_transaction $paymentTransactionId not posted -- $note" }
        return CheckoutCompletedIngestionOutcome.Unposted(paymentTransactionId = paymentTransactionId, note = note)
    }

    private fun accountRoleFor(memberId: Uuid): AccountRole =
        AccountTable
            .selectAll()
            .where { AccountTable.memberId eq memberId }
            .singleOrNull()
            ?.get(AccountTable.role) ?: AccountRole.MEMBER

    /** Exact minor-units -> scale-2 [BigDecimal] conversion, NEVER via [Double] (e.g. `1234` -> `12.34`). */
    private fun minorUnitsToDecimal(minorUnits: Long): BigDecimal =
        BigDecimal(minorUnits).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY)
}

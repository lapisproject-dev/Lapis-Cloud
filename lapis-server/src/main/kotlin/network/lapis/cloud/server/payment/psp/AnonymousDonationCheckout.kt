package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.embed.EmbedDonationLimits
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.DonationVerdict
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator
import network.lapis.cloud.server.rpc.paymentGatewayDisclaimerIsCurrentlyAcknowledged
import network.lapis.cloud.shared.domain.DonationDuty
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Outcome of [AnonymousDonationCheckout.create]. */
internal sealed interface AnonymousDonationResult {
    data class Success(
        val redirectUrl: String,
    ) : AnonymousDonationResult

    /** Honeypot getroffen: HTTP 200, identische Antwortform, aber Weiterleitung auf die ABBRUCH-Seite -- kein Orakel für einen Bot. */
    data class HoneypotTripped(
        val redirectUrl: String,
    ) : AnonymousDonationResult

    data object AmountOutOfRange : AnonymousDonationResult

    data object GatewayUnavailable : AnonymousDonationResult

    data class ProhibitedByLaw(
        val reason: String,
    ) : AnonymousDonationResult

    data class StripeFailed(
        val message: String,
    ) : AnonymousDonationResult

    /**
     * Fix (Review MAJOR #4, Welle V1.4.1b): the strict, per-real-attempt Stripe-call budget was
     * exhausted. Deliberately a SEPARATE outcome from every branch above -- see [create] KDoc "two
     * limiters" for why this is checked immediately before the Stripe call rather than at the
     * route's early admission gate.
     */
    data class RateLimited(
        val retryAfterSeconds: Long,
    ) : AnonymousDonationResult
}

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- die serverseitige
 * Fachlogik hinter `POST /api/embed/v1/donation/checkout`, bewusst GETRENNT vom Route-Handler und
 * `transaction`-frei bis auf den einen expliziten Persistenz-Block (Schritt 8), exakt wie
 * [PspCheckoutSessions] es vormacht -- so ist die Prüfreihenfolge unit-testbar ohne einen
 * HTTP-Stack.
 *
 * **Die Reihenfolge in [create] ist verbindlich, jede Umstellung ist ein Review-Blocker:**
 * Honeypot -> Gateway-Verfügbarkeit -> Betragsprüfung -> §25 PartG -> Rate-Limit -> Stripe-Aufruf ->
 * Persistenz. Der Stripe-Aufruf steht bewusst VOR der Persistenz (Schritt 7 vor Schritt 8) -- ein
 * Stripe-Fehlschlag hinterlässt so nie eine tote `external_donor`-Zeile.
 *
 * **Zwei Limiter (Fix, Review MAJOR #4)**: [checkoutRateLimiter] ist der STRENGE, knappe
 * "3 echte Versuche/Stunde"-Kontostand -- absichtlich erst HIER geprüft, unmittelbar vor dem
 * eigentlichen Stripe-Aufruf, NICHT vom Route-Handler vor dem JSON-Decode. Ein Honeypot-Treffer,
 * ein nicht nutzbares Gateway, ein Betrag außerhalb des Rahmens oder ein §25-PROHIBITED-Verdikt
 * kosten dieses knappe Kontingent dadurch nicht mehr -- nur ein Aufruf, der tatsächlich bis zum
 * Stripe-Aufruf durchdringt, zählt. Der Route-Handler prüft VOR dem JSON-Decode zusätzlich einen
 * zweiten, deutlich großzügigeren Limiter als reine Flut-/DoS-Bremse (siehe `EmbedDonationRoutes`
 * KDoc).
 */
internal class AnonymousDonationCheckout(
    private val pspConfigState: PspConfigState,
    private val checkoutClient: StripeCheckoutClient?,
    private val baseUrl: String,
    private val checkoutRateLimiter: FederationInboxRateLimiter,
) {
    suspend fun create(
        amountEur: BigDecimal,
        honeypotValue: String?,
        canonicalOrigin: String,
        rateLimitKey: String,
    ): AnonymousDonationResult {
        // 1. Honeypot zuerst -- kein Stripe-Aufruf, keine DB-Zeile, kein Log-Eintrag mit Nutzereingabe.
        if (!honeypotValue.isNullOrBlank()) {
            return AnonymousDonationResult.HoneypotTripped(cancelUrl(canonicalOrigin))
        }

        // 2. Gateway-Verfügbarkeit. Niemals melden, WELCHE Bedingung fehlt (PspWebhookRoutes-
        // Präzedenz "never leak WHICH variable is missing to an unauthenticated caller").
        val settingsRow =
            transaction {
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
            }
        val gatewayEnabled = settingsRow?.get(OrganizationSettingsTable.paymentGatewayEnabled) ?: false
        val provider = settingsRow?.get(OrganizationSettingsTable.paymentGatewayProvider)
        val isPoliticalParty = settingsRow?.get(OrganizationSettingsTable.isPoliticalParty) ?: false
        val pspConfig = (pspConfigState as? PspConfigState.Configured)?.config
        val gatewayUsable =
            gatewayEnabled &&
                provider == PaymentProvider.STRIPE &&
                pspConfig != null &&
                checkoutClient != null &&
                paymentGatewayDisclaimerIsCurrentlyAcknowledged() &&
                EmbedDonationLimits.rangeIsUsable(pspConfig.maxCheckoutAmountEur)
        if (!gatewayUsable) {
            return AnonymousDonationResult.GatewayUnavailable
        }
        // pspConfig/checkoutClient are smart-cast non-null past this point: gatewayUsable's own
        // `&&` chain above short-circuits to false the moment either is null, so gatewayUsable ==
        // true implies both were non-null when that chain was evaluated.

        // 3. Betrag -- ausschließlich compareTo, nie equals/==.
        val effectiveMax = EmbedDonationLimits.effectiveMaxAmountEur(pspConfig.maxCheckoutAmountEur)
        if (amountEur.scale() > 2 ||
            amountEur.compareTo(EmbedDonationLimits.MIN_AMOUNT_EUR) < 0 ||
            amountEur.compareTo(effectiveMax) > 0
        ) {
            return AnonymousDonationResult.AmountOutOfRange
        }

        // 4. §25 PartG -- nur wenn isPoliticalParty. ZERO ist hier korrekt (per-donation-Regel,
        // nicht aggregiert -- siehe PartyDonationComplianceCalculator KDoc).
        if (isPoliticalParty) {
            val verdict =
                PartyDonationComplianceCalculator.check(
                    amount = amountEur,
                    category = DonorCategory.ANONYMOUS,
                    priorPostedTotalThisYear = BigDecimal.ZERO,
                )
            if (verdict.verdict == DonationVerdict.PROHIBITED) {
                logger.warn { "AnonymousDonationCheckout: PROHIBITED under §25 PartG -- ${verdict.reason}" }
                return AnonymousDonationResult.ProhibitedByLaw(verdict.reason ?: "PROHIBITED under §25 PartG")
            }
            if (DonationDuty.ANONYMOUS_FORWARDING_REQUIRED in verdict.duties) {
                // Defense-in-depth: Schritt 3 (effectiveMax <= MAX_AMOUNT_EUR ==
                // ANONYMOUS_FORWARDING_THRESHOLD_EUR) hätte das bereits fangen müssen -- ein
                // Betrag, der diese Pflicht auslöst, ist durch die Betragsgrenze strukturell
                // unerreichbar. Diese Zeile ist ein zweiter Gurt, kein regulärer Codepfad.
                logger.error {
                    "AnonymousDonationCheckout: ANONYMOUS_FORWARDING_REQUIRED duty triggered despite the amount " +
                        "ceiling -- this should be unreachable, EmbedDonationLimits.MAX_AMOUNT_EUR should have " +
                        "prevented it (amount=$amountEur)"
                }
                return AnonymousDonationResult.AmountOutOfRange
            }
        }

        // 5. Rate-Limit -- siehe Klassen-KDoc "Zwei Limiter". Erst HIER, unmittelbar vor dem
        // Stripe-Aufruf: jede Ablehnung oben (Honeypot/Gateway/Betrag/§25) hat dieses knappe
        // Kontingent noch NICHT belastet.
        if (!checkoutRateLimiter.checkAndRecord(rateLimitKey)) {
            return AnonymousDonationResult.RateLimited(retryAfterSeconds = checkoutRateLimiter.retryAfterSeconds(rateLimitKey))
        }

        // 6. UUIDs minten.
        val externalDonorId = Uuid.random()
        val checkoutSessionId = Uuid.random()

        // 7. Stripe-Aufruf VOR der Persistenz -- ein Fehlschlag hinterlässt keine external_donor-Zeile.
        val stripeResult =
            checkoutClient.createCheckoutSession(
                checkoutSessionId = checkoutSessionId.toString(),
                amount = amountEur,
                currency = "EUR",
                description = "Spende",
                returnUrls = StripeReturnUrls.embedDonation(baseUrl = baseUrl, canonicalOrigin = canonicalOrigin),
            )
        val success =
            stripeResult as? StripeCheckoutResult.Success
                ?: return AnonymousDonationResult.StripeFailed((stripeResult as StripeCheckoutResult.Failure).message)

        // 8. Eine einzige transaction {} -- beides oder nichts.
        val now = DbClock.nowLocalDateTime()
        val expiresAt = (now.toInstant(TimeZone.UTC) + pspConfig.checkoutTtlMinutes.minutes).toLocalDateTime(TimeZone.UTC)
        transaction {
            ExternalDonorTable.insert {
                it[id] = externalDonorId
                it[displayName] = "Online-Spende ohne Namensangabe"
                it[donorCategory] = DonorCategory.ANONYMOUS
                it[street] = null
                it[postalCode] = null
                it[city] = null
                it[country] = null
                // Fix (Review MAJOR #1, Welle V1.4.1b): PENDING, not yet a confirmed donor -- a
                // Stripe checkout that is abandoned/expires must never surface in the default
                // activeOnly=true donor picker/list (LedgerScreen/DonorsScreen). PspWebhookIngestion
                // flips this to true the moment the webhook confirms the money actually arrived;
                // PspWebhookIngestion.ingestCheckoutExpired deletes this row (together with its
                // payment_checkout_session) if the session instead expires unconfirmed -- see that
                // function's own KDoc for why deletion, not just deactivation, closes the unbounded
                // orphan-row growth this review finding reported.
                it[active] = false
            }
            PspCheckoutSessions.create(
                id = checkoutSessionId,
                provider = PaymentProvider.STRIPE,
                providerSessionId = success.sessionId,
                intent = PaymentIntent.DONATION,
                contributionId = null,
                memberId = null,
                externalDonorId = externalDonorId,
                eventRegistrationId = null,
                embedOrigin = canonicalOrigin,
                amount = amountEur.setScale(2, RoundingMode.UNNECESSARY),
                currency = "EUR",
                donorCategory = DonorCategory.ANONYMOUS,
                purpose = null,
                createdAt = now,
                expiresAt = expiresAt,
                providerIdempotencyKey = success.idempotencyKey,
                redirectUrl = success.redirectUrl,
            )
        }

        return AnonymousDonationResult.Success(redirectUrl = success.redirectUrl)
    }

    private fun cancelUrl(canonicalOrigin: String): String =
        StripeReturnUrls.embedDonation(baseUrl = baseUrl, canonicalOrigin = canonicalOrigin).cancelUrl
}

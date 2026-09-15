package network.lapis.cloud.server.payment.psp

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- exakter Spiegel von [PspConfigState]
 * (siehe dessen KDoc für die volle "opted in but broken must never silently degrade"-Begründung),
 * NUR für PayPal. Eigenständiges Objekt statt einer Erweiterung von [PspConfig] -- dieses Repo
 * hält bewusst `SmtpConfig`/`PspConfig`/`SepaConfig`/`DunningConfig`/`FinTsConfig` als getrennte
 * Single-Purpose-Objekte, auch wo ihre Form identisch ist.
 */
sealed interface PaypalConfigState {
    /** Keine `LAPIS_PAYPAL_*`-Variable ist gesetzt -- die ehrliche, offengelegte "kein PayPal"-Haltung. */
    data object NotConfigured : PaypalConfigState

    data class Configured(
        val config: PaypalConfig,
    ) : PaypalConfigState

    /**
     * Mindestens eine `LAPIS_PAYPAL_*`-Variable ist gesetzt, aber die Konfiguration fehlt oder ist
     * ungültig. **KRITISCH (Implementierungsplan §6.1)**: die drei geteilten `LAPIS_PSP_*`-
     * Zahlenknöpfe zählen NICHT als PayPal-Opt-in-Signal -- sie sind bereits Stripes Opt-in-Signal
     * (siehe [PspConfig.ALL_ENV_KEYS]), und ein Zählen für beide würde eine Stripe-only-Installation,
     * die `LAPIS_PSP_MAX_CHECKOUT_AMOUNT_EUR` gesetzt hat, fälschlich als [Incomplete] melden und
     * [PaypalStartupCheck] zum Absturz bringen.
     */
    data class Incomplete(
        val missing: List<String>,
        val invalid: List<String>,
    ) : PaypalConfigState
}

/**
 * Deployment-seitige PayPal-Zugangsdaten/-Einstellungen (Welle V1.2.8b, GitHub Issue #6). Folgt
 * [PspConfig] EXAKT: privater Konstruktor, eine [load]-Fabrik mit injizierbarer `env`-Lambda,
 * redigierendes [toString].
 *
 * **Niemals persistiert, niemals verschlüsselt gespeichert** -- exakt dieselbe Begründung wie
 * [PspConfig] KDoc "Never persisted, never encrypted-at-rest": eine einzige org-weite Deployment-
 * Credential, kein pro-Zeilen-Datum.
 *
 * **Fail-fast, nicht graduelle Degradation** -- siehe [PaypalStartupCheck].
 *
 * **Leseort: [load] und sonst nirgendwo.** Keine andere Datei in diesem Codebase darf
 * `System.getenv` für einen PayPal-Wert aufrufen.
 */
class PaypalConfig private constructor(
    /** `LAPIS_PAYPAL_CLIENT_ID` -- niemals geloggt, niemals in [toString], niemals in einem DTO/einer Exception-Message. */
    val clientId: String,
    /** `LAPIS_PAYPAL_CLIENT_SECRET` -- gleiche Disziplin wie [clientId]. */
    val clientSecret: String,
    /** `LAPIS_PAYPAL_WEBHOOK_ID` -- wird bei JEDER Zustellung an PayPals Signaturprüfung übergeben, siehe [PaypalOrdersClient.verifyWebhookSignature]. */
    val webhookId: String,
    /** `LAPIS_PAYPAL_API_BASE_URL`, Default [DEFAULT_API_BASE_URL]. */
    val apiBaseUrl: String,
    /** `LAPIS_PSP_WEBHOOK_TOLERANCE_SECONDS` -- WIEDERVERWENDET von Stripe, siehe Klassen-KDoc "geteilte Knöpfe". */
    val webhookToleranceSeconds: Long,
    /** `LAPIS_PSP_MAX_CHECKOUT_AMOUNT_EUR` -- wiederverwendet. */
    val maxCheckoutAmountEur: BigDecimal,
    /** `LAPIS_PSP_CHECKOUT_TTL_MINUTES` -- wiederverwendet. */
    val checkoutTtlMinutes: Long,
) {
    /** Redigiert [clientId]/[clientSecret]/[webhookId] -- alles andere ist für ein Startup-Log operational nützlich und trägt kein Geheimnis. */
    override fun toString(): String =
        "PaypalConfig(clientId=<redacted>, clientSecret=<redacted>, webhookId=<redacted>, apiBaseUrl=$apiBaseUrl, " +
            "webhookToleranceSeconds=$webhookToleranceSeconds, maxCheckoutAmountEur=$maxCheckoutAmountEur, " +
            "checkoutTtlMinutes=$checkoutTtlMinutes)"

    companion object {
        const val ENV_CLIENT_ID = "LAPIS_PAYPAL_CLIENT_ID"
        const val ENV_CLIENT_SECRET = "LAPIS_PAYPAL_CLIENT_SECRET"
        const val ENV_WEBHOOK_ID = "LAPIS_PAYPAL_WEBHOOK_ID"
        const val ENV_API_BASE_URL = "LAPIS_PAYPAL_API_BASE_URL"

        const val DEFAULT_API_BASE_URL = "https://api-m.paypal.com"
        const val SANDBOX_API_BASE_URL = "https://api-m.sandbox.paypal.com"

        /**
         * **Enthält NUR die vier `LAPIS_PAYPAL_*`-Namen -- niemals die geteilten `LAPIS_PSP_*`-
         * Zahlenknöpfe.** Siehe [PaypalConfigState.Incomplete] KDoc/Implementierungsplan §6.1: das
         * ist die einzige Zeile, die eine bereits laufende Stripe-only-Produktivinstanz vor einem
         * fälschlichen Startup-Absturz bewahrt.
         */
        private val ALL_ENV_KEYS = listOf(ENV_CLIENT_ID, ENV_CLIENT_SECRET, ENV_WEBHOOK_ID, ENV_API_BASE_URL)

        private val CLIENT_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
        private val WEBHOOK_ID_PATTERN = Regex("^[A-Z0-9-]+$")

        /**
         * Reine String-Validierung, KEIN Netzwerk/I/O -- gleiche Haltung wie [PspConfig.load]. Wirft
         * niemals -- siehe [PaypalConfigState.Incomplete] für die Fehlerberichterstattung.
         */
        fun load(env: (String) -> String? = System::getenv): PaypalConfigState {
            fun value(key: String): String? = env(key)?.trim()?.takeUnless { it.isBlank() }

            val anySet = ALL_ENV_KEYS.any { value(it) != null }
            if (!anySet) return PaypalConfigState.NotConfigured

            val clientId = value(ENV_CLIENT_ID)
            val clientSecret = value(ENV_CLIENT_SECRET)
            val webhookId = value(ENV_WEBHOOK_ID)

            val missing = mutableListOf<String>()
            val invalid = mutableListOf<String>()

            if (clientId == null) {
                missing += ENV_CLIENT_ID
            } else if (clientId.length < 20 || !CLIENT_ID_PATTERN.matches(clientId)) {
                invalid += ENV_CLIENT_ID
            }
            if (clientSecret == null) {
                missing += ENV_CLIENT_SECRET
            } else if (clientSecret.length < 20) {
                invalid += ENV_CLIENT_SECRET
            }
            if (webhookId == null) {
                missing += ENV_WEBHOOK_ID
            } else if (webhookId.isBlank() || !WEBHOOK_ID_PATTERN.matches(webhookId)) {
                invalid += ENV_WEBHOOK_ID
            }

            val rawApiBaseUrl = value(ENV_API_BASE_URL)
            val apiBaseUrl =
                when {
                    rawApiBaseUrl == null -> DEFAULT_API_BASE_URL
                    isAcceptableApiBaseUrl(rawApiBaseUrl) -> rawApiBaseUrl
                    else -> {
                        invalid += ENV_API_BASE_URL
                        DEFAULT_API_BASE_URL
                    }
                }

            // Die drei geteilten Zahlenknöpfe -- degradieren auf Default statt zu werfen, exakt wie
            // PspConfig.load es für Stripe bereits macht.
            val webhookToleranceSeconds =
                (value(PspConfig.ENV_WEBHOOK_TOLERANCE_SECONDS)?.toLongOrNull() ?: PspConfig.DEFAULT_WEBHOOK_TOLERANCE_SECONDS)
                    .coerceIn(
                        minimumValue = PspConfig.MIN_WEBHOOK_TOLERANCE_SECONDS,
                        maximumValue = PspConfig.MAX_WEBHOOK_TOLERANCE_SECONDS,
                    )
            val maxCheckoutAmountEur =
                (value(PspConfig.ENV_MAX_CHECKOUT_AMOUNT_EUR)?.toBigDecimalOrNull() ?: PspConfig.DEFAULT_MAX_CHECKOUT_AMOUNT_EUR)
                    .coerceIn(minimumValue = PspConfig.MIN_MAX_CHECKOUT_AMOUNT_EUR, maximumValue = PspConfig.MAX_MAX_CHECKOUT_AMOUNT_EUR)
                    .setScale(2, RoundingMode.HALF_EVEN)
            val checkoutTtlMinutes =
                (value(PspConfig.ENV_CHECKOUT_TTL_MINUTES)?.toLongOrNull() ?: PspConfig.DEFAULT_CHECKOUT_TTL_MINUTES)
                    .coerceIn(minimumValue = PspConfig.MIN_CHECKOUT_TTL_MINUTES, maximumValue = PspConfig.MAX_CHECKOUT_TTL_MINUTES)

            if (missing.isNotEmpty() || invalid.isNotEmpty()) {
                return PaypalConfigState.Incomplete(missing = missing, invalid = invalid)
            }

            return PaypalConfigState.Configured(
                config =
                    PaypalConfig(
                        clientId = requireNotNull(clientId),
                        clientSecret = requireNotNull(clientSecret),
                        webhookId = requireNotNull(webhookId),
                        apiBaseUrl = apiBaseUrl,
                        webhookToleranceSeconds = webhookToleranceSeconds,
                        maxCheckoutAmountEur = maxCheckoutAmountEur,
                        checkoutTtlMinutes = checkoutTtlMinutes,
                    ),
            )
        }

        /**
         * `https://...` wird nur akzeptiert, wenn es EXAKT [DEFAULT_API_BASE_URL] oder
         * [SANDBOX_API_BASE_URL] ist -- eine echte PSP-Basis-URL hat keinen legitimen dritten Wert.
         * Ein Loopback (`http://127.0.0.1...`/`http://localhost...`) bleibt als reine Test-Lücke
         * erlaubt, exakt wie [PspConfig.isAcceptableApiBaseUrl].
         */
        private fun isAcceptableApiBaseUrl(url: String): Boolean =
            when {
                url.startsWith("https://") -> url == DEFAULT_API_BASE_URL || url == SANDBOX_API_BASE_URL
                else -> url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
            }

        private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
    }
}

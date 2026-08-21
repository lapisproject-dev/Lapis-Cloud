package network.lapis.cloud.server.mail

import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress

/**
 * V1.2.3 Echter SMTP-Versand. Result of [SmtpConfig.load] -- a three-way state, mirroring
 * `network.lapis.cloud.server.conference.ConferenceStreamingConfig`'s own "opted in but broken
 * must never silently degrade" posture, one step further split into [NotConfigured] (nobody opted
 * in at all -- callers fall back to [NoOpMailTransport]) and [Incomplete] (somebody opted in but
 * the configuration is broken -- [SmtpStartupCheck] turns this into a fail-fast startup crash,
 * never a silent no-op).
 */
sealed interface SmtpConfigState {
    /** No `LAPIS_SMTP_*` variable is set at all -- the honest, disclosed non-delivery posture stays exactly as before this wave. */
    data object NotConfigured : SmtpConfigState

    data class Configured(
        val config: SmtpConfig,
    ) : SmtpConfigState

    /**
     * At least one `LAPIS_SMTP_*` variable is set, but the configuration is missing required
     * values or contains an invalid one. There is deliberately no separate `LAPIS_SMTP_ENABLED`
     * flag -- the mere presence of any `LAPIS_SMTP_*` variable IS the opt-in signal, so a half
     * configuration is unambiguously an operator error, never a legitimate intermediate state.
     */
    data class Incomplete(
        val missing: List<String>,
        val invalid: List<String>,
    ) : SmtpConfigState
}

/** How [SmtpConfig] talks TLS to the SMTP server -- there is deliberately no plaintext option. */
enum class SmtpTransportSecurity {
    /** Port 465 by convention -- TLS from the very first byte, no `STARTTLS` negotiation. */
    IMPLICIT_TLS,

    /** Port 587/25 by convention -- `STARTTLS` is *required* (`mail.smtp.starttls.required=true`), never merely opportunistic (see stolperfalle 5: an opportunistic STARTTLS is a downgrade vector). */
    STARTTLS_REQUIRED,
}

/**
 * Deployment-supplied SMTP transport credentials/settings for real outbound email (V1.2.3, first
 * real SMTP transport in this codebase -- replaces the `NoOpPasswordResetMailer`/
 * `NoOpFriendVerificationMailer` disclosed-stub pattern with an actually-wired
 * [JakartaMailTransport] when configured). Follows `network.lapis.cloud.server.payment.sepa.SepaConfig`/
 * `network.lapis.cloud.server.economy.oracle.OracleSourceConfig` **exactly**: a private
 * constructor, a [load] factory taking an injectable `env` lambda (`System.getenv` cannot be
 * mutated per test), and a redacting [toString].
 *
 * **Never persisted, never encrypted-at-rest.** Unlike `ConferenceStreamingConfig`'s stream-
 * destination credentials (which live in the database and therefore need `SecretBox`), SMTP
 * credentials here come exclusively from the environment and are held only in this in-memory
 * config object for the lifetime of the process -- no `LAPIS_SECRET_ENCRYPTION_KEY` involvement.
 *
 * **Fail-fast, not graceful degradation** -- see [SmtpStartupCheck] KDoc "Fail-fast or only loud"
 * for the full reasoning: the opt-in signal here is the presence of ANY `LAPIS_SMTP_*` variable
 * (an env var, not a DB flag), so an operator who set some-but-not-all of them almost certainly
 * made a typo, not a deliberate half-configuration.
 *
 * **Transport security follows the port alone (V1.2.3 Design-Review)** -- [SmtpTransportSecurity]
 * is derived exclusively from [port]; there is deliberately no `LAPIS_SMTP_STARTTLS` override
 * anymore (removed before this feature's first release, so this was never a migration). One fewer
 * knob is one fewer way to accidentally weaken transport security.
 */
class SmtpConfig private constructor(
    /** `LAPIS_SMTP_HOST`. */
    val host: String,
    /** `LAPIS_SMTP_PORT`, default [DEFAULT_PORT]. */
    val port: Int,
    /** `LAPIS_SMTP_USERNAME`. Half of the SMTP credential pair -- never logged, never in [toString], never in a [MailSendOutcome.Failed] message. */
    val username: String,
    /** `LAPIS_SMTP_PASSWORD`. See [username] -- same discipline, doubly so. */
    val password: String,
    /** `LAPIS_SMTP_FROM_ADDRESS`. Must match the netcup-authenticated mailbox, or the deployment sees 5xx rejections -- see README.adoc "E-Mail-Versand". */
    val fromAddress: String,
    /**
     * `LAPIS_SMTP_FROM_NAME`, **Pflicht** (V1.2.3 Design-Review) -- a mail to a real human being
     * says who is sending it. Since [network.lapis.cloud.server.mail.MailTemplates] no longer
     * hardcodes the product name "Lapis Cloud" (white-label: PdV and ELB are two separate pilots,
     * see [MailBranding] KDoc), this value is the ONLY sender identity a recipient ever sees.
     */
    val fromDisplayName: String,
    /**
     * `LAPIS_SMTP_REPLY_TO`, optional (V1.2.3 Design-Review). When set, outgoing mail carries an
     * explicit `Reply-To` header with this address and [network.lapis.cloud.server.mail.MailTemplates]'
     * footer invites a reply; when unset, the footer instead points at
     * `network.lapis.cloud.server.federation.FederationConfig.publicBaseUrl`
     * -- either way, no mail leaves without SOME way back to the operator.
     */
    val replyTo: String?,
    val transportSecurity: SmtpTransportSecurity,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
) {
    /** Redacts [username]/[password] -- everything else is operationally useful to see in a startup log and carries no secret. */
    override fun toString(): String =
        "SmtpConfig(host=$host, port=$port, username=<redacted>, password=<redacted>, " +
            "fromAddress=$fromAddress, fromDisplayName=$fromDisplayName, replyTo=$replyTo, " +
            "transportSecurity=$transportSecurity, connectTimeoutMs=$connectTimeoutMs, readTimeoutMs=$readTimeoutMs)"

    companion object {
        const val ENV_HOST = "LAPIS_SMTP_HOST"
        const val ENV_PORT = "LAPIS_SMTP_PORT"
        const val ENV_USERNAME = "LAPIS_SMTP_USERNAME"
        const val ENV_PASSWORD = "LAPIS_SMTP_PASSWORD"
        const val ENV_FROM_ADDRESS = "LAPIS_SMTP_FROM_ADDRESS"
        const val ENV_FROM_NAME = "LAPIS_SMTP_FROM_NAME"
        const val ENV_REPLY_TO = "LAPIS_SMTP_REPLY_TO"

        const val DEFAULT_PORT = 465
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535

        private val ALL_ENV_KEYS =
            listOf(ENV_HOST, ENV_PORT, ENV_USERNAME, ENV_PASSWORD, ENV_FROM_ADDRESS, ENV_FROM_NAME, ENV_REPLY_TO)

        /**
         * Pure string validation ONLY -- no DNS, no socket, no I/O of any kind (same
         * "`./gradlew clean check` never needs a running SMTP relay" posture every other `*Config.load`
         * in this codebase establishes). Never throws -- see [SmtpConfigState.Incomplete] for how a
         * broken configuration is reported instead.
         */
        fun load(env: (String) -> String? = System::getenv): SmtpConfigState {
            fun value(key: String): String? = env(key)?.trim()?.takeUnless { it.isBlank() }

            val anySet = ALL_ENV_KEYS.any { value(it) != null }
            if (!anySet) return SmtpConfigState.NotConfigured

            val host = value(ENV_HOST)
            val username = value(ENV_USERNAME)
            val password = value(ENV_PASSWORD)
            val fromAddress = value(ENV_FROM_ADDRESS)
            val fromDisplayName = value(ENV_FROM_NAME)
            val replyTo = value(ENV_REPLY_TO)

            val missing = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            if (host == null) missing += ENV_HOST
            if (username == null) missing += ENV_USERNAME
            if (password == null) missing += ENV_PASSWORD
            if (fromAddress == null) missing += ENV_FROM_ADDRESS
            if (fromDisplayName == null) missing += ENV_FROM_NAME

            val rawPort = value(ENV_PORT)
            val port =
                when {
                    rawPort == null -> DEFAULT_PORT
                    else -> {
                        val parsed = rawPort.toIntOrNull()
                        if (parsed == null || parsed !in MIN_PORT..MAX_PORT) {
                            invalid += "$ENV_PORT (not a port number $MIN_PORT..$MAX_PORT)"
                            DEFAULT_PORT
                        } else {
                            parsed
                        }
                    }
                }

            if (fromAddress != null && !isValidMailboxAddress(fromAddress)) {
                invalid += ENV_FROM_ADDRESS
            }

            // Header-injection guard for the display name too (V1.2.3 Design-Review), symmetric to
            // isValidMailboxAddress's \r/\n check for an address -- InternetAddress(address,
            // personal, charset) RFC-2047-encodes the personal part, but belt-and-braces is already
            // the house rule established for fromAddress above.
            if (fromDisplayName != null && (fromDisplayName.contains('\r') || fromDisplayName.contains('\n'))) {
                invalid += ENV_FROM_NAME
            }

            if (replyTo != null && !isValidMailboxAddress(replyTo)) {
                invalid += ENV_REPLY_TO
            }

            if (missing.isNotEmpty() || invalid.isNotEmpty()) {
                return SmtpConfigState.Incomplete(missing = missing, invalid = invalid)
            }

            val transportSecurity =
                if (port == DEFAULT_PORT) SmtpTransportSecurity.IMPLICIT_TLS else SmtpTransportSecurity.STARTTLS_REQUIRED

            return SmtpConfigState.Configured(
                config =
                    SmtpConfig(
                        host = requireNotNull(host),
                        port = port,
                        username = requireNotNull(username),
                        password = requireNotNull(password),
                        fromAddress = requireNotNull(fromAddress),
                        fromDisplayName = requireNotNull(fromDisplayName),
                        replyTo = replyTo,
                        transportSecurity = transportSecurity,
                        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
                    ),
            )
        }
    }
}

/**
 * Rejects a header-injection attempt (`\r`/`\n`) BEFORE even trying [InternetAddress]
 * validation -- a defense-in-depth belt-and-braces check that does not depend on whichever
 * Jakarta Mail version happens to be on the classpath. `strict = true` additionally rejects
 * addresses [InternetAddress]'s lenient default constructor would otherwise accept. Named
 * generically (not `isValidFromAddress`, V1.2.3 Design-Review rename) because it validates
 * [SmtpConfig.fromAddress] and the optional [SmtpConfig.replyTo] -- same rules, two call sites.
 *
 * **Promoted to a top-level `internal` function (security-review fix, V1.2.3)** -- was originally
 * `private` inside [SmtpConfig]'s companion, which only ever protected the operator-supplied
 * `LAPIS_SMTP_FROM_ADDRESS`/`LAPIS_SMTP_REPLY_TO` env vars. `RegistrationService.registerApplication`/
 * `.registerFriend` accept an unauthenticated, attacker-controlled `email` with NO format check of
 * its own (verified: `input.email.trim().lowercase()` is the only transformation, `PasswordPolicy
 * .validate` only checks the password) and persist it into `MemberTable` -- from there it reaches
 * `MailDispatcher`'s per-request log line (`maskEmailForLogging`, whose own KDoc has the full
 * CRLF-log-injection writeup) on every subsequent password-reset/FRIEND-verification send for that
 * row, not just the one triggering request. Reusing this exact check there (rather than duplicating
 * a second, potentially-drifting mailbox validator) closes that at the source: a `\r`/`\n`-bearing
 * or otherwise malformed `email` is now rejected at registration, before any row is ever written.
 */
internal fun isValidMailboxAddress(address: String): Boolean {
    if (address.contains('\r') || address.contains('\n')) return false
    // InternetAddress alone accepts a bare local-part with no "@host" (valid per lenient
    // RFC822 local-delivery syntax) -- an explicit "@" check closes that gap for what is
    // meant to be a real internet mailbox address.
    if (!address.contains('@')) return false
    return try {
        InternetAddress(address, true).validate()
        true
    } catch (e: AddressException) {
        false
    }
}

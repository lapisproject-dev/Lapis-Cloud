package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Result of one [MailTransport.send] attempt. Formgleich zu
 * `network.lapis.cloud.server.postal.PostalDispatchOutcome` (V0.4.2) -- same sealed-interface
 * shape, same "a Failed message is always short/templated, never the raw exception or the mail
 * body" discipline.
 */
sealed interface MailSendOutcome {
    data object Sent : MailSendOutcome

    /**
     * [sanitizedErrorMessage] is always a short, templated description -- typically just the
     * exception's simple class name (see [JakartaMailTransport] KDoc "Error handling"). **Never**
     * the raw exception message (an `AuthenticationFailedException` carries the SMTP server's
     * response line, which can echo the username), **never** the mail body, **never** a token.
     */
    data class Failed(
        val sanitizedErrorMessage: String,
    ) : MailSendOutcome

    /**
     * [NoOpMailTransport]'s outcome -- deliberately distinct from [Sent] so [MailDispatcher] never
     * logs "delivered" for a mail that was never actually handed to a relay. Introduced 2026-08-21
     * (review fix, V1.2.3): before this, [NoOpMailTransport] returned [Sent], and every unconfigured
     * deployment (the default, and both piloting orgs' state at the time) logged two contradicting
     * lines back to back -- [NoOpMailTransport]'s own honest "würde gesendet -- kein SMTP
     * konfiguriert" INFO line immediately followed by [MailDispatcher]'s "Mail delivered" INFO line
     * for the very same send.
     */
    data object Skipped : MailSendOutcome
}

/**
 * Masks the local part of an email address for logging -- e.g. `max@example.org` becomes
 * `m***@example.org`. A per-request server log that pairs a recipient address with a purpose
 * (`"password-reset"`/`"friend-email-verification"`) would otherwise become a standing record of
 * who reset a password or joined as a FRIEND when, which for a political party's membership
 * system touches Art. 9 GDPR territory (see [MailDispatcher]/[NoOpMailTransport] call sites) --
 * unlike `AdminBootstrap`'s manually-invoked CLI logging, this runs on every unauthenticated
 * request. Falls back to `"***"` for a value with no `@` at all (defense-in-depth; [MailDispatcher]
 * itself does NOT validate that `to` contains an `@` -- the only check on the address shape happens
 * later, inside [JakartaMailTransport], and it only rejects embedded CR/LF, not a missing `@`. This
 * fallback is what actually keeps this function total for a malformed/empty address) so this never
 * throws on malformed input.
 *
 * **CRLF-log-injection guard (security-review fix, V1.2.3).** `RegistrationService.registerFriend`/
 * `.registerApplication` now reject a malformed `email` before it ever reaches `MemberTable` (see
 * `SmtpConfig.isValidMailboxAddress`, reused there via the shared top-level `isValidMailboxAddress`
 * in this file), but this function stays defense-in-depth regardless -- it is the last place a
 * control character could still reach a log line (a pre-existing row from before that guard
 * existed, or any future caller that forgets it). Every [Char.isISOControl] character -- not just
 * `\r`/`\n`, all of them, since `logback.xml`'s single-line pattern
 * (`%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`) has no escaping of its own -- is
 * replaced with `?` before masking, so neither the one-character local-part prefix nor the literal
 * domain suffix can ever forge a fake log line.
 */
internal fun maskEmailForLogging(address: String): String {
    val sanitized = address.map { if (it.isISOControl()) '?' else it }.joinToString("")
    val at = sanitized.indexOf('@')
    if (at <= 0) return "***"
    return "${sanitized.take(1)}***${sanitized.substring(at)}"
}

/**
 * Abstraction over "send exactly one email, right now". [MailDispatcher] is the only caller in
 * this codebase -- individual mailer adapters ([SmtpPasswordResetMailer]/
 * [SmtpFriendVerificationMailer]) never call a [MailTransport] directly, see [MailDispatcher] KDoc
 * for why (timing-side-channel-safe fire-and-forget).
 */
interface MailTransport {
    /**
     * Blocking (from the caller's perspective, a real network round-trip) send attempt of exactly
     * one message to exactly one recipient. **Never throws** -- every failure mode, including a
     * malformed [to]/[subject] and a transport-level exception, is reported as
     * [MailSendOutcome.Failed].
     */
    suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome
}

/**
 * Fallback used whenever [SmtpConfigState.NotConfigured] -- exactly the previous behaviour of the
 * now-removed `NoOpPasswordResetMailer`/`NoOpFriendVerificationMailer`: an honest, disclosed
 * non-delivery, logged but never a startup failure. See [SmtpConfig] KDoc and [SmtpStartupCheck]
 * KDoc for the full "why this stays optional" rationale. Returns [MailSendOutcome.Skipped], not
 * [MailSendOutcome.Sent] -- see [MailSendOutcome.Skipped] KDoc for why that distinction matters to
 * [MailDispatcher]'s own logging.
 */
class NoOpMailTransport : MailTransport {
    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        logger.info { "Mail an ${maskEmailForLogging(to)} ('$subject') würde gesendet -- kein SMTP konfiguriert ($LOG_HINT)." }
        return MailSendOutcome.Skipped
    }

    private companion object {
        const val LOG_HINT = "LAPIS_SMTP_HOST unset"
    }
}

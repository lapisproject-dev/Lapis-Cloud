package network.lapis.cloud.server.mail

/**
 * Escapes [value] for safe interpolation into a hand-written `htmlBody` string (Security-Review
 * MINOR fix, Welle V1.4.3.2 Fix-Runde): several event mails (`EventService.reissueTicket`/
 * `.mailEventCancelled`, `EventWaitlist.mailPromotion`, `EventRegistrationSubmission
 * .mailRegistrationReceived`, `PspWebhookRoutes.mailEventTicket`) build their HTML part via plain
 * string templates rather than an auto-escaping builder like `kotlinx.html.stream.createHTML`
 * (the mechanism `MailTemplates`/`SocialPublicHtml` use elsewhere in this codebase), and interpolate
 * values that ultimately trace back to unauthenticated public input -- a guest's `guestName` from
 * the public registration form is bounded only by length (`EventPolicy
 * .MAX_GUEST_NAME_LENGTH`/`.normalizeGuestName`'s blank-check), never by character content -- or to
 * a BOARD/ADMIN-supplied value (`Event.title`, `cancelEvent`'s `reason`) that a compromised or
 * malicious such account could use to embed a phishing link into a mail sent under this
 * organization's own From-address. Same treatment [network.lapis.cloud.server.routes.OidcRoutes]'s
 * own (private, not reusable across packages) `htmlEscape` already applies to its consent-page HTML.
 *
 * Apply this to every interpolated value that is not a compile-time literal or a URL this server
 * itself constructed (a `ticketUrl`/`cancelUrl` built from a slug + server-generated token never
 * contains attacker-controlled characters, so escaping it would only risk mangling the `href`).
 */
internal fun htmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

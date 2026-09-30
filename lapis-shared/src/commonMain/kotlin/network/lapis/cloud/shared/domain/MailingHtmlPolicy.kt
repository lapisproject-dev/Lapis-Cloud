package network.lapis.cloud.shared.domain

/**
 * Welle V1.9.7 "SuperMailer" -- the single, shared allowlist/limits every HTML-sanitizing pass for
 * mailing-message content is built from. Currently consumed by the server-side
 * `network.lapis.cloud.server.mail.newsletter.MailingHtmlSanitizer` (jsoup) and, since V1.9.15, by
 * the client-side editor's own DOM normalizer (`MailingHtmlNormalizer`, no DOMPurify -- a rejected
 * dependency, the server sanitizer stays the authority). Both read [ALLOWED_TAGS]/[TAG_RENAMES]/
 * [ALLOWED_HREF_SCHEMES] from here rather than duplicating a second, potentially-drifting allowlist.
 *
 * **Values are deliberately conservative** -- a newsletter body is prose (paragraphs, headings,
 * lists, a quote, links), never a page layout. No `style`/`class`/`id`, no images, no forms, no
 * scripts, no iframes -- see `MailingHtmlSanitizer` KDoc for the full security rationale.
 */
object MailingHtmlPolicy {
    /** Tags a sanitized mailing body may retain, after [TAG_RENAMES] has already run. */
    val ALLOWED_TAGS: Set<String> = setOf("p", "br", "strong", "em", "h2", "h3", "ul", "ol", "li", "blockquote", "a")

    /**
     * Applied BEFORE the allowlist -- `document.execCommand` (the follow-up wave's planned
     * contenteditable editor) emits `<b>`/`<i>`/`<div>` in Chrome, none of which are in
     * [ALLOWED_TAGS]; without this rename step that formatting would silently be stripped rather
     * than normalized (see `MailingHtmlSanitizer` KDoc "S5").
     */
    val TAG_RENAMES: Map<String, String> = mapOf("b" to "strong", "i" to "em", "div" to "p")

    /** Schemes `href` may use on an `<a>` element. */
    val ALLOWED_HREF_SCHEMES: Set<String> = setOf("https", "http", "mailto")

    /** The subset of [ALLOWED_HREF_SCHEMES] that a link-click can meaningfully be counted for (Teil B, follow-up wave). */
    val TRACKABLE_SCHEMES: Set<String> = setOf("https", "http")

    /** Byte ceiling on the RAW (pre-sanitize) HTML body -- checked BEFORE parsing (DoS guard). */
    const val MAX_HTML_BYTES: Int = 200_000

    /** Ceiling on the number of `<a href="http(s)...">` links a sanitized body may contain. */
    const val MAX_LINKS: Int = 200

    /** Ceiling on the derived plain-text body's character count (mirrors `mailing_message.body_text`'s `VARCHAR(20000)`). */
    const val MAX_TEXT_CHARS: Int = 20_000

    /** Ceiling on a mailing message's subject line (mirrors `mailing_message.subject`'s `VARCHAR(300)`). */
    const val MAX_SUBJECT_CHARS: Int = 300

    /** Minimum number of independent consents before an aggregate open/click count may be disclosed (k-anonymity floor, Teil B/C, follow-up wave). */
    const val MIN_CONSENTS_FOR_STATS: Int = 5

    /** Ceiling on the number of recipients a single [network.lapis.cloud.shared.rpc.IMailingService.sendMailingMessage] call may queue. */
    const val MAX_RECIPIENTS: Int = 5_000

    /** Ceiling an open/click counter is clamped to (matches `chk_mailing_delivery_log_open_count`, follow-up wave). */
    const val MAX_EVENT_COUNT: Int = 1_000

    /** Days a tracking event (open/click) is retained before a retention poller erases it (Teil B/C, follow-up wave). */
    const val RETENTION_DAYS: Int = 180
}

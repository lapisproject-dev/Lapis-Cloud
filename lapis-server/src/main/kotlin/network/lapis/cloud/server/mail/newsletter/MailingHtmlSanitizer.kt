package network.lapis.cloud.server.mail.newsletter

import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.rpc.BadRequestException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Result of [MailingHtmlSanitizer.sanitize] -- [html] is safe to embed verbatim (via
 * `kotlinx.html`'s `unsafe { +html }`) into a rendered mail, [plainText] is the tracking-free
 * plain-text counterpart (see [MailingPlainText]), and [trackableLinks] is every `http(s)` link
 * target found, IN DOCUMENT ORDER -- that order IS the `linkIndex` both the
 * `mailing_message_link` insert (`MailingService.createDraftMessageHtml`) and the click-rewrite
 * pass (`MailingMailRenderer`, follow-up wave) key off of (plan "S6": both MUST come from the
 * SAME sanitize call over the SAME source HTML for the indices to line up).
 *
 * Constructor is `internal` -- only [MailingHtmlSanitizer.sanitize] may construct one, so a caller
 * can never fabricate a "sanitized" result that skipped the actual sanitization pass.
 */
@ConsistentCopyVisibility
data class SanitizedMailingHtml internal constructor(
    val html: String,
    val plainText: String,
    val trackableLinks: List<String>,
)

/**
 * Welle V1.9.7 "SuperMailer" Teil A -- allowlist-based HTML sanitizer for board-authored
 * newsletter content, built on jsoup (see `gradle/libs.versions.toml` for the library-choice
 * rationale).
 *
 * **Why jsoup, not hand-rolled.** An allowlist-based HTML sanitizer has to correctly handle
 * malformed/unbalanced markup, nested and duplicated tags, HTML-entity and URL-encoding tricks,
 * and attribute-parsing edge cases (unquoted values, stray `>` inside an attribute value) --
 * exactly the class of problem a real HTML5-tree-construction parser exists to solve and a
 * regex-based approach reliably gets wrong (the entire "regex can't parse HTML" body of prior art
 * this codebase does not need to re-litigate). jsoup parses via the same tree-construction
 * algorithm a browser uses, then [Cleaner]/[Safelist] walk the resulting DOM rather than the raw
 * text -- a forged `<a href="javascript&#x3a;...">` or an attribute smuggled via a malformed tag
 * cannot survive a real parse-then-serialize round-trip the way it might survive a naive
 * string-replace.
 *
 * **Pipeline** (mirrors the design plan exactly):
 *  1. Byte-size guard on the RAW input, BEFORE parsing at all -- a DoS guard against a pathological
 *     multi-megabyte body forcing jsoup to build a huge DOM for content that will be rejected
 *     anyway.
 *  2. [Jsoup.parseBodyFragment] -- treats the input as an HTML fragment (no implicit `<html>`/
 *     `<head>` wrapping to worry about).
 *  3. [MailingHtmlPolicy.TAG_RENAMES] applied FIRST, in-place, via [Element.tagName] -- `<b>`/`<i>`/
 *     `<div>` (what `execCommand`, the follow-up wave's planned editor, actually emits in Chrome)
 *     become `<strong>`/`<em>`/`<p>` BEFORE the allowlist runs, so that formatting survives instead
 *     of being silently stripped (S5 in the plan).
 *  4. [Cleaner] with a [Safelist] built from [MailingHtmlPolicy.ALLOWED_TAGS]/[.ALLOWED_HREF_SCHEMES]
 *     -- every non-allowlisted tag/attribute is dropped (`Safelist.none()` as the base, nothing is
 *     implicitly trusted).
 *  5. A belt-and-braces pass over every surviving `<a href>`: a control character or a length over
 *     2048 unwraps the element (removes the tag, keeps its text) rather than merely stripping the
 *     attribute -- [Safelist] alone does not enforce a length ceiling.
 *  6. More than [MailingHtmlPolicy.MAX_LINKS] surviving trackable (`http`/`https`) links throws
 *     [BadRequestException] (mailto links are unlimited -- they cannot be tracked and cost nothing
 *     to render).
 *
 * **Idempotent**: `sanitize(sanitize(x).html) == sanitize(x)` -- every element/attribute that
 * survives one pass already satisfies the allowlist the second pass would apply, so nothing more
 * is stripped/renamed on a second call (pinned by `MailingHtmlSanitizerTest`).
 */
object MailingHtmlSanitizer {
    private val safelist: Safelist =
        Safelist
            .none()
            .addTags(*MailingHtmlPolicy.ALLOWED_TAGS.toTypedArray())
            .addAttributes("a", "href")
            .addProtocols("a", "href", *MailingHtmlPolicy.ALLOWED_HREF_SCHEMES.toTypedArray())
            .preserveRelativeLinks(false)

    fun sanitize(rawHtml: String): SanitizedMailingHtml {
        if (rawHtml.toByteArray(Charsets.UTF_8).size > MailingHtmlPolicy.MAX_HTML_BYTES) {
            throw BadRequestException("HTML-Inhalt zu groß (max. ${MailingHtmlPolicy.MAX_HTML_BYTES} Bytes).")
        }

        val parsed = Jsoup.parseBodyFragment(rawHtml)
        MailingHtmlPolicy.TAG_RENAMES.forEach { (from, to) ->
            parsed.body().select(from).forEach { it.tagName(to) }
        }

        val cleanedDoc: Document = Cleaner(safelist).clean(parsed)
        cleanedDoc.outputSettings(Document.OutputSettings().prettyPrint(false))

        // Belt-and-braces href guard (S1/plan step 6) -- Safelist's addProtocols already rejects a
        // disallowed scheme, but enforces neither a length ceiling nor a control-character ban.
        // unwrap() (not remove()) keeps the anchor's own text -- only the link itself is untrusted,
        // not the words the author wrote.
        cleanedDoc.body().select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.any { it.isISOControl() } || href.length > MAX_HREF_LENGTH) {
                anchor.unwrap()
            }
        }

        val trackableLinks =
            cleanedDoc
                .body()
                .select("a[href]")
                .map { it.attr("href") }
                .filter { href -> MailingHtmlPolicy.TRACKABLE_SCHEMES.any { scheme -> href.startsWith("$scheme:") } }
        if (trackableLinks.size > MailingHtmlPolicy.MAX_LINKS) {
            throw BadRequestException("Zu viele Links (max. ${MailingHtmlPolicy.MAX_LINKS}).")
        }

        val html = cleanedDoc.body().html()
        // Review fix (finding #1, W-SuperMailer round 1): the byte guard above only bounds the RAW
        // input -- jsoup's tag renames/serialization (`<i>` -> `<em>`, a bare `&` -> `&amp;`, etc.)
        // can make the CLEANED output larger than the input, so a body just under MAX_HTML_BYTES raw
        // could still balloon past it once sanitized. Without this second check, such a message
        // would be accepted and stored, only to blow up the NEXT time it is sanitized (the
        // re-sanitize in `MailingDeliveryWorker.loadSendPlan`/`MailingService.previewMailingMessage`)
        // -- fatal for the former, since that exception escapes `processMessage` and (pre-fix) killed
        // the whole delivery worker. Checking the output keeps `sanitize` idempotent with respect to
        // this guard too: a body that is rejected here can never be smuggled into storage by any
        // other path, so a later re-sanitize of already-stored content always sees the same size it
        // was already validated at.
        if (html.toByteArray(Charsets.UTF_8).size > MailingHtmlPolicy.MAX_HTML_BYTES) {
            throw BadRequestException("HTML-Inhalt zu groß (max. ${MailingHtmlPolicy.MAX_HTML_BYTES} Bytes).")
        }
        val plainText = MailingPlainText.derive(cleanedDoc)
        return SanitizedMailingHtml(html = html, plainText = plainText, trackableLinks = trackableLinks)
    }

    private const val MAX_HREF_LENGTH = 2048
}

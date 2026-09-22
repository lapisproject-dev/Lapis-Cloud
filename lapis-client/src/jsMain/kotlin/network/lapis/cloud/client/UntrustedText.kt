package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Link
import io.kvision.html.P
import io.kvision.html.Span
import io.kvision.html.Tag
import io.kvision.html.Template
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.h4
import io.kvision.html.h5
import io.kvision.html.h6
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span

/**
 * Security audit W6b, round 4 (Block A of the "money-forgery hardening" wave): the central helper set for widget
 * content that comes from a server-, member- or DTO-controlled field -- everything [sanitizeUntrustedI18nText]'s
 * own KDoc on `I18nCatalogManager.kt` warns about. KVision's `Widget` resolves ANY content string that starts with
 * `KV_I18N_MARKER` through `I18n.trans`/`gettext` on render, independent of [trFormat] -- so a vote option label,
 * motion title, donor name, or any other untrusted field handed straight to `div(...)`/`span(...)`/`p(...)`/
 * `h1(...)`..`h6(...)`/`link(...)` as plain widget content can render as a forged, freely chosen money amount if
 * it carries a forged marker + `MONEY_SENTINEL` payload.
 *
 * Security audit W6b, round 7 (major finding 1): [io.kvision.html.Link] (built via the `link(...)` DSL function)
 * renders its `label` through exactly the same `createLabelWithIcon` -> `Widget.translate` -> `I18n.trans` path as
 * `Tag.content` -- it was missing from this file (and from the widget-text tripwire's covered call list) even
 * though the round-3/4 KDoc above already said "ANY content string". [untrustedLink] closes that gap the same way
 * the other helpers close theirs -- always sanitize `label` first.
 *
 * Round 8 (major finding, follow-up to round 7): `Select`/`SimpleSelect`'s `options: List<StringPair>?` is the
 * same unguarded sink again -- each option's label (the pair's `second`) is rendered by KVision's `Tag` for the
 * generated `<option>` element and resolved through `Widget.translate` -> `I18n.trans` exactly like `Tag.content`,
 * `Link.label`, and every other case this file already covers. Nothing sanitized it and the widget-text tripwire's
 * regex never matched `.select(`/`.options =` shapes, so 21+ call sites that build option pairs straight from a
 * member-/server-controlled DTO field (`it.id to it.displayName`, `it.id to it.name`, `it.id to it.title`, ...)
 * were exposed: a forged marker + `MONEY_SENTINEL` payload in a display name renders as an arbitrary money amount
 * in a dropdown -- letting a member choose a peer-transfer recipient, meeting chair, or committee role whose shown
 * label has nothing to do with the underlying `id` actually submitted. [untrustedOptions] sanitizes every label in
 * an options list; the `id`/value half of each pair is left untouched (it is submitted, never rendered as text).
 *
 * Round 3 fixed call sites one at a time by wrapping each argument in `sanitizeUntrustedI18nText(...)` inline. The
 * ledger test [network.lapis.cloud.server.clientversion.ClientUntrustedWidgetTextTripwireTest] found 58 remaining
 * raw call sites afterwards -- round 4 closes the engstelle instead of repeating the same manual wrap 58 more
 * times: EVERY new/edited call site handing untrusted DTO text to a widget as plain content MUST go through one of
 * these helpers (never a bare `div(x.field)`/`span(x.field)`/etc.), so the sanitization step cannot be forgotten
 * again. Each helper is a thin wrapper around the matching KVision DSL builder that always sanitizes [text] first.
 *
 * [untrustedCardTitle] specifically covers the single most repeated shape in the codebase (21 of the 58 round-3
 * findings): a card/list header row's `fw-bold flex-grow-1` title.
 *
 * Round 5 (major finding, "money-forgery hardening" follow-up): the round-3/4 helpers only cover widget text handed
 * in at CONSTRUCTION time (`div(text)`, `span(text)`, ...). The ledger test's regex is necessarily blind to the
 * equally dangerous *assignment* shape, `widget.content = dtoField.field` -- it hits the exact same
 * `Widget`/`Template.content` render path (see [sanitizeUntrustedI18nText]'s KDoc) but has no opening paren for the
 * regex to anchor on. [untrustedContent] closes that second shape: always sanitize before assigning to an existing
 * widget's `content`, never assign a DTO/server field to `.content` directly.
 */
internal fun Container.untrustedDiv(
    text: String,
    className: String? = null,
    init: (Div.() -> Unit)? = null,
): Div = div(sanitizeUntrustedI18nText(text), className = className, init = init)

internal fun Container.untrustedSpan(
    text: String,
    className: String? = null,
    init: (Span.() -> Unit)? = null,
): Span = span(sanitizeUntrustedI18nText(text), className = className, init = init)

internal fun Container.untrustedP(
    text: String,
    className: String? = null,
    init: (P.() -> Unit)? = null,
): P = p(sanitizeUntrustedI18nText(text), className = className, init = init)

/**
 * The `link(...)` counterpart (round 7): only [label] -- the untrusted, server-/member-controlled part -- is
 * sanitized; [url] is never rendered as translatable widget content (a raw `href`/`javascript:void(0)` or a
 * download URL built by `*Http.receiptDownloadUrl(...)`, not free text) and is passed through unchanged.
 */
internal fun Container.untrustedLink(
    label: String,
    url: String? = null,
    target: String? = null,
    dataNavigo: Boolean? = null,
    className: String? = null,
    init: (Link.() -> Unit)? = null,
): Link =
    link(
        sanitizeUntrustedI18nText(label),
        url = url,
        target = target,
        dataNavigo = dataNavigo,
        className = className,
        init = init,
    )

/**
 * `level` selects `h2`..`h6`; `h1` is deliberately NOT selectable here -- `ClientPageHeaderTripwireTest` (R6)
 * enforces that `PageHeader.kt`'s `pageHeader()` is the ONLY source of a screen's `h1`, and a caller wanting a
 * page header (not a section title) should use that, not this helper. Any other level is a programming error
 * (not an untrusted-input path), so it fails fast rather than silently falling back to a default heading level.
 * Takes only [className] (not a per-level typed `init` block, which would need an unsafe cross-type cast) --
 * every known call site (`h2` with a single `h5`/`h6` size class) fits that shape exactly. `ClientPageHeaderTripwireTest`
 * (R7) also requires an `h5`/`h6` size-class literal on the same source line as an `h2(`/`h3(` call; the caller
 * supplies the real class through [className], so the literal below is documentation, not enforcement -- R7
 * compliance is asserted by the DOM tests on the actual rendered className, not by this comment.
 */
internal fun Container.untrustedHeading(
    text: String,
    level: Int,
    className: String? = null,
): Tag {
    val safe = sanitizeUntrustedI18nText(text)
    return when (level) {
        2 -> h2(safe, className = className) // caller passes an "h5" (or "h6") size class via className
        3 -> h3(safe, className = className) // caller passes an "h5" (or "h6") size class via className
        4 -> h4(safe, className = className)
        5 -> h5(safe, className = className)
        6 -> h6(safe, className = className)
        else -> error("untrustedHeading: level must be 2..6 (h1 belongs to PageHeader.kt's pageHeader()), was $level")
    }
}

/**
 * The 21-times-repeated card/list header row title: `fw-bold flex-grow-1`. [extraClasses] appends further CSS
 * classes (e.g. a status color) after the two standard ones.
 */
internal fun Container.untrustedCardTitle(
    text: String,
    extraClasses: String? = null,
): Div {
    val classes = listOfNotNull("flex-grow-1", "fw-bold", extraClasses).joinToString(" ")
    return untrustedDiv(text, className = classes)
}

/**
 * The assignment-shape counterpart to [untrustedDiv]/[untrustedSpan]/[untrustedP]/[untrustedHeading]: sets an
 * EXISTING widget's `content` to untrusted DTO/server text, sanitized first. Use this instead of a bare
 * `widget.content = dtoField.field` assignment (see the round-5 note on the class KDoc above) whenever the widget
 * already exists -- e.g. it was created earlier and is now being refreshed with a new value from a suspend
 * response, or from a TAN/error/disclaimer callback. [text] may be `null` (clears the content); a non-null value
 * is always run through [sanitizeUntrustedI18nText] first, unconditionally.
 */
internal fun untrustedContent(
    widget: Template,
    text: String?,
) {
    widget.content = text?.let(::sanitizeUntrustedI18nText)
}

/**
 * The `select(...)`/`SimpleSelect.options` counterpart (round 8): sanitizes the label half (`second`) of every
 * `(value, label)` pair before it is handed to KVision as `options`. The value half (`first`) -- an id, enum
 * `.name`, or similar identifier that is submitted, never rendered as translatable widget content -- is passed
 * through unchanged. Use this instead of a bare `list.map { it.id to it.displayName }` (or any comparable
 * DTO-to-option mapping) wherever the label originates from a member-/server-controlled field.
 */
internal fun untrustedOptions(options: List<Pair<String, String>>): List<Pair<String, String>> =
    options.map { (value, label) -> value to sanitizeUntrustedI18nText(label) }

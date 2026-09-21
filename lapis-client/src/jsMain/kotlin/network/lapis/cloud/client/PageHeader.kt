package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.H1
import io.kvision.html.P
import io.kvision.html.div
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Welle V1.4.31 (W5) -- the one page header of every screen (rules R6, R35, R36, R37).
 *
 * Structure, top to bottom: the [banners] slot (warning and compliance bands -- ABOVE the title, R37), a title row
 * with the single `h1` on the left and at most one [primaryAction] on the right (R36), and an optional subtitle.
 * The `h1` is styled `.h4` (see `theme.css`), `h2` section titles are `.h5`; that step-down is what R7 asks for.
 *
 * [title] is a `tr()`-marked CONSTANT that matches the sidebar entry of the screen (R35), never a data value: a
 * person's name or a loaded count belongs in the [subtitle] (see [PageHeader.setSubtitle]). The subtitle is always
 * set as TEXT, never `rich`, so a member name can never become markup.
 *
 * What the header does besides drawing:
 *  - `document.title` becomes "<title> -- <brand>" with the operator's white-label title ([Branding.title]) -- see [PageTitle]. The
 *    text goes through [resolvedAttributeText]: a raw `tr()` value would leak the `###KvI18nS###` marker into the
 *    browser tab. A language switch rebuilds it ([PageTitle.apply]).
 *  - The route change is announced through the live region [LIVE_REGION_ID] (mounted once by `App.kt`); the header only
 *    writes the TEXT of that element, it never creates it -- a live region that is created together with its text is
 *    not announced (§2.12).
 *  - Focus moves to the `h1` after a ROUTE change only (see [PageFocus]); a re-render (language switch,
 *    `refreshShell`) must not take the focus away from the control the person was using.
 *
 * The `h1` gets its `id` and `tabindex` and its hooks BEFORE it is added ([addWithLifecycle], see there).
 */
class PageHeader internal constructor(
    val root: Div,
    private val subtitleTag: P?,
    private val subtitleRow: Div?,
) {
    /**
     * Sets the subtitle to a loaded data value (TEXT, never markup); a `null` clears it. A header built WITHOUT a subtitle slot
     * (`subtitle = null`) has nothing to write into: setting text there fails loudly instead of silently dropping the value (audit
     * fix -- it used to be a silent no-op, so a caller that forgot the slot showed nothing and no test noticed). The browser tab title
     * follows: "<title> -- <subtitle> -- <brand>", so two tabs of the same screen for different members can be told apart.
     */
    fun setSubtitle(text: String?) {
        check(subtitleTag != null || text == null) { "pageHeader was built without a subtitle slot -- pass subtitle = \"\" to reserve one" }
        subtitleTag?.content = text ?: ""
        PageTitle.setSubtitle(text)
    }

    /** Whether the header reserved a subtitle slot (`subtitle != null` at construction). */
    val hasSubtitleSlot: Boolean get() = subtitleTag != null

    /**
     * Adds [block]'s widgets NEXT TO the subtitle, in the same row (a status badge that belongs to the name shown there, e.g. the
     * "DSGVO-gelöscht" marker of a member whose data was erased). Needs the subtitle slot, like [setSubtitle].
     */
    fun subtitleAside(block: Container.() -> Unit) {
        val row = checkNotNull(subtitleRow) { "pageHeader was built without a subtitle slot -- pass subtitle = \"\" to reserve one" }
        row.block()
    }
}

/**
 * The browser tab title of the current page: "<title> -- <brand>", or "<title> -- <subtitle> -- <brand>" once a screen has set a
 * subtitle. It is composed from the CONSTANT title (a `tr(...)` string) each time, so [apply] can rebuild it after a language switch:
 * `document.title` is not part of the KVision tree, so `I18n.language`'s root restart does not reach it (audit fix -- the header
 * doc promised a translated title but only the first render was translated). The live region ([LIVE_REGION_ID]) is NOT rewritten on
 * a language switch: it announces ROUTE changes, and a switch is not one.
 */
internal object PageTitle {
    private var titleKey: String? = null
    private var subtitle: String? = null

    fun set(
        title: String,
        subtitle: String?,
    ) {
        titleKey = title
        this.subtitle = subtitle?.takeIf { it.isNotBlank() }
        apply()
    }

    fun setSubtitle(text: String?) {
        subtitle = text?.takeIf { it.isNotBlank() }
        apply()
    }

    /** Recomputes `document.title` in the CURRENT language (call after `I18n.language` changed). */
    fun apply() {
        val key = titleKey ?: return
        val plain = resolvedAttributeText(key)
        document.title = listOfNotNull(plain, subtitle, Branding.title).joinToString(" – ")
    }

    /** Test seam: forget the current page. */
    internal fun reset() {
        titleKey = null
        subtitle = null
    }
}

/** Element id of the polite live region `App.kt` mounts once in the shell. */
internal const val LIVE_REGION_ID = "lapis-live-region"

/** Element id of the page `h1`, the focus target after a route change; `.lapis-content` is labelled by it (`aria-labelledby`, set by `App.kt`). */
internal const val PAGE_TITLE_ID = "lapis-page-title"

fun Container.pageHeader(
    title: String,
    subtitle: String? = null,
    banners: (Container.() -> Unit)? = null,
    primaryAction: (Container.() -> Unit)? = null,
): PageHeader {
    val header = div(className = "lapis-page-header")
    if (banners != null) header.banners()
    val titleRow = header.div(className = "d-flex flex-wrap align-items-start justify-content-between gap-2")
    val heading = H1(content = title, className = "h4 lapis-page-title mb-0")
    heading.id = PAGE_TITLE_ID
    heading.setAttribute("tabindex", "-1")
    titleRow.addWithLifecycle(
        heading,
        onInsert = { vnode ->
            if (PageFocus.consume()) (vnode.elm as? HTMLElement)?.focus()
        },
    )
    if (primaryAction != null) {
        val actionSlot = titleRow.div(className = "lapis-page-action")
        actionSlot.primaryAction()
    }
    var subtitleRow: Div? = null
    val subtitleTag =
        if (subtitle != null) {
            val row = header.div(className = "d-flex flex-wrap align-items-center gap-2")
            val tag = P(content = subtitle, className = "text-muted mb-0")
            row.add(tag)
            subtitleRow = row
            tag
        } else {
            null
        }
    PageTitle.set(title, subtitle)
    (document.getElementById(LIVE_REGION_ID))?.textContent = resolvedAttributeText(title)
    return PageHeader(header, subtitleTag, subtitleRow)
}

/**
 * Focus hand-over between [initRouting]'s `show` and the next page header: `show` reports every route it renders through
 * [onRouteShown], the header's insert hook consumes the request. A re-render never requests it, so it never steals the focus. A
 * screen that builds no header simply leaves the request unused; the next `show` overwrites it.
 *
 * Audit fix M7: the FIRST route shown (the page load, a deep link or the boot redirect) requests nothing. Moving the focus to
 * the `h1` on load would put the skip link BEHIND the first Tab stop it is meant to be -- a keyboard user's first Tab landed on
 * the first control after the title instead of the skip link. Only a genuine route CHANGE (the second `show` onwards) moves the focus.
 *
 * Audit fix (minor): [consume] never hands out the focus when it would be STOLEN -- while a modal dialog is open (its focus trap
 * owns the focus) or while a text field, select or editable region already holds it (a route change fired by a control the
 * person is still working in must not pull the caret away).
 */
internal object PageFocus {
    private var pending = false
    private var routesShown = 0

    /** Called by `show` for every route it renders: the first one only counts, every later one requests the focus. */
    fun onRouteShown() {
        routesShown++
        if (routesShown > 1) pending = true
    }

    /** Requests the focus for the next header unconditionally (tests, and callers that know better than [onRouteShown]). */
    fun request() {
        pending = true
    }

    fun consume(): Boolean {
        val wasPending = pending
        pending = false
        return wasPending && !modalOpen() && !editableFocused()
    }

    /** Test seam: back to the state of a fresh page load. */
    internal fun reset() {
        pending = false
        routesShown = 0
    }

    private fun modalOpen(): Boolean = document.querySelector(".modal.show") != null

    private fun editableFocused(): Boolean {
        val active = document.activeElement as? HTMLElement ?: return false
        return active.tagName in setOf("INPUT", "TEXTAREA", "SELECT") || active.isContentEditable
    }
}

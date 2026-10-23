package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Link
import io.kvision.html.P
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * V1.9.187 -- the short, collapsible introduction under the title of a view ("page intro").
 *
 * What a view's intro says, who may do what and where the help topic lives is decided in [PageIntros] (one registry, a `when` over the
 * routes); a screen only passes `intro = PageIntros.forRoute(Routes.X)` to [pageHeader]. [viewKey] is a CODE constant (`"contributions"`),
 * never a data value and never taken from the URL.
 */
data class PageIntroDef(
    val viewKey: String,
    val text: String,
    val helpTopic: String? = null,
    val more: String? = null,
)

/**
 * The collapsed state of the page intros, per view, in the browser only (`localStorage`, key `lapis.pageIntro.v2.<viewKey>`, value
 * `"collapsed"`). Every storage access is wrapped: a blocked or throwing storage means the intro is open after a reload, and a collapse still
 * works for the rest of the page's life through [sessionCollapsed] -- nothing throws and nothing is lost on screen.
 *
 * V1.9.190 migration: until V1.9.189 an intro could be HIDDEN for good (`lapis.pageIntro.v1.<viewKey>` = `"hidden"`). Now it is only folded
 * away to a small toggle. A stored v1 `"hidden"` is read as "collapsed" the first time it is looked at: v2 is written, v1 is removed.
 * [expand] removes the v1 key as well, otherwise a later read would migrate the old value back to "collapsed".
 */
internal object PageIntroStorage {
    private const val PREFIX_V2 = "lapis.pageIntro.v2."
    private const val PREFIX_V1 = "lapis.pageIntro.v1."
    private const val COLLAPSED = "collapsed"
    private const val HIDDEN_V1 = "hidden"
    private val sessionCollapsed = mutableSetOf<String>()

    fun isCollapsed(viewKey: String): Boolean {
        if (viewKey in sessionCollapsed) return true
        val stored = runCatching { localStorage.getItem(PREFIX_V2 + viewKey) == COLLAPSED }.getOrDefault(false)
        if (stored) return true
        val legacy = runCatching { localStorage.getItem(PREFIX_V1 + viewKey) == HIDDEN_V1 }.getOrDefault(false)
        if (!legacy) return false
        // Migrate: the old "hidden" becomes "collapsed". If the write throws the memory set carries the state for this page life.
        sessionCollapsed += viewKey
        runCatching { localStorage.setItem(PREFIX_V2 + viewKey, COLLAPSED) }
        runCatching { localStorage.removeItem(PREFIX_V1 + viewKey) }
        return true
    }

    fun collapse(viewKey: String) {
        sessionCollapsed += viewKey
        runCatching { localStorage.setItem(PREFIX_V2 + viewKey, COLLAPSED) }
    }

    fun expand(viewKey: String) {
        sessionCollapsed -= viewKey
        runCatching { localStorage.removeItem(PREFIX_V2 + viewKey) }
        runCatching { localStorage.removeItem(PREFIX_V1 + viewKey) }
    }

    fun anyCollapsed(): Boolean = sessionCollapsed.isNotEmpty() || storedKeys().isNotEmpty()

    /** Opens every intro again (memory and storage, v1 and v2). */
    fun expandAll() {
        sessionCollapsed.clear()
        storedKeys().forEach { key -> runCatching { localStorage.removeItem(key) } }
    }

    /** The v2 keys, plus the v1 keys whose value is still `"hidden"` (not yet migrated). */
    private fun storedKeys(): List<String> =
        runCatching {
            (0 until localStorage.length)
                .mapNotNull { index -> localStorage.key(index) }
                .filter { key ->
                    key.startsWith(PREFIX_V2) ||
                        (key.startsWith(PREFIX_V1) && runCatching { localStorage.getItem(key) == HIDDEN_V1 }.getOrDefault(false))
                }
        }.getOrDefault(emptyList())
}

/**
 * Hangs the intro of a view into this container (the page header does it for every screen). It is always there: open (text, optional
 * "Mehr", help link and an "Einklappen" button) or collapsed to one small "Hinweis anzeigen" toggle.
 *
 * - The text is plain TEXT (`P(content = ...)`, never `rich`).
 * - The help link exists only for a topic in [HelpTopics.available]; it opens in a new tab with `rel="noopener noreferrer"`, the arrow is
 *   decoration (`aria-hidden`).
 * - Folding swaps only the content of the section, never the page. Collapse hands the focus to the new toggle, expand to the intro body
 *   (`tabindex=-1`); if the target is not rendered yet the title is the fallback -- the focus is never lost to the document.
 * - The collapsed toggle shows its name as visible text (WCAG 2.5.3: no `aria-label`); the open button's `aria-label` contains its visible
 *   word "Einklappen". `aria-controls` points at the body id, which does not exist while collapsed -- allowed by ARIA, kept in both states
 *   so the relation is declared once.
 * - V1.9.188: an optional [more] (plain text) sits in a closed `<details>` with a "Mehr" summary, directly after the text and before the
 *   help link.
 * - DOM order is text, [more], help link, collapse button -- the tab order. No animation, no transition.
 *
 * [labelledByTitle] ties the region to the page `h1` ([PAGE_TITLE_ID]); a direct caller without that `h1` gets an `aria-label` instead.
 */
internal fun Container.pageIntro(
    viewKey: String,
    text: String,
    helpTopic: String? = null,
    labelledByTitle: Boolean = false,
    more: String? = null,
): Tag {
    val section = Tag(TAG.SECTION, className = "page-intro")
    val sectionId = "page-intro-$viewKey"
    val bodyId = "$sectionId-body"
    section.setAttribute("id", sectionId)
    if (labelledByTitle) {
        section.setAttribute("aria-labelledby", PAGE_TITLE_ID)
    } else {
        section.setAttribute("aria-label", gettext("Einführung"))
    }

    fun focusTitle() = (document.getElementById(PAGE_TITLE_ID) as? HTMLElement)?.focus()

    lateinit var render: () -> Unit

    fun buildCollapsed(): Button {
        val toggle = newActionButton(ActionIcon.INFO, tr("Hinweis anzeigen"), style = ButtonStyle.LINK, small = true)
        toggle.addCssClass("page-intro__toggle")
        toggle.title = gettext("Einführung dieser Seite anzeigen")
        toggle.setAttribute("aria-expanded", "false")
        toggle.setAttribute("aria-controls", bodyId)
        val chevron = Tag(TAG.SPAN, className = ActionIcon.CHEVRON_DOWN.css)
        chevron.setAttribute("aria-hidden", "true")
        toggle.add(chevron)
        toggle.onClick {
            PageIntroStorage.expand(viewKey)
            render()
            focusWhenRendered(fallback = ::focusTitle) { section.findFirstById(bodyId) }
        }
        return toggle
    }

    fun buildOpen() {
        val body = Tag(TAG.DIV, className = "page-intro__body")
        body.setAttribute("id", bodyId)
        body.setAttribute("tabindex", "-1")
        body.add(P(content = text, className = "page-intro__text"))
        if (more != null) {
            // V1.9.188: the one optional "Mehr" part (only views named in the source scan's MORE_ALLOWED may pass one). A native `<details>`:
            // closed on every render (no `open` attribute, nothing stored, no script), the browser does the toggling and the keyboard handling.
            val details = Tag(TAG.DETAILS, className = "page-intro__more")
            details.add(Tag(TAG.SUMMARY, tr("Mehr"), className = "page-intro__more-toggle"))
            details.add(P(content = more, className = "page-intro__more-text"))
            body.add(details)
        }
        if (HelpTopics.exists(helpTopic)) {
            val link = Link(label = "", url = Branding.helpUrl(checkNotNull(helpTopic)), className = "page-intro__link")
            link.target = "_blank"
            link.setAttribute("rel", "noopener noreferrer")
            link.add(Tag(TAG.SPAN, tr("Mehr in der Hilfe")))
            val arrow = Tag(TAG.SPAN, " ↗")
            arrow.setAttribute("aria-hidden", "true")
            link.add(arrow)
            body.add(link)
        }
        section.add(body)

        val collapseName = gettext("Einführung einklappen")
        val collapse = newActionButton(ActionIcon.CHEVRON_UP, tr("Einklappen"), style = ButtonStyle.LINK, small = true)
        collapse.addCssClass("page-intro__collapse")
        collapse.title = collapseName
        collapse.setAttribute("aria-label", collapseName)
        collapse.setAttribute("aria-expanded", "true")
        collapse.setAttribute("aria-controls", bodyId)
        collapse.onClick {
            PageIntroStorage.collapse(viewKey)
            render()
            focusWhenRendered(fallback = ::focusTitle) { section.findFirstByClass("page-intro__toggle") }
        }
        section.add(collapse)
    }

    render = {
        section.removeAll()
        if (PageIntroStorage.isCollapsed(viewKey)) {
            section.addCssClass("page-intro--collapsed")
            section.add(buildCollapsed())
        } else {
            section.removeCssClass("page-intro--collapsed")
            buildOpen()
        }
    }
    render()
    add(section)
    return section
}

/** The rendered DOM element below this tag with the given id / class, or `null` while it is not rendered yet. */
private fun Tag.findFirstById(id: String): HTMLElement? = getElement()?.querySelector("#$id") as? HTMLElement

private fun Tag.findFirstByClass(cls: String): HTMLElement? = getElement()?.querySelector(".$cls") as? HTMLElement

/**
 * KVision renders through a virtual DOM, so the element that [target] names may not exist right after `add`. Retries for about a
 * quarter of a second, then falls back to [fallback] (the page title) -- never to `body`.
 */
private fun focusWhenRendered(
    fallback: () -> Unit,
    attempt: Int = 0,
    target: () -> HTMLElement?,
) {
    val element = target()
    if (element != null) {
        element.focus()
    } else if (attempt >= 15) {
        fallback()
    } else {
        window.setTimeout({ focusWhenRendered(fallback, attempt + 1, target) }, 16)
    }
}

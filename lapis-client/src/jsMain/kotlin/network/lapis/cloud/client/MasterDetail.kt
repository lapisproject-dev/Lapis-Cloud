package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Link
import io.kvision.i18n.tr
import io.kvision.table.Row
import io.kvision.table.Table
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTableElement

/**
 * V1.9.192 -- the one master-detail pattern (Gremien, Sitzungen; Umfragen and Konsensieren reuse the back button).
 *
 * A list is a real table whose name column is a real link (`<a href="#/committees/<id>">`, so Ctrl-click and middle-click work
 * natively); the row itself has no click handler. The detail page is its own route (deep link), has a deterministic "Zur Übersicht"
 * button ABOVE the h1 (never `history.back()`), and the list gives the focus back to the link that was opened.
 *
 * All text that comes from the server is sanitized here ([sanitizeUntrustedI18nText]); ids in routes are URL-encoded.
 */

private external fun encodeURIComponent(value: String): String

/** CSS class of a data row of a [masterTable] (hover tint, see `theme.css`). */
internal const val MASTER_ROW_CLASS = "lapis-master-row"

/** CSS class of the "Zur Übersicht" button (natural width, left aligned, see `theme.css`). */
internal const val BACK_BUTTON_CLASS = "lapis-back-button"

/** Attribute that carries the record id on the name link (target of the focus restore). */
internal const val RECORD_ID_ATTRIBUTE = "data-record-id"

/** `"$base/<id>"` with the id URL-encoded, so an id can never inject anything into an `href` or a route. */
internal fun detailRoute(
    base: String,
    id: String,
): String = "$base/${encodeURIComponent(id)}"

/**
 * The table of a master list: [standardTable] (R14/R15, `scope="col"` headers) plus a visually hidden `<caption>` as its accessible
 * name. [caption] must be a static `tr()` text.
 */
internal fun Container.masterTable(
    caption: String,
    headers: List<TableHeader>,
    mobileLayout: TableMobileLayout = TableMobileLayout.CARDS,
): Table {
    requireValidMobileHidden(headers)
    val table = standardTable(headers, stickyFirstColumn = mobileLayout == TableMobileLayout.SCROLL)
    table.caption = caption
    table.addCssClass(REPORT_CAPTION_HIDDEN_CLASS)
    if (mobileLayout == TableMobileLayout.CARDS) table.addCssClass(TABLE_CARDS_CLASS)
    val hidden = headers.indices.filter { headers[it].mobileHidden }.toSet()
    var observer: TableLabelObserver? = null
    table.addAfterInsertHook { vnode ->
        val element = vnode.elm as? HTMLTableElement ?: return@addAfterInsertHook
        observer?.stop()
        observer = TableLabelObserver(element, hidden).also { it.start() }
    }
    table.addAfterDestroyHook {
        observer?.stop()
        observer = null
    }
    return table
}

/** How a [masterTable] behaves below 768 px: a card list (default) or a sideways scrolling table with a sticky first column. */
internal enum class TableMobileLayout { CARDS, SCROLL }

/** Marks a data row of a [masterTable]. */
internal fun Row.masterRow() {
    addCssClass(MASTER_ROW_CLASS)
}

/**
 * The name link of a master row: a real anchor to the detail route. [text] is untrusted and sanitized. A click remembers the id
 * ([MasterDetailFocus]) so the list can give the focus back; the navigation itself is the browser's own (hash link).
 */
internal fun Container.recordLink(
    text: String,
    base: String,
    id: String,
): Link {
    val link = Link(label = sanitizeUntrustedI18nText(text), url = "#${detailRoute(base, id)}")
    link.setAttribute(RECORD_ID_ATTRIBUTE, id)
    link.onClick { MasterDetailFocus.remember(base, id) }
    if (MasterDetailFocus.consumeIfRemembered(base, id)) {
        link.addAfterInsertHook { vnode -> (vnode.elm as? HTMLElement)?.focus() }
    }
    add(link)
    return link
}

/**
 * "Zur Übersicht" above the page header of a detail page. Always navigates to [listRoute] (never `history.back()`, which would leave
 * the app after a deep link). Natural width, not full width (`lapis-back-button`). V1.9.194: [label] names the goal where "Übersicht" is
 * not it ("Zur Liste"); the rule is arrow plus text that says WHERE it goes, never a bare "Zurück".
 */
internal fun Container.overviewBackButton(
    listRoute: String,
    label: String = tr("Zur Übersicht"),
    navigate: (String) -> Unit = ::navigateTo,
): Button = backButton(label) { navigate(listRoute) }

/**
 * V1.9.194: THE back button of the app -- arrow icon plus a visible text that names the goal, outline secondary, natural width (never
 * `w-100`/`d-grid`). Used wherever a screen is left towards another one without a route change of its own (voting booths, conference
 * dock, editors, notices). A caller that needs the button before its handler exists leaves [onBack] empty and adds `onClick` later. [overviewBackButton] is the same component for a plain route.
 */
internal fun Container.backButton(
    label: String,
    onBack: () -> Unit = {},
): Button {
    val button = newActionButton(ActionIcon.BACK, label, ButtonStyle.OUTLINESECONDARY)
    button.addCssClass(BACK_BUTTON_CLASS)
    button.onClick { onBack() }
    add(button)
    return button
}

/**
 * Remembers, per list route, which record was opened last, so that "Zur Übersicht" (or the browser's back button) lands on the very
 * link again. Module-wide, no `localStorage`. The entry is consumed by the first restore and dropped by [clearAll] whenever the
 * person goes to a route outside the two master-detail families (see `show` in `Routing.kt`), so a fresh visit never restores.
 */
internal object MasterDetailFocus {
    private val last = mutableMapOf<String, String>()

    fun remember(
        base: String,
        id: String,
    ) {
        last[base] = id
    }

    /** The remembered id of [base] without consuming it (tests). */
    fun peek(base: String): String? = last[base]

    fun clearAll() {
        last.clear()
    }

    /**
     * True (once) when [id] is the remembered record of [base]; the name link then takes the focus as soon as it is in the DOM. Called by
     * [recordLink] while it builds the link, so the insert hook is registered before the widget is added.
     */
    fun consumeIfRemembered(
        base: String,
        id: String,
    ): Boolean {
        if (last[base] != id) return false
        last.remove(base)
        return true
    }
}

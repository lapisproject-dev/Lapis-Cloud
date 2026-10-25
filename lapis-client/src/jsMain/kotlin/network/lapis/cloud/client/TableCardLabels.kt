package network.lapis.cloud.client

import org.w3c.dom.Element
import org.w3c.dom.HTMLTableElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit

/**
 * R62 / V1.9.211 -- labels the cells of a [masterTable] so `theme.css` can show it as a card list below 768 px.
 *
 * Per body cell: `data-label` (text of the column header, shown by `td::before`), `data-cell="actions"` for a cell that holds a
 * [tableActionGroup], `data-mobile-hidden` for a column declared [TableHeader.mobileHidden]. Explicit ARIA roles are set on the
 * table parts, because `display: block/flex` removes the native table semantics in several browsers. The label text comes from
 * the app's own header texts and is only ever written with `setAttribute` (never as HTML).
 */
internal fun labelTable(
    table: HTMLTableElement,
    hiddenColumns: Set<Int>,
) {
    val headers =
        table
            .querySelectorAll("thead th")
            .let { list ->
                (0 until list.length).map { index ->
                    list
                        .item(index)
                        ?.textContent
                        .orEmpty()
                        .trim()
                }
            }
    setIfDifferent(table, "role", "table")
    forEachElement(table.querySelectorAll("thead, tbody")) { setIfDifferent(it, "role", "rowgroup") }
    forEachElement(table.querySelectorAll("tr")) { setIfDifferent(it, "role", "row") }
    forEachElement(table.querySelectorAll("thead th")) { setIfDifferent(it, "role", "columnheader") }
    forEachElement(table.querySelectorAll("tbody tr")) { row ->
        val cells = row.children
        for (index in 0 until cells.length) {
            val cell = cells.item(index) ?: continue
            if (cell.tagName.equals("TH", ignoreCase = true)) {
                setIfDifferent(cell, "role", "rowheader")
                continue
            }
            setIfDifferent(cell, "role", "cell")
            // A spanning cell (e.g. an expandable detail row) belongs to no single column: no label, no actions marker.
            if (cell.hasAttribute("colspan")) continue
            if (cell.querySelector(".$TABLE_ACTIONS_CLASS") != null) {
                setIfDifferent(cell, "data-cell", "actions")
                continue
            }
            setIfDifferent(cell, "data-label", headers.getOrElse(index) { "" })
            if (index in hiddenColumns) setIfDifferent(cell, "data-mobile-hidden", "true")
        }
    }
}

private fun forEachElement(
    list: org.w3c.dom.NodeList,
    action: (Element) -> Unit,
) {
    for (index in 0 until list.length) (list.item(index) as? Element)?.let(action)
}

private fun setIfDifferent(
    element: Element,
    name: String,
    value: String,
) {
    if (element.getAttribute(name) != value) element.setAttribute(name, value)
}

/** Keeps [labelTable] current while rows are added or replaced (only `childList`/`subtree` is observed, so attribute writes never loop). */
internal class TableLabelObserver(
    private val table: HTMLTableElement,
    private val hiddenColumns: Set<Int>,
) {
    private var observer: MutationObserver? = null

    fun start() {
        stop()
        labelTable(table, hiddenColumns)
        val created = MutationObserver { _, _ -> labelTable(table, hiddenColumns) }
        created.observe(table, MutationObserverInit(childList = true, subtree = true))
        observer = created
    }

    fun stop() {
        observer?.disconnect()
        observer = null
    }
}

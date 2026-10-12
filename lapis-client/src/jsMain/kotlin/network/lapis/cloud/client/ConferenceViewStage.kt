package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.document
import network.lapis.cloud.shared.conference.ConferenceTileRef
import network.lapis.cloud.shared.conference.ConferenceViewLayout
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/*
 * V1.9.115 -- the DOM side of the switchable views: the containers inside the call's tile area (gallery, large tile, filmstrip, pager,
 * parking) and the one function that moves the EXISTING tile elements between them.
 *
 * The rule of V1.4.19 holds: a tile element (and the `<video>` inside it) is created once, by `buildTile`, and only ever MOVED here with
 * `insertBefore` / `appendChild` -- never cloned, never re-created -- and only when its place really changed (a `<video>` that is
 * re-attached every beat can hiccup). Tiles that are on no page and not in the strip rest in the parking container (display:none, still in
 * the document, so playback and the watchdog are unaffected; LiveKit's adaptive stream stops fetching pictures nobody sees).
 *
 * Nothing here knows a name or an identity beyond the opaque keys of [ConferenceTileRef]; labels are set with `setAttribute` / `textContent`.
 */

internal const val CONFERENCE_STRIP_ACTIVATABLE_ATTR = "data-lapis-strip"

/** What the stage reports back to the screen. */
internal class ConferenceViewStageHandlers(
    /** The gallery pager: -1 previous page, +1 next page. */
    val onPage: (Int) -> Unit,
    /** The strip arrows: -1 / +1. */
    val onStripPage: (Int) -> Unit,
    /** A tile of the strip was tapped or activated by keyboard: the key of the tile (identity, or `identity#screen`). */
    val onStripTile: (String) -> Unit,
)

internal class ConferenceViewContainers(
    val gallery: HTMLElement,
    val main: HTMLElement,
    val stripRow: HTMLElement,
    val strip: HTMLElement,
    val stripPrev: HTMLElement,
    val stripNext: HTMLElement,
    val pager: HTMLElement,
    val pagerPrev: HTMLElement,
    val pagerNext: HTMLElement,
    val pagerStatus: HTMLElement,
    val parked: HTMLElement,
)

/** The state of the strip as the screen sliced it: the visible tiles and the page position (for the arrows). */
internal class ConferenceStripState(
    val visible: List<ConferenceTileRef>,
    val page: Int,
    val pageCount: Int,
)

internal object ConferenceViewStage {
    private fun arrow(
        icon: String,
        label: String,
        onClick: () -> Unit,
    ): HTMLElement {
        val button = document.createElement("button") as HTMLElement
        button.setAttribute("type", "button")
        button.className = "btn btn-outline-secondary lapis-conference-pager-btn"
        button.setAttribute("aria-label", label)
        val glyph = document.createElement("i") as HTMLElement
        glyph.className = icon
        glyph.setAttribute("aria-hidden", "true")
        button.appendChild(glyph)
        button.addEventListener("click", { onClick() })
        return button
    }

    /** Creates the containers ONCE and appends them to [root] (the call's tile area). */
    fun create(
        root: HTMLElement,
        handlers: ConferenceViewStageHandlers,
    ): ConferenceViewContainers {
        val gallery = document.createElement("div") as HTMLElement
        // V1.9.92: a centred, wrapping flex row (the last row sits in the middle); the tile size comes from `applyTileGrid`
        gallery.className = "lapis-conference-tiles"
        gallery.style.cssText = "display:flex;flex-wrap:wrap;justify-content:center;align-content:center;gap:8px;"
        gallery.style.setProperty("--lapis-tile-w", "320px")
        gallery.style.setProperty("--lapis-tile-h", "180px")
        root.appendChild(gallery)

        val pager = document.createElement("div") as HTMLElement
        pager.className = "lapis-conference-pager"
        pager.style.display = "none"
        val prev = arrow("fas fa-chevron-left", gettext("Vorherige Seite")) { handlers.onPage(-1) }
        val next = arrow("fas fa-chevron-right", gettext("Nächste Seite")) { handlers.onPage(1) }
        val status = document.createElement("span") as HTMLElement
        status.className = "lapis-conference-pager-status"
        status.setAttribute("role", "status")
        pager.appendChild(prev)
        pager.appendChild(status)
        pager.appendChild(next)
        root.appendChild(pager)

        val main = document.createElement("div") as HTMLElement
        main.className = "lapis-conference-main"
        main.style.display = "none"
        root.appendChild(main)

        val stripRow = document.createElement("div") as HTMLElement
        stripRow.className = "lapis-conference-strip-row"
        stripRow.style.display = "none"
        val stripPrev = arrow("fas fa-chevron-left", gettext("Vorherige Kacheln")) { handlers.onStripPage(-1) }
        val strip = document.createElement("div") as HTMLElement
        strip.className = "lapis-conference-filmstrip"
        val stripNext = arrow("fas fa-chevron-right", gettext("Weitere Kacheln")) { handlers.onStripPage(1) }
        stripRow.appendChild(stripPrev)
        stripRow.appendChild(strip)
        stripRow.appendChild(stripNext)
        root.appendChild(stripRow)
        // One delegated listener: a tap or Enter / Space on a tile of the strip pins that person.
        strip.addEventListener("click", { event -> stripTileOf(strip, event)?.let { handlers.onStripTile(it) } })
        strip.addEventListener(
            "keydown",
            { event ->
                val key = (event as? KeyboardEvent)?.key
                if (key == "Enter" || key == " ") {
                    stripTileOf(strip, event)?.let {
                        event.preventDefault()
                        handlers.onStripTile(it)
                    }
                }
            },
        )

        val parked = document.createElement("div") as HTMLElement
        parked.className = "lapis-conference-parked"
        parked.style.display = "none"
        parked.setAttribute("aria-hidden", "true")
        root.appendChild(parked)
        return ConferenceViewContainers(gallery, main, stripRow, strip, stripPrev, stripNext, pager, prev, next, status, parked)
    }

    private fun stripTileOf(
        strip: HTMLElement,
        event: Event,
    ): String? {
        var node: org.w3c.dom.Element? = event.target as? org.w3c.dom.Element
        while (node != null && node !== strip) {
            val key = node.getAttribute(CONFERENCE_STRIP_ACTIVATABLE_ATTR)
            if (key != null) return key
            node = node.parentElement
        }
        return null
    }

    /**
     * Moves the existing tile elements into the containers the [layout] asks for. [elementOf] maps a tile to its element (`null`: no such
     * tile any more), [allTiles] is every tile element there is (those that are on no list rest in the parking container). Returns whether
     * ANY element changed its place (the caller then lets stalled videos play on and re-measures).
     */
    fun apply(
        c: ConferenceViewContainers,
        layout: ConferenceViewLayout,
        strip: ConferenceStripState,
        elementOf: (ConferenceTileRef) -> HTMLElement?,
        allTiles: Collection<HTMLElement>,
        stripNames: (ConferenceTileRef) -> String,
    ): Boolean {
        var moved = false

        val big = layout.main
        val bigElement = big?.let(elementOf)
        val stripTiles = if (bigElement != null) strip.visible.mapNotNull { tile -> elementOf(tile)?.let { tile to it } } else emptyList()
        val galleryWanted = if (big == null) layout.galleryPage.mapNotNull(elementOf) else emptyList()
        val wantedAll =
            HashSet<HTMLElement>().apply {
                addAll(galleryWanted)
                addAll(listOfNotNull(bigElement))
                addAll(stripTiles.map { it.second })
            }

        fun place(
            container: HTMLElement,
            wanted: List<HTMLElement>,
        ) {
            wanted.forEachIndexed { index, element ->
                val at = container.children.item(index)
                if (at !== element) {
                    // an element that is already in this container later in the list is moved (a synchronous move does not pause a <video>)
                    container.insertBefore(element, at)
                    moved = true
                }
            }
            // everything behind the wanted ones leaves: parked, unless another container wants it (that one moves it, once)
            container.children.elements().drop(wanted.size).forEach { extra ->
                if (extra is HTMLElement && extra !in wantedAll) {
                    c.parked.appendChild(extra)
                    moved = true
                }
            }
        }

        place(c.main, listOfNotNull(bigElement))
        place(c.strip, stripTiles.map { it.second })
        place(c.gallery, galleryWanted)
        val placed = HashSet<HTMLElement>()
        placed +=
            c.gallery.children
                .elements()
                .mapNotNull { it as? HTMLElement }
        placed +=
            c.main.children
                .elements()
                .mapNotNull { it as? HTMLElement }
        placed +=
            c.strip.children
                .elements()
                .mapNotNull { it as? HTMLElement }
        for (element in allTiles) {
            if (element !in placed && element.parentNode !== c.parked) {
                c.parked.appendChild(element)
                moved = true
            }
        }

        // strip tiles are activatable (tap / Enter pins the person); tiles that left the strip lose that again
        val activatable = HashSet<HTMLElement>()
        stripTiles.forEach { (tile, element) ->
            if (!tile.screenShare) {
                activatable += element
                val label = gettext("%1 anheften", stripNames(tile))
                if (element.getAttribute(CONFERENCE_STRIP_ACTIVATABLE_ATTR) != tile.key) {
                    element.setAttribute(CONFERENCE_STRIP_ACTIVATABLE_ATTR, tile.key)
                    element.setAttribute("role", "button")
                    element.setAttribute("tabindex", "0")
                }
                if (element.getAttribute("aria-label") != label) element.setAttribute("aria-label", label)
            }
        }
        for (element in allTiles) {
            if (element !in activatable) clearActivatable(element)
        }

        val galleryShown = big == null
        c.gallery.style.display = if (galleryShown) "flex" else "none"
        c.main.style.display = if (galleryShown) "none" else "flex"
        c.stripRow.style.display = if (galleryShown || stripTiles.isEmpty()) "none" else "flex"
        c.stripPrev.style.visibility = if (strip.page > 0) "visible" else "hidden"
        c.stripNext.style.visibility = if (strip.page < strip.pageCount - 1) "visible" else "hidden"

        val paged = galleryShown && layout.pageCount > 1
        c.pager.style.display = if (paged) "flex" else "none"
        if (paged) applyPager(c, layout)
        return moved
    }

    private fun clearActivatable(element: HTMLElement) {
        if (element.getAttribute(CONFERENCE_STRIP_ACTIVATABLE_ATTR) == null) return
        element.removeAttribute(CONFERENCE_STRIP_ACTIVATABLE_ATTR)
        element.removeAttribute("role")
        element.removeAttribute("tabindex")
        element.removeAttribute("aria-label")
    }

    private fun applyPager(
        c: ConferenceViewContainers,
        layout: ConferenceViewLayout,
    ) {
        c.pagerStatus.textContent = gettext("Seite %1 von %2", layout.page + 1, layout.pageCount)
        val hasPrev = layout.page > 0
        val hasNext = layout.page < layout.pageCount - 1
        setPagerArrow(c.pagerPrev, enabled = hasPrev)
        setPagerArrow(c.pagerNext, enabled = hasNext)
        val target = layout.speakerPage
        val prevHint = target != null && target < layout.page
        val nextHint = target != null && target > layout.page
        c.pagerPrev.classList.toggle(CONFERENCE_PAGER_SPEAKING_CLASS, prevHint)
        c.pagerNext.classList.toggle(CONFERENCE_PAGER_SPEAKING_CLASS, nextHint)
        c.pagerPrev.setAttribute(
            "aria-label",
            if (prevHint) gettext("Vorherige Seite, dort spricht gerade jemand (Seite %1)", target!! + 1) else gettext("Vorherige Seite"),
        )
        c.pagerNext.setAttribute(
            "aria-label",
            if (nextHint) gettext("Nächste Seite, dort spricht gerade jemand (Seite %1)", target!! + 1) else gettext("Nächste Seite"),
        )
    }

    private fun setPagerArrow(
        button: HTMLElement,
        enabled: Boolean,
    ) {
        if (enabled) {
            button.removeAttribute("aria-disabled")
            button.classList.remove("disabled")
        } else {
            button.setAttribute("aria-disabled", "true")
            button.classList.add("disabled")
        }
    }
}

/** The page arrow that leads to a page where somebody is speaking carries this class (a gold ring, see theme.css). */
internal const val CONFERENCE_PAGER_SPEAKING_CLASS = "lapis-conference-pager--speaking"

private fun org.w3c.dom.HTMLCollection.elements(): List<org.w3c.dom.Element> = (0 until length).mapNotNull { item(it) }

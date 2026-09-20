package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.image
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Sammelt rohe DOM-Attribute eines KVision-Widgets und schreibt sie, sobald das Element existiert -- ueber
 * EINEN `addAfterInsertHook` (nicht einen je Aufruf) und erneut bei jedem [set]. Grund: `addAfterInsertHook`
 * feuert nur einmal pro Element-Instanz (Stolperfalle 8 des Bestands, siehe `ConferenceScreen.kt`
 * `setStaticA11yLabel`), Attribute wie `aria-checked` muessen aber bei jedem Zustandswechsel nachgezogen
 * werden.
 */
private class RawAttributes(
    private val widget: Widget,
) {
    private val values = mutableMapOf<String, String>()
    private var hookRegistered = false

    fun set(
        name: String,
        value: String,
    ) {
        values[name] = value
        val element = widget.getElement()
        if (element != null) {
            element.setAttribute(name, value)
        } else if (!hookRegistered) {
            hookRegistered = true
            widget.addAfterInsertHook { vnode ->
                (vnode.elm as? HTMLElement)?.let { el -> values.forEach { (n, v) -> el.setAttribute(n, v) } }
            }
        }
    }
}

private class BackgroundTile(
    val effect: ConferenceBackgroundEffect,
    val root: Div,
    val check: Widget,
    val attributes: RawAttributes,
)

/** DOM-`id` der ausklappbaren Gruppe -- Ziel von `aria-controls` am Knopf (Audit-Befund N5). */
private const val BACKGROUND_PANEL_ID = "lapis-conference-background-panel"

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- der sichtbare Abschnitt im "Mehr"-Blatt: EINE eingeklappte
 * Zeile (volle Breite, linksbuendig, visuell identisch zu Whiteboard/Notizen -- Jobs-Ruling K1: die
 * "Mehr"-Schublade waechst durch dieses Feature nicht), darunter ausklappbar der Datenschutzsatz, die
 * Ladezeile und das Raster aus neun Kacheln (Aus, Weichzeichnen leicht/stark, sechs Bilder).
 *
 * Gebaut von `ConferenceScreen.kt` direkt NACH den Whiteboard-/Notizen-Knoepfen und VOR der Geraete-Gruppe --
 * die DOM-Reihenfolge im `moreSheet` bleibt so exakt "Whiteboard/Notizen, Hintergrund, Geraete". Die Gruppe
 * traegt bewusst NICHT `lapis-conference-config-row` (wie `deviceGroup`): sie ueberlebt den Vollbildmodus.
 *
 * **Nichtunterstuetzung** (Norman-Ruling K4, kein Ausblenden): die Zeile bleibt sichtbar, der Knopf ist
 * deaktiviert, darunter steht ein Erklaersatz; das Raster wird in diesem Fall gar nicht gebaut. Es gibt zwei
 * Erklaersaetze -- "Browser/Geraet" und "in der App noch nicht unterstuetzt" (Audit-Befund M5, siehe
 * [ConferenceBackgroundAvailability]).
 *
 * Alle Texte hier: `tr(...)` NUR als Widget-Inhalt, `gettext(...)` fuer Attribute
 * (`ClientTrAttributeLeakTest`); Attribute laufen ueber [RawAttributes] (mit erneutem Setzen bei jedem
 * [render]). Sichtbare Beschriftungen kommen aus den `…Tr`-Varianten, damit ein Sprachwechsel zur Laufzeit
 * sie neu uebersetzt (Audit-Befund N4).
 */
internal class ConferenceBackgroundSection(
    parent: Container,
    val availability: ConferenceBackgroundAvailability,
    private val onSelect: (ConferenceBackgroundEffect) -> Unit,
) {
    val supported: Boolean get() = availability == ConferenceBackgroundAvailability.AVAILABLE

    // `addCssClasses` (Plural!) -- `addCssClass` mit Leerzeichen wirft InvalidCharacterError mitten im
    // Aufbau des `moreSheet` und hat schon einmal jedes nachfolgende Geschwister aus dem DOM gekippt
    // (siehe `whiteboardToggleButton` in ConferenceScreen.kt).
    val toggleButton: Button =
        parent.button(
            conferenceBackgroundToggleLabelTr(ConferenceBackgroundEffect.OFF),
            icon = "fas fa-image",
            style = ButtonStyle.OUTLINESECONDARY,
        )
    val group: SimplePanel = parent.vPanel(spacing = 6) { addCssClasses("lapis-conference-background-group") }

    var panelOpen: Boolean = false
        private set

    private val toggleAttributes = RawAttributes(toggleButton)
    private val groupAttributes = RawAttributes(group)
    private val tiles = mutableListOf<BackgroundTile>()
    private var loadingLine: Div? = null
    private var grid: Div? = null
    private var gridAttributes: RawAttributes? = null

    /**
     * Audit-Befund N5: waehrend einer laufenden Anwendung nimmt das Raster keine Klicks/Tasten an -- sonst
     * kettet ein ungeduldiger Nutzer mehrere `switchTo`-Aufrufe aneinander, die der Controller zwar
     * serialisiert, aber jeder einzelne kostet Ladezeit und kann in den 10-s-Deckel laufen.
     */
    private var applying: Boolean = false

    init {
        toggleButton.addCssClasses("w-100 text-start")
        groupAttributes.set("id", BACKGROUND_PANEL_ID)
        if (!supported) {
            toggleButton.disabled = true
            group.div(unsupportedExplanation()) { addCssClasses("text-muted small") }
        } else {
            group.hide()
            buildOpenContent()
            toggleButton.onClick { toggle() }
        }
        applyToggleAria()
    }

    /** Beide Nichtunterstuetzungs-Saetze -- `tr(...)`, weil sie direkt Widget-Inhalt sind (Audit-Befund N4). */
    private fun unsupportedExplanation(): String =
        if (availability == ConferenceBackgroundAvailability.UNSUPPORTED_IN_APP_WEBVIEW) {
            tr("Hintergrundeffekte werden in der App noch nicht unterstützt – im Browser dieses Geräts stehen sie zur Verfügung.")
        } else {
            tr("Hintergrundeffekte werden von diesem Browser oder Gerät nicht unterstützt.")
        }

    private fun toggle() {
        panelOpen = !panelOpen
        if (panelOpen) group.show() else group.hide()
        applyToggleAria()
    }

    /**
     * `aria-expanded` (kein `aria-pressed` -- kein Ansichtsschalter im Sinne der bestehenden Regel) und
     * `aria-controls` auf die Gruppen-`id` (Audit-Befund N5: der Zusammenhang Knopf -> Bereich war fuer
     * Screenreader nicht ausgedrueckt).
     */
    fun applyToggleAria() {
        if (!supported) return
        toggleAttributes.set("aria-expanded", panelOpen.toString())
        toggleAttributes.set("aria-controls", BACKGROUND_PANEL_ID)
    }

    private fun buildOpenContent() {
        // Datenschutzsatz, FEST sichtbar (Zhuo-Ruling).
        group.div(tr("Die Bearbeitung findet nur in diesem Browser statt. Es wird kein Bild an den Server gesendet.")) {
            addCssClasses("text-muted small")
        }
        loadingLine =
            group.div(tr("Hintergrund wird vorbereitet …")) { addCssClasses("text-muted small") }.also { it.hide() }
        val builtGrid = group.div { addCssClasses("lapis-conference-background-grid") }
        grid = builtGrid
        gridAttributes =
            RawAttributes(builtGrid).apply {
                set("role", "radiogroup")
                set("aria-label", gettext("Hintergrundauswahl"))
                set("aria-busy", "false")
            }
        ConferenceBackgroundEffect.entries.forEach { effect -> tiles += buildTile(builtGrid, effect) }
    }

    private fun buildTile(
        grid: Div,
        effect: ConferenceBackgroundEffect,
    ): BackgroundTile {
        val root = grid.div { addCssClasses("lapis-conference-background-tile") }
        val attributes = RawAttributes(root)
        attributes.set("role", "radio")
        attributes.set("aria-label", conferenceBackgroundEffectLabel(effect))
        attributes.set("aria-checked", "false")
        attributes.set("tabindex", "-1")

        val preview = root.div { addCssClasses("lapis-conference-background-preview") }
        val imagePath = conferenceBackgroundImagePath(effect)
        when {
            imagePath != null -> preview.image(imagePath, "")
            effect == ConferenceBackgroundEffect.OFF -> {
                preview.addCssClasses("lapis-conference-background-preview-off")
                preview.icon("fas fa-ban")
            }
            effect == ConferenceBackgroundEffect.BLUR_LIGHT ->
                preview.addCssClasses("lapis-conference-background-preview-blur-light")
            else -> preview.addCssClasses("lapis-conference-background-preview-blur-strong")
        }
        // Kare: Farbe allein faellt bei Farbfehlsichtigkeit aus -- Haekchen ZUSAETZLICH zum Akzentrahmen.
        val check = root.icon("fas fa-check") { addCssClasses("lapis-conference-background-check") }
        check.hide()
        // Norman-Ruling K5: Bild UND Wort. `tr(...)`, damit ein Sprachwechsel zur Laufzeit greift (N4).
        root.div(conferenceBackgroundEffectLabelTr(effect)) { addCssClasses("small") }

        root.onClick { if (!applying) onSelect(effect) }
        root.addAfterInsertHook { vnode ->
            (vnode.elm as? HTMLElement)?.addEventListener("keydown", { event -> onTileKey(effect, event as? KeyboardEvent) })
        }
        return BackgroundTile(effect, root, check, attributes)
    }

    private fun onTileKey(
        effect: ConferenceBackgroundEffect,
        event: KeyboardEvent?,
    ) {
        if (event == null) return
        val index = tiles.indexOfFirst { it.effect == effect }
        when (event.key) {
            "Enter", " " -> {
                event.preventDefault()
                if (!applying) onSelect(effect)
            }
            "ArrowRight", "ArrowDown" -> moveFocus(event, index, index + 1)
            "ArrowLeft", "ArrowUp" -> moveFocus(event, index, index - 1)
        }
    }

    private fun moveFocus(
        event: KeyboardEvent,
        sourceIndex: Int,
        targetIndex: Int,
    ) {
        event.preventDefault()
        // Pfeiltasten wandern nur (kein Auswaehlen -- ein Wechsel laedt ggf. WASM), zyklisch.
        val target = tiles.getOrNull((targetIndex + tiles.size) % tiles.size) ?: return
        // Roving tabindex (ARIA APG radiogroup): das fokussierte Element traegt tabindex=0, alle anderen -1.
        // render() setzt es beim naechsten Zustandswechsel wieder auf die ausgewaehlte Kachel zurueck.
        tiles.getOrNull(sourceIndex)?.attributes?.set("tabindex", "-1")
        target.attributes.set("tabindex", "0")
        target.root.getElement()?.focus()
    }

    /**
     * DIE einzige Stelle, die Haekchen, `aria-checked`, Roving-`tabindex`, Knopfbeschriftung und Ladezeile
     * setzt -- aus dem `onStateChanged` des Controllers aufgerufen. Wiederholtes Setzen ist gewollt (siehe
     * [RawAttributes]).
     */
    fun render(state: ConferenceBackgroundState) {
        // Vor dem Guard: auf einem nicht unterstuetzten Browser bleibt der Knopf bei "Aus" (kein Effekt an einem
        // Bedienelement zeigen, das nicht bedienbar ist).
        if (!supported) return
        val displayed = conferenceBackgroundDisplayedEffect(state)
        // Sichtbarer Text als tr()-Marker (N4), Tooltip als zusammengesetzter gettext-Text (Attribut).
        toggleButton.text = conferenceBackgroundToggleLabelTr(displayed)
        toggleAttributes.set("title", conferenceBackgroundToggleLabel(displayed))
        applying = state.phase == ConferenceBackgroundPhase.APPLYING
        gridAttributes?.set("aria-busy", applying.toString())
        grid?.let { element ->
            if (applying) {
                element.addCssClasses("lapis-conference-background-grid-busy")
            } else {
                element.removeCssClass("lapis-conference-background-grid-busy")
            }
        }
        tiles.forEach { tile ->
            val selected = tile.effect == displayed
            tile.attributes.set("aria-checked", selected.toString())
            tile.attributes.set("aria-disabled", applying.toString())
            tile.attributes.set("tabindex", if (selected) "0" else "-1")
            if (selected) {
                tile.root.addCssClasses("lapis-conference-background-tile-selected")
                tile.check.show()
            } else {
                tile.root.removeCssClass("lapis-conference-background-tile-selected")
                tile.check.hide()
            }
        }
        val loading = loadingLine
        if (loading != null) {
            if (state.phase == ConferenceBackgroundPhase.APPLYING) loading.show() else loading.hide()
        }
        applyToggleAria()
    }
}

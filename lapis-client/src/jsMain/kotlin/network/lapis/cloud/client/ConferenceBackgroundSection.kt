package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onClick
import io.kvision.form.upload.upload
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
import network.lapis.cloud.shared.domain.ConferenceBackgroundImageDto
import network.lapis.cloud.shared.domain.ConferenceBackgroundRules
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.files.File

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
    val choice: ConferenceBackgroundChoice,
    val root: Div,
    val check: Widget,
    val attributes: RawAttributes,
)

/** V1.9.4 -- one own-uploaded-image tile, including its `role="none"` wrapper and × remove button (see [F4] in class KDoc). */
private class CustomTileEntry(
    val imageId: String,
    val wrapper: Div,
    val tile: BackgroundTile,
    val removeButton: Button,
)

/** DOM-`id` der ausklappbaren Gruppe -- Ziel von `aria-controls` am Knopf (Audit-Befund N5). */
private const val BACKGROUND_PANEL_ID = "lapis-conference-background-panel"

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- der sichtbare Abschnitt im "Mehr"-Blatt: EINE eingeklappte
 * Zeile (volle Breite, linksbuendig, visuell identisch zu Whiteboard/Notizen -- Jobs-Ruling K1: die
 * "Mehr"-Schublade waechst durch dieses Feature nicht), darunter ausklappbar der Datenschutzsatz, die
 * Ladezeile und das Raster aus neun eingebauten Kacheln (Aus, Weichzeichnen leicht/stark, sechs Bilder)
 * PLUS, seit V1.9.4, die eigenen hochgeladenen Bilder.
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
 * **F4 (ARIA-Aufbau, Umsetzungsplan)**: jede eigene Kachel steckt in einem `div[role="none"]`-Wrapper,
 * der die eigentliche `role="radio"`-Kachel UND den ×-Entfernen-Knopf als GESCHWISTER haelt -- damit
 * enthaelt das `role="radiogroup"`-Raster selbst ausschliesslich `role="radio"`-Elemente (kein `button`
 * ist Nachfahre eines davon), waehrend Kachel und Loeschknopf trotzdem visuell und strukturell
 * zusammengehoeren. Das × sitzt OBEN LINKS (Raskin: stabile Positionen -- oben rechts sitzt dauerhaft
 * der Auswahl-Haken).
 *
 * Alle Texte hier: `tr(...)` NUR als Widget-Inhalt, `gettext(...)` fuer Attribute
 * (`ClientTrAttributeLeakTest`); Attribute laufen ueber [RawAttributes] (mit erneutem Setzen bei jedem
 * [render]). Sichtbare Beschriftungen kommen aus den `…Tr`-Varianten, damit ein Sprachwechsel zur Laufzeit
 * sie neu uebersetzt (Audit-Befund N4).
 */
internal class ConferenceBackgroundSection(
    parent: Container,
    val availability: ConferenceBackgroundAvailability,
    private val onSelect: (ConferenceBackgroundChoice) -> Unit,
    /** V1.9.4 -- ausgewaehlte Datei normalisieren+hochladen (siehe `ConferenceScreen.kt` Aufrufstelle). */
    private val onUploadFile: (File) -> Unit,
    /** V1.9.4 -- eigenes Bild entfernen (nach Bestaetigung, siehe [buildCustomTile]). */
    private val onDeleteCustom: (String) -> Unit,
    /** V1.9.4 -- F1: nur ein volles Mitglied darf hochladen; Anzeigen/Loeschen bleiben unabhaengig davon moeglich. */
    private val uploadEligible: Boolean,
) {
    val supported: Boolean get() = availability == ConferenceBackgroundAvailability.AVAILABLE

    // `addCssClasses` (Plural!) -- `addCssClass` mit Leerzeichen wirft InvalidCharacterError mitten im
    // Aufbau des `moreSheet` und hat schon einmal jedes nachfolgende Geschwister aus dem DOM gekippt
    // (siehe `whiteboardToggleButton` in ConferenceScreen.kt).
    val toggleButton: Button =
        parent.button(
            conferenceBackgroundToggleLabelTr(CONFERENCE_BACKGROUND_OFF),
            icon = "fas fa-image",
            style = ButtonStyle.OUTLINESECONDARY,
        )
    val group: SimplePanel = parent.vPanel(spacing = 6) { addCssClasses("lapis-conference-background-group") }

    var panelOpen: Boolean = false
        private set

    private val toggleAttributes = RawAttributes(toggleButton)
    private val groupAttributes = RawAttributes(group)
    private val tiles = mutableListOf<BackgroundTile>()
    private val customEntries = mutableListOf<CustomTileEntry>()
    private var loadingLine: Div? = null
    private var grid: Div? = null
    private var gridAttributes: RawAttributes? = null
    private var uploadCountLine: Div? = null
    private var statusLine: Div? = null
    private var uploadButtonRef: Button? = null

    /**
     * Review-Befund (MAJOR): der zuletzt von [render] gesehene Zustand -- [setCustomImages] baut die
     * eigenen Kacheln komplett NEU auf (frische [BackgroundTile]-Instanzen mit `aria-checked=false`,
     * verstecktem Haekchen, `tabindex=-1`) und wartet sonst auf den NAECHSTEN `onStateChanged`-Aufruf
     * des Controllers, um sie wieder korrekt einzufaerben. Wird zwischen zwei Aufbauten aber gar kein
     * neuer Zustandswechsel ausgeloest (z. B. `onCustomImageDeleted` fuer ein ANDERES Bild als die
     * aktuelle Auswahl -- ein reines No-op im Controller), verliert die tatsaechlich ausgewaehlte
     * Kachel dauerhaft ihr Haekchen/`aria-checked=true`, und das Raster hat kein `tabindex=0`-Element
     * mehr fuer die Tastatur.
     */
    private var lastState: ConferenceBackgroundState? = null

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
        // Datenschutzsatz 1, FEST sichtbar (Zhuo-Ruling).
        group.div(tr("Ihr Kamerabild wird nur in diesem Browser bearbeitet und nie an den Server gesendet.")) {
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
        ConferenceBackgroundEffect.entries.forEach { effect ->
            tiles += buildBuiltInTile(builtGrid, ConferenceBackgroundChoice.BuiltIn(effect))
        }

        if (uploadEligible) {
            // Gleiches Muster wie `TravelExpenseScreen.kt`/`DocumentsScreen.renderVersionUpload`
            // (registriertes `Upload`-Feld + eigener Absenden-Knopf, siehe `FormGrammar.kt`) --
            // bewusst KEIN handgebautes verstecktes `<input type=file>` (kein Praezedenzfall dafuer
            // in diesem Client, das registrierte Formularfeld ist der etablierte, gepruefte Weg).
            val row = group.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
            val uploadForm = row.lapisForm()
            val fileUpload = uploadForm.panel.upload(label = tr("Eigenes Bild auswählen"))

            fun selectedNativeFile() = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) }
            val fileField =
                uploadForm.register(
                    fileUpload,
                    label = tr("Eigenes Bild auswählen"),
                    required = true,
                    requiredMessage = tr("Bitte eine Datei auswählen."),
                )
            val addButton = Button(conferenceUploadButtonLabel(0), icon = "fas fa-plus", style = ButtonStyle.OUTLINESECONDARY)
            addButton.addCssClasses("w-100 text-start")
            uploadForm.buttons(primary = addButton)
            uploadButtonRef = addButton
            addButton.onClick {
                uploadForm.submit(addButton) {
                    val nativeFile = selectedNativeFile() ?: return@submit
                    onUploadFile(nativeFile)
                    fileField.reset()
                }
            }
            uploadCountLine =
                group.div(conferenceMaxReachedMessage()) { addCssClasses("text-muted small") }.also { it.hide() }
        }

        // Datenschutzsatz 2, nur wenn ueberhaupt hochgeladen werden darf.
        if (uploadEligible) {
            group.div(
                tr(
                    "Eigene Bilder werden ohne Standort- und Kameradaten gespeichert und sind für andere " +
                        "Mitglieder und den Vorstand nicht sichtbar.",
                ),
            ) { addCssClasses("text-muted small") }
        }

        statusLine =
            group
                .div("") {
                    addCssClasses("text-muted small")
                }.also {
                    RawAttributes(it).set("role", "status")
                    it.hide()
                }
    }

    /**
     * Vier ausdrueckliche, vollstaendige msgids (0 bis [ConferenceBackgroundRules.MAX_PER_MEMBER],
     * fest 3) -- `tr()` kennt KEINE Platzhalter (Audit-Befund N4, Umsetzungsplan Abschnitt 5.4). Eine
     * fuenfte, generische msgid mit `%1`-Ersetzung waere hier falsch: sie wuerde bei einem
     * Sprachwechsel zur Laufzeit nie neu aufgeloest (siehe Datei-KDoc "sichtbare Beschriftungen").
     */
    private fun conferenceUploadButtonLabel(count: Int): String =
        when (count) {
            0 -> tr("Eigenes Bild hinzufügen (0 von 3)")
            1 -> tr("Eigenes Bild hinzufügen (1 von 3)")
            2 -> tr("Eigenes Bild hinzufügen (2 von 3)")
            else -> tr("Eigenes Bild hinzufügen (3 von 3)")
        }

    private fun conferenceMaxReachedMessage(): String = tr("Bitte entfernen Sie zuerst ein eigenes Bild.")

    private fun buildBuiltInTile(
        grid: Div,
        choice: ConferenceBackgroundChoice.BuiltIn,
    ): BackgroundTile {
        val root = grid.div { addCssClasses("lapis-conference-background-tile") }
        val attributes = RawAttributes(root)
        attributes.set("role", "radio")
        attributes.set("aria-label", conferenceBackgroundEffectLabel(choice.effect))
        attributes.set("aria-checked", "false")
        attributes.set("tabindex", "-1")

        val preview = root.div { addCssClasses("lapis-conference-background-preview") }
        val imagePath = conferenceBackgroundImagePath(choice)
        when {
            imagePath != null -> preview.image(imagePath, "")
            choice.effect == ConferenceBackgroundEffect.OFF -> {
                preview.addCssClasses("lapis-conference-background-preview-off")
                preview.icon("fas fa-ban")
            }
            choice.effect == ConferenceBackgroundEffect.BLUR_LIGHT ->
                preview.addCssClasses("lapis-conference-background-preview-blur-light")
            else -> preview.addCssClasses("lapis-conference-background-preview-blur-strong")
        }
        // Kare: Farbe allein faellt bei Farbfehlsichtigkeit aus -- Haekchen ZUSAETZLICH zum Akzentrahmen.
        val check = root.icon("fas fa-check") { addCssClasses("lapis-conference-background-check") }
        check.hide()
        // Norman-Ruling K5: Bild UND Wort. `tr(...)`, damit ein Sprachwechsel zur Laufzeit greift (N4).
        root.div(conferenceBackgroundEffectLabelTr(choice.effect)) { addCssClasses("small") }

        root.onClick { if (!applying) onSelect(choice) }
        root.addAfterInsertHook { vnode ->
            (vnode.elm as? HTMLElement)?.addEventListener("keydown", { event -> onTileKey(choice, event as? KeyboardEvent) })
        }
        return BackgroundTile(choice, root, check, attributes)
    }

    /**
     * V1.9.4 -- eine eigene Kachel: `div[role=none]`-Wrapper (F4) mit der `role=radio`-Kachel und dem
     * ×-Entfernen-Knopf als Geschwister. Der ×-Knopf liegt NICHT in [tiles] (keine Pfeiltasten-Teilnahme).
     */
    private fun buildCustomTile(
        grid: Div,
        image: ConferenceBackgroundImageDto,
        /** 1-based position AMONG THE MEMBER'S OWN images (never the absolute tile index, which would wrongly count the nine built-ins too). */
        ownIndex: Int,
    ): CustomTileEntry {
        val choice = ConferenceBackgroundChoice.Custom(image.id)
        val wrapper = grid.div { addCssClasses("lapis-conference-background-tile-wrap") }
        RawAttributes(wrapper).set("role", "none")

        val root = wrapper.div { addCssClasses("lapis-conference-background-tile") }
        val attributes = RawAttributes(root)
        attributes.set("role", "radio")
        attributes.set("aria-label", gettext("Eigenes Bild %1 von %2", ownIndex, ConferenceBackgroundRules.MAX_PER_MEMBER))
        attributes.set("aria-checked", "false")
        attributes.set("tabindex", "-1")

        val preview = root.div { addCssClasses("lapis-conference-background-preview") }
        conferenceBackgroundThumbPath(image.id)?.let { thumbPath -> preview.image(thumbPath, "") }
        val check = root.icon("fas fa-check") { addCssClasses("lapis-conference-background-check") }
        check.hide()
        root.div(tr("Eigenes Bild")) { addCssClasses("small") }

        root.onClick { if (!applying) onSelect(choice) }
        root.addAfterInsertHook { vnode ->
            (vnode.elm as? HTMLElement)?.addEventListener("keydown", { event -> onTileKey(choice, event as? KeyboardEvent) })
        }

        // × oben links -- oben rechts ist dauerhaft der Auswahl-Haken (Raskin: stabile Positionen).
        val removeButton =
            wrapper.button("", icon = "fas fa-xmark", style = ButtonStyle.OUTLINEDANGER) {
                addCssClasses("lapis-conference-background-remove")
            }
        RawAttributes(removeButton).set("aria-label", gettext("Eigenes Bild %1 entfernen", ownIndex))
        removeButton.onClick {
            confirmDialog(
                title = tr("Eigenes Bild entfernen?"),
                message = tr("Dieses eigene Hintergrundbild wird endgültig entfernt."),
                confirmLabel = tr("Entfernen"),
                onConfirm = { onDeleteCustom(image.id) },
            )
        }

        val tile = BackgroundTile(choice, root, check, attributes)
        return CustomTileEntry(imageId = image.id, wrapper = wrapper, tile = tile, removeButton = removeButton)
    }

    /**
     * V1.9.4 -- baut die eigenen Kacheln (hinter den neun eingebauten, in Hochlade-/Server-Reihenfolge)
     * neu auf. Alte Wrapper werden zuerst aus dem Raster entfernt (Stolperfalle S13: `addAfterInsertHook`
     * feuert nur einmal pro Element-Instanz, ein Neuaufbau braucht neue Instanzen).
     *
     * Ein Aufruf waehrend die Gruppe bereits SICHTBAR ist (Nutzer hat "Mehr" -> "Hintergrund" schon
     * aufgeklappt, BEVOR `listMine()` zurueckkam, oder Upload/Loeschen laufen -- beides passiert immer
     * bei geoeffnetem Panel) rendert die neuen Kacheln sofort ins DOM, kein zusaetzlicher Patch-Zyklus
     * noetig (`ConferenceBackgroundSectionDomTest`, DOM-Sonde des Review-Befunds).
     *
     * **Ende jedes Aufbaus**: [applyTileSelection] wird mit dem zuletzt von [render] gesehenen Zustand
     * ([lastState]) SOFORT erneut angewendet (Review-Befund, MAJOR) -- die frisch gebauten eigenen
     * Kacheln starten mit `aria-checked=false`/verstecktem Haekchen/`tabindex=-1`, und ein Neuaufbau
     * loest selbst keinen `onStateChanged`-Aufruf des Controllers aus. Ohne diesen Nachzug verlor eine
     * bereits ausgewaehlte ANDERE Kachel (z. B. eigenes Bild A) ihr Haekchen, sobald irgendein anderes
     * eigenes Bild (B) geloescht wurde, und das Raster hatte kein `tabindex=0`-Element mehr fuer die
     * Tastatur -- ein unsichtbarer Modus (Tesler-Regel).
     */
    fun setCustomImages(images: List<ConferenceBackgroundImageDto>) {
        if (!supported) return
        val builtGrid = grid ?: return
        customEntries.forEach { entry ->
            tiles.remove(entry.tile)
            builtGrid.remove(entry.wrapper)
        }
        customEntries.clear()
        images.forEachIndexed { index, image ->
            val entry = buildCustomTile(grid = builtGrid, image = image, ownIndex = index + 1)
            customEntries += entry
            tiles += entry.tile
        }
        uploadButtonRef?.let { button ->
            button.text = conferenceUploadButtonLabel(images.size)
            button.disabled = images.size >= ConferenceBackgroundRules.MAX_PER_MEMBER
        }
        if (images.size >= ConferenceBackgroundRules.MAX_PER_MEMBER) {
            uploadCountLine?.show()
        } else {
            uploadCountLine?.hide()
        }
        lastState?.let { state -> applyTileSelection(conferenceBackgroundDisplayedChoice(state)) }
    }

    fun setUploadInProgress(inProgress: Boolean) {
        uploadButtonRef?.let { button ->
            button.disabled = inProgress || customEntries.size >= ConferenceBackgroundRules.MAX_PER_MEMBER
            button.text = if (inProgress) tr("Wird hochgeladen …") else conferenceUploadButtonLabel(customEntries.size)
        }
    }

    /** Feste, uebersetzte Fehlertexte -- nie eine rohe Server-Antwort (Datei-KDoc "statusLine"). */
    fun showStatus(text: String?) {
        val line = statusLine ?: return
        if (text == null) {
            line.content = ""
            line.hide()
        } else {
            line.content = text
            line.show()
        }
    }

    private fun onTileKey(
        choice: ConferenceBackgroundChoice,
        event: KeyboardEvent?,
    ) {
        if (event == null) return
        val index = tiles.indexOfFirst { it.choice == choice }
        when (event.key) {
            "Enter", " " -> {
                event.preventDefault()
                if (!applying) onSelect(choice)
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
        // Pfeiltasten wandern nur (kein Auswaehlen -- ein Wechsel laedt ggf. WASM), zyklisch, ueber ALLE
        // Kacheln (neun eingebaute PLUS die eigenen, V1.9.4).
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
        lastState = state
        val displayed = conferenceBackgroundDisplayedChoice(state)
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
        applyTileSelection(displayed)
        val loading = loadingLine
        if (loading != null) {
            if (state.phase == ConferenceBackgroundPhase.APPLYING) loading.show() else loading.hide()
        }
        applyToggleAria()
    }

    /**
     * Der Haekchen-/`aria-checked`-/Roving-`tabindex`-Teil von [render], extrahiert (Review-Befund), damit
     * [setCustomImages] ihn nach einem Neuaufbau der eigenen Kacheln SOFORT erneut anwenden kann, statt auf
     * den naechsten `onStateChanged`-Aufruf des Controllers zu warten, der u. U. nie kommt (siehe [lastState]
     * KDoc).
     */
    private fun applyTileSelection(displayed: ConferenceBackgroundChoice) {
        var anySelected = false
        tiles.forEach { tile ->
            val selected = tile.choice == displayed
            if (selected) anySelected = true
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
        // Review-Befund (MINOR, a11y): `displayed` can be a Custom(X) whose tile is not (yet) in the
        // grid -- e.g. listMine() failed at join (ConferenceScreen: list == null, setCustomImages is
        // never called), leaving only the 9 built-in tiles while localStorage restored a Custom(X)
        // choice. With nothing matching, every tile above got tabindex=-1 and the radiogroup would
        // have NO tab stop at all -- ARIA APG radiogroup requires the first radio to stay tabbable
        // when nothing is checked. Same gap opens briefly between setCustomImages and
        // onCustomImageDeleted when the currently selected image is deleted.
        if (!anySelected) {
            tiles.firstOrNull()?.attributes?.set("tabindex", "0")
        }
    }
}

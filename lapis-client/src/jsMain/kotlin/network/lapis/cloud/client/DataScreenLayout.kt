package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.panel.HPanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px

/**
 * Gemeinsames Layout-Vokabular fuer **datendichte Tabellen-Screens** -- Ergebnis der
 * Design-Team-Sitzung vom 2026-09-18 (siehe Vault, root `CLAUDE.md` "UI/UX-Design-Team").
 *
 * ## Warum ueberhaupt
 *
 * Jeder Screen hatte seine eigene, hart kodierte Breite auf dem Root-`vPanel`
 * (`width = 720/800/860/900/960.px` + `mx-auto`). Zwei Folgen:
 *
 * 1. **`width` statt `maxWidth` ist nicht responsiv.** Ein fester `width`-Wert bleibt auch dann
 *    stehen, wenn das Viewport schmaler ist -- auf 375 px erzwingt `width = 960.px` horizontales
 *    Scrollen der gesamten Seite. `maxWidth` deckelt nur nach oben und laesst den Inhalt darunter
 *    frei schrumpfen.
 * 2. **Auf grossen Schirmen blieb rechts toter Raum**, obwohl genau diese Screens (Kontenplan,
 *    Mahnwesen, SEPA-Laeufe) breite Tabellen mit sechs bis acht Spalten tragen.
 *
 * ## Warum 1440 px und nicht "unbegrenzt"
 *
 * Unbegrenzte Breite bricht die Lesbarkeit der Textspalten (Kontoname, Betreff, Grund) auf einem
 * 27"-Schirm -- die Zeilenlaenge wird zur Augenbewegung. 1440 px ist der Kompromiss, den das
 * Design-Team gegen Ives "nimm den ganzen Schirm" gesetzt hat: breit genug, dass auf einem
 * 1440p-/1080p-Laptop kein toter Raum mehr entsteht, schmal genug, dass die Tabelle auf einem
 * Ultrawide nicht zum Horizont laeuft.
 *
 * ## Geltungsbereich -- bewusst NICHT app-weit
 *
 * Diese Helfer gehoeren ausschliesslich auf Screens mit **breiten Datentabellen**. Formular- und
 * Karten-Screens (`LoginScreen`, `RegistrationScreen`, `FriendRegistrationScreen`,
 * `VerifyEmailDeepLinkScreen`, `PasswordResetDeepLinkScreen`, `ConferenceScreen`) sowie die
 * Governance-Karten-Screens (Gremien, Sitzungen, Antraege, Events) behalten ihre schmale,
 * lesefreundliche Spalte -- deren Grosszuegigkeit ist fuer ihre Nutzung richtig und wurde in der
 * Sitzung ausdruecklich bestaetigt.
 *
 * **`CostCentersScreen`/`DonorsScreen`: Nachtrag Design-Team-Sitzung 2026-09-18 (Nachmittag).**
 * Beide standen am Vormittag noch auf der Karten-Ausnahmeliste ("Aktion nicht im dichten Raster").
 * Steve Jobs' abschliessendes Review (Punkt 2) hat diese Entscheidung revidiert: Tesler gewinnt
 * gegen Rams, weil auf dem Kostenstellen-Screen dieselben Objekte oben als Karten und im Bericht
 * darunter bereits als Tabellenzeile erscheinen -- zwei Darstellungsgrammatiken fuer ein Objekt auf
 * einem Screen war der eigentliche Defekt, nicht die Kartenform selbst. Beide Screens nutzen jetzt
 * [dataScreenRoot] + eine echte Tabelle -- analog zu `LedgerScreen.kt`s Kontenplan-Tabelle.
 *
 * ## Gegenstueck auf der Tabelle selbst: `ResponsiveType.RESPONSIVE`
 *
 * Solange das Root-Panel eine feste Breite von 900 px hatte, scrollte auf einem 375-px-Telefon die
 * GANZE Seite horizontal -- Kopfzeile und Navigation inklusive. Mit der jetzt frei schrumpfenden
 * Breite waere eine achtspaltige Tabelle stattdessen aus ihrem Container herausgelaufen. Deshalb
 * gehoert zu jedem Screen, der auf [dataScreenRoot] umgestellt wird, zwingend
 * `responsiveType = ResponsiveType.RESPONSIVE` auf seinen breiten Tabellen: Bootstrap legt die
 * Tabelle dann in einen eigenen, horizontal scrollbaren Rahmen -- gescrollt wird die Tabelle, nicht
 * die Seite. Das ist der eigentliche Grund, warum die Breitenfreigabe bei 375 px nicht bricht.
 */
internal const val DATA_SCREEN_MAX_WIDTH_PX = 1440

/**
 * Root-Panel eines datendichten Tabellen-Screens.
 *
 * `w-100` + `mx-auto` + [DATA_SCREEN_MAX_WIDTH_PX] als `maxWidth`: nimmt bis 1440 px die volle
 * verfuegbare Breite, zentriert darueber. `px-3` gibt die 16-px-Seitenrinne, damit der Inhalt auf
 * einem 375-px-Telefon nicht am Displayrand klebt -- ohne diese Rinne sass die Tabelle vorher
 * buendig auf Kante, sobald die feste Breite unterschritten wurde.
 */
fun Container.dataScreenRoot(spacing: Int = 14): VPanel =
    vPanel(spacing = spacing) {
        addCssClasses("mx-auto w-100 px-3")
        maxWidth = DATA_SCREEN_MAX_WIDTH_PX.px
        marginTop = 24.px
    }

/**
 * Icon-Knopf fuer die Aktionsspalte einer Datentabelle.
 *
 * ## Warum Icon statt Text
 *
 * Mehrere Volltext-Knoepfe pro Zeile ("Details anzeigen", "Deaktivieren") stapeln sich in einer
 * schmalen Zelle untereinander und blaehen jede Zeile auf ~60 px auf. Bei 40 Konten ist das eine
 * halbe Bildschirmhoehe reiner Knopfrand. Nebeneinanderliegende Icon-Knoepfe halten die Zeile bei
 * einer Zeilenhoehe.
 *
 * ## Warum trotzdem nicht "Icon pur"
 *
 * Don Normans Einwand in der Sitzung -- Zielgruppe ist teils der Schatzmeister, der sich einmal im
 * Monat einloggt -- ist der Grund, warum [tooltip] **Pflichtparameter** ist und sowohl als
 * `title` (Hover-Tooltip) als auch als `aria-label` (Screenreader; ein Knopf ohne Textinhalt hat
 * sonst keinen zugaenglichen Namen) gesetzt wird. Ein Icon ohne jeden Kontext waere die
 * Verdichtung, die die Erkennbarkeit kaputt macht.
 *
 * `title` wird ueber KVisions eigene `Widget.title`-Property gesetzt, nicht per rohem
 * DOM-Schreibzugriff -- siehe die KDoc-Warnung in `MemberAdministrationScreen.kt` zum
 * `###KvI18nS###`-Marker-Leak, wenn uebersetzte Strings am KVision-Rendering vorbei gesetzt werden.
 *
 * `btn-sm` per [addCssClass] statt ueber ein KVision-`size`-Enum: eine einzelne, garantiert
 * vorhandene Bootstrap-Utility-Klasse (siehe `CssClasses.kt` zum Unterschied Einzelklasse vs.
 * Mehrfachklassen-String).
 */
fun Container.tableActionButton(
    icon: String,
    tooltip: String,
    style: ButtonStyle = ButtonStyle.OUTLINESECONDARY,
): Button {
    val actionButton = button("", icon = icon, style = style)
    actionButton.addCssClass("btn-sm")
    actionButton.tableActionTooltip(tooltip)
    return actionButton
}

/**
 * Setzt Tooltip **und** zugaenglichen Namen eines Icon-Knopfes in einem Schritt.
 *
 * Eigene Funktion (statt nur `title = ...`), weil mehrere Aufrufstellen den Tooltip nachtraeglich
 * ueberschreiben, sobald der Knopf deaktiviert wird ("DSGVO-geloescht", "Peer-Schutz ..."): dabei
 * muss `aria-label` zwingend mitwandern, sonst liest ein Screenreader weiter den alten Grund vor.
 */
fun Button.tableActionTooltip(tooltip: String) {
    title = tooltip
    // Welle V1.4.21: `aria-label` geht per `setAttribute` am KVision-Patch-Zyklus vorbei -- ein `tr(...)`-
    // Ergebnis trüge dort den `###KvI18nS###`-Marker in den DOM (Screenreader: "###KvI18nS###Details
    // anzeigen"). `title` oben ist eine KVision-Property und wird korrekt aufgelöst. Das Entfernen
    // des Markers ist für bereits aufgelöste Strings (`gettext`) ein No-op, gilt also unbedingt --
    // Aufrufstellen dürfen weiter `tr(...)` übergeben. Der Tripwire `ClientTrAttributeLeakTest` sieht
    // diesen indirekten Fluss (Aufrufstelle -> Helfer -> setAttribute) nicht.
    setAttribute("aria-label", resolvedAttributeText(tooltip))
}

/** Marker, den KVisions `tr()` um jeden Text legt; nur der eigene Patch-Zyklus löst ihn auf. */
private const val KV_I18N_MARKER = "###KvI18nS###"

/**
 * Für jeden Text, der per rohem `setAttribute(...)` in den DOM geht: entfernt den `tr()`-Marker
 * (No-op für bereits aufgelöste Strings). Gleiche Idee wie `ConferenceScreen.resolvedA11yText`.
 */
internal fun resolvedAttributeText(text: String): String = text.removePrefix(KV_I18N_MARKER)

/**
 * Segmented Control (Bootstrap `btn-group btn-group-sm`, `role="group"`, je Knopf `aria-pressed`) für
 * eine kleine, gegenseitig ausschließende Auswahl -- Welle V1.4.21 (Offene Posten: Alle / Kreditoren /
 * Debitoren). [options] trägt bereits übersetzte Labels (sichtbarer Knopftext ist zugleich der
 * zugängliche Name); [ariaLabel] benennt die Gruppe und läuft über [resolvedAttributeText], darf
 * also `tr(...)` oder `gettext(...)` sein.
 */
fun <T> Container.segmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    ariaLabel: String,
    onSelect: (T) -> Unit,
): HPanel {
    val group =
        hPanel(spacing = 0) {
            addCssClasses("btn-group btn-group-sm")
            setAttribute("role", "group")
            setAttribute("aria-label", resolvedAttributeText(ariaLabel))
        }
    var current = selected
    val buttons = mutableListOf<Pair<T, Button>>()

    fun paint() {
        buttons.forEach { (value, segmentButton) ->
            val active = value == current
            segmentButton.style = if (active) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEPRIMARY
            segmentButton.setAttribute("aria-pressed", active.toString())
        }
    }
    options.forEach { (value, label) ->
        val segmentButton = group.button(label, style = ButtonStyle.OUTLINEPRIMARY)
        buttons.add(value to segmentButton)
        segmentButton.onClick {
            if (value != current) {
                current = value
                paint()
                onSelect(value)
            }
        }
    }
    paint()
    return group
}

/**
 * Container fuer mehrere [tableActionButton]s in derselben Tabellenzelle.
 *
 * `flex-nowrap` ist der eigentliche Punkt: mit dem vorherigen `flex-wrap` sind zwei Knoepfe in
 * einer schmalen Aktionsspalte untereinander gerutscht -- genau der Zeilen-Stapel, den diese Welle
 * beseitigt. Mit Icon-Knoepfen (~34 px) passen auch vier Aktionen nebeneinander.
 */
fun Container.tableActionGroup(): HPanel =
    hPanel(spacing = 4) {
        addCssClasses("flex-nowrap align-items-center")
    }

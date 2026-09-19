package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.navbar.Nav
import io.kvision.navbar.navLink
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * V1.4.16 Dark-Mode-Toggle -- Nutzer-Beschwerde 2026-09-15 ("Webseite nachts zu hell"). Zwei
 * States (hell/dunkel), kein dritter "System"-State: der EFFEKTIVE Anfangswert respektiert
 * `prefers-color-scheme`, solange der Nutzer nichts explizit gewählt hat (siehe [initialTheme]),
 * aber sobald er klickt, ist die Wahl fix -- kein Drei-Zustands-Zyklus, den man erst verstehen
 * muss (Raskin: die einfachste Bedienung, die die Anforderung noch erfüllt).
 *
 * `data-theme` UND `data-bs-theme` werden immer GEMEINSAM gesetzt: `data-theme` steuert die
 * eigenen `--lapis-*`-Variablen in `theme.css`, `data-bs-theme` ist Bootstraps EIGENER
 * Farbmodus-Schalter (5.3+, siehe `theme.css`-Kopfkommentar zur Bootstrap-Version) -- Tabellen,
 * Formulare, Modals, Cards etc. bekommen dadurch ihre dunklen Bootstrap-Standardfarben ohne dass
 * diese Datei jede einzelne Bootstrap-Komponente selbst neu einfärben müsste.
 *
 * `index.html` enthält denselben Lese-Pfad als reines Inline-Script (VOR dem großen Kotlin/JS-
 * Bundle), um einen Flash des falschen Themes beim Laden zu vermeiden -- siehe dessen eigener
 * Kommentar. Beide Stellen müssen bei einer Änderung des Storage-Keys/der Werte synchron bleiben.
 */
private const val THEME_STORAGE_KEY = "lapis-cloud-theme"
private const val THEME_LIGHT = "light"
private const val THEME_DARK = "dark"

private fun systemPrefersDark(): Boolean = window.matchMedia("(prefers-color-scheme: dark)").matches

private fun storedTheme(): String? = localStorage[THEME_STORAGE_KEY]?.takeIf { it == THEME_LIGHT || it == THEME_DARK }

/** Effektives Theme JETZT (gespeicherte Wahl, sonst System-Präferenz) -- gleiche Logik wie `index.html`s Inline-Script. */
private fun currentTheme(): String = storedTheme() ?: if (systemPrefersDark()) THEME_DARK else THEME_LIGHT

private fun applyTheme(theme: String) {
    document.documentElement?.setAttribute("data-theme", theme)
    document.documentElement?.setAttribute("data-bs-theme", theme)
}

/**
 * Erneut anwenden nach dem Kotlin/JS-Start (das Inline-Script in `index.html` hat es beim allerersten
 * Paint bereits gesetzt -- dieser Aufruf ist kein Duplikat-Risiko, nur eine Bestätigung/Auffrischung
 * für den Fall, dass zwischen Inline-Script und App-Start etwas den Wert zurückgesetzt hätte).
 */
fun applyStoredTheme() {
    applyTheme(currentTheme())
}

/**
 * Kompakter `fas fa-moon`/`fas fa-sun`-Umschalter, gleiche Formsprache wie der Sprachumschalter
 * daneben (`addLanguageSwitcher`) -- ein einzelner Link statt eines Dropdowns, weil es hier nur
 * zwei Zustände gibt, kein Auswahlmenü nötig ist. `navLink` statt `button`, weil `Nav` (anders als
 * `Navbar` selbst) keine `button()`-Extension hat -- `dataNavigo = false` + `url = "javascript:void(0)"`
 * ist dasselbe Muster wie beim "Abmelden"-Link/Sprachumschalter-Einträgen oben (rein lokaler
 * Klick-Handler, keine Route). Symbol zeigt den ZUSTAND, IN DEN ein Klick wechselt (Mond =
 * "wechsle zu dunkel", Sonne = "wechsle zu hell") -- gängige Konvention, keine Überraschung.
 */
fun addThemeToggle(rightNav: Nav) {
    val link =
        rightNav.navLink(
            "",
            url = "javascript:void(0)",
            icon = if (currentTheme() == THEME_DARK) "fas fa-sun" else "fas fa-moon",
            dataNavigo = false,
        )
    link.setAttribute("aria-label", gettext("Dunklen/hellen Modus umschalten"))
    link.onClick {
        val next = if (currentTheme() == THEME_DARK) THEME_LIGHT else THEME_DARK
        localStorage[THEME_STORAGE_KEY] = next
        applyTheme(next)
        link.icon = if (next == THEME_DARK) "fas fa-sun" else "fas fa-moon"
    }
}

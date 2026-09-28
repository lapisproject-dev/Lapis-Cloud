package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.i18n.I18n
import io.kvision.i18n.tr
import kotlinx.browser.localStorage
import org.w3c.dom.get
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reported bug (2026-09-28): "switching the UI language works in the current view, but navigating
 * to a different view/screen resets the language back to German." Suspected cause per the roadmap
 * note: the choice isn't persisted to `localStorage`, or `I18n.language` gets re-initialized on
 * screen mount instead of being read from a persisted value.
 *
 * Root-cause investigation found the persistence mechanism ALREADY in place and already mirroring
 * `ThemeToggle.kt`'s `THEME_STORAGE_KEY`/`storedTheme()`/`currentTheme()` idiom exactly:
 * - [setLanguage] writes `code` to `localStorage[LANGUAGE_STORAGE_KEY]` before setting
 *   [io.kvision.i18n.I18n.language] -- same "write, then apply" order as `addThemeToggle`'s
 *   `onClick` in `ThemeToggle.kt`.
 * - `App.kt`'s `main()` reads it back via [initialLanguage] and sets `I18n.language` BEFORE
 *   `startApplication` runs -- the exact read-once-at-boot analogue of `applyStoredTheme()`
 *   (called once, first thing, in `App.start()`).
 * - `Routing.kt`'s `show()` (every `routing.kvOn(...)` handler) only ever calls
 *   `pageContainer.removeAll()` followed by `render(pageContainer)` -- it never touches `I18n` at
 *   all, so no screen mount can re-initialize the language to a default.
 *
 * These tests pin that down as an explicit regression guard against the reported symptom, rather
 * than re-verifying an assumption: [navigatingToADifferentScreen_doesNotResetTheLanguage]
 * reproduces the report's exact mechanics inside a real, mounted KVision [io.kvision.panel.Root] --
 * switch language while "screen A" is showing, then tear it down and mount "screen B" into the SAME
 * container via `removeAll()`, exactly like `Routing.kt`'s `show()` does on every navigation -- and
 * asserts the choice survives, both in memory and as what a fresh app boot would read back.
 *
 * `internal` visibility on [LANGUAGE_STORAGE_KEY]/[initialLanguage]/[setLanguage] in `App.kt` is the
 * test seam this file exercises (previously `private`, following the same seam-widening precedent as
 * `LanguageChange.kt`'s `requestLanguageChange` parameters).
 */
class LanguagePersistenceDomTest {
    @BeforeTest
    fun clearPersistedLanguage() {
        localStorage.removeItem(LANGUAGE_STORAGE_KEY)
    }

    @AfterTest
    fun resetLanguage() {
        localStorage.removeItem(LANGUAGE_STORAGE_KEY)
        I18n.language = "de"
    }

    @Test
    fun switchingLanguage_persistsToLocalStorage_underTheSameKeyIdiomAsTheTheme() {
        setLanguage("en")
        assertEquals("en", localStorage[LANGUAGE_STORAGE_KEY], "setLanguage must write to localStorage, same as ThemeToggle.kt's onClick")
        assertEquals("en", I18n.language)
    }

    @Test
    fun aFreshAppBoot_readsThePersistedChoiceBack_insteadOfFallingBackToGerman() {
        setLanguage("fr")
        // What `App.kt`'s `main()` calls before `startApplication` on every real page load.
        assertEquals("fr", initialLanguage())
    }

    @Test
    fun noPersistedChoiceYet_defaultsToGerman() {
        assertEquals("de", initialLanguage())
    }

    @Test
    fun navigatingToADifferentScreen_doesNotResetTheLanguageToGerman() {
        withMountedRoot("language-persistence-nav") { root, element ->
            // "Screen A" -- content mounted directly into the root, exactly like `Routing.kt`'s
            // `pageContainer` (itself a plain `SimplePanel`, and `Root` IS-A `SimplePanel`).
            root.div(tr("Mitgliederbereich"))
            assertEquals("Mitgliederbereich", element().textContent)

            // The real switch -- same call `addLanguageSwitcher`'s `onClick` makes in `App.kt`.
            setLanguage("en")
            assertEquals("en", I18n.language)
            assertEquals("en", localStorage[LANGUAGE_STORAGE_KEY])

            // "Navigate to a different view/screen": `Routing.kt`'s `show()` does exactly this on
            // every single route change -- `pageContainer.removeAll()` then renders the next
            // screen into the SAME container. It never reads or writes `I18n.language`.
            root.removeAll()
            root.div(tr("Beitragsübersicht"))

            assertEquals(
                "en",
                I18n.language,
                "the in-memory language must survive a screen/view change -- Routing.kt's screen " +
                    "swap never touches I18n.language",
            )
        }
        // And the persisted value a subsequent app boot would read is likewise untouched.
        assertEquals("en", initialLanguage(), "the persisted language must survive a screen/view change too")
    }
}

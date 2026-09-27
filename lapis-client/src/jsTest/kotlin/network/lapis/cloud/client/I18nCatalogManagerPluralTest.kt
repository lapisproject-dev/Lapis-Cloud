package network.lapis.cloud.client

import io.kvision.i18n.I18n
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * i18n-Restschuld (2026-09-27): end-to-end coverage of [I18nCatalogManager.ngettext] reading a
 * REAL plural catalog array -- the exact shape `gettext.js`'s own `po2json` wrapper produces for
 * a `msgid`/`msgid_plural` `.po` entry (`catalog[msgid] = [msgstr[0], msgstr[1], ...]`, verified
 * empirically against that tool for this test file, see the commit this file was added in).
 * Round 4's tests below (through [untrustedArgument_isSanitizedOnThePluralArrayPathTooNotOnlyOnGettext])
 * used only synthetic messages -- no application call site produced this shape at the time. Round
 * 5 changed that: `SepaBatchesScreen.kt` now has three real call sites (through the top-level
 * `ngettext(...)` wrapper in `I18nCatalogManager.kt`, see its own KDoc), exercised below with the
 * real msgid/msgid_plural pairs (see [realPluralPairs]).
 *
 * Resets `I18n.manager`/`I18n.language` back to [TestI18nSetup]'s fixed baseline before AND
 * after every test, mirroring [AppStateTest]'s teardown discipline for this module's other
 * global/singleton state.
 */
class I18nCatalogManagerPluralTest {
    @BeforeTest
    fun resetI18n() = resetToBaseline()

    @AfterTest
    fun tearDown() = resetToBaseline()

    private fun resetToBaseline() {
        I18n.manager = I18nCatalogManager(emptyMap())
        I18n.language = "de"
    }

    private fun installCatalog(
        language: String,
        entries: Map<String, dynamic>,
    ) {
        val catalog = js("({})")
        entries.forEach { (key, value) -> catalog[key] = value }
        I18n.language = language
        I18n.manager = I18nCatalogManager(mapOf(language to catalog))
    }

    @Test
    fun polish_selectsOneFewManyByForm() {
        val forms = js("['%1 plik', '%1 pliki', '%1 plików']")
        installCatalog("pl", mapOf("%1 file" to forms))
        assertEquals("1 plik", I18n.manager.ngettext("%1 file", "%1 files", 1, 1))
        assertEquals("2 pliki", I18n.manager.ngettext("%1 file", "%1 files", 2, 2))
        assertEquals("5 plików", I18n.manager.ngettext("%1 file", "%1 files", 5, 5))
        // the x12-x14 exception: falls in "many", not "few", exactly like PluralRulesTest.
        assertEquals("12 plików", I18n.manager.ngettext("%1 file", "%1 files", 12, 12))
    }

    @Test
    fun russian_elevenIsManyNotOne() {
        val forms = js("['%1 файл', '%1 файла', '%1 файлов']")
        installCatalog("ru", mapOf("%1 file" to forms))
        assertEquals("1 файл", I18n.manager.ngettext("%1 file", "%1 files", 1, 1))
        assertEquals("11 файлов", I18n.manager.ngettext("%1 file", "%1 files", 11, 11))
        assertEquals("21 файл", I18n.manager.ngettext("%1 file", "%1 files", 21, 21))
    }

    @Test
    fun englishTwoForm_stillWorksAsAPluralArray() {
        val forms = js("['%1 file', '%1 files']")
        installCatalog("en", mapOf("%1 file" to forms))
        assertEquals("1 file", I18n.manager.ngettext("%1 file", "%1 files", 1, 1))
        assertEquals("0 files", I18n.manager.ngettext("%1 file", "%1 files", 0, 0))
        assertEquals("3 files", I18n.manager.ngettext("%1 file", "%1 files", 3, 3))
    }

    @Test
    fun noPluralArrayEntry_fallsBackToTheBareSingularPluralKeyBinary() {
        // German is never catalog-backed at all (see I18nCatalogManager's class KDoc) --
        // ngettext must still work by falling back to whichever of singularKey/pluralKey
        // value selects, unsubstituted-catalog, exactly like gettext() does for German.
        installCatalog("de", emptyMap())
        assertEquals("1 Datei", I18n.manager.ngettext("%1 Datei", "%1 Dateien", 1, 1))
        assertEquals("3 Dateien", I18n.manager.ngettext("%1 Datei", "%1 Dateien", 3, 3))
    }

    @Test
    fun catalogHasOnlyAFlatStringForTheKey_fallsBackRatherThanCrashing() {
        // A translator filled msgstr without ever adding msgid_plural -- catalog[key] is a
        // plain string, not an array. ngettext must not throw; it falls back to the bare binary.
        installCatalog("en", mapOf("%1 Datei" to "translated singular, no plural array"))
        assertEquals("translated singular, no plural array", I18n.manager.ngettext("%1 Datei", "%1 Dateien", 1, 1))
        // value != 1 and no plural array present: falls back to pluralKey, looked up as its own
        // (untranslated, since only the singular key has a catalog entry) key, with %1 still substituted.
        assertEquals("3 Dateien", I18n.manager.ngettext("%1 Datei", "%1 Dateien", 3, 3))
    }

    @Test
    fun untrustedArgument_isSanitizedOnThePluralArrayPathTooNotOnlyOnGettext() {
        val forms = js("['%1', '%1']")
        installCatalog("en", mapOf("count" to forms))
        val forged = KV_I18N_MARKER + "forged"
        val result = I18n.manager.ngettext("count", "count", 2, forged)
        assertEquals(sanitizeUntrustedI18nText(forged), result)
    }

    // ── i18n-Restschuld, round 5 (2026-09-27): the real SepaBatchesScreen.kt msgid/msgid_plural pairs, across all
    // seven catalog languages -- unlike the synthetic "%1 file"/"%1 files" pairs above, these use the ACTUAL German
    // source strings the app now calls ngettext() with (this codebase's own top-level wrapper in
    // I18nCatalogManager.kt, which delegates to I18n.ngettext()/I18nManager.ngettext(), called directly below like
    // every other test in this file), so a msgid typo between the call site and a catalog would show up here too,
    // not only in PluralMessagesCatalogTest's server-side catalog scan. Translations installed below are
    // synthetic-but-recognizable ("ONE"/"FEW"/"MANY"/"OTHER"), never the real catalog text -- this test is about
    // ngettext()'s form SELECTION per language, not translation content (PluralMessagesCatalogTest already checks
    // the real catalog files have the right shape).

    private val realPluralPairs =
        listOf(
            "Lauf anlegen (%1 Position, %2)" to "Lauf anlegen (%1 Positionen, %2)",
            "%1 Position konnte nicht gebucht werden -- erneut versuchen mit \"Abrechnen\"." to
                "%1 Positionen konnten nicht gebucht werden -- erneut versuchen mit \"Abrechnen\".",
            "%1 Position konnte nicht gebucht werden." to "%1 Positionen konnten nicht gebucht werden.",
        )

    @Test
    fun realSepaBatchesMessages_twoFormLanguages_selectSingularAtOneAndPluralOtherwise() {
        for (lang in listOf("en", "es", "fr", "it", "nl")) {
            // French is the one two-form language in this list where 0 ALSO takes the singular form
            // (the classic GNU gettext rule -- see PluralRules.kt's own KDoc, "0 and 1 both take the
            // singular form"); every other two-form language here treats 0 as plural, same as any
            // count other than exactly 1.
            val zeroForm = if (lang == "fr") "ONE" else "OTHER"
            realPluralPairs.forEach { (singular, plural) ->
                val forms = js("['ONE', 'OTHER']")
                installCatalog(lang, mapOf(singular to forms))
                assertEquals("ONE", I18n.manager.ngettext(singular, plural, 1, 1), "$lang/$singular @ 1")
                assertEquals(zeroForm, I18n.manager.ngettext(singular, plural, 0, 0), "$lang/$singular @ 0")
                assertEquals("OTHER", I18n.manager.ngettext(singular, plural, 3, 3), "$lang/$singular @ 3")
            }
        }
    }

    @Test
    fun realSepaBatchesMessages_polishAndRussian_selectOneFewMany() {
        for (lang in listOf("pl", "ru")) {
            realPluralPairs.forEach { (singular, plural) ->
                val forms = js("['ONE', 'FEW', 'MANY']")
                installCatalog(lang, mapOf(singular to forms))
                assertEquals("ONE", I18n.manager.ngettext(singular, plural, 1, 1), "$lang/$singular @ 1")
                assertEquals("FEW", I18n.manager.ngettext(singular, plural, 2, 2), "$lang/$singular @ 2")
                assertEquals("MANY", I18n.manager.ngettext(singular, plural, 5, 5), "$lang/$singular @ 5")
            }
        }
    }

    @Test
    fun realSepaBatchesMessages_germanNeverCatalogBacked_fallsBackToTheBareSingularPluralBinary() {
        installCatalog("de", emptyMap())
        realPluralPairs.forEach { (singular, plural) ->
            assertEquals(singular.replace("%1", "1"), I18n.manager.ngettext(singular, plural, 1, 1), singular)
            assertEquals(plural.replace("%1", "3"), I18n.manager.ngettext(singular, plural, 3, 3), plural)
        }
    }
}

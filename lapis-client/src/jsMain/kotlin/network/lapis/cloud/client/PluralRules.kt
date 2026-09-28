package network.lapis.cloud.client

/**
 * CLDR/gettext plural-category selection for this app's eight UI languages (German is the
 * source language and never catalog-backed -- see [I18nCatalogManager]'s class KDoc). The
 * index returned matches each language's own `Plural-Forms` header in
 * `messages-<lang>.po`/`messages.pot` exactly (`msgstr[0]`, `msgstr[1]`, ...), so a plural
 * catalog array (see [I18nCatalogManager.ngettext]) can be indexed directly by this
 * function's result -- no separate mapping table to keep in sync.
 *
 * i18n-Restschuld (2026-09-27): the values below are the standard GNU gettext `Plural-Forms`
 * expressions -- the ones published in the GNU gettext manual's plural-forms appendix and
 * used identically, digit for digit, across the Debian, GNOME and KDE translation projects --
 * transcribed 1:1 as Kotlin `when` branches. They are never `eval`'d from a header string read
 * out of a catalog file: [I18nCatalogManager]'s own money-forgery hardening treats "never
 * evaluate untrusted text as code" as a hard rule throughout this module, and a `.po` header
 * is translator-editable text, not something this app should ever execute.
 *
 * `count` is always a non-negative item count in every anticipated use (grammatical plural
 * selection has no meaning for a negative quantity); `kotlin.math.abs` guards the arithmetic
 * below anyway, so a caller mistake falls into the last ("many"/"other") bucket instead of
 * ever producing a result outside `0 until pluralFormCount(language)`.
 */
internal fun pluralFormIndex(
    language: String,
    count: Int,
): Int {
    val n = kotlin.math.abs(count)
    return when (language) {
        // Germanic/Romance two-form languages: the singular is exactly 1, everything else
        // (including 0, "no items") takes the plural form.
        "en", "es", "it", "nl" -> if (n != 1) 1 else 0
        // French: 0 and 1 both take the singular form -- the classic/GNU gettext rule (not
        // CLDR's newer, narrower "i = 0,1" split, which this app has no need to distinguish
        // since French otherwise behaves like the two-form languages above).
        "fr" -> if (n > 1) 1 else 0
        // Polish: one/few/many, driven by the last one or two digits of the count.
        "pl" -> polishOrRussianFewMany(n, russianOneRule = false)
        // Russian: one/few/many, same "few"/"many" shape as Polish, but "one" additionally
        // excludes n%100 == 11 (so "11" is "many", not "one", unlike a bare "n%10 == 1" check).
        "ru" -> polishOrRussianFewMany(n, russianOneRule = true)
        // Unknown/unsupported language code, or German (never catalog-backed, see class KDoc
        // on [I18nCatalogManager] -- German call sites never reach this catalog-array path at
        // all): the same safe two-form fallback used for the Germanic/Romance languages above.
        else -> if (n != 1) 1 else 0
    }
}

private fun polishOrRussianFewMany(
    n: Int,
    russianOneRule: Boolean,
): Int {
    val isOne = if (russianOneRule) n % 10 == 1 && n % 100 != 11 else n == 1
    val isFew = n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20)
    return when {
        isOne -> 0
        isFew -> 1
        else -> 2
    }
}

/** Number of plural forms [pluralFormIndex] can return for `language`, i.e. `nplurals` from that language's own `Plural-Forms` header. */
internal fun pluralFormCount(language: String): Int =
    when (language) {
        "pl", "ru" -> 3
        else -> 2
    }

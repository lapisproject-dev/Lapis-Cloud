package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.CountryCodes

/**
 * Welle V1.9.121 -- country display names and their sort order for the country and nationality selection lists.
 *
 * The browser knows the names (`Intl.DisplayNames`, region type) and the locale-aware sort order (`Intl.Collator`). Unlike dates and
 * money (see `DateTime.kt` / `Money.kt`, which avoid `Intl` because the ICU output differs between browsers), a country NAME is
 * exactly what the user wants to read in their own language -- but the exact spelling can differ a little between browsers. Nothing is
 * stored under these names; the stored value is always the code. Everything that touches `Intl` sits behind [CountryNameProvider] so
 * tests can swap in a deterministic fake ([countryNameProvider]); a missing name or a thrown error falls back to the code, never crashes.
 *
 * The two internal nationality values never go to `Intl`: [CountryCodes.STATELESS] and [CountryCodes.UNCLEAR] come from the i18n catalogue.
 */
internal interface CountryNameProvider {
    /** The name of [code] in [lang] (a BCP 47 language such as `de`); the code itself when the browser does not know it. */
    fun name(
        code: String,
        lang: String,
    ): String

    /** The English name of [code] (searched in addition to the UI-language name). */
    fun englishName(code: String): String

    /** Alphabetical order of country NAMES in [lang]. */
    fun comparator(lang: String): Comparator<String>
}

internal object BrowserCountryNameProvider : CountryNameProvider {
    private val displayNames = HashMap<String, dynamic>()
    private val collators = HashMap<String, dynamic>()

    private fun construct(
        constructor: dynamic,
        vararg args: dynamic,
    ): dynamic = js("Reflect").construct(constructor, args)

    private fun displayNamesFor(lang: String): dynamic =
        displayNames.getOrPut(lang) {
            val options: dynamic = js("({})")
            options.type = "region"
            construct(js("Intl.DisplayNames"), arrayOf(lang), options)
        }

    override fun name(
        code: String,
        lang: String,
    ): String {
        val fallback = if (code == CountryCodes.KOSOVO) gettext("Kosovo") else code
        return try {
            val resolved = displayNamesFor(lang).of(code) as? String
            if (resolved.isNullOrBlank() || resolved == code) fallback else resolved
        } catch (e: Throwable) {
            fallback
        }
    }

    override fun englishName(code: String): String = name(code, "en")

    override fun comparator(lang: String): Comparator<String> {
        val collator =
            try {
                collators.getOrPut(lang) { construct(js("Intl.Collator"), arrayOf(lang)) }
            } catch (e: Throwable) {
                null
            }
        return Comparator { a, b ->
            if (collator == null) a.compareTo(b) else (collator.compare(a, b) as Number).toInt()
        }
    }
}

/** Swappable for tests. */
internal var countryNameProvider: CountryNameProvider = BrowserCountryNameProvider

/** The name shown for [code] in the UI language [lang]: `Intl` for countries, the catalogue for `XS` / `ZZ`. */
internal fun countryDisplayName(
    code: String,
    lang: String,
): String =
    when (code) {
        CountryCodes.STATELESS -> gettext("staatenlos")
        CountryCodes.UNCLEAR -> gettext("ungeklärt")
        else -> countryNameProvider.name(code, lang)
    }

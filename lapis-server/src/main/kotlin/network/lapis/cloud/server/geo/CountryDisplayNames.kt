package network.lapis.cloud.server.geo

import network.lapis.cloud.shared.domain.CountryCodes
import java.util.Locale

/**
 * Welle V1.9.121 -- display names of the country codes in [CountryCodes] (letters, mailmerge, Art. 15 export). Letters are always
 * German. The names come from the JDK (CLDR) -- they can differ slightly between JDK versions; nothing is stored under them.
 *
 * `XS` / `ZZ` are internal and never come from [Locale]; `XK` is user-assigned and gets a fixed override if the JDK does not know it.
 */
object CountryDisplayNames {
    const val STATELESS_DE = "staatenlos"
    const val UNCLEAR_DE = "ungeklärt"

    private val overridesDe: Map<String, String> = mapOf(CountryCodes.KOSOVO to "Kosovo")

    /** German name of [code]; falls back to the code itself for an unknown value (never throws). */
    fun germanName(code: String): String = name(code = code, locale = Locale.GERMAN)

    fun name(
        code: String,
        locale: Locale,
    ): String {
        val upper = code.trim().uppercase()
        when (upper) {
            CountryCodes.STATELESS -> return STATELESS_DE
            CountryCodes.UNCLEAR -> return UNCLEAR_DE
        }
        if (locale.language == Locale.GERMAN.language) overridesDe[upper]?.let { return it }
        val name = Locale.of("", upper).getDisplayCountry(locale)
        return if (name.isBlank() || name == upper) {
            overridesDe[upper] ?: upper
        } else {
            name
        }
    }

    /** "Polen, Deutschland" style text for a nationality list (German names, sorted by the code order of the caller). */
    fun germanNationalityText(codes: List<String>): String = codes.joinToString(", ") { germanName(it) }
}

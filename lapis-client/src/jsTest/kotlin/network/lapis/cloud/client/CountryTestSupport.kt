package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.CountryCodes

/**
 * V1.9.121 -- a deterministic stand-in for the browser's country names (the real `Intl.DisplayNames` output differs a little between
 * browsers and versions, see `CountryNames.kt`): a handful of German names, every other code is its own name. Sorted plainly.
 */
internal object FakeCountryNameProvider : CountryNameProvider {
    private val german =
        mapOf(
            "DE" to "Deutschland",
            "PL" to "Polen",
            "AT" to "Österreich",
            "GE" to "Georgien",
            "FR" to "Frankreich",
            "CH" to "Schweiz",
            "XK" to "Kosovo",
        )
    private val english =
        mapOf(
            "DE" to "Germany",
            "PL" to "Poland",
            "AT" to "Austria",
            "GE" to "Georgia",
            "FR" to "France",
            "CH" to "Switzerland",
            "XK" to "Kosovo",
        )

    override fun name(
        code: String,
        lang: String,
    ): String = german[code] ?: code

    override fun englishName(code: String): String = english[code] ?: code

    override fun comparator(lang: String): Comparator<String> = Comparator { a, b -> a.compareTo(b) }
}

/** Runs [block] with the fake names installed and restores the real provider afterwards, whatever happens. */
internal suspend fun <T> withFakeCountryNames(block: suspend () -> T): T {
    val previous = countryNameProvider
    countryNameProvider = FakeCountryNameProvider
    try {
        return block()
    } finally {
        countryNameProvider = previous
    }
}

/** The number of selectable countries, for list-size assertions. */
internal val SELECTABLE_COUNTRY_COUNT: Int = CountryCodes.SELECTABLE.size

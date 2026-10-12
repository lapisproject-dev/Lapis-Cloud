package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.CountryCodes

/**
 * Welle V1.9.121 -- the country list of the member forms, built on [SearchableSelect] (no new widget): an ARIA combobox with the flag in
 * front of every name, searchable by the name in the UI language, the English name and the country code, accents ignored.
 *
 * Order: the pinned country (the organization's, from the session; Germany when unknown) first, a divider, then everything alphabetically
 * in the UI language (`Intl.Collator`). The nationality list ends with a second group "staatenlos" / "ungeklärt" (no flag, a spacer keeps
 * the names aligned). There is NO preselection: the empty entry "— bitte wählen —" is pinned on top.
 */
internal enum class CountryListMode { COUNTRY, NATIONALITY }

internal class CountryListOptions(
    /** `(code, name)` pairs; the entry with id `""` ("— bitte wählen —") is NOT included, the caller adds it. */
    val options: List<Pair<String, String>>,
    /** English name and code per code, searched but not shown. */
    val searchTexts: Map<String, String>,
    /** Codes after which a divider line is drawn. */
    val separatorAfter: Set<String>,
)

/** The country pinned on top: the organization's, else Germany. */
internal fun pinnedCountryCode(): String =
    AppState.session
        ?.organizationCountryCode
        ?.takeIf { CountryCodes.isCountry(it) }
        ?: "DE"

internal fun buildCountryListOptions(
    mode: CountryListMode,
    lang: String = I18n.language,
    pinned: String = pinnedCountryCode(),
    provider: CountryNameProvider = countryNameProvider,
): CountryListOptions {
    val comparator = provider.comparator(lang)
    val names = CountryCodes.SELECTABLE.associateWith { provider.name(it, lang) }
    val sorted = CountryCodes.SELECTABLE.sortedWith { a, b -> comparator.compare(names.getValue(a), names.getValue(b)) }
    val pinnedCode = pinned.takeIf { CountryCodes.isCountry(it) }
    val ordered = if (pinnedCode != null) listOf(pinnedCode) + sorted.filter { it != pinnedCode } else sorted
    val options = ordered.map { it to names.getValue(it) }.toMutableList()
    val searchTexts = HashMap<String, String>()
    ordered.forEach { searchTexts[it] = "${provider.englishName(it)} $it" }
    val separators = mutableSetOf<String>()
    if (pinnedCode != null) separators += pinnedCode
    if (mode == CountryListMode.NATIONALITY) {
        separators += ordered.last()
        options += CountryCodes.STATELESS to countryDisplayName(CountryCodes.STATELESS, lang)
        options += CountryCodes.UNCLEAR to countryDisplayName(CountryCodes.UNCLEAR, lang)
        searchTexts[CountryCodes.STATELESS] = "stateless XXA"
        searchTexts[CountryCodes.UNCLEAR] = "unclear unknown"
    }
    return CountryListOptions(options = options, searchTexts = searchTexts, separatorAfter = separators)
}

/** The decoration in front of a country name: the flag, or an equally wide spacer for an entry without one. */
internal fun countryDecoration(code: String): Widget? = countryFlagImage(code) ?: if (code.isEmpty()) null else flagSpacer()

/** The decoration in front of the SELECTED name in the field: only a real flag (a spacer there would be an empty gap). */
internal fun countrySelectedDecoration(code: String): Widget? = countryFlagImage(code)

/** Configures [select] as a country list (flags, search texts, pinned country, divider, no row cap, "— bitte wählen —"). */
internal fun SearchableSelect.configureAsCountryList(
    mode: CountryListMode,
    lang: String = I18n.language,
) {
    val built = buildCountryListOptions(mode, lang)
    maxShown = built.options.size + 1
    searchTexts = built.searchTexts
    separatorAfter = built.separatorAfter
    optionIcon = ::countryDecoration
    leadingIcon = ::countrySelectedDecoration
    options = listOf("" to gettext("— bitte wählen —")) + built.options
}

/** A standalone country combobox (not wired to a form): `value` is `""` or a selectable country code. */
fun Container.countrySelect(
    value: String? = null,
    label: String? = null,
    init: (SearchableSelect.() -> Unit)? = null,
): SearchableSelect {
    val select = SearchableSelect(value = value, label = label)
    select.configureAsCountryList(CountryListMode.COUNTRY)
    init?.invoke(select)
    add(select)
    return select
}

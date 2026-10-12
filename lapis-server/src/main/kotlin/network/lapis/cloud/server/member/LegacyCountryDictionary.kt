package network.lapis.cloud.server.member

import network.lapis.cloud.shared.domain.CountryCodes
import java.text.Normalizer
import java.util.Locale

/**
 * Welle V1.9.121 -- maps the legacy free-text `member.country` / `member.nationality` values to codes of [CountryCodes]. Pure (no
 * database), so the whole matching table is unit-testable. Used by the bulk reconciliation ([CountryLegacyMapping]) and by the CSV import.
 *
 * Matching is deliberately conservative: a value is only [Resolution.Recognised] when it maps to exactly ONE code (one code per
 * part for a nationality list). Everything doubtful ([Resolution.Ambiguous], e.g. "Kongo", "Korea", "Jugoslawien") or unknown
 * ([Resolution.Unrecognised]) stays open and a human decides.
 *
 * Sources of the dictionary: the names of all 250 countries in the eight UI languages (JDK/CLDR), the alpha-2/alpha-3 codes (only
 * when written in capitals, so "no"/"in"/"it" as words never match) and a hand list of German/English adjectives and short forms.
 */
object LegacyCountryDictionary {
    sealed interface Resolution {
        /** Exactly one code (country) or one-to-ten distinct codes (nationality list), sorted. */
        data class Recognised(
            val codes: List<String>,
        ) : Resolution

        data object Ambiguous : Resolution

        data object Unrecognised : Resolution
    }

    private val UI_LOCALES: List<Locale> =
        listOf("de", "en", "es", "fr", "it", "nl", "pl", "ru").map { Locale.forLanguageTag(it) }

    /** Alpha-2 codes that are also common words: written in capitals they still need a human ("NO", "IN", "TO" ...). */
    private val AMBIGUOUS_ALPHA2: Set<String> = setOf("NO", "IN", "IS", "AS", "DO", "TO", "ME", "AM", "SO", "OR", "ID", "AN", "ON")

    /** Alpha-3 codes that are also common words. */
    private val AMBIGUOUS_ALPHA3: Set<String> = setOf("AND", "CAN", "ARE", "PER", "MAR", "SUR", "BEN", "TOP", "SEN")

    /** Values that name nothing single (successor states, regions), never auto-resolved. */
    private val EXPLICIT_AMBIGUOUS: Set<String> =
        setOf(
            "kongo",
            "congo",
            "korea",
            "jugoslawien",
            "yugoslavia",
            "sowjetunion",
            "udssr",
            "ussr",
            "soviet union",
            "tschechoslowakei",
            "czechoslovakia",
            "serbien und montenegro",
            "ddr",
            "deutsche demokratische republik",
            "ostdeutschland",
            "westdeutschland",
            "europa",
            "europe",
            "eu",
            "afrika",
            "africa",
            "asien",
            "asia",
            "amerika sud",
            "welt",
        )

    private val STATELESS_KEYS: Set<String> =
        setOf(
            "staatenlos",
            "staatenlose",
            "staatenloser",
            "stateless",
            "apatride",
            "apatrida",
            "ohne staatsangehorigkeit",
            "ohne staatsangehoerigkeit",
        )

    private val UNCLEAR_KEYS: Set<String> =
        setOf("ungeklart", "ungeklaert", "unklar", "unbekannt", "unknown", "ungeklarte staatsangehorigkeit")

    /** (code, German adjective stems, English demonyms). German endings "", e, er, en, es, em and English "", s are expanded. */
    private val DEMONYMS: List<Triple<String, List<String>, List<String>>> =
        listOf(
            Triple("DE", listOf("deutsch"), listOf("german")),
            Triple("AT", listOf("österreichisch", "oesterreichisch"), listOf("austrian")),
            Triple("CH", listOf("schweizerisch", "schweizer"), listOf("swiss")),
            Triple("LI", listOf("liechtensteinisch"), listOf("liechtenstein")),
            Triple("PL", listOf("polnisch"), listOf("polish", "pole")),
            Triple("TR", listOf("türkisch", "tuerkisch"), listOf("turkish", "turk")),
            Triple("UA", listOf("ukrainisch"), listOf("ukrainian")),
            Triple("RU", listOf("russisch"), listOf("russian")),
            Triple("BY", listOf("belarussisch", "weissrussisch", "weißrussisch"), listOf("belarusian")),
            Triple("IT", listOf("italienisch"), listOf("italian")),
            Triple("GE", listOf("georgisch"), listOf("georgian")),
            Triple("FR", listOf("französisch", "franzoesisch"), listOf("french")),
            Triple("ES", listOf("spanisch"), listOf("spanish")),
            Triple("PT", listOf("portugiesisch"), listOf("portuguese")),
            Triple("NL", listOf("niederländisch", "niederlaendisch", "holländisch", "hollaendisch"), listOf("dutch")),
            Triple("BE", listOf("belgisch"), listOf("belgian")),
            Triple("LU", listOf("luxemburgisch"), listOf("luxembourgish")),
            Triple("GB", listOf("britisch", "englisch"), listOf("british", "english")),
            Triple("IE", listOf("irisch"), listOf("irish")),
            Triple("DK", listOf("dänisch", "daenisch"), listOf("danish")),
            Triple("SE", listOf("schwedisch"), listOf("swedish")),
            Triple("NO", listOf("norwegisch"), listOf("norwegian")),
            Triple("FI", listOf("finnisch"), listOf("finnish")),
            Triple("IS", listOf("isländisch", "islaendisch"), listOf("icelandic")),
            Triple("EE", listOf("estnisch"), listOf("estonian")),
            Triple("LV", listOf("lettisch"), listOf("latvian")),
            Triple("LT", listOf("litauisch"), listOf("lithuanian")),
            Triple("CZ", listOf("tschechisch"), listOf("czech")),
            Triple("SK", listOf("slowakisch"), listOf("slovak")),
            Triple("HU", listOf("ungarisch"), listOf("hungarian")),
            Triple("RO", listOf("rumänisch", "rumaenisch"), listOf("romanian")),
            Triple("BG", listOf("bulgarisch"), listOf("bulgarian")),
            Triple("GR", listOf("griechisch"), listOf("greek")),
            Triple("CY", listOf("zypriotisch"), listOf("cypriot")),
            Triple("MT", listOf("maltesisch"), listOf("maltese")),
            Triple("SI", listOf("slowenisch"), listOf("slovenian", "slovene")),
            Triple("HR", listOf("kroatisch"), listOf("croatian")),
            Triple("BA", listOf("bosnisch"), listOf("bosnian")),
            Triple("RS", listOf("serbisch"), listOf("serbian")),
            Triple("ME", listOf("montenegrinisch"), listOf("montenegrin")),
            Triple("MK", listOf("mazedonisch", "nordmazedonisch"), listOf("macedonian")),
            Triple("AL", listOf("albanisch"), listOf("albanian")),
            Triple("XK", listOf("kosovarisch"), listOf("kosovar", "kosovan")),
            Triple("MD", listOf("moldauisch", "moldawisch"), listOf("moldovan")),
            Triple("AM", listOf("armenisch"), listOf("armenian")),
            Triple("AZ", listOf("aserbaidschanisch"), listOf("azerbaijani")),
            Triple("KZ", listOf("kasachisch"), listOf("kazakh")),
            Triple("UZ", listOf("usbekisch"), listOf("uzbek")),
            Triple("US", listOf("amerikanisch"), listOf("american")),
            Triple("CA", listOf("kanadisch"), listOf("canadian")),
            Triple("MX", listOf("mexikanisch"), listOf("mexican")),
            Triple("BR", listOf("brasilianisch"), listOf("brazilian")),
            Triple("AR", listOf("argentinisch"), listOf("argentine", "argentinian")),
            Triple("CL", listOf("chilenisch"), listOf("chilean")),
            Triple("CO", listOf("kolumbianisch"), listOf("colombian")),
            Triple("PE", listOf("peruanisch"), listOf("peruvian")),
            Triple("VE", listOf("venezolanisch"), listOf("venezuelan")),
            Triple("CU", listOf("kubanisch"), listOf("cuban")),
            Triple("CN", listOf("chinesisch"), listOf("chinese")),
            Triple("JP", listOf("japanisch"), listOf("japanese")),
            Triple("KR", listOf("südkoreanisch", "suedkoreanisch"), listOf("south korean")),
            Triple("KP", listOf("nordkoreanisch"), listOf("north korean")),
            Triple("IN", listOf("indisch"), listOf("indian")),
            Triple("PK", listOf("pakistanisch"), listOf("pakistani")),
            Triple("BD", listOf("bangladeschisch"), listOf("bangladeshi")),
            Triple("AF", listOf("afghanisch"), listOf("afghan")),
            Triple("IR", listOf("iranisch"), listOf("iranian")),
            Triple("IQ", listOf("irakisch"), listOf("iraqi")),
            Triple("SY", listOf("syrisch"), listOf("syrian")),
            Triple("LB", listOf("libanesisch"), listOf("lebanese")),
            Triple("IL", listOf("israelisch"), listOf("israeli")),
            Triple("PS", listOf("palästinensisch", "palaestinensisch"), listOf("palestinian")),
            Triple("JO", listOf("jordanisch"), listOf("jordanian")),
            Triple("SA", listOf("saudi-arabisch", "saudisch"), listOf("saudi")),
            Triple("EG", listOf("ägyptisch", "aegyptisch"), listOf("egyptian")),
            Triple("MA", listOf("marokkanisch"), listOf("moroccan")),
            Triple("DZ", listOf("algerisch"), listOf("algerian")),
            Triple("TN", listOf("tunesisch"), listOf("tunisian")),
            Triple("LY", listOf("libysch"), listOf("libyan")),
            Triple("NG", listOf("nigerianisch"), listOf("nigerian")),
            Triple("GH", listOf("ghanaisch"), listOf("ghanaian")),
            Triple("ET", listOf("äthiopisch", "aethiopisch"), listOf("ethiopian")),
            Triple("ER", listOf("eritreisch"), listOf("eritrean")),
            Triple("SO", listOf("somalisch"), listOf("somali")),
            Triple("KE", listOf("kenianisch"), listOf("kenyan")),
            Triple("ZA", listOf("südafrikanisch", "suedafrikanisch"), listOf("south african")),
            Triple("VN", listOf("vietnamesisch"), listOf("vietnamese")),
            Triple("TH", listOf("thailändisch", "thailaendisch"), listOf("thai")),
            Triple("PH", listOf("philippinisch"), listOf("filipino", "philippine")),
            Triple("ID", listOf("indonesisch"), listOf("indonesian")),
            Triple("MY", listOf("malaysisch"), listOf("malaysian")),
            Triple("SG", listOf("singapurisch"), listOf("singaporean")),
            Triple("AU", listOf("australisch"), listOf("australian")),
            Triple("NZ", listOf("neuseeländisch", "neuseelaendisch"), listOf("new zealand")),
            Triple("TW", listOf("taiwanesisch"), listOf("taiwanese")),
            Triple("LK", listOf("sri-lankisch", "srilankisch"), listOf("sri lankan")),
            Triple("NP", listOf("nepalesisch"), listOf("nepalese")),
        )

    /** Short forms and aliases (already in normalised form, see [normalize]). Every value is a single code. */
    private val ALIASES: Map<String, String> =
        mapOf(
            "brd" to "DE",
            "bundesrepublik deutschland" to "DE",
            "bundesrepublik" to "DE",
            "d" to "DE",
            "ger" to "DE",
            "deutschland" to "DE",
            "republik osterreich" to "AT",
            "osterreich" to "AT",
            "oesterreich" to "AT",
            "usa" to "US",
            "u s a" to "US",
            "us" to "US",
            "vereinigte staaten" to "US",
            "vereinigte staaten von amerika" to "US",
            "united states of america" to "US",
            "united states" to "US",
            "amerika" to "US",
            "america" to "US",
            "uk" to "GB",
            "u k" to "GB",
            "great britain" to "GB",
            "grossbritannien" to "GB",
            "großbritannien" to "GB",
            "vereinigtes konigreich" to "GB",
            "vereinigtes koenigreich" to "GB",
            "united kingdom" to "GB",
            "england" to "GB",
            "schottland" to "GB",
            "scotland" to "GB",
            "wales" to "GB",
            "holland" to "NL",
            "burma" to "MM",
            "russische foderation" to "RU",
            "russian federation" to "RU",
            "turkei" to "TR",
            "tuerkei" to "TR",
            "turkey" to "TR",
            "turkiye" to "TR",
            "weissrussland" to "BY",
            "belarus" to "BY",
            "tschechien" to "CZ",
            "tschechische republik" to "CZ",
            "czech republic" to "CZ",
            "czechia" to "CZ",
            "nordmazedonien" to "MK",
            "mazedonien" to "MK",
            "macedonia" to "MK",
            "north macedonia" to "MK",
            "bosnien herzegowina" to "BA",
            "bosnien und herzegowina" to "BA",
            "bosnia" to "BA",
            "bosnia and herzegovina" to "BA",
            "elfenbeinkuste" to "CI",
            "ivory coast" to "CI",
            "cote d ivoire" to "CI",
            "swasiland" to "SZ",
            "eswatini" to "SZ",
            "vereinigte arabische emirate" to "AE",
            "uae" to "AE",
            "vae" to "AE",
            "emirate" to "AE",
            "volksrepublik china" to "CN",
            "china" to "CN",
            "taiwan" to "TW",
            "hongkong" to "HK",
            "hong kong" to "HK",
            "sudkorea" to "KR",
            "suedkorea" to "KR",
            "south korea" to "KR",
            "republik korea" to "KR",
            "nordkorea" to "KP",
            "north korea" to "KP",
            "sudafrika" to "ZA",
            "suedafrika" to "ZA",
            "south africa" to "ZA",
            "palastina" to "PS",
            "palaestina" to "PS",
            "palestine" to "PS",
            "vatikan" to "VA",
            "vatikanstadt" to "VA",
            "vatican" to "VA",
            "georgien" to "GE",
            "sakartvelo" to "GE",
            "ukraine" to "UA",
        )

    private data class Index(
        val byKey: Map<String, Set<String>>,
        val alpha3: Map<String, String>,
    )

    private val index: Index by lazy { buildIndex() }

    /** Lower case, diacritics removed (a/o/u umlauts and sharp s folded), punctuation to spaces, whitespace collapsed. */
    fun normalize(raw: String): String {
        val folded =
            Normalizer
                .normalize(raw.trim().replace("ß", "ss").replace("ẞ", "ss"), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase()
        return folded.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** Resolves a legacy COUNTRY text to exactly one selectable country, or reports ambiguity / no match. */
    fun resolveCountry(raw: String?): Resolution {
        val one = resolveToken(raw ?: return Resolution.Unrecognised)
        return when {
            one is Resolution.Recognised && !CountryCodes.isCountry(one.codes.single()) -> Resolution.Unrecognised
            else -> one
        }
    }

    /**
     * Resolves a legacy NATIONALITY text: one value or a list separated by `,` `/` `;` `&` `+` " und " " and ". At most
     * [CountryCodes.MAX_NATIONALITIES] parts; every part must be unambiguous, otherwise the whole value stays open. "staatenlos"
     * ([CountryCodes.STATELESS]) and "ungeklärt" ([CountryCodes.UNCLEAR]) are exclusive: combined with another part the value stays open.
     */
    fun resolveNationality(raw: String?): Resolution {
        if (raw.isNullOrBlank()) return Resolution.Unrecognised
        resolveToken(raw).let { whole -> if (whole is Resolution.Recognised || whole is Resolution.Ambiguous) return whole }
        val parts =
            raw
                .split(
                    Regex("[,/;&+]|\\s+und\\s+|\\s+and\\s+", RegexOption.IGNORE_CASE),
                ).map { it.trim() }
                .filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.size > CountryCodes.MAX_NATIONALITIES) return Resolution.Unrecognised
        val codes = sortedSetOf<String>()
        var ambiguous = false
        for (part in parts) {
            when (val one = resolveToken(part)) {
                is Resolution.Recognised -> codes += one.codes
                Resolution.Ambiguous -> ambiguous = true
                Resolution.Unrecognised -> return Resolution.Unrecognised
            }
        }
        if (ambiguous) return Resolution.Ambiguous
        if (codes.size > 1 && codes.any { it in CountryCodes.NATIONALITY_SPECIAL }) return Resolution.Ambiguous
        return Resolution.Recognised(codes.toList())
    }

    private fun resolveToken(raw: String): Resolution {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Resolution.Unrecognised
        val key = normalize(trimmed)
        if (key.isEmpty()) return Resolution.Unrecognised
        if (key in EXPLICIT_AMBIGUOUS) return Resolution.Ambiguous
        if (key in STATELESS_KEYS) return Resolution.Recognised(listOf(CountryCodes.STATELESS))
        if (key in UNCLEAR_KEYS) return Resolution.Recognised(listOf(CountryCodes.UNCLEAR))
        index.byKey[key]?.let { codes ->
            return if (codes.size == 1) Resolution.Recognised(listOf(codes.single())) else Resolution.Ambiguous
        }
        if (trimmed.length == 2 && trimmed.all { it in 'A'..'Z' }) {
            return when {
                trimmed in AMBIGUOUS_ALPHA2 -> Resolution.Ambiguous
                CountryCodes.isCountry(trimmed) -> Resolution.Recognised(listOf(trimmed))
                else -> Resolution.Unrecognised
            }
        }
        if (trimmed.length == 3 && trimmed.all { it in 'A'..'Z' }) {
            if (trimmed in AMBIGUOUS_ALPHA3) return Resolution.Ambiguous
            index.alpha3[trimmed]?.let { return Resolution.Recognised(listOf(it)) }
        }
        return Resolution.Unrecognised
    }

    private fun buildIndex(): Index {
        val primary = HashMap<String, MutableSet<String>>()
        val secondary = HashMap<String, MutableSet<String>>()
        val alpha3 = HashMap<String, String>()

        fun put(
            target: HashMap<String, MutableSet<String>>,
            text: String,
            code: String,
        ) {
            if (text.isBlank()) return
            target.getOrPut(normalize(text)) { sortedSetOf() }.add(code)
            // typed transliteration of umlauts ("Oesterreich", "Tuerkei")
            val translit =
                text
                    .replace(
                        "ä",
                        "ae",
                    ).replace("ö", "oe")
                    .replace("ü", "ue")
                    .replace("Ä", "Ae")
                    .replace("Ö", "Oe")
                    .replace("Ü", "Ue")
            if (translit != text) target.getOrPut(normalize(translit)) { sortedSetOf() }.add(code)
        }

        fun putAllForms(
            target: HashMap<String, MutableSet<String>>,
            name: String,
            code: String,
        ) {
            put(target, name, code)
            val withoutParens = name.replace(Regex("\\s*\\(.*?\\)"), "").trim()
            if (withoutParens != name) put(target, withoutParens, code)
            Regex("\\((.*?)\\)")
                .find(name)
                ?.groupValues
                ?.get(1)
                ?.let { put(target, it, code) }
        }

        for (code in CountryCodes.SELECTABLE) {
            val locale = Locale.of("", code)
            // German and English names are primary: they are never diluted by a homonym in another language.
            for (lang in listOf(Locale.GERMAN, Locale.ENGLISH)) putAllForms(primary, locale.getDisplayCountry(lang), code)
            if (code == CountryCodes.KOSOVO) putAllForms(primary, "Kosovo", code)
            for (lang in UI_LOCALES.drop(2)) putAllForms(secondary, locale.getDisplayCountry(lang), code)
            runCatching { locale.isO3Country }.getOrNull()?.takeIf { it.length == 3 }?.let { alpha3[it] = code }
        }
        // XK has no JDK ISO3; Kosovo's user-assigned alpha-3 is "XKX".
        alpha3["XKX"] = CountryCodes.KOSOVO

        val de = listOf("", "e", "er", "en", "es", "em")
        for ((code, adjectives, demonyms) in DEMONYMS) {
            for (stem in adjectives) for (end in de) put(primary, stem + end, code)
            for (stem in demonyms) for (end in listOf("", "s")) put(primary, stem + end, code)
        }
        for ((alias, code) in ALIASES) put(primary, alias, code)

        val merged = HashMap<String, Set<String>>()
        for ((key, codes) in secondary) merged[key] = codes
        // A primary key wins over a secondary one entirely (the primary set replaces, not extends).
        for ((key, codes) in primary) merged[key] = codes
        return Index(byKey = merged, alpha3 = alpha3)
    }
}

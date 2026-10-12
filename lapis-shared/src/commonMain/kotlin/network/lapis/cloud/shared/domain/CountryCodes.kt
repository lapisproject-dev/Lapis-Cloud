package network.lapis.cloud.shared.domain

/**
 * Welle V1.9.121 -- the fixed country list behind the member country and nationality selection lists.
 *
 * Codes are ISO 3166-1 alpha-2 (the 249 officially assigned ones) plus [KOSOVO] (`XK`, a user-assigned code every major
 * data set uses). Two further INTERNAL codes exist for the nationality field only: [STATELESS] (`XS`, the ICAO machine-readable
 * travel document code is `XXA`) and [UNCLEAR] (`ZZ`). They are exclusive: if one of them is chosen, nothing else may be.
 *
 * DOM-free and dependency-free (shared by the server and the browser client).
 */
object CountryCodes {
    /** The 249 officially assigned ISO 3166-1 alpha-2 codes, sorted. Hard-coded so server and client can never disagree. */
    val ISO_ALPHA2: List<String> =
        listOf(
            "AD",
            "AE",
            "AF",
            "AG",
            "AI",
            "AL",
            "AM",
            "AO",
            "AQ",
            "AR",
            "AS",
            "AT",
            "AU",
            "AW",
            "AX",
            "AZ",
            "BA",
            "BB",
            "BD",
            "BE",
            "BF",
            "BG",
            "BH",
            "BI",
            "BJ",
            "BL",
            "BM",
            "BN",
            "BO",
            "BQ",
            "BR",
            "BS",
            "BT",
            "BV",
            "BW",
            "BY",
            "BZ",
            "CA",
            "CC",
            "CD",
            "CF",
            "CG",
            "CH",
            "CI",
            "CK",
            "CL",
            "CM",
            "CN",
            "CO",
            "CR",
            "CU",
            "CV",
            "CW",
            "CX",
            "CY",
            "CZ",
            "DE",
            "DJ",
            "DK",
            "DM",
            "DO",
            "DZ",
            "EC",
            "EE",
            "EG",
            "EH",
            "ER",
            "ES",
            "ET",
            "FI",
            "FJ",
            "FK",
            "FM",
            "FO",
            "FR",
            "GA",
            "GB",
            "GD",
            "GE",
            "GF",
            "GG",
            "GH",
            "GI",
            "GL",
            "GM",
            "GN",
            "GP",
            "GQ",
            "GR",
            "GS",
            "GT",
            "GU",
            "GW",
            "GY",
            "HK",
            "HM",
            "HN",
            "HR",
            "HT",
            "HU",
            "ID",
            "IE",
            "IL",
            "IM",
            "IN",
            "IO",
            "IQ",
            "IR",
            "IS",
            "IT",
            "JE",
            "JM",
            "JO",
            "JP",
            "KE",
            "KG",
            "KH",
            "KI",
            "KM",
            "KN",
            "KP",
            "KR",
            "KW",
            "KY",
            "KZ",
            "LA",
            "LB",
            "LC",
            "LI",
            "LK",
            "LR",
            "LS",
            "LT",
            "LU",
            "LV",
            "LY",
            "MA",
            "MC",
            "MD",
            "ME",
            "MF",
            "MG",
            "MH",
            "MK",
            "ML",
            "MM",
            "MN",
            "MO",
            "MP",
            "MQ",
            "MR",
            "MS",
            "MT",
            "MU",
            "MV",
            "MW",
            "MX",
            "MY",
            "MZ",
            "NA",
            "NC",
            "NE",
            "NF",
            "NG",
            "NI",
            "NL",
            "NO",
            "NP",
            "NR",
            "NU",
            "NZ",
            "OM",
            "PA",
            "PE",
            "PF",
            "PG",
            "PH",
            "PK",
            "PL",
            "PM",
            "PN",
            "PR",
            "PS",
            "PT",
            "PW",
            "PY",
            "QA",
            "RE",
            "RO",
            "RS",
            "RU",
            "RW",
            "SA",
            "SB",
            "SC",
            "SD",
            "SE",
            "SG",
            "SH",
            "SI",
            "SJ",
            "SK",
            "SL",
            "SM",
            "SN",
            "SO",
            "SR",
            "SS",
            "ST",
            "SV",
            "SX",
            "SY",
            "SZ",
            "TC",
            "TD",
            "TF",
            "TG",
            "TH",
            "TJ",
            "TK",
            "TL",
            "TM",
            "TN",
            "TO",
            "TR",
            "TT",
            "TV",
            "TW",
            "TZ",
            "UA",
            "UG",
            "UM",
            "US",
            "UY",
            "UZ",
            "VA",
            "VC",
            "VE",
            "VG",
            "VI",
            "VN",
            "VU",
            "WF",
            "WS",
            "YE",
            "YT",
            "ZA",
            "ZM",
            "ZW",
        )

    /** Kosovo -- not an ISO code, but selectable (user-assigned code `XK`). */
    const val KOSOVO = "XK"

    /** Stateless person (internal code; ICAO document code `XXA`). Nationality field only, exclusive. */
    const val STATELESS = "XS"

    /** Nationality unclear / not yet determined (internal code). Nationality field only, exclusive. */
    const val UNCLEAR = "ZZ"

    /** The two internal, exclusive nationality values. */
    val NATIONALITY_SPECIAL: Set<String> = setOf(STATELESS, UNCLEAR)

    /** Upper bound of simultaneously held nationalities. */
    const val MAX_NATIONALITIES = 10

    /** Every code selectable in the country field: ISO plus Kosovo (250 entries), sorted. */
    val SELECTABLE: List<String> = (ISO_ALPHA2 + KOSOVO).sorted()

    private val selectableSet: Set<String> = SELECTABLE.toSet()

    /** `true` for one of the 250 selectable country codes (exact, upper case). `XS`/`ZZ` are NOT countries. */
    fun isCountry(code: String?): Boolean = code != null && code in selectableSet

    /** `true` for a code valid in the nationality field: a country or one of [NATIONALITY_SPECIAL]. */
    fun isNationalityValue(code: String?): Boolean = isCountry(code) || (code != null && code in NATIONALITY_SPECIAL)

    /** Trim + upper case; anything that is not exactly two ASCII letters becomes `null`. Does NOT check membership of the list. */
    fun normalizeCode(raw: String?): String? {
        val trimmed = raw?.trim()?.uppercase() ?: return null
        return trimmed.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    /** Result of [normalizeNationalities]. */
    sealed interface NationalitySelection {
        /** Deduplicated, sorted codes (may be empty = "cleared"). */
        data class Valid(
            val codes: List<String>,
        ) : NationalitySelection

        data class Invalid(
            val reason: CountryViolation,
        ) : NationalitySelection
    }

    /**
     * Normalises and validates a nationality selection: upper-cases, deduplicates, checks every code, caps the list at
     * [MAX_NATIONALITIES] and enforces that [STATELESS] / [UNCLEAR] stand alone. Never carries the offending value.
     */
    fun normalizeNationalities(raw: List<String>): NationalitySelection {
        val codes = LinkedHashSet<String>()
        for (entry in raw) {
            val code = normalizeCode(entry) ?: return NationalitySelection.Invalid(CountryViolation.UNKNOWN_CODE)
            if (!isNationalityValue(code)) return NationalitySelection.Invalid(CountryViolation.UNKNOWN_CODE)
            codes += code
        }
        if (codes.size > MAX_NATIONALITIES) return NationalitySelection.Invalid(CountryViolation.TOO_MANY)
        if (codes.size > 1 && codes.any { it in NATIONALITY_SPECIAL }) {
            return NationalitySelection.Invalid(CountryViolation.SPECIAL_NOT_EXCLUSIVE)
        }
        return NationalitySelection.Valid(codes.sorted())
    }

    /** Validates a country-field value: only the 250 selectable countries; `XS`/`ZZ` are rejected with a dedicated reason. */
    fun validateCountry(raw: String?): CountryViolation? {
        val code = normalizeCode(raw) ?: return CountryViolation.UNKNOWN_CODE
        return when {
            code in NATIONALITY_SPECIAL -> CountryViolation.SPECIAL_IN_COUNTRY_FIELD
            !isCountry(code) -> CountryViolation.UNKNOWN_CODE
            else -> null
        }
    }
}

/** Welle V1.9.121 -- why a country / nationality selection was rejected. Never carries the value. */
enum class CountryViolation { UNKNOWN_CODE, TOO_MANY, SPECIAL_NOT_EXCLUSIVE, SPECIAL_IN_COUNTRY_FIELD }

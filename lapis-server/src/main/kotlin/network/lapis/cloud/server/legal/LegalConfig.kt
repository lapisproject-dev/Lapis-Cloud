package network.lapis.cloud.server.legal

/**
 * V1.4.7 "Rechtstexte" -- the operator-supplied details that fill the Impressum/Datenschutz
 * templates (`GET /impressum`, `GET /datenschutz`). Pure string validation, NO I/O -- exactly the
 * posture `network.lapis.cloud.server.branding.BrandConfig` establishes (see its own KDoc).
 *
 * **Never fail-fast, never throwing.** [load] does not throw under any circumstances. Missing or
 * rejected MANDATORY fields do NOT abort startup and do NOT produce an HTTP 500 -- the page still
 * renders, with an operator-addressed notice block IN PLACE OF the missing data (never next to it,
 * never as an empty-looking line -- Ive's rule from the V1.4.7 design-team review). A
 * formal-looking but content-empty legal notice would be worse than a visibly unfinished one.
 *
 * All values are operator-controlled (environment variables), never request input -- the
 * control-character/length checks here are defense-in-depth against header/log injection (any
 * value can end up in a WARN log AND in HTML), not a defense against an untrusted caller. Compare
 * [BrandConfig]'s own C0 check.
 */
class LegalConfig private constructor(
    val operatorName: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val contactEmail: String?,
    val representative: String?,
    val phone: String?,
    val registerCourt: String?,
    val registerNumber: String?,
    val vatId: String?,
    val dpoContact: String?,
    val mstvResponsible: String?,
    val supervisoryAuthority: String?,
    /**
     * Names of the `LAPIS_LEGAL_*` variables whose value was rejected -- ONLY for startup logging
     * (see [LegalStartupCheck]), never a reason to throw. Empty when every set value was valid.
     */
    val invalid: List<String>,
) {
    /** Names of the mandatory variables that ended up unset/blank/rejected. Empty ⇔ complete. */
    val missingMandatory: List<String> =
        buildList {
            if (operatorName == null) add(ENV_OPERATOR_NAME)
            if (street == null) add(ENV_STREET)
            if (postalCode == null) add(ENV_POSTAL_CODE)
            if (city == null) add(ENV_CITY)
            if (country == null) add(ENV_COUNTRY)
            if (contactEmail == null) add(ENV_CONTACT_EMAIL)
            if (representative == null) add(ENV_REPRESENTATIVE)
        }

    val isComplete: Boolean get() = missingMandatory.isEmpty()

    companion object {
        const val ENV_OPERATOR_NAME = "LAPIS_LEGAL_OPERATOR_NAME"
        const val ENV_STREET = "LAPIS_LEGAL_STREET"
        const val ENV_POSTAL_CODE = "LAPIS_LEGAL_POSTAL_CODE"
        const val ENV_CITY = "LAPIS_LEGAL_CITY"
        const val ENV_COUNTRY = "LAPIS_LEGAL_COUNTRY"
        const val ENV_CONTACT_EMAIL = "LAPIS_LEGAL_CONTACT_EMAIL"
        const val ENV_REPRESENTATIVE = "LAPIS_LEGAL_REPRESENTATIVE"
        const val ENV_PHONE = "LAPIS_LEGAL_PHONE"
        const val ENV_REGISTER_COURT = "LAPIS_LEGAL_REGISTER_COURT"
        const val ENV_REGISTER_NUMBER = "LAPIS_LEGAL_REGISTER_NUMBER"
        const val ENV_VAT_ID = "LAPIS_LEGAL_VAT_ID"
        const val ENV_DPO_CONTACT = "LAPIS_LEGAL_DPO_CONTACT"
        const val ENV_MSTV_RESPONSIBLE = "LAPIS_LEGAL_MSTV_RESPONSIBLE"
        const val ENV_SUPERVISORY_AUTHORITY = "LAPIS_LEGAL_SUPERVISORY_AUTHORITY"

        private const val MAX_FIELD_LENGTH = 200

        /** Only [representative]/[dpoContact] may be multi-line (more than one named person). */
        private const val MAX_MULTILINE_LENGTH = 600

        private val MULTILINE_FIELDS = setOf(ENV_REPRESENTATIVE, ENV_DPO_CONTACT)

        /**
         * Pure string validation ONLY -- no DNS, no socket, no file I/O. Never throws -- a rejected
         * value is recorded in [LegalConfig.invalid] and the field falls back to `null`, see class
         * KDoc.
         */
        fun load(env: (String) -> String? = System::getenv): LegalConfig {
            val invalid = mutableListOf<String>()

            fun readField(name: String): String? {
                val raw = env(name)?.trim()?.takeUnless { it.isBlank() } ?: return null
                val multiline = name in MULTILINE_FIELDS
                val maxLength = if (multiline) MAX_MULTILINE_LENGTH else MAX_FIELD_LENGTH
                val hasIllegalControlChar =
                    if (multiline) {
                        // \n is allowed (multiple named persons, one per line); \r and every other
                        // C0 control character are rejected -- \r\n is deliberately NOT normalized,
                        // a silent repair would blur the header-injection guard this exists for.
                        raw.any { it.code < 0x20 && it != '\n' }
                    } else {
                        raw.any { it.code < 0x20 }
                    }
                return when {
                    hasIllegalControlChar -> {
                        invalid += name
                        null
                    }
                    raw.length > maxLength -> {
                        invalid += name
                        null
                    }
                    name == ENV_CONTACT_EMAIL && !isPlausibleEmail(raw) -> {
                        invalid += name
                        null
                    }
                    else -> raw
                }
            }

            return LegalConfig(
                operatorName = readField(ENV_OPERATOR_NAME),
                street = readField(ENV_STREET),
                postalCode = readField(ENV_POSTAL_CODE),
                city = readField(ENV_CITY),
                country = readField(ENV_COUNTRY),
                contactEmail = readField(ENV_CONTACT_EMAIL),
                representative = readField(ENV_REPRESENTATIVE),
                phone = readField(ENV_PHONE),
                registerCourt = readField(ENV_REGISTER_COURT),
                registerNumber = readField(ENV_REGISTER_NUMBER),
                vatId = readField(ENV_VAT_ID),
                dpoContact = readField(ENV_DPO_CONTACT),
                mstvResponsible = readField(ENV_MSTV_RESPONSIBLE),
                supervisoryAuthority = readField(ENV_SUPERVISORY_AUTHORITY),
                invalid = invalid,
            )
        }

        /**
         * Deliberately conservative, NOT RFC 5322: exactly one `@`, at least one character before
         * it, at least one `.` after it, no whitespace. No DNS/MX lookup (no I/O, see class KDoc).
         */
        private fun isPlausibleEmail(raw: String): Boolean {
            if (raw.any { it.isWhitespace() } || raw.contains(' ')) return false
            val atIndex = raw.indexOf('@')
            if (atIndex <= 0 || raw.indexOf('@', atIndex + 1) != -1) return false
            val domain = raw.substring(atIndex + 1)
            return domain.contains('.') && domain.first() != '.' && domain.last() != '.'
        }
    }
}

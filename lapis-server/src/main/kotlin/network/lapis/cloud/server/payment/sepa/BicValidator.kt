package network.lapis.cloud.server.payment.sepa

/**
 * V1.2.2 "SEPA-Lastschriftmandate" (Security Round 1, 2026-08-20 -- MINOR-5). ISO 9362 BIC/SWIFT
 * format check: 8 or 11 characters -- 4-letter bank code + 2-letter country code + 2-alphanumeric
 * location code + an optional 3-alphanumeric branch code.
 *
 * Extracted from what used to be a private `BIC_REGEX` living only inside
 * `network.lapis.cloud.server.rpc.SepaService` (gating [network.lapis.cloud.shared.domain.SepaMandateInput.debtorBic]
 * at `grantMandate`) into its own object so the exact SAME check can also gate the organization's
 * OWN creditor BIC (`network.lapis.cloud.server.rpc.OrganizationSettingsService.updateOrganizationSettings`'s
 * `bankBic`, and `SepaService.generateBatchFile`'s read of it before embedding it in a pain.008 file)
 * -- before this extraction, only the debtor side was validated at all: a malformed creditor BIC
 * would either sail straight into the generated bank file unchecked, or (once
 * `SepaPain008Writer.validate` is reached) surface as a raw, unmapped [IllegalArgumentException]
 * (HTTP 500) instead of the [network.lapis.cloud.shared.rpc.ConflictException] this wave uses for
 * every other actionable configuration problem.
 *
 * Format check ONLY -- same posture [IbanValidator]'s own KDoc documents for its Mod-97-10 check:
 * this says nothing about whether the BIC actually resolves to a real institution.
 */
object BicValidator {
    private val BIC_REGEX = Regex("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$")

    /** `true` iff [raw] matches the ISO 9362 BIC shape (8 or 11 characters, see class KDoc). */
    fun isValid(raw: String): Boolean = BIC_REGEX.matches(raw)

    /**
     * Throws [IllegalArgumentException] when [raw] does not match -- never contains [raw] itself in
     * the message (a BIC is not personal data, but this mirrors [IbanValidator.requireValid]'s own
     * discipline for symmetry). Callers map this to a [network.lapis.cloud.shared.rpc.ConflictException]
     * with an actionable, German, field-specific message -- this function's own message is an
     * internal implementation detail, never shown to a caller directly.
     */
    fun requireValid(raw: String): String {
        require(isValid(raw)) {
            "BIC is not formally valid (expected 8 or 11 characters: bank code + country code + location code [+ branch code])"
        }
        return raw
    }
}

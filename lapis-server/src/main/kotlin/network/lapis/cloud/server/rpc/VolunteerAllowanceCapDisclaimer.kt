package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- the versioned, hashed BOARD
 * cap-acknowledgment disclaimer, structurally identical to [AuctionComplianceDisclaimer] (see that
 * object's KDoc for the full rationale of the pattern). Shown to and echoed back verbatim (via
 * [matches]) by a BOARD/ADMIN member deciding a payment whose under-lock recheck yields
 * [network.lapis.cloud.shared.domain.VolunteerAllowanceVerdict.EXCEEDS_CAP] -- see
 * `VolunteerAllowanceService.decidePayment` KDoc.
 *
 * **Legal-verification disclaimer, same class as [AuctionComplianceDisclaimer]/
 * [PartyDonationComplianceCalculator]'s own KDoc**: [TEXT] documents that an ADMIN/BOARD member was
 * shown the named risk before opting to book an over-cap payment. **This is NOT a reviewed legal
 * conclusion and NOT automated Rechtsberatung.**
 *
 * [VERSION]/[TEXT]/[SHA256] are all immutable at runtime -- **a future wording change requires a
 * NEW [VERSION] string, never an in-place edit of [TEXT] under the same version** (that would
 * silently invalidate the audit trail's claim that a given actor saw a given version's exact
 * wording).
 *
 * **Persistence: NOT a dedicated `*_compliance_acknowledgment` table** -- unlike
 * [AuctionComplianceDisclaimer]'s sibling tables (which back an ORGANIZATION-WIDE, once-per-org
 * opt-in toggle), this acknowledgment is PER PAYMENT and must be written atomically with the
 * decision in the very same `volunteer_allowance_payment` row (`cap_disclaimer_version`/
 * `cap_disclaimer_sha256`/`cap_acknowledged_by`/`cap_acknowledged_at`) -- otherwise
 * `chk_vap_exceeding_needs_ack` could not guarantee the invariant DB-side.
 */
object VolunteerAllowanceCapDisclaimer {
    const val VERSION: String = "2026-09-12.v1"

    val TEXT: String =
        """
        Rechtshinweis zur Ehrenamts-/Übungsleiterpauschale (§3 Nr. 26 / 26a EStG)

        Diese Zahlung überschreitet den steuerfreien Jahresfreibetrag der gewählten Kategorie in
        dieser Organisation. Bevor Sie eine Zahlung mit übersteigendem Anteil genehmigen und buchen,
        bestätigen Sie Folgendes:

        - Der übersteigende Anteil dieser Zahlung ist lohnsteuer- und ggf. sozialversicherungspflichtig.
          Lapis Cloud rechnet ihn nicht ab und führt keine Lohnsteuer an. Das übernehmen Sie oder
          Ihre Steuerberatung.
        - Lapis Cloud kennt nur die in dieser Organisation gebuchten Beträge. Andere Organisationen
          sind Lapis Cloud nicht bekannt -- der gesetzliche Freibetrag gilt pro Person pro
          Kalenderjahr über alle Organisationen hinweg, nicht nur innerhalb dieser Organisation.
        - Die Selbstauskunft der empfangenden Person deckt nur ab, was diese Person Lapis Cloud
          gegenüber erklärt hat -- keine automatisierte Prüfung gegen andere Organisationen ist möglich.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Prüfung durch eine
        Steuerberaterin/einen Steuerberater oder eine Rechtsanwältin/einen Rechtsanwalt. Die
        Verantwortung für die korrekte lohnsteuerliche Behandlung des übersteigenden Anteils liegt
        ausschließlich beim Betreiber der jeweiligen Organisation.
        """.trimIndent()

    /**
     * `SHA-256` over `"$VERSION\n$TEXT"` -- a fresh [MessageDigest] instance PER CALL (thread-safe,
     * see the codebase's standing security checklist "Kryptografie: MessageDigest neue Instanz pro
     * Aufruf"), computed once at class-init time since [VERSION]/[TEXT] are themselves immutable.
     */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /**
     * `true` iff [version] equals [VERSION] AND [sha256] equals [SHA256] (constant-time comparison
     * via [MessageDigest.isEqual]). A malformed (non-hex, wrong length) [sha256] is treated as a
     * non-match, never thrown.
     */
    fun matches(
        version: String,
        sha256: String,
    ): Boolean {
        if (version != VERSION) return false
        val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
        val expected = hexToBytes(SHA256)
        return MessageDigest.isEqual(provided, expected)
    }

    private fun sha256Hex(input: String): String {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex character in '$hex'" }
            ((high shl 4) + low).toByte()
        }
    }
}

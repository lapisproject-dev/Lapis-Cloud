package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * The versioned, hashed legal-risk disclaimer an ADMIN must be shown -- and echo back verbatim
 * (via [matches]) -- before [PaymentGatewayService.enablePaymentGateway] will flip
 * `OrganizationSettings.paymentGatewayEnabled` on. Exact mirror of [AuctionComplianceDisclaimer]/
 * [SepaComplianceDisclaimer] -- see [AuctionComplianceDisclaimer]'s own KDoc for the full mechanism
 * this one reuses unchanged.
 *
 * **Legal-verification disclaimer, same class as [AuctionComplianceDisclaimer]/
 * [PartyDonationComplianceCalculator]'s own KDoc**: [TEXT] below names the risk areas identified as
 * relevant to a hosted PSP-Checkout-Integration at the time it was written (vault plan "Lapis Cloud
 * V1.2 -- Zahlungsverkehr" § 2.4). **This is NOT a reviewed legal conclusion and NOT automated
 * Rechtsberatung.**
 *
 * Welle V1.2.1 "Zahlungs-Fundament" shipped ONLY this disclaimer and the enable/disable gate --
 * `paymentGatewayEnabled` had no real functionality behind it yet (no PSP HTTP client, no checkout
 * session, no webhook route). Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) is that
 * promised sub-wave -- the gate/disclaimer mechanism here is unchanged, `PaymentGatewayService
 * .requirePaymentGatewayUsable()`/`network.lapis.cloud.server.routes.PspWebhookRoutes` now both
 * check [paymentGatewayDisclaimerIsCurrentlyAcknowledged] for real.
 */
object PaymentGatewayComplianceDisclaimer {
    const val VERSION: String = "2026-08-19.v1"

    val TEXT: String =
        """
        Rechtshinweis zur Zahlungsdienstleister-Anbindung (PayPal/Stripe)

        Bevor Sie die Annahme von Beiträgen und/oder Spenden über einen Zahlungsdienstleister für
        Ihre Organisation aktivieren, bestätigen Sie, dass folgende Risikobereiche geprüft und ggf.
        mit eigener rechtlicher Beratung geklärt wurden:

        - Zahlungsdiensteaufsicht (ZAG): ob die Nutzung eines Zahlungsdienstleisters als Zahlstelle
          für Beiträge/Spenden eine erlaubnispflichtige eigene Zahlungsdienstleistung begründet.
        - Auftragsverarbeitungsvertrag (AVV, Art. 28 DSGVO): ein wirksamer AVV mit dem gewählten
          Zahlungsdienstleister muss VOR der Aktivierung bestehen.
        - Drittlandübermittlung (Art. 44 ff. DSGVO): PayPal und Stripe sind US-Mutterkonzerne --
          die Zulässigkeit der damit verbundenen Datenübermittlung muss geklärt sein.
        - Geldwäschegesetz (GwG): ob geldwäscherechtliche Sorgfaltspflichten bei der Annahme von
          Spenden über diesen Kanal gelten.
        - § 25 Parteiengesetz (PartG): für Parteigliederungen -- Spenden über diesen Kanal
          durchlaufen automatisch die bestehende Spenden-Compliance-Prüfung dieser Plattform; diese
          ersetzt keine eigene rechtliche Prüfung.
        - PCI-DSS-Abgrenzung: die Anbindung nutzt ausschließlich gehostete Checkout-Flows -- die
          Organisation muss trotzdem sicherstellen, dass keine Kartendaten außerhalb dieses Flows
          verarbeitet werden.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Prüfung durch eine
        Rechtsanwältin/einen Rechtsanwalt. Die Plattform selbst nimmt keine rechtliche Einordnung vor
        und trifft keine automatisierte Entscheidung über die Zulässigkeit der Nutzung. Die
        Verantwortung für die Einhaltung aller einschlägigen Vorschriften liegt ausschließlich beim
        Betreiber der jeweiligen Organisation.
        """.trimIndent()

    /** `SHA-256` over `"$VERSION\n$TEXT"` -- see [AuctionComplianceDisclaimer.SHA256] KDoc. */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /** See [AuctionComplianceDisclaimer.matches] KDoc -- identical constant-time comparison contract. */
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

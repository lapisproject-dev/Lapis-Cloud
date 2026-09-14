package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". The versioned, hashed legal-risk disclaimer an
 * ADMIN must be shown -- and echo back verbatim (via [matches]) -- before
 * `BankAccountService.beginFinTsSetup` will accept a setup attempt for a given bank account. Exact
 * structural mirror of [VatComplianceDisclaimer] -- see that object's own KDoc for the full
 * mechanism this one reuses unchanged.
 *
 * Rechtliche Einordnung (Vault-Recherche 2026-09-13): In der aktuellen Ein-Organisation-pro-Instanz-
 * Architektur greift die Organisation mit ihren EIGENEN FinTS-Zugangsdaten auf ihr EIGENES Konto zu
 * -- architektonisch wie klassische Homebanking-Software, keine BaFin-Zulassung als
 * Kontoinformationsdienst (§ 1 Abs. 1a Nr. 8 ZAG) erforderlich. Bei einem kuenftigen Multi-Tenant-
 * SaaS-Betrieb, in dem EIN Betreiber die Zugangsdaten FREMDER Organisationen haelt und fuer diese
 * abruft, muesste das neu bewertet werden -- ausdruecklich AUSSER SCOPE dieser Welle, hier nur als
 * Warnmarke fuer die Welle, die Multi-Tenancy einfuehrt.
 */
object FinTsComplianceDisclaimer {
    const val VERSION: String = "2026-09-13.v1"

    val TEXT: String =
        """
        Rechtshinweis zum FinTS/HBCI-Live-Kontoabruf

        Bevor Sie den Live-Abruf Ihres Bankkontos aktivieren, bestaetigen Sie, dass Sie folgende
        Punkte geprueft haben:

        - Diese Funktion ist ein DIREKTER Zugriff Ihrer eigenen Organisation auf IHR EIGENES
          Bankkonto mit IHREN EIGENEN FinTS-Zugangsdaten -- keine Nutzung eines
          Drittanbieter-Kontoinformationsdienstes (kein "Screen Scraping", kein PSD2-XS2A-Zugriff
          ueber einen dritten Dienstleister).
        - Wo Ihre Bank es anbietet, wird empfohlen, fuer diesen Zugang die eingeschraenkte
          Zugriffsklasse "Kontoinformation" statt der vollen Zugriffsklasse zu registrieren -- diese
          Plattform fuehrt ohnehin ausschliesslich lesende Zugriffe aus (Kontoumsatz-/
          Saldoabfrage), niemals Ueberweisungen oder Lastschriften.
        - Die eingegebene PIN wird verschluesselt in der Datenbank dieser Instanz gespeichert (AES-
          256-GCM, siehe SecretBox) und niemals im Klartext angezeigt. PIN-Sicherheit und -Rotation
          bei Verdacht auf Kompromittierung liegen in der Verantwortung des Vorstands.
        - Diese Plattform verbindet sich direkt mit dem HBCI/FinTS-Endpunkt Ihrer Bank; ein
          Netzwerk-Sicherheitsmechanismus (Ziel-Adress-Pinning) greift auf dieser direkten
          Verbindung nicht in gleicher Tiefe wie bei anderen ausgehenden Verbindungen dieser
          Plattform -- siehe docs/architecture/bank-account.adoc fuer die vollstaendige
          Einordnung dieser Restluecke.
        - Banken, die ausschliesslich das camt.052-XML-Format (HKCAZ) statt des klassischen
          MT940-Formats (HKKAZ) anbieten, werden von dieser Funktion NICHT unterstuetzt.

        Dieser Hinweis stellt KEINE Rechtsberatung dar. Die Verantwortung fuer die Einhaltung aller
        einschlaegigen Vorschriften (insb. Bankvertragsbedingungen Ihrer Bank) liegt beim Vorstand
        der jeweiligen Organisation.
        """.trimIndent()

    /** `SHA-256` over `"$VERSION\n$TEXT"` -- see [VatComplianceDisclaimer.SHA256] KDoc. */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /** See [VatComplianceDisclaimer.matches] KDoc -- identical constant-time comparison contract. */
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

package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * V1.9.320 -- the versioned, hashed texts a person must be shown (and echo back verbatim) before an auction in Euro is possible. Two texts, one
 * mechanism, same shape as [AuctionComplianceDisclaimer]:
 *
 * - [AuctionFiatOperatorDisclaimer]: shown to an ADMIN who switches Fiat auctions on for the whole instance (acknowledgment kind `OPERATOR`).
 * - [AuctionFiatSellerDisclaimer]: shown to a MEMBER before listing in Euro (acknowledgment kind `SELLER`).
 *
 * **Legal-verification disclaimer, same class as [AuctionComplianceDisclaimer]:** the texts name the risk areas the authors identified at the time
 * of writing. They are **NOT a reviewed legal conclusion and NOT automated Rechtsberatung**; the wording has not been checked by a lawyer yet. A
 * wording change requires a NEW `VERSION`, never an in-place edit (that would silently invalidate the claim of the acknowledgment trail that a
 * person saw a given version's exact wording). The hash is computed over the UTF-8 bytes of `"$VERSION\n$TEXT"`.
 */
internal object FiatDisclaimerHashing {
    /** `SHA-256` hex of [version] + newline + [text]; a fresh [MessageDigest] per call (not thread-safe). */
    fun sha256Hex(
        version: String,
        text: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$version\n$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Constant-time check of the echoed [version]/[sha256]; a malformed (non-hex, odd length) hash is a non-match, never thrown. */
    fun matches(
        expectedVersion: String,
        expectedSha256: String,
        version: String,
        sha256: String,
    ): Boolean {
        if (version != expectedVersion) return false
        val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
        return MessageDigest.isEqual(provided, hexToBytes(expectedSha256))
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex character" }
            ((high shl 4) + low).toByte()
        }
    }
}

/** The notice an ADMIN acknowledges before switching auctions in Euro on. See [FiatDisclaimerHashing] KDoc. */
object AuctionFiatOperatorDisclaimer {
    const val VERSION: String = "2026-11-23.v1"

    val TEXT: String =
        """
        Rechtshinweis zu Auktionen in Euro mit Direktzahlung

        Bevor Sie Auktionen in Euro für Ihre Organisation freischalten, bestätigen Sie, dass Sie Folgendes verstanden und, soweit nötig, mit eigener
        rechtlicher Beratung geklärt haben:

        - Lapis führt den Kontakt zwischen Verkäufer und Höchstbietendem herbei und erfasst Gebot, Zuschlag und Bestätigungen. Lapis nimmt kein Geld
          an, hält keines, überweist nichts und erstattet nichts. Die Zahlung läuft unmittelbar zwischen Käufer und Verkäufer und außerhalb der
          Plattform. Es gibt keine Treuhand, keinen Käuferschutz und keine Zahlungsgarantie.
        - Zahlungsdiensteaufsicht (ZAG): Bitte prüfen Sie, ob Ihr Betrieb trotz fehlenden Geldflusses über die Plattform eine erlaubnispflichtige
          Tätigkeit sein kann, etwa durch die Weitergabe von Zahlungsangaben oder die Gestaltung des Ablaufs.
        - Geldwäschegesetz (GwG): Bitte prüfen Sie, ob für Ihren Betrieb Sorgfalts- und Meldepflichten gelten.
        - Steuerrecht: Verkäufer können umsatz-, einkommen- und gewerbesteuerpflichtig sein. Für Betreiber von Plattformen können Melde- und
          Aufzeichnungspflichten bestehen (Plattformen-Steuertransparenzgesetz). Lapis nimmt keine steuerliche Einordnung vor.
        - Verbraucherschutz: Informations-, Widerrufs- und Gewährleistungspflichten können zwischen Verkäufer und Käufer gelten.
        - Parteiengesetz (PartG): Bei Nutzung durch eine Parteigliederung ist zu klären, wie Erlöse zu behandeln und auszuweisen sind.
        - Datenschutz: Lapis speichert die Zahlungsangaben eines Verkäufers verschlüsselt, zeigt sie nur dem Höchstbietenden nach dem Zuschlag und dem
          Verkäufer selbst und löscht sie nach Abschluss. Das ersetzt keine eigene Prüfung der Rechtsgrundlagen.
        - Streitigkeiten zwischen Verkäufer und Käufer löst Lapis nicht. Es gibt weder einen Abbruch noch Fristen oder Mahnungen für ausbleibende
          Zahlungen.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine anwaltliche Prüfung. Die Verantwortung für die Einhaltung aller
        einschlägigen Vorschriften liegt ausschließlich beim Betreiber der jeweiligen Organisation. Es wird empfohlen, Auktionen in Euro zunächst nur
        in einem eng begrenzten Pilotbetrieb zu nutzen, bis die eigene Rechtslage abschließend geklärt ist.
        """.trimIndent()

    val SHA256: String = FiatDisclaimerHashing.sha256Hex(version = VERSION, text = TEXT)

    fun matches(
        version: String,
        sha256: String,
    ): Boolean = FiatDisclaimerHashing.matches(expectedVersion = VERSION, expectedSha256 = SHA256, version = version, sha256 = sha256)
}

/** The notice a MEMBER acknowledges before listing an auction in Euro. See [FiatDisclaimerHashing] KDoc. */
object AuctionFiatSellerDisclaimer {
    const val VERSION: String = "2026-11-23.v1"

    val TEXT: String =
        """
        Hinweis für Verkäufer: Auktion in Euro mit Direktzahlung

        Bevor Sie eine Auktion in Euro einstellen, bestätigen Sie Folgendes:

        - Die Bezahlung erfolgt direkt zwischen Ihnen und dem Höchstbietenden, außerhalb von Lapis. Lapis nimmt kein Geld an, hält keines und
          zahlt nichts zurück. Es gibt keine Treuhand, keinen Käuferschutz und keine Zahlungsgarantie.
        - Mit dem Zuschlag kommt zwischen Ihnen und dem Höchstbietenden ein Vertrag zu dem Preis zustande, den die Auktion ausweist. Sie sind selbst
          dafür verantwortlich, die Ware zu liefern, sobald Sie den Zahlungseingang bestätigt haben.
        - Sie sind selbst verantwortlich für Steuern (zum Beispiel Umsatzsteuer und Einkommensteuer), für eine eventuell nötige Gewerbeanmeldung
          und für verbraucherrechtliche Pflichten wie Informationen, Widerruf und Gewährleistung.
        - Ihre Zahlungsangaben (Kontoinhaber, IBAN, Zahlungslink) werden verschlüsselt gespeichert und nur dem Höchstbietenden nach dem Zuschlag
          angezeigt. Ein Zahlungslink muss auf Ihre eigene Zahlungsseite zeigen. Nach Abschluss werden die Angaben gelöscht.
        - Nach dem Zuschlag können Sie Ihre Zahlungsangaben nicht mehr ändern. Prüfen Sie sie daher vor dem Einstellen sorgfältig.
        - Bleibt eine Zahlung aus, gibt es in Lapis weder einen Abbruch noch Fristen oder Mahnungen. Streitigkeiten klären Sie unmittelbar mit der
          Gegenseite.
        - Eine Auktion in Euro hat keinen Einfluss auf Ihr LTR-Guthaben. Es fällt keine Einstellgebühr in LTR an.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine anwaltliche Prüfung.
        """.trimIndent()

    val SHA256: String = FiatDisclaimerHashing.sha256Hex(version = VERSION, text = TEXT)

    fun matches(
        version: String,
        sha256: String,
    ): Boolean = FiatDisclaimerHashing.matches(expectedVersion = VERSION, expectedSha256 = SHA256, version = version, sha256 = sha256)
}

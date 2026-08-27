package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.PublicRankingKind
import java.security.MessageDigest

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- the versioned, two-layer, hashed DSGVO consent
 * disclosure a member must be shown -- and echo back verbatim (via [Disclaimer.matches]) -- before
 * `PublicRankingConsentStore.grant` will record their opt-in into one of the two public
 * leaderboards `GET /transparenz` can show. Same immutability/versioning contract
 * [ConferenceGuestConsentDisclaimer] establishes: [Disclaimer.version]/[Disclaimer.text]/
 * [Disclaimer.sha256] are computed once from `val`s that never change at runtime -- a future
 * wording change requires a NEW version string for that [PublicRankingKind], never an in-place
 * edit of the existing text under the same version (that would silently invalidate every member's
 * belief about which exact wording they agreed to, and -- per [PublicRankingConsentStore
 * .currentState]'s own contract -- immediately makes every existing grant under that kind
 * ineffective, requiring re-consent).
 *
 * ## Two disclaimers, never one shared text (design decision D9)
 *
 * [PublicRankingKind.LTR_HOLDINGS] and [PublicRankingKind.DONATIONS] get COMPLETELY SEPARATE
 * [Disclaimer]s -- different [Disclaimer.headline]/[Disclaimer.keyPoints]/[Disclaimer.text]/
 * [Disclaimer.version]/[Disclaimer.sha256]. An internal-currency balance and a EUR donation total
 * to a political party are different kinds of exposure with different real-world consequences;
 * presenting one shared wording for both would obscure that difference from the member deciding
 * whether to opt in.
 *
 * ## Two-layer disclosure -- structurally drift-proof
 *
 * Same shape [ConferenceGuestConsentDisclaimer] establishes: [Disclaimer.text] is COMPOSED from
 * [Disclaimer.headline]/[Disclaimer.keyPoints]/the internal `detail` remainder
 * (`HEADLINE + "\n\n" + KEY_POINTS.joined + "\n\n" + DETAIL`), so drift between the short
 * client-rendered summary and the long hashed text is structurally impossible.
 * [PublicRankingConsentDisclaimerTest] asserts each [Disclaimer.keyPoints] entry appears verbatim
 * in [Disclaimer.text], and that the two kinds never share a [Disclaimer.text]/[Disclaimer.sha256].
 */
object PublicRankingConsentDisclaimer {
    /** A fully composed, versioned, hashed consent disclosure for one [PublicRankingKind]. */
    data class Disclaimer(
        val kind: PublicRankingKind,
        val version: String,
        val headline: String,
        val keyPoints: List<String>,
        val text: String,
        val sha256: String,
    ) {
        /**
         * `true` iff [version] equals [Disclaimer.version] AND [sha256] equals [Disclaimer.sha256]
         * (constant-time hex comparison via [MessageDigest.isEqual], same posture
         * [ConferenceGuestConsentDisclaimer.matches] establishes). A malformed (non-hex, wrong
         * length) [sha256] is treated as a non-match, never thrown.
         */
        fun matches(
            version: String,
            sha256: String,
        ): Boolean {
            if (version != this.version) return false
            val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
            val expected = hexToBytes(this.sha256)
            return MessageDigest.isEqual(provided, expected)
        }
    }

    private val LTR_HOLDINGS_VERSION = "2026-08-27.v1"
    private val LTR_HOLDINGS_HEADLINE = "Ihr LTR-Guthaben kann auf der öffentlichen Transparenzseite erscheinen."
    private val LTR_HOLDINGS_KEY_POINTS =
        listOf(
            """
            Ihr Anzeigename und Ihr aktuelles freies LTR-Guthaben werden veröffentlicht. Beide
            erscheinen -- solange Ihre Zustimmung wirksam ist -- unter den zehn höchsten Guthaben
            auf der öffentlichen Seite /transparenz, für jede Besucherin und jeden Besucher ohne
            Anmeldung einsehbar, unabhängig von deren Mitgliedschaft.
            """.trimIndent(),
            """
            Sie können jederzeit widerrufen. Ein Widerruf entfernt Ihren Namen spätestens nach 60
            Sekunden (der Cache-Zeit der Seite) von der öffentlichen Liste -- ohne Rückfrage, ohne
            Bestätigungsdialog. Diese Einwilligung ist unabhängig von einer etwaigen Einwilligung
            in die Spenden-Rangliste und muss getrennt widerrufen werden.
            """.trimIndent(),
        )
    private val LTR_HOLDINGS_DETAIL =
        """
        Mindestkohorte: Ihr Name erscheint nur, wenn insgesamt mindestens fünf Mitglieder in diese
        Rangliste eingewilligt haben -- unterhalb dieser Schwelle bleibt der gesamte Abschnitt der
        Seite verborgen, damit auch die Abwesenheit einer Liste keine Aussage über einzelne
        Mitglieder erlaubt.

        Wortlautänderung: Ändert sich dieser Hinweistext, verliert eine bereits erteilte
        Zustimmung ihre Wirkung -- Sie müssen der neuen Fassung erneut zustimmen, damit Ihr Name
        wieder erscheint.

        Dieser Hinweis stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text
        unter einer neuen Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
        """.trimIndent()

    private val DONATIONS_VERSION = "2026-08-27.v1"
    private val DONATIONS_HEADLINE = "Ihre Spenden können auf der öffentlichen Transparenzseite erscheinen."
    private val DONATIONS_KEY_POINTS =
        listOf(
            """
            Ihr Anzeigename und Ihre gebuchte Spendensumme des laufenden Kalenderjahres werden
            veröffentlicht. Beide erscheinen -- solange Ihre Zustimmung wirksam ist -- unter den
            zehn höchsten Spenderinnen und Spendern dieses Jahres auf der öffentlichen Seite
            /transparenz, als exakter Betrag, nicht gerundet oder gebändert.
            """.trimIndent(),
            """
            Sie können jederzeit widerrufen. Ein Widerruf entfernt Ihren Namen spätestens nach 60
            Sekunden (der Cache-Zeit der Seite) von der öffentlichen Liste -- ohne Rückfrage, ohne
            Bestätigungsdialog. Diese Einwilligung ist unabhängig von einer etwaigen Einwilligung
            in die LTR-Rangliste und muss getrennt widerrufen werden.
            """.trimIndent(),
        )
    private val DONATIONS_DETAIL =
        """
        Mindestkohorte: Ihr Name erscheint nur, wenn insgesamt mindestens fünf Mitglieder in diese
        Rangliste eingewilligt haben -- unterhalb dieser Schwelle bleibt der gesamte Abschnitt der
        Seite verborgen, damit auch die Abwesenheit einer Liste keine Aussage über einzelne
        Mitglieder erlaubt.

        Wortlautänderung: Ändert sich dieser Hinweistext, verliert eine bereits erteilte
        Zustimmung ihre Wirkung -- Sie müssen der neuen Fassung erneut zustimmen, damit Ihr Name
        wieder erscheint.

        Dieser Hinweis stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text
        unter einer neuen Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
        """.trimIndent()

    private val LTR_HOLDINGS: Disclaimer =
        build(
            kind = PublicRankingKind.LTR_HOLDINGS,
            version = LTR_HOLDINGS_VERSION,
            headline = LTR_HOLDINGS_HEADLINE,
            keyPoints = LTR_HOLDINGS_KEY_POINTS,
            detail = LTR_HOLDINGS_DETAIL,
        )
    private val DONATIONS: Disclaimer =
        build(
            kind = PublicRankingKind.DONATIONS,
            version = DONATIONS_VERSION,
            headline = DONATIONS_HEADLINE,
            keyPoints = DONATIONS_KEY_POINTS,
            detail = DONATIONS_DETAIL,
        )

    /** The current, versioned disclosure for [kind]. */
    fun of(kind: PublicRankingKind): Disclaimer =
        when (kind) {
            PublicRankingKind.LTR_HOLDINGS -> LTR_HOLDINGS
            PublicRankingKind.DONATIONS -> DONATIONS
        }

    private fun build(
        kind: PublicRankingKind,
        version: String,
        headline: String,
        keyPoints: List<String>,
        detail: String,
    ): Disclaimer {
        val text = headline + "\n\n" + keyPoints.joinToString("\n\n") + "\n\n" + detail
        return Disclaimer(
            kind = kind,
            version = version,
            headline = headline,
            keyPoints = keyPoints,
            text = text,
            sha256 = sha256Hex("$version\n$text"),
        )
    }

    /** `MessageDigest.getInstance` is NOT thread-safe -- fresh instance per call (house checklist). */
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

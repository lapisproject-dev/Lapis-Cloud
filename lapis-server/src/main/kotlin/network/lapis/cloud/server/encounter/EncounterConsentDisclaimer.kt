package network.lapis.cloud.server.encounter

import network.lapis.cloud.shared.domain.EncounterProfile
import java.security.MessageDigest

/** One versioned, hashed consent text; every [EncounterProfile] has exactly one ([encounterConsentFor]). */
interface EncounterConsentText {
    val version: String
    val headline: String
    val keyPoints: List<String>
    val text: String
    val sha256: String

    /** `true` iff [version] and [sha256] equal this text's own (constant-time compare; a malformed hash is a non-match, never thrown). */
    fun matches(
        version: String,
        sha256: String,
    ): Boolean
}

/** The consent text of a room profile. A guest who consented to one profile's text must consent again for the other (another purpose). */
fun encounterConsentFor(profile: EncounterProfile): EncounterConsentText =
    when (profile) {
        EncounterProfile.CHURCH_SERVICE -> EncounterConsentDisclaimer
        EncounterProfile.ASSEMBLY -> EncounterAssemblyConsentDisclaimer
    }

internal object ConsentHashing {
    fun sha256Hex(input: String): String {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

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

/**
 * The versioned, hashed Art. 9 GDPR consent a NON-member (GUEST or FRIEND) must acknowledge before entering an encounter space that
 * admits non-members. Same immutability/versioning contract as [network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer]:
 * [VERSION]/[TEXT]/[SHA256] are immutable at runtime, a wording change requires a NEW [VERSION], and [TEXT] is COMPOSED from
 * [HEADLINE]/[KEY_POINTS]/[DETAIL] so the short client summary and the long hashed text cannot drift apart.
 *
 * **The text is a DRAFT.** It is a proposal for the explicit consent under Art. 9(2)(a) GDPR (taking part in a church service can
 * reveal religious belief) and MUST be reviewed by a lawyer before a real deployment replaces it under a new [VERSION]. The statements
 * it makes about the system (nothing about attendance is stored, no recording, only the pulpit is broadcast) are exactly what the
 * server implements -- `EncounterPrivacyWatchTest` pins them.
 */
object EncounterConsentDisclaimer : EncounterConsentText {
    const val VERSION: String = "2026-10-06.v1"

    val HEADLINE: String = "Sie nehmen an einem Gottesdienst oder einer Andacht teil."

    val KEY_POINTS: List<String> =
        listOf(
            "Wer anwesend ist, wird nicht gespeichert. Die Anwesenheit besteht nur, solange Sie im Raum sind, und wird beim " +
                "Verlassen oder nach dem Ende der Veranstaltung gelöscht. Es gibt keine Teilnahmeliste und keinen Verlauf.",
            "Es wird nichts aufgezeichnet. Übertragen wird nur die Kanzel, Ihr eigenes Bild und Ihr Ton werden nicht " +
                "gesendet. Andere Anwesende sehen höchstens Ihren Namen, und nur solange Sie im Raum sind.",
        )

    private val DETAIL: String =
        """
        Besondere Kategorie von Daten: Die Teilnahme an einem Gottesdienst kann Rückschlüsse auf religiöse Überzeugungen zulassen. Das
        sind besondere Kategorien personenbezogener Daten (Art. 9 DSGVO). Wir verarbeiten sie nur, wenn Sie ausdrücklich einwilligen
        (Art. 9 Abs. 2 lit. a DSGVO).

        Was gespeichert wird: Als Nachweis Ihrer Einwilligung speichern wir die Version dieses Textes, dessen Prüfsumme und das Datum
        Ihrer Bestätigung, ohne Angabe von Raum oder Uhrzeit. Sonst wird nichts über Ihre Teilnahme gespeichert.

        Ihre Kontrolle: Sie können den Raum jederzeit verlassen. Ihre Einwilligung können Sie jederzeit mit Wirkung für die Zukunft
        widerrufen, indem Sie sich an die verantwortliche Organisation wenden. Die Rechtmäßigkeit der bisherigen Verarbeitung bleibt
        davon unberührt.

        Dieser Hinweis ist ein Entwurf und stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text unter einer neuen
        Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
        """.trimIndent()

    val TEXT: String = HEADLINE + "\n\n" + KEY_POINTS.joinToString("\n\n") + "\n\n" + DETAIL

    /** `SHA-256` over `"$VERSION\n$TEXT"`, byte-for-byte the shape of every other disclaimer in this codebase. */
    val SHA256: String = ConsentHashing.sha256Hex("$VERSION\n$TEXT")

    override val version: String get() = VERSION
    override val headline: String get() = HEADLINE
    override val keyPoints: List<String> get() = KEY_POINTS
    override val text: String get() = TEXT
    override val sha256: String get() = SHA256

    /** `true` iff [version] equals [VERSION] AND [sha256] equals [SHA256] (constant-time compare; a malformed hash is a non-match, never thrown). */
    override fun matches(
        version: String,
        sha256: String,
    ): Boolean = ConsentHashing.matches(expectedVersion = VERSION, expectedSha256 = SHA256, version = version, sha256 = sha256)
}

/**
 * V1.9.67: the consent text of an ASSEMBLY room (a political or civic gathering; attending can reveal political opinion or worldview,
 * Art. 9 GDPR). Same contract as [EncounterConsentDisclaimer]: immutable, a wording change needs a NEW [VERSION].
 *
 * **DRAFT, MUST be reviewed by a lawyer** before a real deployment relies on it. The statements it makes about the system are exactly
 * what the server implements (`EncounterConsentDisclaimerTest` pins the wording of those claims by substring checks; unlike the church
 * text, this one is not part of `EncounterPrivacyWatchTest`).
 */
object EncounterAssemblyConsentDisclaimer : EncounterConsentText {
    const val VERSION: String = "2026-10-07.assembly.v1"

    val HEADLINE: String = "Sie nehmen an einer Versammlung teil."

    val KEY_POINTS: List<String> =
        listOf(
            "Wer anwesend ist, wird nicht gespeichert. Die Anwesenheit besteht nur, solange Sie im Raum sind, und wird beim " +
                "Verlassen oder nach dem Ende der Versammlung gelöscht. Es gibt keine Teilnahmeliste und keinen Verlauf.",
            "Es wird nichts aufgezeichnet. Übertragen wird nur das Podium, Ihr eigenes Bild und Ihr Ton werden nicht " +
                "gesendet. Andere Anwesende sehen höchstens Ihren Namen, und nur solange Sie im Raum sind.",
        )

    private val DETAIL: String =
        """
        Besondere Kategorie von Daten: Die Teilnahme an einer Versammlung kann Rückschlüsse auf politische Meinungen oder
        weltanschauliche Überzeugungen zulassen. Das sind besondere Kategorien personenbezogener Daten (Art. 9 DSGVO). Wir verarbeiten
        sie nur, wenn Sie ausdrücklich einwilligen (Art. 9 Abs. 2 lit. a DSGVO).

        Was gespeichert wird: Als Nachweis Ihrer Einwilligung speichern wir die Version dieses Textes, dessen Prüfsumme und das Datum
        Ihrer Bestätigung, ohne Angabe von Raum oder Uhrzeit. Sonst wird nichts über Ihre Teilnahme gespeichert.

        Ihre Kontrolle: Sie können den Raum jederzeit verlassen. Ihre Einwilligung können Sie jederzeit mit Wirkung für die Zukunft
        widerrufen, indem Sie sich an die verantwortliche Organisation wenden. Die Rechtmäßigkeit der bisherigen Verarbeitung bleibt
        davon unberührt.

        Dieser Hinweis ist ein Entwurf und stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text unter einer neuen
        Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
        """.trimIndent()

    val TEXT: String = HEADLINE + "\n\n" + KEY_POINTS.joinToString("\n\n") + "\n\n" + DETAIL

    val SHA256: String = ConsentHashing.sha256Hex("$VERSION\n$TEXT")

    override val version: String get() = VERSION
    override val headline: String get() = HEADLINE
    override val keyPoints: List<String> get() = KEY_POINTS
    override val text: String get() = TEXT
    override val sha256: String get() = SHA256

    override fun matches(
        version: String,
        sha256: String,
    ): Boolean = ConsentHashing.matches(expectedVersion = VERSION, expectedSha256 = SHA256, version = version, sha256 = sha256)
}

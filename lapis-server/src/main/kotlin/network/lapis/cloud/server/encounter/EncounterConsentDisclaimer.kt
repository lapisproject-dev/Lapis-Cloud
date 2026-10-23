@file:Suppress("DEPRECATION")

package network.lapis.cloud.server.encounter

import network.lapis.cloud.server.agreementtexts.AgreementMarkdown
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.agreementtexts.EncounterConsentLabels
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextRules
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.ServiceBusyException
import java.security.MessageDigest

/** One versioned, hashed consent text; every [EncounterProfile] has exactly one at a time ([encounterConsentFor]). */
interface EncounterConsentText {
    val version: String
    val headline: String
    val keyPoints: List<String>
    val text: String
    val sha256: String

    /** V1.9.196: how [text] is displayed; [headline] and [keyPoints] are always plain text (Markdown characters removed). */
    val format: AgreementTextFormat get() = AgreementTextFormat.PLAIN

    /** V1.9.196: server-rendered sanitized HTML of [text] for a MARKDOWN notice, else `null`. */
    val renderedHtml: String? get() = null

    /** `true` iff [version] and [sha256] equal this text's own (constant-time compare; a malformed hash is a non-match, never thrown). */
    fun matches(
        version: String,
        sha256: String,
    ): Boolean
}

/**
 * One resolved entry notice: label, texts and hash as the guest is shown them AND as the proof stores them. [kind] is the ENC kind it came from,
 * [placeholder] is `true` while the built-in text (not an admin's published version) is the live one.
 */
internal data class ResolvedEncounterConsent(
    override val version: String,
    override val headline: String,
    override val keyPoints: List<String>,
    override val text: String,
    override val sha256: String,
    val kind: AgreementTextKind,
    val placeholder: Boolean,
    override val format: AgreementTextFormat = AgreementTextFormat.PLAIN,
    override val renderedHtml: String? = null,
) : EncounterConsentText {
    override fun matches(
        version: String,
        sha256: String,
    ): Boolean = version == this.version && AgreementTextHash.matches(expectedSha256 = this.sha256, providedSha256 = sha256)
}

/**
 * The entry notice of a room profile (V1.9.148: administered per instance). The kind follows the profile (ENC_CHURCH / ENC_ASSEM); the text is the
 * published version with the highest sequence number, else the built-in placeholder -- resolved and re-hashed by [AgreementTextResolver] (a
 * stored hash that no longer matches fails the call with [ServiceBusyException] instead of serving a wrong proof). Must run inside a transaction.
 *
 * For a room whose option "the pulpit may be recorded" is ON ([pulpitRecordingAllowed]) the code, never the admin, appends the recording paragraph as
 * the last key point and as the end of the full text, adds the system suffix `+rec` to the label and hashes the result. The choice follows the ROOM
 * option, not the instance switch: better to inform too much than too little. V1.9.166: a room with a map ([mapEnabled], assembly profile only) gets
 * a second code-owned paragraph and the suffix `+map` (`+rec+map` for both); without both options the result is byte-identical to before. A guest who acknowledged the variant without recording must acknowledge
 * again for the other one (another label and hash), and so must a guest after the admin published a new version.
 */
internal fun encounterConsentFor(
    profile: EncounterProfile,
    pulpitRecordingAllowed: Boolean = false,
    mapEnabled: Boolean = false,
): EncounterConsentText {
    val kind = EncounterConsentLabels.kindOf(profile)
    // V1.9.166: the map exists in the assembly profile only. Fail closed (the CHECK constraint makes this unreachable).
    if (mapEnabled && profile != EncounterProfile.ASSEMBLY) throw ServiceBusyException()
    val current = AgreementTextResolver.current(kind = kind, profile = profile)
    if (!pulpitRecordingAllowed && !mapEnabled) {
        // A published body that breaks the paragraph rule (an old row, a manual edit) is never served half-way.
        val split = EncounterConsentLayout.splitOrNull(body = current.body) ?: throw ServiceBusyException()
        return ResolvedEncounterConsent(
            version = current.versionLabel,
            headline = plainSummary(format = current.format, paragraph = split.headline),
            keyPoints = split.keyPoints.map { plainSummary(format = current.format, paragraph = it) },
            text = current.body,
            sha256 = current.sha256,
            kind = kind,
            placeholder = current.placeholder,
            format = current.format,
            renderedHtml = AgreementMarkdown.htmlFor(format = current.format, body = current.body),
        )
    }
    // The code, never the admin, appends its paragraphs (recording first, then map) as the last key points and as the end of the full text, adds
    // the system suffixes "+rec" and "+map" to the label (in that order) and hashes the result.
    val paragraphs =
        listOfNotNull(
            if (pulpitRecordingAllowed) EncounterConsentLayout.recordingParagraph(kind) else null,
            if (mapEnabled) EncounterConsentLayout.mapParagraph(kind) else null,
        )
    // Only the placeholder carries the sentence "Es wird nichts aufgezeichnet."; the leading space belongs to the pattern. It is false only
    // where the pulpit may be recorded.
    val base =
        if (pulpitRecordingAllowed && current.placeholder) {
            current.body.replaceFirst(" " + EncounterConsentLayout.NO_RECORDING_SENTENCE, "")
        } else {
            current.body
        }
    val split = EncounterConsentLayout.splitOrNull(body = base, extraKeyPoints = paragraphs) ?: throw ServiceBusyException()
    val label =
        current.versionLabel +
            (if (pulpitRecordingAllowed) EncounterConsentLayout.RECORDING_SUFFIX else "") +
            (if (mapEnabled) EncounterConsentLayout.MAP_SUFFIX else "")
    val text = base + "\n\n" + paragraphs.joinToString("\n\n")
    return ResolvedEncounterConsent(
        version = label,
        headline = plainSummary(format = current.format, paragraph = split.headline),
        keyPoints = split.keyPoints.map { plainSummary(format = current.format, paragraph = it) },
        text = text,
        sha256 = AgreementTextHash.of(versionLabel = label, body = text),
        kind = kind,
        placeholder = current.placeholder,
        format = current.format,
        // The COMPOSED full text is rendered (the code-owned paragraphs are plain sentences and valid Markdown); the hash above is over the source.
        renderedHtml =
            AgreementMarkdown.htmlFor(
                format = current.format,
                body = text,
                // the composed text (published text + code-owned paragraphs) may exceed MAX_CHARS: never render less than what is hashed
                maxChars = maxOf(AgreementTextRules.MAX_CHARS, text.length),
            ),
    )
}

/** Headline / key point of a notice: for a MARKDOWN text the Markdown characters are removed (the summary is shown as plain text). */
private fun plainSummary(
    format: AgreementTextFormat,
    paragraph: String,
): String = if (format == AgreementTextFormat.MARKDOWN) AgreementMarkdown.toPlainText(paragraph) else paragraph

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
@Deprecated(
    "V1.9.148: proof of consents already given only -- never served; the live notice comes from encounterConsentFor",
    level = DeprecationLevel.WARNING,
)
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
@Deprecated(
    "V1.9.148: proof of consents already given only -- never served; the live notice comes from encounterConsentFor",
    level = DeprecationLevel.WARNING,
)
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

/**
 * V1.9.143: the Art. 9 consent of a CHURCH_SERVICE room whose pulpit MAY be recorded. Same contract as [EncounterConsentDisclaimer], but the second key
 * point tells the truth: the pulpit can be recorded, the person's own picture and sound never are. The statements about attendance are unchanged
 * (`PulpitRecordingConsentTextTest` pins both).
 *
 * **DRAFT, MUST be reviewed by a lawyer** for the country of the instance before a real deployment relies on it; replace it under a new [VERSION].
 */
@Deprecated(
    "V1.9.148: proof of consents already given only -- never served; the live notice comes from encounterConsentFor",
    level = DeprecationLevel.WARNING,
)
object EncounterRecordingConsentDisclaimer : EncounterConsentText {
    const val VERSION: String = "2026-10-16.rec.v1"

    val HEADLINE: String = EncounterConsentDisclaimer.HEADLINE

    val KEY_POINTS: List<String> =
        listOf(
            EncounterConsentDisclaimer.KEY_POINTS[0],
            "In diesem Raum kann die Kanzel aufgezeichnet werden. Ihr eigenes Bild und Ihr Ton werden nicht gesendet und nie " +
                "aufgezeichnet. Andere Anwesende sehen höchstens Ihren Namen, und nur solange Sie im Raum sind.",
        )

    private val DETAIL: String = EncounterConsentDisclaimer.TEXT.substringAfter(EncounterConsentDisclaimer.KEY_POINTS[1] + "\n\n")

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

/** V1.9.143: the same for an ASSEMBLY room whose podium may be recorded. DRAFT, lawyer review required. */
@Deprecated(
    "V1.9.148: proof of consents already given only -- never served; the live notice comes from encounterConsentFor",
    level = DeprecationLevel.WARNING,
)
object EncounterAssemblyRecordingConsentDisclaimer : EncounterConsentText {
    const val VERSION: String = "2026-10-16.assembly-rec.v1"

    val HEADLINE: String = EncounterAssemblyConsentDisclaimer.HEADLINE

    val KEY_POINTS: List<String> =
        listOf(
            EncounterAssemblyConsentDisclaimer.KEY_POINTS[0],
            "In diesem Raum kann das Podium aufgezeichnet werden. Ihr eigenes Bild und Ihr Ton werden nicht gesendet und nie " +
                "aufgezeichnet. Andere Anwesende sehen höchstens Ihren Namen, und nur solange Sie im Raum sind.",
        )

    private val DETAIL: String =
        EncounterAssemblyConsentDisclaimer.TEXT.substringAfter(EncounterAssemblyConsentDisclaimer.KEY_POINTS[1] + "\n\n")

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

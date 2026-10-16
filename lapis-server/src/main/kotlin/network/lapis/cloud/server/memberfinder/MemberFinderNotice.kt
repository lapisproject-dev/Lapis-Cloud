package network.lapis.cloud.server.memberfinder

import network.lapis.cloud.server.encounter.ConsentHashing
import network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION

/**
 * V1.9.144 -- the REFERENCE text of the operator notice an ADMIN acknowledges before switching the member finder on (a client, when there is one, shows
 * the same text through gettext). [SHA256] is the SHA-256 over `"$VERSION\n$TEXT_DE"`, written to the audit entry of the switch-on so that it is provable
 * which text the admin saw.
 *
 * **Why a notice at all.** Party membership is a special category of data. Whether the finder is admissible, which consent and information duties apply
 * and whether a data protection impact assessment is needed depends on the country of the instance. Lapis Cloud does not decide that; it makes the
 * feature opt-in per instance and puts the responsibility, in writing, with the operator. The notice is NOT legal advice either.
 *
 * **A change of [TEXT_DE] needs a new [VERSION]** (and the same value of [MEMBER_FINDER_NOTICE_VERSION]): `MemberFinderNoticePinTest` pins version and hash.
 */
internal object MemberFinderNotice {
    const val VERSION: String = MEMBER_FINDER_NOTICE_VERSION

    /** The paragraphs, separated by a blank line in [TEXT_DE]. */
    val PARAGRAPHS_DE: List<String> =
        listOf(
            "Sie sind im Begriff, den Mitglieder-Finder für diese Instanz einzuschalten. Mitglieder, die " +
                "ausdrücklich einwilligen, können damit " +
                "andere Mitglieder derselben Region (die ersten zwei Ziffern der Postleitzahl) finden. Namen werden " +
                "erst nach beidseitiger " +
                "Zustimmung sichtbar.",
            "Eine Parteimitgliedschaft ist ein besonders geschütztes Datum (Art. 9 DSGVO und ähnliche Regelungen " +
                "anderer Länder). Zulässigkeit, " +
                "Einwilligung, Informationspflichten und die Notwendigkeit einer Datenschutz-Folgenabschätzung sind " +
                "je Land verschieden. Lapis " +
                "Cloud ist keine Rechtsberatung.",
            "Als Betreiber sind Sie dafür verantwortlich, dass eine Rechtsgrundlage besteht und die Mitglieder " +
                "informiert werden. Ersetzen Sie " +
                "den eingebauten Einwilligungstext in der Verwaltung der Vertragstexte durch eine rechtlich geprüfte Fassung.",
            "Ich habe die rechtliche Zulässigkeit für mein Land geprüft und übernehme die Verantwortung.",
        )

    val TEXT_DE: String = PARAGRAPHS_DE.joinToString("\n\n")

    /** `SHA-256` over `"$VERSION\n$TEXT_DE"`. */
    val SHA256: String = ConsentHashing.sha256Hex("$VERSION\n$TEXT_DE")
}

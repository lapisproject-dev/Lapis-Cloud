package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.PartgReviewerEventDto
import network.lapis.cloud.shared.domain.PartgReviewerEventKind
import network.lapis.cloud.shared.domain.PartgReviewerFileFormat
import network.lapis.cloud.shared.domain.PartgReviewerInvitationStatus
import network.lapis.cloud.shared.domain.PartgReviewerRules

// V1.9.310 (R6b) -- the pure logic of the two screens of the auditor's read access (the board's section in "Versionen und Freigabe" and the page of
// the auditor itself): words, validation and the token of the link. No DOM here, so it is testable without a browser.

/** What is wrong with the invitation the board is about to send. */
internal enum class ReviewerInviteProblem { EMAIL, NO_VERSION }

/** The problems of an invitation: an address that is not a plain address, and no version chosen. Empty = fine. */
internal fun reviewerInviteProblems(
    email: String,
    selectedVersions: Int,
): List<ReviewerInviteProblem> =
    buildList {
        if (PartgReviewerRules.normalizeEmail(email) == null) add(ReviewerInviteProblem.EMAIL)
        if (selectedVersions <= 0) add(ReviewerInviteProblem.NO_VERSION)
    }

internal fun reviewerProblemText(problem: ReviewerInviteProblem): String =
    when (problem) {
        ReviewerInviteProblem.EMAIL -> gettext("Bitte geben Sie eine gültige E-Mail-Adresse ein.")
        ReviewerInviteProblem.NO_VERSION -> gettext("Bitte wählen Sie mindestens eine Version aus.")
    }

/** The status as a WORD (never colour alone). */
internal fun reviewerStatusWord(status: PartgReviewerInvitationStatus): String =
    when (status) {
        PartgReviewerInvitationStatus.ACTIVE -> gettext("Gültig")
        PartgReviewerInvitationStatus.EXPIRED -> gettext("Abgelaufen")
        PartgReviewerInvitationStatus.REVOKED -> gettext("Widerrufen")
        PartgReviewerInvitationStatus.LOCKED -> gettext("Gesperrt")
    }

internal fun reviewerFormatWord(format: PartgReviewerFileFormat?): String =
    when (format) {
        PartgReviewerFileFormat.PDF -> gettext("PDF")
        PartgReviewerFileFormat.XLSX -> gettext("Excel")
        PartgReviewerFileFormat.JSON -> gettext("JSON")
        null -> ""
    }

/** One line of the usage list, without the time. */
internal fun reviewerEventText(event: PartgReviewerEventDto): String {
    val version = event.versionNumber?.toString().orEmpty()
    return when (event.kind) {
        PartgReviewerEventKind.INVITATION_CREATED -> gettext("Einladung angelegt")
        PartgReviewerEventKind.INVITATION_REVOKED -> gettext("Einladung beendet")
        PartgReviewerEventKind.CODE_SENT -> gettext("Code gesendet")
        PartgReviewerEventKind.CODE_FAILED -> gettext("Falscher Code eingegeben")
        PartgReviewerEventKind.INVITATION_LOCKED -> gettext("Einladung gesperrt (zu viele falsche Codes)")
        PartgReviewerEventKind.SESSION_OPENED -> gettext("Sitzung geöffnet")
        PartgReviewerEventKind.SESSION_ENDED -> gettext("Sitzung beendet")
        PartgReviewerEventKind.DOWNLOAD ->
            if (version.isEmpty()) {
                gettext("Datei geladen (%1)", reviewerFormatWord(event.format))
            } else {
                gettext("Version %1 geladen (%2)", version, reviewerFormatWord(event.format))
            }
        PartgReviewerEventKind.DOCUMENT_VIEWED ->
            if (version.isEmpty()) gettext("Version angesehen") else gettext("Version %1 angesehen", version)
        PartgReviewerEventKind.COMPARISON_VIEWED -> gettext("Vergleich angesehen")
    }
}

/** "Version 2, 3" for the versions an invitation covers. */
internal fun reviewerVersionsText(numbers: List<Int>): String =
    if (numbers.isEmpty()) gettext("Keine Version mehr") else gettext("Version %1", numbers.joinToString(", "))

/** The label of one validity option: "14 Tage". */
internal fun reviewerValidityLabel(days: Int): String = gettext("%1 Tage", days.toString())

private val TOKEN_IN_HASH = Regex("""[A-Za-z0-9_-]{16,64}""")

/**
 * The token of the link: `location.hash` is `#t=<token>`. Anything else (other parameters, a foreign alphabet, a wrong length) gives `null`. The page
 * removes the fragment from the address bar at once and keeps the token in memory only.
 */
internal fun reviewerTokenFromHash(hash: String): String? {
    val value = hash.removePrefix("#")
    if (!value.startsWith("t=")) return null
    return value.removePrefix("t=").takeIf { TOKEN_IN_HASH.matches(it) }
}

/** Is [path] the page of the auditor? The link is `<base>/pruefung#t=...`. */
internal fun isReviewerPath(path: String): Boolean = path == "/pruefung"

/** The code input as the page sends it: blanks removed, exactly six digits, else `null`. */
internal fun reviewerCodeInput(raw: String): String? = PartgReviewerRules.normalizeCode(raw)

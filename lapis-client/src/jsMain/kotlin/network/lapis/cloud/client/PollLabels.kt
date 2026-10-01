package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollOptionDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason

/*
 * V1.9.31 "Umfragen" -- the label and colour tables of the poll screens. Everything a person reads goes through gettext; everything a member
 * typed (question, description, option texts, the creator's name) goes through the untrusted-text sanitizer.
 */

fun pollStatusLabel(status: PollStatus): String =
    when (status) {
        PollStatus.OPEN -> gettext("Offen")
        PollStatus.CLOSED -> gettext("Geschlossen")
        PollStatus.ABORTED -> gettext("Abgebrochen")
    }

fun pollStatusColor(status: PollStatus): String =
    when (status) {
        PollStatus.OPEN -> "primary"
        PollStatus.CLOSED -> "secondary"
        PollStatus.ABORTED -> "dark"
    }

/** "endet am 12.10.2026, 18:00" or "ohne Frist". */
internal fun pollDeadlineLabel(poll: PollDto): String =
    poll.closesAt?.let { gettext("endet am %1", formatDateTime(it)) } ?: gettext("ohne Frist")

/** "1 Antwort" / "%1 Antworten" -- never "1 Antworten". */
internal fun pollAnswersLabel(count: Int): String = if (count == 1) gettext("1 Antwort") else gettext("%1 Antworten", count)

/** Why the head result is withheld (fewer than the minimum number of answers): no number below the minimum is ever disclosed. */
internal fun pollHeadWithheldText(): String =
    gettext("Zu wenige Antworten für eine anonyme Auswertung (mindestens %1).", PollRules.MIN_RESPONSES_FOR_RESULT)

/** Why the LTR-weighted result is withheld; `null` is a server that sent no reason. */
internal fun pollWithheldText(reason: PollWeightedWithheldReason?): String =
    when (reason) {
        PollWeightedWithheldReason.TOO_FEW_RESPONSES -> pollHeadWithheldText()
        PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT ->
            gettext("Keine der Antworten hatte LTR-Gewicht – eine gewichtete Auswertung ist nicht möglich.")
        PollWeightedWithheldReason.TOO_FEW_WEIGHTED_RESPONSES ->
            gettext(
                "Zu wenige Antworten mit LTR-Gewicht für eine anonyme gewichtete Auswertung (mindestens %1).",
                PollRules.MIN_WEIGHTED_RESPONSES,
            )
        PollWeightedWithheldReason.SMALL_WEIGHTED_GROUP ->
            gettext("Die gewichtete Auswertung wird zurückgehalten, damit keine einzelne Antwort erkennbar wird.")
        null -> gettext("Die gewichtete Auswertung ist nicht verfügbar.")
    }

/** The text of one option: a member's free text, sanitized (a forged i18n marker must never render as a catalog text). */
internal fun pollOptionText(option: PollOptionDto): String = sanitizeUntrustedI18nText(option.text)

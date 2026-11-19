package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MeetingRewardBlockedReason
import network.lapis.cloud.shared.domain.MeetingRewardLineReason
import network.lapis.cloud.shared.domain.MeetingRewardOwnLineDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus
import network.lapis.cloud.shared.domain.MeetingRewardUnavailableReason
import network.lapis.cloud.shared.rpc.ConflictException

/*
 * V1.9.316 -- texts and error handling of the LTR recognition for attendance at a Sitzung (settings section + card on the Sitzung).
 * The wording is always "Anerkennung", never a payment word: it is an issuance of LTR, not a wage. The tax and legal classification has not been reviewed.
 *
 * HONEST LIMIT: Kilua RPC transmits only the TYPE of a service exception, never its message (see the KDoc of `guarded`), so on today's transport the
 * message of a [ConflictException] reaches this file EMPTY and every refusal shows the fallback. The table below is kept exact anyway (the server code
 * throws fixed English sentences, `MeetingRewardErrorsCoverageTest` fails the build as soon as one of them has no entry here), so the specific text
 * appears the moment a message can travel. The screens additionally PREVENT the common refusals (the buttons are driven by `can*` and `blockedReason`).
 * A raw server text is never shown.
 */

/** The text for every refusal that is not (or not yet) known. */
internal fun meetingRewardFallbackText(): String = gettext("Die Aktion ist gerade nicht möglich. Bitte laden Sie die Seite neu.")

private fun exactTexts(): Map<String, String> =
    mapOf(
        "The recognition for meetings is not switched on" to gettext("Die Anerkennung für Sitzungen ist nicht eingeschaltet."),
        "The recognition is not switched on for this committee" to gettext("Für dieses Gremium ist die Anerkennung nicht eingeschaltet."),
        "The meeting was not held" to gettext("Die Sitzung hat nicht stattgefunden."),
        "The protocol of the meeting is not confirmed" to gettext("Das Protokoll der Sitzung ist noch nicht bestätigt."),
        "The attendance differs from the confirmed protocol" to gettext("Die Anwesenheit weicht vom bestätigten Protokoll ab."),
        "The settlement changed, please prepare it again" to gettext("Die Abrechnung hat sich geändert. Bitte bereiten Sie sie neu vor."),
        "A second administrator is required" to gettext("Dafür ist ein zweiter Admin nötig."),
        "The confirming administrator has not held the role long enough" to
            gettext("Der bestätigende Admin hat diese Rolle noch nicht lange genug."),
        "The resolution is not an adopted resolution of the general assembly" to
            gettext("Der Beschluss ist kein angenommener Beschluss der Mitgliederversammlung."),
        "A proposal is already open" to gettext("Es liegt bereits ein Vorschlag vor."),
        "The rules changed since the proposal was made" to gettext("Die Ordnung hat sich seit dem Vorschlag geändert."),
        "Switching on needs a resolution of the general assembly" to
            gettext("Das Einschalten braucht einen Beschluss der Mitgliederversammlung."),
        "The total of the settlement exceeds the limit per meeting" to gettext("Die Summe überschreitet die Obergrenze je Sitzung."),
        "The settlement was already released" to gettext("Die Abrechnung wurde bereits freigegeben."),
        "An amount has more than two decimal places" to gettext("Beträge dürfen höchstens zwei Nachkommastellen haben."),
        "An amount is out of range" to gettext("Ein Betrag liegt außerhalb des erlaubten Bereichs."),
        "A committee is unknown" to gettext("Ein Gremium ist unbekannt."),
        "The reference is too long" to gettext("Der Hinweistext ist zu lang."),
        "This action is not available in the current state" to gettext("Diese Aktion ist im jetzigen Stand nicht möglich."),
        "The data was changed concurrently, please reload" to
            gettext("Die Daten wurden zwischenzeitlich geändert. Bitte laden Sie die Seite neu."),
    )

/** The German text for the message of a refusal; `null`/blank/unknown gives the fallback. A raw server text is NEVER returned. */
internal fun meetingRewardErrorText(serverMessage: String?): String {
    val message = serverMessage?.trim().orEmpty()
    if (message.isEmpty()) return meetingRewardFallbackText()
    return exactTexts()[message] ?: meetingRewardFallbackText()
}

/** The result of [meetingRewardCall]: the value, or the German text of the recognition's own refusal (any other failure is handled by [guarded]). */
internal class MeetingRewardOutcome<T>(
    val value: T?,
    val refusal: String?,
)

/** Runs a call of a recognition screen. A [ConflictException] is its own refusal (the German text is RETURNED, no toast); everything else goes through [guarded]. */
internal suspend fun <T> meetingRewardCall(block: suspend () -> T): MeetingRewardOutcome<T> {
    var refusal: String? = null
    val result =
        guarded {
            try {
                block()
            } catch (e: ConflictException) {
                refusal = meetingRewardErrorText(e.message)
                null
            }
        }
    return MeetingRewardOutcome(value = result, refusal = refusal)
}

/** Like [meetingRewardCall], but shows the refusal as an error toast and returns only the value. */
internal suspend fun <T> meetingRewardGuarded(block: suspend () -> T): T? {
    val outcome = meetingRewardCall(block)
    outcome.refusal?.let { notifyError(it) }
    return outcome.value
}

/** Only an administrator sees the recognition screens (the server enforces it on every call). */
internal fun canSeeMeetingReward(): Boolean = AppState.hasRole(AccountRole.ADMIN)

internal fun meetingRewardReasonLabel(reason: MeetingRewardLineReason): String =
    when (reason) {
        MeetingRewardLineReason.EXCUSED -> gettext("Entschuldigt")
        MeetingRewardLineReason.REPRESENTED -> gettext("Vertreten")
        MeetingRewardLineReason.NOT_PRESENT -> gettext("Nicht anwesend")
        MeetingRewardLineReason.GUEST -> gettext("Gast")
        MeetingRewardLineReason.CAP_REACHED -> gettext("Obergrenze erreicht")
        MeetingRewardLineReason.CAP_REDUCED -> gettext("Auf die Obergrenze gekürzt")
        MeetingRewardLineReason.ALREADY_SETTLED -> gettext("Bereits abgerechnet")
        MeetingRewardLineReason.MEMBER_NOT_ELIGIBLE -> gettext("Nicht berechtigt")
    }

internal fun meetingRewardUnavailableText(reason: MeetingRewardUnavailableReason): String =
    when (reason) {
        MeetingRewardUnavailableReason.FEATURE_DISABLED -> gettext("Die Anerkennung für Sitzungen ist nicht eingeschaltet.")
        MeetingRewardUnavailableReason.COMMITTEE_NOT_ENABLED -> gettext("Für dieses Gremium ist die Anerkennung nicht eingeschaltet.")
        MeetingRewardUnavailableReason.MEETING_NOT_HELD -> gettext("Die Sitzung hat nicht stattgefunden.")
        MeetingRewardUnavailableReason.PROTOCOL_NOT_CONFIRMED -> gettext("Das Protokoll der Sitzung ist noch nicht bestätigt.")
        MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL -> gettext("Die Anwesenheit weicht vom bestätigten Protokoll ab.")
    }

internal fun meetingRewardBlockedText(reason: MeetingRewardBlockedReason): String =
    when (reason) {
        MeetingRewardBlockedReason.NOT_ADMIN -> gettext("Das dürfen nur Admins.")
        MeetingRewardBlockedReason.FOUR_EYES -> gettext("Dafür ist ein zweiter Admin nötig.")
        MeetingRewardBlockedReason.NOT_RELEASABLE -> gettext("Es ist nichts zur Freigabe vorbereitet.")
        MeetingRewardBlockedReason.APPROVER_NOT_TENURED -> gettext("Der bestätigende Admin hat diese Rolle noch nicht lange genug.")
    }

internal fun meetingRewardChangeKindLabel(kind: MeetingRewardPolicyChangeKind): String =
    when (kind) {
        MeetingRewardPolicyChangeKind.CHANGE -> gettext("Änderung")
        MeetingRewardPolicyChangeKind.DISABLE -> gettext("Ausgeschaltet")
    }

internal fun meetingRewardChangeStatusLabel(status: MeetingRewardPolicyChangeStatus): String =
    when (status) {
        MeetingRewardPolicyChangeStatus.PROPOSED -> gettext("Vorgeschlagen")
        MeetingRewardPolicyChangeStatus.CONFIRMED -> gettext("Bestätigt")
        MeetingRewardPolicyChangeStatus.WITHDRAWN -> gettext("Zurückgezogen")
    }

/**
 * The text of a ledger row of the recognition, resolved at READ time from the caller's own line (the ledger `note` stays empty). The committee name is
 * member-editable text, so the composed result is sanitized.
 */
internal fun meetingRewardLedgerText(line: MeetingRewardOwnLineDto): String {
    val date = formatDate(line.meetingDate)
    val text =
        if (line.bonusAmount.toDouble() > 0.0) {
            gettext(
                "Anerkennung für Sitzungsteilnahme: %1, %2, davon Zuschlag %3",
                line.committeeName,
                date,
                formatLtr(line.bonusAmount),
            )
        } else {
            gettext("Anerkennung für Sitzungsteilnahme: %1, %2", line.committeeName, date)
        }
    return sanitizeUntrustedI18nText(text)
}

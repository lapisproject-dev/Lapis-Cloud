package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType

/*
 * V1.9.22 "Wahlen" -- the label and colour tables of the elections screens. Every status/type that the server can send has a
 * label here, also the two reserved election types (LIST_VOTE/RANKED_CHOICE) the client never offers but could still receive.
 */

fun electionTypeLabel(type: ElectionType): String =
    when (type) {
        ElectionType.YES_NO -> gettext("Ja/Nein-Wahl")
        ElectionType.SINGLE_CHOICE -> gettext("Einzelwahl")
        ElectionType.MULTI_CHOICE -> gettext("Mehrfachwahl")
        ElectionType.LIST_VOTE -> gettext("Listenwahl")
        ElectionType.RANKED_CHOICE -> gettext("Rangfolgewahl")
    }

fun electionStatusLabel(status: ElectionStatus): String =
    when (status) {
        ElectionStatus.PREPARATION -> gettext("In Vorbereitung")
        ElectionStatus.CANDIDATE_LIST_RELEASED -> gettext("Kandidatenliste freigegeben")
        ElectionStatus.OPEN -> gettext("Abstimmung läuft")
        ElectionStatus.CLOSED -> gettext("Abstimmung beendet")
        ElectionStatus.TALLIED -> gettext("Ausgezählt")
        ElectionStatus.ABORTED -> gettext("Abgebrochen")
    }

fun electionStatusColor(status: ElectionStatus): String =
    when (status) {
        ElectionStatus.PREPARATION -> "secondary"
        ElectionStatus.CANDIDATE_LIST_RELEASED -> "info"
        ElectionStatus.OPEN -> "primary"
        ElectionStatus.CLOSED -> "warning"
        ElectionStatus.TALLIED -> "success"
        ElectionStatus.ABORTED -> "dark"
    }

fun answerLabel(answer: ElectionAnswer): String =
    when (answer) {
        ElectionAnswer.YES -> gettext("Ja")
        ElectionAnswer.NO -> gettext("Nein")
        ElectionAnswer.ABSTAIN -> gettext("Enthaltung")
    }

/** `true` for the two types that elect people (and therefore have candidacies, a candidate list and seats). */
fun isPersonnelElection(type: ElectionType): Boolean = type == ElectionType.SINGLE_CHOICE || type == ElectionType.MULTI_CHOICE

/**
 * The text of one ballot option or ballot selection. A YES_NO election stores its options as the enum names `YES`/`NO`/`ABSTAIN`,
 * which are translated here; everything else is a candidate's display name at the moment of release -- untrusted free text, so it
 * is sanitized (a forged i18n marker in a name must never render as a catalog text or a money amount).
 */
fun displayOptionLabel(
    election: ElectionDto,
    raw: String,
): String {
    if (election.electionType == ElectionType.YES_NO) {
        val answer = ElectionAnswer.entries.firstOrNull { it.name == raw }
        if (answer != null) return answerLabel(answer)
    }
    return sanitizeUntrustedI18nText(raw)
}

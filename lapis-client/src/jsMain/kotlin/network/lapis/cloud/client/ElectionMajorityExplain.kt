package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ElectionType

/*
 * V1.9.22 -- the plain-language explanation of a required majority, shown live while an election is being configured. The two
 * calculations below are exact copies of the server's integer arithmetic (`computeJaNeinErgebnis` for yes/no, the single-choice
 * branch of `ElectionService.computeOutcome`), so the example numbers can never promise a result the server would not give.
 */

/**
 * The smallest number of yes votes that carries a yes/no election with [decisive] yes+no votes (abstentions excluded) at [percent]:
 * the smallest `ja` with `ja * 100 >= percent * decisive` that is not a tie (`ja != decisive - ja`). `null` when there is no such
 * number (no decisive votes at all).
 */
fun minYesNeeded(
    decisive: Int,
    percent: Int,
): Int? {
    if (decisive <= 0) return null
    return (0..decisive).firstOrNull { ja ->
        ja.toLong() * 100 >= percent.toLong() * decisive && ja != decisive - ja
    }
}

/** The smallest vote count of a single-choice winner among [total] votes cast: `w * 100 >= percent * total`. */
fun minVotesSingleChoice(
    total: Int,
    percent: Int,
): Int = ((percent.toLong() * total + 99) / 100).toInt()

/** The sizes of the worked examples. */
private val EXAMPLE_SIZES = listOf(3, 10, 100)

/** The sentences explaining [percent] for [type]; empty for a type without a majority rule worth explaining. */
fun majorityExplanation(
    type: ElectionType,
    percent: Int,
): List<String> =
    when (type) {
        ElectionType.YES_NO ->
            EXAMPLE_SIZES.mapNotNull { n ->
                minYesNeeded(decisive = n, percent = percent)?.let { needed ->
                    gettext(
                        "Beispiel: Bei %1 Ja- und Nein-Stimmen sind mindestens %2 davon Ja nötig. Enthaltungen zählen nicht mit.",
                        n,
                        needed,
                    )
                }
            } + gettext("Bei Stimmengleichheit ist nichts entschieden, der Antrag wird zurückgestellt.")
        ElectionType.SINGLE_CHOICE ->
            EXAMPLE_SIZES.map { n ->
                gettext(
                    "Beispiel: Bei %1 abgegebenen Stimmen braucht die gewählte Person mindestens %2 davon.",
                    n,
                    minVotesSingleChoice(total = n, percent = percent),
                )
            } +
                gettext("Gibt es nur eine Person ohne Gegenkandidatur, wird keine Mehrheit geprüft.")
        ElectionType.MULTI_CHOICE ->
            listOf(
                gettext(
                    "Bei einer Mehrfachwahl gewinnen die Kandidierenden mit den meisten Stimmen. " +
                        "Ein Gleichstand an der Sitzgrenze entscheidet nichts.",
                ),
            )
        ElectionType.LIST_VOTE, ElectionType.RANKED_CHOICE -> emptyList()
    }

package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionType

/*
 * V1.9.22 -- the plain-language explanation of a required majority, shown live while an election is being configured. The two
 * calculations below are exact copies of the server's integer arithmetic (`computeJaNeinErgebnis` for yes/no, the single-choice
 * branch of `ElectionService.computeOutcome`), so the example numbers can never promise a result the server would not give.
 */

/**
 * The smallest number of yes votes that carries a yes/no election with [decisive] yes+no votes (abstentions excluded) at
 * [numerator]/[denominator]: the smallest `ja` with `ja * denominator >= numerator * decisive` that is not a tie
 * (`ja != decisive - ja`). `null` when there is no such number (no decisive votes at all). Exact copy of the server's
 * `MajorityFraction.isMetBy` (V1.9.23).
 */
fun minYesNeeded(
    decisive: Int,
    numerator: Int,
    denominator: Int,
): Int? {
    if (decisive <= 0) return null
    return (0..decisive).firstOrNull { ja ->
        ja.toLong() * denominator >= numerator.toLong() * decisive && ja != decisive - ja
    }
}

/** The percent form (elections created before V1.9.23): `ja * 100 >= percent * decisive`. */
fun minYesNeeded(
    decisive: Int,
    percent: Int,
): Int? = minYesNeeded(decisive = decisive, numerator = percent, denominator = 100)

/** The smallest vote count of a single-choice winner among [total] votes cast: `w * denominator >= numerator * total`. */
fun minVotesSingleChoice(
    total: Int,
    numerator: Int,
    denominator: Int,
): Int = ((numerator.toLong() * total + denominator - 1) / denominator).toInt()

/** The percent form (elections created before V1.9.23). */
fun minVotesSingleChoice(
    total: Int,
    percent: Int,
): Int = minVotesSingleChoice(total = total, numerator = percent, denominator = 100)

/** `ceil(numerator * 100 / denominator)` -- the whole percent a client that only knows percents is shown (the server's rule). */
fun majorityPercentCeil(
    numerator: Int,
    denominator: Int,
): Int = ((numerator.toLong() * 100 + denominator - 1) / denominator).toInt()

/** The words for a required majority: "Einfache Mehrheit", "Zwei Drittel", "Drei Viertel", else "mindestens N von M Stimmen". */
fun majorityLabel(
    numerator: Int,
    denominator: Int,
): String =
    when {
        numerator * 2 == denominator -> gettext("Einfache Mehrheit (mehr Ja als Nein)")
        numerator * 3 == denominator * 2 -> gettext("Zwei Drittel")
        numerator * 4 == denominator * 3 -> gettext("Drei Viertel")
        else -> gettext("mindestens %1 von %2 Stimmen", numerator, denominator)
    }

/** The majority of [e] in words: the exact fraction when it has one, else the legacy percent. */
fun majorityLabel(e: ElectionDto): String {
    val numerator = e.requiredMajorityNumerator
    val denominator = e.requiredMajorityDenominator
    return if (numerator != null && denominator != null) {
        majorityLabel(numerator, denominator)
    } else {
        gettext("%1 Prozent", e.requiredMajorityPercent)
    }
}

/** The sizes of the worked examples. */
private val EXAMPLE_SIZES = listOf(3, 10, 100)

/** The percent form (elections created before V1.9.23). */
fun majorityExplanation(
    type: ElectionType,
    percent: Int,
): List<String> = majorityExplanation(type = type, numerator = percent, denominator = 100)

/** The sentences explaining [numerator]/[denominator] for [type]; empty for a type without a majority rule worth explaining. */
fun majorityExplanation(
    type: ElectionType,
    numerator: Int,
    denominator: Int,
): List<String> =
    when (type) {
        ElectionType.YES_NO ->
            EXAMPLE_SIZES.mapNotNull { n ->
                minYesNeeded(decisive = n, numerator = numerator, denominator = denominator)?.let { needed ->
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
                    minVotesSingleChoice(total = n, numerator = numerator, denominator = denominator),
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

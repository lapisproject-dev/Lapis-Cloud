package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel

/**
 * Welle V1.9.34 -- badge colour of the quorum row. Reached = calm (`secondary`), not reached = `warning`. Never `success`/`danger`:
 * in the meetings screen green means "a resolution was accepted", and a missing quorum is a hint, not an error.
 */
internal fun quorumBadgeColor(met: Boolean): String = if (met) "secondary" else "warning"

/** One always-visible, small sentence under the quorum row. No legal assessment, no time, no button. */
internal fun SimplePanel.quorumExplanation() {
    div(
        tr(
            "Quorum heißt: Genug stimmberechtigte Mitglieder sind anwesend, damit abgestimmt werden kann. " +
                "Ob ein Antrag angenommen ist, zeigt allein das Beschlussergebnis. Diese Anzeige ist keine rechtliche Bewertung.",
        ),
    ) {
        addCssClasses("text-muted small")
    }
}

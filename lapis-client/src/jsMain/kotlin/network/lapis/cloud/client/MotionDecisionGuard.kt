package network.lapis.cloud.client

import io.kvision.i18n.tr

/*
 * V1.9.54 -- the conflict fallback of `resolveMotion`. Kilua RPC transmits only the exception TYPE, and the server answers with the
 * same `ConflictException` for "an election, a vote or a consensus is running", "already decided" and "an amendment is pending", so the
 * text names the likely causes without claiming one. `e.message` is never shown (it can carry UUIDs); the screen reloads instead.
 */

/**
 * Like `guarded`, but a `ConflictException` shows one fixed text and then runs [onConflict] (a reload), so the screen shows the real state.
 * Delegates all other exception types to [electionGuarded], i.e. to `guarded`.
 */
suspend fun <T> motionDecisionGuarded(
    onConflict: () -> Unit,
    block: suspend () -> T,
): T? =
    electionGuarded(
        conflictMessage =
            tr(
                "Über diesen Antrag kann gerade nicht entschieden werden, etwa weil dazu noch eine Wahl, eine Abstimmung oder ein " +
                    "Konsensieren läuft oder der Antrag inzwischen entschieden wurde. Die Ansicht wurde aktualisiert.",
            ),
        onConflict = onConflict,
        block = block,
    )

package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MailingPauseReason
import network.lapis.cloud.shared.domain.MailingSendEstimateDto
import network.lapis.cloud.shared.domain.MailingSendProgressDto

// Welle V1.9.81 -- the texts around the hourly send budget of a mailing-list send: the confirmation before "Senden" and the progress line
// of a running send. All of them are PURE functions returning FINISHED strings (gettext, never tr): a tr() marker is resolved by KVision only
// at the start of a widget's text, so one that is joined into a longer string would show up raw (the lesson of the temporary-password
// receipt, V1.9.80 follow-up). The numbers come from the server; the only free text near them is sanitized by the callers.

private const val SECONDS_PER_MINUTE = 60L
private const val ROUND_TO_MINUTES = 5L
private const val MINUTES_PER_HOUR = 60L

/**
 * A duration for a person: `unter 1 Minute`, then whole minutes rounded UP to 5 (`ca. 15 Min.`), from an hour on hours and minutes
 * (`ca. 2 Std. 5 Min.`). The rounding up keeps the promise honest -- it never says "10 minutes" for 11.
 */
internal fun formatMailingDuration(seconds: Long): String {
    if (seconds < SECONDS_PER_MINUTE) return gettext("unter 1 Minute")
    val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
    val rounded = ((minutes + ROUND_TO_MINUTES - 1) / ROUND_TO_MINUTES) * ROUND_TO_MINUTES
    return if (rounded < MINUTES_PER_HOUR) {
        gettext("ca. %1 Min.", rounded)
    } else {
        gettext("ca. %1 Std. %2 Min.", rounded / MINUTES_PER_HOUR, rounded % MINUTES_PER_HOUR)
    }
}

/**
 * The extra lines of the send confirmation: the recipient count (with the hourly budget and the duration when a budget is configured --
 * without one, only the number) and, when the send cannot fit into one budget hour, the sentence saying so. Already sanitized for
 * [confirmDialog], which hands each line to `modal.div(...)`.
 */
internal fun mailingSendSummaryLines(estimate: MailingSendEstimateDto): List<String> {
    val perHour = estimate.bulkBudgetPerHour
    val summary =
        if (perHour == null) {
            gettext("%1 Empfänger", estimate.recipientCount)
        } else {
            gettext(
                "%1 Empfänger · Stundenbudget für Rundschreiben: %2 pro Stunde · Dauer %3",
                estimate.recipientCount,
                perHour,
                formatMailingDuration(estimate.estimatedSeconds),
            )
        }
    val lines = mutableListOf(sanitizeUntrustedI18nText(summary))
    if (estimate.spansMultipleHours) lines += sanitizeUntrustedI18nText(gettext("Der Versand wird auf mehrere Stunden verteilt."))
    return lines
}

/** `HH:mm` of a class-A system timestamp in the organization's zone (class A is shown, never converted by hand). */
internal fun mailingClockTime(utc: LocalDateTime): String {
    val local = toOrganizationZone(utc)
    return formatTimeComponents(local.hour, local.minute)
}

/** What the progress panel shows for one [MailingSendProgressDto]: the headline, the pause (if any), and the two problem counters. */
internal data class MailingSendProgressTexts(
    val headline: String,
    val pause: String?,
    val interrupted: String?,
    val failed: String?,
)

internal fun mailingSendProgressTexts(progress: MailingSendProgressDto): MailingSendProgressTexts {
    val done = (progress.total - progress.pending).coerceAtLeast(0)
    val headline =
        gettext(
            "Wird versendet: %1 von %2 · noch %3",
            done,
            progress.total,
            formatMailingDuration(progress.remainingSeconds ?: 0L),
        )
    val until = progress.pausedUntil
    val pause =
        if (until == null) {
            null
        } else {
            when (progress.pauseReason) {
                MailingPauseReason.PROVIDER_DEFERRAL -> gettext("pausiert bis ca. %1 (Anbieter hat verzögert)", mailingClockTime(until))
                else -> gettext("pausiert bis ca. %1 (Stundenbudget)", mailingClockTime(until))
            }
        }
    return MailingSendProgressTexts(
        headline = headline,
        pause = pause,
        interrupted = progress.interrupted.takeIf { it > 0 }?.let { gettext("%1 unklar (Unterbrechung)", it) },
        failed = progress.failed.takeIf { it > 0 }?.let { gettext("%1 fehlgeschlagen", it) },
    )
}

package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MailingPauseReason
import network.lapis.cloud.shared.domain.MailingSendEstimateDto
import network.lapis.cloud.shared.domain.MailingSendProgressDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.9.81 -- the pure texts of the send confirmation and the progress line. */
class MailingSendFormatTest {
    @Test
    fun duration_underAMinute() {
        assertEquals("unter 1 Minute", formatMailingDuration(0))
        assertEquals("unter 1 Minute", formatMailingDuration(59))
        assertEquals("unter 1 Minute", formatMailingDuration(-5))
    }

    @Test
    fun duration_minutesAreRoundedUpToFive() {
        assertEquals("ca. 5 Min.", formatMailingDuration(60))
        assertEquals("ca. 5 Min.", formatMailingDuration(300))
        assertEquals("ca. 10 Min.", formatMailingDuration(301))
        assertEquals("ca. 10 Min.", formatMailingDuration(600))
        assertEquals("ca. 15 Min.", formatMailingDuration(601))
        assertEquals("ca. 15 Min.", formatMailingDuration(11 * 60))
        assertEquals("ca. 55 Min.", formatMailingDuration(55 * 60))
        assertEquals("ca. 55 Min.", formatMailingDuration(54 * 60 + 1))
    }

    @Test
    fun duration_fromAnHourOn_hoursAndMinutes() {
        assertEquals("ca. 1 Std. 0 Min.", formatMailingDuration(56 * 60))
        assertEquals("ca. 1 Std. 0 Min.", formatMailingDuration(3600))
        assertEquals("ca. 1 Std. 5 Min.", formatMailingDuration(3601))
        assertEquals("ca. 2 Std. 5 Min.", formatMailingDuration(2 * 3600 + 1))
        assertEquals("ca. 5 Std. 0 Min.", formatMailingDuration(5 * 3600))
    }

    @Test
    fun summary_withoutBudget_isOnlyTheNumber() {
        val lines =
            mailingSendSummaryLines(
                MailingSendEstimateDto(recipientCount = 42, bulkBudgetPerHour = null, estimatedSeconds = 11, spansMultipleHours = false),
            )
        assertEquals(listOf("42 Empfänger"), lines)
    }

    @Test
    fun summary_withBudget_namesTheHourlyFigureAndTheDuration() {
        val lines =
            mailingSendSummaryLines(
                MailingSendEstimateDto(recipientCount = 120, bulkBudgetPerHour = 200, estimatedSeconds = 2160, spansMultipleHours = false),
            )
        assertEquals(listOf("120 Empfänger · Stundenbudget für Rundschreiben: 200 pro Stunde · Dauer ca. 40 Min."), lines)
    }

    @Test
    fun summary_whenItDoesNotFitIntoOneHour_saysSo() {
        val lines =
            mailingSendSummaryLines(
                MailingSendEstimateDto(recipientCount = 450, bulkBudgetPerHour = 200, estimatedSeconds = 8100, spansMultipleHours = true),
            )
        assertEquals(2, lines.size)
        assertEquals("450 Empfänger · Stundenbudget für Rundschreiben: 200 pro Stunde · Dauer ca. 2 Std. 15 Min.", lines[0])
        assertEquals("Der Versand wird auf mehrere Stunden verteilt.", lines[1])
    }

    @Test
    fun summary_neverCarriesAForgedI18nMarker() {
        mailingSendSummaryLines(MailingSendEstimateDto(1, 5, 60, false)).forEach { assertFalse(it.contains("###Kv")) }
    }

    private fun progress(
        total: Int = 100,
        pending: Int = 40,
        interrupted: Int = 0,
        failed: Int = 0,
        pausedUntil: LocalDateTime? = null,
        reason: MailingPauseReason? = null,
        remaining: Long? = 1800,
    ) = MailingSendProgressDto(
        messageId = "m",
        total = total,
        sent = total - pending - interrupted - failed,
        failed = failed,
        interrupted = interrupted,
        skipped = 0,
        pending = pending,
        pausedUntil = pausedUntil,
        pauseReason = reason,
        remainingSeconds = remaining,
    )

    @Test
    fun progress_headline_countsFinishedOfTotal_withTheRemainingTime() {
        val texts = mailingSendProgressTexts(progress(total = 100, pending = 40, remaining = 1800))
        assertEquals("Wird versendet: 60 von 100 · noch ca. 30 Min.", texts.headline)
        assertNull(texts.pause)
        assertNull(texts.interrupted)
        assertNull(texts.failed)
    }

    @Test
    fun progress_problemCounters_appearOnlyWhenNonZero() {
        val texts = mailingSendProgressTexts(progress(interrupted = 3, failed = 2))
        assertEquals("3 unklar (Unterbrechung)", texts.interrupted)
        assertEquals("2 fehlgeschlagen", texts.failed)
    }

    @Test
    fun progress_pause_namesTheReasonAndAClockTime() {
        // 2031-01-01 11:30 UTC; the organization zone defaults to UTC in a bare test, so HH:MM is deterministic.
        val at = LocalDateTime(2031, 1, 1, 11, 30)
        val budget = mailingSendProgressTexts(progress(pausedUntil = at, reason = MailingPauseReason.HOURLY_BUDGET)).pause
        val provider = mailingSendProgressTexts(progress(pausedUntil = at, reason = MailingPauseReason.PROVIDER_DEFERRAL)).pause
        assertTrue(budget!!.startsWith("pausiert bis ca. ") && budget.endsWith(" (Stundenbudget)"), budget)
        assertTrue(provider!!.startsWith("pausiert bis ca. ") && provider.endsWith(" (Anbieter hat verzögert)"), provider)
        assertTrue(Regex("""\d\d:\d\d""").containsMatchIn(budget), budget)
    }

    @Test
    fun progress_nothingPending_neverShowsANegativeDone() {
        val texts = mailingSendProgressTexts(progress(total = 5, pending = 0, remaining = null))
        assertEquals("Wird versendet: 5 von 5 · noch unter 1 Minute", texts.headline)
    }
}

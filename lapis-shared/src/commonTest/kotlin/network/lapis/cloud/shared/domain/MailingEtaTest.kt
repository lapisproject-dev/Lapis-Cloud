package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Welle V1.9.81 -- the send-time estimate: delay rate within the hourly burst, one extra hour per further full batch, rounded up. */
class MailingEtaTest {
    @Test
    fun nothingToSendTakesNoTime() {
        assertEquals(0, MailingEta.estimateSeconds(remaining = 0, minDelayMs = 250, bulkPerHour = 200))
        assertEquals(0, MailingEta.estimateSeconds(remaining = -3, minDelayMs = 250, bulkPerHour = null))
    }

    @Test
    fun withoutABudgetOnlyTheMinimumDelayCounts() {
        assertEquals(25, MailingEta.estimateSeconds(remaining = 100, minDelayMs = 250, bulkPerHour = null))
        // rounded UP: 3 * 250 ms = 0.75 s -> 1 s
        assertEquals(1, MailingEta.estimateSeconds(remaining = 3, minDelayMs = 250, bulkPerHour = null))
        assertEquals(0, MailingEta.estimateSeconds(remaining = 1000, minDelayMs = 0, bulkPerHour = null))
    }

    @Test
    fun aListWithinTheHourlyBudgetGoesOutAtOnceAtTheDelayRate() {
        // a free sliding window allows a burst: 150 of 200 per hour take 150 * 250 ms, not 45 minutes
        assertEquals(38, MailingEta.estimateSeconds(remaining = 150, minDelayMs = 250, bulkPerHour = 200))
        assertEquals(50, MailingEta.estimateSeconds(remaining = 200, minDelayMs = 250, bulkPerHour = 200))
    }

    @Test
    fun everyFurtherFullBatchWaitsOneHour() {
        // 201 -> one extra mail after one hour; 250 -> 50 mails after one hour; 400 -> a full second batch
        assertEquals(3601, MailingEta.estimateSeconds(remaining = 201, minDelayMs = 250, bulkPerHour = 200))
        assertEquals(3613, MailingEta.estimateSeconds(remaining = 250, minDelayMs = 250, bulkPerHour = 200))
        assertEquals(3650, MailingEta.estimateSeconds(remaining = 400, minDelayMs = 250, bulkPerHour = 200))
        assertEquals(7200 + 13, MailingEta.estimateSeconds(remaining = 450, minDelayMs = 250, bulkPerHour = 200))
    }

    @Test
    fun theMinimumDelayDominatesWhenItIsSlowerThanTheBudget() {
        // 10 s per recipient beats 3600 / 8000 s
        assertEquals(1000, MailingEta.estimateSeconds(remaining = 100, minDelayMs = 10_000, bulkPerHour = 8000))
    }

    @Test
    fun aNonPositiveBudgetIsIgnoredInsteadOfDividingByZero() {
        assertEquals(25, MailingEta.estimateSeconds(remaining = 100, minDelayMs = 250, bulkPerHour = 0))
        assertEquals(25, MailingEta.estimateSeconds(remaining = 100, minDelayMs = 250, bulkPerHour = -5))
    }
}

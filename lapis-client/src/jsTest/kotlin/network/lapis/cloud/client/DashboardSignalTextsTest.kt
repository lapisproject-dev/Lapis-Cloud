package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.224 -- the sentences of the dashboard signals: every reason of the shared contract has a text, singular and plural, dates are formatted. */
class DashboardSignalTextsTest {
    private fun signal(
        reason: String,
        count: Int,
        params: Map<String, String> = emptyMap(),
    ) = DashboardSignalDto(
        moduleKey = reason.substringBefore('.'),
        reasonKey = reason,
        count = count,
        params = params,
        priority = DashboardPriority.ACTION,
    )

    private val withDate = mapOf("date" to "2030-06-15")

    @Test
    fun everyReasonOfTheContract_hasANonEmptyText_forOneAndForSeveralObjects() {
        DashboardReasonKeys.ALL.forEach { reason ->
            listOf(1, 3).forEach { count ->
                val text = DashboardSignalTexts.text(signal(reason, count, withDate + mapOf("bids" to "2")))
                assertTrue(!text.isNullOrBlank(), "no text for $reason with $count")
            }
        }
    }

    @Test
    fun everyModuleOfTheContract_hasARoute() {
        DashboardModuleKeys.ALL.forEach { assertNotNull(DashboardSignalRoutes.listRoute(it), "no route for $it") }
    }

    @Test
    fun theNumberAppearsInThePluralOnly() {
        assertTrue(DashboardSignalTexts.text(signal(DashboardReasonKeys.ELECTION_NOT_VOTED, 3))!!.contains("3"))
        assertFalse(DashboardSignalTexts.text(signal(DashboardReasonKeys.ELECTION_NOT_VOTED, 1))!!.any { it.isDigit() })
    }

    @Test
    fun aDate_isFormatted_withTheYearOnlyOutsideTheCurrentOne() {
        val far = DashboardSignalTexts.text(signal(DashboardReasonKeys.EVENTS_NEXT_REGISTERED, 1, mapOf("date" to "2031-06-15")))!!
        assertTrue(far.contains("2031"), far)
        val thisYear = organizationToday().year
        val near = DashboardSignalTexts.text(signal(DashboardReasonKeys.EVENTS_NEXT_REGISTERED, 1, mapOf("date" to "$thisYear-06-15")))!!
        assertFalse(near.contains(thisYear.toString()), near)
    }

    @Test
    fun theOwnBids_pickTheMatchingSentence() {
        val one = DashboardSignalTexts.text(signal(DashboardReasonKeys.AUCTION_OWN_HAS_BIDS, 1, mapOf("bids" to "1")))!!
        val many = DashboardSignalTexts.text(signal(DashboardReasonKeys.AUCTION_OWN_HAS_BIDS, 1, mapOf("bids" to "4")))!!
        assertTrue(many.contains("4"))
        assertFalse(one.contains("4"))
    }

    @Test
    fun anUnknownReason_andFreeTextParameters_giveNoText() {
        assertNull(DashboardSignalTexts.text(signal("auction.fromTheFuture", 1)))
        assertNull(DashboardSignalTexts.text(signal(DashboardReasonKeys.SURVEY_NOT_ANSWERED, 1, mapOf("date" to "<b>x</b>"))))
        assertNull(DashboardSignalTexts.text(signal(DashboardReasonKeys.EVENTS_NEXT_REGISTERED, 1)), "an event sentence needs its date")
    }

    @Test
    fun formatSignalDate_acceptsIsoDatesAndDateTimes_andNothingElse() {
        assertNotNull(formatSignalDate("2030-06-15"))
        assertNotNull(formatSignalDate("2030-06-15T12:00:00"))
        assertNull(formatSignalDate("kein Datum"))
        assertEquals(null, formatSignalDate(""))
    }
}

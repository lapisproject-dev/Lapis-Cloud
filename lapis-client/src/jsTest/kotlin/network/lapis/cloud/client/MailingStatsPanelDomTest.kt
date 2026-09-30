package network.lapis.cloud.client

import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.MailingLinkStatsDto
import network.lapis.cloud.shared.domain.MailingMessageStatsDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welle V1.9.15 -- the per-message statistics panel: aggregates only, suppression is explained, never shown as zero. */
class MailingStatsPanelDomTest {
    private fun stats(
        openSuppressed: Boolean = false,
        clickSuppressed: Boolean = false,
        retentionExpired: Boolean = false,
        links: List<MailingLinkStatsDto> = emptyList(),
    ) = MailingMessageStatsDto(
        messageId = "m1",
        delivered = 42,
        openCohort = 7,
        openedAtLeastOnce = if (openSuppressed) null else 5,
        clickCohort = 7,
        links = links,
        openSuppressed = openSuppressed,
        clickSuppressed = clickSuppressed,
        suppressed = openSuppressed || clickSuppressed,
        retentionExpired = retentionExpired,
    )

    private fun rendered(
        id: String,
        stats: MailingMessageStatsDto,
        check: (String, io.kvision.panel.Root, () -> org.w3c.dom.HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            mountedForm(id) { root, element ->
                val host = root.vPanel()
                renderMailingStatsPanel(host, stats)
                check(element().textContent.orEmpty(), root, element)
            }
        }

    @Test
    fun aggregates_areShown_asNumbers_withoutPercentages(): Promise<Unit> =
        rendered(
            "msp-ok",
            stats(
                links =
                    listOf(
                        MailingLinkStatsDto(0, "https://example.org/a", uniqueRecipients = 3, totalClicks = 9),
                        MailingLinkStatsDto(1, "https://example.org/b", uniqueRecipients = 0, totalClicks = 0),
                    ),
            ),
        ) { text, _, element ->
            assertTrue(text.contains("Zugestellt: 42"), text)
            assertTrue(text.contains("Geöffnet (Schätzwert): 5 von 7 mit Einwilligung"), text)
            assertTrue(text.contains("https://example.org/a"))
            assertEquals(2, element().allOf("tbody tr").size)
            assertFalse(text.contains("%"), "no percentages anywhere: $text")
            assertTrue(text.contains("Öffnungen sind ungenau"))
            assertFalse(text.contains("Zu wenige Einwilligungen"))
        }

    @Test
    fun suppressedCohort_isExplained_andShowsNoNumbersInsteadOfZero(): Promise<Unit> =
        rendered(
            "msp-suppressed",
            stats(
                openSuppressed = true,
                clickSuppressed = true,
                links = listOf(MailingLinkStatsDto(0, "https://example.org/a", uniqueRecipients = null, totalClicks = null)),
            ),
        ) { text, _, element ->
            assertTrue(text.contains("Zu wenige Einwilligungen für eine Auswertung (mindestens 5)."), text)
            assertFalse(text.contains("Geöffnet (Schätzwert)"), "no open figure when suppressed: $text")
            assertEquals(0, element().allOf("table").size, "no link table when click numbers are suppressed")
            assertFalse(text.contains("https://example.org/a"))
        }

    @Test
    fun aHostileUrlAndMarker_appearAsEscapedText_neverAsMarkupOrTranslation(): Promise<Unit> =
        rendered(
            "msp-untrusted",
            stats(
                links =
                    listOf(
                        MailingLinkStatsDto(
                            0,
                            "https://example.org/<script>alert(1)</script>?###KvI18nS###Zugestellt: %1",
                            uniqueRecipients = 1,
                            totalClicks = 1,
                        ),
                    ),
            ),
        ) { text, _, element ->
            assertEquals(0, element().allOf("script").size)
            assertTrue(text.contains("<script>alert(1)</script>"), "shown as literal text: $text")
            assertFalse(text.contains("###KvI18nS###"), "the forged marker is stripped")
        }

    @Test
    fun afterTheRetentionPeriod_onlyTheExpiryNoteAndDeliveredCountRemain(): Promise<Unit> =
        rendered("msp-retention", stats(retentionExpired = true)) { text, _, _ ->
            assertTrue(text.contains("Zugestellt: 42"))
            assertTrue(text.contains("Auswertung nach Aufbewahrungsfrist gelöscht."))
            assertFalse(text.contains("Geöffnet (Schätzwert)"))
        }
}

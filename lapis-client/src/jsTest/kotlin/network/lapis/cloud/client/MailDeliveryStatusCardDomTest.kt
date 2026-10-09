package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MailDeliveryStatusDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welle V1.9.81 -- the ADMIN health card of the mail pipeline: budget, queue, failures by purpose -- and never an address. */
class MailDeliveryStatusCardDomTest {
    private fun status(
        budget: Boolean = true,
        outbox: Boolean = true,
        failed: Map<String, Int> = mapOf("password-reset" to 2, "event-registration" to 1),
        expired: Map<String, Int> = mapOf("password-reset" to 1),
        pausedUntil: LocalDateTime? = null,
    ) = MailDeliveryStatusDto(
        budgetEnabled = budget,
        maxPerHour = if (budget) 250 else null,
        reservePerHour = if (budget) 50 else null,
        usedInWindow = if (budget) 37 else null,
        outboxEnabled = outbox,
        queuedCount = if (outbox) 4 else 0,
        oldestQueuedAgeSeconds = if (outbox) 600 else null,
        failedLast7DaysByPurpose = failed,
        expiredLast7DaysByPurpose = expired,
        bulkPausedUntil = pausedUntil,
    )

    private fun rendered(
        id: String,
        dto: MailDeliveryStatusDto,
        check: (String) -> Unit,
    ): Promise<Unit> =
        formTest {
            mountedForm(id) { root, element ->
                renderMailDeliveryStatusBody(root.vPanel(), dto)
                check(element().textContent.orEmpty())
            }
        }

    @Test
    fun budgetAndQueue_areShownAsNumbers(): Promise<Unit> =
        rendered("mds-full", status()) { text ->
            assertTrue(text.contains("37 / 250 (Reserve 50)"), text)
            assertTrue(text.contains("Wartend: 4"), text)
            assertTrue(text.contains("Älteste Wartezeit: ca. 10 Min."), text)
            assertTrue(text.contains("password-reset: 2"), text)
            assertTrue(text.contains("event-registration: 1"), text)
            assertFalse(text.contains("###"), "no tr() marker on screen")
        }

    @Test
    fun withoutABudgetOrAQueue_itSaysSoInsteadOfShowingZeros(): Promise<Unit> =
        rendered("mds-none", status(budget = false, outbox = false, failed = emptyMap(), expired = emptyMap())) { text ->
            assertTrue(text.contains("Kein Stundenbudget eingerichtet."), text)
            assertTrue(text.contains("Dauerhafte Warteschlange nicht aktiv"), text)
            assertFalse(text.contains("Wartend:"))
            assertEquals(2, Regex("Keine\\.").findAll(text).count(), text)
        }

    @Test
    fun aBulkPause_isShownWithItsClockTime(): Promise<Unit> =
        rendered("mds-pause", status(pausedUntil = LocalDateTime(2031, 1, 1, 9, 5))) { text ->
            assertTrue(Regex("""Rundschreiben pausiert bis ca. \d\d:\d\d \(Anbieter hat verzögert\)""").containsMatchIn(text), text)
        }

    @Test
    fun noAddressCanAppear_evenIfAHostileStringIsInThePurposeMap(): Promise<Unit> =
        rendered("mds-hostile", status(failed = mapOf("###KvI18nS###x" to 1))) { text ->
            assertFalse(text.contains("@"), "no address-shaped text on the card")
            assertFalse(text.contains("###KvI18nS###"), "a forged marker is stripped")
        }

    @Test
    fun theScreen_loadsThroughTheSharedLoadLifecycle_andOffersARetryOnFailure(): Promise<Unit> =
        formTest {
            mountedForm("mds-screen") { root, element ->
                var failing = true
                var loads = 0
                renderMailDeliveryStatusScreen(root.vPanel()) {
                    loads++
                    if (failing) null else status()
                }
                awaitUntil("the error state is shown") { element().textContent.orEmpty().contains("Erneut versuchen") }
                assertEquals(1, loads)
                failing = false
                element().buttonNamed("Erneut versuchen").click()
                awaitUntil("the card is shown") { element().textContent.orEmpty().contains("37 / 250 (Reserve 50)") }
                assertEquals(2, loads)
            }
        }
}

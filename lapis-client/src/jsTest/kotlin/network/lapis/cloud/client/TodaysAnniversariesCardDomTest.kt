package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnniversaryEmphasis
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TodaysAnniversariesDto
import network.lapis.cloud.shared.domain.TodaysAnniversaryDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.276 -- the dashboard card "Heute" (BOARD/ADMIN): visibility by role, texts, the cap, and its timing relative to "Für Sie". */
class TodaysAnniversariesCardDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Person",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
            aiAssistantEnabled = true,
            mcpEnabled = true,
        )

    private class FakeSignals(
        private val delayMs: Long = 0,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : DashboardSignalsRpc {
        var calls = 0

        override suspend fun signals(): List<DashboardSignalDto> {
            calls++
            if (delayMs > 0) delay(delayMs)
            gate?.await()
            return emptyList()
        }
    }

    private class FakeToday(
        private val gate: CompletableDeferred<Unit>? = null,
        private val answer: () -> TodaysAnniversariesDto,
    ) : TodaysAnniversariesRpc {
        var calls = 0

        override suspend fun today(): TodaysAnniversariesDto {
            calls++
            gate?.await()
            return answer()
        }
    }

    private class FakeVisibility : DashboardVisibility {
        var now = 1_000_000.0
        val listeners = mutableListOf<(Event) -> Unit>()

        override fun addListener(listener: (Event) -> Unit) {
            listeners += listener
        }

        override fun removeListener(listener: (Event) -> Unit) {
            listeners -= listener
        }

        override fun isVisible() = true

        override fun nowMs() = now

        fun fire() = listeners.toList().forEach { it(Event("visibilitychange")) }
    }

    private fun entry(
        name: String,
        kind: AnniversaryEntryKind = AnniversaryEntryKind.BIRTHDAY,
        years: Int = 50,
        emphasis: AnniversaryEmphasis = AnniversaryEmphasis.STANDARD,
    ) = TodaysAnniversaryDto(memberId = "id-$name", memberDisplayName = name, kind = kind, years = years, emphasis = emphasis)

    private fun dto(
        vararg entries: TodaysAnniversaryDto,
        total: Int = entries.size,
    ) = TodaysAnniversariesDto(entries.toList(), total)

    private fun HTMLElement.card(): HTMLElement? = querySelector(".lapis-dashboard-today .card") as HTMLElement?

    private suspend fun runIn(
        id: String,
        role: AccountRole,
        today: TodaysAnniversariesRpc,
        signals: FakeSignals = FakeSignals(),
        visibility: FakeVisibility = FakeVisibility(),
        block: suspend (HTMLElement, FakeVisibility) -> Unit,
    ) {
        AppState.setSession(session(role))
        withFetchStub {
            mountedForm(id) { root, element ->
                renderDashboardScreen(
                    root,
                    OnlineCountsRpc { error("unused") },
                    WindowOnlineCountsScheduler,
                    signals,
                    visibility,
                    StorageSpaceRpc { error("unused") },
                    today,
                )
                try {
                    block(element(), visibility)
                } finally {
                    root.removeAll()
                }
            }
        }
    }

    @Test
    fun boardAndAdmin_seeTheCard_withHeadingAndLabelledList(): Promise<Unit> =
        formTest {
            listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                runIn(
                    "today-$role",
                    role,
                    FakeToday { dto(entry("Anna"), entry("Ben", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, 5)) },
                ) { el, _ ->
                    awaitUntil("the card for $role") { el.card() != null }
                    val card = assertNotNull(el.card())
                    assertEquals("Heute", card.querySelector("h2")?.textContent?.trim())
                    val list = assertNotNull(card.querySelector("ul"))
                    assertTrue(!list.getAttribute("aria-label").isNullOrBlank(), "the list is labelled")
                    assertEquals(2, card.querySelectorAll("li").length)
                    assertNull(el.querySelector(".lapis-dashboard-today[aria-live]"), "no live region")
                }
            }
        }

    @Test
    fun anEmptyAnswer_showsNoCard(): Promise<Unit> {
        val today = FakeToday { dto() }
        return formTest {
            runIn("today-empty", AccountRole.BOARD, today) { el, _ ->
                awaitUntil("the empty sentence of Für Sie") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
                awaitUntil("the call") { today.calls == 1 }
                delay(50)
                assertNull(el.card())
            }
        }
    }

    @Test
    fun memberAndTreasurer_getNeitherSlotNorCall(): Promise<Unit> =
        formTest {
            listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEach { role ->
                val today = FakeToday { dto(entry("Anna")) }
                runIn("today-no-$role", role, today) { el, _ ->
                    awaitUntil("Für Sie settled") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
                    delay(50)
                    assertNull(el.querySelector(".lapis-dashboard-today"), "no slot for $role")
                    assertEquals(0, today.calls, "no call for $role")
                }
            }
        }

    @Test
    fun linesUseSingularAndPlural(): Promise<Unit> {
        val today =
            FakeToday {
                dto(
                    entry("Anna", years = 50),
                    entry("Ben", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, years = 1),
                    entry("Cem", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, years = 10),
                )
            }
        return formTest {
            runIn("today-texts", AccountRole.BOARD, today) { el, _ ->
                awaitUntil("the card") { el.card() != null }
                val text = el.card()!!.textContent.orEmpty()
                assertTrue(text.contains("Anna wird 50"), text)
                assertTrue(text.contains("Ben: seit 1 Jahr dabei"), text)
                assertTrue(text.contains("Cem: seit 10 Jahren dabei"), text)
            }
        }
    }

    @Test
    fun markerWord_forFirstYearAndMilestones_notForStandard(): Promise<Unit> {
        val today =
            FakeToday {
                dto(
                    entry("A1", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, 1, AnniversaryEmphasis.FIRST_YEAR),
                    entry("A2", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, 10, AnniversaryEmphasis.NOTABLE),
                    entry("A3", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, 25, AnniversaryEmphasis.MAJOR),
                    entry("A4", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, 5, AnniversaryEmphasis.STANDARD),
                )
            }
        return formTest {
            runIn("today-marker", AccountRole.ADMIN, today) { el, _ ->
                awaitUntil("the card") { el.card() != null }
                val items = el.card()!!.querySelectorAll("li")
                val texts = (0 until items.length).map { items.item(it)!!.textContent.orEmpty() }
                assertTrue(texts[0].contains("erstes Jahr"), texts[0])
                assertTrue(texts[1].contains("Meilenstein"), texts[1])
                assertTrue(texts[2].contains("Meilenstein"), texts[2])
                assertTrue(!texts[3].contains("Meilenstein") && !texts[3].contains("erstes Jahr"), texts[3])
            }
        }
    }

    @Test
    fun moreThanSixEntries_showSixPlusTheRestSentence_andTheLink(): Promise<Unit> {
        val today = FakeToday { dto(*(1..9).map { entry("Person $it") }.toTypedArray()) }
        return formTest {
            runIn("today-cap", AccountRole.BOARD, today) { el, _ ->
                awaitUntil("the card") { el.card() != null }
                val card = el.card()!!
                assertEquals(TODAY_CARD_ROWS, card.querySelectorAll("li").length)
                assertTrue(card.textContent.orEmpty().contains("und 3 weitere Einträge"), card.textContent.orEmpty())
                assertEquals("#/anniversaries", card.querySelector("a")?.getAttribute("href"))
            }
        }
    }

    @Test
    fun serverCappedList_usesTotalCountForTheRest(): Promise<Unit> {
        val today = FakeToday { dto(*(1..7).map { entry("Person $it") }.toTypedArray(), total = 53) }
        return formTest {
            runIn("today-total", AccountRole.BOARD, today) { el, _ ->
                awaitUntil("the card") { el.card() != null }
                assertTrue(
                    el
                        .card()!!
                        .textContent
                        .orEmpty()
                        .contains("und 47 weitere Einträge"),
                )
            }
        }
    }

    @Test
    fun aFailure_showsNothing_andNoToast(): Promise<Unit> {
        val today = FakeToday { error("boom") }
        return formTest {
            runIn("today-fail", AccountRole.BOARD, today) { el, _ ->
                awaitUntil("Für Sie settled") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
                awaitUntil("the call") { today.calls == 1 }
                delay(50)
                assertNull(el.card())
                assertNull(kotlinx.browser.document.querySelector(".toast, .lapis-toast"), "no toast")
            }
        }
    }

    @Test
    fun theCardWaitsForForYou_inEitherOrder(): Promise<Unit> =
        formTest {
            // today answers first, signals later
            val signalGate = CompletableDeferred<Unit>()
            runIn("today-order-1", AccountRole.BOARD, FakeToday { dto(entry("Anna")) }, FakeSignals(gate = signalGate)) { el, _ ->
                delay(100)
                assertNull(el.card(), "no card before Für Sie has settled")
                signalGate.complete(Unit)
                awaitUntil("the card after the signals") { el.card() != null }
            }
            // signals first, today later
            val todayGate = CompletableDeferred<Unit>()
            runIn("today-order-2", AccountRole.BOARD, FakeToday(todayGate) { dto(entry("Anna")) }) { el, _ ->
                awaitUntil("Für Sie settled") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
                assertNull(el.card())
                todayGate.complete(Unit)
                awaitUntil("the card after the answer") { el.card() != null }
            }
        }

    @Test
    fun theSixtySecondRefresh_reloadsSignals_notTheCard(): Promise<Unit> {
        val signals = FakeSignals()
        val today = FakeToday { dto(entry("Anna")) }
        return formTest {
            runIn("today-refresh", AccountRole.BOARD, today, signals) { el, visibility ->
                awaitUntil("the card") { el.card() != null }
                visibility.now += 61_000
                visibility.fire()
                awaitUntil("the second signal call") { signals.calls == 2 }
                assertEquals(1, today.calls)
            }
        }
    }

    @Test
    fun anAnswerAfterLeavingTheScreen_addsNothing(): Promise<Unit> {
        val gate = CompletableDeferred<Unit>()
        val today = FakeToday(gate) { dto(entry("Anna")) }
        return formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub {
                mountedForm("today-gone") { root, element ->
                    renderDashboardScreen(
                        root,
                        OnlineCountsRpc { error("unused") },
                        WindowOnlineCountsScheduler,
                        FakeSignals(),
                        FakeVisibility(),
                        StorageSpaceRpc { error("unused") },
                        today,
                    )
                    awaitUntil("the call") { today.calls == 1 }
                    root.removeAll()
                    gate.complete(Unit)
                    delay(100)
                    assertNull(element().querySelector(".lapis-dashboard-today .card"))
                }
            }
        }
    }

    @Test
    fun aNameWithMarkup_isShownAsText(): Promise<Unit> {
        val today = FakeToday { dto(entry("<img src=x onerror=alert(1)> %2")) }
        return formTest {
            runIn("today-xss", AccountRole.BOARD, today) { el, _ ->
                awaitUntil("the card") { el.card() != null }
                val card = el.card()!!
                assertNull(card.querySelector("img"))
                assertTrue(card.textContent.orEmpty().contains("<img src=x onerror=alert(1)>"), card.textContent.orEmpty())
            }
        }
    }
}

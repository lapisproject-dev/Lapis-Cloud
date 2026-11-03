package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OnlineCountsDto
import network.lapis.cloud.shared.domain.OnlineGroupCountsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ADMIN dashboard also mounts the online-counts tile; keep it answered and its timer off, so nothing outlives the test. */
private object QuietOnlineCountsRpc : OnlineCountsRpc {
    override suspend fun onlineCounts(): OnlineCountsDto {
        val group = OnlineGroupCountsDto(total = 1, members = 1, friends = 0, guests = 0, other = 0)
        return OnlineCountsDto(active = group, signedIn = group, computedAt = LocalDateTime(2026, 6, 1, 8, 5))
    }
}

private object QuietScheduler : OnlineCountsScheduler {
    override fun setInterval(
        callback: () -> Unit,
        ms: Int,
    ): Int = 1

    override fun clearInterval(id: Int) = Unit

    override fun addVisibilityListener(listener: (Event) -> Unit) = Unit

    override fun removeVisibilityListener(listener: (Event) -> Unit) = Unit
}

/** V1.9.263 -- the storage-space card of "Für Sie" (ADMIN only, hint and warning only, always first). */
class DashboardStorageSpaceDomTest {
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
        private val answer: List<DashboardSignalDto>,
    ) : DashboardSignalsRpc {
        override suspend fun signals(): List<DashboardSignalDto> {
            if (delayMs > 0) delay(delayMs)
            return answer
        }
    }

    private class FakeStorage(
        private val delayMs: Long = 0,
        private val answer: () -> StorageSpaceDto,
    ) : StorageSpaceRpc {
        var calls = 0

        override suspend fun storageSpace(): StorageSpaceDto {
            calls++
            if (delayMs > 0) delay(delayMs)
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

    private fun dto(level: StorageSpaceLevel) =
        when (level) {
            StorageSpaceLevel.UNKNOWN -> StorageSpaceDto(level, null, null)
            StorageSpaceLevel.WARNING -> StorageSpaceDto(level, 18_499_999_999L, 1_000_000_000_000L)
            else -> StorageSpaceDto(level, 150_000_000_000L, 1_000_000_000_000L)
        }

    private val urgentSignal =
        DashboardSignalDto(
            moduleKey = DashboardModuleKeys.CONTRIBUTIONS,
            reasonKey = DashboardReasonKeys.CONTRIBUTIONS_OVERDUE,
            count = 1,
            params = emptyMap(),
            objectId = null,
            priority = DashboardPriority.URGENT,
            deadline = null,
        )

    private fun HTMLElement.storageCards(): Int = querySelectorAll(".lapis-storage-card").length

    private fun run(
        id: String,
        role: AccountRole,
        signals: DashboardSignalsRpc,
        storage: StorageSpaceRpc,
        visibility: FakeVisibility = FakeVisibility(),
        block: suspend (HTMLElement, FakeVisibility) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            withFetchStub {
                mountedForm(id) { root, element ->
                    renderDashboardScreen(
                        root,
                        OnlineCountsRpc { error("unused") },
                        WindowOnlineCountsScheduler,
                        signals,
                        visibility,
                        storage,
                    )
                    block(element(), visibility)
                }
            }
        }

    @Test
    fun hintCard_isFirstInForYou_evenBesideAnUrgentSignal(): Promise<Unit> {
        val storage = FakeStorage { dto(StorageSpaceLevel.HINT) }
        return run("storage-hint", AccountRole.ADMIN, FakeSignals(answer = listOf(urgentSignal)), storage) { el, _ ->
            awaitUntil("the storage card") { el.storageCards() == 1 }
            awaitUntil("the signal card") { el.querySelectorAll("a.lapis-signal-card").length == 1 }
            val section = assertNotNull(el.querySelector(".lapis-dashboard-signals-section"), "no section: ${el.innerHTML}") as HTMLElement
            val firstCard = assertNotNull(section.querySelector(".lapis-signal-card"), "no card: ${section.innerHTML}") as HTMLElement
            assertTrue(firstCard.classList.contains("lapis-storage-card"), "the storage card comes before the signal cards")
            assertEquals("status", firstCard.getAttribute("role"))
            assertFalse(firstCard.classList.contains("bg-warning-subtle"), "hint: no warning colours")
            assertTrue(firstCard.textContent.orEmpty().contains("Speicherplatz wird knapp"))
            val icon = assertNotNull(firstCard.querySelector(".fas"), "no icon: ${firstCard.outerHTML}")
            assertEquals("true", icon.getAttribute("aria-hidden"))
            val link = assertNotNull(firstCard.querySelector("a"), "no link: ${firstCard.outerHTML}")
            assertEquals("#${Routes.DOCUMENTS}", link.getAttribute("href"))
            assertEquals(1, storage.calls)
        }
    }

    @Test
    fun warningCard_carriesTheWarningClasses_andTheNumbers(): Promise<Unit> =
        run(
            "storage-warning",
            AccountRole.ADMIN,
            FakeSignals(answer = emptyList()),
            FakeStorage { dto(StorageSpaceLevel.WARNING) },
        ) { el, _ ->
            awaitUntil("the storage card") { el.storageCards() == 1 }
            val card = assertNotNull(el.querySelector(".lapis-storage-card")) as HTMLElement
            assertTrue(card.classList.contains("bg-warning-subtle"))
            val text = card.textContent.orEmpty()
            assertTrue(text.contains("Speicherplatz fast voll"), text)
            assertTrue(text.contains("18,4 GB"), text)
            assertTrue(text.contains("1,0 TB"), text)
            assertTrue(text.contains("1 %"), text)
        }

    private fun noCard(
        id: String,
        storage: StorageSpaceRpc,
    ): Promise<Unit> =
        run(id, AccountRole.ADMIN, FakeSignals(answer = emptyList()), storage) { el, _ ->
            awaitUntil("the empty sentence") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
            assertEquals(0, el.storageCards())
            assertNull(kotlinx.browser.document.querySelector(".toast, .lapis-toast"), "no toast")
        }

    @Test
    fun infoShowsNoCard(): Promise<Unit> = noCard("storage-info", FakeStorage { dto(StorageSpaceLevel.INFO) })

    @Test
    fun unknownShowsNoCard(): Promise<Unit> = noCard("storage-unknown", FakeStorage { dto(StorageSpaceLevel.UNKNOWN) })

    @Test
    fun anRpcFailureShowsNoCardAndNoToast(): Promise<Unit> = noCard("storage-failure", FakeStorage { error("boom") })

    private fun noRpcFor(
        id: String,
        role: AccountRole,
    ): Promise<Unit> {
        val storage = FakeStorage { dto(StorageSpaceLevel.WARNING) }
        return run(id, role, FakeSignals(answer = emptyList()), storage) { el, _ ->
            awaitUntil("the empty sentence") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
            delay(100)
            assertEquals(0, el.storageCards())
            assertEquals(0, storage.calls)
        }
    }

    @Test
    fun memberGetsNoCard_andTheRpcIsNeverCalled(): Promise<Unit> = noRpcFor("storage-member", AccountRole.MEMBER)

    @Test
    fun boardGetsNoCard_andTheRpcIsNeverCalled(): Promise<Unit> = noRpcFor("storage-board", AccountRole.BOARD)

    @Test
    fun treasurerGetsNoCard_andTheRpcIsNeverCalled(): Promise<Unit> = noRpcFor("storage-treasurer", AccountRole.TREASURER)

    @Test
    fun emptySentenceStaysHidden_signalsFirst(): Promise<Unit> =
        run(
            "storage-order-a",
            AccountRole.ADMIN,
            FakeSignals(delayMs = 20, answer = emptyList()),
            FakeStorage(delayMs = 150) { dto(StorageSpaceLevel.HINT) },
        ) { el, _ ->
            awaitUntil("the storage card") { el.storageCards() == 1 }
            delay(50)
            assertFalse(el.textContent.orEmpty().contains("nichts Neues"), "signals first, storage later")
        }

    @Test
    fun emptySentenceStaysHidden_storageFirst(): Promise<Unit> =
        run(
            "storage-order-b",
            AccountRole.ADMIN,
            FakeSignals(delayMs = 150, answer = emptyList()),
            FakeStorage(delayMs = 20) { dto(StorageSpaceLevel.HINT) },
        ) { el, _ ->
            awaitUntil("the storage card") { el.storageCards() == 1 }
            delay(250)
            assertFalse(el.textContent.orEmpty().contains("nichts Neues"), "storage first, signals later")
        }

    @Test
    fun noPolling_aVisibilityReloadDoesNotAskTheStorageAgain(): Promise<Unit> {
        val storage = FakeStorage { dto(StorageSpaceLevel.HINT) }
        return run("storage-no-poll", AccountRole.ADMIN, FakeSignals(answer = listOf(urgentSignal)), storage) { el, visibility ->
            awaitUntil("the storage card") { el.storageCards() == 1 }
            visibility.now += 120_000
            visibility.fire()
            delay(100)
            assertEquals(1, storage.calls)
            assertEquals(1, el.storageCards())
        }
    }
}

package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IOpenItemService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.17 (Kleinrestpunkt V1.4.21): after an action or "Aktualisieren" the open-items list refetches to the depth the
 * user had scrolled to instead of falling back to the first page -- in a mounted screen with a fake `listOpenItems`.
 */
class OpenItemsScreenRefetchDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            AppState.setSession(
                SessionInfoDto(
                    memberId = "m-1",
                    displayName = "Test",
                    role = AccountRole.TREASURER,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                ),
            )
            try {
                block()
            } finally {
                closeOpenModals()
                AppState.setSession(null)
            }
        }

    /** `a` rows for "Alle" (no direction), `b` rows for the payable segment; the index is part of the UUID-shaped id. */
    private fun itemId(
        prefix: Char,
        index: Int,
    ) = "${prefix}0000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun item(
        id: String,
        direction: OpenItemDirection = OpenItemDirection.PAYABLE,
    ) = OpenItemDto(
        id = id,
        direction = direction,
        counterpartyName = "Gegenpartei $id",
        counterpartyKey = id,
        reference = null,
        itemDate = LocalDate(2026, 1, 1),
        dueDate = LocalDate(2026, 2, 1),
        amount = 100.0.toDecimal(),
        openAmount = 100.0.toDecimal(),
        contraAccountId = "acc-1",
        contraAccountNumber = "50000",
        contraAccountName = "Wareneinsatz",
        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        status = OpenItemStatus.OPEN,
        daysOverdue = 0,
        asOf = LocalDate(2026, 3, 1),
        createdByMemberId = "m-1",
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    /** What the fake server knows: rows per direction prefix, optionally with some rows removed (settled) for the refetch. */
    private class FakeServer(
        val total: Int,
    ) {
        var removed: Set<String> = emptySet()
        var failListCalls = false
        var delayListMs = 0
        val listCalls = mutableListOf<RecordedRequest>()
    }

    private fun HTMLElement.rows(): List<HTMLElement> =
        (0 until querySelectorAll("tr[data-item-id]").length).map {
            querySelectorAll("tr[data-item-id]").item(it) as HTMLElement
        }

    private fun HTMLElement.buttonWith(text: String): HTMLElement {
        val buttons = querySelectorAll("button")
        for (index in 0 until buttons.length) {
            val button = buttons.item(index) as HTMLElement
            if (button.textContent
                    .orEmpty()
                    .trim()
                    .startsWith(text)
            ) {
                return button
            }
        }
        throw AssertionError("no button \"$text\" in: $textContent")
    }

    private fun respond(
        server: FakeServer,
        listRoute: String,
        detailRoute: String,
    ): (RecordedRequest) -> StubResponse =
        { request ->
            when {
                !request.isRpc -> StubResponse()
                request.rpcRoute == listRoute -> {
                    server.listCalls += request
                    if (server.failListCalls) {
                        StubResponse(networkError = true)
                    } else {
                        val direction = request.rpcParam(0)
                        val prefix = if (direction == null) 'a' else 'b'
                        val limit = request.rpcParam(2) as Int
                        val afterId = request.rpcParam(4) as String?
                        val all = (0 until server.total).map { itemId(prefix, it) }.filter { it !in server.removed }
                        val start = if (afterId == null) 0 else all.indexOf(afterId) + 1
                        val page = all.drop(start).take(limit).map { item(it) }
                        val answer = request.answerWith(jsonOf(ListSerializer(OpenItemDto.serializer()), page))
                        StubResponse(text = answer.text, delayMs = server.delayListMs)
                    }
                }
                request.rpcRoute == detailRoute -> {
                    val id = request.rpcParam(0) as String
                    request.answerWith(jsonOf(ListSerializer(OpenItemDetailDto.serializer()), listOf(OpenItemDetailDto(item(id)))))
                }
                else -> request.answerWith("null")
            }
        }

    private suspend fun routes(): Pair<String, String> =
        routeOf { rpcService<IOpenItemService>().listOpenItems() } to
            routeOf { rpcService<IOpenItemService>().getOpenItem("x") }

    @Test
    fun refresh_afterThreeLoads_refetchesOneCallOfTheWholeDepth_andKeepsTheRows(): Promise<Unit> =
        test {
            val (listRoute, detailRoute) = routes()
            val server = FakeServer(total = 400)
            withFetchStub(respond = respond(server, listRoute, detailRoute)) { calls ->
                withMountedRoot("open-items-refetch-depth") { root, element ->
                    renderOpenItemsScreen(root)
                    awaitUntil("first page") { element().rows().size == 50 }
                    element().buttonWith("Mehr laden").click()
                    awaitUntil("second page") { element().rows().size == 100 }
                    element().buttonWith("Mehr laden").click()
                    awaitUntil("third page") { element().rows().size == 150 }
                    assertEquals(3, server.listCalls.size)

                    element().buttonWith("Aktualisieren").click()
                    awaitUntil("the refetch") { server.listCalls.size == 4 }
                    val refetch = server.listCalls.last()
                    assertEquals(150, refetch.rpcParam(2) as Int, "one call of the whole depth")
                    assertNull(refetch.rpcParam(4), "from the start, no cursor")
                    awaitUntil("150 rows again") { element().rows().size == 150 }
                    assertFalse(element().textContent.orEmpty().contains("Wird geladen …") && element().rows().isEmpty())
                    assertNull(element().querySelector("[aria-busy=\"true\"]"), "busy state is cleared")
                    assertTrue(calls.isNotEmpty())
                }
            }
        }

    @Test
    fun refresh_keepsTheSegment(): Promise<Unit> =
        test {
            val (listRoute, detailRoute) = routes()
            val server = FakeServer(total = 120)
            withFetchStub(respond = respond(server, listRoute, detailRoute)) {
                withMountedRoot("open-items-refetch-segment") { root, element ->
                    renderOpenItemsScreen(root)
                    awaitUntil("first page") { element().rows().size == 50 }
                    assertFalse(element().textContent.orEmpty().contains(KV_I18N_MARKER), "no raw marker in the counts line")
                    element().buttonWith("Kreditoren").click()
                    awaitUntil("payable page") {
                        element()
                            .rows()
                            .firstOrNull()
                            ?.getAttribute("data-item-id")
                            ?.startsWith("b") == true
                    }
                    element().buttonWith("Aktualisieren").click()
                    awaitUntil("the refetch") { server.listCalls.size == 3 }
                    assertEquals("PAYABLE", server.listCalls.last().rpcParam(0) as String, "the segment survives a refresh")
                    awaitUntil("rows again") { element().rows().size == 50 }
                    assertTrue(element().rows().all { it.getAttribute("data-item-id")!!.startsWith("b") })
                }
            }
        }

    @Test
    fun aStaleRefetch_isDiscarded_whenANewerLoadTookOver(): Promise<Unit> =
        test {
            val (listRoute, detailRoute) = routes()
            val server = FakeServer(total = 400)
            withFetchStub(respond = respond(server, listRoute, detailRoute)) {
                withMountedRoot("open-items-refetch-stale") { root, element ->
                    renderOpenItemsScreen(root)
                    awaitUntil("first page") { element().rows().size == 50 }
                    element().buttonWith("Mehr laden").click()
                    awaitUntil("second page") { element().rows().size == 100 }
                    server.delayListMs = 400
                    element().buttonWith("Aktualisieren").click() // refetch to 100 rows, answered late
                    awaitUntil("the refetch was sent") { server.listCalls.size == 3 }
                    server.delayListMs = 0
                    element().buttonWith("Kreditoren").click() // a reset supersedes it
                    awaitUntil("payable page 1") {
                        element()
                            .rows()
                            .firstOrNull()
                            ?.getAttribute("data-item-id")
                            ?.startsWith("b") == true
                    }
                    assertNull(
                        element().querySelector("[aria-busy=\"true\"]"),
                        "a reset that supersedes a running refetch clears the busy state",
                    )
                    assertNull(element().querySelector(".lapis-busy"), "and the dimming class")
                    delay(700) // the stale answer arrives now
                    assertNull(element().querySelector("[aria-busy=\"true\"]"), "still not busy after the stale answer")
                    assertNull(element().querySelector(".lapis-busy"))
                    assertEquals(50, element().rows().size, "the stale refetch must not overwrite the reset")
                    assertTrue(element().rows().all { it.getAttribute("data-item-id")!!.startsWith("b") })
                }
            }
        }

    @Test
    fun aFailingRefetch_keepsTheOldRows_andShowsNoErrorState(): Promise<Unit> =
        test {
            val (listRoute, detailRoute) = routes()
            val server = FakeServer(total = 400)
            withFetchStub(respond = respond(server, listRoute, detailRoute)) {
                withMountedRoot("open-items-refetch-error") { root, element ->
                    renderOpenItemsScreen(root)
                    awaitUntil("first page") { element().rows().size == 50 }
                    element().buttonWith("Mehr laden").click()
                    awaitUntil("second page") { element().rows().size == 100 }
                    server.failListCalls = true
                    element().buttonWith("Aktualisieren").click()
                    awaitUntil("the failing refetch was sent") { server.listCalls.size == 3 }
                    delay(300)
                    assertEquals(100, element().rows().size, "the old rows stay")
                    assertFalse(element().textContent.orEmpty().contains("Erneut versuchen"), "no error state replaces the list")
                    assertNull(element().querySelector("[aria-busy=\"true\"]"))
                }
            }
        }

    @Test
    fun aSelectedItemThatDroppedOut_closesTheDetail_andMovesTheFocusToTheNeighbour(): Promise<Unit> =
        test {
            val (listRoute, detailRoute) = routes()
            val server = FakeServer(total = 60)
            val selected = itemId('a', 2)
            withFetchStub(respond = respond(server, listRoute, detailRoute)) {
                withMountedRoot("open-items-refetch-focus") { root, element ->
                    renderOpenItemsScreen(root, selectedItemId = selected)
                    awaitUntil("first page") { element().rows().size == 50 }
                    awaitUntil("the detail") {
                        element().textContent.orEmpty().contains("Gegenpartei $selected") &&
                            element().querySelector("h2.h5") != null
                    }
                    server.removed = setOf(selected) // settled in the meantime
                    element().buttonWith("Aktualisieren").click()
                    awaitUntil("the list without the item") {
                        element().rows().none { it.getAttribute("data-item-id") == selected } &&
                            element().rows().size == 50
                    }
                    awaitUntil("detail closed") { element().textContent.orEmpty().contains("Posten oben auswählen, um Details zu sehen.") }
                    val active = kotlinx.browser.document.activeElement as? HTMLElement
                    assertNotNull(active, "something has the focus")
                    assertEquals(itemId('a', 3), active.getAttribute("data-item-id"), "the row that took the old index")
                }
            }
        }

    @Test
    fun aSelectedItemThatStays_isReopened(): Promise<Unit> =
        test {
            val (listRoute, detailRoute) = routes()
            val server = FakeServer(total = 10)
            val selected = itemId('a', 1)
            withFetchStub(respond = respond(server, listRoute, detailRoute)) { calls ->
                withMountedRoot("open-items-refetch-reopen") { root, element ->
                    renderOpenItemsScreen(root, selectedItemId = selected)
                    awaitUntil("rows") { element().rows().size == 10 }
                    awaitUntil("the detail") { calls.count { it.isRpc && it.rpcRoute == detailRoute } >= 1 }
                    val detailCallsBefore = calls.count { it.isRpc && it.rpcRoute == detailRoute }
                    element().buttonWith("Aktualisieren").click()
                    awaitUntil("the detail is read again") { calls.count { it.isRpc && it.rpcRoute == detailRoute } > detailCallsBefore }
                    assertFalse(
                        element().textContent.orEmpty().contains("Posten oben auswählen, um Details zu sehen."),
                        "the detail stays open",
                    )
                }
            }
        }

    @Test
    fun theFirstLoad_stillShowsThePlaceholder(): Promise<Unit> =
        test {
            val (listRoute, detailRoute) = routes()
            val server = FakeServer(total = 10)
            server.delayListMs = 300
            withFetchStub(respond = respond(server, listRoute, detailRoute)) {
                withMountedRoot("open-items-refetch-first") { root, element ->
                    renderOpenItemsScreen(root)
                    assertTrue(element().textContent.orEmpty().contains("Wird geladen …"))
                    awaitUntil("rows") { element().rows().size == 10 }
                }
            }
        }
}

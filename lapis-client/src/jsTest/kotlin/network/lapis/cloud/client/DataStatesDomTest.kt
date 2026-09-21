package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPostalMailService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.31 (W5, R34/R41): the screens that moved their load into the shared state region show four distinct states --
 * loading (a `role="status"` text), error (`alert-danger` with ONE fixed sentence and "Erneut versuchen"), empty (its own text) and
 * content -- and the retry asks the server exactly ONCE more. Every test drives the real screen in a mounted root against a stubbed
 * `window.fetch`; the route of the load under test is learned with [routeOf], so a renamed service method breaks the build.
 */
class DataStatesDomTest {
    @AfterTest
    fun reset() {
        AppState.setSession(null)
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Testperson",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2026, 9, 8, 12, 0),
        )

    private fun HTMLElement.retryButton(): HTMLElement? =
        (0 until querySelectorAll("button").length)
            .map { querySelectorAll("button").item(it) as HTMLElement }
            .firstOrNull { it.textContent?.trim() == "Erneut versuchen" }

    private fun HTMLElement.hasErrorBox(): Boolean = querySelector(".alert-danger[role=alert]") != null

    /**
     * Fails every call of [route] while [failing] is true, answers it with [okJson] afterwards, and answers every other RPC with `null`.
     */
    private fun answerer(
        route: String,
        failing: () -> Boolean,
        okJson: String,
        settingsRoute: String = "",
        settingsJson: String = "null",
    ): (RecordedRequest) -> StubResponse =
        { request ->
            when {
                !request.isRpc -> StubResponse()
                // the postal screen also asks for the organization settings (banner): answer them, an unknown state is an error box now
                request.rpcRoute == settingsRoute -> request.answerWith(settingsJson)
                request.rpcRoute != route -> rpcResult(request.json.id as Int, "null")
                failing() -> StubResponse(networkError = true)
                else -> rpcResult(request.json.id as Int, okJson)
            }
        }

    @Test
    fun postalMailLog_loadingThenErrorThenRetryThenEmpty(): Promise<Unit> =
        test {
            val route = routeOf { rpcService<IPostalMailService>().listPostalDeliveryLog() }
            val settingsRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val settingsJson =
                jsonOf(
                    OrganizationSettingsDto.serializer(),
                    OrganizationSettingsDto("o1", "Verein", null, null, null, null, null, null, null, null, postalMailEnabled = true),
                )
            var failing = true
            withFetchStub(answerer(route, { failing }, "[]", settingsRoute, settingsJson)) { calls ->
                withMountedRoot("ds-postal") { root, element ->
                    renderPostalMailScreen(root)
                    assertEquals("Wird geladen …", element().querySelector("[role=status]")?.textContent, "loading state")
                    awaitUntil("error state") { element().hasErrorBox() }
                    assertEquals(1, calls.count { it.isRpc && it.rpcRoute == route })
                    val error = element().querySelector(".alert-danger")!!.textContent.orEmpty()
                    assertEquals("Die Daten konnten nicht geladen werden.Erneut versuchen", error, "one fixed sentence, no e.message")
                    failing = false
                    element().retryButton()!!.click()
                    awaitUntil("empty state after the retry") {
                        element().textContent.orEmpty().contains("Noch keine postalischen Versandvorgänge protokolliert.")
                    }
                    assertEquals(2, calls.count { it.isRpc && it.rpcRoute == route }, "the retry asked exactly once more")
                    assertNull(element().querySelector(".alert-danger"), "the error box is gone after a successful reload")
                }
            }
        }

    @Test
    fun eventRoomsList_errorHasARetry_andEmptyIsItsOwnText(): Promise<Unit> =
        test {
            val route = routeOf { rpcService<IEventRoomService>().listRooms(includeInactive = true) }
            var failing = true
            withFetchStub(answerer(route, { failing }, "[]")) { calls ->
                withMountedRoot("ds-rooms") { root, element ->
                    renderEventRoomsScreen(root)
                    awaitUntil("error state") { element().hasErrorBox() }
                    assertTrue(!element().textContent.orEmpty().contains("Noch keine Räume angelegt."), "an error is not an empty list")
                    failing = false
                    element().retryButton()!!.click()
                    awaitUntil("empty state") { element().textContent.orEmpty().contains("Noch keine Räume angelegt.") }
                    assertEquals(2, calls.count { it.isRpc && it.rpcRoute == route })
                }
            }
        }

    @Test
    fun cateringAndVolunteerShifts_eventListFailureShowsAnErrorWithRetry(): Promise<Unit> =
        test {
            AppState.setSession(session())
            val route =
                routeOf {
                    rpcService<IEventService>().listEvents(
                        network.lapis.cloud.shared.domain
                            .EventQuery(limit = 1),
                    )
                }
            listOf<Pair<String, (io.kvision.panel.Root) -> Unit>>(
                "ds-catering" to { renderCateringScreen(it) },
                "ds-shifts" to { renderMyVolunteerShiftsScreen(it) },
            ).forEach { (id, render) ->
                withFetchStub(answerer(route, { true }, "null")) { calls ->
                    withMountedRoot(id) { root, element ->
                        render(root)
                        awaitUntil("$id: error state") { element().hasErrorBox() }
                        assertNotNull(element().retryButton(), "$id: retry button")
                        element().retryButton()!!.click()
                        awaitUntil("$id: the retry asked again") { calls.count { it.isRpc && it.rpcRoute == route } == 2 }
                    }
                }
            }
        }

    @Test
    fun ltrBalanceStrip_failureIsAnErrorState_notADash(): Promise<Unit> =
        test {
            val route = routeOf { rpcService<ILtrLedgerService>().getMyBalance() }
            var failing = true
            var reported: String? = "unset"
            withFetchStub(answerer(route, { failing }, "null")) { calls ->
                withMountedRoot("ds-ltr") { root, element ->
                    root.renderMyLtrBalanceInline { reported = it?.toString() }
                    awaitUntil("error state") { element().hasErrorBox() }
                    assertNull(reported, "onLoaded(null) on a failed load (contract unchanged)")
                    assertTrue(!element().textContent.orEmpty().contains("--"), "no bare dash for a failed load")
                    failing = false
                    element().retryButton()!!.click()
                    awaitUntil("second load") { calls.count { it.isRpc && it.rpcRoute == route } == 2 }
                }
            }
        }
}

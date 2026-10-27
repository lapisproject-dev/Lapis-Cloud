package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMailingService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.231 -- "Mailinglisten": subscription table with icon-only actions, the board-only "Verwalten" tab, deep link fallback. */
class MailingListsScreenDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun list(
        id: String,
        name: String,
        subscribed: Boolean,
    ) = MailingListDto(
        id = id,
        name = name,
        description = null,
        createdBy = "caller-1",
        subscriberCount = 4,
        isSubscribedByCurrentMember = subscribed,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.tabs(): List<HTMLElement> = allOf("[role=tab]")

    private inline fun withScreen(
        role: AccountRole,
        hash: String,
        id: String,
        crossinline block: suspend (() -> HTMLElement, List<RecordedRequest>, List<String>) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            val lists = routeOf { rpcService<IMailingService>().listMailingLists() }
            val rows = mutableListOf(list("a", "Rundbrief", true), list("b", "Newsletter", false))
            val subscribe = routeOf { rpcService<IMailingService>().subscribe("x") }
            val unsubscribe = routeOf { rpcService<IMailingService>().unsubscribe("x") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == lists -> request.answerWith(jsonOf(ListSerializer(MailingListDto.serializer()), rows.toList()))
                        request.rpcRoute == subscribe || request.rpcRoute == unsubscribe -> request.answerWith("null")
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm(id) { root, element ->
                    renderMailingListsScreen(root, hash)
                    block(element, calls, listOf(lists, subscribe, unsubscribe))
                }
            }
        }

    @Test
    fun aMember_getsNoTabBar_noManageTab_andTheDeepLinkFallsBackSilently(): Promise<Unit> =
        withScreen(AccountRole.MEMBER, "#/mailing-lists?tab=manage", "ml-member") { el, _, _ ->
            awaitUntil("the table") { el().shows("Rundbrief") }
            assertEquals(0, el().tabs().size)
            assertFalse(el().shows("Mailinglisten verwalten"))
            assertFalse(el().shows("Neue Mailingliste"))
            assertEquals(0, el().allOf(".toast").size, "no message for the fallback")
        }

    @Test
    fun aTreasurer_isTreatedLikeAMember(): Promise<Unit> =
        withScreen(AccountRole.TREASURER, "#/mailing-lists?tab=manage", "ml-treasurer") { el, _, _ ->
            awaitUntil("the table") { el().shows("Rundbrief") }
            assertEquals(0, el().tabs().size)
            assertFalse(el().shows("Mailinglisten verwalten"))
        }

    @Test
    fun theTable_hasStatusBadge_andIconOnlyActionsWithAccessibleNames(): Promise<Unit> =
        withScreen(AccountRole.MEMBER, "#/mailing-lists", "ml-table") { el, _, _ ->
            awaitUntil("the table") { el().shows("Rundbrief") }
            assertEquals(listOf("Liste", "Status", "Aktionen"), el().allOf("thead th").map { it.textContent?.trim().orEmpty() })
            assertEquals(1, el().allOf(".badge").count { it.textContent?.trim() == "Abonniert" })
            val actions = el().allOf("tbody button")
            assertTrue(actions.all { it.textContent?.trim().isNullOrEmpty() }, "icon only")
            assertTrue(actions.all { it.getAttribute("title") == it.getAttribute("aria-label") && it.getAttribute("aria-label") != null })
            assertEquals(
                listOf("Abbestellen", "Einstellungen", "Abonnieren"),
                actions.map { it.getAttribute("aria-label") },
                "settings only for the subscribed list",
            )
            assertTrue(el().shows("Rundbrief (4 Abonnenten)"), "the subscriber count is kept")
        }

    @Test
    fun subscribingAndUnsubscribing_callTheRightRpc_andReload(): Promise<Unit> =
        withScreen(AccountRole.MEMBER, "#/mailing-lists", "ml-toggle") { el, calls, routes ->
            awaitUntil("the table") { el().shows("Newsletter") }
            val lists = routes[0]
            val before = calls.toRoute(lists).size
            el().iconButtonNamed("Abonnieren").click()
            val subscribe = routes[1]
            awaitUntil("subscribe call") { calls.toRoute(subscribe).size == 1 }
            assertEquals("b", calls.singleCall(subscribe).rpcParam(0) as String)
            awaitUntil("reloaded") { calls.toRoute(lists).size > before }
            val unsubscribe = routes[2]
            el().iconButtonNamed("Abbestellen").click()
            awaitUntil("unsubscribe call") { calls.toRoute(unsubscribe).size == 1 }
            assertEquals("a", calls.singleCall(unsubscribe).rpcParam(0) as String)
        }

    @Test
    fun settingsToggle_opensTheTrackingSwitches_withAriaExpanded(): Promise<Unit> =
        withScreen(AccountRole.MEMBER, "#/mailing-lists", "ml-settings") { el, _, _ ->
            awaitUntil("the table") { el().shows("Rundbrief") }
            val button = el().iconButtonNamed("Einstellungen")
            assertEquals("false", button.getAttribute("aria-expanded"))
            button.click()
            awaitUntil("expanded") { el().iconButtonNamed("Einstellungen").getAttribute("aria-expanded") == "true" }
            assertEquals(2, el().allOf("input[type=checkbox]").size)
        }

    @Test
    fun aBoardMember_seesBothTabs_manageOnlyWhenAsked(): Promise<Unit> =
        withScreen(AccountRole.BOARD, "#/mailing-lists", "ml-board") { el, _, _ ->
            awaitUntil("tabs") { el().tabs().size == 2 }
            assertEquals(listOf("Meine Abonnements", "Verwalten"), el().tabs().map { it.textContent?.trim() })
            assertEquals("true", el().tabs()[0].getAttribute("aria-selected"))
            awaitUntil("the table") { el().shows("Rundbrief") }
            assertEquals(0, el().createButtonLabels().size, "the board tab is built lazily")
            el().tabs()[1].click()
            awaitUntil("the admin section") { el().createButtonLabels() == listOf("Neue Mailingliste") }
        }

    @Test
    fun anAdmin_deepLinkToManage_opensTheBoardTab(): Promise<Unit> =
        withScreen(AccountRole.ADMIN, "#/mailing-lists?tab=manage", "ml-admin") { el, _, _ ->
            awaitUntil("the admin section") { el().createButtonLabels() == listOf("Neue Mailingliste") }
            assertEquals("true", el().tabs()[1].getAttribute("aria-selected"))
            assertNull(el().tabs()[0].getAttribute("aria-selected").takeIf { it == "true" })
        }
}

class ResolveMailingListsTabTest {
    @Test
    fun manage_onlyForTheBoardTier() {
        assertEquals(MailingListsTab.MANAGE, resolveMailingListsTab("manage", canManage = true))
        assertEquals(MailingListsTab.SUBSCRIPTIONS, resolveMailingListsTab("manage", canManage = false))
    }

    @Test
    fun everythingElse_fallsBackToTheSubscriptions() {
        assertEquals(MailingListsTab.SUBSCRIPTIONS, resolveMailingListsTab(null, canManage = true))
        assertEquals(MailingListsTab.SUBSCRIPTIONS, resolveMailingListsTab("subscriptions", canManage = true))
        assertEquals(MailingListsTab.SUBSCRIPTIONS, resolveMailingListsTab("<script>", canManage = true))
        assertEquals(MailingListsTab.SUBSCRIPTIONS, resolveMailingListsTab("", canManage = false))
    }

    @Test
    fun theHashCarriesOnlyTheTabSlug() {
        assertEquals("#/mailing-lists?tab=manage", mailingListsHash(MailingListsTab.MANAGE))
        assertEquals("#/mailing-lists?tab=subscriptions", mailingListsHash(MailingListsTab.SUBSCRIPTIONS))
    }
}

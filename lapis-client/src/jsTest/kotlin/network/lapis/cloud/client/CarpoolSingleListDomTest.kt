package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICarpoolService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.186 -- the Mitfahrzentrale shows ONE list: the feed, or with "Nur meine" the own entries (expired ones included); the "Art" filter
 * narrows both views; a guest gets no toolbar. The own-entry actions are icon buttons named after the route, the delete confirmation has a row
 * of its own, the open-request counter sits in the "Mitfahrer verwalten" button, and a card shows the tidied place with the original in `title`.
 */
class CarpoolSingleListDomTest {
    private fun session(status: MemberStatus) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = status,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun posting(
        id: String,
        own: Boolean = true,
        past: Boolean = false,
        type: CarpoolPostingType = CarpoolPostingType.OFFER,
        from: String = "Ort-$id",
        to: String = "Ziel-$id",
        openRequests: Int = 0,
    ) = CarpoolPostingDto(
        id = id,
        type = type,
        fromPlace = from,
        toPlace = to,
        departureDate = if (past) LocalDate(2020, 5, 1) else LocalDate(2099, 5, 1),
        departureTime = LocalTime(8, 30, 0),
        seatsOffered = if (type == CarpoolPostingType.OFFER) 3 else null,
        notes = null,
        authorDisplayName = "Vera Vorstand",
        isOwn = own,
        isPast = past,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        seatsFree = if (type == CarpoolPostingType.OFFER) 2 else null,
        openRequests = openRequests,
    )

    private class Routes(
        val mine: String,
        val feed: String,
    )

    /** Serves what the server does: the feed holds the own, not yet expired entries (and filters by type); `listMyPostings` all own ones. */
    private suspend fun withStub(
        mine: List<CarpoolPostingDto>,
        feed: List<CarpoolPostingDto>,
        block: suspend (List<RecordedRequest>, Routes) -> Unit,
    ) {
        val routes =
            Routes(
                mine = routeOf { rpcService<ICarpoolService>().listMyPostings() },
                feed = routeOf { rpcService<ICarpoolService>().listPostings(null) },
            )
        val serializer = ListSerializer(CarpoolPostingDto.serializer())
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == routes.mine -> request.answerWith(jsonOf(serializer, mine))
                    request.rpcRoute == routes.feed -> {
                        val wanted: dynamic = request.rpcParam(0)
                        val type = (wanted as? String)?.let { CarpoolPostingType.valueOf(it) }
                        request.answerWith(jsonOf(serializer, feed.filter { type == null || it.type == type }))
                    }
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, routes) }
    }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.badgeCount(text: String): Int = allOf(".badge").count { it.textContent.orEmpty().trim() == text }

    private fun HTMLElement.iconButton(name: String): HTMLElement =
        assertNotNull(allOf("button").firstOrNull { it.getAttribute("aria-label") == name }, "no icon button '$name'")

    private fun HTMLElement.onlyMine(): HTMLInputElement = controlOf("Nur meine") as HTMLInputElement

    @Test
    fun ownEntriesAppearOnceInTheOneList_andTheOldSectionIsGone(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            val one = posting("a")
            val two = posting("b")
            withStub(mine = listOf(one, two), feed = listOf(one, two)) { calls, routes ->
                mountedForm("carpool-single-list") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("both entries are shown") { element().shows("Ort-a") && element().shows("Ort-b") }
                    val screen = element()
                    assertEquals(1, Regex("Ort-a").findAll(screen.textContent.orEmpty()).count(), "listed once")
                    assertEquals(1, Regex("Ort-b").findAll(screen.textContent.orEmpty()).count(), "listed once")
                    assertEquals(2, screen.badgeCount("Eigener Eintrag"))
                    assertFalse(screen.shows("Meine Einträge"), "the second section is gone")
                    assertTrue(calls.toRoute(routes.mine).isEmpty(), "the own list is fetched only on demand")
                }
            }
        }

    @Test
    fun onlyMineSwitchesTheSource_andShowsTheExpiredOwnEntry(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            val live = posting("live")
            val expired = posting("old", past = true)
            withStub(mine = listOf(live, expired), feed = listOf(live)) { calls, routes ->
                mountedForm("carpool-only-mine") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the feed is shown") { element().shows("Ort-live") }
                    val screen = element()
                    assertFalse(screen.shows("Ort-old"), "an expired entry is not in the feed")
                    screen.onlyMine().click()
                    awaitUntil("the own list is shown") { screen.shows("Ort-old") }
                    assertTrue(screen.shows("Abgelaufen"))
                    assertEquals(1, calls.toRoute(routes.mine).size)
                    screen.onlyMine().click()
                    awaitUntil("back to the feed") { !screen.shows("Ort-old") && screen.shows("Ort-live") }
                }
            }
        }

    @Test
    fun theTypeFilterNarrowsBothViews(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            val offer = posting("offer")
            val request = posting("request", type = CarpoolPostingType.REQUEST)
            val others = posting("other", own = false)
            withStub(mine = listOf(offer, request), feed = listOf(offer, request, others)) { _, _ ->
                mountedForm("carpool-type-filter") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the feed is shown") { element().shows("Ort-other") }
                    val screen = element()
                    screen.chooseIn("Art", "REQUEST")
                    awaitUntil("only requests in the feed") { screen.shows("Ort-request") && !screen.shows("Ort-offer") }
                    assertFalse(screen.shows("Ort-other"))

                    screen.onlyMine().click()
                    awaitUntil("own view, still only requests") { screen.shows("Ort-request") && !screen.shows("Ort-offer") }
                    screen.chooseIn("Art", "OFFER")
                    awaitUntil("own view, only offers") { screen.shows("Ort-offer") && !screen.shows("Ort-request") }
                    screen.chooseIn("Art", "REQUEST")
                    awaitUntil("own requests again") { screen.shows("Ort-request") }
                }
            }
        }

    @Test
    fun onlyMineWithNothingOwnedSaysSo(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            withStub(mine = emptyList(), feed = listOf(posting("x", own = false))) { _, _ ->
                mountedForm("carpool-only-mine-empty") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the feed is shown") { element().shows("Ort-x") }
                    element().onlyMine().click()
                    awaitUntil("the empty text") { element().shows("Noch keine eigenen Einträge.") }
                    assertFalse(element().shows("Ort-x"))
                }
            }
        }

    @Test
    fun aGuestSeesNeitherTheCheckboxNorTheTypeSelect(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.GUEST))
            withStub(mine = emptyList(), feed = listOf(posting("g", own = false))) { calls, routes ->
                mountedForm("carpool-single-guest") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the feed is shown") { element().shows("Ort-g") }
                    assertTrue(element().allOf("select").isEmpty())
                    assertTrue(element().allOf("input[type='checkbox']").isEmpty())
                    assertFalse(element().shows("Nur meine"))
                    assertTrue(calls.toRoute(routes.mine).isEmpty())
                }
            }
        }

    @Test
    fun ownActionsAreIconButtonsNamedAfterTheRoute_andDeleteConfirmsInItsOwnRow(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            val own = posting("a", from = "Braunschweig", to = "Hannover")
            withStub(mine = listOf(own), feed = listOf(own)) { _, _ ->
                mountedForm("carpool-icon-actions") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the entry is shown") { element().shows("Braunschweig") }
                    val screen = element()
                    val names =
                        listOf(
                            "Fahrt Braunschweig nach Hannover bearbeiten",
                            "Fahrt Braunschweig nach Hannover duplizieren",
                            "Fahrt Braunschweig nach Hannover löschen",
                        )
                    names.forEach { name ->
                        val button = screen.iconButton(name)
                        assertEquals(name, button.getAttribute("title"), "title equals the accessible name")
                        assertFalse(name.contains(KV_MARKER))
                        assertEquals("", button.textContent.orEmpty().trim(), "icon only: no visible text")
                    }
                    listOf("Bearbeiten", "Duplizieren", "Löschen").forEach { word ->
                        assertTrue(
                            screen.allOf("button").none { it.textContent.orEmpty().trim() == word },
                            "no visible text button '$word'",
                        )
                    }
                    screen.iconButton(names[2]).click()
                    awaitUntil("the confirmation") { screen.shows("Wirklich löschen?") }
                    assertTrue(screen.allOf("button").any { it.textContent.orEmpty().trim() == "Endgültig löschen" })
                    assertTrue(screen.allOf("button").any { it.textContent.orEmpty().trim() == "Zurück" })
                    screen.buttonNamed("Zurück").click()
                    awaitUntil("the confirmation folds away") { !screen.shows("Wirklich löschen?") }
                }
            }
        }

    @Test
    fun theCardShowsTheTidiedPlace_andTheOriginalInTheTitle(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            val own = posting("a", from = "06217 MERSEBURG (SAALE), DEUTSCHLAND", to = "HANNOVER")
            withStub(mine = listOf(own), feed = listOf(own)) { _, _ ->
                mountedForm("carpool-place-format") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the entry is shown") { element().shows("Merseburg (Saale) → Hannover") }
                    val title = assertNotNull(element().allOf("[title]").firstOrNull { it.shows("Merseburg (Saale)") }, "title carrier")
                    assertEquals("06217 MERSEBURG (SAALE), DEUTSCHLAND → HANNOVER", title.getAttribute("title"))
                    assertFalse(element().shows("06217"), "the postal code is not shown")
                }
            }
        }

    @Test
    fun theOpenRequestCounterSitsInTheCompactManageButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(MemberStatus.ACTIVE))
            val own = posting("a", openRequests = 2)
            withStub(mine = listOf(own), feed = listOf(own)) { _, _ ->
                mountedForm("carpool-counter") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the entry is shown") { element().shows("Ort-a") }
                    val screen = element()
                    val manage = screen.allOf("button").first { it.shows("Mitfahrer verwalten") }
                    assertTrue(manage.shows("2 offene Anfragen"))
                    assertEquals("Mitfahrer verwalten, 2 offene Anfragen", manage.getAttribute("aria-label"))
                    assertTrue(manage.classList.contains("btn-sm"))
                    assertFalse(manage.classList.contains("w-100"))
                    assertEquals(1, screen.allOf(".badge").count { it.shows("offene Anfragen") }, "no second badge in the card header")
                }
            }
        }
}

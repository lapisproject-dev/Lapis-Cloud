package network.lapis.cloud.client

import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.domain.NoticePageDto
import network.lapis.cloud.shared.domain.NoticeStatus
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.INoticeBoardService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.297 -- the bulletin board in the DOM: the category switch with `aria-pressed`, the pinned area, the weight pill (only from 0.01), the empty
 * state, who gets which buttons, the expiry hint, the deep link of the LTR statement and "Meine Aushänge" with the removal reason. Every test
 * stubs `fetch` and removes what it mounted ([formTest]).
 */
class NoticeBoardScreenDomTest {
    private class Routes(
        val list: String,
        val mine: String,
        val get: String,
        val balance: String,
    )

    private suspend fun withStub(
        page: (category: String?) -> NoticePageDto,
        mine: List<NoticeDto> = emptyList(),
        single: NoticeDto? = null,
        block: suspend (List<RecordedRequest>, Routes) -> Unit,
    ) {
        val routes =
            Routes(
                list = routeOf { rpcService<INoticeBoardService>().listNotices() },
                mine = routeOf { rpcService<INoticeBoardService>().listMyNotices() },
                get = routeOf { rpcService<INoticeBoardService>().getNotice("x") },
                balance = routeOf { rpcService<ILtrLedgerService>().getMyBalance() },
            )
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == routes.list -> {
                        val category: dynamic = request.rpcParam(0).category
                        request.answerWith(jsonOf(NoticePageDto.serializer(), page(category as? String)))
                    }
                    request.rpcRoute == routes.mine -> request.answerWith(jsonOf(ListSerializer(NoticeDto.serializer()), mine))
                    request.rpcRoute == routes.get ->
                        if (single != null) {
                            request.answerWith(jsonOf(NoticeDto.serializer(), single))
                        } else {
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.NotFoundException")
                        }
                    request.rpcRoute == routes.balance -> request.answerWith(jsonOf(LtrLedgerBalanceDto.serializer(), balanceDto("12.50")))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, routes) }
    }

    @Test
    fun theCategorySwitch_marksOnePressedButton_andFiltersTheBoard(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(
                page = { category ->
                    val all = listOf(noticeDto("s", category = NoticeCategory.SEARCH), noticeDto("o", category = NoticeCategory.OFFER))
                    noticePage(items = all.filter { category == null || it.category.name == category })
                },
            ) { calls, routes ->
                mountedForm("notice-board-filter") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("both notices are shown") { element().shows("Titel-s") && element().shows("Titel-o") }
                    val switchButtons = element().allOf("[role='group'] button")
                    assertEquals(5, switchButtons.size, "Alle plus the four categories")
                    assertEquals("true", element().buttonNamed("Alle").getAttribute("aria-pressed"))
                    assertEquals("false", element().buttonNamed("Suche").getAttribute("aria-pressed"))
                    element().buttonNamed("Suche").click()
                    awaitUntil("only the search is shown") { element().shows("Titel-s") && !element().shows("Titel-o") }
                    assertEquals("true", element().buttonNamed("Suche").getAttribute("aria-pressed"))
                    assertEquals("false", element().buttonNamed("Alle").getAttribute("aria-pressed"))
                    assertEquals(
                        "SEARCH",
                        calls
                            .toRoute(routes.list)
                            .last()
                            .rpcParam(0)
                            .category as String,
                    )
                }
            }
        }

    @Test
    fun pinnedNoticesStandInTheirOwnArea_andTheAreaIsAbsentWithoutAny(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            var withPinned = true
            withStub(
                page = {
                    if (withPinned) {
                        noticePage(
                            items = listOf(noticeDto("n1")),
                            pinned = listOf(noticeDto("p1", pinned = true)),
                        )
                    } else {
                        noticePage(items = listOf(noticeDto("n1")))
                    }
                },
            ) { _, _ ->
                mountedForm("notice-board-pinned") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("the pinned area is shown") { element().shows("Titel-p1") }
                    val area = assertNotNull(element().allOf(".lapis-notice-pinned").singleOrNull(), "one pinned area")
                    assertTrue(area.shows("Vom Vorstand") && area.shows("Titel-p1") && !area.shows("Titel-n1"))
                }
                withPinned = false
                mountedForm("notice-board-unpinned") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("the board is shown") { element().shows("Titel-n1") }
                    assertEquals(0, element().allOf(".lapis-notice-pinned").size)
                }
            }
        }

    @Test
    fun theWeightPillAppearsFromOneCent_andIsAbsentForANoticeWithoutAStake(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(
                page = { noticePage(items = listOf(noticeDto("heavy", weight = "2.00"), noticeDto("plain", weight = "0.00"))) },
            ) { _, _ ->
                mountedForm("notice-board-weight") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("both cards") { element().shows("Titel-heavy") && element().shows("Titel-plain") }
                    val cards = element().allOf(".lapis-notice-card")
                    val heavy = cards.single { it.shows("Titel-heavy") }
                    val plain = cards.single { it.shows("Titel-plain") }
                    assertTrue(heavy.shows("Gewicht"), "a notice with a stake shows its weight")
                    assertFalse(plain.shows("Gewicht"), "no weight pill at 0.00")
                }
            }
        }

    @Test
    fun theEmptyStateExplainsWhatToDo(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(page = { noticePage() }) { _, _ ->
                mountedForm("notice-board-empty") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("the empty text") {
                        element().shows(
                            "Noch keine Aushänge. Suchen Sie etwas oder bieten Sie etwas an? Hängen Sie den ersten Aushang aus.",
                        )
                    }
                }
            }
        }

    @Test
    fun theReplyButtonIsMissingWhenReplyingIsNotPossible_andModerationButtonsAreForTheBoardOnly(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession(role = AccountRole.BOARD))
            withStub(
                page = {
                    noticePage(
                        items =
                            listOf(
                                noticeDto("blocked", canReply = false, canModerate = true),
                                noticeDto("open", canModerate = true),
                                noticeDto("own", mine = true, canModerate = true),
                            ),
                    )
                },
            ) { _, _ ->
                mountedForm("notice-board-actions") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("three cards") { element().allOf(".lapis-notice-card").size == 3 }

                    fun card(id: String) = element().allOf(".lapis-notice-card").single { it.shows("Titel-$id") }
                    assertNull(card("blocked").buttonLike("Antworten"), "no reply button when canReply is false")
                    assertNotNull(card("open").buttonLike("Antworten"))
                    assertNull(card("own").buttonLike("Antworten"), "no reply to one's own notice")
                    assertNotNull(card("open").buttonLike("Pinnen"))
                    assertNotNull(card("open").buttonLike("Entfernen"))
                    assertNull(card("own").buttonLike("Entfernen"), "the board withdraws its own notice, it does not remove it")
                    assertNotNull(card("own").buttonLike("Zurückziehen"))
                    assertNotNull(card("own").buttonLike("Bearbeiten"))
                }
            }
        }

    @Test
    fun aPlainMemberGetsNoModerationButtons(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(page = { noticePage(items = listOf(noticeDto("a"))) }) { _, _ ->
                mountedForm("notice-board-plain") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("the card") { element().shows("Titel-a") }
                    assertNull(element().buttonLike("Pinnen"))
                    assertNull(element().buttonLike("Entfernen"))
                }
            }
        }

    @Test
    fun theExpiryHintNamesTheLastDay(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(page = { noticePage(items = listOf(noticeDto("last", daysLeft = 0), noticeDto("later", daysLeft = 5))) }) { _, _ ->
                mountedForm("notice-board-expiry") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("both cards") { element().shows("Titel-last") && element().shows("Titel-later") }
                    val cards = element().allOf(".lapis-notice-card")
                    assertTrue(cards.single { it.shows("Titel-last") }.shows("läuft heute ab"))
                    assertTrue(cards.single { it.shows("Titel-later") }.shows("sichtbar bis"))
                }
            }
        }

    @Test
    fun aLongTextIsFoldedAndUnfoldsOnClick(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            val long = "Sehr lang. ".repeat(40)
            withStub(page = { noticePage(items = listOf(noticeDto("long", body = long), noticeDto("short"))) }) { _, _ ->
                mountedForm("notice-board-fold") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("both cards") { element().shows("Titel-long") && element().shows("Titel-short") }
                    val cards = element().allOf(".lapis-notice-card")
                    val longCard = cards.single { it.shows("Titel-long") }
                    assertNull(cards.single { it.shows("Titel-short") }.buttonLike("Mehr anzeigen"), "a short text is not folded")
                    val body = longCard.allOf(".lapis-notice-body").single()
                    assertTrue(body.classList.contains("lapis-notice-body--folded"))
                    longCard.buttonNamed("Mehr anzeigen").click()
                    awaitUntil("unfolded") { !body.classList.contains("lapis-notice-body--folded") }
                    assertEquals("true", body.getAttribute("aria-expanded"))
                    assertNotNull(longCard.buttonLike("Weniger anzeigen"))
                }
            }
        }

    @Test
    fun titleAndTextAreShownAsTextNeverAsMarkup(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(
                page = {
                    noticePage(
                        items =
                            listOf(
                                noticeDto("xss", title = "<img src=x onerror=alert(1)>Titel", body = "<script>alert(2)</script>Text"),
                            ),
                    )
                },
            ) { _, _ ->
                mountedForm("notice-board-xss") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("the card") { element().shows("Titel") }
                    assertEquals(0, element().allOf("img[src='x']").size)
                    assertEquals(0, element().allOf("script").size)
                    assertTrue(element().shows("<script>alert(2)</script>Text"), "shown literally")
                }
            }
        }

    @Test
    fun myNoticesShowEveryStateWithTheRemovalReason(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            val removed =
                noticeDto("gone", mine = true, status = NoticeStatus.REMOVED)
                    .copy(removalReason = network.lapis.cloud.shared.domain.NoticeRemovalReason.RULES, removalNote = "Bitte keine Werbung.")
            val withdrawn = noticeDto("back", mine = true, status = NoticeStatus.WITHDRAWN)
            withStub(page = { noticePage() }, mine = listOf(removed, withdrawn)) { _, _ ->
                mountedForm("notice-board-mine") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("my notices") { element().shows("Titel-gone") && element().shows("Titel-back") }
                    val goneCard = element().allOf(".lapis-notice-card").single { it.shows("Titel-gone") }
                    assertTrue(goneCard.shows("Entfernt"))
                    assertTrue(goneCard.shows("Verstoß gegen die Regeln der Organisation"))
                    assertTrue(goneCard.shows("Bitte keine Werbung."))
                    assertTrue(element().allOf(".lapis-notice-card").single { it.shows("Titel-back") }.shows("Zurückgezogen"))
                }
            }
        }

    @Test
    fun theDeepLinkOfTheStatementShowsTheNotice_orACalmLineWhenItIsGone(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(page = { noticePage() }, single = noticeDto("linked", title = "Verlinkter Aushang")) { calls, routes ->
                mountedForm("notice-board-deeplink") { root, element ->
                    renderNoticeBoardScreen(root, "#/notice-board?notice=abc")
                    awaitUntil("the linked notice") { element().shows("Verlinkter Aushang") }
                    assertEquals("abc", calls.singleCall(routes.get).rpcParam(0) as String)
                }
            }
            withStub(page = { noticePage() }, single = null) { _, _ ->
                mountedForm("notice-board-deeplink-gone") { root, element ->
                    renderNoticeBoardScreen(root, "#/notice-board?notice=abc")
                    awaitUntil("the calm line") { element().shows("Dieser Aushang ist nicht mehr vorhanden.") }
                    assertFalse(element().shows("Verlinkter Aushang"))
                }
            }
        }

    @Test
    fun theCreateButtonOpensTheForm_withTheDefaultsOfTheBoard(): Promise<Unit> =
        formTest {
            AppState.setSession(noticeSession())
            withStub(page = { noticePage(items = listOf(noticeDto("a"))) }) { _, _ ->
                mountedForm("notice-board-create") { root, element ->
                    renderNoticeBoardScreen(root)
                    awaitUntil("the board") { element().shows("Titel-a") }
                    element().buttonNamed("Aushang aufgeben").click()
                    awaitUntil("the form") { element().shows("Neuer Aushang") }
                    assertTrue(element().shows("LTR-Einsatz (optional)"))
                    assertTrue(element().shows("Höchstens 2000 Zeichen."))
                    assertNotNull(element().allOf("label").firstOrNull { it.textContent.orEmpty().startsWith("Kategorie") })
                    val heading: HTMLElement = element()
                    assertTrue(heading.shows("Sichtbar bis (einschließlich)"))
                }
            }
        }
}

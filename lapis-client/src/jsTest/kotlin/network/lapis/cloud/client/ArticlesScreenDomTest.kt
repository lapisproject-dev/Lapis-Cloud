package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IArticleService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.50 -- the Artikel screen: "Neuer Artikel" is the ONE primary action in the title row (built once, not with every load of the list),
 * visible only in the tab "Meine Artikel" and never while the editor is open; the editor replaces the list; a tab change leaves an open
 * editor through the editor's own exit.
 */
class ArticlesScreenDomTest {
    private fun article(
        id: String,
        title: String,
        status: ArticleStatus = ArticleStatus.DRAFT,
    ) = ArticleDto(
        id = id,
        slug = null,
        title = title,
        excerpt = "Auszug $id",
        body = "Text $id",
        coverImageUrl = null,
        status = status,
        submittedAt = null,
        reviewedAt = null,
        rejectionReason = null,
        publishedAt = null,
        updatedAt = LocalDateTime(2026, 9, 28, 10, 0),
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.newButtons(): List<HTMLElement> = allOf("button").filter { it.textContent?.trim() == "Neuer Artikel" }

    /** The create button takes part in the layout (a hidden or removed button cannot be used by anyone). */
    private fun HTMLElement.newButtonShown(): Boolean = newButtons().any { it.isRendered() }

    private fun HTMLElement.headerSlotButtons(): List<String> = allOf(".lapis-page-action button").map { it.textContent.orEmpty().trim() }

    private suspend fun withArticleStub(
        mine: List<ArticleDto>,
        block: suspend (List<RecordedRequest>, String) -> Unit,
    ) {
        val listMine = routeOf { rpcService<IArticleService>().listMyArticles() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listMine -> request.answerWith(jsonOf(ListSerializer(ArticleDto.serializer()), mine))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, listMine) }
    }

    @Test
    fun theNewButtonIsOneButtonInTheTitleRow_builtOnce_notWithEveryLoad(): Promise<Unit> =
        formTest {
            withArticleStub(mine = listOf(article("a1", "Erster Entwurf"))) { calls, listMine ->
                mountedForm("r50-articles-header") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the list is shown") { element().shows("Erster Entwurf") }
                    val screen = element()
                    assertEquals(listOf("Neuer Artikel"), screen.headerSlotButtons(), "the one primary action sits in the title row")
                    assertEquals(1, screen.newButtons().size, "no second, list-bound button")
                    assertTrue(calls.toRoute(listMine).isNotEmpty())
                }
            }
        }

    @Test
    fun anEmptyList_keepsItsEmptyState_andStillOffersTheButtonInTheHeader(): Promise<Unit> =
        formTest {
            withArticleStub(mine = emptyList()) { _, _ ->
                mountedForm("r50-articles-empty") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the empty state") { element().shows("Noch keine Artikel.") }
                    assertEquals(1, element().newButtons().size)
                    assertTrue(element().newButtonShown())
                }
            }
        }

    @Test
    fun theEditorReplacesTheList_andTheButtonIsHiddenWhileItIsOpen(): Promise<Unit> =
        formTest {
            withArticleStub(mine = listOf(article("a1", "Erster Entwurf"))) { _, _ ->
                mountedForm("r50-articles-editor") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the list is shown") { element().shows("Erster Entwurf") }
                    val screen = element()
                    screen.newButtons().single().click()
                    awaitUntil("the editor is built") { screen.querySelector(".lapis-form") != null }
                    assertFalse(screen.newButtonShown(), "no create button while the editor is open")
                    assertFalse(screen.shows("Erster Entwurf") && screen.allOf(".lapis-card-list").any { it.isRendered() })

                    screen.buttonNamed("Zurück zur Liste").click()
                    awaitUntil("back on the list") { screen.querySelector(".lapis-form") == null && screen.shows("Erster Entwurf") }
                    assertTrue(screen.newButtonShown(), "the button is back with the list")
                    assertEquals(1, screen.newButtons().size, "still exactly one button")
                }
            }
        }

    @Test
    fun theButtonBelongsToTheTabMeineArtikel_onlyAndComesBack(): Promise<Unit> =
        formTest {
            AppState.setSession(
                SessionInfoDto(
                    memberId = "b1",
                    displayName = "Vera Vorstand",
                    role = AccountRole.BOARD,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                ),
            )
            withArticleStub(mine = emptyList()) { _, _ ->
                mountedForm("r50-articles-tabs") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the empty state") { element().shows("Noch keine Artikel.") }
                    val screen = element()
                    screen.buttonNamed("Freigabe").click()
                    awaitUntil("the review tab is shown") { screen.shows("Keine Artikel zur Freigabe.") }
                    assertFalse(screen.newButtonShown(), "no create button in the review tab")
                    screen.buttonNamed("Veröffentlicht").click()
                    awaitUntil("the published tab is shown") { screen.shows("Keine veröffentlichten Artikel.") }
                    assertFalse(screen.newButtonShown())
                    screen.buttonNamed("Meine Artikel").click()
                    awaitUntil("my articles again") { screen.shows("Noch keine Artikel.") }
                    assertTrue(screen.newButtonShown())
                    assertNotNull(screen.newButtons().firstOrNull())
                }
            }
        }

    @Test
    fun theCardsOfADraft_carryTheIconsOfTheirVerbs(): Promise<Unit> =
        formTest {
            withArticleStub(mine = listOf(article("a1", "Erster Entwurf"))) { _, _ ->
                mountedForm("r50-articles-icons") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the list is shown") { element().shows("Erster Entwurf") }
                    val screen = element()
                    assertTrue(screen.buttonNamed("Zur Freigabe einreichen").querySelector("i.fa-paper-plane") != null, "SEND")
                    assertTrue(screen.buttonNamed("Bearbeiten").querySelector("i.fa-pen") != null, "EDIT")
                    assertTrue(screen.buttonNamed("Löschen").querySelector("i.fa-trash") != null, "DELETE")
                }
            }
        }
}

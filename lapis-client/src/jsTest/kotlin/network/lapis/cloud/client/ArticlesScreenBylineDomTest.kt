package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.ArticleSummaryDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IArticleService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.105 -- the author line on the article lists: the author's summary line and "Namensnennung widerrufen" on an article that can no
 * longer be edited; the board's "Autorenzeile ändern" dialog (keep is offered and the default only when a line exists, "nicht nennen", an
 * organization with a text field prefilled from the branding -- never a personal name option); the "Importiert" badge and the "Ohne Titelbild"
 * hint; the approve dialog showing the line that goes public; the import button only for the board.
 */
class ArticlesScreenBylineDomTest {
    private fun board() =
        SessionInfoDto(
            memberId = "b1",
            displayName = "Vera Vorstand",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun mine(
        id: String,
        status: ArticleStatus,
        byline: String?,
        kind: ArticleBylineKind?,
    ) = ArticleDto(
        id = id,
        slug = if (status == ArticleStatus.PUBLISHED) "slug-$id" else null,
        title = "Artikel $id",
        excerpt = "Auszug $id",
        body = "Text",
        coverImageUrl = null,
        status = status,
        submittedAt = LocalDateTime(2026, 10, 1, 10, 0),
        reviewedAt = null,
        rejectionReason = null,
        publishedAt = if (status == ArticleStatus.PUBLISHED) LocalDateTime(2026, 10, 2, 10, 0) else null,
        updatedAt = LocalDateTime(2026, 10, 2, 10, 0),
        byline = byline,
        bylineKind = kind,
    )

    private fun summary(
        id: String,
        byline: String?,
        kind: ArticleBylineKind?,
        imported: Boolean = false,
        hasCover: Boolean = true,
    ) = ArticleSummaryDto(
        id = id,
        title = "Veröffentlicht $id",
        excerpt = "Auszug $id",
        submittedAt = LocalDateTime(2026, 10, 1, 10, 0),
        authorIsSelf = false,
        publishedAt = LocalDateTime(2026, 10, 2, 10, 0),
        slug = "slug-$id",
        byline = byline,
        bylineKind = kind,
        imported = imported,
        hasCover = hasCover,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    @Test
    fun aSubmittedArticleWithAPersonalLine_showsTheLine_andOffersTheRevocation_whichAsksFirst(): Promise<Unit> =
        formTest {
            val listMine = routeOf { rpcService<IArticleService>().listMyArticles() }
            val revoke = routeOf { rpcService<IArticleService>().revokeOwnByline("x") }
            val article = mine("a1", ArticleStatus.SUBMITTED, "Max Muster", ArticleBylineKind.PERSONAL)
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listMine -> request.answerWith(jsonOf(ListSerializer(ArticleDto.serializer()), listOf(article)))
                        request.rpcRoute == revoke ->
                            request.answerWith(
                                jsonOf(ArticleDto.serializer(), article.copy(byline = null, bylineKind = null)),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("v105-articles-revoke") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the list is shown") { element().shows("Artikel a1") }
                    assertTrue(element().shows("Wird als „Max Muster“ genannt."))
                    element().buttonNamed("Namensnennung widerrufen").click()
                    awaitUntil("the question is asked") { document.querySelector(".modal.show") != null }
                    assertEquals(0, calls.toRoute(revoke).size, "asks first: nothing withdrawn yet")
                    lastOpenModal().buttonNamed("Widerrufen").click()
                    awaitUntil("the revocation was sent") { calls.toRoute(revoke).isNotEmpty() }
                    assertEquals("a1", calls.singleCall(revoke).rpcParam(0) as String)
                }
            }
        }

    @Test
    fun aDraftOrAnArticleWithoutAPersonalLine_hasNoRevokeButton_andSaysNobodyIsNamed(): Promise<Unit> =
        formTest {
            val listMine = routeOf { rpcService<IArticleService>().listMyArticles() }
            val items =
                listOf(
                    mine("d1", ArticleStatus.DRAFT, "Max Muster", ArticleBylineKind.PERSONAL),
                    mine("s1", ArticleStatus.SUBMITTED, null, null),
                    mine("p1", ArticleStatus.PUBLISHED, "Redaktion", ArticleBylineKind.ORGANIZATION),
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listMine -> request.answerWith(jsonOf(ListSerializer(ArticleDto.serializer()), items))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("v105-articles-no-revoke") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the list is shown") { element().shows("Artikel p1") }
                    assertEquals(0, element().allOf("button").count { it.textContent?.trim() == "Namensnennung widerrufen" })
                    assertTrue(element().shows("Wird nicht genannt."))
                    assertTrue(element().shows("Wird als „Redaktion“ genannt."))
                }
            }
        }

    @Test
    fun theBoardDialog_offersKeepAsTheDefault_andNeverAPersonalName_andSavesAnOrganizationLine(): Promise<Unit> =
        formTest {
            AppState.setSession(board())
            val listMine = routeOf { rpcService<IArticleService>().listMyArticles() }
            val listPublished = routeOf { rpcService<IArticleService>().listPublishedArticles() }
            val setBoard =
                routeOf {
                    rpcService<IArticleService>().setBylineAsBoard(
                        "x",
                        network.lapis.cloud.shared.domain
                            .BoardBylineInput(network.lapis.cloud.shared.domain.BoardBylineMode.NONE),
                    )
                }
            val imported = summary("i1", "Dirk Hesse", ArticleBylineKind.IMPORTED, imported = true, hasCover = false)
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listMine -> request.answerWith("[]")
                        request.rpcRoute == listPublished ->
                            request.answerWith(jsonOf(ListSerializer(ArticleSummaryDto.serializer()), listOf(imported)))
                        request.rpcRoute == setBoard -> request.answerWith(jsonOf(ArticleSummaryDto.serializer(), imported))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("v105-articles-board-dialog") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("my articles") { element().shows("Noch keine Artikel.") }
                    assertTrue(
                        element().allOf("button").any { it.textContent?.trim() == "Frühere Artikel importieren" },
                        "import button for the board",
                    )
                    element().buttonNamed("Veröffentlicht").click()
                    awaitUntil("the published list") { element().shows("Veröffentlicht i1") }
                    assertTrue(element().shows("Importiert"), "the imported badge")
                    assertTrue(element().shows("Ohne Titelbild"), "the missing cover is flagged")
                    assertTrue(
                        element().allOf("button").any { it.textContent?.trim() == "Titelbild" },
                        "the board can add the cover of an import",
                    )
                    assertTrue(element().shows("Wird als „Dirk Hesse“ genannt."))

                    element().buttonNamed("Autorenzeile ändern").click()
                    awaitUntil("the dialog is open") { document.querySelector(".modal.show") != null }
                    val dialog = lastOpenModal()
                    val keep = dialog.controlOf("Beibehalten: „Dirk Hesse“") as HTMLInputElement
                    assertTrue(keep.checked, "keep is the default")
                    val none = dialog.controlOf("Nicht nennen") as HTMLInputElement
                    assertFalse(none.checked)
                    assertEquals(3, dialog.allOf("input[type=radio]").size, "keep, nobody, organization -- and no personal-name option")
                    assertEquals(
                        1,
                        dialog.allOf("label").count {
                            it.textContent
                                .orEmpty()
                                .trim()
                                .startsWith("Organisation oder Redaktion")
                        },
                        "only the radio label: the text field appears with its option",
                    )

                    // keep + save: nothing is sent
                    dialog.buttonNamed("Speichern").click()
                    awaitUntil("the dialog is closed again") { document.querySelectorAll(".modal.show").length == 0 }
                    assertEquals(0, calls.toRoute(setBoard).size, "keeping the line sends nothing")

                    // organization: field prefilled from the branding, saved as ORGANIZATION
                    element().buttonNamed("Autorenzeile ändern").click()
                    awaitUntil("the dialog is open again") { document.querySelector(".modal.show") != null }
                    val second = lastOpenModal()
                    (second.controlOf("Organisation oder Redaktion") as HTMLInputElement).click()
                    awaitUntil("the field is shown") {
                        second.allOf("label").count {
                            it.textContent
                                .orEmpty()
                                .trim()
                                .startsWith("Organisation oder Redaktion")
                        } == 2
                    }
                    val org = second.controlOf("Organisation oder Redaktion", nth = 1) as HTMLInputElement
                    assertEquals(Branding.title, org.value, "prefilled with the organization's name")
                    second.typeInto("Organisation oder Redaktion", "Pressestelle", nth = 1)
                    second.buttonNamed("Speichern").click()
                    awaitUntil("the board call was sent") { calls.toRoute(setBoard).isNotEmpty() }
                    val call = calls.singleCall(setBoard)
                    assertEquals("i1", call.rpcParam(0) as String)
                    assertEquals("ORGANIZATION", call.rpcParam(1).mode as String)
                    assertEquals("Pressestelle", call.rpcParam(1).text as String)
                }
            }
        }

    @Test
    fun theReviewDialogOfAnApproval_showsTheLineThatGoesPublic(): Promise<Unit> =
        formTest {
            AppState.setSession(board())
            val listMine = routeOf { rpcService<IArticleService>().listMyArticles() }
            val listSubmitted = routeOf { rpcService<IArticleService>().listSubmittedArticles() }
            val review = routeOf { rpcService<IArticleService>().getArticleForReview("x") }
            val queued = summary("q1", "Erika Beispiel", ArticleBylineKind.PERSONAL).copy(publishedAt = null, slug = null)
            val reviewDto =
                network.lapis.cloud.shared.domain.ArticleReviewDto(
                    id = "q1",
                    title = "Veröffentlicht q1",
                    excerpt = "Auszug q1",
                    renderedBodyHtml = "<p>Text</p>",
                    coverImageUrl = null,
                    submittedAt = LocalDateTime(2026, 10, 1, 10, 0),
                    authorIsSelf = false,
                    byline = "Erika Beispiel",
                    bylineKind = ArticleBylineKind.PERSONAL,
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listMine -> request.answerWith("[]")
                        request.rpcRoute == listSubmitted ->
                            request.answerWith(jsonOf(ListSerializer(ArticleSummaryDto.serializer()), listOf(queued)))
                        request.rpcRoute == review ->
                            request.answerWith(
                                jsonOf(
                                    network.lapis.cloud.shared.domain.ArticleReviewDto
                                        .serializer(),
                                    reviewDto,
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("v105-articles-approve-line") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("my articles") { element().shows("Noch keine Artikel.") }
                    element().buttonNamed("Freigabe").click()
                    awaitUntil("the queue") { element().shows("Veröffentlicht q1") }
                    element().buttonNamed("Ansehen").click()
                    awaitUntil("the detail") { element().allOf("button").any { it.textContent?.trim() == "Genehmigen" } }
                    element().buttonNamed("Genehmigen").click()
                    awaitUntil("the dialog is open") { document.querySelector(".modal.show") != null }
                    assertTrue(lastOpenModal().shows("Wird als „Erika Beispiel“ genannt."), "the approver sees what goes public")
                }
            }
        }

    @Test
    fun aMember_hasNoImportButton(): Promise<Unit> =
        formTest {
            AppState.setSession(
                SessionInfoDto(
                    memberId = "m1",
                    displayName = "Max Mitglied",
                    role = AccountRole.MEMBER,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                ),
            )
            val listMine = routeOf { rpcService<IArticleService>().listMyArticles() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listMine -> request.answerWith("[]")
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("v105-articles-member-no-import") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("my articles") { element().shows("Noch keine Artikel.") }
                    assertFalse(element().allOf("button").any { it.textContent?.trim() == "Frühere Artikel importieren" })
                }
            }
        }
}

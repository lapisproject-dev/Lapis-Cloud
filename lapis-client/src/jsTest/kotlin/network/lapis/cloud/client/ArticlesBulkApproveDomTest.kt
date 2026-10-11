package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBulkApproveResultDto
import network.lapis.cloud.shared.domain.ArticleBulkSkipDto
import network.lapis.cloud.shared.domain.ArticleBulkSkipReason
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleSummaryDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IArticleImportService
import network.lapis.cloud.shared.rpc.IArticleService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.106 -- the bulk approval above the review list: shown only while imported articles wait; the importer sees the reason and no
 * button; the button names exactly the articles it sends (at most 100, oldest first); the confirmation says so and puts the focus on "Abbrechen";
 * the result goes to an `aria-live` region that is in the page from the start, with the skipped articles listed in plain words.
 */
class ArticlesBulkApproveDomTest {
    private fun board() =
        SessionInfoDto(
            memberId = "b1",
            displayName = "Vera Vorstand",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun queued(
        id: String,
        imported: Boolean = true,
        importedBySelf: Boolean = false,
    ) = ArticleSummaryDto(
        id = id,
        title = "Importiert $id",
        excerpt = "Auszug $id",
        submittedAt = LocalDateTime(2026, 10, 1, 10, 0),
        authorIsSelf = false,
        imported = imported,
        importedBySelf = importedBySelf,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private suspend fun <T> withQueue(
        queue: List<ArticleSummaryDto>,
        result: ArticleBulkApproveResultDto = ArticleBulkApproveResultDto(0, emptyList(), emptyList()),
        approveDelayMs: Int = 0,
        block: suspend (List<RecordedRequest>, String) -> T,
    ): T {
        val listMine = routeOf { rpcService<IArticleService>().listMyArticles() }
        val listQueue = routeOf { rpcService<IArticleService>().listSubmittedArticles() }
        val approve = routeOf { rpcService<IArticleImportService>().approveImportedArticles(emptyList()) }
        return withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listMine -> request.answerWith(jsonOf(ListSerializer(ArticleDto.serializer()), emptyList()))
                    request.rpcRoute == listQueue -> request.answerWith(jsonOf(ListSerializer(ArticleSummaryDto.serializer()), queue))
                    request.rpcRoute == approve -> {
                        val answer = request.answerWith(jsonOf(ArticleBulkApproveResultDto.serializer(), result))
                        StubResponse(status = answer.status, text = answer.text, delayMs = approveDelayMs)
                    }
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, approve) }
    }

    @Test
    fun withoutImportedArticles_thereIsNoBulkRegion_butTheLiveRegionIsThere(): Promise<Unit> =
        formTest {
            AppState.setSession(board())
            withQueue(listOf(queued("n1", imported = false))) { _, _ ->
                mountedForm("v106-bulk-none") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the articles screen is built") { element().buttonNamed("Freigabe").textContent != null }
                    element().buttonNamed("Freigabe").click()
                    awaitUntil("the queue is shown") { element().shows("Importiert n1") }
                    assertFalse(element().shows("Importierte Artikel zur Freigabe"))
                    assertTrue(element().querySelector("[aria-live=polite]") != null, "the live region is part of the page from the start")
                }
            }
        }

    @Test
    fun theImporterSeesOnlyTheReason_noButton(): Promise<Unit> =
        formTest {
            AppState.setSession(board())
            withQueue(listOf(queued("i1", importedBySelf = true), queued("i2", importedBySelf = true))) { _, _ ->
                mountedForm("v106-bulk-importer") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the articles screen is built") { element().buttonNamed("Freigabe").textContent != null }
                    element().buttonNamed("Freigabe").click()
                    awaitUntil("the region is shown") { element().shows("Importierte Artikel zur Freigabe: 2") }
                    assertTrue(element().shows("Diese Artikel haben Sie importiert. Ein anderes Vorstandsmitglied muss sie freigeben."))
                    assertEquals(0, element().allOf("button").count { it.textContent.orEmpty().contains("importierten Artikel freigeben") })
                }
            }
        }

    @Test
    fun aMixedQueue_countsOnlyWhatMayBeApproved_andTheDialogFocusesCancel_andCancelSendsNothing(): Promise<Unit> =
        formTest {
            AppState.setSession(board())
            withQueue(
                listOf(queued("a1"), queued("a2"), queued("own", importedBySelf = true), queued("n1", imported = false)),
            ) { calls, approve ->
                mountedForm("v106-bulk-mixed") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the articles screen is built") { element().buttonNamed("Freigabe").textContent != null }
                    element().buttonNamed("Freigabe").click()
                    awaitUntil("the region is shown") { element().shows("Importierte Artikel zur Freigabe: 3") }
                    val region = element().querySelector("[role=region]") as HTMLElement
                    assertEquals("article-bulk-heading", region.getAttribute("aria-labelledby"))
                    assertTrue(element().shows("Von Ihnen importiert und deshalb nicht von Ihnen freigebbar: 1"))
                    val button = element().buttonNamed("Alle importierten Artikel freigeben (2)")
                    assertTrue(button.getAttribute("style").orEmpty().contains("min-height: 44px"), "a touch-sized target")
                    button.click()
                    awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                    val modal = lastOpenModal()
                    assertTrue(modal.shows("Importierte Artikel freigeben?"))
                    assertTrue(modal.shows("Anzahl der Artikel: 2"))
                    assertTrue(modal.shows("ursprünglichen Datum"))
                    awaitUntil("the focus is on Abbrechen") { (document.activeElement as? HTMLElement)?.textContent?.trim() == "Abbrechen" }
                    modal.buttonNamed("Abbrechen").click()
                    assertEquals(0, calls.toRoute(approve).size, "cancelling sends nothing")
                }
            }
        }

    @Test
    fun confirming_sendsExactlyTheShownIds_announcesTheResult_andListsTheSkippedInPlainWords_andBlocksADoubleClick(): Promise<Unit> =
        formTest {
            AppState.setSession(board())
            val result =
                ArticleBulkApproveResultDto(
                    approvedCount = 1,
                    approvedIds = listOf("a1"),
                    skipped = listOf(ArticleBulkSkipDto("a2", "Importiert a2", ArticleBulkSkipReason.NOT_SUBMITTED)),
                )
            withQueue(
                listOf(queued("a1"), queued("a2"), queued("own", importedBySelf = true)),
                result = result,
                approveDelayMs = 300,
            ) { calls, approve ->
                mountedForm("v106-bulk-confirm") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the articles screen is built") { element().buttonNamed("Freigabe").textContent != null }
                    element().buttonNamed("Freigabe").click()
                    awaitUntil("the region is shown") { element().shows("Importierte Artikel zur Freigabe: 3") }
                    val button = element().buttonNamed("Alle importierten Artikel freigeben (2)")
                    button.click()
                    awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Freigeben (2)").click()
                    awaitUntil("the call went out") { calls.toRoute(approve).isNotEmpty() }
                    assertTrue(button.asDynamic().disabled as Boolean, "the button is out of reach while the call runs")
                    val ids = (calls.singleCall(approve).rpcParam(0) as Array<String>).toList()
                    assertEquals(listOf("a1", "a2"), ids, "exactly the approvable articles, oldest first, never the own import")
                    awaitUntil("the result is announced") { element().shows("Freigegeben: 1, übersprungen: 1.") }
                    val live = element().querySelector("[aria-live=polite]") as HTMLElement
                    assertTrue(live.shows("Freigegeben: 1, übersprungen: 1."), "inside the live region")
                    assertTrue(live.shows("Übersprungene Artikel anzeigen"))
                    assertTrue(live.shows("Importiert a2"))
                    assertTrue(live.shows("Bereits entschieden"))
                }
            }
        }

    @Test
    fun moreThanAHundred_sendsExactlyAHundred_andSaysHowManyFollow(): Promise<Unit> =
        formTest {
            AppState.setSession(board())
            val queue = (1..101).map { queued("x$it") }
            withQueue(queue) { calls, approve ->
                mountedForm("v106-bulk-hundred") { root, element ->
                    renderArticlesScreen(root)
                    awaitUntil("the articles screen is built") { element().buttonNamed("Freigabe").textContent != null }
                    element().buttonNamed("Freigabe").click()
                    awaitUntil("the region is shown") { element().shows("Importierte Artikel zur Freigabe: 101") }
                    assertTrue(element().shows("Weitere folgen nach dieser Freigabe: 1"))
                    element().buttonNamed("Die ersten importierten Artikel freigeben (100)").click()
                    awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Freigeben (100)").click()
                    awaitUntil("the call went out") { calls.toRoute(approve).isNotEmpty() }
                    val ids = (calls.singleCall(approve).rpcParam(0) as Array<String>).toList()
                    assertEquals(100, ids.size)
                    assertEquals("x1", ids.first())
                    assertEquals("x100", ids.last())
                }
            }
        }
}

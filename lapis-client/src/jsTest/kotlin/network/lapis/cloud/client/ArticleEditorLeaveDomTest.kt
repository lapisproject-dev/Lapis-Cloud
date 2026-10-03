package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.rpc.IArticleService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.50 -- leaving the article editor. Leaving SAVES first (the autosave promise, unchanged); the question "Änderungen verwerfen?" is
 * asked only when something is NOT saved (the save failed). "Verwerfen" drops the pending edit and never saves it; "Weiter bearbeiten"
 * changes nothing. The only renamed accessible name of the wave: "Zurück zur Liste" (no arrow).
 */
class ArticleEditorLeaveDomTest {
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"

    private fun draft(
        id: String,
        title: String,
    ) = ArticleDto(
        id = id,
        slug = null,
        title = title,
        excerpt = "Auszug",
        body = "Text",
        coverImageUrl = null,
        status = ArticleStatus.DRAFT,
        submittedAt = null,
        reviewedAt = null,
        rejectionReason = null,
        publishedAt = null,
        updatedAt = LocalDateTime(2026, 9, 28, 10, 0),
    )

    private suspend fun withSaveStub(
        failing: Boolean,
        block: suspend (List<RecordedRequest>, String) -> Unit,
    ) {
        val saveDraft = routeOf { rpcService<IArticleService>().saveDraft(null, ArticleDraftInput("t", "e", "b")) }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == saveDraft ->
                        if (failing) {
                            serviceExceptionResult(request.json.id as Int, conflict)
                        } else {
                            request.answerWith(jsonOf(ArticleDto.serializer(), draft("n1", "Neuer Titel")))
                        }
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, saveDraft) }
    }

    private fun HTMLElement.typeArticle() {
        typeInto("Titel", "Neuer Titel")
        typeInto("Auszug", "Kurz")
        typeInto("Text (Markdown)", "Inhalt")
    }

    private fun HTMLElement.dialogOpen(): Boolean = ownerDocument?.querySelector(".modal.show") != null

    @Test
    fun theBackButton_isNamedWithoutAnArrow_andItsIconIsDecoration(): Promise<Unit> =
        formTest {
            withSaveStub(failing = false) { _, _ ->
                mountedForm("r50-editor-name") { root, element ->
                    renderArticleEditor(root, null) {}
                    awaitUntil("the editor is built") { element().querySelector(".lapis-form") != null }
                    val back = element().buttonNamed("Zurück zur Liste")
                    assertEquals("Zurück zur Liste", back.textContent?.trim(), "no arrow in the accessible name")
                    assertEquals("true", back.querySelector("i")?.getAttribute("aria-hidden"), "the arrow icon is decoration")
                    assertTrue(back.querySelector("i.fa-arrow-left") != null, "BACK")
                }
            }
        }

    @Test
    fun anUntouchedNewEditor_leavesAtOnce_withoutAQuestionAndWithoutASave(): Promise<Unit> =
        formTest {
            withSaveStub(failing = false) { calls, saveDraft ->
                var backs = 0
                mountedForm("r50-editor-untouched") { root, element ->
                    renderArticleEditor(root, null) { backs++ }
                    awaitUntil("the editor is built") { element().querySelector(".lapis-form") != null }
                    element().buttonNamed("Zurück zur Liste").click()
                    awaitUntil("left") { backs == 1 }
                    assertFalse(element().dialogOpen(), "nothing to lose: no question")
                    assertEquals(0, calls.toRoute(saveDraft).size, "an empty new editor never creates a draft")
                }
            }
        }

    @Test
    fun leavingASavedEdit_savesFirst_andAsksNothing(): Promise<Unit> =
        formTest {
            withSaveStub(failing = false) { calls, saveDraft ->
                var backs = 0
                mountedForm("r50-editor-saved") { root, element ->
                    renderArticleEditor(root, null) { backs++ }
                    awaitUntil("the editor is built") { element().querySelector(".lapis-form") != null }
                    element().typeArticle()
                    element().buttonNamed("Zurück zur Liste").click()
                    awaitUntil("left") { backs == 1 }
                    assertFalse(element().dialogOpen(), "the edit reached the server: no question")
                    assertTrue(calls.toRoute(saveDraft).isNotEmpty(), "leaving flushed the pending edit")
                }
            }
        }

    @Test
    fun aFailedSave_asks_weiterBearbeitenChangesNothing_verwerfenLeavesOnceAndNeverSaves(): Promise<Unit> =
        formTest {
            withSaveStub(failing = true) { calls, saveDraft ->
                var backs = 0
                mountedForm("r50-editor-failed") { root, element ->
                    renderArticleEditor(root, null) { backs++ }
                    awaitUntil("the editor is built") { element().querySelector(".lapis-form") != null }
                    element().typeArticle()

                    element().buttonNamed("Zurück zur Liste").click()
                    answerDiscardDialog("Weiter bearbeiten")
                    assertEquals(0, backs, "'Weiter bearbeiten' stays in the editor")
                    assertTrue(element().querySelector(".lapis-form") != null)
                    assertFalse(
                        element().buttonNamed("Zurück zur Liste").asDynamic().disabled as Boolean,
                        "the back button is usable again",
                    )

                    element().buttonNamed("Zurück zur Liste").click()
                    awaitUntil("the question is asked again") { element().dialogOpen() }
                    val savesBeforeDiscard = calls.toRoute(saveDraft).size
                    lastOpenModal().buttonNamed("Verwerfen").click()
                    awaitUntil("left exactly once") { backs == 1 }
                    // The pending edit was dropped: not even the debounce (2 s) may send it afterwards.
                    delay(ArticleAutoSaveController.DEBOUNCE_MS + 400L)
                    assertEquals(1, backs, "left once")
                    assertEquals(savesBeforeDiscard, calls.toRoute(saveDraft).size, "'Verwerfen' never saves")
                }
            }
        }

    @Test
    fun anEditorThatIsLeftThroughItsHandle_runsTheSameExit(): Promise<Unit> =
        formTest {
            withSaveStub(failing = true) { _, _ ->
                var left = 0
                mountedForm("r50-editor-handle") { root, element ->
                    val handle = renderArticleEditor(root, null) {}
                    awaitUntil("the editor is built") { element().querySelector(".lapis-form") != null }
                    element().typeArticle()
                    handle.requestLeave { left++ }
                    answerDiscardDialog("Weiter bearbeiten")
                    assertEquals(0, left, "'Weiter bearbeiten' never runs the exit")
                    handle.requestLeave { left++ }
                    awaitUntil("the question is asked again") { element().dialogOpen() }
                    lastOpenModal().buttonNamed("Verwerfen").click()
                    awaitUntil("the exit ran") { left == 1 }
                    assertNotNull(handle)
                }
            }
        }
}

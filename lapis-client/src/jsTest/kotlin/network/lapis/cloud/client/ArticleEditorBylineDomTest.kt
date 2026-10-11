package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.rpc.IArticleService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.105 -- the author's consent card ("Autorenzeile") of the article editor: a native checkbox "Mich als Autor nennen" (off by
 * default, its consent text hangs on it through `aria-describedby`), the name field appears only when ticked and is saved on `change` (never per
 * keystroke), un-ticking withdraws at once, a live region announces both, and the summary line is honest ("Wird nicht genannt." until a text is
 * stored).
 */
class ArticleEditorBylineDomTest {
    private fun draft(
        id: String,
        byline: String? = null,
        kind: ArticleBylineKind? = null,
    ) = ArticleDto(
        id = id,
        slug = null,
        title = "Ein Titel",
        excerpt = "Auszug",
        body = "Text",
        coverImageUrl = null,
        status = ArticleStatus.DRAFT,
        submittedAt = null,
        reviewedAt = null,
        rejectionReason = null,
        publishedAt = null,
        updatedAt = LocalDateTime(2026, 10, 11, 10, 0),
        byline = byline,
        bylineKind = kind,
    )

    private suspend fun withBylineStub(
        answer: (consent: Boolean, text: String?) -> ArticleDto,
        block: suspend (List<RecordedRequest>, String) -> Unit,
    ) {
        val setOwn = routeOf { rpcService<IArticleService>().setOwnByline("x", true, "y") }
        val saveDraft = routeOf { rpcService<IArticleService>().saveDraft("x", ArticleDraftInput("t", "e", "b")) }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    // An editor of an existing draft schedules its (unchanged) auto-save once, two seconds after it was built: let it fire
                    // inside THIS test's stub, so it never lands in the request log of the next test.
                    request.rpcRoute == saveDraft -> request.answerWith(jsonOf(ArticleDto.serializer(), draft("a1")))
                    request.rpcRoute == setOwn -> {
                        val consent = request.rpcParam(1) as Boolean
                        val text = request.rpcParam(2) as String?
                        request.answerWith(jsonOf(ArticleDto.serializer(), answer(consent, text)))
                    }
                    else -> request.answerWith("[]")
                }
            },
        ) { calls ->
            block(calls, setOwn)
            delay(ArticleAutoSaveController.DEBOUNCE_MS + 500L)
        }
    }

    /** A hidden KVision container is not in the DOM at all: "shown" means a label of that text exists AND takes part in the layout. */
    private fun HTMLElement.fieldShown(label: String): Boolean =
        allOf("label").any {
            it.textContent
                .orEmpty()
                .trim()
                .startsWith(label) &&
                it.isRendered()
        }

    private fun HTMLElement.summaryText(): String =
        querySelector(".lapis-article-byline .small:not(.text-muted)")?.textContent.orEmpty().trim()

    private fun HTMLElement.liveText(): String = querySelector(".lapis-article-byline [aria-live=polite]")?.textContent.orEmpty().trim()

    @Test
    fun aNewEditor_asksToSaveOnceFirst_beforeTheConsentCardAppears(): Promise<Unit> =
        formTest {
            withBylineStub(answer = { _, _ -> draft("n1") }) { _, _ ->
                mountedForm("v105-byline-new") { root, element ->
                    renderArticleEditor(root, null) {}
                    awaitUntil("the editor is built") { element().querySelector(".lapis-form") != null }
                    assertTrue(element().textContent.orEmpty().contains("Autorenzeile: bitte zuerst einmal speichern"))
                    assertEquals(null, element().querySelector(".lapis-article-byline"), "no consent card without an article id")
                }
            }
        }

    @Test
    fun theCheckboxIsOffByDefault_theNameFieldIsHidden_andTheSummaryIsHonest(): Promise<Unit> =
        formTest {
            withBylineStub(answer = { _, _ -> draft("a1") }) { calls, setOwn ->
                mountedForm("v105-byline-default") { root, element ->
                    renderArticleEditor(root, draft("a1")) {}
                    awaitUntil("the consent card is built") { element().querySelector(".lapis-article-byline") != null }
                    val box = element().controlOf("Mich als Autor nennen") as HTMLInputElement
                    assertEquals("checkbox", box.type, "a native checkbox")
                    assertFalse(box.checked, "off by default")
                    assertFalse(element().fieldShown("So möchte ich genannt werden"), "no name field until the box is ticked")
                    assertEquals("Wird nicht genannt.", element().summaryText())
                    val describedBy = box.getAttribute("aria-describedby").orEmpty()
                    assertTrue(describedBy.isNotEmpty(), "the consent text is tied to the checkbox")
                    val consentText =
                        describedBy
                            .split(
                                ' ',
                            ).mapNotNull { element().ownerDocument?.getElementById(it)?.textContent }
                            .joinToString(" ")
                    assertTrue(
                        consentText.contains("Sie können die Zustimmung jederzeit widerrufen"),
                        "the revocation is in the described text",
                    )
                    assertEquals(0, calls.toRoute(setOwn).size, "merely rendering saves nothing")
                }
            }
        }

    @Test
    fun tickingAloneStoresNothing_theTextIsSavedOnChange_andTheLiveRegionAnnounces(): Promise<Unit> =
        formTest {
            withBylineStub(
                answer = { _, text -> draft("a1", byline = text, kind = ArticleBylineKind.PERSONAL) },
            ) { calls, setOwn ->
                mountedForm("v105-byline-save") { root, element ->
                    renderArticleEditor(root, draft("a1")) {}
                    awaitUntil("the consent card is built") { element().querySelector(".lapis-article-byline") != null }
                    element().tick("Mich als Autor nennen")
                    awaitUntil("the name field appears") { element().fieldShown("So möchte ich genannt werden") }
                    assertEquals(0, calls.toRoute(setOwn).size, "ticking with an empty field stores nothing")
                    assertEquals("Wird nicht genannt.", element().summaryText(), "honest until a text is stored")

                    val name = element().controlOf("So möchte ich genannt werden") as HTMLInputElement
                    assertEquals(120, name.maxLength, "the field is limited like the server")
                    name.value = "Max Muster"
                    name.dispatchEvent(Event("input"))
                    assertEquals(0, calls.toRoute(setOwn).size, "typing alone (no change yet) never saves: no consent record per keystroke")
                    name.dispatchEvent(Event("change"))
                    awaitUntil("the byline was sent") { calls.toRoute(setOwn).isNotEmpty() }
                    val call = calls.singleCall(setOwn)
                    assertEquals(true, call.rpcParam(1) as Boolean)
                    assertEquals("Max Muster", call.rpcParam(2) as String)
                    awaitUntil("the summary shows the stored name") { element().summaryText() == "Wird als „Max Muster“ genannt." }
                    assertEquals("Einwilligung gespeichert.", element().liveText())
                }
            }
        }

    @Test
    fun theSameTextOnChange_isNotSentAgain(): Promise<Unit> =
        formTest {
            withBylineStub(answer = { _, text -> draft("a1", byline = text, kind = ArticleBylineKind.PERSONAL) }) { calls, setOwn ->
                mountedForm("v105-byline-same") { root, element ->
                    renderArticleEditor(root, draft("a1", byline = "Max Muster", kind = ArticleBylineKind.PERSONAL)) {}
                    awaitUntil("the consent card is built") { element().querySelector(".lapis-article-byline") != null }
                    val box = element().controlOf("Mich als Autor nennen") as HTMLInputElement
                    assertTrue(box.checked, "an existing consent shows as ticked")
                    assertEquals("Wird als „Max Muster“ genannt.", element().summaryText())
                    val name = element().controlOf("So möchte ich genannt werden") as HTMLInputElement
                    name.dispatchEvent(Event("change"))
                    assertEquals(0, calls.toRoute(setOwn).size, "unchanged text: nothing sent")
                }
            }
        }

    @Test
    fun unticking_withdrawsAtOnce_clearsTheField_andAnnounces(): Promise<Unit> =
        formTest {
            withBylineStub(answer = { _, _ -> draft("a1") }) { calls, setOwn ->
                mountedForm("v105-byline-revoke") { root, element ->
                    renderArticleEditor(root, draft("a1", byline = "Max Muster", kind = ArticleBylineKind.PERSONAL)) {}
                    awaitUntil("the consent card is built") { element().querySelector(".lapis-article-byline") != null }
                    val box = element().controlOf("Mich als Autor nennen") as HTMLInputElement
                    box.click()
                    awaitUntil("the withdrawal was sent") { calls.toRoute(setOwn).isNotEmpty() }
                    val call = calls.singleCall(setOwn)
                    assertEquals(false, call.rpcParam(1) as Boolean)
                    assertEquals(null, call.rpcParam(2))
                    awaitUntil("the summary says nobody is named") { element().summaryText() == "Wird nicht genannt." }
                    assertEquals("Einwilligung widerrufen.", element().liveText())
                    awaitUntil("the name field is gone again") { !element().fieldShown("So möchte ich genannt werden") }
                    assertNotNull(box)
                }
            }
        }

    @Test
    fun unticking_beforeTheSaveAnswers_stillWithdrawsOnceItReturnsPersonal(): Promise<Unit> =
        formTest {
            withBylineStub(
                answer = { consent, text ->
                    if (consent) draft("a1", byline = text, kind = ArticleBylineKind.PERSONAL) else draft("a1")
                },
            ) { calls, setOwn ->
                mountedForm("v105-byline-race") { root, element ->
                    renderArticleEditor(root, draft("a1")) {}
                    awaitUntil("the consent card is built") { element().querySelector(".lapis-article-byline") != null }
                    val box = element().controlOf("Mich als Autor nennen") as HTMLInputElement
                    element().tick("Mich als Autor nennen")
                    awaitUntil("the name field appears") { element().fieldShown("So möchte ich genannt werden") }
                    val name = element().controlOf("So möchte ich genannt werden") as HTMLInputElement
                    name.value = "Max Muster"
                    name.dispatchEvent(Event("input"))
                    name.dispatchEvent(Event("change"))
                    // The author changes their mind before the RPC answers.
                    box.click()
                    awaitUntil("the compensating withdrawal was sent") { calls.toRoute(setOwn).size >= 2 }
                    val sent = calls.toRoute(setOwn)
                    assertEquals(true, sent[0].rpcParam(1) as Boolean)
                    assertEquals(false, sent[1].rpcParam(1) as Boolean)
                    awaitUntil("the summary says nobody is named") { element().summaryText() == "Wird nicht genannt." }
                    assertFalse(box.checked)
                }
            }
        }
}

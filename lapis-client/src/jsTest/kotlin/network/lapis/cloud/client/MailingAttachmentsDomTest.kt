package network.lapis.cloud.client

import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.MailingAttachmentDto
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.232 -- the "Anhänge" section of a mailing message: editable in a draft, read-only afterwards, names are untrusted text. */
class MailingAttachmentsDomTest {
    private fun attachment(
        id: String,
        name: String,
        size: Long = 2048,
        available: Boolean = true,
        mime: String = "application/pdf",
    ) = MailingAttachmentDto(id = id, fileName = name, mimeType = mime, sizeBytes = size, fileAvailable = available)

    private fun message(
        attachments: List<MailingAttachmentDto>,
        status: MailingMessageStatus = MailingMessageStatus.DRAFT,
    ) = MailingMessageDto(
        id = "msg-1",
        mailingListId = "list-1",
        subject = "Einladung",
        bodyText = "Text",
        sentBy = "board-1",
        sentAt = null,
        status = status,
        attachments = attachments,
    )

    private fun HTMLElement.byLabel(label: String): HTMLElement =
        assertNotNull(allOf("button, a").firstOrNull { it.getAttribute("aria-label") == label }, "no button or link '$label'")

    private fun chooseFiles(
        input: HTMLInputElement,
        vararg names: String,
    ) {
        val transfer = js("new DataTransfer()")
        names.forEach { transfer.items.add(File(arrayOf<dynamic>("%PDF-1.4"), it, js("({type: 'application/pdf'})"))) }
        input.asDynamic().files = transfer.files
        input.dispatchEvent(Event("change"))
    }

    @Test
    fun aDraftWithoutFiles_showsTheEmptyText_theSummary_theRules_andTheUploadChooser(): Promise<Unit> =
        formTest {
            mountedForm("ma-empty") { root, element ->
                renderMailingAttachments(root, message(emptyList()), onChanged = {})
                delay(60)
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Keine Anhänge."), text)
                assertTrue(text.contains("0 von 5 Dateien, zusammen 0 B von 20 MB."), text)
                assertTrue(text.contains("höchstens 5 Dateien, 10 MB pro Datei, zusammen 20 MB"), text)
                assertTrue(text.contains("Anhänge gehen an alle Empfänger dieser Nachricht."), text)
                assertTrue(text.contains("Lapis prüft Anhänge nicht auf Viren."), text)
                assertNull(element().querySelector("table"))
                val input = assertNotNull(element().querySelector("input[type=file]") as? HTMLInputElement)
                assertTrue(input.multiple)
                val accept = input.getAttribute("accept").orEmpty()
                assertTrue(accept.contains(".pdf") && accept.contains("application/pdf") && accept.contains(".docx"), accept)
                assertFalse(accept.contains("svg") || accept.contains("html"), accept)
                assertFalse(text.contains("###"), "no tr() marker on screen")
            }
        }

    @Test
    fun aDraftWithFiles_listsThemAsText_withNamedButtons_andNeverInterpretsTheFileName(): Promise<Unit> =
        formTest {
            val evil = "<img src=x onerror=alert(1)>.pdf"
            mountedForm("ma-list") { root, element ->
                renderMailingAttachments(
                    root,
                    message(
                        listOf(
                            attachment("a1", evil),
                            attachment(
                                "a2",
                                "Einladung.pdf",
                                size =
                                    3 * 1024 * 1024,
                            ),
                        ),
                    ),
                    {},
                )
                delay(60)
                val table = assertNotNull(element().querySelector("table") as? HTMLElement)
                assertNull(table.querySelector("img"), "a file name is text, never markup")
                assertTrue(table.textContent.orEmpty().contains(evil))
                assertEquals(listOf("Datei", "Größe", "Aktionen"), table.allOf("th").map { it.textContent?.trim().orEmpty() })
                assertTrue(table.classList.contains("lapis-mailing-attachments"))
                for (name in listOf(
                    "„$evil“ herunterladen",
                    "„$evil“ entfernen",
                    "„Einladung.pdf“ herunterladen",
                    "„Einladung.pdf“ entfernen",
                )) {
                    assertFalse(
                        element()
                            .byLabel(name)
                            .getAttribute("aria-label")
                            .orEmpty()
                            .contains("###"),
                    )
                }
                assertTrue(element().allOf("button").any { it.textContent?.trim() == "Entfernen" }, "visible text next to the icon")
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("2 von 5 Dateien, zusammen 3,0 MB von 20 MB."), text)
                assertTrue(text.contains("3,0 MB") && text.contains("2 KB"), text)
                assertEquals(
                    "/api/mailing/attachments/a2/download",
                    element().byLabel("„Einladung.pdf“ herunterladen").getAttribute("href"),
                )
            }
        }

    @Test
    fun atTheLimit_theChooserIsDisabled_andTheHintIsShown(): Promise<Unit> =
        formTest {
            mountedForm("ma-limit") { root, element ->
                renderMailingAttachments(
                    root,
                    message((1..AttachmentRules.MAX_FILES).map { attachment("a$it", "Datei $it.pdf") }),
                    {},
                )
                delay(60)
                assertTrue((element().querySelector("input[type=file]") as HTMLInputElement).disabled)
                assertTrue(element().textContent.orEmpty().contains("Höchstens 5 Anhänge pro Nachricht."))
            }
        }

    @Test
    fun aSentMessage_isReadOnly_withDownloadOnly(): Promise<Unit> =
        formTest {
            for (status in listOf(MailingMessageStatus.QUEUED, MailingMessageStatus.SENT, MailingMessageStatus.FAILED)) {
                mountedForm("ma-ro-$status") { root, element ->
                    renderMailingAttachments(root, message(listOf(attachment("a1", "Einladung.pdf")), status), {})
                    delay(60)
                    assertTrue(element().allOf("button").none { it.textContent?.trim() == "Entfernen" })
                    assertNull(element().querySelector("input[type=file]"))
                    assertNotNull(element().byLabel("„Einladung.pdf“ herunterladen"))
                    assertTrue(element().textContent.orEmpty().contains("Nach dem Senden können Anhänge nicht mehr geändert werden."))
                }
            }
        }

    @Test
    fun aSentMessageWithoutFiles_showsNothingAtAll(): Promise<Unit> =
        formTest {
            mountedForm("ma-sent-empty") { root, element ->
                renderMailingAttachments(root, message(emptyList(), MailingMessageStatus.SENT), {})
                delay(60)
                assertEquals("", element().textContent.orEmpty().trim())
            }
        }

    @Test
    fun aFileRemovedByRetention_isListedAsDeleted_withoutADeadDownloadLink(): Promise<Unit> =
        formTest {
            mountedForm("ma-gone") { root, element ->
                renderMailingAttachments(
                    root,
                    message(listOf(attachment("a1", "Alt.pdf", available = false)), MailingMessageStatus.SENT),
                    {},
                )
                delay(60)
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Alt.pdf") && text.contains("Datei gelöscht"), text)
                assertNull(element().allOf("a").firstOrNull { it.textContent?.trim() == "Herunterladen" }, "no dead download link")
            }
        }

    @Test
    fun remove_sendsDelete_withoutConfirmation_andReloads(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 200) }) { calls ->
                mountedForm("ma-remove") { root, element ->
                    var changed = 0
                    renderMailingAttachments(root, message(listOf(attachment("a7", "Plan.pdf"))), { changed++ })
                    delay(60)
                    element().byLabel("„Plan.pdf“ entfernen").click()
                    awaitUntil("the delete request", timeoutMs = 1000) { calls.any { it.method == "DELETE" } && changed == 1 }
                    assertTrue(calls.single { it.method == "DELETE" }.url.endsWith("/api/mailing/attachments/a7"))
                }
            }
        }

    @Test
    fun choosingFiles_uploadsThemOneAfterTheOther_inALiveRegion_thenReloads(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 201, text = """{"attachmentId":"new"}""") }) { calls ->
                mountedForm("ma-upload") { root, element ->
                    var changed = 0
                    renderMailingAttachments(root, message(emptyList()), { changed++ })
                    delay(60)
                    chooseFiles(element().querySelector("input[type=file]") as HTMLInputElement, "a.pdf", "b.pdf")
                    awaitUntil("both uploaded", timeoutMs = 1500) { changed == 1 }
                    val posts = calls.filter { it.method == "POST" }
                    assertEquals(2, posts.size)
                    assertTrue(posts.all { it.url.endsWith("/api/mailing/messages/msg-1/attachments") })
                    assertEquals(
                        listOf("formdata:{\"file\":\"<file:a.pdf:8>\"}", "formdata:{\"file\":\"<file:b.pdf:8>\"}"),
                        posts.map { it.body },
                    )
                    val live = assertNotNull(element().querySelector("[role=status][aria-live=polite]") as? HTMLElement)
                    assertTrue(live.textContent.orEmpty().contains("Hochgeladen"), live.textContent)
                }
            }
        }

    @Test
    fun aRefusedUpload_showsTheFixedTextOfItsCode_notTheServerBody(): Promise<Unit> =
        formTest {
            withFetchStub(
                respond = { StubResponse(status = 415, text = """{"code":"MACRO_OR_EMBEDDED","detail":"SERVER-INTERNAL-DETAIL"}""") },
            ) { _ ->
                mountedForm("ma-refused") { root, element ->
                    renderMailingAttachments(root, message(emptyList()), {})
                    delay(60)
                    chooseFiles(element().querySelector("input[type=file]") as HTMLInputElement, "makro.pdf")
                    awaitUntil("the failure is shown", timeoutMs = 1500) {
                        element().textContent.orEmpty().contains("enthält Makros oder eingebettete Objekte und wird nicht angenommen")
                    }
                    assertFalse(element().textContent.orEmpty().contains("SERVER-INTERNAL-DETAIL"))
                }
            }
        }

    @Test
    fun aFileTypeThatIsNotWhitelisted_isRefusedBeforeAnyRequest(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 201, text = """{"attachmentId":"new"}""") }) { calls ->
                mountedForm("ma-precheck") { root, element ->
                    var changed = 0
                    renderMailingAttachments(root, message(emptyList()), { changed++ })
                    delay(60)
                    chooseFiles(element().querySelector("input[type=file]") as HTMLInputElement, "virus.exe")
                    awaitUntil("done", timeoutMs = 1500) { changed == 1 }
                    assertEquals(0, calls.count { it.method == "POST" })
                    assertTrue(element().textContent.orEmpty().contains("nicht unterstütztes Format"))
                }
            }
        }

    @Test
    fun moreFilesThanFreeSlots_takesTheFirstOnes_andSaysSo(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 201, text = """{"attachmentId":"new"}""") }) { calls ->
                mountedForm("ma-spill") { root, element ->
                    var changed = 0
                    val existing = (1..4).map { attachment("a$it", "Alt $it.pdf") }
                    renderMailingAttachments(root, message(existing), { changed++ })
                    delay(60)
                    chooseFiles(element().querySelector("input[type=file]") as HTMLInputElement, "neu1.pdf", "neu2.pdf", "neu3.pdf")
                    awaitUntil("done", timeoutMs = 1500) { changed == 1 }
                    assertEquals(1, calls.count { it.method == "POST" })
                    assertTrue(
                        element().textContent.orEmpty().contains(
                            "Es sind nur noch 1 Anhänge möglich; die übrigen Dateien wurden nicht übernommen.",
                        ),
                        element().textContent,
                    )
                }
            }
        }

    @Test
    fun aSecondChoiceDuringARunningUpload_isRefusedWithANotice(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 201, text = """{"attachmentId":"new"}""", delayMs = 400) }) { calls ->
                mountedForm("ma-running") { root, element ->
                    renderMailingAttachments(root, message(emptyList()), {})
                    delay(60)
                    val input = element().querySelector("input[type=file]") as HTMLInputElement
                    chooseFiles(input, "a.pdf")
                    delay(60)
                    chooseFiles(input, "b.pdf")
                    awaitUntil("the notice", timeoutMs = 1000) {
                        element().textContent.orEmpty().contains("Ein Upload läuft noch. Bitte wählen Sie die Dateien danach erneut aus.")
                    }
                    delay(600)
                    assertEquals(1, calls.count { it.method == "POST" })
                }
            }
        }

    @Test
    fun precheck_appliesTheRulesInOrder_withBinaryBoundaries() {
        val ten = AttachmentRules.MAX_FILE_BYTES
        assertNull(mailingAttachmentPrecheck("a.pdf", ten, emptyList(), emptyList()), "exactly 10 MiB is fine")
        assertTrue(mailingAttachmentPrecheck("a.pdf", ten + 1, emptyList(), emptyList())!!.contains("zu groß"))
        assertTrue(mailingAttachmentPrecheck("a.exe", 10, emptyList(), emptyList())!!.contains("nicht unterstütztes Format"))
        assertTrue(mailingAttachmentPrecheck("ohne-endung", 10, emptyList(), emptyList())!!.contains("nicht unterstütztes Format"))
        // total: 10 + 10 MiB is exactly the limit, one more byte is not
        val full = listOf(attachment("a", "a.pdf", size = ten), attachment("b", "b.pdf", size = ten))
        assertNull(mailingAttachmentPrecheck("c.pdf", 0, full, emptyList()))
        assertTrue(mailingAttachmentPrecheck("c.pdf", 1, full, emptyList())!!.contains("größer als 20 MB"))
        assertTrue(
            mailingAttachmentPrecheck(
                "c.pdf",
                ten,
                listOf(attachment("a", "a.pdf", size = ten)),
                listOf("b.pdf" to ten + 1),
            )!!.contains("20 MB"),
        )
        // count: five existing + one in this selection
        val five = (1..5).map { attachment("x$it", "x$it.pdf", size = 1) }
        assertTrue(mailingAttachmentPrecheck("neu.pdf", 1, five, emptyList())!!.contains("Höchstens 5 Anhänge"))
        assertTrue(mailingAttachmentPrecheck("neu.pdf", 1, five.take(4), listOf("y.pdf" to 1L))!!.contains("Höchstens 5 Anhänge"))
        // duplicate names compare lower-case, against existing files and the same selection
        assertTrue(
            mailingAttachmentPrecheck(
                "PLAN.PDF",
                1,
                listOf(attachment("p", "plan.pdf", size = 1)),
                emptyList(),
            )!!.contains("schon vorhanden"),
        )
        assertTrue(mailingAttachmentPrecheck("plan.pdf", 1, emptyList(), listOf("Plan.PDF" to 1L))!!.contains("schon vorhanden"))
        // the file name is untrusted text: no marker survives in the message
        assertFalse(mailingAttachmentPrecheck("x${KV_I18N_MARKER}y.exe", 1, emptyList(), emptyList())!!.contains(KV_I18N_MARKER))
    }

    @Test
    fun refusalTexts_existForEveryServerCode_andNeverEchoTheServer() {
        val codes =
            network.lapis.cloud.shared.domain.AttachmentErrorCode.entries
                .map { it.name }
        val texts = codes.associateWith { mailingAttachmentRefusalText(it, "x.pdf") }
        texts.forEach { (code, text) ->
            assertTrue(text.isNotBlank(), code)
            assertFalse(text.contains("###"), code)
        }
        assertEquals(texts.getValue("RATE_LIMITED"), texts.getValue("RATE_LIMITED"))
        assertEquals("Hochladen fehlgeschlagen.", mailingAttachmentRefusalText("", "x.pdf"), "an unknown or empty code is generic")
        assertEquals("Hochladen fehlgeschlagen.", mailingAttachmentRefusalText("SOMETHING_NEW", "x.pdf"))
        assertTrue(mailingAttachmentRefusalText("MACRO_OR_EMBEDDED", "x.docx").contains("enthält Makros oder eingebettete Objekte"))
        assertTrue(mailingAttachmentRefusalText("DUPLICATE_NAME", "x.pdf").contains("schon vorhanden"))
    }

    @Test
    fun theSendConfirmationLine_existsOnlyWithAttachments_inSingularAndPlural() {
        assertNull(mailingSendAttachmentsLine(emptyList()))
        assertEquals("Mit 1 Anhang (2 KB).", mailingSendAttachmentsLine(listOf(attachment("a", "a.pdf", size = 2048))))
        assertEquals(
            "Mit 2 Anhängen (3,0 MB).",
            mailingSendAttachmentsLine(
                listOf(attachment("a", "a.pdf", size = 2 * 1024 * 1024), attachment("b", "b.pdf", size = 1024 * 1024)),
            ),
        )
    }

    @Test
    fun theComposeForm_tellsThatAttachmentsComeAfterSaving(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() }) { _ ->
                mountedForm("ma-compose-hint") { root, element ->
                    val list =
                        MailingListDto(
                            id = "list-1",
                            name = "Rundbrief",
                            description = null,
                            createdBy = "x",
                            subscriberCount = 0,
                            isSubscribedByCurrentMember = false,
                        )
                    renderMailingListDetail(root, list, refreshSelfService = {})
                    delay(120)
                    assertTrue(
                        element().textContent.orEmpty().contains("Anhänge fügen Sie nach dem Speichern beim Entwurf hinzu."),
                        element().textContent,
                    )
                }
            }
        }
}

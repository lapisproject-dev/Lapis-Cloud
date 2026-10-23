package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.TravelExpenseAttachmentDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
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

/** V1.9.198 -- the "Anlagen" section of a travel-expense report: editable in a draft, read-only afterwards, names are untrusted text. */
class TravelExpenseAttachmentsDomTest {
    private fun attachment(
        id: String,
        name: String,
    ) = TravelExpenseAttachmentDto(
        id = id,
        reportId = "report-1",
        originalFilename = name,
        mimeType = "application/pdf",
        sizeBytes = 2048,
        sha256 = "0".repeat(64),
        uploadedAt = LocalDateTime(2026, 3, 2, 10, 0),
    )

    private fun report(
        attachments: List<TravelExpenseAttachmentDto>,
        status: TravelExpenseReportStatus = TravelExpenseReportStatus.DRAFT,
    ) = TravelExpenseReportDto(
        id = "report-1",
        subjectMemberId = "member-1",
        subjectDisplayName = "Amara Okafor",
        status = status,
        purpose = "Delegiertenversammlung",
        travelFrom = LocalDate(2026, 3, 10),
        travelTo = LocalDate(2026, 3, 12),
        totalAmount = 0.0.toDecimal(),
        createdAt = LocalDateTime(2026, 3, 1, 9, 0),
        requestedBy = "member-1",
        requestedByDisplayName = "Amara Okafor",
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
    fun noAttachments_showsTheEmptyText_inBothModes_andNoTable(): Promise<Unit> =
        formTest {
            for (editable in listOf(true, false)) {
                mountedForm("te-att-empty-$editable") { root, element ->
                    renderTravelExpenseAttachments(root, report(emptyList()), editable = editable, onChanged = {})
                    delay(60)
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Keine Anlagen."), text)
                    assertNull(element().querySelector("table"))
                    assertEquals(editable, element().querySelector("input[type=file]") != null, "upload only while editable")
                }
            }
        }

    @Test
    fun editable_listsFilesAsText_withNamedIconButtons_andNeverInterpretsTheFileName(): Promise<Unit> =
        formTest {
            val evil = "<img src=x onerror=alert(1)>.pdf"
            mountedForm("te-att-editable") { root, element ->
                renderTravelExpenseAttachments(
                    root,
                    report(listOf(attachment("att-1", evil), attachment("att-2", "Einladung.pdf"))),
                    true,
                ) {}
                delay(60)
                val table = assertNotNull(element().querySelector("table") as? HTMLElement)
                assertNull(table.querySelector("img"), "a file name is text, never markup")
                assertTrue(table.textContent.orEmpty().contains(evil))
                assertEquals(listOf("Datei", "Größe", "Aktionen"), table.allOf("th").map { it.textContent?.trim().orEmpty() })
                assertTrue(
                    table
                        .allOf("th")
                        .last()
                        .classList
                        .contains("visually-hidden"),
                )
                for (name in listOf(
                    "„$evil“ herunterladen",
                    "„$evil“ entfernen",
                    "„Einladung.pdf“ herunterladen",
                    "„Einladung.pdf“ entfernen",
                )) {
                    val control = element().byLabel(name)
                    assertFalse(control.getAttribute("aria-label").orEmpty().contains("###"), "no i18n marker in the DOM")
                }
                assertTrue(element().textContent.orEmpty().contains("2 KB"))
            }
        }

    @Test
    fun readOnly_offersOnlyTheDownload_withTheFileNameInItsAccessibleName(): Promise<Unit> =
        formTest {
            mountedForm("te-att-readonly") { root, element ->
                renderTravelExpenseAttachments(root, report(listOf(attachment("att-1", "Einladung.pdf"))), false) {}
                delay(60)
                val download = element().allOf("a").first { it.textContent?.trim() == "Herunterladen" }
                assertEquals("„Einladung.pdf“ herunterladen", download.getAttribute("aria-label"))
                assertEquals(
                    "/api/travel-expenses/attachments/att-1/download",
                    download.getAttribute("href"),
                    "a real link, no script navigation",
                )
                assertTrue(element().allOf("button").none { it.textContent?.trim() == "Entfernen" })
                assertNull(element().querySelector("input[type=file]"))
                assertTrue(element().textContent.orEmpty().contains("Nach dem Einreichen können Anlagen nicht mehr geändert werden."))
            }
        }

    @Test
    fun aWithdrawnOrRejectedReport_listsTheNames_butOffersNoDownload_andSaysWhy(): Promise<Unit> =
        formTest {
            for (status in listOf(TravelExpenseReportStatus.WITHDRAWN, TravelExpenseReportStatus.REJECTED)) {
                mountedForm("te-att-deleted-$status") { root, element ->
                    renderTravelExpenseAttachments(root, report(listOf(attachment("att-1", "Einladung.pdf")), status), false) {}
                    delay(60)
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Einladung.pdf"), text)
                    assertTrue(text.contains("Datei nach Rückzug/Ablehnung gelöscht"), text)
                    assertNull(element().allOf("a").firstOrNull { it.textContent?.trim() == "Herunterladen" }, "no dead download link")
                }
            }
        }

    @Test
    fun atTheLimit_theChooserIsDisabled_andTheHintIsShown(): Promise<Unit> =
        formTest {
            mountedForm("te-att-limit") { root, element ->
                val full = (1..10).map { attachment("att-$it", "Datei $it.pdf") }
                renderTravelExpenseAttachments(root, report(full), true) {}
                delay(60)
                assertTrue((element().querySelector("input[type=file]") as HTMLInputElement).disabled)
                assertTrue(element().textContent.orEmpty().contains("Höchstens 10 Anlagen pro Abrechnung."))
            }
        }

    @Test
    fun remove_sendsDelete_withoutConfirmation_andReloads(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 200) }) { calls ->
                mountedForm("te-att-remove") { root, element ->
                    var changed = 0
                    renderTravelExpenseAttachments(root, report(listOf(attachment("att-7", "Plan.pdf"))), true) { changed++ }
                    delay(60)
                    element().byLabel("„Plan.pdf“ entfernen").click()
                    awaitUntil("the delete request", timeoutMs = 1000) { calls.any { it.method == "DELETE" } && changed == 1 }
                    assertTrue(calls.single { it.method == "DELETE" }.url.endsWith("/api/travel-expenses/attachments/att-7"))
                }
            }
        }

    @Test
    fun choosingFiles_uploadsThemOneAfterTheOther_showsTheStatusInALiveRegion_thenReloads(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 201, text = """{"attachmentId":"new"}""") }) { calls ->
                mountedForm("te-att-upload") { root, element ->
                    var changed = 0
                    renderTravelExpenseAttachments(root, report(emptyList()), true) { changed++ }
                    delay(60)
                    val input = element().querySelector("input[type=file]") as HTMLInputElement
                    assertEquals("application/pdf,image/jpeg,image/png", input.getAttribute("accept"))
                    assertTrue(input.multiple)
                    chooseFiles(input, "a.pdf", "b.pdf")
                    awaitUntil("both uploaded", timeoutMs = 1500) { changed == 1 }
                    val posts = calls.filter { it.method == "POST" }
                    assertEquals(2, posts.size)
                    assertTrue(posts.all { it.url.endsWith("/api/travel-expenses/reports/report-1/attachments") })
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
    fun aRefusedUpload_showsAFixedGermanText_notTheServerBody(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 415, text = "SERVER-INTERNAL-DETAIL") }) { _ ->
                mountedForm("te-att-refused") { root, element ->
                    renderTravelExpenseAttachments(root, report(emptyList()), true) {}
                    delay(60)
                    chooseFiles(element().querySelector("input[type=file]") as HTMLInputElement, "a.pdf")
                    awaitUntil("the failure is shown", timeoutMs = 1500) {
                        element().textContent.orEmpty().contains("nicht unterstütztes Format")
                    }
                    assertFalse(element().textContent.orEmpty().contains("SERVER-INTERNAL-DETAIL"))
                }
            }
        }

    @Test
    fun aSecondChoiceDuringARunningUpload_isRefusedWithANotice_andUploadsNothingMore(): Promise<Unit> =
        formTest {
            withFetchStub(respond = { StubResponse(status = 201, text = """{"attachmentId":"new"}""", delayMs = 400) }) { calls ->
                mountedForm("te-att-running") { root, element ->
                    renderTravelExpenseAttachments(root, report(emptyList()), true) {}
                    delay(60)
                    val input = element().querySelector("input[type=file]") as HTMLInputElement
                    chooseFiles(input, "a.pdf")
                    delay(60)
                    chooseFiles(input, "b.pdf")
                    awaitUntil("the notice", timeoutMs = 1000) {
                        element().textContent.orEmpty().contains("Ein Upload läuft noch. Bitte wählen Sie die Dateien danach erneut aus.")
                    }
                    delay(600)
                    assertEquals(1, calls.count { it.method == "POST" }, "the second choice must not be uploaded")
                }
            }
        }

    @Test
    fun uploadStatusRegion_keepsTwoLinesForTwoDifferentFilesWithTheSameName_andOneForTheSameFile(): Promise<Unit> =
        formTest {
            mountedForm("te-att-status-keys") { root, element ->
                val first = File(arrayOf<dynamic>("1"), "image.jpg", js("({type: 'image/jpeg'})"))
                val second = File(arrayOf<dynamic>("2"), "image.jpg", js("({type: 'image/jpeg'})"))
                val region = UploadStatusRegion(root)
                region.set("image.jpg", "Wird hochgeladen…", first)
                region.set("image.jpg", "Wird hochgeladen…", second)
                delay(30)
                val live = assertNotNull(element().querySelector("[role=status]") as? HTMLElement)
                assertEquals(2, live.children.length, live.textContent)
                region.set("image.jpg", "Hochgeladen", first)
                delay(30)
                assertEquals(2, live.children.length, "the same file updates its own line")
                assertTrue(live.textContent.orEmpty().contains("Hochgeladen"))
            }
        }

    @Test
    fun leftoverReceiptFiles_areScopedToTheirReport_andOfferNoRetryForTheUnknownLine(): Promise<Unit> =
        formTest {
            pendingFailedReceiptUploads.clear()
            try {
                pendingFailedReceiptUploads[unknownLineKey("report-A")] = mutableListOf(File(arrayOf<dynamic>("x"), "a-rest.pdf"))
                mountedForm("te-att-scope-b") { root, element ->
                    renderFailedReceiptUploads(root, unknownLineKey("report-B"), {})
                    delay(30)
                    assertFalse(element().textContent.orEmpty().contains("a-rest.pdf"), "leftovers of report A are not shown in B")
                }
                mountedForm("te-att-scope-a") { root, element ->
                    renderFailedReceiptUploads(root, unknownLineKey("report-A"), {})
                    delay(30)
                    assertTrue(element().textContent.orEmpty().contains("a-rest.pdf"))
                    assertNull(
                        element().allOf("button").firstOrNull { it.textContent?.trim() == "Erneut hochladen" },
                        "no retry without a known line",
                    )
                    assertNotNull(element().allOf("button").firstOrNull { it.textContent?.trim() == "Verwerfen" })
                }
            } finally {
                pendingFailedReceiptUploads.clear()
            }
        }

    @Test
    fun theComfortPrecheck_refusesHeicAndOversizedFiles_butAcceptsTheThreeFormats() {
        assertNull(travelExpenseFilePrecheck("a.pdf", "application/pdf", 10))
        assertNull(travelExpenseFilePrecheck("a.JPG", "image/jpeg", 10))
        assertNull(travelExpenseFilePrecheck("a.png", "", 10))
        assertNotNull(travelExpenseFilePrecheck("foto.heic", "image/heic", 10))
        assertNotNull(travelExpenseFilePrecheck("a.webp", "image/webp", 10))
        assertNotNull(travelExpenseFilePrecheck("a.pdf", "application/pdf", 10L * 1024 * 1024 + 1))
        assertEquals("Hochladen fehlgeschlagen.", travelExpenseUploadRefusalText(500, "x.pdf"))
        assertTrue(travelExpenseUploadRefusalText(409, "x.pdf").contains("Höchstzahl"))
        assertTrue(travelExpenseUploadRefusalText(429, "x.pdf").contains("Zu viele Anfragen"))
    }
}

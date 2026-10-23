package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import org.w3c.dom.HTMLButtonElement
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

/**
 * V1.9.191 -- the "Weitere Kosten" section of the draft editor: one list, one "Kosten hinzufügen" button, an inline form with a segmented
 * choice of the cost kind (a draft per kind), editing in place without a kind selector, removal, and the focus targets.
 */
class TravelExpenseExtraCostsDomTest {
    private val memberSession =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Amara Okafor",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun rates(mileageCentsPerKm: Long? = 30) =
        TravelExpenseRatesDto(
            mileageRatePerKm = 0.3.toDecimal(),
            perDiemRate = 28.0.toDecimal(),
            expenseAccountConfigured = true,
            bankAccountConfigured = true,
            rateSet = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = mileageCentsPerKm),
        )

    private val mileageLine =
        TravelExpenseLineDto(
            id = "line-km",
            reportId = "report-1",
            kind = TravelExpenseLineKind.MILEAGE,
            description = "Zugfahrt Hannover",
            kilometers = 100.0.toDecimal(),
            rateSnapshot = 0.3.toDecimal(),
            passengerCount = 1,
            amount = 30.0.toDecimal(),
            amountCents = 3000,
        )

    private val otherLine =
        TravelExpenseLineDto(
            id = "line-other",
            reportId = "report-1",
            kind = TravelExpenseLineKind.OTHER,
            description = "Taxi zum Bahnhof",
            amount = 12.5.toDecimal(),
            amountCents = 1250,
        )

    private val legacyLine =
        TravelExpenseLineDto(
            id = "line-legacy",
            reportId = "report-1",
            kind = TravelExpenseLineKind.RECEIPTED,
            description = "Altbeleg",
            amount = 5.0.toDecimal(),
            amountCents = 500,
        )

    private fun draft(lines: List<TravelExpenseLineDto> = emptyList()) =
        TravelExpenseReportDto(
            id = "report-1",
            subjectMemberId = "member-1",
            subjectDisplayName = "Amara Okafor",
            status = TravelExpenseReportStatus.DRAFT,
            purpose = "Delegiertenversammlung",
            travelFrom = LocalDate(2026, 3, 10),
            travelTo = LocalDate(2026, 3, 12),
            totalAmount = 0.0.toDecimal(),
            lines = lines,
            createdAt = LocalDateTime(2026, 3, 1, 9, 0),
            requestedBy = "member-1",
            requestedByDisplayName = "Amara Okafor",
        )

    private class Routes(
        val addLine: String,
        val updateLine: String,
        val removeLine: String,
        val list: String = "",
        val submit: String = "",
    )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private suspend fun withEditor(
        id: String,
        report: TravelExpenseReportDto,
        ratesDto: TravelExpenseRatesDto = rates(),
        override: (RecordedRequest, Routes) -> StubResponse? = { _, _ -> null },
        block: suspend (element: () -> HTMLElement, calls: List<RecordedRequest>, routes: Routes) -> Unit,
    ) {
        val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
        val listRoute = routeOf { rpcService<ITravelExpenseService>().listMyReports() }
        val input = TravelExpenseLineInput(kind = TravelExpenseLineKind.OTHER, description = "x")
        val routes =
            Routes(
                addLine = routeOf { rpcService<ITravelExpenseService>().addLine("x", input) },
                updateLine = routeOf { rpcService<ITravelExpenseService>().updateLine("x", input) },
                removeLine = routeOf { rpcService<ITravelExpenseService>().removeLine("x") },
                list = listRoute,
                submit = routeOf { rpcService<ITravelExpenseService>().submitReport("x") },
            )
        val plain =
            answering(
                ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), ratesDto),
                listRoute to jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(report)),
                routes.addLine to jsonOf(TravelExpenseReportDto.serializer(), report),
                routes.updateLine to jsonOf(TravelExpenseReportDto.serializer(), report),
                routes.removeLine to jsonOf(TravelExpenseReportDto.serializer(), report),
            )
        withFetchStub(respond = { request -> override(request, routes) ?: plain(request) }) { calls ->
            mountedForm(id) { root, element ->
                renderTravelExpenseScreen(root, null)
                awaitUntil("editor rendered", timeoutMs = 1000) { element().textContent.orEmpty().contains("Weitere Kosten") }
                delay(120)
                block(element, calls, routes)
            }
        }
    }

    private fun HTMLElement.section(): HTMLElement {
        val heading = allOf("h2").first { it.textContent?.trim() == "Weitere Kosten" }
        return assertNotNull(heading.nextElementSibling as? HTMLElement, "the section body follows its heading")
    }

    private fun HTMLElement.byLabel(label: String): HTMLElement =
        assertNotNull(allOf("button").firstOrNull { it.getAttribute("aria-label") == label }, "no icon button '$label'")

    private fun HTMLElement.checkedKind(): String =
        allOf("[role=radio]")
            .single {
                it.getAttribute("aria-checked") == "true"
            }.textContent
            .orEmpty()
            .trim()

    @Test
    fun emptyDraft_showsTheEmptyText_andExactlyOneAddButton_noTable(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-empty", draft()) { element, _, _ ->
                val section = element().section()
                assertTrue(section.textContent.orEmpty().contains("Noch keine weiteren Kosten erfasst."))
                assertNull(section.querySelector("table"), "no table without lines")
                assertEquals(1, section.allOf("button").count { it.textContent?.trim() == "Kosten hinzufügen" })
            }
        }

    @Test
    fun theList_isATableWithIconActions_namedAfterTheKind_andLegacyLinesCannotBeEdited(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-list", draft(listOf(mileageLine, otherLine, legacyLine))) { element, _, _ ->
                val section = element().section()
                val table = assertNotNull(section.querySelector("table") as? HTMLElement)
                val headers = table.allOf("th").map { it.textContent?.trim().orEmpty() }
                assertEquals(listOf("Art", "Angaben", "Betrag", "Aktionen"), headers)
                assertTrue(
                    table
                        .allOf("th")
                        .last()
                        .classList
                        .contains("visually-hidden"),
                    "the actions header is for assistive technology only",
                )
                val text = table.textContent.orEmpty()
                assertTrue(text.contains("Fahrt") && text.contains("Zugfahrt Hannover") && text.contains("100"), text)
                assertTrue(text.contains("Taxi zum Bahnhof"))
                // The kind is a word, no badge, no icon in the first column.
                val firstCell =
                    table
                        .allOf("tbody tr")
                        .first()
                        .allOf("td")
                        .first()
                assertEquals("Fahrt", firstCell.textContent?.trim())
                assertNull(firstCell.querySelector("i"), "no icon in the kind column")
                for (name in listOf(
                    "Fahrt bearbeiten",
                    "Fahrt entfernen",
                    "Sonstiges bearbeiten",
                    "Sonstiges entfernen",
                    "Beleg-Kosten entfernen",
                )) {
                    val button = section.byLabel(name)
                    assertEquals(name, button.getAttribute("title"), "title and aria-label name the kind")
                    assertFalse(button.getAttribute("aria-label").orEmpty().contains("###"), "no i18n marker in the DOM")
                }
                assertTrue(
                    section.allOf("button").none { it.getAttribute("aria-label") == "Beleg-Kosten bearbeiten" },
                    "a legacy line has no edit icon",
                )
                // The receipt block survives (mandatory for OTHER, optional map proof for mileage).
                assertTrue(text.contains("Kartennachweis hochladen"), "mileage keeps the optional map proof")
                assertTrue(text.contains("Beleg hochladen"), "a receipted kind keeps its receipt upload")
            }
        }

    @Test
    fun addForm_keepsADraftPerKind_andSendsTheChosenKind_thenTheAddButtonComesBack(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-add", draft()) { element, calls, routes ->
                element().buttonNamed("Kosten hinzufügen").click()
                delay(120)
                val section = element().section()
                assertTrue(
                    section.allOf("button").none {
                        it.textContent?.trim() == "Kosten hinzufügen"
                    },
                    "the add button is hidden while the form is open",
                )
                assertEquals("Fahrt", section.checkedKind(), "the first available kind is preselected")
                assertEquals("Kostenart", section.querySelector("[role=radiogroup]")?.getAttribute("aria-label"))
                assertEquals(
                    listOf("Fahrt", "ÖPNV", "Übernachtung", "Bewirtung", "Sonstiges"),
                    section.allOf("[role=radio]").map {
                        it.textContent.orEmpty().trim()
                    },
                )

                section.buttonNamed("ÖPNV").click()
                delay(80)
                section.typeInto("Beschreibung", "Zug Hannover")
                section.typeInto("Betrag", "12,50")
                section.buttonNamed("Sonstiges").click()
                delay(80)
                assertEquals("", (section.controlOf("Beschreibung") as HTMLInputElement).value, "another kind starts empty")
                section.typeInto("Beschreibung", "Parkgebühr")
                section.buttonNamed("ÖPNV").click()
                delay(80)
                assertEquals(
                    "Zug Hannover",
                    (section.controlOf("Beschreibung") as HTMLInputElement).value,
                    "the typed text of a kind comes back",
                )
                assertEquals("12,50", (section.controlOf("Betrag") as HTMLInputElement).value)
                assertTrue(
                    section
                        .allOf(
                            "[role=alert].lapis-form-alert, .lapis-field-error--shown",
                        ).none { it.textContent.orEmpty().isNotBlank() },
                )

                section.buttonNamed("Hinzufügen").click()
                awaitUntil("addLine", timeoutMs = 1000) { calls.toRoute(routes.addLine).size == 1 }
                val call = calls.singleCall(routes.addLine)
                assertEquals("report-1", call.rpcParam(0) as String)
                val input = call.rpcParam(1)
                assertEquals("PUBLIC_TRANSPORT", input.kind as String)
                assertEquals("Zug Hannover", input.description as String)
                assertEquals(1250.0, (input.amountCents as Number).toDouble())
            }
        }

    @Test
    fun cancel_closesTheForm_bringsTheButtonBack_andFocusesIt(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-cancel", draft()) { element, calls, routes ->
                element().buttonNamed("Kosten hinzufügen").click()
                awaitUntil("the chosen segment has the focus") {
                    val active = document.activeElement as? HTMLElement
                    active != null && active.getAttribute("role") == "radio" && active.getAttribute("aria-checked") == "true"
                }
                element().section().buttonNamed("Abbrechen").click()
                awaitUntil("the add button has the focus again") {
                    (document.activeElement as? HTMLElement)?.textContent?.trim() ==
                        "Kosten hinzufügen"
                }
                assertNull(element().section().querySelector("[role=radiogroup]"))
                assertTrue(calls.toRoute(routes.addLine).isEmpty())
            }
        }

    @Test
    fun mileageSegment_isDisabledWithoutARate_andTheFirstEnabledKindIsPreselected(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-no-rate", draft(), ratesDto = rates(mileageCentsPerKm = null)) { element, _, _ ->
                element().buttonNamed("Kosten hinzufügen").click()
                delay(120)
                val section = element().section()
                val mileage = section.allOf("[role=radio]").first()
                assertEquals("true", mileage.getAttribute("aria-disabled"))
                assertEquals("Kilometersatz ist noch nicht konfiguriert.", mileage.getAttribute("title"))
                assertEquals("ÖPNV", section.checkedKind())
            }
        }

    @Test
    fun edit_opensInPlace_withoutAKindSelector_disablesTheOtherEditIcons_andSendsUpdateLine(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-edit", draft(listOf(mileageLine, otherLine))) { element, calls, routes ->
                val section = element().section()
                section.byLabel("Sonstiges bearbeiten").click()
                delay(120)
                assertNull(section.querySelector("[role=radiogroup]"), "the kind of an existing line is fixed")
                assertTrue(section.allOf("h3").any { it.textContent?.trim() == "Sonstiges bearbeiten" })
                assertEquals("Taxi zum Bahnhof", (section.controlOf("Beschreibung") as HTMLInputElement).value)
                assertEquals("12,50", (section.controlOf("Betrag") as HTMLInputElement).value)
                assertTrue((section.byLabel("Fahrt bearbeiten") as HTMLButtonElement).disabled, "only one form at a time")
                assertTrue((section.allOf("button").first { it.textContent?.trim() == "Kosten hinzufügen" } as HTMLButtonElement).disabled)

                section.typeInto("Betrag", "15,00")
                section.buttonNamed("Speichern").click()
                awaitUntil("updateLine", timeoutMs = 1000) { calls.toRoute(routes.updateLine).size == 1 }
                val call = calls.singleCall(routes.updateLine)
                assertEquals("line-other", call.rpcParam(0) as String)
                assertEquals(1500.0, (call.rpcParam(1).amountCents as Number).toDouble())
                assertEquals("OTHER", call.rpcParam(1).kind as String)
                assertTrue(calls.toRoute(routes.addLine).isEmpty())
            }
        }

    @Test
    fun cancelOfAnEdit_returnsTheFocusToTheEditIconOfThatLine(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-edit-cancel", draft(listOf(otherLine))) { element, _, _ ->
                val section = element().section()
                section.byLabel("Sonstiges bearbeiten").click()
                delay(120)
                section.buttonNamed("Abbrechen").click()
                awaitUntil("the edit icon has the focus") {
                    (document.activeElement as? HTMLElement)?.getAttribute("aria-label") ==
                        "Sonstiges bearbeiten"
                }
                assertFalse((element().section().byLabel("Sonstiges bearbeiten") as HTMLButtonElement).disabled)
            }
        }

    @Test
    fun remove_sendsRemoveLineForThatLine(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-remove", draft(listOf(mileageLine, otherLine))) { element, calls, routes ->
                element().section().byLabel("Sonstiges entfernen").click()
                awaitUntil("removeLine", timeoutMs = 1000) { calls.toRoute(routes.removeLine).size == 1 }
                assertEquals("line-other", calls.singleCall(routes.removeLine).rpcParam(0) as String)
            }
        }

    // ── V1.9.198: receipts in the add form, attachments section, missing-receipt wiring ──

    private fun chooseFilesIn(
        input: HTMLInputElement,
        vararg files: File,
    ) {
        val transfer = js("new DataTransfer()")
        files.forEach { transfer.items.add(it) }
        input.asDynamic().files = transfer.files
        input.dispatchEvent(Event("change"))
    }

    private fun pdf(name: String) = File(arrayOf<dynamic>("%PDF-1.4"), name, js("({type: 'application/pdf'})"))

    private fun HTMLElement.picker(): HTMLElement =
        assertNotNull(querySelector(".lapis-te-receipt-picker") as? HTMLElement, "no receipt picker")

    private fun HTMLElement.openAddFormFor(kind: String) = buttonNamed(kind).click()

    @Test
    fun draftEditor_hasTheAttachmentsSection_afterTheCosts(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-attachments-section", draft()) { element, _, _ ->
                val headings = element().allOf("h2").map { it.textContent?.trim().orEmpty() }
                assertTrue(headings.indexOf("Anlagen") > headings.indexOf("Weitere Kosten"), headings.toString())
                assertTrue(element().textContent.orEmpty().contains("Keine Anlagen."))
            }
        }

    @Test
    fun addForm_offersTheReceiptPicker_onlyForReceiptedKinds_withTheRightFileInputs(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-picker", draft()) { element, _, _ ->
                element().buttonNamed("Kosten hinzufügen").click()
                delay(120)
                val section = element().section()
                assertNull(section.querySelector(".lapis-te-receipt-picker"), "mileage keeps its optional map proof on the row, no picker")
                section.buttonNamed("ÖPNV").click()
                delay(80)
                val picker = section.picker()
                val labelId = assertNotNull(picker.getAttribute("aria-labelledby"))
                assertEquals("Belege", picker.querySelector("#$labelId")?.textContent?.trim())
                assertEquals("group", picker.getAttribute("role"))
                val inputs = picker.allOf("input[type=file]").map { it as HTMLInputElement }
                assertEquals(2, inputs.size)
                val chooser = inputs[0]
                assertTrue(chooser.multiple)
                assertEquals("application/pdf,image/jpeg,image/png", chooser.getAttribute("accept"))
                val camera = inputs[1]
                assertEquals("image/*", camera.getAttribute("accept"))
                assertEquals("environment", camera.getAttribute("capture"))
                assertTrue(camera.closest(".lapis-te-camera") != null, "the camera chooser sits in the touch-only wrapper")
                assertTrue(picker.textContent.orEmpty().contains("Ohne Beleg kann die Abrechnung nicht eingereicht werden."))
            }
        }

    @Test
    fun picker_refusesHeicAndOversizedFiles_atTheField_andCapsTheCount(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-picker-precheck", draft()) { element, _, _ ->
                element().buttonNamed("Kosten hinzufügen").click()
                delay(120)
                val section = element().section()
                section.buttonNamed("ÖPNV").click()
                delay(80)
                val chooser = section.picker().allOf("input[type=file]").first() as HTMLInputElement
                chooseFilesIn(chooser, File(arrayOf<dynamic>("x"), "foto.heic", js("({type: 'image/heic'})")))
                assertTrue(
                    section
                        .picker()
                        .textContent
                        .orEmpty()
                        .contains("nicht unterstütztes Format"),
                    section.picker().textContent,
                )
                val big = File(arrayOf<dynamic>(js("new ArrayBuffer(11 * 1024 * 1024)")), "gross.pdf", js("({type: 'application/pdf'})"))
                chooseFilesIn(chooser, big)
                assertTrue(
                    section
                        .picker()
                        .textContent
                        .orEmpty()
                        .contains("zu groß"),
                    section.picker().textContent,
                )
                assertTrue(section.picker().allOf("button").none { it.getAttribute("aria-label")?.contains("aus der Auswahl") == true })
                chooseFilesIn(chooser, *(1..6).map { pdf("b$it.pdf") }.toTypedArray())
                val removes =
                    section.picker().allOf("button").filter {
                        it.getAttribute("aria-label")?.contains("aus der Auswahl entfernen") ==
                            true
                    }
                assertEquals(5, removes.size, "at most five receipts per line")
                assertTrue(
                    section
                        .picker()
                        .textContent
                        .orEmpty()
                        .contains("Höchstens 5 Belege pro Zeile."),
                )
                removes.first().click()
                assertEquals(
                    4,
                    section.picker().allOf("button").count { it.getAttribute("aria-label")?.contains("aus der Auswahl entfernen") == true },
                )
            }
        }

    private fun withNewLine(base: TravelExpenseReportDto): TravelExpenseReportDto =
        base.copy(
            lines =
                base.lines +
                    TravelExpenseLineDto(
                        id = "line-new",
                        reportId = "report-1",
                        kind = TravelExpenseLineKind.PUBLIC_TRANSPORT,
                        description = "Zug Hannover",
                        amount = 12.5.toDecimal(),
                        amountCents = 1250,
                    ),
        )

    @Test
    fun addingACostLine_createsTheLineFirst_thenUploadsTheChosenReceiptsOneAfterTheOther(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val base = draft()
            val withLine = withNewLine(base)
            var added = false
            withEditor(
                "te-extra-picker-success",
                base,
                override = { request, routes ->
                    when {
                        request.isRpc && request.rpcRoute == routes.addLine -> {
                            added = true
                            request.answerWith(jsonOf(TravelExpenseReportDto.serializer(), withLine))
                        }
                        request.isRpc && request.rpcRoute == routes.list ->
                            request.answerWith(
                                jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(if (added) withLine else base)),
                            )
                        !request.isRpc && request.method == "POST" -> StubResponse(status = 201, text = """{"receiptId":"r"}""")
                        else -> null
                    }
                },
            ) { element, calls, routes ->
                element().buttonNamed("Kosten hinzufügen").click()
                delay(120)
                val section = element().section()
                section.buttonNamed("ÖPNV").click()
                delay(80)
                section.typeInto("Beschreibung", "Zug Hannover")
                section.typeInto("Betrag", "12,50")
                chooseFilesIn(section.picker().allOf("input[type=file]").first() as HTMLInputElement, pdf("eins.pdf"), pdf("zwei.pdf"))
                section.buttonNamed("Hinzufügen").click()
                awaitUntil("both receipts sent", timeoutMs = 2000) { calls.count { !it.isRpc && it.method == "POST" } == 2 }
                val order = calls.filter { it.isRpc && it.rpcRoute == routes.addLine || !it.isRpc && it.method == "POST" }
                assertTrue(order.first().isRpc, "the line is created before any receipt is sent")
                val posts = order.drop(1)
                assertTrue(posts.all { it.url.endsWith("/api/travel-expenses/lines/line-new/receipts") })
                assertEquals(
                    listOf("<file:eins.pdf:8>", "<file:zwei.pdf:8>"),
                    posts.map { it.body.substringAfter(":\"").substringBefore("\"}") },
                )
            }
        }

    @Test
    fun aFailedReceiptUpload_keepsTheLine_listsTheFile_andOffersRetryAndDiscard(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            pendingFailedReceiptUploads.clear()
            val base = draft()
            val withLine = withNewLine(base)
            var added = false
            var uploads = 0
            withEditor(
                "te-extra-picker-failure",
                base,
                override = { request, routes ->
                    when {
                        request.isRpc && request.rpcRoute == routes.addLine -> {
                            added = true
                            request.answerWith(jsonOf(TravelExpenseReportDto.serializer(), withLine))
                        }
                        request.isRpc && request.rpcRoute == routes.list ->
                            request.answerWith(
                                jsonOf(ListSerializer(TravelExpenseReportDto.serializer()), listOf(if (added) withLine else base)),
                            )
                        !request.isRpc && request.method == "POST" -> {
                            uploads++
                            // first file ok, second refused (415), the retry of the second fails again
                            if (uploads == 2 ||
                                uploads == 3
                            ) {
                                StubResponse(status = 415, text = "x")
                            } else {
                                StubResponse(status = 201, text = """{"receiptId":"r"}""")
                            }
                        }
                        else -> null
                    }
                },
            ) { element, calls, _ ->
                element().buttonNamed("Kosten hinzufügen").click()
                delay(120)
                val section = element().section()
                section.buttonNamed("ÖPNV").click()
                delay(80)
                section.typeInto("Beschreibung", "Zug Hannover")
                section.typeInto("Betrag", "12,50")
                chooseFilesIn(section.picker().allOf("input[type=file]").first() as HTMLInputElement, pdf("gut.pdf"), pdf("kaputt.pdf"))
                section.buttonNamed("Hinzufügen").click()
                awaitUntil("the failed file is listed", timeoutMs = 2500) { element().textContent.orEmpty().contains("Nicht hochgeladen:") }
                val failedBlock = assertNotNull(element().querySelector(".lapis-te-failed-uploads") as? HTMLElement)
                assertTrue(failedBlock.textContent.orEmpty().contains("kaputt.pdf"))
                assertFalse(failedBlock.textContent.orEmpty().contains("gut.pdf"))
                val retry = element().byLabel("„kaputt.pdf“ erneut hochladen")
                val discard = element().byLabel("„kaputt.pdf“ verwerfen")
                retry.click()
                awaitUntil("the same file is sent again", timeoutMs = 1500) { calls.count { !it.isRpc && it.method == "POST" } == 3 }
                assertEquals("formdata:{\"file\":\"<file:kaputt.pdf:8>\"}", calls.last { !it.isRpc && it.method == "POST" }.body)
                assertTrue(element().textContent.orEmpty().contains("Nicht hochgeladen:"), "still failed -> still listed")
                element().byLabel("„kaputt.pdf“ verwerfen").click()
                awaitUntil("the list is empty", timeoutMs = 1500) { !element().textContent.orEmpty().contains("Nicht hochgeladen:") }
                assertTrue(pendingFailedReceiptUploads.isEmpty())
                @Suppress("UNUSED_EXPRESSION")
                discard
            }
        }

    @Test
    fun addingWithoutAnyFile_stillWorks_andSendsNoUpload(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            withEditor("te-extra-picker-nofile", draft()) { element, calls, routes ->
                element().buttonNamed("Kosten hinzufügen").click()
                delay(120)
                val section = element().section()
                section.buttonNamed("ÖPNV").click()
                delay(80)
                section.typeInto("Beschreibung", "Zug Hannover")
                section.typeInto("Betrag", "12,50")
                section.buttonNamed("Hinzufügen").click()
                awaitUntil("addLine", timeoutMs = 1000) { calls.toRoute(routes.addLine).size == 1 }
                delay(100)
                assertTrue(calls.none { !it.isRpc && it.method == "POST" })
            }
        }

    @Test
    fun missingReceipt_keepsTheSubmitButtonFocusable_wiresTheHint_andFocusesTheFirstAffectedChooser(): Promise<Unit> =
        formTest {
            AppState.setSession(memberSession)
            val report = draft(listOf(otherLine)).copy(payoutChoice = TravelExpensePayoutChoice.PAYOUT)
            withEditor("te-extra-missing-receipt", report) { element, calls, routes ->
                val hint = assertNotNull(element().querySelector("#lapis-te-missing-receipt") as? HTMLElement)
                assertEquals("Jede Beleg-Kosten-Zeile benötigt mindestens einen Beleg.", hint.textContent?.trim())
                val submit = element().buttonNamed("Zur Freigabe einreichen") as HTMLButtonElement
                assertFalse(submit.disabled, "not natively disabled, so it stays focusable")
                assertEquals("true", submit.getAttribute("aria-disabled"))
                assertEquals("lapis-te-missing-receipt", submit.getAttribute("aria-describedby"))
                val group = assertNotNull(element().querySelector("[data-lapis-te-first-missing]") as? HTMLElement)
                assertEquals(
                    "lapis-te-missing-receipt",
                    group.getAttribute("aria-describedby"),
                    "the first affected line's block is described by the hint",
                )
                val chooser = assertNotNull(group.querySelector("input[type=file]") as? HTMLInputElement)
                submit.click()
                delay(100)
                assertTrue(calls.toRoute(routes.submit).isEmpty(), "a click on the blocked button sends nothing")
                assertEquals(chooser, document.activeElement, "the focus goes to the file chooser of the first affected line")
            }
        }
}

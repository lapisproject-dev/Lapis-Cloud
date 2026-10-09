package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventImportPreviewDto
import network.lapis.cloud.shared.domain.EventImportPreviewRowDto
import network.lapis.cloud.shared.domain.EventImportResultDto
import network.lapis.cloud.shared.domain.EventImportRowStatus
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventImportService
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.82 -- the admin import screen: the import button only for BOARD/ADMIN, a preview with error rows first (status as symbol AND
 * word), a confirm button that stays disabled while there is an error or nothing to create, a locked text field in the preview, and a
 * commit that sends the text and hash of the preview unchanged.
 */
class EventImportScreenDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun row(
        index: Int,
        status: EventImportRowStatus,
        title: String,
        reasons: List<String> = emptyList(),
        hints: List<String> = emptyList(),
    ) = EventImportPreviewRowDto(
        index = index,
        slug = "slug-$index",
        title = title,
        status = status,
        reasons = reasons,
        hints = hints,
        startsAt = if (status == EventImportRowStatus.ERROR) null else LocalDateTime(2025, 3, 1, 19, 0),
        endsAt = if (status == EventImportRowStatus.ERROR) null else LocalDateTime(2025, 3, 1, 22, 0),
    )

    private fun preview(vararg rows: EventImportPreviewRowDto): EventImportPreviewDto =
        EventImportPreviewDto(
            rows = rows.toList(),
            createCount = rows.count { it.status == EventImportRowStatus.CREATE },
            skipCount = rows.count { it.status == EventImportRowStatus.SKIP_SLUG_EXISTS },
            errorCount = rows.count { it.status == EventImportRowStatus.ERROR },
            payloadSha256 = "ab".repeat(32),
            timeZoneId = "Europe/Berlin",
        )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    @Test
    fun theImportButtonOnTheEventsScreen_isOnlyThereForBoardAndAdmin(): Promise<Unit> =
        formTest {
            val list = routeOf { rpcService<IEventService>().listEvents() }
            for ((role, expected) in listOf(
                AccountRole.BOARD to true,
                AccountRole.ADMIN to true,
                AccountRole.MEMBER to false,
                AccountRole.TREASURER to false,
            )) {
                AppState.setSession(session(role))
                withFetchStub(
                    respond = { request ->
                        when {
                            !request.isRpc -> StubResponse()
                            request.rpcRoute == list ->
                                request.answerWith(
                                    jsonOf(EventPageDto.serializer(), EventPageDto(emptyList(), 0, 200, 0)),
                                )
                            else -> request.answerWith("[]")
                        }
                    },
                ) { _ ->
                    mountedForm("event-import-button-${role.name}") { root, element ->
                        renderEventsScreen(root)
                        awaitUntil("the empty list is shown") { element().text().contains("Keine Veranstaltungen gefunden.") }
                        val present = element().allOf("button").any { it.textContent?.trim() == "Importieren" }
                        assertEquals(expected, present, "import button for ${role.name}")
                    }
                }
            }
        }

    @Test
    fun aPreviewWithAnError_listsErrorsFirst_withSymbolAndWord_locksTheText_andBlocksTheConfirmation(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IEventImportService>().previewEventImport("[]") }
            val commitRoute = routeOf { rpcService<IEventImportService>().commitEventImport("[]", "x") }
            val answer =
                preview(
                    row(0, EventImportRowStatus.CREATE, "Gute Veranstaltung", hints = listOf("Ende fehlt, gleich Beginn gesetzt.")),
                    row(1, EventImportRowStatus.ERROR, "Kaputte Veranstaltung", reasons = listOf("Eintrag 2: Der Slug ist zu lang.")),
                    row(2, EventImportRowStatus.SKIP_SLUG_EXISTS, "Schon da"),
                )
            // the server already sends errors first; the screen keeps that order
            val ordered = answer.copy(rows = answer.rows.sortedWith(compareBy({ it.status != EventImportRowStatus.ERROR }, { it.index })))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == previewRoute -> request.answerWith(jsonOf(EventImportPreviewDto.serializer(), ordered))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("event-import-error-preview") { root, element ->
                    renderEventImportScreen(root)
                    val screen = element()
                    assertTrue(screen.text().contains("Zeiten in Ortszeit"), "the time zone hint is shown")
                    assertTrue(screen.text().contains("Titelbilder werden nicht importiert"))
                    val payload = "  [{\"slug\":\"x\"}]  \n"
                    screen.typeInto("Import-Daten", payload)
                    screen.buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { screen.text().contains("Kaputte Veranstaltung") }

                    // the exact text goes to the server (no trim, no normalization): the hash must match it
                    assertEquals(payload, calls.singleCall(previewRoute).rpcParam(0) as String)
                    assertEquals(0, calls.toRoute(commitRoute).size, "nothing is committed by a preview")

                    val rows = screen.allOf("tbody tr").ifEmpty { screen.allOf(".lapis-data-card") }
                    assertEquals(3, rows.size)
                    assertTrue(
                        rows[0].text().contains("Kaputte Veranstaltung") && rows[0].text().contains("Fehler"),
                        "error row first, with the word",
                    )
                    assertTrue(rows[0].text().contains("!"), "... and the symbol")
                    assertTrue(
                        rows[1].text().contains("Gute Veranstaltung") &&
                            rows[1].text().contains("Wird angelegt") &&
                            rows[1].text().contains("✓"),
                    )
                    assertTrue(rows[1].text().contains("Ende fehlt"), "the hint is shown")
                    assertTrue(rows[2].text().contains("Übersprungen") && rows[2].text().contains("↷"))
                    assertTrue(rows[0].text().contains("Der Slug ist zu lang"), "the reason is shown")

                    assertTrue(screen.text().contains("1 werden angelegt · 1 übersprungen · 1 Fehler"))
                    val confirm = screen.buttonNamed("1 Veranstaltungen importieren")
                    assertTrue(
                        confirm.getAttribute("disabled") != null || (confirm.asDynamic().disabled as Boolean),
                        "blocked by the error",
                    )
                    assertTrue((screen.controlOf("Import-Daten") as HTMLTextAreaElement).disabled, "the text is locked in the preview")

                    screen.buttonNamed("Zurück zum Bearbeiten").click()
                    awaitUntil("back in the editor") { !screen.text().contains("Kaputte Veranstaltung") }
                    assertFalse((screen.controlOf("Import-Daten") as HTMLTextAreaElement).disabled, "the text is editable again")
                    assertEquals(payload, (screen.controlOf("Import-Daten") as HTMLTextAreaElement).value, "the text was kept")
                    assertTrue(screen.allOf("button").any { it.textContent?.trim() == "Vorschau prüfen" })
                }
            }
        }

    @Test
    fun aCleanPreview_enablesTheConfirmation_withTheCount_andCommitsTheUnchangedTextAndHash(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val previewRoute = routeOf { rpcService<IEventImportService>().previewEventImport("[]") }
            val commitRoute = routeOf { rpcService<IEventImportService>().commitEventImport("[]", "x") }
            val answer = preview(row(0, EventImportRowStatus.CREATE, "Eins"), row(1, EventImportRowStatus.CREATE, "Zwei"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == previewRoute -> request.answerWith(jsonOf(EventImportPreviewDto.serializer(), answer))
                        request.rpcRoute == commitRoute ->
                            request.answerWith(jsonOf(EventImportResultDto.serializer(), EventImportResultDto(2, 0, emptyList())))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("event-import-clean-preview") { root, element ->
                    renderEventImportScreen(root)
                    val screen = element()
                    val payload = "[{\"slug\":\"eins\"},{\"slug\":\"zwei\"}]"
                    screen.typeInto("Import-Daten", payload)
                    screen.buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { screen.text().contains("Zwei") }
                    val confirm = screen.buttonNamed("2 Veranstaltungen importieren")
                    assertFalse(confirm.asDynamic().disabled as Boolean, "enabled without errors")
                    confirm.click()
                    awaitUntil("the commit was sent") { calls.toRoute(commitRoute).size == 1 }
                    val commit = calls.singleCall(commitRoute)
                    assertEquals(payload, commit.rpcParam(0) as String)
                    assertEquals("ab".repeat(32), commit.rpcParam(1) as String)
                    awaitUntil("the success message is shown") { screen.text().contains("2 Veranstaltungen angelegt, 0 übersprungen.") }
                    assertTrue(
                        screen.allOf("tbody tr").isEmpty() && screen.allOf(".lapis-data-card").isEmpty(),
                        "the preview is gone after the commit",
                    )
                    assertTrue((screen.controlOf("Import-Daten") as HTMLTextAreaElement).disabled, "no second commit of the same text")
                }
            }
        }

    @Test
    fun aPreviewWithNothingToCreate_keepsTheConfirmationDisabled(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IEventImportService>().previewEventImport("[]") }
            val answer = preview(row(0, EventImportRowStatus.SKIP_SLUG_EXISTS, "Schon da"))
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == previewRoute) {
                        request.answerWith(jsonOf(EventImportPreviewDto.serializer(), answer))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("event-import-nothing") { root, element ->
                    renderEventImportScreen(root)
                    val screen = element()
                    screen.typeInto("Import-Daten", "[{}]")
                    screen.buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { screen.text().contains("Schon da") }
                    assertTrue(screen.buttonNamed("0 Veranstaltungen importieren").asDynamic().disabled as Boolean)
                }
            }
        }

    @Test
    fun emptyAndOversizedInput_isRefusedBeforeAnyRequest(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IEventImportService>().previewEventImport("[]") }
            withFetchStub(respond = { request -> request.answerWith("[]") }) { calls ->
                mountedForm("event-import-refused") { root, element ->
                    renderEventImportScreen(root)
                    val screen = element()
                    screen.buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the empty hint is shown") { screen.text().contains("Bitte zuerst die Import-Daten einfügen.") }
                    screen.typeInto("Import-Daten", "x".repeat(EVENT_IMPORT_MAX_BYTES + 1))
                    screen.buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the size hint is shown") { screen.text().contains("zu groß") }
                    assertEquals(0, calls.toRoute(previewRoute).size, "nothing was sent")
                    assertTrue(document.querySelector(".modal.show") == null)
                }
            }
        }
}

package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.ProcessingAgreementDto
import network.lapis.cloud.shared.domain.ProcessingAgreementInput
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDsgvoComplianceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R36B for the data-protection registers: every one of the four views (AVV, TOM, DSFA, Datenpannen) has its own create button
 * in its title row. The forms are loose widgets, so their fingerprint is built by hand -- [everyFieldIsInTheFingerprint] proves it lists EVERY
 * input: changing any single one makes Escape ask, restoring it makes Escape close at once.
 */
class RestGroupFormsDsgvoDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun agreement(name: String) =
        ProcessingAgreementDto(
            id = "a1",
            processorName = name,
            processingPurpose = "Briefversand",
            dataCategories = "Name, Adresse",
            avvStatus = AvvStatus.NONE,
            signedDate = null,
            reviewDueDate = null,
            documentId = null,
            notes = null,
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
            createdBy = "caller-1",
            createdByDisplayName = "Vera Vorstand",
            updatedAt = null,
            updatedBy = null,
            active = true,
        )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    /** Every list answers an empty list; everything else `[]`. */
    private fun emptyLists(request: RecordedRequest): StubResponse = if (request.isRpc) request.answerWith("[]") else StubResponse()

    private val views =
        listOf(
            Triple("Verarbeitungsverzeichnis (AVV)", "Neuer AVV-Eintrag", "dsgvo-avv-create"),
            Triple("TOM", "Neue TOM", "dsgvo-tom-create"),
            Triple("DSFA", "Neue DSFA", "dsgvo-dsfa-create"),
            Triple("Datenpannen", "Datenpanne melden", "dsgvo-breach-create"),
        )

    @Test
    fun admin_everyViewHasItsOwnButtonInTheTitleRow_collapsedAfterTheLoad(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            withFetchStub(respond = ::emptyLists) {
                mountedForm("r49-dsgvo-admin") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the AVV view") { element().shows("Noch keine AVV-Einträge angelegt.") }
                    val screen = element()
                    views.forEachIndexed { index, (toggle, label, formId) ->
                        if (index > 0) {
                            screen
                                .allOf(
                                    "button",
                                ).first { it.textContent?.trim() == toggle && it.getAttribute("aria-controls") == null }
                                .click()
                        }
                        awaitUntil("the view '$toggle' shows its create button") { screen.createButtonLabels() == listOf(label) }
                        assertFalse(screen.hostOpen(formId), "'$toggle' is collapsed")
                    }
                }
            }
        }

    @Test
    fun board_seesTheDsfaAndBreachButtonsOnly_aMemberSeesNone(): Promise<Unit> =
        formTest {
            withFetchStub(respond = ::emptyLists) {
                AppState.setSession(session(AccountRole.BOARD))
                mountedForm("r49-dsgvo-board") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the AVV view") { element().shows("Noch keine AVV-Einträge angelegt.") }
                    val screen = element()
                    assertEquals(emptyList(), screen.createButtonLabels(), "AVV is ADMIN only")
                    screen.buttonNamed("TOM").click()
                    awaitUntil("the TOM view") { screen.shows("Noch keine TOM-Einträge angelegt.") }
                    assertEquals(emptyList(), screen.createButtonLabels(), "TOM is ADMIN only")
                    screen.buttonNamed("DSFA").click()
                    awaitUntil("the DSFA view") { screen.createButtonLabels() == listOf("Neue DSFA") }
                    screen.buttonNamed("Datenpannen").click()
                    awaitUntil("the breach view") { screen.createButtonLabels() == listOf("Datenpanne melden") }
                }
                AppState.setSession(session(AccountRole.MEMBER))
                mountedForm("r49-dsgvo-member") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the AVV view") { element().shows("Noch keine AVV-Einträge angelegt.") }
                    assertEquals(emptyList(), element().createButtonLabels())
                    element().buttonNamed("Datenpannen").click()
                    awaitUntil("the breach view") { element().shows("Noch keine Datenpannen erfasst.") }
                    assertEquals(emptyList(), element().createButtonLabels(), "a plain member gets no write affordance")
                }
            }
        }

    @Test
    fun changingTheViewOverATypedForm_asksFirst(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            withFetchStub(respond = ::emptyLists) {
                mountedForm("r49-dsgvo-switch") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the AVV view") { element().shows("Noch keine AVV-Einträge angelegt.") }
                    val screen = element()
                    val host = openCreateFormHost(screen, "dsgvo-avv-create")
                    host.typeInto("Verarbeiter", "Letterxpress")

                    screen.buttonNamed("TOM").click()
                    answerDiscardDialog("Weiter bearbeiten")
                    assertTrue(screen.hostOpen("dsgvo-avv-create"), "'Weiter bearbeiten' stays in the AVV view")
                    assertEquals(listOf("Neuer AVV-Eintrag"), screen.createButtonLabels())

                    screen.buttonNamed("TOM").click()
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("the TOM view") { screen.createButtonLabels() == listOf("Neue TOM") }
                    assertFalse(screen.hostOpen("dsgvo-tom-create"))
                }
            }
        }

    @Test
    fun aBlankSubmit_keepsEveryFormOpenWithItsMessage(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val messages =
                listOf(
                    "Bitte Verarbeiter, Verarbeitungszweck, Datenkategorien und AVV-Status angeben.",
                    "Bitte Kategorie, Titel und Beschreibung angeben.",
                    "Bitte Titel, Verarbeitungsbeschreibung und Status angeben.",
                    "Bitte einen gültigen Entdeckungszeitpunkt",
                )
            val buttons = listOf("AVV-Eintrag anlegen", "TOM anlegen", "DSFA anlegen", "Datenpanne melden")
            withFetchStub(respond = ::emptyLists) {
                mountedForm("r49-dsgvo-blank") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the AVV view") { element().shows("Noch keine AVV-Einträge angelegt.") }
                    val screen = element()
                    views.forEachIndexed { index, (toggle, _, formId) ->
                        if (index > 0) {
                            screen
                                .allOf(
                                    "button",
                                ).first { it.textContent?.trim() == toggle && it.getAttribute("aria-controls") == null }
                                .click()
                            awaitUntil("the view '$toggle'") { screen.createButtonLabels() == listOf(views[index].second) }
                        }
                        val host = openCreateFormHost(screen, formId)
                        host.buttonNamed(buttons[index]).click()
                        awaitUntil("'$toggle' shows its message") { host.shows(messages[index]) }
                        assertTrue(screen.hostOpen(formId), "'$toggle' stays open")
                    }
                }
            }
        }

    @Test
    fun aSavedAgreement_foldsBack_andReloadsTheList(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IDsgvoComplianceService>().listProcessingAgreements() }
            val create =
                routeOf {
                    rpcService<IDsgvoComplianceService>().createProcessingAgreement(ProcessingAgreementInput("a", "b", "c", AvvStatus.NONE))
                }
            val rows = mutableListOf<ProcessingAgreementDto>()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(
                                jsonOf(ListSerializer(ProcessingAgreementDto.serializer()), rows.toList()),
                            )
                        request.rpcRoute == create -> {
                            rows += agreement("Letterxpress")
                            request.answerWith(jsonOf(ProcessingAgreementDto.serializer(), rows.last()))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-dsgvo-save") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the AVV view") { element().shows("Noch keine AVV-Einträge angelegt.") }
                    val screen = element()
                    val host = openCreateFormHost(screen, "dsgvo-avv-create")
                    host.typeInto("Verarbeiter", "Letterxpress")
                    host.typeInto("Verarbeitungszweck", "Briefversand")
                    host.typeInto("Datenkategorien", "Name, Adresse")
                    val listCallsBefore = calls.toRoute(list).size
                    host.buttonNamed("AVV-Eintrag anlegen").click()
                    awaitUntil("the create call") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.hostOpen("dsgvo-avv-create") }
                    awaitUntil("the list was reloaded") { calls.toRoute(list).size > listCallsBefore }
                    awaitUntil("the new agreement is listed") { screen.shows("Letterxpress") }
                    assertEquals(1, screen.createButtonLabels().size)
                }
            }
        }

    @Test
    fun editingInTheRow_keepsTheInlineToggle_withoutACancelAndWithoutTheCreateForm(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IDsgvoComplianceService>().listProcessingAgreements() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(
                            jsonOf(ListSerializer(ProcessingAgreementDto.serializer()), listOf(agreement("${KV_MARKER}Letterxpress"))),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-dsgvo-edit") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the agreement row") { element().shows("Briefversand") }
                    val screen = element()
                    assertFalse(screen.shows(KV_MARKER), "a forged i18n marker in a processor name never reaches the DOM")
                    screen.buttonNamed("Bearbeiten").click()
                    awaitUntil("the edit form in the row") { screen.allOf("button").any { it.textContent?.trim() == "Speichern" } }
                    assertTrue(
                        screen.allOf("button").none { it.textContent?.trim() == "AVV-Eintrag anlegen" },
                        "no create button in an edit form",
                    )
                    assertTrue(
                        screen.allOf("button").none { it.textContent?.trim() == "Abbrechen" },
                        "the row toggle is the way out, no Cancel",
                    )
                    assertFalse(screen.hostOpen("dsgvo-avv-create"), "the create form stays closed")
                }
            }
        }

    // ── Fingerprint completeness ────────────────────────────────────────────────────────────────

    private fun HTMLElement.isEditable(): Boolean = this is HTMLInputElement || this is HTMLSelectElement || this is HTMLTextAreaElement

    private fun valueOf(control: HTMLElement): String =
        when (control) {
            is HTMLInputElement -> control.value
            is HTMLTextAreaElement -> control.value
            is HTMLSelectElement -> control.value
            else -> error("not a field")
        }

    private fun setValue(
        control: HTMLElement,
        value: String,
    ) {
        when (control) {
            is HTMLInputElement -> control.value = value
            is HTMLTextAreaElement -> control.value = value
            is HTMLSelectElement -> control.value = value
            else -> error("not a field")
        }
        control.dispatchEvent(Event("input"))
        control.dispatchEvent(Event("change"))
        control.dispatchEvent(Event("blur"))
    }

    /** A value different from [current] for [control]: another option of a select, a marker text for a text field. */
    private fun differentValue(
        control: HTMLElement,
        current: String,
    ): String =
        if (control is HTMLSelectElement) {
            val options = (0 until control.options.length).map { (control.options.item(it) as org.w3c.dom.HTMLOptionElement).value }
            assertNotNull(options.firstOrNull { it != current }, "a select with a single option cannot be tested")
        } else {
            current + "x"
        }

    private suspend fun everyFieldIsInTheFingerprint(
        screen: HTMLElement,
        formId: String,
        minimumFields: Int,
    ) {
        val fieldCount = openCreateFormHost(screen, formId).allOf("input:not([type=hidden]), select, textarea").size
        pressEscape(screen.querySelector("[id='$formId']") as HTMLElement)
        awaitUntil("closed") { !screen.hostOpen(formId) }
        assertTrue(fieldCount >= minimumFields, "$formId: expected at least $minimumFields fields, found $fieldCount")
        for (index in 0 until fieldCount) {
            val host = openCreateFormHost(screen, formId)
            val control = host.allOf("input:not([type=hidden]), select, textarea")[index]
            assertTrue(control.isEditable())
            val original = valueOf(control)
            setValue(control, differentValue(control, original))
            pressEscape(host)
            answerDiscardDialog("Weiter bearbeiten")
            assertTrue(screen.hostOpen(formId), "$formId field #$index: a changed field must make Escape ask")
            setValue(control, original)
            pressEscape(host)
            awaitUntil("$formId field #$index: back to the original closes at once") { !screen.hostOpen(formId) }
            assertTrue(document.querySelector(".modal.show") == null, "$formId field #$index: no dialog once the value is restored")
        }
    }

    @Test
    fun everyFieldIsInTheFingerprint(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            withFetchStub(respond = ::emptyLists) {
                mountedForm("r49-dsgvo-fingerprint") { root, element ->
                    renderDsgvoComplianceScreen(root)
                    awaitUntil("the AVV view") { element().shows("Noch keine AVV-Einträge angelegt.") }
                    val screen = element()
                    everyFieldIsInTheFingerprint(screen, "dsgvo-avv-create", minimumFields = 8)
                    val expectedFields = listOf(3, 10, 10)
                    views.drop(1).forEachIndexed { index, (toggle, label, formId) ->
                        screen
                            .allOf(
                                "button",
                            ).first { it.textContent?.trim() == toggle && it.getAttribute("aria-controls") == null }
                            .click()
                        awaitUntil("the view '$toggle'") { screen.createButtonLabels() == listOf(label) }
                        everyFieldIsInTheFingerprint(screen, formId, minimumFields = expectedFields[index])
                    }
                }
            }
        }
}

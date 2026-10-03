package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactPageDto
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionDto
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICrmService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * V1.9.48 -- rule R36B for the CRM screen: "Kontakt anlegen" sits in the page header, "Interaktion erfassen" in the title row
 * "Interaktionsverlauf" of a contact's detail. Both forms are collapsed, ask before discarding typed input (also when the detail is
 * collapsed over a changed interaction form), fold back after a save and reload what they feed.
 */
class CommunityCollapsibleFormsCrmDomTest {
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"
    private val marker = "###KvI18nS###"

    private fun boardSession() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.BOARD,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun contact(
        id: String,
        name: String,
    ) = CrmContactDto(
        id = id,
        displayName = name,
        email = null,
        phone = null,
        street = null,
        postalCode = null,
        city = null,
        country = null,
        contactType = CrmContactType.INTERESSENT,
        lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
        consentSource = null,
        consentGivenAt = null,
        consentWithdrawnAt = null,
        externalDonorId = null,
        memberId = null,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        createdBy = "member-1",
        lastInteractionAt = null,
        retentionReviewDueAt = LocalDateTime(2030, 1, 1, 0, 0),
        archivedAt = null,
        mayReceiveEmail = false,
    )

    private fun interaction(summary: String) =
        CrmInteractionDto(
            id = "i-1",
            contactId = "c1",
            occurredAt = LocalDateTime(2026, 5, 1, 10, 0),
            kind = CrmInteractionKind.NOTE,
            summary = summary,
            recordedBy = "member-1",
            recordedByDisplayName = "Dana Keller",
            recordedAt = LocalDateTime(2026, 5, 1, 10, 0),
        )

    private fun HTMLElement.pageActionButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hostOpen(formId: String): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun awaitDiscardDialog(): HTMLElement {
        awaitUntil("the discard dialog is shown") { document.querySelector(".modal.show") != null }
        return lastOpenModal()
    }

    private suspend fun dismissDialogWith(label: String) {
        awaitDiscardDialog().buttonNamed(label).click()
        awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
    }

    private fun page(rows: List<CrmContactDto>) = jsonOf(CrmContactPageDto.serializer(), CrmContactPageDto(rows, rows.size))

    private val seedContactInput =
        CrmContactInput(
            "n",
            null,
            null,
            null,
            null,
            null,
            null,
            CrmContactType.INTERESSENT,
            CrmLawfulBasis.CONTRACT,
            null,
            null,
            null,
            null,
        )

    @Test
    fun contactForm_collapsedInHeader_escapeAsksOnlyAfterTyping_conflictKeepsIt_saveFoldsBackAndReloads(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<ICrmService>().listContacts(null, false, false, 50, 0) }
            val create = routeOf { rpcService<ICrmService>().createContact(seedContactInput) }
            val rows = mutableListOf(contact("c1", "Altkontakt"))
            var conflictNext = true
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(rows))
                        request.rpcRoute == create ->
                            if (conflictNext) {
                                conflictNext = false
                                serviceExceptionResult(request.json.id as Int, conflict)
                            } else {
                                val added = contact("c2", "Neukontakt")
                                rows += added
                                request.answerWith(jsonOf(CrmContactDto.serializer(), added))
                            }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-crm-contact") { root, element ->
                    renderCrmContactsScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altkontakt") }
                    val screen = element()
                    assertEquals(listOf("Kontakt anlegen"), screen.pageActionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.shows("Neuen Kontakt anlegen"), "the old always-visible section title is gone")
                    assertFalse(screen.hostOpen("lapis-create-crm-contact"), "collapsed after the load")

                    val host = openCreateFormHost(screen, "lapis-create-crm-contact")
                    assertTrue(document.activeElement is HTMLInputElement, "the focus moved into the first field")
                    escape(host)
                    awaitUntil("closed without a question") { !screen.hostOpen("lapis-create-crm-contact") }
                    assertTrue(document.querySelector(".modal.show") == null)

                    val reopened = openCreateFormHost(screen, "lapis-create-crm-contact")
                    reopened.typeInto("Name", "Neukontakt")
                    escape(reopened)
                    dismissDialogWith("Weiter bearbeiten")
                    assertEquals("Neukontakt", (reopened.controlOf("Name") as HTMLInputElement).value, "keep editing keeps the input")

                    reopened.chooseIn("Typ", CrmContactType.INTERESSENT.name)
                    reopened.chooseIn("Rechtsgrundlage", CrmLawfulBasis.CONTRACT.name)
                    reopened.buttonNamed("Kontakt anlegen").click()
                    awaitUntil("the first write was attempted") { calls.toRoute(create).size == 1 }
                    awaitUntil("the failed save is over") { !reopened.buttonNamed("Kontakt anlegen").hasAttribute("disabled") }
                    assertTrue(screen.hostOpen("lapis-create-crm-contact"), "a conflict never folds the form back")
                    assertEquals("Neukontakt", (reopened.controlOf("Name") as HTMLInputElement).value)

                    val listCallsBefore = calls.toRoute(list).size
                    reopened.buttonNamed("Kontakt anlegen").click()
                    awaitUntil("the second write was made") { calls.toRoute(create).size == 2 }
                    awaitUntil("the form folded back") { !screen.hostOpen("lapis-create-crm-contact") }
                    awaitUntil("the new row is listed") { screen.shows("Neukontakt") }
                    assertEquals(
                        "Neukontakt",
                        calls
                            .toRoute(create)
                            .last()
                            .rpcParam(0)
                            .displayName as String,
                    )
                    assertEquals(listCallsBefore + 1, calls.toRoute(list).size, "the list was reloaded once after the save")
                    val button = createFormButton(screen, "lapis-create-crm-contact")
                    awaitUntil("the focus is back on the button") { document.activeElement == button }
                }
            }
        }

    @Test
    fun interactionForm_sitsInTheTimelineTitleRow_saveFoldsBackAndReloadsTheTimeline(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<ICrmService>().listContacts(null, false, false, 50, 0) }
            val get = routeOf { rpcService<ICrmService>().getContact("c1") }
            val timeline = routeOf { rpcService<ICrmService>().listInteractions("c1", 50, 0) }
            val record =
                routeOf {
                    rpcService<ICrmService>().recordInteraction(CrmInteractionInput("c1", null, CrmInteractionKind.NOTE, "s"))
                }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(listOf(contact("c1", "Altkontakt"))))
                        request.rpcRoute == get -> request.answerWith(jsonOf(CrmContactDto.serializer(), contact("c1", "Altkontakt")))
                        request.rpcRoute == timeline ->
                            request.answerWith(jsonOf(ListSerializer(CrmInteractionDto.serializer()), emptyList()))
                        request.rpcRoute == record ->
                            request.answerWith(
                                jsonOf(CrmInteractionDto.serializer(), interaction("Rückruf vereinbart")),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-crm-interaction") { root, element ->
                    renderCrmContactsScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altkontakt") }
                    val screen = element()
                    screen.buttonNamed("Details anzeigen").click()
                    awaitUntil("the detail shows its timeline button") {
                        screen.allOf("button").any {
                            it.textContent?.trim() ==
                                "Interaktion erfassen"
                        }
                    }
                    assertEquals(
                        listOf("Kontakt anlegen"),
                        screen.pageActionButtons().map { it.textContent?.trim() },
                        "the page header holds only the contact button",
                    )
                    val titleRowButton = screen.buttonNamed("Interaktion erfassen")
                    assertTrue(titleRowButton.closest(".lapis-page-header") == null, "the interaction button is not in the page header")
                    assertTrue(
                        titleRowButton.parentElement?.classList?.contains("lapis-page-action") == true,
                        "it sits in a title row slot",
                    )
                    assertFalse(screen.shows("Neue Interaktion erfassen"), "the old always-visible section title is gone")
                    val formId = titleRowButton.getAttribute("aria-controls").orEmpty()
                    assertTrue(formId.startsWith("lapis-create-crm-interaction-"), "own sequence id, never a contact id: $formId")
                    assertFalse(formId.contains("c1"))
                    assertFalse(screen.hostOpen(formId), "collapsed")
                    val timelineCalls = calls.toRoute(timeline).size

                    val host = openCreateFormHost(screen, formId)
                    host.typeInto("Notiz", "Rückruf vereinbart")
                    host.buttonNamed("Interaktion speichern").click()
                    awaitUntil("recordInteraction was called") { calls.toRoute(record).size == 1 }
                    awaitUntil("the form folded back") { !screen.hostOpen(formId) }
                    assertEquals("Rückruf vereinbart", calls.singleCall(record).rpcParam(0).summary as String)
                    awaitUntil("the timeline was reloaded") { calls.toRoute(timeline).size == timelineCalls + 1 }
                }
            }
        }

    @Test
    fun collapsingTheDetailOverAChangedInteractionForm_asksFirst(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<ICrmService>().listContacts(null, false, false, 50, 0) }
            val get = routeOf { rpcService<ICrmService>().getContact("c1") }
            val timeline = routeOf { rpcService<ICrmService>().listInteractions("c1", 50, 0) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(page(listOf(contact("c1", "Altkontakt"))))
                        request.rpcRoute == get -> request.answerWith(jsonOf(CrmContactDto.serializer(), contact("c1", "Altkontakt")))
                        request.rpcRoute == timeline ->
                            request.answerWith(jsonOf(ListSerializer(CrmInteractionDto.serializer()), emptyList()))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-crm-detail-collapse") { root, element ->
                    renderCrmContactsScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altkontakt") }
                    val screen = element()
                    val toggle = screen.buttonNamed("Details anzeigen")
                    toggle.click()
                    awaitUntil("the detail is built") { screen.allOf("button").any { it.textContent?.trim() == "Interaktion erfassen" } }
                    val formId = screen.buttonNamed("Interaktion erfassen").getAttribute("aria-controls").orEmpty()

                    // unchanged form: collapsing the detail asks nothing
                    openCreateFormHost(screen, formId)
                    toggle.click()
                    awaitUntil("collapsed without a question") { !screen.hostOpen(formId) }
                    assertTrue(document.querySelector(".modal.show") == null)

                    toggle.click()
                    awaitUntil("the detail is rebuilt with its own form id") {
                        screen.allOf("button").any {
                            it.textContent?.trim() == "Interaktion erfassen" && it.getAttribute("aria-controls") != formId
                        }
                    }
                    val secondId = screen.buttonNamed("Interaktion erfassen").getAttribute("aria-controls").orEmpty()
                    assertNotEquals(formId, secondId, "every build gets its own id")
                    val host = openCreateFormHost(screen, secondId)
                    host.typeInto("Notiz", "halbfertig")
                    toggle.click()
                    dismissDialogWith("Weiter bearbeiten")
                    assertTrue(screen.hostOpen(secondId), "the detail and the input stay")
                    assertEquals("halbfertig", (host.controlOf("Notiz") as HTMLTextAreaElement).value)

                    toggle.click()
                    dismissDialogWith("Verwerfen")
                    awaitUntil("the form is gone with the detail") { !screen.hostOpen(secondId) }
                }
            }
        }

    @Test
    fun aHostileContactName_isNotResolvedAsAMarker(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession())
            val list = routeOf { rpcService<ICrmService>().listContacts(null, false, false, 50, 0) }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(page(listOf(contact("c1", "${marker}Quorum heißt"))))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-crm-hostile") { root, element ->
                    renderCrmContactsScreen(root)
                    awaitUntil("the list is shown") { element().shows("Quorum") }
                    assertFalse(element().shows(marker), "the marker never reaches the DOM")
                }
            }
        }
}

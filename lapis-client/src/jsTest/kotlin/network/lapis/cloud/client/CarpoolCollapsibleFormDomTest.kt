package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.rpc.ICarpoolService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.50 -- rule R36B for the Mitfahrerzentrale: "Eintrag erstellen" is the collapsed create form of the page header (one button in the
 * title row); "Bearbeiten" and "Duplizieren" of a card open the SAME host, pre-filled; a changed form asks before it is dropped; a failed
 * save keeps the form open; a forged i18n marker in a place name never reaches the DOM.
 */
class CarpoolCollapsibleFormDomTest {
    private val formId = "carpool-create-form"
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"

    private fun posting(
        id: String,
        own: Boolean,
        from: String = "Braunschweig",
        notes: String? = "Zwei Koffer",
    ) = CarpoolPostingDto(
        id = id,
        type = CarpoolPostingType.OFFER,
        fromPlace = from,
        toPlace = "Hannover",
        departureDate = LocalDate(2099, 5, 1),
        departureTime = LocalTime(8, 30, 0),
        seatsOffered = 3,
        notes = notes,
        authorDisplayName = "Vera Vorstand",
        isOwn = own,
        isPast = false,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.valueOf(label: String): String =
        when (val control = controlOf(label)) {
            is HTMLInputElement -> control.value
            is HTMLTextAreaElement -> control.value
            is HTMLSelectElement -> control.value
            else -> error("not a value control: $label")
        }

    private fun HTMLElement.setDate(value: String) {
        val control = controlOf("Abfahrtsdatum") as HTMLInputElement
        control.value = value
        control.dispatchEvent(
            org.w3c.dom.events
                .Event("input"),
        )
        control.dispatchEvent(
            org.w3c.dom.events
                .Event("blur"),
        )
    }

    private suspend fun withCarpoolStub(
        mine: List<CarpoolPostingDto>,
        feed: List<CarpoolPostingDto> = emptyList(),
        createAnswer: ((RecordedRequest) -> StubResponse)? = null,
        block: suspend (List<RecordedRequest>, Routes) -> Unit,
    ) {
        val routes =
            Routes(
                listMine = routeOf { rpcService<ICarpoolService>().listMyPostings() },
                listFeed = routeOf { rpcService<ICarpoolService>().listPostings(null) },
                create =
                    routeOf {
                        rpcService<ICarpoolService>().createPosting(
                            CarpoolPostingInput(CarpoolPostingType.OFFER, "a", "b", LocalDate(2099, 1, 1)),
                        )
                    },
                update =
                    routeOf {
                        rpcService<ICarpoolService>().updatePosting(
                            "x",
                            CarpoolPostingInput(CarpoolPostingType.OFFER, "a", "b", LocalDate(2099, 1, 1)),
                        )
                    },
            )
        withFetchStub(
            respond = { request ->
                val serializer = ListSerializer(CarpoolPostingDto.serializer())
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == routes.listMine -> request.answerWith(jsonOf(serializer, mine))
                    request.rpcRoute == routes.listFeed -> request.answerWith(jsonOf(serializer, feed))
                    request.rpcRoute == routes.create ->
                        createAnswer?.invoke(request) ?: request.answerWith(jsonOf(CarpoolPostingDto.serializer(), posting("new", true)))
                    request.rpcRoute == routes.update ->
                        request.answerWith(jsonOf(CarpoolPostingDto.serializer(), posting("p1", true)))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls -> block(calls, routes) }
    }

    private class Routes(
        val listMine: String,
        val listFeed: String,
        val create: String,
        val update: String,
    )

    @Test
    fun theCreateButtonIsOneButtonInTheTitleRow_andTheFormIsCollapsedAfterLoading(): Promise<Unit> =
        formTest {
            withCarpoolStub(mine = listOf(posting("p1", own = true))) { calls, routes ->
                mountedForm("r50-carpool-collapsed") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the own posting is listed") { element().shows("Braunschweig") }
                    val screen = element()
                    assertEquals(listOf("Eintrag erstellen"), screen.createButtonLabels(), "one create button, in the page header slot")
                    assertTrue(screen.createButtonShown(formId))
                    assertFalse(screen.hostOpen(formId), "collapsed after the load")
                    assertEquals(
                        1,
                        screen.allOf("button").count { it.textContent?.trim() == "Eintrag erstellen" },
                        "no second, hand-built create button on the page",
                    )
                    assertTrue(calls.toRoute(routes.listMine).isNotEmpty())
                }
            }
        }

    @Test
    fun theTypeFilterIsALapisToolbar(): Promise<Unit> =
        formTest {
            withCarpoolStub(mine = emptyList()) { _, _ ->
                mountedForm("r50-carpool-toolbar") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the page is built") { element().shows("Fahrten") }
                    assertTrue(element().allOf(".lapis-toolbar").isNotEmpty(), "the filter row is a lapisToolbar()")
                }
            }
        }

    @Test
    fun savingANewEntry_createsOnce_foldsBack_andReloadsBothLists(): Promise<Unit> =
        formTest {
            withCarpoolStub(mine = emptyList()) { calls, routes ->
                mountedForm("r50-carpool-save") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the lists were loaded") { calls.toRoute(routes.listMine).isNotEmpty() }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.typeInto("Von (Ort oder PLZ)", "Wolfsburg")
                    host.typeInto("Nach (Ort oder PLZ)", "Berlin")
                    host.setDate("2099-06-01")
                    host.typeInto("Freie Plätze", "2")
                    // The base counts only after EVERY initial load is through (the feed is requested twice on open): read while
                    // one was still on its way, its late request is counted as the reload (CI 2026-10-03: feed "before 0", after 3).
                    awaitAppScopeIdle("the initial loads finished")
                    val minesBefore = calls.toRoute(routes.listMine).size
                    val feedBefore = calls.toRoute(routes.listFeed).size
                    host.buttonNamed("Speichern").click()
                    awaitUntil("createPosting") { calls.toRoute(routes.create).size == 1 }
                    val sent = calls.singleCall(routes.create).rpcParam(0)
                    assertEquals("Wolfsburg", sent.fromPlace as String)
                    assertEquals("Berlin", sent.toPlace as String)
                    awaitUntil("the form folded back") { !screen.hostOpen(formId) }
                    awaitUntil(
                        "both lists were reloaded exactly once",
                        detail = {
                            "listMine ${calls.toRoute(routes.listMine).size} (before $minesBefore), " +
                                "listFeed ${calls.toRoute(routes.listFeed).size} (before $feedBefore)"
                        },
                    ) {
                        calls.toRoute(routes.listMine).size == minesBefore + 1 && calls.toRoute(routes.listFeed).size == feedBefore + 1
                    }
                }
            }
        }

    @Test
    fun cancelOnAnUntouchedForm_closesAtOnce_butAChangedFormAsksFirst(): Promise<Unit> =
        formTest {
            withCarpoolStub(mine = emptyList()) { _, _ ->
                mountedForm("r50-carpool-cancel") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the page is built") { element().shows("Fahrten") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.buttonNamed("Abbrechen").click()
                    awaitUntil("closed without a question") { !screen.hostOpen(formId) }

                    val again = openCreateForm(screen, formId)
                    again.typeInto("Von (Ort oder PLZ)", "Wolfsburg")
                    again.buttonNamed("Abbrechen").click()
                    answerDiscardDialog("Weiter bearbeiten")
                    assertTrue(screen.hostOpen(formId), "'Weiter bearbeiten' keeps the typed form")
                    again.buttonNamed("Abbrechen").click()
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("closed after the confirmation") { !screen.hostOpen(formId) }
                }
            }
        }

    @Test
    fun aFailedSave_keepsTheFormOpen(): Promise<Unit> =
        formTest {
            withCarpoolStub(
                mine = emptyList(),
                createAnswer = { request -> serviceExceptionResult(request.json.id as Int, conflict) },
            ) { calls, routes ->
                mountedForm("r50-carpool-conflict") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the page is built") { element().shows("Fahrten") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.typeInto("Von (Ort oder PLZ)", "Wolfsburg")
                    host.typeInto("Nach (Ort oder PLZ)", "Berlin")
                    host.setDate("2099-06-01")
                    host.typeInto("Freie Plätze", "2")
                    host.buttonNamed("Speichern").click()
                    awaitUntil("createPosting") { calls.toRoute(routes.create).size == 1 }
                    delay300()
                    assertTrue(screen.hostOpen(formId), "a failed save never folds the form back")
                }
            }
        }

    @Test
    fun editAndDuplicate_openTheSameHostPrefilled(): Promise<Unit> =
        formTest {
            withCarpoolStub(mine = listOf(posting("p1", own = true))) { _, _ ->
                mountedForm("r50-carpool-prefill") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the own posting is listed") { element().shows("Braunschweig") }
                    val screen = element()
                    screen.buttonNamed("Bearbeiten").click()
                    awaitUntil("the edit form is built") { screen.querySelector("[id='$formId'] .lapis-form") != null }
                    val host = assertNotNull(screen.querySelector("[id='$formId']") as? HTMLElement)
                    assertTrue(host.shows("Eintrag bearbeiten"))
                    assertEquals("Braunschweig", host.valueOf("Von (Ort oder PLZ)"))
                    assertEquals("2099-05-01", host.valueOf("Abfahrtsdatum"))
                    assertEquals(1, screen.allOf("[id='$formId'] .lapis-form").size, "never two forms at once")

                    screen.buttonNamed("Duplizieren").click()
                    awaitUntil("the duplicate form replaces the edit form (untouched form: no question)") {
                        screen
                            .querySelector("[id='$formId']")
                            ?.textContent
                            .orEmpty()
                            .contains("Neuer Eintrag")
                    }
                    val duplicate = assertNotNull(screen.querySelector("[id='$formId']") as? HTMLElement)
                    assertEquals("Braunschweig", duplicate.valueOf("Von (Ort oder PLZ)"))
                    assertEquals("", duplicate.valueOf("Abfahrtsdatum"), "a duplicate starts without the date")
                    assertEquals(1, screen.allOf("[id='$formId'] .lapis-form").size)
                }
            }
        }

    @Test
    fun aForgedI18nMarkerInAPlaceNameOrNote_neverReachesTheDom(): Promise<Unit> =
        formTest {
            withCarpoolStub(
                mine = listOf(posting("p1", own = true, from = "${KV_MARKER}Ort", notes = "${KV_MARKER}Notiz")),
            ) { _, _ ->
                mountedForm("r50-carpool-marker") { root, element ->
                    renderCarpoolScreen(root)
                    awaitUntil("the posting is listed") { element().shows("Hannover") }
                    assertFalse(element().shows(KV_MARKER), "the marker must stay inert in the card")
                    element().buttonNamed("Bearbeiten").click()
                    awaitUntil("the edit form is built") { element().querySelector("[id='$formId'] .lapis-form") != null }
                    val host = assertNotNull(element().querySelector("[id='$formId']") as? HTMLElement)
                    assertEquals("${KV_MARKER}Notiz", host.valueOf("Notiz"), "the form carries the note as plain DATA (a textarea value)")
                    assertEquals("${KV_MARKER}Ort", host.valueOf("Von (Ort oder PLZ)"))
                    assertFalse(host.allOf("label, button, h2").any { it.shows(KV_MARKER) }, "and never as text content of the form")
                }
            }
        }

    private suspend fun delay300() = kotlinx.coroutines.delay(300)
}

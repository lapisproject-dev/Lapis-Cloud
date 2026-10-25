package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R36B for the stream destinations (ADMIN): "Neues Stream-Ziel" is one collapsed form behind a page header button. The
 * stream key is a secret: after saving AND after discarding, the form host is empty -- no password field, no key value anywhere in the DOM.
 */
class RestGroupFormsStreamDestinationDomTest {
    private val formId = "stream-destination-create"
    private val secret = "s3cr3t-STREAM-key-4711"

    private val admin =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Ada Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun destination(
        id: String,
        label: String,
        platform: ConferenceStreamPlatform = ConferenceStreamPlatform.GENERIC_RTMP,
    ) = ConferenceStreamDestinationDto(
        id = id,
        label = label,
        platform = platform,
        rtmpUrl = "rtmps://ingest.example.org/live",
        streamKeyMask = "********",
        streamKeySetAt = LocalDateTime(2026, 1, 1, 0, 0),
        createdByDisplayName = "Ada Admin",
        enabled = true,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.typeSecret() {
        val key = querySelector("input[type=password]") as HTMLInputElement
        key.value = secret
        key.dispatchEvent(Event("input"))
        key.dispatchEvent(Event("blur"))
    }

    @Test
    fun oneButtonInTheHeader_formIsClosedAfterTheLoad_escapeWithoutChangesClosesAtOnce(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(
                                jsonOf(
                                    ListSerializer(ConferenceStreamDestinationDto.serializer()),
                                    listOf(destination("d1", "${KV_MARKER}Kanal Eins")),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-stream-closed") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the destination row") { element().shows("Kanal Eins") }
                    val screen = element()
                    assertEquals(listOf("Neues Stream-Ziel"), screen.createButtonLabels())
                    assertFalse(screen.hostOpen(formId), "collapsed after the load")
                    assertFalse(screen.shows(KV_MARKER), "a forged i18n marker in a label never reaches the DOM")
                    assertEquals("false", screen.createButtonOf(formId).getAttribute("aria-expanded"))

                    val host = openCreateForm(screen, formId)
                    assertTrue(document.activeElement is HTMLInputElement, "the focus moved into the first field")
                    assertEquals("true", screen.createButtonOf(formId).getAttribute("aria-expanded"))
                    pressEscape(host)
                    awaitUntil("closed without a question") { !screen.hostOpen(formId) }
                }
            }
        }

    @Test
    fun aSavedForm_foldsBack_reloadsTheList_andLeavesNoKeyInTheDom(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            val create =
                routeOf {
                    rpcService<IConferenceStreamingService>().createDestination(
                        "l",
                        ConferenceStreamPlatform.GENERIC_RTMP,
                        "u",
                        "k",
                    )
                }
            val rows = mutableListOf(destination("d1", "Kanal Eins"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), rows.toList()))
                        request.rpcRoute == create -> {
                            rows += destination("d2", "Neues Ziel")
                            request.answerWith(jsonOf(ConferenceStreamDestinationDto.serializer(), rows.last()))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-stream-save") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the destination row") { element().shows("Kanal Eins") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.typeInto("Bezeichnung", "Neues Ziel")
                    host.typeInto("RTMP-Basis-URL", "rtmps://neu.example.org/live")
                    host.typeSecret()
                    pressEscape(host)
                    answerDiscardDialog("Weiter bearbeiten")
                    assertTrue(screen.hostOpen(formId), "'Weiter bearbeiten' keeps the typed form")

                    val listCallsBefore = calls.toRoute(list).size
                    host.buttonNamed("Stream-Ziel anlegen").click()
                    awaitUntil("the create call was made") { calls.toRoute(create).size == 1 }
                    awaitUntil("the form folded back") { !screen.hostOpen(formId) }
                    awaitUntil("the list was reloaded") { calls.toRoute(list).size > listCallsBefore }
                    awaitUntil("the new destination is listed") { screen.shows("Neues Ziel") }
                    assertNull(screen.querySelector("input[type=password]"), "no key field is left behind")
                    assertFalse(screen.innerHTML.contains(secret), "the key is nowhere in the DOM")
                    assertEquals(1, screen.createButtonLabels().size, "still exactly one button")
                }
            }
        }

    @Test
    fun aDiscardedForm_leavesNoKeyInTheDom(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), emptyList()))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-stream-discard") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Stream-Ziele") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.typeSecret()
                    host.buttonNamed("Abbrechen").click()
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("the form is gone") { !screen.hostOpen(formId) }
                    assertNull(screen.querySelector("input[type=password]"))
                    assertFalse(screen.innerHTML.contains(secret))
                }
            }
        }

    @Test
    fun aBlankSubmit_keepsTheFormOpen_andSendsNothing(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            val create =
                routeOf {
                    rpcService<IConferenceStreamingService>().createDestination(
                        "l",
                        ConferenceStreamPlatform.GENERIC_RTMP,
                        "u",
                        "k",
                    )
                }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), emptyList()))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r49-stream-blank") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Stream-Ziele") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.buttonNamed("Stream-Ziel anlegen").click()
                    awaitUntil("the fields report the missing values") { host.shownErrors().isNotEmpty() }
                    assertTrue(calls.toRoute(create).isEmpty(), "nothing is sent")
                    assertTrue(screen.hostOpen(formId), "a validation error never folds the form back")
                }
            }
        }

    @Test
    fun aPlatformPreset_countsAsAChange_soEscapeAsks(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), emptyList()))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-stream-preset") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Stream-Ziele") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    host.chooseIn("Plattform", "YOUTUBE")
                    assertTrue((host.controlOf("RTMP-Basis-URL") as HTMLInputElement).value.startsWith("rtmp"), "the preset URL was filled")
                    pressEscape(host)
                    answerDiscardDialog("Verwerfen")
                    awaitUntil("closed after discarding") { !screen.hostOpen(formId) }
                }
            }
        }

    private val xHintStart = "X (ehemals Twitter)"
    private val xEditHint = "Bei X kann sich der Schlüssel pro Übertragung ändern."

    private fun HTMLElement.urlInput() = controlOf("RTMP-Basis-URL") as HTMLInputElement

    private fun answerEmptyList(
        request: RecordedRequest,
        list: String,
    ) = if (request.isRpc && request.rpcRoute == list) {
        request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), emptyList()))
    } else {
        request.answerWith("[]")
    }

    @Test
    fun platformX_isAnOption_showsTheHint_leavesTheUrlEmptyWithAPlaceholder(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            withFetchStub(respond = { answerEmptyList(it, list) }) {
                mountedForm("v131-stream-x-options") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Stream-Ziele") }
                    val screen = element()
                    val host = openCreateForm(screen, formId)
                    val options = (host.controlOf("Plattform") as HTMLSelectElement).options
                    val values = (0 until options.length).map { (options.item(it) as org.w3c.dom.HTMLOptionElement).value }
                    assertTrue("X" in values, "X is an option")
                    assertEquals("GENERIC_RTMP", values.last(), "the generic RTMP option closes the list")
                    assertFalse(host.shows(xHintStart), "no X hint before X is chosen")

                    host.chooseIn("Plattform", "X")
                    assertTrue(host.shows(xHintStart), "the X hint is shown")
                    assertTrue(host.shows("Media Studio"), "the hint says where to find the data")
                    assertEquals("", host.urlInput().value, "there is no preset for X")
                    assertEquals("rtmps://…", host.urlInput().placeholder)
                }
            }
        }

    @Test
    fun switchingPlatform_clearsAPresetUrl_butKeepsATypedOne_andDropsThePlaceholderAgain(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            withFetchStub(respond = { answerEmptyList(it, list) }) {
                mountedForm("v131-stream-x-switch") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Stream-Ziele") }
                    val host = openCreateForm(element(), formId)

                    host.chooseIn("Plattform", "YOUTUBE")
                    assertEquals("rtmp://a.rtmp.youtube.com/live2", host.urlInput().value)
                    host.chooseIn("Plattform", "X")
                    assertEquals("", host.urlInput().value, "YouTube -> X empties the preset URL")
                    host.chooseIn("Plattform", "TWITCH")
                    assertEquals("rtmp://live.twitch.tv/app", host.urlInput().value)
                    host.chooseIn("Plattform", "YOUTUBE")
                    assertEquals("rtmp://a.rtmp.youtube.com/live2", host.urlInput().value, "Twitch -> YouTube replaces the preset")

                    host.typeInto("RTMP-Basis-URL", "rtmps://ingest.example.org/live")
                    host.chooseIn("Plattform", "X")
                    assertEquals("rtmps://ingest.example.org/live", host.urlInput().value, "a typed URL survives the switch")

                    host.chooseIn("Plattform", "PEERTUBE")
                    assertEquals("", host.urlInput().placeholder.orEmpty(), "the placeholder is gone again")
                    assertFalse(host.shows(xHintStart), "and so is the X hint")
                }
            }
        }

    @Test
    fun creatingAnXDestination_sendsPlatformXAndTheTrimmedUrl(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            val create =
                routeOf {
                    rpcService<IConferenceStreamingService>().createDestination("l", ConferenceStreamPlatform.X, "u", "k")
                }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), emptyList()))
                        request.rpcRoute == create ->
                            request.answerWith(
                                jsonOf(
                                    ConferenceStreamDestinationDto.serializer(),
                                    destination("d9", "X-Kanal", ConferenceStreamPlatform.X),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("v131-stream-x-create") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Stream-Ziele") }
                    val host = openCreateForm(element(), formId)
                    host.typeInto("Bezeichnung", "X-Kanal")
                    host.chooseIn("Plattform", "X")
                    host.typeInto("RTMP-Basis-URL", "  rtmps://ingest.example.org/live  ")
                    host.typeSecret()
                    host.buttonNamed("Stream-Ziel anlegen").click()
                    awaitUntil("the create call was made") { calls.toRoute(create).size == 1 }
                    val body = calls.toRoute(create).single().body
                    assertTrue(body.contains("\\\"X\\\""), "platform X is sent: $body")
                    assertTrue(body.contains("\\\"rtmps://ingest.example.org/live\\\""), "the URL is sent trimmed: $body")
                }
            }
        }

    @Test
    fun theEditModal_showsTheXHintOnlyForAnXDestination_andKeepsTheKeyFieldAPasswordField(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(
                            jsonOf(
                                ListSerializer(ConferenceStreamDestinationDto.serializer()),
                                listOf(
                                    destination("dx", "Ziel X", ConferenceStreamPlatform.X),
                                    destination("dg", "Ziel Generisch", ConferenceStreamPlatform.GENERIC_RTMP),
                                ),
                            ),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("v131-stream-x-edit") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("both rows") { element().shows("Ziel X") && element().shows("Ziel Generisch") }
                    // Rows are sorted by label: "Ziel Generisch" first, "Ziel X" second.
                    val editButtons = element().allOf("button").filter { it.textContent?.trim() == "Bearbeiten" }
                    assertEquals(2, editButtons.size)

                    editButtons[0].click()
                    awaitUntil("the edit modal of the generic destination") { document.querySelector(".modal.show") != null }
                    val generic = lastOpenModal()
                    assertFalse(generic.shows(xEditHint), "no X hint for GENERIC_RTMP")
                    assertEquals("password", (generic.querySelector("input[type=password]") as HTMLInputElement).type)
                    generic.buttonNamed("Abbrechen").click()
                    awaitUntil("the first modal is closed") { document.querySelector(".modal.show") == null }

                    editButtons[1].click()
                    awaitUntil("the edit modal of the X destination") { document.querySelector(".modal.show") != null }
                    val x = lastOpenModal()
                    assertTrue(x.shows(xEditHint), "the X destination shows the key-rotation hint")
                    assertEquals("password", (x.querySelector("input[type=password]") as HTMLInputElement).type)
                    assertEquals("", (x.querySelector("input[type=password]") as HTMLInputElement).value)
                }
            }
        }

    @Test
    fun aPresetFillsNothingButTheHint_isStoredAsGenericRtmp_andTheKeyFieldStaysUntouched(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            val create =
                routeOf {
                    rpcService<IConferenceStreamingService>().createDestination("l", ConferenceStreamPlatform.GENERIC_RTMP, "u", "k")
                }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), emptyList()))
                        request.rpcRoute == create ->
                            request.answerWith(jsonOf(ConferenceStreamDestinationDto.serializer(), destination("d7", "FB")))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("v1207-stream-preset") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list is shown") { element().shows("Noch keine Stream-Ziele") }
                    val host = openCreateForm(element(), formId)
                    val options = (host.controlOf("Plattform") as HTMLSelectElement).options
                    val values = (0 until options.length).map { (options.item(it) as org.w3c.dom.HTMLOptionElement).value }
                    assertEquals(
                        listOf("preset:facebook", "preset:telegram", "preset:linkedin", "preset:rumble", "preset:kick", "preset:vimeo"),
                        values.take(6),
                    )
                    assertEquals(listOf("YOUTUBE", "TWITCH", "X", "PEERTUBE", "OWNCAST", "GENERIC_RTMP"), values.drop(6))

                    host.chooseIn("Plattform", "preset:facebook")
                    assertTrue(host.shows("Facebook zeigt") || host.shows("Facebook"), "the preset hint is shown")
                    assertEquals("rtmps://…", host.urlInput().placeholder)
                    val key = host.querySelector("input[type=password]") as HTMLInputElement
                    assertEquals("", key.value, "the key field is never prefilled")
                    assertTrue(key.placeholder.isNullOrEmpty(), "and has no placeholder")

                    host.typeInto("Bezeichnung", "FB")
                    host.typeInto("RTMP-Basis-URL", "rtmps://ingest.example.org/live")
                    host.typeSecret()
                    host.buttonNamed("Stream-Ziel anlegen").click()
                    awaitUntil("the create call was made") { calls.toRoute(create).size == 1 }
                    assertTrue(
                        calls
                            .toRoute(create)
                            .single()
                            .body
                            .contains("GENERIC_RTMP"),
                        "stored as generic RTMP",
                    )
                }
            }
        }
}

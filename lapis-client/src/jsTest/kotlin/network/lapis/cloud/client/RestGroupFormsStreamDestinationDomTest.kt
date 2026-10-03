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
    ) = ConferenceStreamDestinationDto(
        id = id,
        label = label,
        platform = ConferenceStreamPlatform.GENERIC_RTMP,
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
}

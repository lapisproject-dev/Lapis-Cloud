package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamBroadcastInput
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamKeyHint
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamTargetDto
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.274 -- the stream key of ONE broadcast in the browser: the masked text field (and its fallback), the character counter that never shows the
 * key, the start dialog (field only for ticked per-broadcast targets, the hint per platform, a field error instead of a disabled button, the
 * fields emptied on confirm and cancel), the destination form (radio group, key field hidden for "nur pro Sendung") and the single place that
 * opens a viewer page.
 */
class StreamBroadcastKeyDomTest {
    private val secret = "FB-s3cr3t-live-key-4711"

    private fun HTMLElement.all(selector: String): List<Element> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as Element }

    private fun type(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
    }

    // ── the field ───────────────────────────────────────────────────────

    @Test
    fun theKeyField_isAMaskedTextField_withEveryManagerRequestOff_andNotInsideAForm() {
        withMountedRoot("bk-field") { root, element ->
            root.add(BroadcastKeyInput(labelText = "Stream-Schlüssel für diese Sendung", hintText = "Ein Hinweis."))
            val input = assertNotNull(element().querySelector("input") as? HTMLInputElement, "no field")
            assertEquals("text", input.type)
            assertTrue(input.classList.contains("lapis-secret-mask"), "the CSS mask is missing")
            assertEquals("off", input.getAttribute("autocomplete"))
            assertEquals("off", input.getAttribute("autocapitalize"))
            assertEquals("off", input.getAttribute("autocorrect"))
            assertEquals("false", input.getAttribute("spellcheck"))
            assertEquals("true", input.getAttribute("data-1p-ignore"))
            assertEquals("true", input.getAttribute("data-lpignore"))
            assertEquals("true", input.getAttribute("data-bwignore"))
            assertEquals("other", input.getAttribute("data-form-type"))
            assertNull(input.closest("form"), "the field must not sit in a <form>")
            val describedBy = assertNotNull(input.getAttribute("aria-describedby"))
            describedBy.split(" ").forEach { id ->
                assertNotNull(document.getElementById(id), "aria-describedby names a missing element: $id")
            }
        }
    }

    @Test
    fun withoutTheCssMask_theFieldFallsBackToAPasswordFieldWithAutocompleteOff() {
        val original = js("CSS.supports")
        js("CSS.supports = function () { return false; }")
        try {
            withMountedRoot("bk-field-fallback") { root, element ->
                root.add(BroadcastKeyInput(labelText = "Schlüssel", hintText = null))
                val input = element().querySelector("input") as HTMLInputElement
                assertEquals("password", input.type)
                assertEquals("off", input.getAttribute("autocomplete"))
                assertFalse(input.classList.contains("lapis-secret-mask"))
            }
        } finally {
            js("CSS.supports = original")
        }
    }

    @Test
    fun theFeedback_showsTheLength_neverTheKey_andIsNoLiveRegion() {
        withMountedRoot("bk-feedback") { root, element ->
            val field = BroadcastKeyInput(labelText = "Schlüssel", hintText = null)
            root.add(field)
            val input = element().querySelector("input") as HTMLInputElement
            type(input, secret)
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("Schlüssel eingegeben (${secret.length} Zeichen)"), "no length feedback: $text")
            assertFalse(text.contains(secret), "the key is in the visible text")
            val everything = element().all("*").flatMap { el -> (0 until el.attributes.length).map { el.attributes.item(it)!!.value } }
            assertFalse(everything.any { it.contains(secret) }, "the key leaked into an attribute")
            assertEquals(0, element().all("[aria-live]").size, "the length of a secret must not be announced")
            assertEquals(secret, field.takeAndClear())
            assertEquals("", input.value)
            assertFalse(element().textContent.orEmpty().contains("Schlüssel eingegeben"), "the feedback outlived the key")
        }
    }

    @Test
    fun aSpaceOrALineBreak_isAFieldError_andNothingIsTrimmed() {
        withMountedRoot("bk-problem") { root, element ->
            val field = BroadcastKeyInput(labelText = "Schlüssel", hintText = null)
            root.add(field)
            val input = element().querySelector("input") as HTMLInputElement
            assertTrue(field.problem()!!.contains("Stream-Schlüssel"), "an empty field must ask for the key")
            listOf("two words", " lead", "trail ").forEach { bad ->
                input.value = bad
                assertTrue(field.problem()!!.contains("Leerzeichen"), "no error for '$bad'")
            }
            input.value = secret
            assertNull(field.problem())
            assertEquals(secret, input.value, "nothing may be trimmed or repaired")
        }
    }

    // ── the start dialog ────────────────────────────────────────────────

    private val facebook =
        ConferenceStreamTargetDto(
            id = "t-fb",
            label = "Facebook Seite",
            platform = ConferenceStreamPlatform.GENERIC_RTMP,
            keyKind = ConferenceStreamKeyKind.PER_BROADCAST,
            keyHint = ConferenceStreamKeyHint.FACEBOOK,
        )
    private val youtube = ConferenceStreamTargetDto(id = "t-yt", label = "YouTube kanal", platform = ConferenceStreamPlatform.YOUTUBE)
    private val lost =
        ConferenceStreamTargetDto(
            id = "t-lost",
            label = "Verlorenes Ziel",
            platform = ConferenceStreamPlatform.GENERIC_RTMP,
            storedKeyMissing = true,
        )

    @Test
    fun theDialog_showsTheKeyFieldOnlyForATickedPerBroadcastTarget_withTheHintOfItsPlatform_andKeepsTheButtonActive(): Promise<Unit> =
        formTest {
            mountedForm("bk-dialog") { _, _ ->
                var confirmedIds: List<String>? = null
                var confirmedInputs: List<ConferenceStreamBroadcastInput>? = null
                startStreamDialog(listOf(facebook, youtube, lost), 3, emptyList()) { ids, _, _, _, inputs ->
                    confirmedIds = ids
                    confirmedInputs = inputs
                }
                awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertFalse(
                    (modal.querySelector(".lapis-broadcast-key") as? HTMLElement)?.isRendered() == true,
                    "the key field is shown before the target is ticked",
                )
                val boxes = modal.allOf("input[type=checkbox]")
                assertEquals(3, boxes.size)
                assertTrue(modal.textContent.orEmpty().contains("Facebook Seite (Generisches RTMP) -- braucht Schlüssel für diese Sendung"))
                assertTrue(modal.textContent.orEmpty().contains("Schlüssel fehlt, bitte in den Stream-Zielen eintragen"))
                assertTrue((boxes[2] as HTMLInputElement).disabled, "a target without its stored key cannot be ticked")

                boxes[0].click()
                awaitUntil("the key field appears") { (modal.querySelector(".lapis-broadcast-key") as? HTMLElement)?.isRendered() == true }
                val keyField = modal.querySelector(".lapis-broadcast-key") as HTMLElement
                assertTrue(keyField.textContent.orEmpty().contains("Facebook Live Producer"), "no Facebook hint: ${keyField.textContent}")
                val input = keyField.querySelector("input") as HTMLInputElement
                val confirm = modal.buttonNamed("Jetzt live gehen")
                assertFalse((confirm as org.w3c.dom.HTMLButtonElement).disabled, "the button stays active")

                // 1. no key: a field error, the focus in the field, the dialog stays, nothing was confirmed
                confirm.click()
                assertTrue(keyField.textContent.orEmpty().contains("Bitte geben Sie den Stream-Schlüssel dieser Sendung ein."))
                assertEquals(input, document.activeElement, "the focus did not jump into the field")
                assertTrue(document.querySelector(".modal.show") != null, "the dialog closed with a missing key")
                assertNull(confirmedIds)

                // 2. a key with a space: the field error about spaces
                type(input, "two words")
                confirm.click()
                assertTrue(keyField.textContent.orEmpty().contains("Leerzeichen"))
                assertNull(confirmedIds)

                // 3. a good key and a viewer address: confirmed, the field emptied, the dialog closed
                type(input, secret)
                val viewer = modal.all("details input").first() as HTMLInputElement
                viewer.value = "https://www.facebook.com/page/videos/123"
                confirm.click()
                assertEquals(listOf("t-fb"), confirmedIds)
                val sent = assertNotNull(confirmedInputs).single()
                assertEquals("t-fb", sent.destinationId)
                assertEquals(secret, sent.streamKey)
                assertEquals("https://www.facebook.com/page/videos/123", sent.viewerUrl)
                assertEquals("", input.value, "the key stayed in the field after the confirm")
                assertFalse(sent.toString().contains(secret), "toString of the input printed the key")
            }
        }

    @Test
    fun aTickedPersistentTarget_needsNoKeyField_andSendsNoInputs(): Promise<Unit> =
        formTest {
            mountedForm("bk-dialog-persistent") { _, _ ->
                var confirmedInputs: List<ConferenceStreamBroadcastInput>? = null
                startStreamDialog(listOf(facebook, youtube), 3, emptyList()) { _, _, _, _, inputs -> confirmedInputs = inputs }
                awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                modal.allOf("input[type=checkbox]")[1].click()
                modal.buttonNamed("Jetzt live gehen").click()
                assertEquals(emptyList(), confirmedInputs)
            }
        }

    @Test
    fun cancel_emptiesTheKeyFieldAndClosesTheDialog(): Promise<Unit> =
        formTest {
            mountedForm("bk-dialog-cancel") { _, _ ->
                startStreamDialog(listOf(facebook), 3, emptyList()) { _, _, _, _, _ -> }
                awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                modal.allOf("input[type=checkbox]")[0].click()
                awaitUntil("the key field appears") { modal.querySelector(".lapis-broadcast-key input") != null }
                val input = modal.querySelector(".lapis-broadcast-key input") as HTMLInputElement
                type(input, secret)
                modal.buttonNamed("Abbrechen").click()
                assertEquals("", input.value, "the key survived the cancel")
                awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
            }
        }

    // ── the destination form ────────────────────────────────────────────

    private val admin =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Ada Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun destination(
        id: String,
        kind: ConferenceStreamKeyKind,
        keySetAt: LocalDateTime?,
    ) = ConferenceStreamDestinationDto(
        id = id,
        label = "Ziel $id",
        platform = ConferenceStreamPlatform.GENERIC_RTMP,
        rtmpUrl = "rtmps://ingest.example.org/live",
        keyKind = kind,
        streamKeyMask = "********",
        streamKeySetAt = keySetAt,
        createdByDisplayName = "Ada Admin",
        enabled = true,
    )

    @Test
    fun theCreateForm_hasTheKindRadios_hidesTheKeyFieldForPerBroadcast_andSendsNoKey(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            val create =
                routeOf {
                    rpcService<IConferenceStreamingService>().createDestination("l", ConferenceStreamPlatform.GENERIC_RTMP, "u", "k")
                }
            val created = destination("d9", ConferenceStreamKeyKind.PER_BROADCAST, null)
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list ->
                            request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), emptyList()))
                        request.rpcRoute == create -> request.answerWith(jsonOf(ConferenceStreamDestinationDto.serializer(), created))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("bk-create-form") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the empty list") { element().textContent.orEmpty().contains("Noch keine Stream-Ziele") }
                    val host = openCreateForm(element(), "stream-destination-create")
                    val radios = host.allOf("input[type=radio]")
                    assertEquals(2, radios.size, "two radios: dauerhaft / nur pro Sendung")
                    assertTrue(host.textContent.orEmpty().contains("Lapis speichert den Schlüssel verschlüsselt"))
                    assertTrue(host.textContent.orEmpty().contains("Lapis löscht ihn, wenn die Sendung endet."))
                    val keyInput = host.querySelector("input[type=password]") as HTMLInputElement
                    assertTrue(keyInput.isRendered(), "the key field is shown for a permanent key")
                    type(keyInput, secret)

                    radios[1].click()
                    awaitUntil("the key field is hidden") { !keyInput.isRendered() }
                    assertEquals("", keyInput.value, "the typed key must be dropped with the switch")

                    host.typeInto("Bezeichnung", "Facebook pro Sendung")
                    host.typeInto("RTMP-Basis-URL", "rtmps://live-api-s.facebook.com:443/rtmp/")
                    host.buttonNamed("Stream-Ziel anlegen").click()
                    awaitUntil("the create call was made") { calls.toRoute(create).size == 1 }
                    val request = calls.toRoute(create).single()
                    assertNull(request.rpcParam(3), "a per-broadcast destination must send no key")
                    assertEquals("PER_BROADCAST", request.rpcParam(4) as String)
                    assertFalse(request.body.contains(secret))
                }
            }
        }

    @Test
    fun theList_marksAPerBroadcastDestination_andAPersistentOneWithoutAKey(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            val rows =
                listOf(
                    destination("d1", ConferenceStreamKeyKind.PER_BROADCAST, null),
                    destination("d2", ConferenceStreamKeyKind.PERSISTENT, null),
                    destination("d3", ConferenceStreamKeyKind.PERSISTENT, LocalDateTime(2026, 1, 1, 0, 0)),
                )
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), rows))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("bk-list") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the rows") { element().textContent.orEmpty().contains("Ziel d3") }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Schlüssel pro Sendung"), "no per-broadcast badge")
                    assertTrue(text.contains("Schlüssel fehlt -- bitte neu eingeben"), "no 'key missing' line")
                    assertTrue(text.contains("Schlüssel gesetzt am 2026-01-01"), "the persistent row lost its date")
                    assertEquals(1, text.split("Schlüssel fehlt -- bitte neu eingeben").size - 1)
                }
            }
        }

    @Test
    fun theEditForm_warnsThatSwitchingToPerBroadcastDeletesTheStoredKey(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            val rows = listOf(destination("d1", ConferenceStreamKeyKind.PERSISTENT, LocalDateTime(2026, 1, 1, 0, 0)))
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(jsonOf(ListSerializer(ConferenceStreamDestinationDto.serializer()), rows))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("bk-edit") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("the row") { element().textContent.orEmpty().contains("Ziel d1") }
                    element().buttonNamed("Bearbeiten").click()
                    awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                    val modal = lastOpenModal()
                    val text = "Der gespeicherte Schlüssel wird dabei gelöscht."

                    fun warningShown() = modal.allOf("div").any { it.textContent == text && it.isRendered() }

                    fun keyFieldShown() = (modal.querySelector("input[type=password]") as? HTMLElement)?.isRendered() == true
                    assertFalse(warningShown(), "the warning shows before any switch")
                    assertTrue(keyFieldShown(), "a permanent destination edits its key")
                    modal.allOf("input[type=radio]")[1].click()
                    awaitUntil("the warning appears") { warningShown() }
                    assertFalse(keyFieldShown(), "the key field stays visible for a per-broadcast destination")
                    modal.allOf("input[type=radio]")[0].click()
                    awaitUntil("the warning goes") { !warningShown() }
                    awaitUntil("the key field is back") { keyFieldShown() }
                }
            }
        }

    // ── the single place that opens a viewer page ───────────────────────

    private fun targetWith(url: String?) =
        ConferenceStreamTargetStatusDto(
            destinationId = "d1",
            label = "Ziel",
            platform = ConferenceStreamPlatform.YOUTUBE,
            status = ConferenceStreamTargetStatus.ACTIVE,
            retries = 0,
            failureReason = null,
            viewerUrl = url,
        )

    private fun <T> withStubbedOpen(block: (calls: MutableList<List<String>>) -> T): T {
        val calls = mutableListOf<List<String>>()
        val original = js("window.open")
        val record: (String, String, String) -> Unit = { url, target, features -> calls += listOf(url, target, features) }
        js("window.open = function (u, t, f) { record(u, t, f); return null; }")
        try {
            return block(calls)
        } finally {
            js("window.open = original")
        }
    }

    @Test
    fun openConferenceViewerUrl_opensTheStoredAddressWithNoopener_andAPopupOnlyForTheTwoChatPages() {
        withStubbedOpen { calls ->
            openConferenceViewerUrl(targetWith("https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ"))
            openConferenceViewerUrl(targetWith("https://www.twitch.tv/popout/mychannel/chat"))
            openConferenceViewerUrl(targetWith("https://www.facebook.com/page/videos/123"))
            openConferenceViewerUrl(targetWith("https://x.com/i/broadcasts/1abc"))
            assertEquals(4, calls.size)
            assertEquals(
                listOf(
                    "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ",
                    "_blank",
                    "noopener,noreferrer,popup,width=420,height=720",
                ),
                calls[0],
            )
            assertEquals(
                listOf("https://www.twitch.tv/popout/mychannel/chat", "_blank", "noopener,noreferrer,popup,width=420,height=720"),
                calls[1],
            )
            assertEquals(listOf("https://www.facebook.com/page/videos/123", "_blank", "noopener,noreferrer"), calls[2])
            assertEquals(listOf("https://x.com/i/broadcasts/1abc", "_blank", "noopener,noreferrer"), calls[3])
        }
    }

    @Test
    fun openConferenceViewerUrl_opensNothingWithoutAnAddressOrWithAnythingButHttps() {
        withStubbedOpen { calls ->
            openConferenceViewerUrl(targetWith(null))
            openConferenceViewerUrl(targetWith("javascript:alert(1)"))
            openConferenceViewerUrl(targetWith("http://example.org/live"))
            assertEquals(0, calls.size)
        }
    }

    @Test
    fun theHostHint_dropsWww_andPopupRecognitionIsExact() {
        assertEquals("youtube.com", conferenceViewerUrlHost("https://www.youtube.com/live_chat?v=x"))
        assertEquals("facebook.com", conferenceViewerUrlHost("https://facebook.com/a"))
        assertNull(conferenceViewerUrlHost("not a url"))
        assertFalse(conferenceViewerUrlOpensPopup("https://www.youtube.com.evil.test/live_chat?is_popout=1&v=x"))
        assertFalse(conferenceViewerUrlOpensPopup("https://www.twitch.tv/popout/ab/chat"))
        assertFalse(conferenceViewerUrlOpensPopup("https://www.twitch.tv/popout/mychannel/chat/extra"))
    }
}

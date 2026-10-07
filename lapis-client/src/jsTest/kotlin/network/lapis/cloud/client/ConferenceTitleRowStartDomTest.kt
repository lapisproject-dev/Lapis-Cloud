package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ApiKeyDto
import network.lapis.cloud.shared.domain.ApiKeyIssueResultDto
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingPageDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.WebhookEndpointDto
import network.lapis.cloud.shared.rpc.IApiKeyService
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.IWebhookService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.51 -- rule R36B/R57 for the conference lobby ("Besprechung jetzt starten" sits in the title row, with the plus icon, and goes
 * away while a call runs), the recording download link (verb icon) and the API-key screen (issue form collapsed behind "Neuer Schlüssel").
 */
class ConferenceTitleRowStartDomTest {
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"
    private val startLabel = "Besprechung jetzt starten"
    private val apiKeyFormId = "lapis-create-api-key"
    private val timeoutMs = 15_000

    private fun session(isGuest: Boolean = false) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            isGuest = isGuest,
        )

    private fun room() =
        ConferenceRoomDto(
            id = "room-1",
            title = "Besprechung",
            description = "",
            livekitRoomName = "lk-room-1",
            createdByMemberId = "member-1",
            createdByDisplayName = "Dana Keller",
            createdAt = LocalDateTime(2026, 10, 3, 10, 0),
            endedAt = null,
            active = true,
            maxParticipants = 10,
            liveParticipantCount = 0,
            myRole = ConferenceRole.MODERATOR,
        )

    private fun token() =
        ConferenceJoinTokenDto(
            roomId = "room-1",
            livekitRoomName = "lk-room-1",
            serverUrl = "ws://127.0.0.1:9",
            token = "not-a-real-token",
            identity = "member-1",
            displayName = "Dana Keller",
            role = ConferenceRole.MODERATOR,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.headerButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hostOpen(formId: String): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

    private class ConferenceRoutes(
        val availability: String,
        val list: String,
        val create: String,
        val join: String,
    )

    private suspend fun conferenceRoutes() =
        ConferenceRoutes(
            availability = routeOf { rpcService<IConferenceService>().getAvailability() },
            list = routeOf { rpcService<IConferenceService>().listActiveRooms() },
            create = routeOf { rpcService<IConferenceService>().createRoom(ConferenceRoomInput(title = "x")) },
            join = routeOf { rpcService<IConferenceService>().joinRoom("r") },
        )

    private fun available(enabled: Boolean = true) =
        jsonOf(
            ConferenceAvailabilityDto.serializer(),
            ConferenceAvailabilityDto(enabled = enabled, serverUrl = "ws://127.0.0.1:9", maxParticipants = 10),
        )

    private fun emptyRooms() = jsonOf(ListSerializer(ConferenceRoomDto.serializer()), emptyList())

    @Test
    fun startButton_sitsInTheTitleRow_withTheIcon_andTheLobbyKeepsNoStartSectionOrOldHeading(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = conferenceRoutes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == routes.availability -> request.answerWith(available())
                        request.rpcRoute == routes.list -> request.answerWith(emptyRooms())
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-conference-start-position") { root, element ->
                    renderConferenceScreen(root)
                    awaitUntil("the lobby is shown", timeoutMs) { element().shows("Aktive Besprechungen") }
                    val screen = element()
                    val buttons = screen.headerButtons()
                    assertEquals(listOf(startLabel), buttons.map { it.textContent?.trim() }, "the title row holds the start action")
                    val button = buttons.single()
                    assertTrue(button.classList.contains("btn-outline-primary"), "outlined primary, like every other new action")
                    val icon = assertNotNull(button.querySelector("i"), "the plus icon")
                    assertEquals("true", icon.getAttribute("aria-hidden"), "the icon is decoration")
                    assertTrue(icon.classList.contains("fa-plus"), "the plus: neither camera nor play")
                    assertFalse(screen.shows("Neue Besprechung"), "the old section heading is gone")
                    assertTrue(
                        screen.allOf("button").filter { it.textContent?.trim() == startLabel }.size == 1,
                        "no second start button in the content",
                    )
                }
            }
        }

    @Test
    fun doubleClick_createsExactlyOneRoom_andAFailedJoinKeepsTheRoomInTheList(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = conferenceRoutes()
            var rooms = emptyRooms()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == routes.availability -> request.answerWith(available())
                        request.rpcRoute == routes.list -> request.answerWith(rooms)
                        request.rpcRoute == routes.create -> {
                            rooms = jsonOf(ListSerializer(ConferenceRoomDto.serializer()), listOf(room()))
                            rpcResult(request.json.id as Int, jsonOf(ConferenceRoomDto.serializer(), room())).let {
                                StubResponse(status = it.status, text = it.text, delayMs = 300)
                            }
                        }
                        request.rpcRoute == routes.join -> serviceExceptionResult(request.json.id as Int, conflict)
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-conference-start-double") { root, element ->
                    renderConferenceScreen(root)
                    awaitUntil("the start button is shown", timeoutMs) { element().headerButtons().isNotEmpty() }
                    val button = element().headerButtons().single()
                    button.click()
                    button.click()
                    awaitUntil("the join was attempted", timeoutMs) { calls.toRoute(routes.join).size == 1 }
                    awaitUntil("the button is usable again", timeoutMs) {
                        element().headerButtons().singleOrNull()?.hasAttribute("disabled") == false
                    }
                    assertEquals(1, calls.toRoute(routes.create).size, "two quick clicks start exactly one room")
                    assertEquals(1, calls.toRoute(routes.join).size)
                    awaitUntil("the room stays in the list for a manual join", timeoutMs) { element().shows("Beitreten") }
                    val again = element().headerButtons().single()
                    assertEquals(startLabel, again.textContent?.trim(), "the label is restored")
                    assertNotNull(again.querySelector("i[aria-hidden=true]"), "and so is the icon")
                }
            }
        }

    @Test
    fun failedCreate_restoresButton_labelAndIcon(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = conferenceRoutes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == routes.availability -> request.answerWith(available())
                        request.rpcRoute == routes.list -> request.answerWith(emptyRooms())
                        request.rpcRoute == routes.create -> serviceExceptionResult(request.json.id as Int, conflict)
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-conference-start-failed") { root, element ->
                    renderConferenceScreen(root)
                    awaitUntil("the start button is shown", timeoutMs) { element().headerButtons().isNotEmpty() }
                    element().headerButtons().single().click()
                    awaitUntil("create was attempted", timeoutMs) { calls.toRoute(routes.create).size == 1 }
                    awaitUntil("the button is usable again", timeoutMs) {
                        element().headerButtons().singleOrNull()?.let {
                            !it.hasAttribute(
                                "disabled",
                            ) &&
                                it.textContent?.trim() == startLabel
                        } ==
                            true
                    }
                    assertNotNull(
                        element().headerButtons().single().querySelector("i[aria-hidden=true]"),
                        "the icon survived the label change",
                    )
                    assertEquals(0, calls.toRoute(routes.join).size, "no join without a room")
                }
            }
        }

    @Test
    fun enteringACall_hidesTheTitleRowStart_soNoSecondMeetingCanBeStarted(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = conferenceRoutes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == routes.availability -> request.answerWith(available())
                        request.rpcRoute == routes.list -> request.answerWith(emptyRooms())
                        request.rpcRoute == routes.create -> request.answerWith(jsonOf(ConferenceRoomDto.serializer(), room()))
                        request.rpcRoute == routes.join -> request.answerWith(jsonOf(ConferenceJoinTokenDto.serializer(), token()))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-conference-start-enter-call") { root, element ->
                    // V1.9.70: the call view lives in the dock host (next to the route outlet), so the test mounts one
                    ConferenceDock.resetForTest()
                    ConferenceDock.bindHost(root.vPanel())
                    try {
                        renderConferenceScreen(root)
                        awaitUntil("the start button is shown", timeoutMs) { element().headerButtons().isNotEmpty() }
                        element().headerButtons().single().click()
                        awaitUntil("the call panel is shown (enterCall was reached)", timeoutMs) {
                            element().querySelector(".lapis-conference-call-panel") != null
                        }
                        assertEquals(1, calls.toRoute(routes.create).size)
                        assertEquals(emptyList(), element().headerButtons().map { it.textContent }, "no start action inside a call")
                        assertFalse(element().shows("Aktive Besprechungen"), "the lobby is gone while the call runs")
                    } finally {
                        ConferenceDock.resetForTest()
                    }
                }
            }
        }

    @Test
    fun lobbyVisibility_movesThePanelAndItsTitleRowActionTogether(): Promise<Unit> =
        formTest {
            mountedForm("r36b-conference-lobby-visibility") { root, element ->
                val header = root.pageHeader("Videokonferenz")
                val lobby = root.vPanel()
                lobby.div("Lobby-Inhalt")
                val action = header.actionSlot
                action.div("Start")
                registerConferenceLobbyHeaderAction(lobby, action)
                awaitUntil("both are shown", timeoutMs) { element().shows("Lobby-Inhalt") && element().shows("Start") }

                setConferenceLobbyVisible(lobby, false)
                awaitUntil("both are hidden", timeoutMs) {
                    !element().shows("Lobby-Inhalt") &&
                        element().querySelector(".lapis-page-action") == null
                }
                setConferenceLobbyVisible(lobby, true)
                awaitUntil("both are back", timeoutMs) { element().shows("Lobby-Inhalt") && element().shows("Start") }
            }
        }

    @Test
    fun noStartButton_forAGuest_whenDisabled_orAfterALoadError(): Promise<Unit> =
        formTest {
            val routes = conferenceRoutes()
            for (scenario in listOf("guest", "disabled", "error")) {
                AppState.setSession(session(isGuest = scenario == "guest"))
                withFetchStub(
                    respond = { request ->
                        when {
                            !request.isRpc -> StubResponse()
                            request.rpcRoute == routes.availability ->
                                if (scenario == "error") {
                                    serviceExceptionResult(request.json.id as Int, conflict)
                                } else {
                                    request.answerWith(available(enabled = scenario != "disabled"))
                                }
                            else -> request.answerWith("[]")
                        }
                    },
                ) { calls ->
                    mountedForm("r36b-conference-no-start-$scenario") { root, element ->
                        renderConferenceScreen(root)
                        awaitUntil("[$scenario] availability was asked", timeoutMs) { calls.toRoute(routes.availability).size == 1 }
                        delay(300)
                        assertEquals(emptyList(), element().headerButtons().map { it.textContent }, "[$scenario] no title-row action")
                        assertFalse(element().shows(startLabel), "[$scenario] no start button anywhere")
                    }
                }
            }
        }

    @Test
    fun recordingDownload_isALinkWithTheDownloadIcon_andOpensSafely(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val availabilityRoute = routeOf { rpcService<IConferenceRecordingService>().getRecordingAvailability() }
            val listRoute =
                routeOf {
                    rpcService<IConferenceRecordingService>().listRecordings(ConferenceRecordingListQuery())
                }
            val recording =
                ConferenceRecordingDto(
                    id = "rec-1",
                    roomId = "room-1",
                    roomTitle = "Vorstandssitzung",
                    status = ConferenceRecordingStatus.READY,
                    startedByMemberId = "member-1",
                    startedByDisplayName = "Dana Keller",
                    startedAt = LocalDateTime(2026, 10, 3, 10, 0),
                    stoppedAt = LocalDateTime(2026, 10, 3, 10, 30),
                    readyAt = LocalDateTime(2026, 10, 3, 10, 40),
                    durationSeconds = 1800,
                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                    documentId = "doc-1",
                    mediaUrl = "/api/conference/recordings/rec-1/media",
                    fileSizeBytes = 1_048_576,
                    trackCount = 2,
                    failureReason = null,
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == availabilityRoute ->
                            request.answerWith(
                                jsonOf(
                                    ConferenceRecordingAvailabilityDto.serializer(),
                                    ConferenceRecordingAvailabilityDto(enabled = true, ffmpegAvailable = true, maxDurationMinutes = 120),
                                ),
                            )
                        request.rpcRoute == listRoute ->
                            request.answerWith(
                                jsonOf(
                                    ConferenceRecordingPageDto.serializer(),
                                    ConferenceRecordingPageDto(rows = listOf(recording), totalCount = 1, limit = 25, offset = 0),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r57-conference-recording-download") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the recording row is shown", timeoutMs) { element().shows("Vorstandssitzung") }
                    val html = element().innerHTML
                    val link =
                        assertNotNull(
                            element().allOf("a").firstOrNull { it.textContent?.trim() == "Herunterladen" },
                            "no download link in: ${html.take(1500)}",
                        )
                    assertEquals("_blank", link.getAttribute("target"), "target in: ${link.outerHTML}")
                    assertEquals("noopener", link.getAttribute("rel"), "rel in: ${link.outerHTML}")
                    assertTrue(
                        link.getAttribute("href").orEmpty().endsWith("/api/conference/recordings/rec-1/media"),
                        "href in: ${link.outerHTML}",
                    )
                    val icon = assertNotNull(link.querySelector(".lapis-action-icon"), "the download icon in: ${link.outerHTML}")
                    assertEquals("true", icon.getAttribute("aria-hidden"), "aria-hidden in: ${link.outerHTML}")
                    assertTrue(icon.classList.contains("fa-download"), "icon class in: ${link.outerHTML}")
                }
            }
        }

    @Test
    fun apiKeys_issueFormIsCollapsed_opensFromTheTitleRow_andShowsTheOneTimeCardAfterwards(): Promise<Unit> =
        formTest {
            AppState.setSession(session().copy(role = AccountRole.ADMIN))
            val listKeys = routeOf { rpcService<IApiKeyService>().listApiKeys(includeRevoked = true) }
            val listHooks = routeOf { rpcService<IWebhookService>().listWebhookEndpoints() }
            val issue = routeOf { rpcService<IApiKeyService>().issueApiKey(label = "x") }
            val key =
                ApiKeyDto(
                    id = "key-1",
                    label = "CI",
                    keyPrefix = "lapis_ab12",
                    createdAt = LocalDateTime(2026, 10, 3, 10, 0),
                    createdByMemberId = "member-1",
                    expiresAt = null,
                    revokedAt = null,
                    lastUsedAt = null,
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listKeys -> request.answerWith(jsonOf(ListSerializer(ApiKeyDto.serializer()), emptyList()))
                        request.rpcRoute == listHooks ->
                            request.answerWith(jsonOf(ListSerializer(WebhookEndpointDto.serializer()), emptyList()))
                        request.rpcRoute == issue ->
                            request.answerWith(
                                jsonOf(
                                    ApiKeyIssueResultDto.serializer(),
                                    ApiKeyIssueResultDto(apiKey = key, rawKey = "lapis_SECRET-VALUE"),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-apikeys-collapsed") { root, element ->
                    renderApiKeysScreen(root)
                    awaitUntil("the list was loaded", timeoutMs) { calls.toRoute(listKeys).isNotEmpty() }
                    val screen = element()
                    assertEquals(listOf("Neuer Schlüssel"), screen.headerButtons().map { it.textContent?.trim() })
                    assertFalse(screen.hostOpen(apiKeyFormId), "collapsed after the load")
                    assertFalse(screen.shows("Schlüssel ausstellen"), "no always-visible issue button")

                    // Cancel without input closes without a question.
                    val first = openCreateForm(screen, apiKeyFormId)
                    first.buttonNamed("Abbrechen").click()
                    awaitUntil("closed without a question", timeoutMs) { !screen.hostOpen(apiKeyFormId) }
                    assertTrue(noModalOpen(), "no confirmation dialog")

                    // Cancel with input asks first; "Weiter bearbeiten" keeps the form.
                    val host = openCreateForm(screen, apiKeyFormId)
                    host.typeInto("Bezeichnung", "CI")
                    host.buttonNamed("Abbrechen").click()
                    awaitUntil("the discard question is shown", timeoutMs) { !noModalOpen() }
                    lastOpenModal().buttonNamed("Weiter bearbeiten").click()
                    awaitUntil("the question is gone", timeoutMs) { noModalOpen() }
                    assertTrue(screen.hostOpen(apiKeyFormId), "the typed label is kept")

                    val listCallsBefore = calls.toRoute(listKeys).size
                    host.buttonNamed("Schlüssel ausstellen").click()
                    awaitUntil("the key was issued", timeoutMs) { calls.toRoute(issue).size == 1 }
                    assertEquals("CI", calls.toRoute(issue).single().rpcParam(0) as String)
                    awaitUntil("the form folded back", timeoutMs) { !screen.hostOpen(apiKeyFormId) }
                    awaitUntil("the one-time card is shown", timeoutMs) { screen.shows("lapis_SECRET-VALUE") }
                    awaitUntil("the list was reloaded", timeoutMs) { calls.toRoute(listKeys).size > listCallsBefore }
                }
            }
        }

    private fun noModalOpen(): Boolean = document.querySelector(".modal.show") == null
}

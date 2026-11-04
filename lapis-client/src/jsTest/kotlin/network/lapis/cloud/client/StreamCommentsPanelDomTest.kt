package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.274 -- "Kommentare öffnen" (comments, level 1): the link exists only for a moderator, only while the stream runs and only for a target with a
 * viewer address; opening goes through the one stubbed `window.open`; a poll refresh must not destroy a half typed address.
 */
class StreamCommentsPanelDomTest {
    private val youtubeChat = "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ"

    private fun target(
        id: String,
        label: String,
        viewerUrl: String?,
    ) = ConferenceStreamTargetStatusDto(
        destinationId = id,
        label = label,
        platform = ConferenceStreamPlatform.YOUTUBE,
        status = ConferenceStreamTargetStatus.ACTIVE,
        retries = 0,
        failureReason = null,
        viewerUrl = viewerUrl,
    )

    private fun stream(
        status: ConferenceStreamStatus,
        vararg targets: ConferenceStreamTargetStatusDto,
    ) = ConferenceStreamDto(
        id = "s1",
        roomId = "r1",
        roomTitle = "Raum",
        status = status,
        layout = ConferenceStreamLayout.GRID,
        latencyMode = ConferenceStreamLatencyMode.STANDARD,
        startedByMemberId = "m1",
        startedByDisplayName = "Ada",
        startedAt = LocalDateTime(2026, 11, 4, 9, 0),
        pausedAt = null,
        endedAt = null,
        restartCount = 0,
        targets = targets.toList(),
        failureReason = null,
        pauseReason = null,
    )

    private val admin = SessionInfoDto("admin-1", "Ada Admin", AccountRole.ADMIN, LocalDateTime(2099, 1, 1, 0, 0))

    private fun HTMLElement.hasButton(text: String) = allOf("button").any { it.textContent?.trim() == text }

    @Test
    fun aModerator_seesTheLinkOnlyForATargetWithAnAddress_whileTheStreamRuns() {
        withMountedRoot("scp-moderator") { root, element ->
            val panel = StreamCommentsPanel()
            root.add(panel)
            val withUrl = target("d1", "YouTube Kanal", youtubeChat)
            val without = target("d2", "Zweites Ziel", null)
            panel.update(stream(ConferenceStreamStatus.LIVE, withUrl, without), canModerate = true) {}
            val open = element().allOf("button").single { it.textContent?.trim() == "Kommentare öffnen" }
            assertEquals("Kommentare auf YouTube Kanal öffnen (neues Fenster)", open.getAttribute("aria-label"))
            assertTrue(element().textContent.orEmpty().contains("öffnet youtube.com"), "no host line: ${element().textContent}")
            assertTrue(element().hasButton("Zuschauer-Adresse ändern"))
            assertTrue(element().hasButton("Zuschauer-Adresse eintragen"))
            assertEquals(1, element().allOf("button").count { it.textContent?.trim() == "Kommentare öffnen" })
        }
    }

    @Test
    fun noOne_butAModerator_seesAnything_andAnEndedStreamShowsNothing() {
        withMountedRoot("scp-others") { root, element ->
            val panel = StreamCommentsPanel()
            root.add(panel)
            val live = stream(ConferenceStreamStatus.LIVE, target("d1", "YouTube Kanal", youtubeChat))
            panel.update(live, canModerate = false) {}
            assertEquals("", element().textContent.orEmpty().trim(), "a non-moderator must see nothing")
            panel.update(live, canModerate = true) {}
            assertTrue(element().hasButton("Kommentare öffnen"))
            panel.update(stream(ConferenceStreamStatus.ENDED, target("d1", "YouTube Kanal", youtubeChat)), canModerate = true) {}
            assertFalse(element().hasButton("Kommentare öffnen"))
            panel.update(null, canModerate = true) {}
            assertEquals("", element().textContent.orEmpty().trim())
            // PAUSED keeps the link (the stream is still the broadcast being moderated)
            panel.update(stream(ConferenceStreamStatus.PAUSED, target("d1", "YouTube Kanal", youtubeChat)), canModerate = true) {}
            assertTrue(element().hasButton("Kommentare öffnen"))
        }
    }

    @Test
    fun clickingTheLink_opensTheStoredAddressThroughTheOneWindowOpen() {
        val calls = mutableListOf<List<String>>()
        val original = js("window.open")
        val record: (String, String, String) -> Unit = { url, targetName, features -> calls += listOf(url, targetName, features) }
        js("window.open = function (u, t, f) { record(u, t, f); return null; }")
        try {
            withMountedRoot("scp-open") { root, element ->
                val panel = StreamCommentsPanel()
                root.add(panel)
                panel.update(stream(ConferenceStreamStatus.LIVE, target("d1", "YouTube Kanal", youtubeChat)), canModerate = true) {}
                element().allOf("button").single { it.textContent?.trim() == "Kommentare öffnen" }.click()
                assertEquals(listOf(listOf(youtubeChat, "_blank", "noopener,noreferrer,popup,width=420,height=720")), calls)
            }
        } finally {
            js("window.open = original")
        }
    }

    @Test
    fun aRefreshWhileTheEditorIsOpen_doesNotDestroyTheTypedAddress(): Promise<Unit> =
        formTest {
            mountedForm("scp-editor") { root, element ->
                val panel = StreamCommentsPanel()
                root.add(panel)
                val first = stream(ConferenceStreamStatus.LIVE, target("d1", "YouTube Kanal", null))
                panel.update(first, canModerate = true) {}
                element().buttonNamed("Zuschauer-Adresse eintragen").click()
                awaitUntil("the editor input") { element().querySelector("input") != null }
                val input = element().querySelector("input") as HTMLInputElement
                input.value = "https://youtu.be/dQw4w9WgXcQ"
                input.dispatchEvent(Event("input"))
                // a poll refresh brings a changed address from elsewhere
                panel.update(stream(ConferenceStreamStatus.LIVE, target("d1", "YouTube Kanal", youtubeChat)), canModerate = true) {}
                assertTrue(panel.editing)
                assertEquals(input, element().querySelector("input"), "the editor input was replaced by a refresh")
                assertEquals("https://youtu.be/dQw4w9WgXcQ", input.value)
                // cancel closes it and shows the refreshed state
                element().buttonNamed("Abbrechen").click()
                awaitUntil("the refreshed link") { element().hasButton("Kommentare öffnen") }
                assertFalse(panel.editing)
            }
        }

    @Test
    fun savingAnAddress_callsTheServer_reportsTheNewTarget_andShowsTheLink(): Promise<Unit> =
        formTest {
            AppState.setSession(admin)
            val set = routeOf { rpcService<IConferenceStreamingService>().setViewerUrl("s", "d", null) }
            val saved = target("d1", "YouTube Kanal", youtubeChat)
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == set) {
                        request.answerWith(jsonOf(ConferenceStreamTargetStatusDto.serializer(), saved))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("scp-save") { root, element ->
                    val panel = StreamCommentsPanel()
                    root.add(panel)
                    var reported: ConferenceStreamTargetStatusDto? = null
                    panel.update(stream(ConferenceStreamStatus.LIVE, target("d1", "YouTube Kanal", null)), canModerate = true) {
                        reported =
                            it
                    }
                    element().buttonNamed("Zuschauer-Adresse eintragen").click()
                    awaitUntil("the editor input") { element().querySelector("input") != null }
                    val input = element().querySelector("input") as HTMLInputElement
                    input.value = "https://youtu.be/dQw4w9WgXcQ"
                    element().buttonNamed("Speichern").click()
                    awaitUntil("the call was made") { calls.toRoute(set).size == 1 }
                    val request = calls.toRoute(set).single()
                    assertEquals("s1", request.rpcParam(0) as String)
                    assertEquals("d1", request.rpcParam(1) as String)
                    assertEquals("https://youtu.be/dQw4w9WgXcQ", request.rpcParam(2) as String)
                    awaitUntil("the link appears") { element().hasButton("Kommentare öffnen") }
                    assertEquals(youtubeChat, assertNotNull(reported).viewerUrl)
                }
            }
        }
}

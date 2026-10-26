package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.VideoOverlayAdminItemDto
import network.lapis.cloud.shared.domain.VideoOverlayAdminLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayUploadError
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IVideoOverlayService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.129 -- the ADMIN part of the card "Videokonferenz": the second switch "Overlays anbieten" (own RPC, follows the server, springs back on
 * a refusal), the library list with its actions, the explanation for an SVG instance logo, and the fixed sentences of every refused upload.
 */
class ConferenceVideoOverlayLibrarySettingsDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun settings(offered: Boolean) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto("o1", "Verein", null, null, null, null, null, null, null, null, videoOverlaysOffered = offered),
        )

    private fun item(
        id: String,
        name: String,
        kind: VideoOverlayKind = VideoOverlayKind.LOGO,
    ) = VideoOverlayAdminItemDto(
        VideoOverlayItemDto(id, kind, name, 600, 300, "/api/video-overlays/${"A".repeat(43)}"),
        sizeBytes = 20_480,
        createdAt = LocalDateTime(2026, 10, 14, 10, 0),
    )

    private fun library(
        items: List<VideoOverlayAdminItemDto> = emptyList(),
        offered: Boolean = false,
        instanceLogoOffered: Boolean = false,
        reason: String? = null,
    ) = jsonOf(VideoOverlayAdminLibraryDto.serializer(), VideoOverlayAdminLibraryDto(offered, items, instanceLogoOffered, reason))

    private fun HTMLElement.switches(): List<HTMLElement> {
        val list = querySelectorAll("button[role=switch]")
        return (0 until list.length).map { list.item(it) as HTMLElement }
    }

    @Test
    fun theOverlaySwitch_followsTheServer_sendsTheNewValue_andSpringsBackOnARefusal(): Promise<Unit> =
        test {
            val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val setRoute = routeOf { rpcService<IOrganizationSettingsService>().updateVideoOverlaysOffered(true) }
            val libRoute = routeOf { rpcService<IVideoOverlayService>().getAdminLibrary() }
            val sent = mutableListOf<Boolean>()
            var refuse = false
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> rpcResult(request.json.id as Int, settings(false))
                    request.rpcRoute == libRoute -> rpcResult(request.json.id as Int, library())
                    request.rpcRoute == setRoute -> {
                        sent += request.rpcParam(0) as Boolean
                        if (refuse) {
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ForbiddenException")
                        } else {
                            rpcResult(request.json.id as Int, settings(request.rpcParam(0) as Boolean))
                        }
                    }
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("overlay-admin-switch") { root, element ->
                    renderConferenceVideoSettings(root)
                    awaitUntil("both switches are there") { element().switches().size >= 2 }
                    val overlay = element().switches()[1]
                    assertEquals("false", overlay.getAttribute("aria-checked"))
                    val label = assertNotNull(element().querySelector("#" + overlay.getAttribute("aria-labelledby")))
                    assertEquals("Overlays anbieten", label.textContent?.trim())
                    val help = assertNotNull(element().querySelector("#" + overlay.getAttribute("aria-describedby")))
                    assertTrue(help.textContent.orEmpty().contains("Aufnahme und Livestream"), help.textContent)
                    overlay.click()
                    awaitUntil("on") { overlay.getAttribute("aria-checked") == "true" && !overlay.hasAttribute("disabled") }
                    refuse = true
                    overlay.click()
                    awaitUntil("the refused call came back") { sent.size == 2 && !overlay.hasAttribute("disabled") }
                    assertEquals("true", overlay.getAttribute("aria-checked"), "the switch is where the server has it")
                    assertEquals(listOf(true, false), sent)
                }
            }
        }

    @Test
    fun theLibrary_listsItsElementsWithNameKindAndSize_andExplainsAnSvgInstanceLogo(): Promise<Unit> =
        test {
            val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val libRoute = routeOf { rpcService<IVideoOverlayService>().getAdminLibrary() }
            val items =
                listOf(
                    item("11111111-1111-4111-8111-111111111111", "Vereinslogo"),
                    item("22222222-2222-4222-8222-222222222222", "Goldrahmen", VideoOverlayKind.FRAME),
                )
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> rpcResult(request.json.id as Int, settings(true))
                    request.rpcRoute == libRoute -> rpcResult(request.json.id as Int, library(items, offered = true, reason = "svg"))
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("overlay-admin-library") { root, element ->
                    renderConferenceVideoSettings(root)
                    awaitUntil("the library is shown") { element().textContent.orEmpty().contains("Goldrahmen") }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Vereinslogo"))
                    assertTrue(text.contains("600 × 300 px"), text)
                    assertTrue(text.contains("20 KB"), text)
                    assertTrue(text.contains("SVG-Datei"), "the SVG instance logo is explained: $text")
                    assertEquals("true", element().switches()[1].getAttribute("aria-checked"))
                    // Two rows, each with move up/down, rename and delete; the first can not move up, the last not down.
                    val buttons = element().querySelectorAll("button[aria-label=\"Nach oben\"]")
                    assertEquals(2, buttons.length)
                    assertTrue((buttons.item(0) as HTMLElement).hasAttribute("disabled"))
                    assertTrue(!(buttons.item(1) as HTMLElement).hasAttribute("disabled"))
                    val down = element().querySelectorAll("button[aria-label=\"Nach unten\"]")
                    assertTrue(!(down.item(0) as HTMLElement).hasAttribute("disabled"))
                    assertTrue((down.item(1) as HTMLElement).hasAttribute("disabled"))
                    // The thumbnails are plain same-origin images of the stored elements.
                    val thumbs = element().querySelectorAll("img.lapis-overlay-admin-thumb")
                    assertEquals(2, thumbs.length)
                    assertTrue((thumbs.item(0) as HTMLElement).getAttribute("src").orEmpty().startsWith("/api/video-overlays/"))
                }
            }
        }

    @Test
    fun everyRefusedUploadHasItsOwnFixedSentence() {
        val sentences = VideoOverlayUploadError.entries.map { videoOverlayUploadErrorText(it) }
        assertTrue(sentences.all { it.isNotBlank() })
        // FORBIDDEN and INVALID_REQUEST share the generic sentence (a refused request is not the admin's mistake); every other code is explained by itself.
        assertEquals(
            VideoOverlayUploadError.entries.size - 1,
            sentences.toSet().size,
            "all but the shared generic sentence are distinct: ${sentences.joinToString(" | ")}",
        )
        assertEquals(videoOverlayUploadErrorText(null), videoOverlayUploadErrorText(VideoOverlayUploadError.FORBIDDEN))
        assertNotEquals(
            videoOverlayUploadErrorText(VideoOverlayUploadError.FRAME_NEEDS_ALPHA),
            videoOverlayUploadErrorText(VideoOverlayUploadError.FRAME_NOT_PNG),
        )
    }
}

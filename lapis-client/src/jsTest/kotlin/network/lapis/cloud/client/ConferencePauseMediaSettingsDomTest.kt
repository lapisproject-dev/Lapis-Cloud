package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PauseMediaUploadError
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.255 -- the ADMIN cards "Pausenbild" and "Pausenmusik": empty and filled state, the 16:9 preview, the music label as TEXT only, the
 * "Entfernen" buttons only when something is stored, and one fixed sentence per refusal.
 */
class ConferencePauseMediaSettingsDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun dto(
        custom: Boolean = false,
        label: String? = null,
    ) = OrganizationSettingsDto(
        "o1",
        "Verein",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        pauseImageCustom = custom,
        pauseImageVersion = if (custom) "abcd1234" else null,
        pauseMusicLabel = label,
        pauseMusicDurationSeconds = if (label != null) 125 else null,
        pauseMusicVersion = if (label != null) "ef567890" else null,
    )

    private fun HTMLElement.buttonTexts(): List<String> {
        val list = querySelectorAll("button")
        return (0 until list.length).map { (list.item(it) as HTMLElement).textContent.orEmpty().trim() }
    }

    @Test
    fun emptyState_showsTheDefaultPictureAndNoMusic_withoutAnyRemoveButton(): Promise<Unit> =
        test {
            withMountedRoot("pause-media-empty") { root, element ->
                renderPauseMediaSection(root, dto(), onChanged = {})
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Nur im Live-Stream zu sehen und zu hören."), text)
                assertTrue(text.contains("Es wird das Standardbild verwendet."), text)
                assertTrue(text.contains("Keine Musik hinterlegt."), text)
                assertTrue(text.contains("Laden Sie nur Musik hoch, für deren öffentliche Wiedergabe Sie die Rechte haben."), text)
                assertTrue(
                    element().buttonTexts().none { it.contains("Entfernen") },
                    "nothing stored, nothing to remove: ${element().buttonTexts()}",
                )
                assertTrue(element().buttonTexts().any { it.contains("Eigenes Bild hochladen") })
                assertTrue(element().buttonTexts().any { it.contains("Musik hochladen") })
                val preview = assertNotNull(element().querySelector("div.lapis-pause-preview"))
                val img = assertNotNull(preview.querySelector("img"))
                assertEquals("/api/conference/pause-media/image?v=0", img.getAttribute("src"))
                assertNull(element().querySelector("audio"))
            }
        }

    @Test
    fun filledState_hasBothRemoveButtons_aPlayer_andTheLabelOnlyAsText(): Promise<Unit> =
        test {
            withMountedRoot("pause-media-filled") { root, element ->
                renderPauseMediaSection(root, dto(custom = true, label = "<b>Lied</b> <img src=x onerror=alert(1)>.mp3"), onChanged = {})
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Ein eigenes Bild ist hinterlegt."), text)
                assertEquals(2, element().buttonTexts().count { it.contains("Entfernen") })
                assertEquals(
                    "/api/conference/pause-media/image?v=abcd1234",
                    element().querySelector("div.lapis-pause-preview img")?.getAttribute("src"),
                )
                val audio = assertNotNull(element().querySelector("audio"))
                assertEquals("/api/conference/pause-media/music?v=ef567890", audio.getAttribute("src"))
                assertEquals("none", audio.getAttribute("preload"))
                assertTrue(audio.hasAttribute("controls"))
                // the label (a person's file name) is text: no element was made out of it, and the duration is m:ss
                val label = assertNotNull(element().querySelector(".lapis-pause-music-label"))
                assertEquals(0, label.querySelectorAll("*").length, label.innerHTML)
                assertTrue(label.textContent.orEmpty().contains("<b>Lied</b>"), label.textContent)
                assertTrue(label.textContent.orEmpty().endsWith("· 2:05"), label.textContent)
                assertNull(element().querySelector("img[onerror]"))
            }
        }

    @Test
    fun everyRefusalHasItsOwnFixedSentence_andTheDurationIsFormatted() {
        val sentences = PauseMediaUploadError.entries.map { pauseMediaErrorText(it) }
        assertTrue(sentences.all { it.isNotBlank() })
        // FORBIDDEN and INVALID_REQUEST (and an unknown error) share one generic sentence; every other code has its own
        assertEquals(PauseMediaUploadError.entries.size - 1, sentences.toSet().size, sentences.joinToString(" | "))
        assertEquals(pauseMediaErrorText(null), pauseMediaErrorText(PauseMediaUploadError.FORBIDDEN))
        assertEquals("0:05", formatPauseMusicDuration(5))
        assertEquals("1:05", formatPauseMusicDuration(65))
        assertEquals("10:00", formatPauseMusicDuration(600))
        assertEquals("0", pauseMediaVersionParam(null))
        assertEquals("abc123", pauseMediaVersionParam("abc?1&2#3"))
    }
}

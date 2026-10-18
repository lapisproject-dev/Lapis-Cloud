package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.ConferenceEventCaptureState
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.158 -- an unavailable option is never offered (dialog) or is aria-disabled with a reason (bar), in a real mounted root. */
class ConferenceUnavailableOptionsDomTest {
    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun resolved(text: String?) = (text ?: "").removePrefix("###KvI18nS###")

    private fun closeDialogs() {
        document.querySelectorAll(".modal").let { list -> for (i in 0 until list.length) (list.item(i) as? HTMLElement)?.remove() }
        document.querySelectorAll(".modal-backdrop").let { list -> for (i in 0 until list.length) (list.item(i) as? HTMLElement)?.remove() }
    }

    private fun modalOptionValues(): List<String> {
        val options = document.querySelectorAll(".modal option")
        return (0 until options.length).map { (options.item(it) as HTMLElement).getAttribute("value").orEmpty() }
    }

    @Test
    fun theStartDialogOfAnEventRoom_offersBoardAndAdministrationOnly_andExplainsWhy() {
        withMountedRoot("unavailable-dialog-event") { _, _ ->
            startRecordingConfirmDialog(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_AVAILABLE) {}
            try {
                assertEquals(listOf("BOARD_ONLY", "ADMIN_ONLY"), modalOptionValues())
                assertNotNull(document.querySelector("[aria-describedby='recording-access-hint']"))
                val hint = assertNotNull(document.getElementById("recording-access-hint"))
                assertTrue(resolved(hint.textContent).contains("nur für den Vorstand oder die Administration"))
                assertFalse(resolved(hint.textContent).contains("wählen Sie"), "the 'choose members' advice must be gone")
            } finally {
                closeDialogs()
            }
        }
    }

    @Test
    fun theStartDialogOfAnOrdinaryRoom_isUnchanged_threeLevelsAndTheOldAdvice() {
        withMountedRoot("unavailable-dialog-ordinary") { _, _ ->
            startRecordingConfirmDialog(ConferenceEventCaptureState.NONE) {}
            try {
                assertEquals(DocumentAccessLevel.entries.map { it.name }, modalOptionValues())
                val hint = assertNotNull(document.getElementById("recording-access-hint"))
                assertTrue(resolved(hint.textContent).contains("wählen Sie"))
            } finally {
                closeDialogs()
            }
        }
    }

    @Test
    fun aBlockedRecordButton_staysFocusable_pointsToTheReason_andLosesItWhenAvailable() {
        withMountedRoot("unavailable-bar") { root, element ->
            val group = assertNotNull(root.conferenceModerationGroup(true, {}, {}))
            group.setRecordingAvailable(true)
            group.setStreamingAvailable(true)
            val host = element()
            val record = { host.q("button[aria-label='Aufzeichnung']") }
            val stream = { host.q("button[aria-label='Live-Stream']") }

            group.applyRecording(recordingToggleView(null, canStart = false, blockedReason = "Grund"))
            group.applyStream(streamToggleView(null, null, canStart = false, blockedReason = "Grund"))
            listOf(record(), stream()).forEach { button ->
                assertEquals("true", button.getAttribute("aria-disabled"))
                assertEquals(ConferenceModerationGroup.CAPTURE_REASON_ID, button.getAttribute("aria-describedby"))
                assertNull(button.getAttribute("disabled"), "not `disabled`: it must stay in the focus order")
                assertTrue(button.classList.contains("disabled"))
            }

            group.applyRecording(recordingToggleView(null, canStart = true))
            group.applyStream(streamToggleView(null, null, canStart = true))
            listOf(record(), stream()).forEach { button ->
                assertNull(button.getAttribute("aria-disabled"))
                assertNull(button.getAttribute("aria-describedby"))
                assertFalse(button.classList.contains("disabled"))
            }
        }
    }

    @Test
    fun aPlainParticipantOfAnEventRoom_getsNoRecordingControlAtAll() {
        withMountedRoot("unavailable-bar-participant") { root, element ->
            assertNull(root.conferenceModerationGroup(canModerate = false, onRecord = {}, onStream = {}))
            assertNull(element().querySelector("button[aria-label='Aufzeichnung']"))
        }
    }
}

package network.lapis.cloud.client

import io.kvision.html.span
import io.kvision.panel.Root
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.shared.domain.ConferenceNotesStateDto
import network.lapis.cloud.shared.domain.NoteBlockDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared-notes textarea of the video conference in a REAL, mounted `Root` (the "late hooks" audit).
 *
 * [ConferenceNotesController] tracks which row's textarea has focus (native focus/blur listeners) so that
 * `syncRow` never overwrites a block somebody is typing in. The listeners are installed by an insert hook.
 * That hook used to be registered on the textarea AFTER `container.textArea(...)` had rendered it into the
 * visible list. The audit's first reading was "the textarea is replaced mid-typing by a foreign patch and
 * the listeners arrive too late". That does NOT reproduce: `createRow` adds six more widgets to the row
 * right after the hook, and each `add` patches the mounted root synchronously, so the one-off replacement
 * (and the hook's first fire) happens inside `createRow`, before the participant can interact. The
 * user-visible tests below therefore pass with the old order as well and pin the behaviour; what the fix
 * changes -- and what [creatingARow_doesNotReplaceItsTextarea] fails on before it -- is that the textarea is
 * now rendered exactly once instead of being built, thrown away and rebuilt while the row is assembled.
 *
 * The panel is visible here on purpose: a hidden notes panel has no elements at all (the production case
 * for the initial state), which hides the difference.
 */
class ConferenceNotesFocusDomTest {
    private fun newSession() =
        LiveKitRoomSession(
            onRemoteTrack = { _, _, _, _ -> },
            onRemoteTrackGone = { _, _, _ -> },
            onParticipantJoined = { _, _ -> },
            onParticipantLeft = { _ -> },
            onLocalVideoTrack = { _ -> },
            onLocalTrackMuteChanged = { _, _ -> },
            onRecordingStatusChanged = { _ -> },
            onActiveSpeakersChanged = { _ -> },
            onActiveDeviceChanged = { _, _ -> },
            onMediaDevicesChanged = {},
            onMediaDevicesError = { _, _ -> },
            onChat = { _ -> },
            onWhiteboardPreview = { _, _, _ -> },
            onWhiteboardCommit = { _, _, _ -> },
            onNotesCommit = { _, _, _ -> },
            onReconnecting = {},
            onReconnected = {},
            onDisconnected = {},
        )

    private fun block(
        content: String,
        version: Int,
    ) = NoteBlockDto(
        id = "block-1",
        content = content,
        position = 0,
        version = version,
        lastEditedByMemberId = "member-a",
        lastEditedByDisplayName = "Ada",
        lastEditedAtEpochMs = 0L,
    )

    private fun blockTextarea(element: () -> HTMLElement): HTMLTextAreaElement =
        assertNotNull(element().querySelector(".border-bottom textarea") as? HTMLTextAreaElement, "the block's textarea is rendered")

    private fun newController(panel: SimplePanel) =
        ConferenceNotesController(panel, "room-1", canModerate = false, localMemberId = "member-b", session = newSession())

    private fun withNotes(body: (Root, ConferenceNotesController, () -> HTMLTextAreaElement) -> Unit) =
        withMountedRoot("conference-notes-focus-test") { root, element ->
            val panel = SimplePanel()
            root.add(panel) // visible and mounted -- see the class KDoc
            val controller = newController(panel)
            controller.applyState(ConferenceNotesStateDto(listOf(block("original", 1))))
            body(root, controller) { blockTextarea(element) }
        }

    @Test
    fun textarea_survivesAForeignPatch_andKeepsFocus() {
        withNotes { root, _, textarea ->
            val before = textarea()
            before.focus()
            root.span("a chat message arrives")
            root.span("the roster changes")
            val after = textarea()
            assertSame(before, after, "a foreign patch must not replace the textarea the participant is typing in")
            assertTrue(after.isConnected)
            assertSame(after, document.activeElement, "focus stays")
        }
    }

    @Test
    fun focusTracking_isInstalledBeforeAnyForeignPatch() {
        withNotes { _, controller, textarea ->
            val field = textarea()
            field.dispatchEvent(Event("focus")) // the participant clicks into the block
            controller.applyState(ConferenceNotesStateDto(listOf(block("remote edit", 2)))) // a remote edit arrives
            assertEquals("original", textarea().value, "the focused block is frozen against the remote edit")

            textarea().dispatchEvent(Event("blur"))
            controller.applyState(ConferenceNotesStateDto(listOf(block("remote edit", 2))))
            assertEquals("remote edit", textarea().value, "once the focus is gone the block follows the remote state again")
        }
    }

    @Test
    fun rowsCreatedLater_areCoveredToo() {
        // A block that appears while the panel is open (remote participant adds one): same code path as the first.
        withMountedRoot("conference-notes-focus-later-test") { root, element ->
            val panel = SimplePanel()
            root.add(panel)
            val controller =
                ConferenceNotesController(panel, "room-1", canModerate = false, localMemberId = "member-b", session = newSession())
            controller.applyState(ConferenceNotesStateDto(emptyList()))
            controller.applyState(ConferenceNotesStateDto(listOf(block("late block", 1))))
            val field = blockTextarea(element)
            field.dispatchEvent(Event("focus"))
            controller.applyState(ConferenceNotesStateDto(listOf(block("remote edit", 2))))
            assertEquals("late block", blockTextarea(element).value)
        }
    }

    @Test
    fun creatingARow_doesNotReplaceItsTextarea() {
        // The distinguishing test of the audit finding: with the hook registered AFTER `container.textArea(...)`
        // the textarea was thrown away and rebuilt by the patch of the very next widget of the row
        // (`captionDiv`), i.e. inside `createRow` itself. Registered before it is added, the first element is
        // the only one. A synchronous MutationObserver.takeRecords() sees every removal since `observe`.
        withMountedRoot("conference-notes-focus-replace-test") { root, element ->
            val panel = SimplePanel()
            root.add(panel)
            val controller =
                ConferenceNotesController(panel, "room-1", canModerate = false, localMemberId = "member-b", session = newSession())
            val observer = MutationObserver { _, _ -> }
            observer.observe(element(), MutationObserverInit(childList = true, subtree = true))
            controller.applyState(ConferenceNotesStateDto(listOf(block("original", 1))))
            // Only the block's own textarea (rows = 3); the "add block" form below the list has its own (rows = 2)
            // and legitimately shifts while the list grows.
            val removedBlockTextareas =
                observer
                    .takeRecords()
                    .flatMap { record -> record.removedNodes.asList() }
                    .count { node ->
                        (node as? HTMLElement)?.let {
                            it.matches("textarea[rows=\"3\"]") ||
                                it.querySelector("textarea[rows=\"3\"]") != null
                        } ==
                            true
                    }
            observer.disconnect()
            assertEquals(0, removedBlockTextareas, "the block textarea is rendered exactly once")
        }
    }
}

package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.ConferenceRecordingLayout
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.294 -- the layout choice of the recording start dialog in a real mounted root: the default follows the room view, the person select
 * belongs to "Einzelne Person" only, a missing person keeps the dialog open and sends nothing, and without people that layout is not offered.
 */
class ConferenceRecordingStartDialogLayoutDomTest {
    private fun resolved(text: String?) = (text ?: "").removePrefix("###KvI18nS###")

    private fun closeDialogs() {
        document.querySelectorAll(".modal").let { list -> for (i in 0 until list.length) (list.item(i) as? HTMLElement)?.remove() }
        document.querySelectorAll(".modal-backdrop").let { list -> for (i in 0 until list.length) (list.item(i) as? HTMLElement)?.remove() }
    }

    private fun layoutSelect(): HTMLSelectElement {
        val selects = document.querySelectorAll(".modal select")
        for (i in 0 until selects.length) {
            val select = selects.item(i) as HTMLSelectElement
            val values =
                (0 until select.options.length).map {
                    select.options
                        .item(it)
                        ?.getAttribute("value")
                        .orEmpty()
                }
            if ("GRID" in values) return select
        }
        error("no layout select")
    }

    private fun layoutValues(): List<String> {
        val select = layoutSelect()
        return (0 until select.options.length).map {
            select.options
                .item(it)
                ?.getAttribute("value")
                .orEmpty()
        }
    }

    private fun modalText(): String = resolved(document.querySelector(".modal")?.textContent)

    private fun confirmButton(): HTMLElement {
        val buttons = document.querySelectorAll(".modal button")
        return (0 until buttons.length)
            .map { buttons.item(it) as HTMLElement }
            .first { resolved(it.textContent).contains("Aufzeichnung jetzt starten") }
    }

    private val hint = "Die Aufzeichnung zeigt nur diese Person und geteilte Bildschirme."

    @Test
    fun theDialog_defaultsToTheRoomView_offersAllThreeLayouts_andHidesThePersonForTheGrid() {
        withMountedRoot("recording-layout-default") { _, _ ->
            startRecordingConfirmDialog(
                participantOptions = listOf("p1" to "Anna", "p2" to "Ben"),
                defaultLayout = ConferenceRecordingLayout.SPEAKER,
                selfIdentity = "p1",
            ) { _, _, _ -> }
            try {
                assertEquals(listOf("GRID", "SPEAKER", "SINGLE_PARTICIPANT"), layoutValues())
                assertEquals("SPEAKER", layoutSelect().value)
                assertFalse(modalText().contains(hint), "the hint belongs to the single image only")
            } finally {
                closeDialogs()
            }
        }
    }

    @Test
    fun withSingleParticipantAsDefault_thePersonAndTheHintAreShown_andTheOwnIdentityIsPreselected() {
        withMountedRoot("recording-layout-single") { _, _ ->
            var confirmed: Triple<DocumentAccessLevel, ConferenceRecordingLayout, String?>? = null
            startRecordingConfirmDialog(
                participantOptions = listOf("p1" to "Anna", "p2" to "Ben"),
                defaultLayout = ConferenceRecordingLayout.SINGLE_PARTICIPANT,
                selfIdentity = "p2",
            ) { level, layout, person -> confirmed = Triple(level, layout, person) }
            try {
                assertTrue(modalText().contains(hint))
                confirmButton().click()
                val result = assertNotNull(confirmed, "the confirm went through")
                assertEquals(ConferenceRecordingLayout.SINGLE_PARTICIPANT, result.second)
                assertEquals("p2", result.third)
            } finally {
                closeDialogs()
            }
        }
    }

    @Test
    fun withoutAnyPerson_theSingleImageIsNotOffered_andTheDefaultFallsBackToTheGrid() {
        withMountedRoot("recording-layout-nobody") { _, _ ->
            startRecordingConfirmDialog(defaultLayout = ConferenceRecordingLayout.SINGLE_PARTICIPANT) { _, _, _ -> }
            try {
                assertEquals(listOf("GRID", "SPEAKER"), layoutValues())
                assertEquals("GRID", layoutSelect().value)
            } finally {
                closeDialogs()
            }
        }
    }

    @Test
    fun theGrid_isConfirmedWithoutAPerson() {
        withMountedRoot("recording-layout-grid") { _, _ ->
            var confirmed: Pair<ConferenceRecordingLayout, String?>? = null
            startRecordingConfirmDialog(
                participantOptions = listOf("p1" to "Anna"),
                defaultLayout = ConferenceRecordingLayout.GRID,
                selfIdentity = "p1",
            ) { _, layout, person -> confirmed = layout to person }
            try {
                confirmButton().click()
                assertEquals(ConferenceRecordingLayout.GRID to null, confirmed)
            } finally {
                closeDialogs()
            }
        }
    }

    @Test
    fun singleImageWithoutAPerson_keepsTheDialogOpen_showsTheError_andSendsNothing() {
        withMountedRoot("recording-layout-noperson") { _, _ ->
            var confirmed = false
            startRecordingConfirmDialog(
                participantOptions = listOf("p1" to "Anna"),
                defaultLayout = ConferenceRecordingLayout.SINGLE_PARTICIPANT,
                selfIdentity = null,
            ) { _, _, _ -> confirmed = true }
            try {
                confirmButton().click()
                assertFalse(confirmed, "nothing is sent without a person")
                assertTrue(modalText().contains("Bitte eine Person für \"Einzelne Person\" auswählen."))
                assertNotNull(document.querySelector(".modal"), "the dialog stays open")
            } finally {
                closeDialogs()
            }
        }
    }

    @Test
    fun theBannerAndTheLayoutLabel_nameTheChosenLayout() {
        assertTrue(
            resolved(conferenceRecordingBannerText(ConferenceRecordingLayout.SINGLE_PARTICIPANT)).endsWith("Ansicht: Einzelne Person."),
        )
        assertTrue(resolved(conferenceRecordingBannerText(ConferenceRecordingLayout.GRID)).endsWith("Ansicht: Raster."))
        assertEquals(CONFERENCE_RECORDING_BANNER_TEXT, conferenceRecordingBannerText(null))
    }
}

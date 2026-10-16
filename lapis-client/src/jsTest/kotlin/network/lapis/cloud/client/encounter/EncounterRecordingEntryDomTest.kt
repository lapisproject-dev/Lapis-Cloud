package network.lapis.cloud.client.encounter

import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterProfile
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.143 -- the entry panel of a room whose pulpit MAY be recorded: it no longer promises "no recording", says that the pulpit (not the congregation) can be
 * recorded and shows the operator's own notice as text. A room without the option keeps the old sentence.
 */
class EncounterRecordingEntryDomTest {
    private fun info(
        notice: String?,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
    ) = EncounterEntryInfoDto(
        space = testSpace(profile = profile).copy(pulpitRecordingAllowed = notice != null),
        consentRequired = false,
        disclaimer = null,
        pulpitRecordingRoomNotice = notice,
    )

    @Test
    fun aRoomWithoutTheOption_keepsTheOldPromise(): Promise<Unit> =
        formTest {
            mountedForm("encounter-entry-no-recording") { root, element ->
                root.encounterEntryPanel(info(notice = null)) { }
                awaitUntil("the panel") { element().textContent.orEmpty().contains("Bevor Sie eintreten") }
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Lapis Cloud speichert keine Anwesenheitsliste und keine Aufzeichnung dieses Raums."))
                assertEquals(3, element().allOf(".lapis-encounter-notes li").size)
            }
        }

    @Test
    fun aRoomWithTheOption_saysThePulpitMayBeRecorded_andShowsTheOperatorsNoticeAsText(): Promise<Unit> =
        formTest {
            mountedForm("encounter-entry-recording") { root, element ->
                root.encounterEntryPanel(info(notice = "<b>Die Kanzel</b> wird aufgezeichnet, die Gemeinde nicht.")) { }
                awaitUntil("the panel") { element().textContent.orEmpty().contains("Bevor Sie eintreten") }
                val text = element().textContent.orEmpty()
                assertFalse(text.contains("und keine Aufzeichnung dieses Raums"), "the promise of no recording is gone")
                assertTrue(text.contains("In diesem Raum kann die Kanzel aufgezeichnet werden, die Gemeinde nicht."))
                assertTrue(text.contains("<b>Die Kanzel</b> wird aufgezeichnet, die Gemeinde nicht."), "untrusted text stays text")
                assertEquals(0, element().allOf("b").size, "no markup from the notice")
                assertEquals(3, element().allOf(".lapis-encounter-notes li").size)
            }
        }

    @Test
    fun anAssemblyRoom_saysPodiumAndParticipants(): Promise<Unit> =
        formTest {
            mountedForm("encounter-entry-recording-assembly") { root, element ->
                root.encounterEntryPanel(info(notice = "Das Podium wird aufgezeichnet.", profile = EncounterProfile.ASSEMBLY)) { }
                awaitUntil("the panel") { element().textContent.orEmpty().contains("Bevor Sie eintreten") }
                assertTrue(
                    element().textContent.orEmpty().contains(
                        "In diesem Raum kann das Podium aufgezeichnet werden, die Teilnehmenden nicht.",
                    ),
                )
            }
        }
}

package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** V1.9.143 -- the texts of the pulpit recording: complete, one per profile where the word differs, and the assembly texts free of the church words. */
class EncounterRecordingVocabularyTest {
    private val churchWords = listOf("Kanzel", "Gemeinde", "Gottesdienst", "Ordner")

    private fun texts(terms: EncounterTerms): Map<String, String> =
        mapOf(
            "banner" to terms.recordingBanner(),
            "bannerShort" to terms.recordingBannerShort(),
            "started" to terms.recordingStartedAnnouncement(),
            "ended" to terms.recordingEndedAnnouncement(),
            "start" to terms.recordingStartLabel(),
            "stop" to terms.recordingStopLabel(),
            "awaiting" to terms.recordingAwaitingLabel(),
            "option" to terms.recordingRoomOptionLabel(),
            "entryNote" to terms.recordingEntryNoteContent(),
            "consentTitle" to terms.recordingConsentTitle(),
            "accept" to terms.recordingConsentAccept(),
            "decline" to terms.recordingConsentDecline(),
            "max" to terms.recordingMaxReached(),
            "asked" to terms.recordingAskedNote(),
            "nobody" to terms.recordingNobodyThereNote(),
            "floor" to terms.recordingFloorNote(),
            "unavailable" to terms.recordingUnavailableNote(),
            "failed" to terms.recordingFailedNote(),
        )

    @Test
    fun everyTextExistsInBothProfiles() {
        listOf(EncounterProfile.CHURCH_SERVICE, EncounterProfile.ASSEMBLY).forEach { profile ->
            texts(termsFor(profile)).forEach { (name, text) -> assertTrue(text.isNotBlank(), "$profile.$name") }
        }
    }

    @Test
    fun theAssemblyTextsUseNoChurchWord() {
        texts(termsFor(EncounterProfile.ASSEMBLY)).forEach { (name, text) ->
            churchWords.forEach { word -> assertFalse(text.contains(word), "assembly.$name mentions '$word': $text") }
        }
    }

    @Test
    fun theTextsThatNameThePulpitDifferPerProfile() {
        val church = texts(termsFor(EncounterProfile.CHURCH_SERVICE))
        val assembly = texts(termsFor(EncounterProfile.ASSEMBLY))
        listOf("banner", "awaiting", "option", "entryNote", "asked", "nobody", "floor").forEach { name ->
            assertNotEquals(church.getValue(name), assembly.getValue(name), name)
        }
        listOf(
            "bannerShort",
            "started",
            "ended",
            "start",
            "stop",
            "consentTitle",
            "accept",
            "decline",
            "max",
            "unavailable",
            "failed",
        ).forEach { name ->
            assertEquals(church.getValue(name), assembly.getValue(name), name)
        }
    }

    @Test
    fun theBannerSaysWhatIsRecordedAndWhatIsNot() {
        assertEquals("Aufzeichnung läuft · Nur die Kanzel wird aufgezeichnet", termsFor(EncounterProfile.CHURCH_SERVICE).recordingBanner())
        assertEquals("Aufzeichnung läuft · Nur das Podium wird aufgezeichnet", termsFor(EncounterProfile.ASSEMBLY).recordingBanner())
        assertEquals("Aufnahme", termsFor(EncounterProfile.CHURCH_SERVICE).recordingBannerShort())
    }
}

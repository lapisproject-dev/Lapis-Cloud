package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** V1.9.67 -- the vocabulary of the two room profiles: complete, distinct, and free of the other profile's words. */
class EncounterVocabularyTest {
    private val churchWords = listOf("Kanzel", "Ordner", "Gemeinde", "Gottesdienst", "Amen")

    /** Every text of [terms] the default reactions of a room can show (the AMEN sentence only exists for a room that allows AMEN). */
    private fun texts(terms: EncounterTerms): Map<String, String> =
        buildMap {
            put("profileName", terms.profileName())
            put("profileDescription", terms.profileDescription())
            EncounterPresenceRole.entries.forEach { put("presence ${it.name}", terms.presenceRoleLabel(it)) }
            EncounterSpaceRole.entries.forEach { put("space ${it.name}", terms.spaceRoleLabel(it)) }
            put("enterAsSpeaker", terms.enterAsSpeakerLabel())
            put("enterAsSteward", terms.enterAsStewardLabel())
            put("emptyStage", terms.emptyStage())
            put("emptyStageContent", terms.emptyStageContent())
            put("stageNamed", terms.stageNamed("X"))
            put("silencedNote", terms.silencedNoteContent())
            put("eventEnded", terms.eventEndedContent())
            put("removalWarning", terms.removalWarningContent())
            put("transmissionNote", terms.transmissionNoteContent())
            put("article9Note", terms.article9NoteContent())
            put("liveBadge", terms.liveBadgeContent())
            put("streamOnlyStage", terms.streamOnlyStageContent())
            put("nobodyOnStage", terms.nobodyOnStageContent())
            put("audienceName", terms.audienceName())
            put("stewardsName", terms.stewardsName())
            put("applause", terms.reactionFromAudience(EncounterReactionOption.APPLAUSE))
            put("heart", terms.reactionFromAudience(EncounterReactionOption.HEART))
        }

    @Test
    fun everyTextOfEveryProfile_isFilledIn() {
        EncounterProfile.entries.forEach { profile ->
            texts(termsFor(profile)).forEach { (name, text) -> assertTrue(text.isNotBlank(), "${profile.name}: $name is blank") }
            EncounterReactionOption.entries.filter { it != EncounterReactionOption.HAND }.forEach {
                assertTrue(termsFor(profile).reactionFromAudience(it).isNotBlank(), "${profile.name}: ${it.name}")
            }
        }
    }

    @Test
    fun anAssembly_neverSpeaksOfPulpitStewardsCongregationOrServices() {
        texts(termsFor(EncounterProfile.ASSEMBLY)).forEach { (name, text) ->
            churchWords.forEach { word -> assertTrue(!text.contains(word), "ASSEMBLY $name contains \"$word\": $text") }
        }
    }

    @Test
    fun theTwoProfiles_useDifferentWordsForTheSameThing() {
        val church = texts(termsFor(EncounterProfile.CHURCH_SERVICE))
        val assembly = texts(termsFor(EncounterProfile.ASSEMBLY))
        church.keys.forEach { key ->
            if (key !in setOf("emptyStageContent")) assertNotEquals(church.getValue(key), assembly.getValue(key), key)
        }
    }

    @Test
    fun theChurchWords_areTheOnesTheCatalogsAlreadyKnow() {
        val terms = termsFor(EncounterProfile.CHURCH_SERVICE)
        assertEquals("Kanzel", terms.presenceRoleLabel(EncounterPresenceRole.PULPIT))
        assertEquals("Ordner im Gottesdienst", terms.presenceRoleLabel(EncounterPresenceRole.STEWARD))
        assertEquals("Gemeinde", terms.presenceRoleLabel(EncounterPresenceRole.CONGREGATION))
        assertEquals("Kanzel (spricht)", terms.spaceRoleLabel(EncounterSpaceRole.PULPIT))
        assertEquals("Amen aus der Gemeinde", terms.reactionFromAudience(EncounterReactionOption.AMEN))
    }

    @Test
    fun theAssemblyWords() {
        val terms = termsFor(EncounterProfile.ASSEMBLY)
        assertEquals("Podium", terms.presenceRoleLabel(EncounterPresenceRole.PULPIT))
        assertEquals("Moderation", terms.presenceRoleLabel(EncounterPresenceRole.STEWARD))
        assertEquals("Teilnehmende", terms.presenceRoleLabel(EncounterPresenceRole.CONGREGATION))
        assertEquals("Podium: Pia", terms.stageNamed("Pia"))
    }

    @Test
    fun theScenesOfTheProfiles_areDistinctFilesOfTheirOwnFolders() {
        val church = termsFor(EncounterProfile.CHURCH_SERVICE)
        val assembly = termsFor(EncounterProfile.ASSEMBLY)
        assertEquals("/assets/encounter-themes/church/front.svg", church.sceneFrontPath)
        assertEquals("/assets/encounter-themes/church/row.svg", church.sceneRowPath)
        assertEquals("/assets/encounter-themes/hall/front.svg", assembly.sceneFrontPath)
        assertEquals("/assets/encounter-themes/hall/row.svg", assembly.sceneRowPath)
    }

    @Test
    fun everyReaction_hasALabelAndAGlyph() {
        EncounterReactionOption.entries.forEach {
            assertTrue(reactionLabel(it).isNotBlank(), it.name)
            assertTrue(reactionGlyph(it).startsWith("fas fa-"), it.name)
        }
        assertEquals(
            EncounterReactionOption.entries.size,
            EncounterReactionOption.entries
                .map { reactionGlyph(it) }
                .toSet()
                .size,
            "one picture each",
        )
    }
}

package network.lapis.cloud.client.encounter

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceRole

/**
 * V1.9.67 Begegnungsraum Stufe 1 -- the ONE place that knows which words and which picture belong to which [EncounterProfile]. Everything
 * else in `client/encounter/` asks [EncounterTerms] (`termsFor(space.profile)`) and never branches on the profile itself, never writes
 * "Kanzel", "Ordner", "Gemeinde", "Amen" or "Gottesdienst" as a literal: `ClientEncounterVocabularyTripwireTest` fails the build if it does.
 *
 * All texts are FIXED literals inside `tr("...")`/`gettext("...")` calls (never composed from data), so the catalog extraction finds
 * them and there is no path for free text. Two flavours of the same sentence exist where a call site needs both: a `...Content` text
 * (`tr`, for widget content, resolved by KVision when it renders) and a plain one (`gettext`, for attributes and option lists, where the
 * KVision marker must not appear).
 *
 * The profile describes the ROOM, never a person.
 */
internal class EncounterTerms(
    val profile: EncounterProfile,
) {
    private val church: Boolean get() = profile == EncounterProfile.CHURCH_SERVICE

    /** Floor-plan scene of this profile: the front (altar or podium, once at the top) and the repeating row of benches or chairs. */
    val sceneFrontPath: String
        get() = if (church) "$SCENE_DIR/church/front.svg" else "$SCENE_DIR/hall/front.svg"
    val sceneRowPath: String
        get() = if (church) "$SCENE_DIR/church/row.svg" else "$SCENE_DIR/hall/row.svg"

    fun profileName(): String = if (church) gettext("Gottesdienst") else gettext("Versammlung")

    fun profileDescription(): String =
        if (church) {
            gettext("Eine Kanzel spricht, die Gemeinde hört zu. Reaktionen: Hand und Amen.")
        } else {
            gettext("Podium und Moderation sprechen, die Teilnehmenden hören zu. Reaktionen: Hand und Applaus.")
        }

    /** Label of the person's role inside a running session. The steward is NOT the bare msgid "Ordner" (it already means "folder"). */
    fun presenceRoleLabel(role: EncounterPresenceRole): String =
        when (role) {
            EncounterPresenceRole.PULPIT -> if (church) gettext("Kanzel") else gettext("Podium")
            EncounterPresenceRole.STEWARD -> if (church) gettext("Ordner im Gottesdienst") else gettext("Moderation")
            EncounterPresenceRole.CONGREGATION -> if (church) gettext("Gemeinde") else gettext("Teilnehmende")
        }

    /** Label of an OFFICE of the room in the role editor. */
    fun spaceRoleLabel(role: EncounterSpaceRole): String =
        when (role) {
            EncounterSpaceRole.PULPIT -> if (church) gettext("Kanzel (spricht)") else gettext("Podium (spricht)")
            EncounterSpaceRole.STEWARD ->
                if (church) {
                    gettext(
                        "Ordner (spricht und moderiert)",
                    )
                } else {
                    gettext("Moderation (spricht und moderiert)")
                }
        }

    fun enterAsSpeakerLabel(): String =
        if (church) gettext("Eintreten und Kanzel übernehmen") else gettext("Eintreten und Podium übernehmen")

    fun enterAsStewardLabel(): String = if (church) gettext("Eintreten als Ordner") else gettext("Eintreten als Moderation")

    fun emptyStageContent(): String = if (church) tr("Die Kanzel ist noch leer.") else tr("Das Podium ist noch leer.")

    fun emptyStage(): String = if (church) gettext("Die Kanzel ist noch leer.") else gettext("Das Podium ist noch leer.")

    /** Accessible name of the stage region with the (untrusted, already sanitised) [names]. */
    fun stageNamed(names: String): String = if (church) gettext("Kanzel: %1", names) else gettext("Podium: %1", names)

    /** Fact line of a room in the list: who speaks. [names] is untrusted and goes through an untrusted helper by the caller. */
    fun speakersFact(names: String): String = stageNamed(names)

    fun silencedNoteContent(): String =
        if (church) tr("Sie wurden von einem Ordner stummgeschaltet.") else tr("Sie wurden von der Moderation stummgeschaltet.")

    fun eventEndedContent(): String = if (church) tr("Der Gottesdienst ist beendet.") else tr("Die Versammlung ist beendet.")

    fun removalWarningContent(): String =
        if (church) {
            tr("Die Person verlässt den Raum und kann bis zum Ende dieses Gottesdienstes nicht wieder eintreten.")
        } else {
            tr("Die Person verlässt den Raum und kann bis zum Ende dieser Versammlung nicht wieder eintreten.")
        }

    fun transmissionNoteContent(): String =
        if (church) {
            tr(
                "Wird die Kanzel übertragen, sehen Zuschauer außerhalb des Raums nur Bild und Ton der Kanzel. " +
                    "Sie selbst werden nicht übertragen.",
            )
        } else {
            tr(
                "Wird das Podium übertragen, sehen Zuschauer außerhalb des Raums nur Bild und Ton des Podiums. " +
                    "Sie selbst werden nicht übertragen.",
            )
        }

    fun article9NoteContent(): String =
        if (church) {
            tr(
                "Die Teilnahme an einem Gottesdienst kann Rückschlüsse auf religiöse Überzeugungen zulassen (besondere Kategorie personenbezogener Daten, Art. 9 DSGVO). Dieser Hinweis ist keine Rechtsberatung.",
            )
        } else {
            tr(
                "Die Teilnahme an einer Versammlung kann Rückschlüsse auf politische Meinungen oder weltanschauliche Überzeugungen zulassen (besondere Kategorie personenbezogener Daten, Art. 9 DSGVO). Dieser Hinweis ist keine Rechtsberatung.",
            )
        }

    fun liveBadgeContent(): String = if (church) tr("Live (nur Kanzel)") else tr("Live (nur Podium)")

    fun streamOnlyStageContent(): String = if (church) tr("Nur Kanzel") else tr("Nur Podium")

    fun nobodyOnStageContent(): String = if (church) tr("Niemand ist auf der Kanzel.") else tr("Niemand ist auf dem Podium.")

    /** The polite live-region sentence for an event reaction of the audience: a fixed sentence, never a count. */
    fun reactionFromAudience(option: EncounterReactionOption): String =
        when (option) {
            EncounterReactionOption.HAND -> ""
            EncounterReactionOption.AMEN -> if (church) gettext("Amen aus der Gemeinde") else gettext("Amen von den Teilnehmenden")
            EncounterReactionOption.APPLAUSE ->
                if (church) {
                    gettext(
                        "Applaus aus der Gemeinde",
                    )
                } else {
                    gettext("Applaus von den Teilnehmenden")
                }
            EncounterReactionOption.HEART -> if (church) gettext("Herz aus der Gemeinde") else gettext("Herz von den Teilnehmenden")
        }

    /** Accessible name of the list of the seats. */
    fun audienceName(): String = presenceRoleLabel(EncounterPresenceRole.CONGREGATION)

    /** Accessible name of the strip of the stewards. */
    fun stewardsName(): String = presenceRoleLabel(EncounterPresenceRole.STEWARD)

    private companion object {
        const val SCENE_DIR = "/assets/encounter-themes"
    }
}

internal fun termsFor(profile: EncounterProfile): EncounterTerms = EncounterTerms(profile)

/** Visible label of a configurable reaction (plain text, also the accessible name of its button). */
internal fun reactionLabel(option: EncounterReactionOption): String =
    when (option) {
        EncounterReactionOption.HAND -> gettext("Hand heben")
        EncounterReactionOption.AMEN -> gettext("Amen")
        EncounterReactionOption.APPLAUSE -> gettext("Applaus")
        EncounterReactionOption.HEART -> gettext("Herz")
    }

/** The same label as widget content (`tr`). */
internal fun reactionLabelContent(option: EncounterReactionOption): String =
    when (option) {
        EncounterReactionOption.HAND -> tr("Hand heben")
        EncounterReactionOption.AMEN -> tr("Amen")
        EncounterReactionOption.APPLAUSE -> tr("Applaus")
        EncounterReactionOption.HEART -> tr("Herz")
    }

/** Icon class (decorative, `aria-hidden`) of a reaction at a seat and on its button. */
internal fun reactionGlyph(option: EncounterReactionOption): String =
    when (option) {
        EncounterReactionOption.HAND -> ActionIcon.HAND.css
        EncounterReactionOption.AMEN -> ActionIcon.AMEN.css
        EncounterReactionOption.APPLAUSE -> ActionIcon.APPLAUSE.css
        EncounterReactionOption.HEART -> ActionIcon.HEART.css
    }

/** The [ActionIcon] of a reaction button. */
internal fun reactionActionIcon(option: EncounterReactionOption): ActionIcon =
    when (option) {
        EncounterReactionOption.HAND -> ActionIcon.HAND
        EncounterReactionOption.AMEN -> ActionIcon.AMEN
        EncounterReactionOption.APPLAUSE -> ActionIcon.APPLAUSE
        EncounterReactionOption.HEART -> ActionIcon.HEART
    }

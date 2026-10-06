package network.lapis.cloud.client.encounter

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceRole

/**
 * V1.9.62 Begegnungsraum (B2) -- the three phases of the room view, DOM-free so they can be tested without a browser.
 *
 * - [Closed]: the room is not open; the view shows the closing notice and (for a moderator) the "open the doors" button.
 * - [Entry]: the room is open and the person is not in yet; the view shows the entry panel (three plain sentences, the Art. 9 hint and
 *   the consent for a non-member).
 * - [Inside]: the person entered; the view holds the LiveKit session and the scene.
 */
internal sealed interface EncounterViewPhase {
    data class Closed(
        val space: EncounterSpaceDto,
    ) : EncounterViewPhase

    data class Entry(
        val info: EncounterEntryInfoDto,
    ) : EncounterViewPhase

    data class Inside(
        val space: EncounterSpaceDto,
        val entry: EncounterEntryDto,
    ) : EncounterViewPhase
}

/** The label of the one entry button: an office holder is told which office they enter with. */
internal fun encounterEnterLabel(space: EncounterSpaceDto): String =
    when (space.myRole) {
        EncounterSpaceRole.PULPIT -> termsFor(space.profile).enterAsSpeakerLabel()
        EncounterSpaceRole.STEWARD -> termsFor(space.profile).enterAsStewardLabel()
        null -> gettext("Eintreten")
    }

/**
 * What the person inside may do. [presenceRole]/[canPublish]/[canPublishData] come from the entry the server gave; [isPrivileged] is the
 * global BOARD/ADMIN role (which may moderate without an office but never publishes); [selfIdentity] is the LiveKit identity (the member id).
 */
internal data class EncounterViewerRights(
    val presenceRole: EncounterPresenceRole,
    val canPublish: Boolean,
    val canPublishData: Boolean,
    val selfIdentity: String,
    val isPrivileged: Boolean,
) {
    /** Office holders and BOARD/ADMIN moderate (the server's `requireSpaceModerator`). */
    val canModerate: Boolean get() = presenceRole != EncounterPresenceRole.CONGREGATION || isPrivileged
}

/**
 * Whether the moderation menu (silence/remove) is offered for [target]. Mirrors the server: never against oneself; an office holder acts
 * against the congregation only (the server refuses another office holder and BOARD/ADMIN -- the client cannot see the global role of a
 * congregation member, so an office holder may see the menu for a BOARD member and the server then refuses with a plain message).
 */
internal fun encounterCanActOn(
    viewer: EncounterViewerRights,
    target: EncounterPresentDto,
): Boolean {
    if (target.memberId == viewer.selfIdentity) return false
    if (!viewer.canModerate) return false
    if (!viewer.isPrivileged && target.role != EncounterPresenceRole.CONGREGATION) return false
    return true
}

/**
 * The role label of a present person, in the vocabulary of the room's [profile] ([EncounterTerms.presenceRoleLabel]). The steward is NOT
 * the bare msgid "Ordner": that one already means "folder" (documents) in every catalog, so it gets its own, unambiguous sentence.
 */
internal fun encounterPresenceRoleLabel(
    role: EncounterPresenceRole,
    profile: EncounterProfile,
): String = termsFor(profile).presenceRoleLabel(role)

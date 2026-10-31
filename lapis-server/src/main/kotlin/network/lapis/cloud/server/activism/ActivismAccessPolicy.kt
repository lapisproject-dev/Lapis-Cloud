package network.lapis.cloud.server.activism

import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismViewerRole
import network.lapis.cloud.shared.domain.MemberStatusSets

/**
 * V1.9.246 -- THE ONE rights resolver of the activism module (`ActivismTripwireTest` forbids a second one). Pure: the caller loads the facts.
 *
 * * A participant is a member whose status is in [MemberStatusSets.ACTIVISM_PARTICIPANT]; everybody else is refused before any action is read.
 * * MANAGER = role BOARD or ADMIN (TREASURER is NOT a manager, open question F8). RESPONSIBLE = one of the 1..10 persons of THIS action.
 * * A plain MEMBER sees an action from PLANNED on; IDEA, AWAITING_REVIEW and WITHDRAWN only RESPONSIBLE and MANAGER see. For everybody else
 *   [resolve] returns null and the caller answers with the SAME not-found as for an unknown id (no existence oracle).
 */
internal object ActivismAccessPolicy {
    fun isParticipant(viewer: CurrentMember): Boolean = viewer.status in MemberStatusSets.ACTIVISM_PARTICIPANT

    fun isManager(viewer: CurrentMember): Boolean = viewer.role == AccountRole.BOARD || viewer.role == AccountRole.ADMIN

    fun resolve(
        viewer: CurrentMember,
        status: ActivismStatus,
        responsible: Boolean,
    ): ActivismViewerRole? {
        if (!isParticipant(viewer)) return null
        val role =
            when {
                isManager(viewer) -> ActivismViewerRole.MANAGER
                responsible -> ActivismViewerRole.RESPONSIBLE
                else -> ActivismViewerRole.MEMBER
            }
        if (role == ActivismViewerRole.MEMBER && status !in PUBLIC_STATUSES) return null
        return role
    }

    private val PUBLIC_STATUSES = setOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING, ActivismStatus.COMPLETED)

    fun mayEdit(role: ActivismViewerRole): Boolean = role != ActivismViewerRole.MEMBER
}

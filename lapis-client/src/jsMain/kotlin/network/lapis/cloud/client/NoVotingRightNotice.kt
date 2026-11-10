package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.div
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SessionInfoDto

/**
 * V1.9.298 -- a member WITHOUT voting right (membership and voting right are independent) sees this notice IN PLACE of the vote, ballot or
 * rating button, never a disabled button. Purely informational: the server enforces the voting right on every ballot.
 */
internal fun SessionInfoDto?.lacksVotingRight(): Boolean = this != null && status in MemberStatusSets.ORGANIZATION_MEMBER && !votingRight

/** The sentence of the notice; also used where only a text slot exists (a participation note). */
internal fun noVotingRightText(): String =
    tr(
        "Sie sind Mitglied, haben aber derzeit kein Stimmrecht und können deshalb nicht abstimmen. Bei Fragen wenden Sie sich an den Vorstand.",
    )

/** An informational alert (`role="status"`) that takes the place of the ballot control. */
internal fun renderNoVotingRightNotice(parent: Container) {
    parent.div(noVotingRightText()) {
        addCssClasses("alert alert-info")
        setAttribute("role", "status")
    }
}

/** The notice when the signed-in session lacks the voting right (reads the session itself, so a caller file need not touch the app state). */
internal fun renderNoVotingRightNoticeIfLacking(parent: Container) {
    if (AppState.session.lacksVotingRight()) renderNoVotingRightNotice(parent)
}

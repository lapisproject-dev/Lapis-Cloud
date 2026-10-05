package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerActionDecisionDto
import network.lapis.cloud.shared.domain.PeerActionDecisionsDto
import network.lapis.cloud.shared.domain.PeerDecisionKind
import network.lapis.cloud.shared.domain.PeerDenyReason

/*
 * Welle V1.9.57 "Admin-Peer-Schutz" -- how the client shows what the peer protection decided.
 *
 * Two rules from the design review:
 *  - **No silent disappearance.** A control that is not available stays visible and disabled, and the REASON stands next to it as
 *    visible text (never only a tooltip: a tooltip does not exist on a touch screen and is invisible to many users). A table row has no
 *    room for a sentence, so there the button keeps its tooltip and opens the dialog, where the sentence is text.
 *  - **One source of truth.** The reason always comes from the server (`IPrivilegedActionService.getPeerActionDecisions`); the client
 *    contains no second copy of the matrix, only the sentences for the closed set of reasons.
 */

/** The reason bounds of a four-eyes request -- they mirror `PrivilegedActionService` (10..500 characters); the server stays the authority. */
internal const val PEER_REASON_MIN_LENGTH: Int = 10
internal const val PEER_REASON_MAX_LENGTH: Int = 500

/** The decision of [action] for the member the decisions were asked for, or `null` when they could not be loaded. */
internal fun PeerActionDecisionsDto?.of(action: PeerAction): PeerActionDecisionDto? = this?.decisions?.firstOrNull { it.action == action }

/** The visible reason of a refusal, fixed sentences per reason (the server's own text never arrives, see Kilua RPC). */
internal fun peerDenyText(
    reason: PeerDenyReason?,
    action: PeerAction,
): String =
    when (reason) {
        PeerDenyReason.NO_SECOND_ADMIN ->
            if (action == PeerAction.TEMP_PASSWORD) {
                tr(
                    "Nicht möglich: Es gibt keinen weiteren Administrator für die Freigabe. " +
                        "Nutzen Sie die Passwort-Reset-Mail oder wenden Sie sich an den Betreiber.",
                )
            } else {
                tr("Nicht möglich: Es gibt keinen weiteren Administrator für die Freigabe. Wenden Sie sich an den Betreiber.")
            }
        PeerDenyReason.MAIL_UNAVAILABLE ->
            tr(
                "Nicht möglich: Auf dieser Installation ist kein Mailversand eingerichtet, der betroffene Administrator könnte nicht gewarnt werden.",
            )
        PeerDenyReason.TARGET_IS_ADMIN ->
            when (action) {
                PeerAction.ERASE -> tr("Geschützt: Ein Administratorkonto wird erst nach der Herabstufung gelöscht.")
                PeerAction.LINK_IDENTITY -> tr("Geschützt: Die Keycloak-Verknüpfung eines anderen Administrators ist hier nicht möglich.")
                PeerAction.EMAIL_OVERRIDE ->
                    tr(
                        "Geschützt: Die Notfalländerung der Adresse eines anderen Administrators ist nicht möglich. Wenden Sie sich an den Betreiber.",
                    )
                else -> tr("Geschützt: Diese Aktion ist gegen ein anderes Administratorkonto nicht möglich.")
            }
        PeerDenyReason.PROTECTED_TARGET ->
            tr("Geschützt: Die Daten von Administratoren kann der Vorstand nicht ändern.")
        PeerDenyReason.SELF_TARGET, PeerDenyReason.NOT_PERMITTED, null -> tr("Keine Berechtigung für diese Aktion.")
    }

/** The visible hint of an action that is allowed only with a second administrator's approval. */
internal fun peerApprovalHint(): String =
    tr("Für ein anderes Administratorkonto ist die Zustimmung eines zweiten Administrators erforderlich.")

/** `true` when [decision] says "needs the approval of a second administrator". */
internal fun PeerActionDecisionDto?.needsApproval(): Boolean = this?.kind == PeerDecisionKind.REQUIRES_APPROVAL

/** `true` when [decision] refuses the action (with a reason the UI shows as text). */
internal fun PeerActionDecisionDto?.isDenied(): Boolean = this?.kind == PeerDecisionKind.DENY

/**
 * One line "shield + reason" below a disabled control. The icon is decoration (`aria-hidden`), the text is the content -- a real
 * element in the document, so it is read and selectable. [text] must be a static `tr(...)` text.
 */
internal fun Container.peerProtectionNotice(text: String): Div =
    div {
        addCssClasses("text-muted small d-flex align-items-start gap-2 mt-1")
        setAttribute("data-peer-protection-notice", "true")
        icon("${ActionIcon.PROTECTED.css} fa-fw lapis-action-icon") { setAttribute("aria-hidden", "true") }
        span(text)
    }

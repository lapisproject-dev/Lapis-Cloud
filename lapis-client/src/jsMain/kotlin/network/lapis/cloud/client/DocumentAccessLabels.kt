package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.DocumentAccessLevel

/**
 * Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar" -- the
 * [DocumentAccessLevel] German label/badge-color table, following the established convention
 * ([MemberStatusLabels.kt], [ComplianceLabels.kt]): a `when` returning [gettext] plus a
 * `...Color()` returning a Bootstrap variant, consumed via `Container.typeBadge(label, color)`
 * ([StatusBadge.kt]).
 *
 * [typeBadge] grammar, not [Container.statusBadge]: a [DocumentAccessLevel] is a fixed
 * classification an admin picks (not a lifecycle a document progresses through on its own), same
 * distinction [StatusBadge.kt]'s own KDoc draws between `ResolutionMode`/`CommitteeType` (type) and
 * `MotionStatus`/`VoteStatus` (status).
 */
fun documentAccessLevelLabel(level: DocumentAccessLevel): String =
    when (level) {
        DocumentAccessLevel.PUBLIC_MEMBERS -> gettext("Alle Mitglieder")
        DocumentAccessLevel.BOARD_ONLY -> gettext("Nur Vorstand")
        DocumentAccessLevel.ADMIN_ONLY -> gettext("Nur Administration")
    }

fun documentAccessLevelColor(level: DocumentAccessLevel): String =
    when (level) {
        // "secondary" -- the normal, unremarkable case (most documents/folders are visible to all
        // members); the two escalations below are the ones worth drawing the eye to.
        DocumentAccessLevel.PUBLIC_MEMBERS -> "secondary"
        DocumentAccessLevel.BOARD_ONLY -> "warning"
        DocumentAccessLevel.ADMIN_ONLY -> "danger"
    }

/** Convenience wrapper -- every call site renders the SAME badge, never a hand-rolled `typeBadge(...)` inline. */
fun Container.documentAccessLevelBadge(level: DocumentAccessLevel): Span =
    typeBadge(documentAccessLevelLabel(level), documentAccessLevelColor(level))

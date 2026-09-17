package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility

// Welle V1.4.3.x "Veranstaltungen: BOARD/ADMIN-Verwaltungsoberfläche" -- label/badge-color tables for
// EventsScreen.kt, `CrmLabels.kt`'s own precedent (gettext() throughout, not tr(): plain Strings
// returned to a caller, never passed straight into a widget constructor, so tr()'s deferred-
// resolution marker would never resolve). `status` is `statusBadge` grammar (a lifecycle status that
// changes over time, DRAFT -> PUBLISHED -> CANCELLED); `visibility` is `typeBadge` grammar (a fixed
// classification that never progresses) -- see `StatusBadge.kt` KDoc.

fun eventStatusLabel(status: EventStatus): String =
    when (status) {
        EventStatus.DRAFT -> gettext("Entwurf")
        EventStatus.PUBLISHED -> gettext("Veröffentlicht")
        EventStatus.CANCELLED -> gettext("Abgesagt")
    }

fun eventStatusColor(status: EventStatus): String =
    when (status) {
        EventStatus.DRAFT -> "secondary"
        EventStatus.PUBLISHED -> "success"
        EventStatus.CANCELLED -> "danger"
    }

fun eventVisibilityLabel(visibility: EventVisibility): String =
    when (visibility) {
        EventVisibility.MEMBERS_ONLY -> gettext("Nur Mitglieder")
        EventVisibility.PUBLIC -> gettext("Öffentlich")
    }

fun eventVisibilityColor(visibility: EventVisibility): String =
    when (visibility) {
        EventVisibility.MEMBERS_ONLY -> "dark"
        EventVisibility.PUBLIC -> "info"
    }

fun Container.eventStatusBadge(status: EventStatus): Span = statusBadge(eventStatusLabel(status), eventStatusColor(status))

fun Container.eventVisibilityBadge(visibility: EventVisibility): Span =
    typeBadge(eventVisibilityLabel(visibility), eventVisibilityColor(visibility))

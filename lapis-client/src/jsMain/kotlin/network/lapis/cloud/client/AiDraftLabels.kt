package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.McpPostDraftStatus

/**
 * Welle V1.8.2b -- shared German label/badge-color table for [McpPostDraftStatus], same shape as
 * `ComplianceLabels.kt`/`AccountingLabels.kt` (label function + color function, [StatusBadge]
 * grammar since a draft's status is a progressing lifecycle state, not a fixed classification).
 * `RAW_GERMAN_BRANCH`'s zero-tolerance ledger (`ClientDataStateTripwireTest.kt`) means every branch
 * below goes through [gettext], never a bare German string literal.
 */
fun mcpPostDraftStatusLabel(status: McpPostDraftStatus): String =
    when (status) {
        McpPostDraftStatus.OPEN -> gettext("Offen")
        McpPostDraftStatus.DISCARDED -> gettext("Verworfen")
        McpPostDraftStatus.RELEASED -> gettext("Veröffentlicht")
    }

fun mcpPostDraftStatusColor(status: McpPostDraftStatus): String =
    when (status) {
        McpPostDraftStatus.OPEN -> "secondary"
        McpPostDraftStatus.DISCARDED -> "secondary"
        McpPostDraftStatus.RELEASED -> "success"
    }

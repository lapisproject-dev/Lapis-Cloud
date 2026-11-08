package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.PartgDonorProposalAction
import network.lapis.cloud.shared.domain.PartgDonorProposalBlock
import network.lapis.cloud.shared.domain.PartgDonorProposalCounts
import network.lapis.cloud.shared.domain.PartgDonorProposalPickDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRowDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRules
import network.lapis.cloud.shared.domain.PartgDonorProposalStatus
import network.lapis.cloud.shared.domain.PartgLargeDonorDto
import network.lapis.cloud.shared.domain.PartgLargeDonorOrigin
import network.lapis.cloud.shared.domain.PartgPersonKind

/**
 * V1.9.293 (R5b) -- the pure parts of the dialog "Vorschlag aus Buchungen" for statement B (display words, the difference, the preselection and the
 * text of the button). The rules (allowed actions, the preselected action, the validation of the picks) are the shared
 * [PartgDonorProposalRules]; the server decided them already, the client only follows.
 */
internal fun partgDonorStatusLabel(status: PartgDonorProposalStatus): String =
    when (status) {
        PartgDonorProposalStatus.NEW -> gettext("Neu")
        PartgDonorProposalStatus.DIFFERENT -> gettext("Betrag weicht ab")
        PartgDonorProposalStatus.UNCHANGED -> gettext("Unverändert")
        PartgDonorProposalStatus.SIMILAR -> gettext("Ähnliche Zeile vorhanden")
        PartgDonorProposalStatus.ENTERED_ONLY -> gettext("Nur von Hand erfasst")
        PartgDonorProposalStatus.NOT_TRANSFERABLE -> gettext("Nicht übernehmbar")
    }

internal fun partgDonorBlockLabel(block: PartgDonorProposalBlock): String =
    when (block) {
        PartgDonorProposalBlock.KIND_UNCLEAR -> gettext("Art der Person uneinheitlich")
        PartgDonorProposalBlock.COUNTRY_MISSING -> gettext("Land fehlt in den Stammdaten")
        PartgDonorProposalBlock.MASTER_DATA_INVALID -> gettext("Stammdaten zu lang oder ungültig")
        PartgDonorProposalBlock.CONTAINS_FEES -> gettext("Beiträge enthalten, Betrag bleibt unverändert")
    }

internal fun partgPersonKindLabel(kind: PartgPersonKind?): String =
    when (kind) {
        PartgPersonKind.NATURAL -> gettext("Natürliche Person")
        PartgPersonKind.LEGAL -> gettext("Juristische Person")
        null -> "–"
    }

/** Bookings minus entered, in cents; `null` ("–", never 0) when one side is missing. */
internal fun partgDonorDifference(row: PartgDonorProposalRowDto): Long? {
    val booked = row.bookedCents ?: return null
    val entered = row.enteredCents ?: return null
    return booked - entered
}

/** The picks the dialog starts with: exactly the rows the server preselected (only NEW). */
internal fun partgDonorInitialPicks(rows: List<PartgDonorProposalRowDto>): Map<String, PartgDonorProposalPickDto> =
    rows
        .filter { it.ref != null && it.preselected != null }
        .associate { it.ref!! to PartgDonorProposalPickDto(ref = it.ref!!, action = it.preselected!!) }

/** `2 anlegen · 1 Betrag ersetzen · 1 verknüpfen`; `null` when nothing is picked. */
internal fun partgDonorApplyLabel(counts: PartgDonorProposalCounts): String? {
    val parts =
        listOfNotNull(
            gettext("%1 anlegen", counts.created).takeIf { counts.created > 0 },
            gettext("%1 Betrag ersetzen", counts.replaced).takeIf { counts.replaced > 0 },
            gettext("%1 verknüpfen", counts.linked).takeIf { counts.linked > 0 },
        )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun partgDonorPicksLabel(picks: Collection<PartgDonorProposalPickDto>): String? =
    partgDonorApplyLabel(PartgDonorProposalRules.counts(picks.toList()))

/** The words in the column "Herkunft" of the list of statement B. */
internal fun partgDonorOriginLabel(donor: PartgLargeDonorDto): String =
    when (donor.origin) {
        PartgLargeDonorOrigin.MANUAL -> gettext("Von Hand")
        PartgLargeDonorOrigin.LEDGER ->
            donor.sourceAppliedAt?.let { gettext("Aus Buchungen (übernommen am %1)", formatDate(systemDate(it))) }
                ?: gettext("Aus Buchungen")
        PartgLargeDonorOrigin.LEDGER_EDITED -> gettext("Aus Buchungen, danach geändert")
    }

internal fun partgDonorActionName(action: PartgDonorProposalAction): String =
    when (action) {
        PartgDonorProposalAction.CREATE -> gettext("Neu anlegen")
        PartgDonorProposalAction.REPLACE_AMOUNT -> gettext("Betrag ersetzen")
        PartgDonorProposalAction.LINK -> gettext("Verknüpfen")
    }

/** The fixed note of the dialog (open legal question). */
internal fun partgDonorProposalNote(): String =
    gettext(
        "Der Vorschlag zählt nur die Buchungen der Bundesgliederung zusammen. Mehrere kleinere Beträge an verschiedene " +
            "Gliederungen werden nicht erkannt; diese Rechtsfrage ist offen (Q1, Q40). Entwurf, nicht geprüft.",
    )

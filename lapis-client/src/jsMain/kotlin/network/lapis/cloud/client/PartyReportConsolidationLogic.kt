package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgConsolidatedDonorDto
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgConsolidationRules
import network.lapis.cloud.shared.domain.PartgDonorCandidateDto
import network.lapis.cloud.shared.domain.PartgExclusionReason
import network.lapis.cloud.shared.domain.PartgIntraTransferInputDto
import network.lapis.cloud.shared.domain.PartgMatchState
import network.lapis.cloud.shared.domain.PartgNotComputableReason
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.SessionInfoDto

// V1.9.290 (R5): the pure, DOM-free texts and rules of the consolidation tab. Nothing here builds a sentence from server free text.

/** The fixed Q1 notice under the combination block: the legal question is open, the list is a draft. */
internal fun partgDonorLegalNote(): String =
    tr(
        "Ob die Schwelle von 10.000 € für die ganze Partei gilt, ist rechtlich noch nicht geklärt. " +
            "Diese Liste ist ein Entwurf und kein geprüfter Ausweis. Teilbeträge unter 10.000 € je Gliederung werden nicht erfasst.",
    )

/** The consolidation is shown to the federal roles only; a chapter treasurer never sees the tab and never calls it. */
internal fun partgConsolidationVisible(session: SessionInfoDto?): Boolean = !partgChapterTreasurerOnly(session)

/** An amount, or "n. b." -- never 0 for "unknown". */
internal fun partgConsolidatedCellText(cell: PartgConsolidatedCell): String =
    when (cell) {
        is PartgConsolidatedCell.Value -> moneyFromCents(cell.cents)
        is PartgConsolidatedCell.NotComputable -> tr("n. b.")
    }

/** The reason below an "n. b." cell; `null` for a value. */
internal fun partgConsolidatedCellReason(cell: PartgConsolidatedCell): String? =
    when (cell) {
        is PartgConsolidatedCell.Value -> null
        is PartgConsolidatedCell.NotComputable ->
            when (cell.reason) {
                PartgNotComputableReason.MISSING_IN_UNITS -> gettext("%1 Einheiten ohne Wert", cell.unitKeys.size)
                PartgNotComputableReason.ELIMINATION_EXCEEDS -> tr("Die Eliminierung ist größer als die Summe")
            }
    }

internal fun partgExclusionText(reason: PartgExclusionReason): String =
    when (reason) {
        PartgExclusionReason.NO_DATA -> tr("keine Daten")
        PartgExclusionReason.BOOKINGS_NOT_TAKEN_OVER -> tr("Buchungen vorhanden, nicht übernommen")
    }

internal fun partgTransferKindLabel(kind: PartgTransferKind): String =
    when (kind) {
        PartgTransferKind.SUBSIDY -> tr("Zuschuss")
        PartgTransferKind.BALANCE -> tr("Forderung/Verbindlichkeit")
    }

/** The match state as a word AND a symbol: never by colour alone. The symbol class and the badge colour (no red) come with it. */
internal class PartgMatchLook(
    val icon: ActionIcon,
    val word: String,
    val color: String,
)

internal fun partgMatchLook(state: PartgMatchState): PartgMatchLook =
    when (state) {
        PartgMatchState.MATCH -> PartgMatchLook(ActionIcon.APPROVE, tr("abgeglichen"), "info")
        PartgMatchState.DIFFERS -> PartgMatchLook(ActionIcon.WARN, tr("weicht ab"), "secondary")
        PartgMatchState.COUNTERPART_MISSING -> PartgMatchLook(ActionIcon.INFO, tr("Gegenseite fehlt"), "secondary")
    }

/** "nicht abgeglichen: 1.234,56 €" -- the difference that stays visible. */
internal fun partgUnmatchedText(cents: Long): String = gettext("nicht abgeglichen: %1", moneyFromCents(cents))

/** The fixed text of a refusal code of a transfer or a combination (the server answers with the code only). */
internal fun partgConsolidationCodeText(code: String?): String =
    when (code) {
        "SAME_UNIT" ->
            tr(
                "Zahler und Empfänger müssen verschiedene Gliederungen sein; " +
                    "bei einer Zusammenführung müssen die Zeilen aus verschiedenen Gliederungen stammen.",
            )
        "AMOUNT_MISSING" -> tr("Bitte mindestens einen Betrag angeben.")
        "AMOUNT_RANGE" -> tr("Der Betrag liegt außerhalb des erlaubten Bereichs.")
        "NOTE_TOO_LONG" -> tr("Die Notiz ist zu lang.")
        "NOTE_CONTROL" -> tr("Bitte keine Steuerzeichen verwenden.")
        "PAIR_EXISTS" ->
            tr("Für diese Gliederungen gibt es diese Art von Übertragung schon. Bitte bearbeiten Sie den vorhandenen Eintrag.")
        "TOO_MANY" -> tr("Es sind nicht mehr Einträge möglich.")
        "ROW_COUNT" ->
            gettext("Bitte zwischen %1 und %2 Zeilen wählen.", PartgConsolidationRules.MIN_ROWS, PartgConsolidationRules.MAX_ROWS)
        "ALREADY_COMBINED" -> tr("Mindestens eine Zeile ist schon zusammengeführt.")
        "DUPLICATE_ROW" -> tr("Eine Zeile kommt doppelt vor.")
        else -> tr("Die Eingabe ist ungültig.")
    }

/** The client-side check of the transfer form: the same rules as the server (`PartgConsolidationRules`). `null` = fine. */
internal fun partgTransferInputCheck(input: PartgIntraTransferInputDto): String? =
    when {
        input.payerUnitKey.isBlank() || input.payeeUnitKey.isBlank() -> tr("Bitte Zahler und Empfänger wählen.")
        input.payerUnitKey == input.payeeUnitKey -> partgConsolidationCodeText("SAME_UNIT")
        else ->
            PartgConsolidationRules.validateAmounts(input.payerAmountCents, input.payeeAmountCents)?.let { partgConsolidationCodeText(it) }
                ?: PartgConsolidationRules.normalizeNote(input.note).exceptionOrNull()?.let { partgConsolidationCodeText(it.message) }
    }

/** The label of a unit key from the list of units (the key itself when unknown). */
internal fun partgUnitLabelOf(
    units: List<PartgUnitDto>,
    key: String,
): String = units.firstOrNull { it.unitKey == key }?.let { unitLabel(it) } ?: key

/** One option line per candidate: the NAME comes from the donor list the screen already has (the candidate carries ids only). */
internal fun partgCandidateLabel(
    candidate: PartgDonorCandidateDto,
    donors: List<PartgConsolidatedDonorDto>,
    units: List<PartgUnitDto>,
): String {
    val byId = donors.flatMap { d -> d.donorIds.map { it to d } }.toMap()
    val name = candidate.donorIds.firstNotNullOfOrNull { byId[it]?.name }?.let { sanitizeUntrustedI18nText(it) } ?: tr("Unbekannt")
    return gettext("%1 in %2", name, candidate.unitKeys.joinToString(", ") { partgUnitLabelOf(units, it) })
}

/** "3 Gliederungen einbezogen, 30 nicht einbezogen" -- the text of the polite live region. */
internal fun partgConsolidationStatusText(dto: PartgConsolidationDto): String =
    gettext("%1 Gliederungen einbezogen, %2 nicht einbezogen. Entwurf, nicht geprüft.", dto.included.size, dto.excluded.size)

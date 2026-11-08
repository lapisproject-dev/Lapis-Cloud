package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgCompletenessCalculator
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgEnterpriseRules
import network.lapis.cloud.shared.domain.PartgExclusionReason
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportComputation
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgSponsorQualification
import network.lapis.cloud.shared.domain.PartgStatementRules
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException

/** V1.9.256 -- turns the entered values into a [PartgReportDocument]. Pure: no database, only [PartgReportComputation]. */
internal object PartgReportAssembler {
    fun unitLabel(unit: PartgUnitDto): String =
        when (unit.level) {
            PartgUnitLevel.FEDERAL -> PartgReportTexts.FEDERAL
            PartgUnitLevel.STATE -> "${PartgReportTexts.STATE_PREFIX} ${unit.chapterName.orEmpty()}".trim()
            PartgUnitLevel.SUBORDINATE ->
                "${PartgReportTexts.SUBORDINATE_PREFIX} ${unit.chapterName.orEmpty()} ${PartgReportTexts.SUBORDINATE_SUFFIX}"
        }

    fun assemble(
        organizationName: String,
        year: Int,
        scope: PartgReportScope,
        units: List<PartgUnitDto>,
        entered: Map<Int, Map<String, Map<String, Long>>>,
        annex: Map<Int, Map<String, PartgAnnexStore.AnnexData>> = emptyMap(),
        notes: Map<Int, PartgAnnexStore.NotesData> = emptyMap(),
        findings: List<PartgFindingDto> = emptyList(),
        consolidation: ConsolidationResult? = null,
        consolidatedDonors: DonorConsolidation? = null,
    ): PartgReportDocument {
        if (units.size > PartgReportComputation.MAX_UNITS) throw PartyReportInvalidInputException("Too many units")
        val selected =
            when (scope) {
                is PartgReportScope.All -> units
                is PartgReportScope.Single ->
                    listOf(units.firstOrNull { it.unitKey == scope.unit.key() } ?: throw NotFoundException("Unit not found"))
            }

        fun values(
            year: Int,
            unit: PartgUnitDto,
        ): Map<String, Long> = entered[year]?.get(unit.unitKey).orEmpty()

        val pages =
            selected.map { unit ->
                UnitPage(
                    label = unitLabel(unit),
                    current = PartgReportComputation.cells(values(year, unit)),
                    prior = PartgReportComputation.cells(values(year - 1, unit)),
                )
            }
        val all = scope is PartgReportScope.All
        val unitSum =
            if (all) {
                UnitPage(
                    label = PartgReportTexts.WHOLE_PARTY,
                    current = PartgReportComputation.aggregate(selected.map { values(year, it) }),
                    prior = PartgReportComputation.aggregate(selected.map { values(year - 1, it) }),
                )
            } else {
                null
            }
        val levels =
            if (all) {
                listOf(
                    Triple(PartgUnitLevel.FEDERAL, PartgReportTexts.LEVEL_FEDERAL, selected.filter { it.level == PartgUnitLevel.FEDERAL }),
                    Triple(PartgUnitLevel.STATE, PartgReportTexts.LEVEL_STATE, selected.filter { it.level == PartgUnitLevel.STATE }),
                    Triple(
                        PartgUnitLevel.SUBORDINATE,
                        PartgReportTexts.LEVEL_SUBORDINATE,
                        selected.filter { it.level == PartgUnitLevel.SUBORDINATE },
                    ),
                ).map { (level, label, members) ->
                    LevelRow(
                        level = level,
                        label = label,
                        current = PartgReportComputation.aggregate(members.map { values(year, it) }),
                        prior = PartgReportComputation.aggregate(members.map { values(year - 1, it) }),
                    )
                }
            } else {
                emptyList()
            }
        return PartgReportDocument(
            organizationName = organizationName,
            year = year,
            priorYear = year - 1,
            scope = scope,
            units = pages,
            unitSum = unitSum,
            levels = levels,
            completeness = PartgCompletenessCalculator.compute(year = year, units = selected, entered = entered),
            annex = annexSection(year = year, selected = selected, all = all, annex = annex),
            notes = notesSection(notes[year]),
            findings = findings,
            consolidation =
                if (all && consolidation != null && consolidatedDonors != null) {
                    consolidationSection(units = units, result = consolidation, donors = consolidatedDonors)
                } else {
                    null
                },
        )
    }

    /** V1.9.290 (R5): the consolidated whole party for the exports. Budget: the donor list is bounded by the unit caps (500 rows per unit). */
    private fun consolidationSection(
        units: List<PartgUnitDto>,
        result: ConsolidationResult,
        donors: DonorConsolidation,
    ): ConsolidationSection {
        val labels = units.associate { it.unitKey to unitLabel(it) }

        fun label(key: String) = labels[key] ?: key
        if (donors.donors.size > PartgReportComputation.MAX_UNITS * PartgAnnexRules.MAX_DONORS_PER_UNIT) {
            throw PartgReportTooLargeException("consolidated donors")
        }
        return ConsolidationSection(
            included = result.included.map(::label),
            excluded =
                result.excluded.map {
                    ExcludedLine(
                        unitLabel = label(it.unitKey),
                        reason =
                            if (it.reason == PartgExclusionReason.NO_DATA) {
                                PartgReportTexts.CONSOLIDATION_REASON_NO_DATA
                            } else {
                                PartgReportTexts.CONSOLIDATION_REASON_BOOKINGS
                            },
                    )
                },
            priorTransfersUnmatched = result.priorTransfersUnmatched,
            lines =
                result.rows.map { row ->
                    val position = checkNotNull(PartgPositions.find(row.code))
                    ConsolidationLine(
                        code = row.code,
                        numberText = PartgReportLayout.numberText(row.code),
                        label = position.labelDe,
                        unitSum = row.unitSum,
                        eliminatedCents = row.eliminatedCents,
                        consolidated = row.consolidated,
                        prior = row.priorConsolidated,
                        bold = position.kind == PartgKind.SUM,
                    )
                },
            reconciliation =
                result.transfers.map {
                    ReconciliationLine(
                        kind = it.kind,
                        payerLabel = label(it.payerUnitKey),
                        payeeLabel = label(it.payeeUnitKey),
                        payerAmountCents = it.payerAmountCents,
                        payeeAmountCents = it.payeeAmountCents,
                        match = it.match,
                    )
                },
            unmatched =
                result.unmatched.map {
                    UnmatchedLine(
                        unitLabel = label(it.unitKey),
                        positionLabel =
                            PartgReportLayout.numberText(it.positionCode) + " " + PartgPositions.find(it.positionCode)?.labelDe.orEmpty(),
                        differenceCents = it.differenceCents,
                    )
                },
            donors =
                donors.donors.map { d ->
                    ConsolidatedDonorLine(
                        unitLabels = d.unitKeys.joinToString("; ") { label(it) },
                        personKind = d.personKind,
                        name = d.name,
                        addressLine =
                            listOfNotNull(d.street, listOfNotNull(d.postalCode, d.city).joinToString(" ").ifEmpty { null })
                                .joinToString(", "),
                        country = d.country,
                        contributionKind = d.contributionKind,
                        totalCents = d.totalCents,
                        rowCount = d.donorIds.size,
                    )
                },
        )
    }

    private fun annexSection(
        year: Int,
        selected: List<PartgUnitDto>,
        all: Boolean,
        annex: Map<Int, Map<String, PartgAnnexStore.AnnexData>>,
    ): AnnexSection {
        val data = annex[year].orEmpty()
        val pages =
            selected.map { unit ->
                val row = data[unit.unitKey]
                AnnexPage(
                    label = unitLabel(unit),
                    deductions =
                        PartgDeduction.entries.associateWith { d ->
                            row?.deductions?.get(d)?.let { PartgCell.Value(it) }
                                ?: PartgCell.NotEntered
                        },
                    memberCount = row?.memberCount,
                )
            }
        val whole =
            if (all) {
                AnnexPage(
                    label = PartgReportTexts.WHOLE_PARTY,
                    deductions = PartgDeduction.entries.associateWith { d -> sumDeduction(deduction = d, pages = pages) },
                    memberCount =
                        selected.indexOfFirst { it.level == PartgUnitLevel.FEDERAL }.takeIf { it >= 0 }?.let {
                            pages[it]
                                .memberCount
                        },
                )
            } else {
                null
            }
        val donors =
            selected.flatMap { unit ->
                val label = unitLabel(unit)
                data[unit.unitKey]?.donors.orEmpty().mapIndexed { index, donor ->
                    DonorLine(
                        unitLabel = label,
                        rowNumber = index + 1,
                        personKind = donor.personKind,
                        name = donor.name,
                        addressLine =
                            listOfNotNull(donor.street, listOfNotNull(donor.postalCode, donor.city).joinToString(" ").ifEmpty { null })
                                .joinToString(", "),
                        country = donor.country,
                        contributionKind = donor.contributionKind,
                        amountCents = donor.amountCents,
                    )
                }
            }
        val sponsors =
            selected.flatMap { unit ->
                val label = unitLabel(unit)
                data[unit.unitKey]?.sponsors.orEmpty().mapIndexedNotNull { index, sponsor ->
                    val amounts = sponsor.entries.map { it.amountCents }
                    val qualification = PartgStatementRules.qualification(amounts)
                    if (qualification == PartgSponsorQualification.NONE) return@mapIndexedNotNull null
                    SponsorLine(
                        unitLabel = label,
                        rowNumber = index + 1,
                        personKind = sponsor.personKind,
                        name = sponsor.name,
                        addressLine =
                            listOfNotNull(
                                sponsor.street,
                                listOfNotNull(sponsor.postalCode, sponsor.city).joinToString(" ").ifEmpty { null },
                            ).joinToString(", "),
                        country = sponsor.country,
                        yearlySumCents = sponsor.yearlySumCents,
                        entryCount = sponsor.entries.size,
                        qualification = qualification,
                        singleEntries =
                            sponsor.entries
                                .filter { it.amountCents > PartgStatementRules.SPONSORING_SINGLE_THRESHOLD_CENTS }
                                .map { SponsorEntryLine(date = it.date, amountCents = it.amountCents, purpose = it.purpose) },
                    )
                }
            }
        val youthOrgs =
            selected.flatMap { unit ->
                val label = unitLabel(unit)
                data[unit.unitKey]?.youthOrgs.orEmpty().mapIndexed { index, y ->
                    YouthOrgLine(
                        unitLabel = label,
                        rowNumber = index + 1,
                        name = y.name,
                        relationship = y.relationship,
                        incomeCents = y.incomeCents,
                        expensesCents = y.expensesCents,
                    )
                }
            }
        val enterprises =
            selected.flatMap { unit ->
                val label = unitLabel(unit)
                data[unit.unitKey]?.enterprises.orEmpty().mapIndexed { index, e ->
                    EnterpriseLine(
                        unitLabel = label,
                        rowNumber = index + 1,
                        name = e.name,
                        legalForm = e.legalForm,
                        seat = e.seat,
                        shareBasisPoints = e.shareBasisPoints,
                        incomeCents = e.incomeCents,
                        expensesCents = e.expensesCents,
                    )
                }
            }
        val enterpriseTotals =
            selected.mapNotNull { unit ->
                val rows = data[unit.unitKey]?.enterprises.orEmpty()
                if (rows.isEmpty()) return@mapNotNull null
                val income = PartgEnterpriseRules.completeSum(rows.map { it.incomeCents })
                val expenses = PartgEnterpriseRules.completeSum(rows.map { it.expensesCents })
                EnterpriseTotal(
                    unitLabel = unitLabel(unit),
                    incomeCents = income,
                    expensesCents = expenses,
                    resultCents = PartgEnterpriseRules.resultCents(income = income, expenses = expenses),
                )
            }
        return AnnexSection(
            units = pages,
            wholeParty = whole,
            donors = donors,
            sponsors = sponsors,
            youthOrgs = youthOrgs,
            enterprises = enterprises,
            enterpriseTotals = enterpriseTotals,
        )
    }

    /** Value when every unit entered the figure, NotEntered when none did, else Partial (the same rule as the positions). */
    private fun sumDeduction(
        deduction: PartgDeduction,
        pages: List<AnnexPage>,
    ): PartgCell {
        val entered = pages.mapNotNull { (it.deductions.getValue(deduction) as? PartgCell.Value)?.cents }
        return when {
            entered.isEmpty() -> PartgCell.NotEntered
            entered.size == pages.size -> PartgCell.Value(entered.sum())
            else -> PartgCell.Partial(cents = entered.sum(), missing = listOf(deduction.name))
        }
    }

    private fun notesSection(data: PartgAnnexStore.NotesData?): NotesSection =
        NotesSection(blocks = data?.notes.orEmpty().filter { it.included }, holdings = data?.holdings.orEmpty())
}

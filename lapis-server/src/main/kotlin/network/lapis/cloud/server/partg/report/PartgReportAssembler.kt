package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgCompletenessCalculator
import network.lapis.cloud.shared.domain.PartgReportComputation
import network.lapis.cloud.shared.domain.PartgReportScope
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
        val consolidated =
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
            consolidated = consolidated,
            levels = levels,
            completeness = PartgCompletenessCalculator.compute(year = year, units = selected, entered = entered),
        )
    }
}

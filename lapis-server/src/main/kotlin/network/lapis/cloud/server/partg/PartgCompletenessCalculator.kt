package network.lapis.cloud.server.partg

import network.lapis.cloud.shared.domain.PartgCompletenessDto
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnitCompletenessDto
import network.lapis.cloud.shared.domain.PartgUnitDto

/**
 * V1.9.256 -- the ONE place that turns entered values into a completeness picture. The overview, the checklist and the export all read the
 * same snapshot ([PartgReportStore.enteredValues]) and count with this object. Pure: no database access.
 */
internal object PartgCompletenessCalculator {
    private val leafOrder: List<String> = PartgPositions.ALL.filter { it.kind == PartgKind.LEAF }.map { it.code }

    fun compute(
        year: Int,
        units: List<PartgUnitDto>,
        entered: Map<Int, Map<String, Map<String, Long>>>,
    ): PartgCompletenessDto =
        PartgCompletenessDto(
            year = year,
            priorYear = year - 1,
            leafCount = PartgPositions.LEAF_COUNT,
            units =
                units.map { unit ->
                    val current = entered[year]?.get(unit.unitKey).orEmpty()
                    PartgUnitCompletenessDto(
                        unit = unit,
                        missingCurrent = leafOrder.filter { it !in current },
                        enteredPrior = entered[year - 1]?.get(unit.unitKey)?.size ?: 0,
                    )
                },
        )
}

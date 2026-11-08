package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgConsolidationStore
import network.lapis.cloud.shared.domain.PartgConsolidatedDonorDto
import network.lapis.cloud.shared.domain.PartgConsolidationRules
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDonorCandidateDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnitDto
import java.text.Normalizer
import java.util.Locale

/** The consolidated statement B of one year. [candidates] is capped at [PartgConsolidationRules.MAX_CANDIDATES]; [candidateTotal] is the full number. */
internal class DonorConsolidation(
    val donors: List<PartgConsolidatedDonorDto>,
    val candidates: List<PartgDonorCandidateDto>,
    val candidateTotal: Int,
    /** Groups with fewer than two rows left, or with a row that was changed after the group was made (P21). */
    val brokenCombinations: Int,
)

/**
 * V1.9.290 (R5) -- statement B over all units. Pure. A manual group of at least two existing rows is ONE person: the display row is the one with
 * the highest amount (tie: smallest id), the amount is the sum, the contribution kind is the union. A group with fewer than two rows left is
 * ignored (and counted for P21). Candidates are SUGGESTIONS only (same kind, normalised name, non-empty postal code and country, different
 * units); nothing is stored. Sorting is done here in Kotlin, never in SQL, because the collation of H2 and PostgreSQL differs.
 */
internal object PartgDonorConsolidation {
    private class Row(
        val unitKey: String,
        val donor: PartgAnnexStore.DonorRow,
    )

    private val combiningMarks = Regex("\\p{M}+")
    private val whitespace = Regex("\\s+")

    /** NFKD, combining marks removed, lower case (ROOT), whitespace collapsed. */
    fun normalizeName(name: String): String =
        Normalizer
            .normalize(name, Normalizer.Form.NFKD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()

    fun compute(
        units: List<PartgUnitDto>,
        annex: Map<String, PartgAnnexStore.AnnexData>,
        combinations: List<PartgConsolidationStore.CombinationRow>,
    ): DonorConsolidation {
        val order = units.withIndex().associate { it.value.unitKey to it.index }
        val rows =
            annex.entries
                .sortedBy { order[it.key] ?: Int.MAX_VALUE }
                .flatMap { (unitKey, data) -> data.donors.map { Row(unitKey = unitKey, donor = it) } }
        val byId = rows.associateBy { it.donor.id }
        val grouped = HashSet<kotlin.uuid.Uuid>()
        var broken = 0
        val result = mutableListOf<PartgConsolidatedDonorDto>()
        combinations.forEach { combination ->
            val members = combination.donorIds.mapNotNull { byId[it] }
            val changedLater = members.any { m -> m.donor.updatedAt?.let { it > combination.createdAt } == true }
            if (members.size < PartgConsolidationRules.MIN_ROWS || changedLater) broken += 1
            if (members.size < PartgConsolidationRules.MIN_ROWS) return@forEach
            grouped += members.map { it.donor.id }
            val display = members.sortedWith(compareByDescending<Row> { it.donor.amountCents }.thenBy { it.donor.id.toString() }).first()
            val kinds = members.map { it.donor.contributionKind }.toSet()
            result +=
                PartgConsolidatedDonorDto(
                    combinationId = combination.id.toString(),
                    displayDonorId = display.donor.id.toString(),
                    personKind = display.donor.personKind,
                    name = display.donor.name,
                    street = display.donor.street,
                    postalCode = display.donor.postalCode,
                    city = display.donor.city,
                    country = display.donor.country,
                    contributionKind = if (kinds.size == 1) kinds.single() else PartgContributionKind.BOTH,
                    totalCents = members.sumOf { it.donor.amountCents },
                    unitKeys = members.map { it.unitKey }.distinct().sortedBy { order[it] ?: Int.MAX_VALUE },
                    donorIds = members.map { it.donor.id.toString() }.sorted(),
                )
        }
        rows.filter { it.donor.id !in grouped }.forEach { row ->
            result +=
                PartgConsolidatedDonorDto(
                    combinationId = null,
                    displayDonorId = row.donor.id.toString(),
                    personKind = row.donor.personKind,
                    name = row.donor.name,
                    street = row.donor.street,
                    postalCode = row.donor.postalCode,
                    city = row.donor.city,
                    country = row.donor.country,
                    contributionKind = row.donor.contributionKind,
                    totalCents = row.donor.amountCents,
                    unitKeys = listOf(row.unitKey),
                    donorIds = listOf(row.donor.id.toString()),
                )
        }
        val sorted =
            result.sortedWith(
                compareByDescending<PartgConsolidatedDonorDto> { it.totalCents }.thenBy { it.name }.thenBy { it.displayDonorId },
            )
        val allCandidates = candidates(free = rows.filter { it.donor.id !in grouped }, order = order)
        return DonorConsolidation(
            donors = sorted,
            candidates = allCandidates.take(PartgConsolidationRules.MAX_CANDIDATES),
            candidateTotal = allCandidates.size,
            brokenCombinations = broken,
        )
    }

    private fun candidates(
        free: List<Row>,
        order: Map<String, Int>,
    ): List<PartgDonorCandidateDto> {
        data class Key(
            val kind: PartgPersonKind,
            val name: String,
            val postalCode: String,
            val country: String,
        )

        val buckets =
            free
                .filter { !it.donor.postalCode.isNullOrBlank() && normalizeName(it.donor.name).isNotEmpty() }
                .groupBy {
                    Key(
                        kind = it.donor.personKind,
                        name = normalizeName(it.donor.name),
                        postalCode = it.donor.postalCode!!.trim(),
                        country = it.donor.country,
                    )
                }
        val result = mutableListOf<PartgDonorCandidateDto>()
        buckets.values.forEach { bucket ->
            // peel layers: each layer takes the strongest row of every unit, so rows of the same unit never share a group
            var rest = bucket
            while (rest.map { it.unitKey }.distinct().size >= PartgConsolidationRules.MIN_ROWS) {
                val layer =
                    rest
                        .groupBy { it.unitKey }
                        .values
                        .map { rowsOfUnit ->
                            rowsOfUnit
                                .sortedWith(
                                    compareByDescending<Row> { it.donor.amountCents }.thenBy { it.donor.id.toString() },
                                ).first()
                        }.sortedBy { order[it.unitKey] ?: Int.MAX_VALUE }
                        .take(PartgConsolidationRules.MAX_ROWS)
                result +=
                    PartgDonorCandidateDto(
                        donorIds = layer.map { it.donor.id.toString() }.sorted(),
                        unitKeys = layer.map { it.unitKey },
                    )
                val taken = layer.map { it.donor.id }.toSet()
                rest = rest.filter { it.donor.id !in taken }
            }
        }
        return result.sortedBy { it.donorIds.joinToString(",") }
    }
}

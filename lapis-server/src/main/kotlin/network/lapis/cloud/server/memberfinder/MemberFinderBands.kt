package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.agreementtexts.CurrentAgreementText
import network.lapis.cloud.server.db.generated.MemberFinderRegionBandTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderRules
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * V1.9.144 -- the daily aggregate `member_finder_region_band`: per region only a coarse band and only for regions with at least
 * `MEMBER_FINDER_MIN_REGION_SIZE` consenting participants. **It is recomputed at most once per organization day** (the poller), never on a join or
 * a leave: a group size that moved with every single consent would let an observer subtract two readings and learn who joined. A day's
 * reading is therefore the same for everybody the whole day. Must run inside a `transaction {}`.
 */
internal object MemberFinderBands {
    fun recompute(
        now: LocalDateTime,
        today: LocalDate,
        current: CurrentAgreementText,
    ) {
        val counts =
            MemberFinderEligibility
                .participants(today = today, current = current)
                .groupingBy { it.regionKey }
                .eachCount()
        val bands = counts.mapNotNull { (region, count) -> MemberFinderRules.bandOf(count)?.let { region to it } }
        MemberFinderRegionBandTable.deleteAll()
        MemberFinderRegionBandTable.batchInsert(bands) { (region, band) ->
            this[MemberFinderRegionBandTable.regionKey] = region
            this[MemberFinderRegionBandTable.band] = band
            this[MemberFinderRegionBandTable.computedAt] = now
        }
    }

    fun bandOf(regionKey: String): MemberFinderBand? =
        MemberFinderRegionBandTable
            .selectAll()
            .where { MemberFinderRegionBandTable.regionKey eq regionKey }
            .singleOrNull()
            ?.get(MemberFinderRegionBandTable.band)

    /** Every region with a visible band, ordered by key. */
    fun all(): List<Pair<String, MemberFinderBand>> =
        MemberFinderRegionBandTable
            .selectAll()
            .map { it[MemberFinderRegionBandTable.regionKey] to it[MemberFinderRegionBandTable.band] }
            .sortedBy { it.first }

    /** The moment of the last evaluation, `null` while the table is empty. */
    fun computedAt(): LocalDateTime? =
        MemberFinderRegionBandTable
            .selectAll()
            .map { it[MemberFinderRegionBandTable.computedAt] }
            .maxOrNull()

    fun computedOn(): LocalDate? = computedAt()?.let { OrganizationTimeZone.dateOf(it) }

    fun deleteAll() {
        MemberFinderRegionBandTable.deleteAll()
    }
}

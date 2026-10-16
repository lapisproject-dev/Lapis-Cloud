// Welle V1.9.144 "Mitglieder-Finder" -- hand-edited, see 72-member-finder.kuml.kts file header. NO member column on purpose.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MemberFinderBand
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object MemberFinderRegionBandTable : Table("member_finder_region_band") {
    public val regionKey: Column<String> = varchar("region_key", 8)
    public val band: Column<MemberFinderBand> = enumerationByName<MemberFinderBand>("band", 8)
    public val computedAt: Column<LocalDateTime> = datetime("computed_at")

    override val primaryKey: PrimaryKey = PrimaryKey(regionKey)
}

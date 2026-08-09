// Hand-written per ADR-0016 Option B (see 29-conference-streaming.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object ConferenceStreamDestinationTable : Table("conference_stream_destination") {
    public val id: Column<Uuid> = uuid("id")
    public val label: Column<String> = varchar("label", 120).uniqueIndex()
    public val platform: Column<ConferenceStreamPlatform> = enumerationByName<ConferenceStreamPlatform>("platform", 12)
    public val rtmpUrl: Column<String> = varchar("rtmp_url", 500)
    public val streamKeyCiphertext: Column<String> = varchar("stream_key_ciphertext", 1024)
    public val streamKeySetAt: Column<LocalDateTime> = datetime("stream_key_set_at")
    public val createdByMemberId: Column<Uuid> = reference("created_by_member_id", MemberTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val enabled: Column<Boolean> = bool("enabled").default(true)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the label UNIQUE constraint above is expressed directly via .uniqueIndex() (matching
    // ConferenceRecordingTrackTable.egressId's own idiom) -- no other index declared on this
    // entity needs Exposed's index {} DSL.
}

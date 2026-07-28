// Hand-written per ADR-0016 Option B (see 26-trust-anchor.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object TrustedExternalAnchorTable : Table("trusted_external_anchor") {
    public val id: Column<Uuid> = uuid("id")
    public val anchorEntityUri: Column<String> = varchar("anchor_entity_uri", 2048).uniqueIndex()
    public val addedAt: Column<LocalDateTime> = datetime("added_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

// Welle V1.9.319 "Mitfahr-Foederation" W7 -- hand-edited, see 79-partner-servers.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartnerSigningKeyHoldTable : Table("partner_signing_key_hold") {
    public val host: Column<String> = varchar("host", 253)
    public val trustedKeySha256: Column<String> = char("trusted_key_sha256", 64)
    public val detectedAt: Column<LocalDateTime> = datetime("detected_at")

    override val primaryKey: PrimaryKey = PrimaryKey(host)
}

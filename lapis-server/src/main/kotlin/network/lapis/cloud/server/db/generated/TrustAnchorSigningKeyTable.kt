// Hand-written per ADR-0016 Option B (see 26-trust-anchor.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object TrustAnchorSigningKeyTable : Table("trust_anchor_signing_key") {
    public val id: Column<Uuid> = uuid("id")
    public val kid: Column<String> = varchar("kid", 64).uniqueIndex()
    public val publicKeyPem: Column<String> = text("public_key_pem")
    public val privateKeyPem: Column<String> = text("private_key_pem")
    public val status: Column<TrustAnchorSigningKeyStatus> = enumerationByName<TrustAnchorSigningKeyStatus>("status", 8)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val retiredAt: Column<LocalDateTime?> = datetime("retired_at").nullable()
    public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 1 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}

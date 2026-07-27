// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object OidcSigningKeyTable : Table("oidc_signing_key") {
    public val id: Column<Uuid> = uuid("id")
    public val kid: Column<String> = varchar("kid", 64).uniqueIndex()
    public val publicKeyPem: Column<String> = text("public_key_pem")
    public val privateKeyPem: Column<String> = text("private_key_pem")
    public val createdAt: Column<LocalDateTime> = datetime("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

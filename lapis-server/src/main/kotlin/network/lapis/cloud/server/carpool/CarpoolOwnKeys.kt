package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import network.lapis.cloud.server.federation.FEDERATION_ACTOR_KEY_ID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The private half of this server's own federation key together with its key id (`actorUri#main-key`): the SAME key the partner feed announces as
 * `signingKey`. Used for two things only: to sign the request of the pull ([network.lapis.cloud.server.carpool.pull.CarpoolTeaserFeedClient]) and to sign
 * the origin statement of an own hint ([CarpoolOriginSignatures]). Never logged: `toString` is redacted.
 */
internal class CarpoolOwnSigningKey(
    val keyId: String,
    val privateKeyPem: String,
) {
    override fun toString(): String = "CarpoolOwnSigningKey(<redacted>)"
}

/** V1.9.314 W4 -- reads the own signing key from the database. Runs inside the caller's transaction, no network. */
internal object CarpoolOwnKeys {
    /** `null` when none is provisioned (the feed then carries no own hint and the pull does not start). */
    fun load(): CarpoolOwnSigningKey? =
        FederationActorKeyTable
            .selectAll()
            .where { FederationActorKeyTable.id eq FEDERATION_ACTOR_KEY_ID }
            .singleOrNull()
            ?.let {
                CarpoolOwnSigningKey(
                    keyId = it[FederationActorKeyTable.actorUri] + "#main-key",
                    privateKeyPem = it[FederationActorKeyTable.privateKeyPem],
                )
            }
}

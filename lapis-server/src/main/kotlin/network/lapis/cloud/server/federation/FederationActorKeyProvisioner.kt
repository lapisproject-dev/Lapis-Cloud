package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * The single seeded [FederationActorKeyTable] row's fixed id -- next unused
 * `...-0000-0000000000fN` slot after `price_oracle_config`'s own `...-f5`. See
 * `24-federation.kuml.kts` file header for why this row is provisioned here (at boot) rather than
 * via a Flyway seed `INSERT` like every other singleton row: `actor_uri` depends on
 * [FederationConfig.actorUri], itself derived from `LAPIS_PUBLIC_BASE_URL`, unknown at
 * migration-authoring time.
 */
val FEDERATION_ACTOR_KEY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f6")

/**
 * Provisions this server's own federation Actor keypair exactly once, idempotently, on every
 * `Application.module()` boot -- mirrors [network.lapis.cloud.server.db.DevSeedData]'s "must exist
 * from first boot, not a client-triggerable mutation" shape, but is NOT gated behind
 * `LAPIS_SEED_DEMO_DATA` (a federation identity is a real capability precondition, not sample
 * data, same reasoning `organization_settings`'s unconditional Flyway seed already documents).
 *
 * **Registered in `OrganizationRestoreService.SEEDED_SINGLETON_ROWS`** -- otherwise a fresh
 * restore-target pre-flight check would always find this row non-empty (since [ensureProvisioned]
 * runs before any restore call could ever execute), permanently blocking restore on every server
 * without `allowNonEmptyTarget=true`.
 */
object FederationActorKeyProvisioner {
    fun ensureProvisioned(actorUri: String) {
        transaction {
            val exists =
                FederationActorKeyTable
                    .selectAll()
                    .where { FederationActorKeyTable.id eq FEDERATION_ACTOR_KEY_ID }
                    .count() > 0
            if (exists) return@transaction

            val keyPair = FederationKeyPairGenerator.generate()
            val now: LocalDateTime = DbClock.nowLocalDateTime()
            FederationActorKeyTable.insert {
                it[id] = FEDERATION_ACTOR_KEY_ID
                it[FederationActorKeyTable.actorUri] = actorUri
                it[publicKeyPem] = keyPair.publicKeyPem
                it[privateKeyPem] = keyPair.privateKeyPem
                it[createdAt] = now
            }
        }
    }
}

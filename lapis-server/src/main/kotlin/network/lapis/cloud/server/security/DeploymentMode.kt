package network.lapis.cloud.server.security

import network.lapis.cloud.server.config.SecretEnv

/**
 * The single, shared "are we pointed at the H2 in-memory default, or a real deployment"
 * signal — refactored out of [network.lapis.cloud.server.db.DevSeedData] (which used to carry a
 * private copy of this exact check) so [AuthTestMode] can share the identical definition rather
 * than risk the two signals drifting apart. Both [DevSeedData]'s demo-data seeding gate and
 * [AuthTestMode]'s trusted-header-auth gate must refuse to activate against anything but H2 —
 * that guarantee is only as strong as this one function.
 *
 * Returns `true` only when [network.lapis.cloud.server.db.DatabaseConfig] is (or will be) pointed
 * at the H2 in-memory default — i.e. `LAPIS_DB_URL` is unset or explicitly a `jdbc:h2:mem:` URL.
 * A real deployment always sets `LAPIS_DB_URL` to a `jdbc:postgresql://...` URL, so this is the
 * same signal [network.lapis.cloud.server.db.DatabaseConfig] itself uses to pick a JDBC driver.
 */
internal object DeploymentMode {
    fun isH2InMemory(): Boolean {
        val jdbcUrl = SecretEnv.get("LAPIS_DB_URL")
        return jdbcUrl == null || jdbcUrl.startsWith("jdbc:h2:mem:")
    }
}

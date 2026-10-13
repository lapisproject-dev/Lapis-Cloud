package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.rpc.FriendRegistrationConfig

/**
 * Where the carpool code reads the friend-registration policy (email-verification duty). One indirection so tests can switch the duty on
 * without touching process environment variables; production always uses the environment-based [FriendRegistrationConfig.load].
 */
internal object CarpoolFriendConfig {
    @Volatile
    internal var provider: () -> FriendRegistrationConfig = { FriendRegistrationConfig.load() }

    fun requireEmailVerification(): Boolean = provider().requireEmailVerification
}

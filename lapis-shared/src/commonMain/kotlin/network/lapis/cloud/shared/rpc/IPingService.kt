package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService

/**
 * Smallest possible round-trip proof for the Kilua RPC wiring: a single
 * suspending method with no persistence dependency. Kept around as a
 * lightweight liveness check independent of the domain services.
 */
@RpcService
interface IPingService {
    suspend fun ping(message: String? = null): String
}

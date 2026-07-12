package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.rpc.IPingService

class PingService : IPingService {
    override suspend fun ping(message: String?): String = "pong: ${message ?: "(no message)"}"
}

package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.McpAccessStateDto
import network.lapis.cloud.shared.rpc.IMcpAccessService
import network.lapis.cloud.shared.rpc.McpFeatureDisabledException

/**
 * Registered instead of [McpAccessService] whenever `McpConfig.isOperational` is `false` --
 * same "typed exception through the normal RPC protocol instead of an unhandled 500" reasoning as
 * [DisabledAiAssistantService] KDoc.
 */
internal class DisabledMcpAccessService : IMcpAccessService {
    override suspend fun getMcpAccessState(): McpAccessStateDto = throw McpFeatureDisabledException()

    override suspend fun setMcpAccessAllowed(allowed: Boolean): McpAccessStateDto = throw McpFeatureDisabledException()

    override suspend fun revokeConnection(tokenId: String): McpAccessStateDto = throw McpFeatureDisabledException()
}

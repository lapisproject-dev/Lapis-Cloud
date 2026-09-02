package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ApiKeyDto
import network.lapis.cloud.shared.domain.ApiKeyIssueResultDto

/**
 * V1.3.1 "API-Fundament, lesend" -- BOARD/ADMIN key-management surface backing the read-only
 * `/api/v1` REST API (see `network.lapis.cloud.server.routes.PublicApiRoutes`). Every method
 * requires BOARD or ADMIN, same tier `network.lapis.cloud.server.security.RequestContext.isPrivileged`
 * already establishes for comparable organization-wide administrative surfaces (e.g. `IMemberService`
 * .listMembersForAdministration).
 */
@RpcService
interface IApiKeyService {
    /** Role: BOARD/ADMIN. Newest-first. `includeRevoked = false` (default) hides revoked keys -- the common "which keys are currently live" view. */
    suspend fun listApiKeys(includeRevoked: Boolean = false): List<ApiKeyDto>

    /** Role: BOARD/ADMIN. Mints a brand-new key -- see [ApiKeyIssueResultDto] KDoc for why this is the ONLY response that ever carries the raw key. */
    suspend fun issueApiKey(
        label: String,
        expiresAt: LocalDateTime? = null,
    ): ApiKeyIssueResultDto

    /** Role: BOARD/ADMIN. Idempotent -- revoking an already-revoked (or unknown) key throws `NotFoundException`, never a silent no-op success, so a caller always knows whether their call actually changed anything. */
    suspend fun revokeApiKey(id: String): ApiKeyDto

    /**
     * Role: BOARD/ADMIN. Design-Team decision #9 -- "Schlüssel verloren? Einfach neu ausstellen"
     * instead of a re-reveal mechanism (raw keys are never retrievable after issuance, full stop):
     * revokes [id] and, in the SAME atomic step, issues a brand-new key with the SAME label and
     * expiry. Two audit entries (`UPDATE` for the revoke, `CREATE` for the new key) -- see
     * `network.lapis.cloud.server.rpc.ApiKeyService.reissueApiKey` KDoc.
     */
    suspend fun reissueApiKey(id: String): ApiKeyIssueResultDto
}

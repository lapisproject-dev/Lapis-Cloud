package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberSummaryDto

/**
 * Foundation stub — see [network.lapis.cloud.shared.domain.MemberStatus] KDoc. Provides just
 * enough member lookup for the V0.1.5 services (contributions/documents/communication) to
 * resolve display names and for the KVision shell to offer a "current member" picker in lieu
 * of real authentication (V0.1.2-V0.1.4).
 */
@RpcService
interface IMemberService {
    /**
     * Deliberately callable without authentication (it's the bootstrap for the "current
     * member" picker used *before* an `X-Member-Id` is chosen) — but that means it must never
     * return PII or authorization-relevant fields. Returns only id + displayName; use
     * [getCurrentMember] (which does require an authenticated caller) for the full [MemberDto]
     * including email and role.
     */
    suspend fun listMembers(): List<MemberSummaryDto>

    /** Resolves the caller's member context from the `X-Member-Id` request header stand-in. */
    suspend fun getCurrentMember(): MemberDto
}

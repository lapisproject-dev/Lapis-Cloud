package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.BoardMemberMapResponse

/**
 * Welle V1.9.5 "Vorstands-Karte" (second attempt) -- BOARD/ADMIN-only aggregate view of member
 * geographic distribution at postal-code granularity. See [BoardMemberMapResponse] KDoc for the
 * privacy shape this wire contract deliberately holds to: no member id, name, street, city or date
 * of any kind ever crosses this method -- only postal-code-bucketed counts.
 *
 * There is deliberately no self-service variant of this route (unlike e.g.
 * `IMemberFinancialHistoryService`): a member's OWN location is never singled out or highlighted,
 * this is a pure aggregate reporting view -- see `docs/architecture/member-map.adoc`.
 *
 * A single method, deliberately -- the Kilua-RPC-generated route index this wave's tests pin
 * (`/rpc/routeBoardMemberMapServiceManager0`) only stays stable as long as this stays the only
 * method on the interface; see `BoardMemberMapRpcWireTest` KDoc.
 */
@RpcService
interface IBoardMemberMapService {
    suspend fun getMemberMap(): BoardMemberMapResponse
}

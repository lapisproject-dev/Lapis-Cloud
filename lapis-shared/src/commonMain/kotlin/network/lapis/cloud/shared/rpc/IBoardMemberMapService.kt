package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.BoardMemberMapResponse
import network.lapis.cloud.shared.domain.MemberMapPlaceDto

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
 * **V1.9.9 "Ortssuche" -- was a single method, deliberately, no longer.** [searchPlaces] is a SECOND
 * method on this interface, which changes the Kilua-RPC-generated route index `getMemberMap` used to
 * be pinned at (`/rpc/routeBoardMemberMapServiceManager0`, declaration-order-derived) -- this is an
 * INTENTIONAL, accepted break of that older pin, not a regression: `BoardMemberMapRpcWireTest` now
 * pins BOTH methods' indices (`getMemberMap` still 0, `searchPlaces` 1, since [searchPlaces] is
 * declared second). [searchPlaces] carries no member data whatsoever, only GeoNames-derived place
 * facts (see [MemberMapPlaceDto] KDoc) -- it still requires the same BOARD/ADMIN role as
 * [getMemberMap] purely because this whole screen is board-internal event-planning tooling, not
 * because a place name itself is sensitive.
 */
@RpcService
interface IBoardMemberMapService {
    suspend fun getMemberMap(): BoardMemberMapResponse

    /** Ranked place-name/postal-code search over the bundled GeoNames centroid index -- see `network.lapis.cloud.server.membermap.PlaceSearchIndex` for the ranking/grouping/institution-filter rules and `network.lapis.cloud.server.rpc.BoardMemberMapService.searchPlaces` for the role gate and input validation. */
    suspend fun searchPlaces(query: String): List<MemberMapPlaceDto>
}

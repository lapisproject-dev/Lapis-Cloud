package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceBackgroundImageDto

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen". Read-only RPC companion to
 * the byte-carrying `POST/GET/DELETE /api/conference-backgrounds*` routes (see
 * `network.lapis.cloud.server.routes.registerConferenceBackgroundRoutes` KDoc for why the actual
 * file bytes travel over dedicated routes rather than Kilua RPC -- same reasoning as
 * `ITravelExpenseService`'s own receipts).
 */
@RpcService
interface IConferenceBackgroundService {
    /**
     * Only the caller's OWN images, sorted by `created_at` ASC (upload order). No role-based
     * exception -- not even BOARD/ADMIN see another member's images through this call (see
     * `ConferenceBackgroundRoutes` KDoc "IDOR" for the identical posture on the byte-carrying
     * routes).
     */
    suspend fun listMine(): List<ConferenceBackgroundImageDto>
}

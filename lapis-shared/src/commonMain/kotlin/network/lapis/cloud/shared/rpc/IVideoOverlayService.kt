package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.VideoOverlayAdminLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayLibraryDto

/**
 * V1.9.129 "Video-Overlays" -- the library of logos and frames a member may lay over their own camera image. The image bytes never travel
 * through RPC: the upload is `POST /api/video-overlays/{kind}`, the delivery `GET /api/video-overlays/{token}`.
 */
@RpcService
interface IVideoOverlayService {
    /** Role: any member allowed into conferences. `offered = false` (and no items) while the ADMIN has not switched the option on. */
    suspend fun getLibrary(): VideoOverlayLibraryDto

    /** Role: ADMIN. */
    suspend fun getAdminLibrary(): VideoOverlayAdminLibraryDto

    /** Role: ADMIN. [name] is trimmed and must be 1..60 characters without control characters. */
    suspend fun renameItem(
        id: String,
        name: String,
    ): VideoOverlayAdminLibraryDto

    /** Role: ADMIN. Deletes the element and its file; members who had chosen it see an empty corner afterwards. */
    suspend fun deleteItem(id: String): VideoOverlayAdminLibraryDto

    /** Role: ADMIN. Moves the element one place up or down within the library order. */
    suspend fun moveItem(
        id: String,
        up: Boolean,
    ): VideoOverlayAdminLibraryDto
}

package network.lapis.cloud.server.events

import kotlin.uuid.Uuid

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- the pure, side-effect-free constants and
 * URL-shaping logic shared between [network.lapis.cloud.server.rpc.EventService.toEventDto] (the
 * RPC-facing [network.lapis.cloud.shared.domain.EventDto.coverImageUrl]) and
 * [network.lapis.cloud.server.routes.EventCoverRoutes] (upload/remove/serve). Deliberately no DB
 * access and no image processing here -- see [EventCoverImageProcessor]/[EventCoverStorage] for
 * that.
 */
internal object EventCoverPolicy {
    /** Streaming DoS cap -- enforced BOTH via an early `Content-Length` pre-check and while streaming, see `EventCoverRoutes`. */
    const val MAX_UPLOAD_BYTES = 5L * 1024 * 1024

    /** Decompression-bomb guard, checked from the image header BEFORE any pixel is decoded -- see `EventCoverImageProcessor.process`. */
    const val MAX_EDGE_PX = 8000
    const val MAX_PIXELS = 40_000_000L

    /** Minimum acceptable size -- orientation-independent, see design decision "F4" in the wave plan: `max(w,h) >= MIN_LONG_EDGE_PX && min(w,h) >= MIN_SHORT_EDGE_PX`. */
    const val MIN_LONG_EDGE_PX = 800
    const val MIN_SHORT_EDGE_PX = 600

    /** Every stored cover image is re-encoded to at most this long edge, proportionally, never cropped. */
    const val TARGET_LONG_EDGE_PX = 1600
    const val JPEG_QUALITY = 0.85f

    /**
     * Absolute URL for an event's cover image, or `null` if [coverImageId] is `null` (no cover set).
     * The `v` query parameter is the first 8 characters of the cover image's own id -- it changes
     * every time the cover is replaced, so the browser cache is self-invalidating without the
     * server needing to track a separate cache-busting counter (see `EventCoverRoutes`' GET route
     * KDoc for how `v` drives `Cache-Control: immutable`).
     */
    fun coverImageUrl(
        baseUrl: String,
        slug: String,
        coverImageId: Uuid?,
    ): String? = coverImageId?.let { "$baseUrl/veranstaltung/$slug/bild?v=${it.toString().take(8)}" }
}

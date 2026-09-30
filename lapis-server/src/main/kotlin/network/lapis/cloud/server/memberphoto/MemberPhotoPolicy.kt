package network.lapis.cloud.server.memberphoto

import network.lapis.cloud.server.events.EventCoverPolicy

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- server-side constants of the member photo pipeline. The
 * client-visible limits (upload cap, minimum/target edge, consent version) live in
 * [network.lapis.cloud.shared.domain.MemberPhotoRules].
 */
internal object MemberPhotoPolicy {
    /** Decompression-bomb guard, checked from the header BEFORE any pixel is decoded -- shared with the event/article cover pipeline. */
    const val MAX_EDGE_PX = EventCoverPolicy.MAX_EDGE_PX
    const val MAX_PIXELS = EventCoverPolicy.MAX_PIXELS

    const val JPEG_QUALITY = 0.85f

    /** Portrait sources are cropped with this fraction of the surplus height cut from the TOP (faces sit in the upper part of a portrait). */
    const val PORTRAIT_CROP_TOP_FRACTION = 0.25

    /** 32 random bytes = 256 bit, Base64url without padding = exactly 43 characters. */
    const val PUBLIC_TOKEN_BYTES = 32
    val PUBLIC_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

    /** How long an upload waits for a free decode slot before answering "busy". */
    const val DECODE_WAIT_MILLIS = 5_000L

    const val CONTENT_TYPE_JPEG = "image/jpeg"
}

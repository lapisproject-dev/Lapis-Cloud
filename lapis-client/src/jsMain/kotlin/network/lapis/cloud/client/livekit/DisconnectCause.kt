package network.lapis.cloud.client.livekit

/**
 * V1.9.69 -- why a LiveKit room disconnected, as far as this client acts on it. [DuplicateIdentity] means the SAME
 * account joined from a second device and the server evicted this one; every other reason is [Other].
 */
enum class DisconnectCause { DuplicateIdentity, Other }

/**
 * Pure. [reason] is the raw first argument of `RoomEvent.Disconnected` (`undefined`/`null` when absent). Compared
 * numerically against the library's own exported constant -- never via strings.
 */
internal fun disconnectCauseOf(reason: Any?): DisconnectCause =
    if (reason != null && jsTypeOf(reason) == "number" && reason.unsafeCast<Int>() == DisconnectReason.DUPLICATE_IDENTITY) {
        DisconnectCause.DuplicateIdentity
    } else {
        DisconnectCause.Other
    }

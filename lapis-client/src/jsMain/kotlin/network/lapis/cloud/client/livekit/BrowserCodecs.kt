package network.lapis.cloud.client.livekit

import org.khronos.webgl.Uint8Array

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- `TextEncoder`/`TextDecoder` are plain BROWSER
 * globals (`window.TextEncoder`), not exports of the `livekit-client` npm package -- they must NOT
 * live in `LiveKitJs.kt`'s `@file:JsModule("livekit-client")` file, or the compiler would try to
 * resolve them against that module's exports and fail. Used by [LiveKitRoomSession] to encode/decode
 * the JSON chat payload sent over LiveKit's data channel (`LocalParticipant.publishData`/
 * `RoomEvent.DataReceived`) -- see [network.lapis.cloud.shared.domain.ConferenceChatMessage] KDoc for
 * why chat rides the data channel and is never an RPC call.
 */
external class TextEncoder {
    fun encode(input: String): Uint8Array
}

external class TextDecoder(
    label: String = definedExternally,
) {
    fun decode(input: Uint8Array): String
}

package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import kotlin.js.Promise

/**
 * V1.9.97 -- the loading of the two recorded bells (call and blessing). This file is the ONLY place of the encounter client that touches the
 * network for a sound and the ONLY place that decodes one, so the tripwire of the privacy scan can pin it exactly.
 *
 * The two URLs are constants of the same origin (no query, no fragment, no room, no person); the directory is versioned and served
 * `immutable` (see `ClientAssetRoutes.kt`; if the bytes ever change, the directory becomes `encounter-sounds-v2` in all three places:
 * here, in the server route and in `lapis-client/build.gradle.kts`).
 */
internal const val ENCOUNTER_SOUNDS_DIR = "/assets/encounter-sounds-v1"

/** The recording of the call (the bell rung from the pulpit). */
internal const val ENCOUNTER_CALL_BELL_URL = "$ENCOUNTER_SOUNDS_DIR/call-bell.mp3"

/** The recording of the blessing (one strike of a low bell). */
internal const val ENCOUNTER_BLESSING_BELL_URL = "$ENCOUNTER_SOUNDS_DIR/blessing-bell.mp3"

/** Upper limit of a sound file in bytes (the two files are about 48 KB); a larger answer is refused before it is decoded. */
internal const val ENCOUNTER_SOUND_MAX_BYTES = 262_144

/**
 * The loader seam: a `jsTest` hands over a fake. Answers a promise of a decoded `AudioBuffer` of [ctx], or a rejected promise. It is
 * given the audio context (not the other way round) so that neither the request nor the decoding appear in the sound class.
 */
internal fun interface EncounterSoundLoader {
    fun load(
        ctx: dynamic,
        url: String,
    ): Promise<dynamic>
}

/**
 * The real loader: ONE request, same origin, without cookies or credentials, without a referrer, without following a redirect; the answer
 * must be `ok`, of a `audio/...` content type (the catch-all route of the server may answer an unknown path with a page and status 200)
 * and at most [ENCOUNTER_SOUND_MAX_BYTES] long. The promise rejects in every other case; the caller never retries on its own.
 */
internal val browserEncounterSoundLoader =
    EncounterSoundLoader { ctx, url ->
        val init: dynamic = js("{}")
        init.credentials = "omit"
        init.referrerPolicy = "no-referrer"
        init.mode = "same-origin"
        init.redirect = "error"
        init.cache = "default"
        val answer: Promise<dynamic> = window.asDynamic().fetch(url, init).unsafeCast<Promise<dynamic>>()
        answer
            .then { response: dynamic -> readSoundBody(response) }
            .then { body: dynamic -> checkedSoundBytes(body) }
            .then { bytes: dynamic -> ctx.decodeAudioData(bytes) }
            .unsafeCast<Promise<dynamic>>()
    }

private fun readSoundBody(response: dynamic): dynamic {
    if (response.ok != true) throw IllegalStateException("sound refused")
    val type: dynamic = response.headers.get("Content-Type")
    if (type !is String || !type.lowercase().startsWith("audio/")) throw IllegalStateException("sound refused")
    return response.arrayBuffer()
}

private fun checkedSoundBytes(body: dynamic): dynamic {
    val size: Int = (body.byteLength as? Number)?.toInt() ?: 0
    if (size <= 0 || size > ENCOUNTER_SOUND_MAX_BYTES) throw IllegalStateException("sound refused")
    return body
}

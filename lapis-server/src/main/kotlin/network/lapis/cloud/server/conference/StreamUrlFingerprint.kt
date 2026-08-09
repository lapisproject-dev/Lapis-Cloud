package network.lapis.cloud.server.conference

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- reproduces LiveKit's OWN
 * stream-key redaction rule so a [LiveKitStreamInfo.url] echoed back inside
 * `EgressInfo.stream_results` can be matched back to the `conference_stream_target` row that
 * requested it. This exists because two things were verified live against a real LiveKit
 * v1.13.5 + egress v1.13.0 container (`deploy/local/docker-compose.yml`, 2026-08-09):
 *
 * 1. **LiveKit redacts the stream key in every URL it echoes back.** `rtmp://h:1935/live/probekey1`
 *    (a 9-character key) comes back as `rtmp://h:1935/live/{pro...ey1}` -- never the plaintext key,
 *    which is correct and desired (the key must never round-trip through a Twirp response this
 *    codebase logs or stores raw), but it means [LiveKitEgressInfo.streamResults] cannot be matched
 *    back to a destination by comparing the exact URL this client sent.
 * 2. **`stream_results` order does NOT match the request's `urls` order** (two URLs sent as
 *    (key1, key2) came back as (key2, key1), live-verified). Index-based matching is wrong too.
 *
 * The fix: `network.lapis.cloud.server.rpc.ConferenceStreamingService.startStream` (a later wave
 * step) computes [of] for the PLAINTEXT `<rtmpUrl>/<streamKey>` it is about to send, BEFORE sending
 * it, and persists the result in `conference_stream_target.url_fingerprint`. `StreamPoller` (a later
 * wave step) then matches [LiveKitStreamInfo.url] against that stored fingerprint by exact string
 * equality -- no live-reachable LiveKit call is needed to compute it, and the fingerprint is
 * deterministic and reproducible from data this server already has.
 *
 * ## The verified redaction rule
 *
 * Only the LAST path segment of the URL (the stream key -- this codebase always builds
 * `<rtmpUrl>/<streamKey>` from two separate stored columns, see
 * `network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable` KDoc, so the key is
 * always the final path component, never embedded elsewhere) is redacted; everything before the
 * final `/` (scheme, host, port, path prefix) is passed through unchanged. Within that last
 * segment, let `N = min(key.length / 3, 3)` (integer division). The redacted form is
 * `"{" + key.take(N) + "..." + key.takeLast(N) + "}"`.
 *
 * **Verified against the live container across the full length spectrum this rule needs to be
 * correct for** (2026-08-09, same session as the `advanced`/multi-destination probes --
 * `network.lapis.cloud.server.conference.ConferenceStreamingConfig` KDoc and the Wave 3
 * scope-decisions doc "Two gotchas from the live capture"):
 *
 * | key length | real key           | real redacted form (LiveKit response) | `N` |
 * |-----------:|---------------------|----------------------------------------|----:|
 * |          1 | `a`                 | `{...}`                                 |   0 |
 * |          2 | `ab`                | `{...}`                                  |   0 |
 * |          3 | `abc`               | `{a...c}`                                |   1 |
 * |          5 | `ab12x`             | `{a...x}`                                |   1 |
 * |          6 | `abcdef`            | `{ab...ef}`                              |   2 |
 * |          9 | `probekey1`         | `{pro...ey1}`                            |   3 |
 * |         16 | `goodkey123456789`  | `{goo...789}`                            |   3 |
 * |         20 | `abcdefghijklmnopqrst` | `{abc...rst}`                         |   3 |
 *
 * The cap at `N = 3` for any key >= 9 characters (verified up to 20) is the reason [MAX_VISIBLE_CHARS_PER_SIDE]
 * is a hard `3`, not `key.length / 3` alone -- a naive uncapped `floor(length/3)` would have predicted
 * `{abcdefghi...jklmnopqrst}`-style redaction for the 20-character case and silently produced the
 * WRONG fingerprint for any key longer than ~9 characters, which would have made every destination
 * with a realistic (long, high-entropy) stream key unmatchable by [StreamPoller].
 */
object StreamUrlFingerprint {
    private const val MAX_VISIBLE_CHARS_PER_SIDE = 3

    /**
     * Applies the verified redaction rule (see class KDoc) to [plaintextRtmpUrlWithKey] -- the FULL
     * URL this client is about to send to LiveKit, i.e. `<rtmpUrl>/<streamKey>` -- and returns the
     * string [LiveKitStreamInfo.url] is expected to equal once LiveKit echoes it back. Only the
     * segment after the LAST `/` is redacted; everything before it (including that `/`) is returned
     * verbatim. Defensive fallback: a URL with no `/` at all, or one ending in `/` (no key segment),
     * is returned UNCHANGED rather than throwing -- this never happens in practice (a blank stream
     * key is rejected before a URL is ever built, see `ConferenceStreamingService.createDestination`
     * KDoc, a later wave step), but a fingerprinting helper misbehaving on malformed input must never
     * be the reason a stream fails to start.
     */
    fun of(plaintextRtmpUrlWithKey: String): String {
        val lastSlash = plaintextRtmpUrlWithKey.lastIndexOf('/')
        if (lastSlash < 0 || lastSlash == plaintextRtmpUrlWithKey.length - 1) return plaintextRtmpUrlWithKey
        val prefix = plaintextRtmpUrlWithKey.substring(0, lastSlash + 1)
        val key = plaintextRtmpUrlWithKey.substring(lastSlash + 1)
        return prefix + redactKey(key)
    }

    private fun redactKey(key: String): String {
        val visible = minOf(key.length / 3, MAX_VISIBLE_CHARS_PER_SIDE)
        return "{" + key.take(visible) + "..." + key.takeLast(visible) + "}"
    }
}

package network.lapis.cloud.server.conference

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Every expected value below is a REAL response LiveKit itself returned (2026-08-09, against
 * `deploy/local/docker-compose.yml`'s `livekit/egress:v1.13.0`) -- see [StreamUrlFingerprint] KDoc
 * for the full capture table and the reasoning behind the `N = min(length / 3, 3)` rule.
 */
class StreamUrlFingerprintTest :
    FunSpec({
        // real key -> real LiveKit-redacted last path segment (verified live, 2026-08-09)
        val verifiedSamples =
            listOf(
                "a" to "{...}",
                "ab" to "{...}",
                "abc" to "{a...c}",
                "abcd" to "{a...d}",
                "ab12x" to "{a...x}",
                "abcdef" to "{ab...ef}",
                "abcdefg" to "{ab...fg}",
                "abcdefgh" to "{ab...gh}",
                "probekey1" to "{pro...ey1}",
                "abcdefghij" to "{abc...hij}",
                "abcdefghijk" to "{abc...ijk}",
                "abcdefghijkl" to "{abc...jkl}",
                "goodkey123456789" to "{goo...789}",
                "badkeyABCDEFGHIJ" to "{bad...HIJ}",
                "abcdefghijklmnopqrst" to "{abc...rst}",
            )

        test("reproduces LiveKit's own verified redaction across the full observed key-length spectrum") {
            verifiedSamples.forEach { (key, expectedRedacted) ->
                withClue("key '$key' (length ${key.length})") {
                    StreamUrlFingerprint.of("rtmp://host:1935/live/$key") shouldBe "rtmp://host:1935/live/$expectedRedacted"
                }
            }
        }

        test("only the LAST path segment is redacted -- scheme/host/port/path prefix pass through unchanged") {
            StreamUrlFingerprint.of("rtmp://a.rtmp.youtube.com/live2/probekey1") shouldBe
                "rtmp://a.rtmp.youtube.com/live2/{pro...ey1}"
            StreamUrlFingerprint.of("rtmps://peertube.example.org:1935/live/probekey1") shouldBe
                "rtmps://peertube.example.org:1935/live/{pro...ey1}"
        }

        test("two different keys of the same length produce different fingerprints (never a collision for distinct destinations)") {
            val a = StreamUrlFingerprint.of("rtmp://host:1935/live/probekey1")
            val b = StreamUrlFingerprint.of("rtmp://host:1935/live/otherkey2")
            (a == b) shouldBe false
        }

        test("the SAME url always produces the SAME fingerprint (deterministic, no randomness -- unlike SecretBox.seal)") {
            val url = "rtmp://host:1935/live/probekey1"
            StreamUrlFingerprint.of(url) shouldBe StreamUrlFingerprint.of(url)
        }

        test("a URL with no key segment at all (no slash) is returned unchanged rather than throwing") {
            StreamUrlFingerprint.of("not-a-url-at-all") shouldBe "not-a-url-at-all"
        }

        test("a URL ending in a trailing slash (empty key segment) is returned unchanged rather than throwing") {
            StreamUrlFingerprint.of("rtmp://host:1935/live/") shouldBe "rtmp://host:1935/live/"
        }

        test("never leaks the plaintext key into the fingerprint for a realistic, high-entropy key") {
            val key = "rtmpkey_9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c"
            val fingerprint = StreamUrlFingerprint.of("rtmp://a.rtmp.youtube.com/live2/$key")
            (fingerprint.contains(key)) shouldBe false
            fingerprint shouldBe "rtmp://a.rtmp.youtube.com/live2/{" + key.take(3) + "..." + key.takeLast(3) + "}"
        }
    })

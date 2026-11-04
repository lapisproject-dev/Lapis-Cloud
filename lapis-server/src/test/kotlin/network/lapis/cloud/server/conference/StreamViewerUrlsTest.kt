package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.ConferenceStreamKeyHint
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.rpc.ConferenceStreamViewerUrlRejectedException

/** V1.9.274 -- the viewer address check: accepted forms per platform with their normalisation, and every shape that must be refused. */
class StreamViewerUrlsTest :
    FunSpec({
        val yt = ConferenceStreamPlatform.YOUTUBE
        val tw = ConferenceStreamPlatform.TWITCH
        val x = ConferenceStreamPlatform.X
        val generic = ConferenceStreamPlatform.GENERIC_RTMP
        val owncast = ConferenceStreamPlatform.OWNCAST
        val other = ConferenceStreamKeyHint.OTHER
        val fb = ConferenceStreamKeyHint.FACEBOOK
        val xHint = ConferenceStreamKeyHint.X
        val id = "dQw4w9WgXcQ"
        val chat = "https://www.youtube.com/live_chat?is_popout=1&v=$id"

        fun ok(
            raw: String,
            platform: ConferenceStreamPlatform,
            hint: ConferenceStreamKeyHint = other,
        ) = StreamViewerUrls.normalize(raw = raw, platform = platform, keyHint = hint)

        fun rejected(
            raw: String,
            platform: ConferenceStreamPlatform,
            hint: ConferenceStreamKeyHint = other,
        ) {
            val e = shouldThrow<ConferenceStreamViewerUrlRejectedException> { ok(raw, platform, hint) }
            e.message shouldBe "The address was not accepted"
        }

        test("YouTube: every form of a live page is normalised to the chat popout of the video id") {
            listOf(
                "https://www.youtube.com/watch?v=$id",
                "https://youtube.com/watch?v=$id&t=5",
                "https://m.youtube.com/watch?v=$id",
                "https://www.youtube.com/live/$id",
                "https://www.youtube.com/live/$id?feature=share",
                "https://www.youtube.com/shorts/$id",
                "https://www.youtube.com/embed/$id",
                "https://youtu.be/$id",
                "https://youtu.be/$id?si=abc",
                "https://www.youtube.com/live_chat?v=$id",
                "  https://www.youtube.com/watch?v=$id  ",
                "HTTPS://WWW.YOUTUBE.COM/watch?v=$id",
                "https://www.youtube.com:443/watch?v=$id",
                "https://www.youtube.com/watch?v=$id#fragment",
            ).forEach { ok(it, yt) shouldBe chat }
        }

        test("a generic destination with a YouTube host is treated as YouTube") {
            ok("https://youtu.be/$id", generic) shouldBe chat
        }

        test("YouTube: id of 10 or 12 characters, a channel page, a doubled v and a foreign host are refused") {
            rejected("https://www.youtube.com/watch?v=abcdefghij", yt)
            rejected("https://www.youtube.com/watch?v=abcdefghijkl", yt)
            rejected("https://www.youtube.com/channel/UC1234567890123456789012", yt)
            rejected("https://www.youtube.com/watch?v=$id&v=abcdefghijk", yt)
            rejected("https://www.youtube.com/", yt)
            rejected("https://www.youtube.com/watch", yt)
            rejected("https://www.twitch.tv/gamer123", yt)
            rejected("https://example.org/watch?v=$id", yt)
        }

        test("Twitch: the channel page and the popout chat both become the popout chat; reserved paths and bad channel names are refused") {
            ok("https://www.twitch.tv/MyChannel", tw) shouldBe "https://www.twitch.tv/popout/mychannel/chat"
            ok("https://twitch.tv/my_channel", tw) shouldBe "https://www.twitch.tv/popout/my_channel/chat"
            ok("https://www.twitch.tv/popout/mychannel/chat", tw) shouldBe "https://www.twitch.tv/popout/mychannel/chat"
            ok("https://www.twitch.tv/mychannel?ref=x", tw) shouldBe "https://www.twitch.tv/popout/mychannel/chat"
            listOf("directory", "videos", "settings", "popout", "p", "search", "u", "moderator", "team", "login").forEach {
                rejected("https://www.twitch.tv/$it", tw)
            }
            rejected("https://www.twitch.tv/abc", tw)
            rejected("https://www.twitch.tv/${"a".repeat(26)}", tw)
            rejected("https://www.twitch.tv/my-channel", tw)
            rejected("https://www.twitch.tv/a/b/c/d", tw)
            rejected("https://www.youtube.com/watch?v=$id", tw)
        }

        test("X: only X and Twitter hosts, the path is kept, the fragment is dropped") {
            ok("https://x.com/i/broadcasts/1abc", x) shouldBe "https://x.com/i/broadcasts/1abc"
            ok("https://twitter.com/i/broadcasts/1abc#top", x) shouldBe "https://twitter.com/i/broadcasts/1abc"
            ok("https://www.x.com/someone/status/123?s=20", x) shouldBe "https://www.x.com/someone/status/123?s=20"
            rejected("https://www.twitch.tv/mychannel", x)
            rejected("https://example.org/i/broadcasts/1abc", x)
            // The key hint X on a generic destination is just as strict.
            ok("https://x.com/i/broadcasts/1abc", generic, xHint) shouldBe "https://x.com/i/broadcasts/1abc"
            rejected("https://example.org/", generic, xHint)
        }

        test("Facebook ingest on a generic destination: only Facebook hosts (Q7)") {
            ok("https://www.facebook.com/page/videos/123", generic, fb) shouldBe "https://www.facebook.com/page/videos/123"
            ok("https://fb.watch/abc123/", generic, fb) shouldBe "https://fb.watch/abc123/"
            rejected("https://www.youtube.com/watch?v=$id", generic, fb)
            rejected("https://example.org/page", generic, fb)
        }

        test("Owncast, PeerTube and a plain generic destination accept any https host and rebuild the address") {
            ok("https://owncast.example.org/embed/chat/readwrite", owncast) shouldBe "https://owncast.example.org/embed/chat/readwrite"
            ok("https://Stream.Example.ORG:443/w/abc?x=1#frag", ConferenceStreamPlatform.PEERTUBE) shouldBe
                "https://stream.example.org/w/abc?x=1"
            ok("https://www.facebook.com/x", generic) shouldBe "https://www.facebook.com/x"
            ok("https://x.com/a", generic) shouldBe "https://x.com/a"
        }

        test("look-alike hosts are refused (exact comparison, never endsWith or contains)") {
            listOf(
                "https://youtube.com.evil.test/watch?v=$id",
                "https://evilyoutube.com/watch?v=$id",
                "https://www.youtube.com.evil.test/watch?v=$id",
                "https://xn--outube-1ta.com/watch?v=$id",
                "https://уoutube.com/watch?v=$id",
                "https://youtu.be.evil.test/$id",
                "https://notyoutu.be/$id",
            ).forEach { rejected(it, yt) }
            rejected("https://twitch.tv.evil.test/mychannel", tw)
            rejected("https://x.com.evil.test/a", x)
            rejected("https://fakex.com/a", x)
            rejected("https://facebook.com.evil.test/a", generic, fb)
        }

        test("only https: other schemes, no scheme and relative references are refused") {
            rejected("http://www.youtube.com/watch?v=$id", yt)
            rejected("javascript:alert(1)", yt)
            rejected("JavaScript:alert(1)", generic)
            rejected("data:text/html,<script>alert(1)</script>", generic)
            rejected("ftp://example.org/a", generic)
            rejected("//example.org/a", generic)
            rejected("www.youtube.com/watch?v=$id", yt)
            rejected("not a url", generic)
            rejected("", generic)
            rejected("   ", generic)
        }

        test("user info, an @ in the authority, other ports, IP literals and localhost are refused") {
            rejected("https://user:pass@example.org/a", generic)
            rejected("https://user@example.org/a", generic)
            rejected("https://example.org@evil.test/a", generic)
            rejected("https://example.org:8443/a", generic)
            rejected("https://example.org:80/a", generic)
            rejected("https://127.0.0.1/a", generic)
            rejected("https://169.254.169.254/latest", generic)
            rejected("https://10.0.0.5/a", generic)
            rejected("https://[::1]/a", generic)
            rejected("https://[2001:db8::1]/a", generic)
            rejected("https://localhost/a", generic)
            rejected("https://app.localhost/a", generic)
            rejected("https://intranet/a", generic)
        }

        test("spaces, control characters, backslashes, quotes and angle brackets are refused") {
            rejected("https://example.org/a b", generic)
            rejected("https://example.org/a\nb", generic)
            rejected("https://example.org/a\tb", generic)
            rejected("https://example.org/a\u0000b", generic)
            rejected("https://example.org/a%00b", generic)
            rejected("https://example.org/a%0d%0ab", generic)
            rejected("https://example.org/a\\b", generic)
            rejected("https://example.org/a\"b", generic)
            rejected("https://example.org/a'b", generic)
            rejected("https://example.org/a<b>", generic)
            rejected("https://example.org/a`b", generic)
            rejected("https://example.org/ä", generic)
        }

        test("the length limits: 2048 characters of input, 512 characters of result") {
            val pad = "a".repeat(2048)
            rejected("https://example.org/$pad", generic)
            rejected("https://example.org/" + "a".repeat(600), generic)
            // exactly 512 characters of result is fine
            val prefix = "https://example.org/"
            ok(prefix + "a".repeat(512 - prefix.length), generic).length shouldBe 512
            rejected(prefix + "a".repeat(513 - prefix.length), generic)
        }

        test("an international host name is converted to ASCII before the comparison and kept as ASCII otherwise") {
            ok("https://bücher.example.org/live", generic) shouldBe "https://xn--bcher-kva.example.org/live"
        }
    })

package network.lapis.cloud.server.share

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import java.net.URI

class ShareLinksTest :
    FunSpec({
        val url = "https://example.org/veranstaltung/x?lang=en"
        val all = ShareNetwork.entries.toList()

        test("encode: special characters become percent escapes, no plus and no space") {
            val encoded = ShareLinks.encode("Ä ö ü ß & \" ' < > # + % 😀")
            encoded shouldBe "%C3%84%20%C3%B6%20%C3%BC%20%C3%9F%20%26%20%22%20%27%20%3C%20%3E%20%23%20%2B%20%25%20%F0%9F%98%80"
            encoded shouldNotContain "+"
            encoded shouldNotContain " "
        }

        test("mailto uses percent escapes and a blank line before the URL") {
            val mail =
                ShareLinks
                    .targets(
                        url = url,
                        title = "Ein Titel",
                        text = "Ein Text",
                        networks = listOf(ShareNetwork.EMAIL),
                    ).single()
                    .href
            mail shouldContain "subject=Ein%20Titel"
            mail shouldContain "body=Ein%20Text%0A%0A"
            mail shouldNotContain "+"
        }

        test("truncateCodepoints never splits a surrogate pair and counts the ellipsis") {
            val emoji = "😀"
            val value = "a".repeat(198) + emoji + emoji + "b"
            val cut = ShareLinks.truncateCodepoints(value = value, max = ShareLinks.TITLE_MAX_CODEPOINTS)
            cut.codePointCount(0, cut.length) shouldBe ShareLinks.TITLE_MAX_CODEPOINTS
            cut shouldEndWith "…"
            // well-formed UTF-16: no lone surrogate survives the cut
            cut.codePoints().noneMatch { it in 0xD800..0xDFFF } shouldBe true
            val between = ShareLinks.truncateCodepoints(value = "a".repeat(197) + emoji + emoji + "bbbb", max = 200)
            between.codePoints().toArray().size shouldBe between.codePointCount(0, between.length)
            ShareLinks.truncateCodepoints(value = "short", max = 200) shouldBe "short"
        }

        test("normalize removes control and bidi characters and collapses whitespace") {
            ShareLinks.normalize("a\u0000b‮c\n\n d\t e") shouldBe "abc d e"
        }

        test("every href uses a fixed prefix; schemes are only https and mailto") {
            val prefixes =
                listOf(
                    "https://www.facebook.com/sharer/sharer.php?u=",
                    "https://twitter.com/intent/tweet?url=",
                    "https://www.linkedin.com/sharing/share-offsite/?url=",
                    "https://wa.me/?text=",
                    "https://t.me/share/url?url=",
                    "https://www.reddit.com/submit?url=",
                    "mailto:?subject=",
                )
            val targets = ShareLinks.targets(url = url, title = "t", text = "x", networks = all)
            targets shouldHaveSize 8 // native has no href
            for (t in targets.filter { it.network != ShareNetwork.COPY }) {
                prefixes.any { t.href.startsWith(it) } shouldBe true
                (URI(t.href).scheme in setOf("https", "mailto")) shouldBe true
            }
            targets.map { runCatching { URI(it.href).host }.getOrNull() }.filterNotNull().toSet() shouldBe
                setOf("www.facebook.com", "twitter.com", "www.linkedin.com", "wa.me", "t.me", "www.reddit.com", "example.org")
        }

        test("a javascript title only ever appears percent-encoded as a parameter value") {
            val targets = ShareLinks.targets(url = url, title = "javascript:alert(1)", text = "javascript:alert(1)", networks = all)
            targets.forEach { it.href shouldNotContain "javascript:" }
        }

        test("a non-https non-loopback URL is rejected") {
            shouldThrow<IllegalArgumentException> { ShareLinks.targets(url = "javascript:alert(1)", title = "", text = "", networks = all) }
            shouldThrow<IllegalArgumentException> {
                ShareLinks.targets(
                    url = "http://evil.example/x",
                    title = "",
                    text = "",
                    networks = all,
                )
            }
            ShareLinks.targets(
                url = "http://localhost:8080/s/1",
                title = "",
                text = "",
                networks = listOf(ShareNetwork.COPY),
            ) shouldHaveSize
                1
        }
    })

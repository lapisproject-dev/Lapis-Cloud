package network.lapis.cloud.server.share

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShareConfigTest :
    FunSpec({
        fun cfg(
            raw: String?,
            base: String = "https://example.org",
        ) = ShareConfig.load(baseUrl = base, env = { if (it == ShareConfig.ENV) raw else null })

        test("not set: all nine networks") {
            val c = cfg(null)
            c.networks shouldBe ShareNetwork.entries.toSet()
            c.rendersBar shouldBe true
        }

        test("empty and blank: bar off, no script") {
            for (raw in listOf("", "   ")) {
                val c = cfg(raw)
                c.networks shouldBe emptySet()
                c.rendersBar shouldBe false
                c.needsScript shouldBe false
            }
        }

        test("tokens are trimmed, lower-cased and ordered by the fixed order, not the input order") {
            val c = cfg(" X , Facebook,EMAIL")
            c.networks shouldBe setOf(ShareNetwork.X, ShareNetwork.FACEBOOK, ShareNetwork.EMAIL)
            c.orderedNetworks shouldBe listOf(ShareNetwork.FACEBOOK, ShareNetwork.X, ShareNetwork.EMAIL)
        }

        test("unknown names are reported and ignored") {
            val parsed = ShareConfig.parse("myspace,whatsapp")
            parsed.unknown shouldBe listOf("myspace")
            parsed.networks shouldBe setOf(ShareNetwork.WHATSAPP)
        }

        test("log sanitizing: invalid and long tokens never reach the log verbatim") {
            ShareConfig.sanitizeForLog("a\nb") shouldBe "<invalid>"
            ShareConfig.sanitizeForLog("x".repeat(200)) shouldBe "x".repeat(32)
            ShareConfig.sanitizeForLog("my-space_1") shouldBe "my-space_1"
        }

        test("native alone renders no bar") {
            cfg("native").rendersBar shouldBe false
        }

        test("script is needed exactly for copy or native") {
            cfg("facebook").needsScript shouldBe false
            cfg("copy").needsScript shouldBe true
            cfg("facebook,native").needsScript shouldBe true
        }

        test("keywords all and none") {
            cfg("all").networks shouldBe ShareNetwork.entries.toSet()
            cfg("none").networks shouldBe emptySet()
        }

        test("shareable base URLs") {
            for (ok in listOf("https://example.org", "http://localhost:8080", "http://127.0.0.1", "http://[::1]:8080")) {
                ShareConfig.isShareableBaseUrl(ok) shouldBe true
            }
        }

        test("unshareable base URLs switch the bar off") {
            for (bad in listOf(
                "http://evil.example",
                "ftp://x",
                "javascript:alert(1)",
                "https://user@host",
                "https://example.org/path",
                "",
            )) {
                ShareConfig.isShareableBaseUrl(bad) shouldBe false
                cfg(null, base = bad).rendersBar shouldBe false
            }
        }

        test("DISABLED renders nothing") {
            ShareConfig.DISABLED.rendersBar shouldBe false
            ShareConfig.DISABLED.needsScript shouldBe false
        }
    })

package network.lapis.cloud.server.ai.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private fun valid(
    url: String,
    allowPlaintext: Boolean = false,
): String? = AiBaseUrlGuard.validateOrNull(raw = url, allowPlaintextLoopback = allowPlaintext)

class AiBaseUrlGuardTest :
    FunSpec({
        test("https is accepted and normalized without a trailing slash") {
            valid(url = "https://api.anthropic.com") shouldBe "https://api.anthropic.com"
            valid(url = "https://api.anthropic.com/") shouldBe "https://api.anthropic.com"
            valid(url = "https://openrouter.ai/api/") shouldBe "https://openrouter.ai/api"
            valid(url = "https://gateway.example.eu:8443/llm") shouldBe "https://gateway.example.eu:8443/llm"
        }

        test("plain http is rejected") {
            valid(url = "http://api.anthropic.com") shouldBe null
            valid(url = "http://api.anthropic.com", allowPlaintext = true) shouldBe null
        }

        test("plain http is accepted only for loopback and only with the explicit escape flag") {
            valid(url = "http://127.0.0.1:11434") shouldBe null
            valid(url = "http://127.0.0.1:11434", allowPlaintext = true) shouldBe "http://127.0.0.1:11434"
            valid(url = "http://localhost:11434", allowPlaintext = true) shouldBe "http://localhost:11434"
            valid(url = "http://192.168.1.5:11434", allowPlaintext = true) shouldBe null
        }

        test("private, link-local, CGNAT and loopback literals are rejected even over https") {
            listOf(
                "https://10.0.0.1",
                "https://172.16.5.5",
                "https://172.31.255.255",
                "https://192.168.0.10",
                "https://169.254.169.254",
                "https://100.64.0.1",
                "https://127.0.0.1",
                "https://0.0.0.0",
                "https://[::1]",
                "https://[fc00::1]",
                "https://localhost",
                "https://service.localhost",
            ).forEach { valid(url = it) shouldBe null }
        }

        test("legacy numeric host notations are rejected") {
            valid(url = "https://2130706433") shouldBe null
            valid(url = "https://0x7f.1") shouldBe null
            valid(url = "https://1.2.3") shouldBe null
        }

        test("public IPv4 addresses just outside the private ranges are not blocked by the range check") {
            valid(url = "https://172.32.0.1") shouldBe "https://172.32.0.1"
            valid(url = "https://100.63.0.1") shouldBe "https://100.63.0.1"
        }

        test("userinfo, query, fragment, whitespace, control characters and oversize are rejected") {
            valid(url = "https://user:pass@api.anthropic.com") shouldBe null
            valid(url = "https://api.anthropic.com?key=1") shouldBe null
            valid(url = "https://api.anthropic.com#x") shouldBe null
            valid(url = "https://api.anthropic.com/a b") shouldBe null
            valid(url = "https://api.anthropic.com/a\nb") shouldBe null
            valid(url = "https://a.example/" + "x".repeat(250)) shouldBe null
            valid(url = "") shouldBe null
            valid(url = "ftp://api.anthropic.com") shouldBe null
        }

        test("known provider hosts are recognized, others are merely not known") {
            AiBaseUrlGuard.isKnownProviderHost("https://api.anthropic.com") shouldBe true
            AiBaseUrlGuard.isKnownProviderHost("https://gateway.example.eu") shouldBe false
        }

        test("requireAllowedRequestUrl accepts URLs under the pinned base") {
            AiBaseUrlGuard.requireAllowedRequestUrl(
                urlString = "https://api.anthropic.com/v1/messages",
                pinnedBaseUrl = "https://api.anthropic.com",
            )
            AiBaseUrlGuard.requireAllowedRequestUrl(
                urlString = "https://openrouter.ai/api/v1/chat/completions",
                pinnedBaseUrl = "https://openrouter.ai/api",
            )
        }

        test("requireAllowedRequestUrl rejects a host change, a scheme change and a path outside the base, without echoing the URL") {
            val pinned = "https://api.anthropic.com"
            listOf(
                "https://evil.example/v1/messages",
                "http://api.anthropic.com/v1/messages",
                "https://api.anthropic.com.evil.example/v1/messages",
                "https://api.anthropic.com:444/v1/messages",
            ).forEach { url ->
                val e =
                    shouldThrow<IllegalArgumentException> {
                        AiBaseUrlGuard.requireAllowedRequestUrl(
                            urlString = url,
                            pinnedBaseUrl = pinned,
                        )
                    }
                e.message.orEmpty() shouldNotContain "evil"
            }
            shouldThrow<IllegalArgumentException> {
                AiBaseUrlGuard.requireAllowedRequestUrl(
                    urlString = "https://openrouter.ai/other/v1/x",
                    pinnedBaseUrl = "https://openrouter.ai/api",
                )
            }
        }
    })

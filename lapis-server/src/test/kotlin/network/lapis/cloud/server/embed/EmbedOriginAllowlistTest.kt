package network.lapis.cloud.server.embed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- the security-critical core of the whole
 * welle. Every row of the "Kanonisierungs-Vertrag" table in [EmbedOriginAllowlist]'s own KDoc gets
 * its own test case here.
 */
class EmbedOriginAllowlistTest :
    FunSpec({
        fun allowlistOf(vararg origins: String) =
            EmbedOriginAllowlist.parse(raw = origins.joinToString(","), allowInsecure = false).allowlist

        test("suffix attack: https://partei.example.evil.com is NOT allowed by an allowlist for https://partei.example") {
            val allowlist = allowlistOf("https://partei.example")
            allowlist.isAllowed("https://partei.example.evil.com") shouldBe false
        }

        test("prefix attack: https://evilpartei.example is NOT allowed by an allowlist for https://partei.example") {
            val allowlist = allowlistOf("https://partei.example")
            allowlist.isAllowed("https://evilpartei.example") shouldBe false
        }

        test("scheme downgrade: http://partei.example is rejected when allowInsecure=false, even if https://partei.example is allowed") {
            val allowlist = allowlistOf("https://partei.example")
            allowlist.isAllowed("http://partei.example") shouldBe false
        }

        test("scheme downgrade is accepted when the allowlist itself was parsed with allowInsecure=true") {
            val result = EmbedOriginAllowlist.parse(raw = "http://partei.example", allowInsecure = true)
            result.rejected shouldBe emptyList()
            result.allowlist.isAllowed("http://partei.example") shouldBe true
        }

        test("port normalization: https://p.example:443 canonicalizes identically to https://p.example") {
            val allowlist = allowlistOf("https://p.example:443")
            allowlist.canonicalOrigins shouldBe listOf("https://p.example")
            allowlist.isAllowed("https://p.example") shouldBe true
        }

        test("a non-default port is its own distinct origin") {
            val allowlist = allowlistOf("https://p.example")
            allowlist.isAllowed("https://p.example:8443") shouldBe false
        }

        test("null / \"null\" / empty / \"*\" / whitespace-only are all rejected") {
            for (raw in listOf(null, "null", "NULL", "", "   ", "*")) {
                EmbedOriginAllowlist.canonicalizeOrigin(raw = raw ?: "", allowInsecure = false) shouldBe null
            }
        }

        test("userinfo is rejected: https://a@p.example") {
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://a@p.example", allowInsecure = false) shouldBe null
        }

        test("a path, query, or fragment is rejected") {
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://p.example/x", allowInsecure = false) shouldBe null
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://p.example?x=1", allowInsecure = false) shouldBe null
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://p.example#frag", allowInsecure = false) shouldBe null
            // A bare trailing slash IS accepted and canonicalizes to the no-slash form.
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://p.example/", allowInsecure = false) shouldBe "https://p.example"
        }

        test("CR/LF and C0 control characters are rejected (response-splitting hardening)") {
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://p.example\r\nX-Evil: 1", allowInsecure = false) shouldBe null
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://p.example\u0000", allowInsecure = false) shouldBe null
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://p.example\u007F", allowInsecure = false) shouldBe null
        }

        test("host and scheme casing canonicalize identically") {
            EmbedOriginAllowlist.canonicalizeOrigin(raw = "HTTPS://Partei.Example", allowInsecure = false) shouldBe "https://partei.example"
        }

        test("IDN/Punycode host canonicalizes to the same ASCII form as its Punycode equivalent") {
            val fromUnicode = EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://münchen.example", allowInsecure = false)
            val fromPunycode = EmbedOriginAllowlist.canonicalizeOrigin(raw = "https://xn--mnchen-3ya.example", allowInsecure = false)
            fromUnicode shouldBe fromPunycode
            fromUnicode shouldBe "https://xn--mnchen-3ya.example"
        }

        test("an origin longer than MAX_ORIGIN_LENGTH is rejected") {
            val longHost = "a".repeat(EmbedOriginAllowlist.MAX_ORIGIN_LENGTH) + ".example"
            val result = EmbedOriginAllowlist.parse(raw = "https://$longHost", allowInsecure = false)
            result.allowlist.isEmpty() shouldBe true
            result.rejected shouldBe listOf("https://$longHost")
        }

        test("more than MAX_ORIGINS distinct entries: the overflow is rejected") {
            val origins = (1..EmbedOriginAllowlist.MAX_ORIGINS + 3).map { "https://p$it.example" }
            val result = EmbedOriginAllowlist.parse(raw = origins.joinToString(","), allowInsecure = false)
            result.allowlist.size shouldBe EmbedOriginAllowlist.MAX_ORIGINS
            result.rejected.size shouldBe 3
        }

        test("duplicates are deduplicated, first-mention order is preserved, trailing/empty elements are silently skipped") {
            val result =
                EmbedOriginAllowlist.parse(
                    raw = "https://b.example,https://a.example,https://b.example,,https://a.example,",
                    allowInsecure = false,
                )
            result.rejected shouldBe emptyList()
            result.allowlist.canonicalOrigins shouldBe listOf("https://b.example", "https://a.example")
        }

        test("canonicalize always returns the STORED allowlist entry, never the raw request string") {
            val allowlist = allowlistOf("https://p.example")
            allowlist.canonicalize("https://P.Example:443/") shouldBe "https://p.example"
        }

        test("canonicalize returns null for a disallowed origin") {
            val allowlist = allowlistOf("https://p.example")
            allowlist.canonicalize("https://other.example") shouldBe null
            allowlist.canonicalize(null) shouldBe null
        }

        test("EmbedConfig.ENV_ALLOWED_ORIGINS with a trailing comma has no rejected entries") {
            val result = EmbedOriginAllowlist.parse(raw = "https://p.example,", allowInsecure = false)
            result.rejected shouldBe emptyList()
        }
    })

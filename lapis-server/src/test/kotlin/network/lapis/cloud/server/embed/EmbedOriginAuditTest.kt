package network.lapis.cloud.server.embed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** V1.9.261 -- the write-side length guard and the use-side re-validation of the partner origin stored on an embed registration. */
class EmbedOriginAuditTest :
    FunSpec({
        fun config(
            raw: String = "https://partei.example",
            enabled: Boolean = true,
        ) = EmbedConfig(
            enabled = enabled,
            allowlist = EmbedOriginAllowlist.parse(raw = raw, allowInsecure = false).allowlist,
            allowInsecureOrigins = false,
        )

        test("forStorage: null stays null, 255 characters stay, 256 characters are dropped") {
            EmbedOriginAudit.forStorage(null) shouldBe null
            EmbedOriginAudit.forStorage("a".repeat(255)) shouldBe "a".repeat(255)
            EmbedOriginAudit.forStorage("a".repeat(256)) shouldBe null
            EmbedOriginAudit.forStorage("https://partei.example") shouldBe "https://partei.example"
        }

        test("revalidateForUse: a current canonical allowlist entry is returned unchanged") {
            EmbedOriginAudit.revalidateForUse(stored = "https://partei.example", config = config()) shouldBe "https://partei.example"
            EmbedOriginAudit.revalidateForUse(
                stored = "https://partei.example:8443",
                config = config(raw = "https://partei.example:8443,https://www.partei.example"),
            ) shouldBe
                "https://partei.example:8443"
        }

        test("revalidateForUse: a removed partner, a disabled embed and null all yield null") {
            EmbedOriginAudit.revalidateForUse(stored = "https://partei.example", config = config(raw = "https://other.example")) shouldBe
                null
            EmbedOriginAudit.revalidateForUse(stored = "https://partei.example", config = config(enabled = false)) shouldBe null
            EmbedOriginAudit.revalidateForUse(stored = "https://partei.example", config = EmbedConfig.DISABLED) shouldBe null
            EmbedOriginAudit.revalidateForUse(stored = null, config = config()) shouldBe null
        }

        test("revalidateForUse: only exact equality with the canonical entry counts, a non-canonical spelling yields null") {
            listOf("HTTPS://PARTEI.EXAMPLE", "https://partei.example/", "https://partei.example:443", " https://partei.example").forEach {
                EmbedOriginAudit.revalidateForUse(stored = it, config = config()) shouldBe null
            }
        }

        test("revalidateForUse: hostile strings yield null") {
            val hostile =
                listOf(
                    "javascript:alert(1)",
                    "https://partei.example.evil.com",
                    "https://evilpartei.example",
                    "https://user@partei.example",
                    "null",
                    "*",
                    "https://partei.example\r\nSet-Cookie: x",
                    "https://partei.example/<script>",
                    "data:text/html,<script>alert(1)</script>",
                    "https://" + "a".repeat(300) + ".example",
                    "https://pаrtei.example",
                    "",
                )
            hostile.forEach { EmbedOriginAudit.revalidateForUse(stored = it, config = config()) shouldBe null }
        }
    })

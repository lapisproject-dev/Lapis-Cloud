package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/**
 * Exercises [documentMaxUploadBytes] purely through its injected `env` function -- never
 * `System.getenv`, no DB/network/filesystem -- mirroring `WebhookConfigTest`'s idiom for the
 * sibling `coerceAtLeast`-floor pattern. Nutzer-Beschwerde 2026-09-15: the previous hardcoded
 * 25 MiB cap was too low; this covers the new `LAPIS_DOCUMENT_MAX_UPLOAD_MB` override and its
 * default/floor.
 */
class DocumentRoutesUploadLimitTest :
    FunSpec({
        test("unset -> default of 128 MiB") {
            documentMaxUploadBytes(envOf()) shouldBe 128L * 1024 * 1024
        }

        test("set to 256 -> 256 MiB") {
            documentMaxUploadBytes(envOf("LAPIS_DOCUMENT_MAX_UPLOAD_MB" to "256")) shouldBe 256L * 1024 * 1024
        }

        test("set to 1 -> 1 MiB (no artificial ceiling)") {
            documentMaxUploadBytes(envOf("LAPIS_DOCUMENT_MAX_UPLOAD_MB" to "1")) shouldBe 1L * 1024 * 1024
        }

        test("set to 0 -> coerced up to the 1 MiB floor, never a zero/negative cap") {
            documentMaxUploadBytes(envOf("LAPIS_DOCUMENT_MAX_UPLOAD_MB" to "0")) shouldBe 1L * 1024 * 1024
        }

        test("negative -> coerced up to the 1 MiB floor") {
            documentMaxUploadBytes(envOf("LAPIS_DOCUMENT_MAX_UPLOAD_MB" to "-5")) shouldBe 1L * 1024 * 1024
        }

        test("not a number -> falls back to the default, does not throw") {
            documentMaxUploadBytes(envOf("LAPIS_DOCUMENT_MAX_UPLOAD_MB" to "not-a-number")) shouldBe 128L * 1024 * 1024
        }

        test("whitespace around the value is trimmed") {
            documentMaxUploadBytes(envOf("LAPIS_DOCUMENT_MAX_UPLOAD_MB" to "  64  ")) shouldBe 64L * 1024 * 1024
        }
    })

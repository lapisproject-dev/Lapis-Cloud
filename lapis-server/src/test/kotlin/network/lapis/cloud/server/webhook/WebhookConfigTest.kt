package network.lapis.cloud.server.webhook

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.crypto.SecretBox
import java.security.SecureRandom
import java.util.Base64

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/** A syntactically valid `LAPIS_SECRET_ENCRYPTION_KEY` value -- exactly [SecretBox.KEY_SIZE_BYTES] random bytes, base64-encoded. */
private fun validEncryptionKeyBase64(): String =
    Base64.getEncoder().encodeToString(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))

/**
 * Exercises [WebhookConfig.load] purely through its injected `env` function -- never
 * `System.getenv` -- mirroring `ConferenceStreamingConfigTest` for the sibling config class this
 * one shares its `LAPIS_SECRET_ENCRYPTION_KEY` fail-fast gate with. No network, no filesystem, no
 * process spawn, no DB.
 *
 * MINOR fix (code review, Welle V1.3.2, Runde 2) -- this class did not previously exist. Every
 * other env-driven `*Config.load()` in this repository (`BrandConfigTest`, `ConferenceConfigTest`,
 * `ConferenceRecordingConfigTest`, `ConferenceStreamingConfigTest`, `SmtpConfigTest`,
 * `OracleSourceConfigTest`, `PspConfigTest`) has one; [WebhookConfig] was the sole exception.
 * Covers, in particular, the three fail-fast `check {}` blocks and the `coerceAtLeast` floors on
 * [WebhookConfig.pollIntervalSeconds]/[WebhookConfig.retentionDays] added in that same review round
 * -- see [WebhookConfig] companion KDoc "Floor for pollIntervalSeconds/retentionDays".
 */
class WebhookConfigTest :
    FunSpec({
        test("everything unset -> disabled, sane defaults, no failure") {
            val config = WebhookConfig.load(envOf())

            config.enabled.shouldBeFalse()
            config.allowInsecureHttp.shouldBeFalse()
            config.pollIntervalSeconds shouldBe 10L
            config.retentionDays shouldBe 30
            config.maxDeliveriesPerTick shouldBe 50
            config.maxConcurrentDeliveries shouldBe 4
            config.secretEncryptionKey shouldBe null
        }

        // ── LAPIS_WEBHOOKS_ENABLED ────────────────────────────────────────────────────────────

        test("LAPIS_WEBHOOKS_ENABLED=true with a valid key -> enabled, key decoded to KEY_SIZE_BYTES bytes") {
            val config =
                WebhookConfig.load(
                    envOf(
                        "LAPIS_WEBHOOKS_ENABLED" to "true",
                        "LAPIS_SECRET_ENCRYPTION_KEY" to validEncryptionKeyBase64(),
                    ),
                )

            config.enabled.shouldBeTrue()
            config.secretEncryptionKey?.size shouldBe SecretBox.KEY_SIZE_BYTES
        }

        test("LAPIS_WEBHOOKS_ENABLED is case-insensitive") {
            val config =
                WebhookConfig.load(
                    envOf(
                        "LAPIS_WEBHOOKS_ENABLED" to "TRUE",
                        "LAPIS_SECRET_ENCRYPTION_KEY" to validEncryptionKeyBase64(),
                    ),
                )
            config.enabled.shouldBeTrue()
        }

        test("any value other than 'true' -> disabled, no failure, even with no key set") {
            WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ENABLED" to "yes")).enabled.shouldBeFalse()
            WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ENABLED" to "1")).enabled.shouldBeFalse()
            WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ENABLED" to "")).enabled.shouldBeFalse()
        }

        test("a key set while disabled is decoded opportunistically but never validated/required") {
            val config = WebhookConfig.load(envOf("LAPIS_SECRET_ENCRYPTION_KEY" to validEncryptionKeyBase64()))

            config.enabled.shouldBeFalse()
            config.secretEncryptionKey?.size shouldBe SecretBox.KEY_SIZE_BYTES
        }

        // ── LAPIS_WEBHOOKS_ALLOW_INSECURE ─────────────────────────────────────────────────────

        test("LAPIS_WEBHOOKS_ALLOW_INSECURE unset -> false") {
            WebhookConfig.load(envOf()).allowInsecureHttp.shouldBeFalse()
        }

        test("LAPIS_WEBHOOKS_ALLOW_INSECURE=true -> true, case-insensitive") {
            WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ALLOW_INSECURE" to "true")).allowInsecureHttp.shouldBeTrue()
            WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ALLOW_INSECURE" to "TRUE")).allowInsecureHttp.shouldBeTrue()
        }

        test("LAPIS_WEBHOOKS_ALLOW_INSECURE set to anything else -> false") {
            WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ALLOW_INSECURE" to "yes")).allowInsecureHttp.shouldBeFalse()
            WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ALLOW_INSECURE" to "false")).allowInsecureHttp.shouldBeFalse()
        }

        // ── Fail-fast on the encryption key (see WebhookConfig KDoc "S8") ────────────────────────

        test("enabled=true with NO key set fails fast, naming the missing env var") {
            val exception =
                shouldThrow<IllegalStateException> {
                    WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ENABLED" to "true"))
                }
            exception.message shouldContain "LAPIS_SECRET_ENCRYPTION_KEY"
        }

        test("enabled=true with a non-base64 key fails fast") {
            val exception =
                shouldThrow<IllegalStateException> {
                    WebhookConfig.load(
                        envOf(
                            "LAPIS_WEBHOOKS_ENABLED" to "true",
                            "LAPIS_SECRET_ENCRYPTION_KEY" to "not valid base64 at all!!! ###",
                        ),
                    )
                }
            exception.message shouldContain "not valid base64"
        }

        test("enabled=true with a key that decodes to the WRONG byte length fails fast, naming the required length") {
            val tooShort = Base64.getEncoder().encodeToString(ByteArray(16))
            val exception =
                shouldThrow<IllegalStateException> {
                    WebhookConfig.load(
                        envOf(
                            "LAPIS_WEBHOOKS_ENABLED" to "true",
                            "LAPIS_SECRET_ENCRYPTION_KEY" to tooShort,
                        ),
                    )
                }
            exception.message shouldContain "16 bytes"
            exception.message shouldContain "${SecretBox.KEY_SIZE_BYTES}"
        }

        test("the fail-fast exception message never contains the failing key material itself") {
            val suspiciousLookingButInvalidKey = "not-a-real-key-but-should-never-appear-in-any-message"
            val exception =
                shouldThrow<IllegalStateException> {
                    WebhookConfig.load(
                        envOf(
                            "LAPIS_WEBHOOKS_ENABLED" to "true",
                            "LAPIS_SECRET_ENCRYPTION_KEY" to suspiciousLookingButInvalidKey,
                        ),
                    )
                }
            exception.message.shouldNotContain(suspiciousLookingButInvalidKey)
        }

        // ── Numeric floors (review fix, Welle V1.3.2 Runde 2) ─────────────────────────────────────

        test("custom pollIntervalSeconds/retentionDays are parsed") {
            val config =
                WebhookConfig.load(
                    envOf(
                        "LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS" to "20",
                        "LAPIS_WEBHOOK_RETENTION_DAYS" to "60",
                    ),
                )

            config.pollIntervalSeconds shouldBe 20L
            config.retentionDays shouldBe 60
        }

        test("unparseable numeric fields fall back to defaults rather than crashing") {
            val config =
                WebhookConfig.load(
                    envOf(
                        "LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS" to "not-a-number",
                        "LAPIS_WEBHOOK_RETENTION_DAYS" to "also-not-a-number",
                    ),
                )

            config.pollIntervalSeconds shouldBe 10L
            config.retentionDays shouldBe 30
        }

        test("LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS=0 or negative is floored at 1, never a spin loop") {
            WebhookConfig.load(envOf("LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS" to "0")).pollIntervalSeconds shouldBe 1L
            WebhookConfig.load(envOf("LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS" to "-5")).pollIntervalSeconds shouldBe 1L
        }

        test("LAPIS_WEBHOOK_RETENTION_DAYS=0 or negative is floored at 1, never an empty retention window") {
            WebhookConfig.load(envOf("LAPIS_WEBHOOK_RETENTION_DAYS" to "0")).retentionDays shouldBe 1
            WebhookConfig.load(envOf("LAPIS_WEBHOOK_RETENTION_DAYS" to "-3")).retentionDays shouldBe 1
        }

        test("a pollIntervalSeconds/retentionDays value already at or above the floor passes through unchanged") {
            WebhookConfig.load(envOf("LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS" to "1")).pollIntervalSeconds shouldBe 1L
            WebhookConfig.load(envOf("LAPIS_WEBHOOK_RETENTION_DAYS" to "1")).retentionDays shouldBe 1
        }

        // ── toString ──────────────────────────────────────────────────────────────────────────

        test("toString never includes the raw key bytes, unset case") {
            WebhookConfig.load(envOf()).toString() shouldContain "secretEncryptionKey=<unset>"
        }

        test("toString never includes the raw key bytes, set case -- only a byte count") {
            val key = validEncryptionKeyBase64()
            val config =
                WebhookConfig.load(envOf("LAPIS_WEBHOOKS_ENABLED" to "true", "LAPIS_SECRET_ENCRYPTION_KEY" to key))

            config.toString() shouldContain "secretEncryptionKey=<redacted, ${SecretBox.KEY_SIZE_BYTES} bytes>"
            config.toString().shouldNotContain(key)
        }
    })

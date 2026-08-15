package network.lapis.cloud.server.conference

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
 * Exercises [ConferenceStreamingConfig.load] purely through its injected `env` function -- never
 * `System.getenv` (see that class's KDoc for why). No network, no filesystem, no process spawn --
 * see class KDoc "load is string validation ONLY".
 */
class ConferenceStreamingConfigTest :
    FunSpec({
        test("everything unset -> disabled, sane defaults, no failure") {
            val config = ConferenceStreamingConfig.load(envOf())

            config.enabled.shouldBeFalse()
            config.secretEncryptionKey shouldBe null
            config.maxDestinations shouldBe 3
            config.pollIntervalSeconds shouldBe 10L
            config.maxDurationMinutes shouldBe 480L
            config.startupTimeoutSeconds shouldBe 60L
            // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen".
            config.pauseVerifyTimeoutSeconds shouldBe 20L
        }

        test("LAPIS_STREAMING_ENABLED=true with a valid key -> enabled, key decoded to KEY_SIZE_BYTES bytes") {
            val config =
                ConferenceStreamingConfig.load(
                    envOf(
                        "LAPIS_STREAMING_ENABLED" to "true",
                        "LAPIS_SECRET_ENCRYPTION_KEY" to validEncryptionKeyBase64(),
                    ),
                )

            config.enabled.shouldBeTrue()
            config.secretEncryptionKey?.size shouldBe SecretBox.KEY_SIZE_BYTES
        }

        test("LAPIS_STREAMING_ENABLED is case-insensitive") {
            val env =
                envOf(
                    "LAPIS_STREAMING_ENABLED" to "TRUE",
                    "LAPIS_SECRET_ENCRYPTION_KEY" to validEncryptionKeyBase64(),
                )
            ConferenceStreamingConfig.load(env).enabled.shouldBeTrue()
        }

        test("any value other than 'true' -> disabled, no failure, even with no key set") {
            ConferenceStreamingConfig.load(envOf("LAPIS_STREAMING_ENABLED" to "yes")).enabled.shouldBeFalse()
            ConferenceStreamingConfig.load(envOf("LAPIS_STREAMING_ENABLED" to "1")).enabled.shouldBeFalse()
            ConferenceStreamingConfig.load(envOf("LAPIS_STREAMING_ENABLED" to "")).enabled.shouldBeFalse()
        }

        test("a key set while disabled is decoded opportunistically but never validated/required") {
            val config =
                ConferenceStreamingConfig.load(
                    envOf("LAPIS_SECRET_ENCRYPTION_KEY" to validEncryptionKeyBase64()),
                )

            config.enabled.shouldBeFalse()
            config.secretEncryptionKey?.size shouldBe SecretBox.KEY_SIZE_BYTES
        }

        // ── Fail-fast (see class KDoc "Fail-fast on the encryption key") ─────────────────────────

        test("enabled=true with NO key set fails fast, naming the missing env var") {
            val exception =
                shouldThrow<IllegalStateException> {
                    ConferenceStreamingConfig.load(envOf("LAPIS_STREAMING_ENABLED" to "true"))
                }
            exception.message shouldContain "LAPIS_SECRET_ENCRYPTION_KEY"
        }

        test("enabled=true with a non-base64 key fails fast") {
            val exception =
                shouldThrow<IllegalStateException> {
                    ConferenceStreamingConfig.load(
                        envOf(
                            "LAPIS_STREAMING_ENABLED" to "true",
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
                    ConferenceStreamingConfig.load(
                        envOf(
                            "LAPIS_STREAMING_ENABLED" to "true",
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
                    ConferenceStreamingConfig.load(
                        envOf(
                            "LAPIS_STREAMING_ENABLED" to "true",
                            "LAPIS_SECRET_ENCRYPTION_KEY" to suspiciousLookingButInvalidKey,
                        ),
                    )
                }
            exception.message.shouldNotContain(suspiciousLookingButInvalidKey)
        }

        // ── Numeric overrides ─────────────────────────────────────────────────────────────────

        test("custom numeric fields are parsed") {
            val config =
                ConferenceStreamingConfig.load(
                    envOf(
                        "LAPIS_STREAM_MAX_DESTINATIONS" to "5",
                        "LAPIS_STREAM_POLL_INTERVAL_SECONDS" to "20",
                        "LAPIS_STREAM_MAX_DURATION_MINUTES" to "600",
                        "LAPIS_STREAM_STARTUP_TIMEOUT_SECONDS" to "90",
                        "LAPIS_STREAMING_PAUSE_VERIFY_TIMEOUT_SECONDS" to "45",
                    ),
                )

            config.maxDestinations shouldBe 5
            config.pollIntervalSeconds shouldBe 20L
            config.maxDurationMinutes shouldBe 600L
            config.startupTimeoutSeconds shouldBe 90L
            config.pauseVerifyTimeoutSeconds shouldBe 45L
        }

        test("unparseable numeric fields fall back to defaults rather than crashing") {
            val config =
                ConferenceStreamingConfig.load(
                    envOf(
                        "LAPIS_STREAM_MAX_DESTINATIONS" to "not-a-number",
                        "LAPIS_STREAM_POLL_INTERVAL_SECONDS" to "also-not-a-number",
                        "LAPIS_STREAMING_PAUSE_VERIFY_TIMEOUT_SECONDS" to "not-a-number-either",
                    ),
                )

            config.maxDestinations shouldBe 3
            config.pollIntervalSeconds shouldBe 10L
            config.pauseVerifyTimeoutSeconds shouldBe 20L
        }

        // ── toString ──────────────────────────────────────────────────────────────────────────

        test("toString never includes the raw key bytes, unset case") {
            ConferenceStreamingConfig.load(envOf()).toString() shouldContain "secretEncryptionKey=<unset>"
        }

        test("toString never includes the raw key bytes, set case -- only a byte count") {
            val key = validEncryptionKeyBase64()
            val config =
                ConferenceStreamingConfig.load(
                    envOf("LAPIS_STREAMING_ENABLED" to "true", "LAPIS_SECRET_ENCRYPTION_KEY" to key),
                )

            config.toString() shouldContain "secretEncryptionKey=<redacted, ${SecretBox.KEY_SIZE_BYTES} bytes>"
            config.toString().shouldNotContain(key)
        }

        // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen".
        test("toString includes pauseVerifyTimeoutSeconds, default and custom value alike") {
            ConferenceStreamingConfig.load(envOf()).toString() shouldContain "pauseVerifyTimeoutSeconds=20"

            val custom =
                ConferenceStreamingConfig.load(envOf("LAPIS_STREAMING_PAUSE_VERIFY_TIMEOUT_SECONDS" to "45"))
            custom.toString() shouldContain "pauseVerifyTimeoutSeconds=45"
        }
    })

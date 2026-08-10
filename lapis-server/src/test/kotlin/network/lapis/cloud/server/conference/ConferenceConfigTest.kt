package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val VALID_SECRET = "lapis-dev-livekit-secret-32bytes-min!!"
private const val VALID_KEY = "devkey"
private const val VALID_URL = "ws://localhost:7880"

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/**
 * Exercises [ConferenceConfig.load] purely through its injected `env` function -- never
 * `System.getenv` (see that class's KDoc for why). No network, no filesystem, no Docker.
 */
class ConferenceConfigTest :
    FunSpec({
        test("all three of URL/key/secret unset -> disabled, no failure, sane defaults") {
            val config = ConferenceConfig.load(envOf())

            config.enabled.shouldBeFalse()
            config.livekitUrl shouldBe ""
            config.apiKey shouldBe ""
            config.apiSecret shouldBe ""
            config.tokenTtlMinutes shouldBe 240L
            config.guestTokenTtlMinutes shouldBe 15L
            config.maxParticipants shouldBe 25
        }

        test("all three set -> enabled, and LAPIS_LIVEKIT_API_URL derives from ws:// -> http://") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                    ),
                )

            config.enabled.shouldBeTrue()
            config.livekitApiUrl shouldBe "http://localhost:7880"
        }

        test("wss:// derives to https://") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to "wss://livekit.example.org",
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                    ),
                )

            config.livekitApiUrl shouldBe "https://livekit.example.org"
        }

        test("explicit LAPIS_LIVEKIT_API_URL overrides the derived value") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_URL" to "http://livekit-internal:7880",
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                    ),
                )

            config.livekitApiUrl shouldBe "http://livekit-internal:7880"
        }

        test("custom TTL and max-participants are parsed") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                        "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" to "60",
                        "LAPIS_CONFERENCE_MAX_PARTICIPANTS" to "10",
                    ),
                )

            config.tokenTtlMinutes shouldBe 60L
            config.maxParticipants shouldBe 10
        }

        test("unparseable TTL/max-participants fall back to defaults rather than crashing") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                        "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" to "not-a-number",
                        "LAPIS_CONFERENCE_MAX_PARTICIPANTS" to "not-a-number-either",
                    ),
                )

            config.tokenTtlMinutes shouldBe 240L
            config.maxParticipants shouldBe 25
        }

        // ── Guest token TTL (Wave 5 security-audit fix) ─────────────────────

        test("guestTokenTtlMinutes defaults to 15, independently of a custom LAPIS_LIVEKIT_TOKEN_TTL_MINUTES") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                        "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" to "480",
                    ),
                )

            config.tokenTtlMinutes shouldBe 480L
            config.guestTokenTtlMinutes shouldBe 15L
        }

        test("custom LAPIS_LIVEKIT_GUEST_TOKEN_TTL_MINUTES is parsed and left independent of tokenTtlMinutes") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                        "LAPIS_LIVEKIT_GUEST_TOKEN_TTL_MINUTES" to "5",
                    ),
                )

            config.guestTokenTtlMinutes shouldBe 5L
            config.tokenTtlMinutes shouldBe 240L
        }

        test("unparseable LAPIS_LIVEKIT_GUEST_TOKEN_TTL_MINUTES falls back to the default rather than crashing") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                        "LAPIS_LIVEKIT_GUEST_TOKEN_TTL_MINUTES" to "not-a-number",
                    ),
                )

            config.guestTokenTtlMinutes shouldBe 15L
        }

        listOf(
            "only URL" to envOf("LAPIS_LIVEKIT_URL" to VALID_URL),
            "only key" to envOf("LAPIS_LIVEKIT_API_KEY" to VALID_KEY),
            "only secret" to envOf("LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET),
            "URL and key, no secret" to
                envOf("LAPIS_LIVEKIT_URL" to VALID_URL, "LAPIS_LIVEKIT_API_KEY" to VALID_KEY),
        ).forEach { (label, env) ->
            test("partial configuration ($label) fails fast with IllegalStateException") {
                val exception = shouldThrow<IllegalStateException> { ConferenceConfig.load(env) }
                exception.message shouldContain "Incomplete LiveKit configuration"
            }
        }

        test("a secret shorter than 32 bytes fails fast, naming the nimbus 256-bit minimum") {
            val exception =
                shouldThrow<IllegalStateException> {
                    ConferenceConfig.load(
                        envOf(
                            "LAPIS_LIVEKIT_URL" to VALID_URL,
                            "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                            "LAPIS_LIVEKIT_API_SECRET" to "too-short",
                        ),
                    )
                }
            exception.message shouldContain "256 bits"
            // The failing secret value itself must never appear in the exception message.
            exception.message.shouldNotContain("too-short")
        }

        test("a secret of exactly 32 bytes is accepted") {
            val exactly32Bytes = "a".repeat(32)
            exactly32Bytes.toByteArray(Charsets.UTF_8).size shouldBe 32

            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to exactly32Bytes,
                    ),
                )
            config.enabled.shouldBeTrue()
        }

        test("toString never contains the raw apiKey or apiSecret values") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_LIVEKIT_URL" to VALID_URL,
                        "LAPIS_LIVEKIT_API_KEY" to VALID_KEY,
                        "LAPIS_LIVEKIT_API_SECRET" to VALID_SECRET,
                    ),
                )

            config.toString().shouldNotContain(VALID_SECRET)
            config.toString().shouldNotContain(VALID_KEY)
        }

        // ── TURN configuration (audit-round-1 fix) ──────────────────────────

        test("TURN unset -> turnEnabled=false, empty turnUrls, blank turnSharedSecret, no failure") {
            val config = ConferenceConfig.load(envOf())

            config.turnEnabled.shouldBeFalse()
            config.turnUrls shouldBe emptyList()
            config.turnSharedSecret shouldBe ""
        }

        test("TURN fully set -> turnEnabled=true, comma-separated URLs are split and trimmed") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_TURN_URLS" to " turn:127.0.0.1:3478?transport=udp , turn:127.0.0.1:3478?transport=tcp ",
                        "LAPIS_TURN_SHARED_SECRET" to "turn-shared-secret-at-least-32-bytes-long!!",
                    ),
                )

            config.turnEnabled.shouldBeTrue()
            config.turnUrls shouldBe listOf("turn:127.0.0.1:3478?transport=udp", "turn:127.0.0.1:3478?transport=tcp")
            config.turnSharedSecret shouldBe "turn-shared-secret-at-least-32-bytes-long!!"
        }

        test("TURN is independent of the LiveKit trio -- TURN alone (LiveKit unset) is accepted") {
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_TURN_URLS" to "turn:127.0.0.1:3478?transport=udp",
                        "LAPIS_TURN_SHARED_SECRET" to "turn-shared-secret-at-least-32-bytes-long!!",
                    ),
                )

            config.enabled.shouldBeFalse()
            config.turnEnabled.shouldBeTrue()
        }

        listOf(
            "only URLs" to envOf("LAPIS_TURN_URLS" to "turn:127.0.0.1:3478?transport=udp"),
            "only secret" to envOf("LAPIS_TURN_SHARED_SECRET" to "turn-shared-secret-at-least-32-bytes-long!!"),
        ).forEach { (label, env) ->
            test("partial TURN configuration ($label) fails fast with IllegalStateException") {
                val exception = shouldThrow<IllegalStateException> { ConferenceConfig.load(env) }
                exception.message shouldContain "Incomplete TURN configuration"
            }
        }

        test("toString never contains the raw turnSharedSecret value") {
            val turnSecret = "turn-shared-secret-at-least-32-bytes-long!!"
            val config =
                ConferenceConfig.load(
                    envOf(
                        "LAPIS_TURN_URLS" to "turn:127.0.0.1:3478?transport=udp",
                        "LAPIS_TURN_SHARED_SECRET" to turnSecret,
                    ),
                )

            config.toString().shouldNotContain(turnSecret)
        }
    })

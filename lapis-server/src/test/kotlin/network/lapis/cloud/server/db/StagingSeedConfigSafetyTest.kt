package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Exercises [StagingSeedConfig.decide] -- the pure decision core behind [StagingSeedData.seedIfEmpty]
 * -- across every combination of its two gates, WITHOUT touching the real process environment
 * (mirrors [network.lapis.cloud.server.security.AuthTestModeSafetyTest]'s own "pure core" pattern).
 */
class StagingSeedConfigSafetyTest :
    FunSpec({
        fun envOf(vararg pairs: Pair<String, String?>): (String) -> String? = { key -> pairs.toMap()[key] }

        test("mode=true + strong password -- Enabled, password passed through unchanged") {
            val decision =
                StagingSeedConfig.decide(
                    envOf(
                        StagingSeedConfig.ENV_STAGING_MODE to "true",
                        StagingSeedConfig.ENV_SEED_PASSWORD to "ein-starkes-testpasswort",
                    ),
                )
            decision.shouldBeInstanceOf<StagingSeedDecision.Enabled>()
            (decision as StagingSeedDecision.Enabled).seedPassword shouldBe "ein-starkes-testpasswort"
        }

        test("mode not exactly the literal 'true' -- always Disabled, even with a strong password set") {
            listOf(null, "", "false", "True", "TRUE", "1", "yes").forEach { modeValue ->
                val env =
                    if (modeValue == null) {
                        envOf(StagingSeedConfig.ENV_SEED_PASSWORD to "ein-starkes-testpasswort")
                    } else {
                        envOf(
                            StagingSeedConfig.ENV_STAGING_MODE to modeValue,
                            StagingSeedConfig.ENV_SEED_PASSWORD to "ein-starkes-testpasswort",
                        )
                    }
                StagingSeedConfig.decide(env) shouldBe StagingSeedDecision.Disabled
            }
        }

        test("mode=true, password missing -- Refused, message names the env var") {
            val decision = StagingSeedConfig.decide(envOf(StagingSeedConfig.ENV_STAGING_MODE to "true"))
            decision.shouldBeInstanceOf<StagingSeedDecision.Refused>()
            (decision as StagingSeedDecision.Refused).reason shouldContain StagingSeedConfig.ENV_SEED_PASSWORD
        }

        test("mode=true, password blank -- Refused") {
            val decision =
                StagingSeedConfig.decide(
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to "true", StagingSeedConfig.ENV_SEED_PASSWORD to "   "),
                )
            decision.shouldBeInstanceOf<StagingSeedDecision.Refused>()
        }

        test("mode=true, password too short (11 chars) -- Refused") {
            val decision =
                StagingSeedConfig.decide(
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to "true", StagingSeedConfig.ENV_SEED_PASSWORD to "a".repeat(11)),
                )
            decision.shouldBeInstanceOf<StagingSeedDecision.Refused>()
        }

        test("mode=true, password too long (129 chars) -- Refused") {
            val decision =
                StagingSeedConfig.decide(
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to "true", StagingSeedConfig.ENV_SEED_PASSWORD to "a".repeat(129)),
                )
            decision.shouldBeInstanceOf<StagingSeedDecision.Refused>()
        }

        test("mode=true, password equals DevSeedData.DEMO_PASSWORD -- Refused (never a real staging login)") {
            val decision =
                StagingSeedConfig.decide(
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to "true", StagingSeedConfig.ENV_SEED_PASSWORD to DevSeedData.DEMO_PASSWORD),
                )
            decision.shouldBeInstanceOf<StagingSeedDecision.Refused>()
        }

        test("PdV/ELB env simulation without either staging var -- Disabled") {
            val env =
                envOf(
                    "LAPIS_DB_URL" to "jdbc:postgresql://127.0.0.1:5432/lapiscloud",
                    "LAPIS_DB_USER" to "lapiscloud",
                    "LAPIS_PUBLIC_BASE_URL" to "https://parteidervernunft.de",
                    "LAPIS_LIVEKIT_URL" to "wss://video.parteidervernunft.de",
                    "LAPIS_TURN_SECRET" to "some-secret",
                )
            StagingSeedConfig.decide(env) shouldBe StagingSeedDecision.Disabled
        }

        test("PdV/ELB env simulation with mode=true but no seed password -- Refused, never Enabled") {
            val env =
                envOf(
                    "LAPIS_DB_URL" to "jdbc:postgresql://127.0.0.1:5432/lapiscloud",
                    StagingSeedConfig.ENV_STAGING_MODE to "true",
                )
            StagingSeedConfig.decide(env).shouldBeInstanceOf<StagingSeedDecision.Refused>()
        }

        test("exhaustive cross-product -- exactly one of 25 (mode x password) cells yields Enabled") {
            val modes = listOf(null, "", "false", "true", "TRUE")
            val passwords = listOf(null, "", "kurz", DevSeedData.DEMO_PASSWORD, "ein-starkes-testpasswort")
            var enabledCount = 0
            modes.forEach { mode ->
                passwords.forEach { password ->
                    val env: (String) -> String? = { key ->
                        when (key) {
                            StagingSeedConfig.ENV_STAGING_MODE -> mode
                            StagingSeedConfig.ENV_SEED_PASSWORD -> password
                            else -> null
                        }
                    }
                    if (StagingSeedConfig.decide(env) is StagingSeedDecision.Enabled) enabledCount++
                }
            }
            enabledCount shouldBe 1
        }
    })

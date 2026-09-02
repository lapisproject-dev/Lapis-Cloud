package network.lapis.cloud.server.embed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Welle V1.4.1a "Öffentliche Website-Integration" -- [EmbedConfig.load]'s fail-fast contract. */
class EmbedConfigTest :
    FunSpec({
        fun envOf(vararg pairs: Pair<String, String>): (String) -> String? = { key -> pairs.toMap()[key] }

        test("enabled=true with an empty allowlist throws, message names LAPIS_EMBED_ALLOWED_ORIGINS") {
            val exception =
                shouldThrow<IllegalStateException> {
                    EmbedConfig.load(envOf(EmbedConfig.ENV_ENABLED to "true"))
                }
            exception.message shouldContain "LAPIS_EMBED_ALLOWED_ORIGINS"
        }

        test("enabled=true with one invalid entry throws and names the entry verbatim") {
            val exception =
                shouldThrow<IllegalStateException> {
                    EmbedConfig.load(
                        envOf(
                            EmbedConfig.ENV_ENABLED to "true",
                            EmbedConfig.ENV_ALLOWED_ORIGINS to "https://ok.example,not-a-valid-origin",
                        ),
                    )
                }
            exception.message shouldContain "not-a-valid-origin"
        }

        test("enabled=false with a broken allowlist does NOT throw") {
            val config =
                EmbedConfig.load(
                    envOf(
                        EmbedConfig.ENV_ENABLED to "false",
                        EmbedConfig.ENV_ALLOWED_ORIGINS to "not-a-valid-origin",
                    ),
                )
            config.enabled shouldBe false
        }

        test("enabled unset defaults to false, no allowlist required") {
            val config = EmbedConfig.load(envOf())
            config.enabled shouldBe false
            config.allowlist.isEmpty() shouldBe true
        }

        test("LAPIS_EMBED_ALLOW_INSECURE=false is set but NOT wirksam (still false-effect), only \"true\" is") {
            val config =
                EmbedConfig.load(
                    envOf(
                        EmbedConfig.ENV_ENABLED to "true",
                        EmbedConfig.ENV_ALLOWED_ORIGINS to "https://ok.example",
                        EmbedConfig.ENV_ALLOW_INSECURE to "false",
                    ),
                )
            config.allowInsecureOrigins shouldBe false
        }

        test("LAPIS_EMBED_ALLOW_INSECURE=true allows http:// origins") {
            val config =
                EmbedConfig.load(
                    envOf(
                        EmbedConfig.ENV_ENABLED to "true",
                        EmbedConfig.ENV_ALLOWED_ORIGINS to "http://dev.example",
                        EmbedConfig.ENV_ALLOW_INSECURE to "true",
                    ),
                )
            config.allowInsecureOrigins shouldBe true
            config.allowlist.isAllowed("http://dev.example") shouldBe true
        }

        test("LAPIS_EMBED_ALLOW_INSECURE unset -> false, no WARN-triggering side effect on the returned config") {
            val config =
                EmbedConfig.load(
                    envOf(
                        EmbedConfig.ENV_ENABLED to "true",
                        EmbedConfig.ENV_ALLOWED_ORIGINS to "https://ok.example",
                    ),
                )
            config.allowInsecureOrigins shouldBe false
        }

        test("DISABLED constant is enabled=false with an empty allowlist") {
            EmbedConfig.DISABLED.enabled shouldBe false
            EmbedConfig.DISABLED.allowlist.isEmpty() shouldBe true
        }
    })

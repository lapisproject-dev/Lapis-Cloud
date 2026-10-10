package network.lapis.cloud.server.mcp.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/** Welle V1.9.100 -- the three `create_article_draft` quota variables: defaults, valid override, rejected values land in `invalid`. */
class McpConfigArticleQuotaTest :
    FunSpec({
        test("defaults are 3 per token and hour, 10 per member and day, 500 per server and day") {
            val config = McpConfig.load { null }
            config.articleDraftPerTokenPerHour shouldBe 3
            config.articleDraftPerMemberPerDay shouldBe 10
            config.articleDraftPerServerPerDay shouldBe 500
            config.invalid shouldNotContain McpConfig.ENV_WRITE_RATE_ARTICLE_TOKEN_HOUR
            McpConfig.disabled().articleDraftPerServerPerDay shouldBe 500
        }

        test("valid values are taken over") {
            val env =
                mapOf(
                    McpConfig.ENV_WRITE_RATE_ARTICLE_TOKEN_HOUR to "7",
                    McpConfig.ENV_WRITE_RATE_ARTICLE_MEMBER_DAY to "21",
                    McpConfig.ENV_WRITE_RATE_ARTICLE_SERVER_DAY to "900",
                )
            val config = McpConfig.load { env[it] }
            config.articleDraftPerTokenPerHour shouldBe 7
            config.articleDraftPerMemberPerDay shouldBe 21
            config.articleDraftPerServerPerDay shouldBe 900
        }

        test("out-of-range and non-numeric values fall back to the default and are reported by name") {
            val env =
                mapOf(
                    McpConfig.ENV_WRITE_RATE_ARTICLE_TOKEN_HOUR to "0",
                    McpConfig.ENV_WRITE_RATE_ARTICLE_MEMBER_DAY to "abc",
                    McpConfig.ENV_WRITE_RATE_ARTICLE_SERVER_DAY to "1000001",
                )
            val config = McpConfig.load { env[it] }
            config.articleDraftPerTokenPerHour shouldBe 3
            config.articleDraftPerMemberPerDay shouldBe 10
            config.articleDraftPerServerPerDay shouldBe 500
            config.invalid shouldContain McpConfig.ENV_WRITE_RATE_ARTICLE_TOKEN_HOUR
            config.invalid shouldContain McpConfig.ENV_WRITE_RATE_ARTICLE_MEMBER_DAY
            config.invalid shouldContain McpConfig.ENV_WRITE_RATE_ARTICLE_SERVER_DAY
        }
    })

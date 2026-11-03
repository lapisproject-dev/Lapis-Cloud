package network.lapis.cloud.server.keycloak

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

private const val CLIENT_SECRET = "kc-provision-secret-value-123"
private const val SECRET_GROUP = "geheime-gruppe-xyz"

private fun load(vararg pairs: Pair<String, String>): KeycloakConfig {
    val env = pairs.toMap()
    return KeycloakConfig.load { env[it] }
}

private fun operational(vararg extra: Pair<String, String>): KeycloakConfig =
    load(
        KeycloakConfig.ENV_ENABLED to "true",
        KeycloakConfig.ENV_ISSUER_URL to "https://keycloak.example.org/realms/lapis",
        KeycloakConfig.ENV_CLIENT_ID to "lapis-cloud",
        KeycloakConfig.ENV_CLIENT_SECRET to CLIENT_SECRET,
        *extra,
    )

private val logger = KotlinLogging.logger {}

/** Welle V1.9.73 -- the configuration of the Keycloak provisioning / profile sync and its startup checks. */
class KeycloakProvisioningConfigTest :
    FunSpec({
        test("defaults: everything off, claim groups, 10 per hour, no group") {
            val config = operational()
            config.autoProvision shouldBe false
            config.syncProfile shouldBe false
            config.provisionClaim shouldBe "groups"
            config.provisionGroup shouldBe null
            config.provisionRatePerHour shouldBe 10
            config.orphanedOptions shouldBe emptyList()
            config.isOperational shouldBe true
        }

        test("AUTO_PROVISION with a group is operational and carries the values") {
            val config =
                operational(
                    KeycloakConfig.ENV_AUTO_PROVISION to "true",
                    KeycloakConfig.ENV_PROVISION_GROUP to "/apolda",
                    KeycloakConfig.ENV_PROVISION_CLAIM to "my_groups",
                    KeycloakConfig.ENV_PROVISION_RATE_PER_HOUR to "25",
                    KeycloakConfig.ENV_SYNC_PROFILE to "TRUE",
                )
            config.isOperational shouldBe true
            config.autoProvision shouldBe true
            config.syncProfile shouldBe true
            config.provisionGroup shouldBe "/apolda"
            config.provisionClaim shouldBe "my_groups"
            config.provisionRatePerHour shouldBe 25
        }

        test("AUTO_PROVISION without a group is operational (open mode, V1.9.264) and invalid stays empty") {
            val config = operational(KeycloakConfig.ENV_AUTO_PROVISION to "true")
            config.isOperational shouldBe true
            config.provisionGroup shouldBe null
            config.provisionWithoutGroup shouldBe true
            config.invalid shouldNotContain KeycloakConfig.ENV_PROVISION_GROUP
        }

        test("a blank group counts as no group (V1.9.264)") {
            val config = operational(KeycloakConfig.ENV_AUTO_PROVISION to "true", KeycloakConfig.ENV_PROVISION_GROUP to "   ")
            config.provisionGroup shouldBe null
            config.isOperational shouldBe true
            config.provisionWithoutGroup shouldBe true
        }

        test("with a group the open mode is off") {
            operational(
                KeycloakConfig.ENV_AUTO_PROVISION to "true",
                KeycloakConfig.ENV_PROVISION_GROUP to "apolda",
            ).provisionWithoutGroup shouldBe false
            operational().provisionWithoutGroup shouldBe false
        }

        test("a group is only mandatory while AUTO_PROVISION is on") {
            operational(KeycloakConfig.ENV_SYNC_PROFILE to "true").isOperational shouldBe true
        }

        test("invalid claim names are rejected: dot, space, leading digit, empty-after-trim stays default, too long") {
            listOf("realm_access.roles", "my groups", "1groups", "gr/oups", "a".repeat(65)).forEach { bad ->
                val config = operational(KeycloakConfig.ENV_PROVISION_CLAIM to bad)
                config.invalid shouldContain KeycloakConfig.ENV_PROVISION_CLAIM
                config.isOperational shouldBe false
            }
            operational(KeycloakConfig.ENV_PROVISION_CLAIM to "  ").provisionClaim shouldBe "groups"
            operational(KeycloakConfig.ENV_PROVISION_CLAIM to "urn:groups-2_x").provisionClaim shouldBe "urn:groups-2_x"
        }

        test("invalid groups are rejected: nested path, only slash, control character, too long") {
            listOf("/parent/child", "/", "a/b", "grp\u0007x", "g".repeat(256), "\u007f", "a\tb").forEach { bad ->
                val config = operational(KeycloakConfig.ENV_AUTO_PROVISION to "true", KeycloakConfig.ENV_PROVISION_GROUP to bad)
                config.invalid shouldContain KeycloakConfig.ENV_PROVISION_GROUP
                config.isOperational shouldBe false
                // V1.9.264: a set but invalid group never degrades to the open mode
                config.provisionWithoutGroup shouldBe false
                val e = shouldThrow<IllegalStateException> { KeycloakStartupCheck.verifyAndLog(config = config, logger = logger) }
                e.message!! shouldContain KeycloakConfig.ENV_PROVISION_GROUP
            }
            // an invalid group stays invalid even while AUTO_PROVISION is off (unchanged behaviour)
            operational(KeycloakConfig.ENV_PROVISION_GROUP to "/a/b").invalid shouldContain KeycloakConfig.ENV_PROVISION_GROUP
            operational(
                KeycloakConfig.ENV_AUTO_PROVISION to "true",
                KeycloakConfig.ENV_PROVISION_GROUP to "  apolda  ",
            ).provisionGroup shouldBe
                "apolda"
        }

        test("the rate must be an integer from 1 to 1000") {
            listOf("0", "-1", "1001", "abc", "1.5").forEach { bad ->
                val config = operational(KeycloakConfig.ENV_PROVISION_RATE_PER_HOUR to bad)
                config.invalid shouldContain KeycloakConfig.ENV_PROVISION_RATE_PER_HOUR
                config.isOperational shouldBe false
            }
            operational(KeycloakConfig.ENV_PROVISION_RATE_PER_HOUR to "1").provisionRatePerHour shouldBe 1
            operational(KeycloakConfig.ENV_PROVISION_RATE_PER_HOUR to "1000").provisionRatePerHour shouldBe 1000
        }

        test("the options are read even when ENABLED is missing and reported as orphaned") {
            val config = load(KeycloakConfig.ENV_AUTO_PROVISION to "true", KeycloakConfig.ENV_SYNC_PROFILE to "true")
            config.enabled shouldBe false
            config.orphanedOptions shouldBe listOf(KeycloakConfig.ENV_AUTO_PROVISION, KeycloakConfig.ENV_SYNC_PROFILE)
            load(KeycloakConfig.ENV_SYNC_PROFILE to "false").orphanedOptions shouldBe emptyList()
        }

        test("startup check: an orphaned option refuses to start and names only variable names") {
            val config = load(KeycloakConfig.ENV_AUTO_PROVISION to "true", KeycloakConfig.ENV_PROVISION_GROUP to SECRET_GROUP)
            val e = shouldThrow<IllegalStateException> { KeycloakStartupCheck.verifyAndLog(config = config, logger = logger) }
            e.message!! shouldContain "LAPIS_KEYCLOAK_AUTO_PROVISION gesetzt, aber LAPIS_KEYCLOAK_ENABLED nicht"
            e.message!! shouldNotContain SECRET_GROUP
        }

        test("startup check: AUTO_PROVISION together with the regional chapter rule refuses to start") {
            val config = operational(KeycloakConfig.ENV_AUTO_PROVISION to "true", KeycloakConfig.ENV_PROVISION_GROUP to SECRET_GROUP)
            val e =
                shouldThrow<IllegalStateException> {
                    KeycloakStartupCheck.verifyAndLog(config = config, logger = logger, regionalChapterEnforcementEnabled = true)
                }
            e.message!! shouldContain "LAPIS_REGIONAL_CHAPTER_ENFORCEMENT_ENABLED"
            e.message!! shouldNotContain SECRET_GROUP
            // without the chapter rule the same configuration starts
            KeycloakStartupCheck.verifyAndLog(config = config, logger = logger, regionalChapterEnforcementEnabled = false)
        }

        test("startup check: the chapter rule alone is fine, and so is AUTO_PROVISION off") {
            KeycloakStartupCheck.verifyAndLog(config = operational(), logger = logger, regionalChapterEnforcementEnabled = true)
        }

        test("startup check: AUTO_PROVISION without a group starts (V1.9.264)") {
            KeycloakStartupCheck.verifyAndLog(config = operational(KeycloakConfig.ENV_AUTO_PROVISION to "true"), logger = logger)
        }

        test("startup check: AUTO_PROVISION without a group together with the regional chapter rule refuses to start") {
            shouldThrow<IllegalStateException> {
                KeycloakStartupCheck.verifyAndLog(
                    config = operational(KeycloakConfig.ENV_AUTO_PROVISION to "true"),
                    logger = logger,
                    regionalChapterEnforcementEnabled = true,
                )
            }.message!! shouldContain "LAPIS_REGIONAL_CHAPTER_ENFORCEMENT_ENABLED"
        }

        test("startup check: orphaned AUTO_PROVISION without ENABLED and without a group still refuses") {
            shouldThrow<IllegalStateException> {
                KeycloakStartupCheck.verifyAndLog(config = load(KeycloakConfig.ENV_AUTO_PROVISION to "true"), logger = logger)
            }.message!! shouldContain "LAPIS_KEYCLOAK_ENABLED nicht"
        }

        test("the open-mode warning appears exactly once, only without a group, and leaks nothing") {
            fun warnings(config: KeycloakConfig): List<String> {
                val name = "test.keycloak.startup.${Uuid.random()}"
                val logbackLogger = LoggerFactory.getLogger(name) as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                logbackLogger.addAppender(appender)
                logbackLogger.level = Level.TRACE
                try {
                    KeycloakStartupCheck.verifyAndLog(config = config, logger = KotlinLogging.logger(name))
                } finally {
                    logbackLogger.detachAppender(appender)
                }
                return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            }

            val open = warnings(operational(KeycloakConfig.ENV_AUTO_PROVISION to "true"))
            open.count { "OHNE LAPIS_KEYCLOAK_PROVISION_GROUP" in it } shouldBe 1
            open.count { "ersetzt KEINE Aufnahmepruefung" in it } shouldBe 1
            open.forEach {
                it shouldNotContain CLIENT_SECRET
                it shouldNotContain "keycloak.example.org"
            }

            val grouped = warnings(operational(KeycloakConfig.ENV_AUTO_PROVISION to "true", KeycloakConfig.ENV_PROVISION_GROUP to "apolda"))
            grouped.count { "OHNE LAPIS_KEYCLOAK_PROVISION_GROUP" in it } shouldBe 0
            grouped.count { "ersetzt KEINE Aufnahmepruefung" in it } shouldBe 1

            val off = warnings(operational())
            off.count { "OHNE LAPIS_KEYCLOAK_PROVISION_GROUP" in it } shouldBe 0
            off.count { "ersetzt KEINE Aufnahmepruefung" in it } shouldBe 0
        }

        test("toString never shows the client secret and shows the new fields") {
            val text = operational(KeycloakConfig.ENV_AUTO_PROVISION to "true", KeycloakConfig.ENV_PROVISION_GROUP to "apolda").toString()
            text shouldNotContain CLIENT_SECRET
            text shouldContain "autoProvision=true"
            text shouldContain "provisionGroup=apolda"
        }
    })

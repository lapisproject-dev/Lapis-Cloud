package network.lapis.cloud.server.keycloak

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val SECRET = "kc-very-secret-client-secret-123"

private fun load(vararg pairs: Pair<String, String>): KeycloakConfig {
    val env = pairs.toMap()
    return KeycloakConfig.load { env[it] }
}

private fun operational(vararg extra: Pair<String, String>): KeycloakConfig =
    load(
        KeycloakConfig.ENV_ENABLED to "true",
        KeycloakConfig.ENV_ISSUER_URL to "https://keycloak.example.org/realms/lapis",
        KeycloakConfig.ENV_CLIENT_ID to "lapis-cloud",
        KeycloakConfig.ENV_CLIENT_SECRET to SECRET,
        *extra,
    )

class KeycloakConfigTest :
    FunSpec({
        test("feature is off when nothing is set") {
            val config = load()
            config.enabled shouldBe false
            config.isOperational shouldBe false
            config.invalid shouldBe emptyList()
        }

        test("feature is on only for exactly true, case-insensitive and trimmed") {
            load(KeycloakConfig.ENV_ENABLED to "true").enabled shouldBe true
            load(KeycloakConfig.ENV_ENABLED to " TRUE ").enabled shouldBe true
            listOf("1", "yes", "on", "false", "truee", "").forEach {
                load(KeycloakConfig.ENV_ENABLED to it).enabled shouldBe false
            }
        }

        test("a complete profile is operational") {
            val config = operational()
            config.isOperational shouldBe true
            config.issuerUrl shouldBe "https://keycloak.example.org/realms/lapis"
            config.clientId shouldBe "lapis-cloud"
            config.invalid shouldBe emptyList()
        }

        test("enabled but a single required part missing keeps the feature non-operational (three separate cases)") {
            val full =
                mapOf(
                    KeycloakConfig.ENV_ENABLED to "true",
                    KeycloakConfig.ENV_ISSUER_URL to "https://keycloak.example.org/realms/lapis",
                    KeycloakConfig.ENV_CLIENT_ID to "lapis-cloud",
                    KeycloakConfig.ENV_CLIENT_SECRET to SECRET,
                )
            listOf(KeycloakConfig.ENV_ISSUER_URL, KeycloakConfig.ENV_CLIENT_ID, KeycloakConfig.ENV_CLIENT_SECRET).forEach { missing ->
                val env = full - missing
                val config = KeycloakConfig.load { env[it] }
                config.isOperational shouldBe false
                config.invalid shouldContain missing
            }
        }

        test("default scopes are 'openid email profile'") {
            operational().scopes shouldBe "openid email profile"
        }

        test("a custom scopes value that omits openid gets it force-added") {
            operational(KeycloakConfig.ENV_SCOPES to "email profile groups").scopes shouldBe "openid email profile groups"
        }

        test("a custom scopes value that already contains openid is kept as-is (normalized whitespace)") {
            operational(KeycloakConfig.ENV_SCOPES to "  openid   email  ").scopes shouldBe "openid email"
        }

        test("requireVerifiedEmail defaults to true and can be turned off") {
            operational().requireVerifiedEmail shouldBe true
            operational(KeycloakConfig.ENV_REQUIRE_VERIFIED_EMAIL to "false").requireVerifiedEmail shouldBe false
        }

        test("emergencyAdminLoginEnabled defaults to true and false parses fine (no validation error here)") {
            operational().emergencyAdminLoginEnabled shouldBe true
            val config = operational(KeycloakConfig.ENV_EMERGENCY_ADMIN_LOGIN_ENABLED to "false")
            config.emergencyAdminLoginEnabled shouldBe false
            config.isOperational shouldBe true
            config.invalid shouldBe emptyList()
        }

        test("rpInitiatedLogout defaults to true and can be turned off") {
            operational().rpInitiatedLogout shouldBe true
            operational(KeycloakConfig.ENV_RP_INITIATED_LOGOUT to "false").rpInitiatedLogout shouldBe false
        }

        test("allowPrivateIssuerHost and allowPlaintextIssuerUrl default to false") {
            val config = operational()
            config.allowPrivateIssuerHost shouldBe false
            config.allowPlaintextIssuerUrl shouldBe false
        }

        test("cache seconds default correctly") {
            val config = operational()
            config.discoveryCacheSeconds shouldBe KeycloakConfig.DEFAULT_DISCOVERY_CACHE_SECONDS
            config.jwksCacheSeconds shouldBe KeycloakConfig.DEFAULT_JWKS_CACHE_SECONDS
        }

        test("valid cache seconds within range are taken over") {
            val config =
                operational(
                    KeycloakConfig.ENV_DISCOVERY_CACHE_SECONDS to "120",
                    KeycloakConfig.ENV_JWKS_CACHE_SECONDS to "600",
                )
            config.discoveryCacheSeconds shouldBe 120
            config.jwksCacheSeconds shouldBe 600
            config.invalid shouldNotContain KeycloakConfig.ENV_DISCOVERY_CACHE_SECONDS
            config.invalid shouldNotContain KeycloakConfig.ENV_JWKS_CACHE_SECONDS
        }

        test("out-of-range cache seconds fall back to the default and are named in invalid (below range, above range, unparseable)") {
            listOf("59", "86401", "abc").forEach { bad ->
                val config = operational(KeycloakConfig.ENV_DISCOVERY_CACHE_SECONDS to bad)
                config.discoveryCacheSeconds shouldBe KeycloakConfig.DEFAULT_DISCOVERY_CACHE_SECONDS
                config.invalid shouldContain KeycloakConfig.ENV_DISCOVERY_CACHE_SECONDS
            }
            listOf("59", "86401", "abc").forEach { bad ->
                val config = operational(KeycloakConfig.ENV_JWKS_CACHE_SECONDS to bad)
                config.jwksCacheSeconds shouldBe KeycloakConfig.DEFAULT_JWKS_CACHE_SECONDS
                config.invalid shouldContain KeycloakConfig.ENV_JWKS_CACHE_SECONDS
            }
        }

        test("boundary cache seconds (60 and 86400) are accepted") {
            operational(KeycloakConfig.ENV_DISCOVERY_CACHE_SECONDS to "60").discoveryCacheSeconds shouldBe 60
            operational(KeycloakConfig.ENV_DISCOVERY_CACHE_SECONDS to "86400").discoveryCacheSeconds shouldBe 86400
        }

        test("a control character in clientId or clientSecret rejects the value") {
            val badClientId = operational(KeycloakConfig.ENV_CLIENT_ID to "lapis\ncloud")
            badClientId.isOperational shouldBe false
            badClientId.invalid shouldContain KeycloakConfig.ENV_CLIENT_ID

            val badSecret = operational(KeycloakConfig.ENV_CLIENT_SECRET to "secret\u0000value")
            badSecret.isOperational shouldBe false
            badSecret.invalid shouldContain KeycloakConfig.ENV_CLIENT_SECRET
        }

        test("a plain-http issuer URL without the opt-in disables the feature") {
            val config = operational(KeycloakConfig.ENV_ISSUER_URL to "http://keycloak.example.org/realms/lapis")
            config.isOperational shouldBe false
            config.invalid shouldContain KeycloakConfig.ENV_ISSUER_URL
        }

        test("a private-network issuer host without the opt-in disables the feature, with it enabled it works") {
            val withoutOptIn = operational(KeycloakConfig.ENV_ISSUER_URL to "https://192.168.1.10/realms/lapis")
            withoutOptIn.isOperational shouldBe false
            withoutOptIn.invalid shouldContain KeycloakConfig.ENV_ISSUER_URL

            val withOptIn =
                operational(
                    KeycloakConfig.ENV_ISSUER_URL to "https://192.168.1.10/realms/lapis",
                    KeycloakConfig.ENV_ALLOW_PRIVATE_ISSUER_HOST to "true",
                )
            withOptIn.isOperational shouldBe true
            withOptIn.issuerUrl shouldBe "https://192.168.1.10/realms/lapis"
        }

        test("load never throws for hostile input") {
            val config =
                load(
                    KeycloakConfig.ENV_ENABLED to "true",
                    KeycloakConfig.ENV_ISSUER_URL to "::::not a url::::",
                    KeycloakConfig.ENV_CLIENT_ID to " ",
                    KeycloakConfig.ENV_CLIENT_SECRET to "  ",
                    KeycloakConfig.ENV_SCOPES to "x".repeat(10_000),
                    KeycloakConfig.ENV_DISCOVERY_CACHE_SECONDS to "9".repeat(500),
                )
            config.isOperational shouldBe false
        }

        test("toString never contains the real client secret, not even partially") {
            val text = operational().toString()
            text shouldNotContain SECRET
            text shouldNotContain SECRET.take(8)
            text shouldContain "clientSecret=<redacted>"
        }
    })

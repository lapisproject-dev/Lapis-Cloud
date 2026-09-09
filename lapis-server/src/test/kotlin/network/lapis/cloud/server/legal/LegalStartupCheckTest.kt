package network.lapis.cloud.server.legal

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/**
 * Exercises [LegalStartupCheck.check] -- no repo-wide log-capture harness exists for
 * `kotlin-logging` (see `network.lapis.cloud.server.branding.BrandingStartupCheckTest`, which has
 * the same limitation for [network.lapis.cloud.server.branding.BrandingStartupCheck]), so this test
 * follows that same, simpler established pattern: verify the never-throwing contract across every
 * shape of [LegalConfig] rather than asserting on captured log text. [LegalConfig] and
 * [LegalHtmlTest]/[LegalRoutesTest] independently cover the "never leak a rejected value" guarantee
 * for the HTML surface; [LegalStartupCheck.check]'s own KDoc documents the same discipline for its
 * log lines by construction (it only ever interpolates [LegalConfig.invalid]/[LegalConfig
 * .missingMandatory] -- variable NAMES, string literals from [LegalConfig.Companion] -- never a
 * field value).
 */
class LegalStartupCheckTest :
    FunSpec({
        test("empty config (nothing set) -> never throws") {
            shouldNotThrowAny { LegalStartupCheck.check(LegalConfig.load(envOf())) }
        }

        test("fully complete, fully valid config -> never throws") {
            val env =
                envOf(
                    LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
                    LegalConfig.ENV_STREET to "Musterweg 1",
                    LegalConfig.ENV_POSTAL_CODE to "12345",
                    LegalConfig.ENV_CITY to "Musterstadt",
                    LegalConfig.ENV_COUNTRY to "Deutschland",
                    LegalConfig.ENV_CONTACT_EMAIL to "info@example.org",
                    LegalConfig.ENV_REPRESENTATIVE to "Max Muster",
                )
            shouldNotThrowAny { LegalStartupCheck.check(LegalConfig.load(env)) }
        }

        test("mandatory fields rejected (invalid, not just missing) -> never throws") {
            val env =
                envOf(
                    LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
                    LegalConfig.ENV_STREET to "Musterweg 1\r\nX-Injected: 1",
                    LegalConfig.ENV_CONTACT_EMAIL to "not-an-email",
                )
            shouldNotThrowAny { LegalStartupCheck.check(LegalConfig.load(env)) }
        }

        test("partial config (some mandatory, some optional missing) -> never throws") {
            val env =
                envOf(
                    LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
                    LegalConfig.ENV_DPO_CONTACT to "Erika Muster",
                )
            shouldNotThrowAny { LegalStartupCheck.check(LegalConfig.load(env)) }
        }
    })

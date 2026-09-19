package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Security mitigation 2026-09-19 -- [mobileWebviewBridgeEnabled] must be opt-in: only the exact
 * value `true` (any case, surrounding whitespace ignored) enables the session bridge.
 */
class MobileWebviewBridgeKillSwitchTest :
    FunSpec({
        fun enabled(value: String?): Boolean =
            mobileWebviewBridgeEnabled { key ->
                if (key == "LAPIS_MOBILE_WEBVIEW_BRIDGE_ENABLED") value else null
            }

        test("unset variable keeps the bridge off") {
            enabled(null) shouldBe false
        }

        test("empty and blank values keep the bridge off") {
            enabled("") shouldBe false
            enabled("   ") shouldBe false
        }

        test("only the value true enables the bridge, case-insensitive and trimmed") {
            enabled("true") shouldBe true
            enabled("TRUE") shouldBe true
            enabled(" True ") shouldBe true
        }

        test("truthy-looking but different values keep the bridge off") {
            enabled("1") shouldBe false
            enabled("yes") shouldBe false
            enabled("on") shouldBe false
            enabled("false") shouldBe false
            enabled("truee") shouldBe false
        }

        test("an unrelated variable does not enable the bridge") {
            mobileWebviewBridgeEnabled { key -> if (key == "OTHER") "true" else null } shouldBe false
        }
    })

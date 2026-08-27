package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PublicRankingKind

/**
 * Mirrors [ConferenceGuestConsentDisclaimerTest]'s own pinning style for the structurally-similar
 * [ConferenceGuestConsentDisclaimer] -- pure, no database. See [PublicRankingConsentDisclaimer]
 * class KDoc "Two disclaimers, never one shared text".
 */
class PublicRankingConsentDisclaimerTest :
    FunSpec({
        test("each kind's disclaimer has exactly two key points, each appearing verbatim in the composed text") {
            for (kind in PublicRankingKind.entries) {
                val disclaimer = PublicRankingConsentDisclaimer.of(kind)
                disclaimer.keyPoints.size shouldBe 2
                disclaimer.keyPoints.forEach { point -> (disclaimer.text.contains(point)) shouldBe true }
                disclaimer.text.contains(disclaimer.headline) shouldBe true
            }
        }

        test("the two kinds have completely separate wording, version, and hash") {
            val ltr = PublicRankingConsentDisclaimer.of(PublicRankingKind.LTR_HOLDINGS)
            val donations = PublicRankingConsentDisclaimer.of(PublicRankingKind.DONATIONS)
            (ltr.text == donations.text) shouldBe false
            (ltr.sha256 == donations.sha256) shouldBe false
            (ltr.headline == donations.headline) shouldBe false
        }

        test("sha256 is deterministic and computed over version + text") {
            val disclaimer = PublicRankingConsentDisclaimer.of(PublicRankingKind.LTR_HOLDINGS)
            val recomputed = PublicRankingConsentDisclaimer.of(PublicRankingKind.LTR_HOLDINGS)
            disclaimer.sha256 shouldBe recomputed.sha256
            disclaimer.sha256.length shouldBe 64
        }

        test("matches: exact version+hash succeeds, wrong version fails, wrong hash fails, malformed hash never throws") {
            val disclaimer = PublicRankingConsentDisclaimer.of(PublicRankingKind.DONATIONS)
            disclaimer.matches(version = disclaimer.version, sha256 = disclaimer.sha256) shouldBe true
            disclaimer.matches(version = "not-a-real-version", sha256 = disclaimer.sha256) shouldBe false
            disclaimer.matches(version = disclaimer.version, sha256 = "0".repeat(64)) shouldBe false
            disclaimer.matches(version = disclaimer.version, sha256 = "not-hex-at-all") shouldBe false
            disclaimer.matches(version = disclaimer.version, sha256 = "") shouldBe false
        }
    })

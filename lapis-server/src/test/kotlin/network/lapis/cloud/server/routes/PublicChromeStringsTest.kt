package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Welle V1.4.7 -- independent of `test/public-chrome-strings-coverage`'s own `PublicChromeTest`
 * (that branch is not merged, see [PublicUiStrings] KDoc's own note on it). Field-by-field, not via
 * `kotlin-reflect` (not a declared dependency of this module) -- every existing field of
 * [PublicUiStrings] is checked directly; a future field addition should extend this list.
 */
class PublicChromeStringsTest :
    FunSpec({
        test("C1: STRINGS has exactly one entry per PublicLanguage") {
            PublicChrome.STRINGS.keys.shouldContainExactlyInAnyOrder(PublicLanguage.entries)
        }

        test("C2: no field of any PublicUiStrings instance is blank") {
            PublicChrome.STRINGS.forEach { (lang, s) ->
                val fields =
                    listOf(
                        "navHome" to s.navHome,
                        "navTransparency" to s.navTransparency,
                        "navSocial" to s.navSocial,
                        "login" to s.login,
                        "register" to s.register,
                        "languageLabel" to s.languageLabel,
                        "skipToContent" to s.skipToContent,
                        "operatedBy" to s.operatedBy,
                        "legalImprint" to s.legalImprint,
                        "legalPrivacy" to s.legalPrivacy,
                        "legalGermanOnlyNote" to s.legalGermanOnlyNote,
                        "tagline" to s.tagline,
                        "statMembers" to s.statMembers,
                        "statLtr" to s.statLtr,
                        "statPosts" to s.statPosts,
                        "linkTransparency" to s.linkTransparency,
                        "socialH1" to s.socialH1,
                        "noPosts" to s.noPosts,
                        "prevPage" to s.prevPage,
                        "nextPage" to s.nextPage,
                        "backToTimeline" to s.backToTimeline,
                        "replies" to s.replies,
                        "moreRepliesHidden" to s.moreRepliesHidden,
                        "transparencyH1" to s.transparencyH1,
                        "jumpStats" to s.jumpStats,
                        "jumpBoard" to s.jumpBoard,
                        "jumpPosts" to s.jumpPosts,
                        "jumpLtr" to s.jumpLtr,
                        "jumpDonors" to s.jumpDonors,
                        "boardEmpty" to s.boardEmpty,
                        "topPosts" to s.topPosts,
                        "topLtrHolders" to s.topLtrHolders,
                        "topDonorsFormat" to s.topDonorsFormat,
                        "allPosts" to s.allPosts,
                        "committeeRoleChair" to s.committeeRoleChair,
                        "committeeRoleDeputyChair" to s.committeeRoleDeputyChair,
                        "committeeRoleSecretary" to s.committeeRoleSecretary,
                        "committeeRoleAssessor" to s.committeeRoleAssessor,
                        "committeeRoleMember" to s.committeeRoleMember,
                    )
                fields.forEach { (name, value) ->
                    withClue("PublicUiStrings.$name for $lang") {
                        value.isBlank() shouldBe false
                    }
                }
            }
        }

        test("C3: legalImprint/legalPrivacy/legalGermanOnlyNote are set for every language") {
            PublicChrome.STRINGS.forEach { (lang, strings) ->
                withClue("$lang") {
                    strings.legalImprint.isBlank() shouldBe false
                    strings.legalPrivacy.isBlank() shouldBe false
                    strings.legalGermanOnlyNote.isBlank() shouldBe false
                }
            }
        }
    })

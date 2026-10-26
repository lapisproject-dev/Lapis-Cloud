package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.GalleryLockReason
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Soziales Netzwerk, Welle V1.1.1 -- covers the pure, DOM-independent label/color table in
 * `SocialNetworkScreen.kt` ([socialPostVisibilityLabel]/[socialPostVisibilityColor]), same scope
 * posture as [CrowdfundingScreenTest]/[AuctionScreenTest]. No rendering harness exists in this
 * module (see [GovernanceAuthzUiTest] KDoc), so the DOM-building `renderSocialNetworkScreen` etc.
 * are out of scope here, same as every other screen's `*ScreenTest.kt`.
 *
 * **Welle V1.1.4** adds coverage for [SocialComposerVisibility], the pure filter/default logic
 * extracted from `renderComposeForm` -- mirrors the server's `requireVisibilityAllowedFor`
 * invariant client-side.
 */
class SocialNetworkScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun socialPostVisibilityLabel_isNonBlankForEveryValue() {
        SocialPostVisibility.entries.forEach { visibility ->
            assertTrue(
                socialPostVisibilityLabel(visibility).isNotBlank(),
                "expected a non-blank label for $visibility",
            )
        }
    }

    @Test
    fun socialPostVisibilityColor_isARealBootstrapHueForEveryValue() {
        SocialPostVisibility.entries.forEach { visibility ->
            val color = socialPostVisibilityColor(visibility)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $visibility, got \"$color\"")
        }
    }

    @Test
    fun allowedVisibilities_forFriend_excludesMembersOnly() {
        val allowed = SocialComposerVisibility.allowedVisibilities(MemberStatus.FRIEND)
        assertFalse(SocialPostVisibility.MEMBERS_ONLY in allowed, "FRIEND must not see MEMBERS_ONLY as an option")
        assertTrue(SocialPostVisibility.MEMBERS_AND_EXTERNAL in allowed)
        assertTrue(SocialPostVisibility.PUBLIC in allowed)
    }

    @Test
    fun allowedVisibilities_forGuest_alsoExcludesMembersOnly() {
        val allowed = SocialComposerVisibility.allowedVisibilities(MemberStatus.GUEST)
        assertFalse(SocialPostVisibility.MEMBERS_ONLY in allowed, "GUEST is NON_MEMBER too, must not see MEMBERS_ONLY")
    }

    @Test
    fun allowedVisibilities_forActive_includesAllThreeStages() {
        val allowed = SocialComposerVisibility.allowedVisibilities(MemberStatus.ACTIVE)
        assertEquals(SocialPostVisibility.entries.toList(), allowed)
    }

    @Test
    fun allowedVisibilities_forNullStatus_includesAllThreeStages() {
        // null == kein bekannter Session-Status (defensiver Fallback) -- verhaelt sich wie
        // ORGANIZATION_MEMBER, nicht wie NON_MEMBER, damit kein authentifizierter Aufrufer
        // faelschlich eingeschraenkt wird, waehrend die Session noch laedt.
        val allowed = SocialComposerVisibility.allowedVisibilities(null)
        assertEquals(SocialPostVisibility.entries.toList(), allowed)
    }

    @Test
    fun defaultVisibility_forFriend_isMembersAndExternal_neverPublic() {
        assertEquals(SocialPostVisibility.MEMBERS_AND_EXTERNAL, SocialComposerVisibility.defaultVisibility(MemberStatus.FRIEND))
    }

    @Test
    fun defaultVisibility_forActive_remainsMembersOnly() {
        assertEquals(SocialPostVisibility.MEMBERS_ONLY, SocialComposerVisibility.defaultVisibility(MemberStatus.ACTIVE))
    }

    @Test
    fun defaultVisibility_isAlwaysWithinAllowedVisibilities() {
        MemberStatus.entries.forEach { status ->
            val default = SocialComposerVisibility.defaultVisibility(status)
            val allowed = SocialComposerVisibility.allowedVisibilities(status)
            assertTrue(default in allowed, "default $default for $status must be one of the allowed options $allowed")
        }
    }

    // ── Welle V1.1.5 -- SocialModerationUi pure predicates ────────────────────────────────────

    @Test
    fun canRemove_trueOnlyForBoardOrAdmin() {
        assertTrue(SocialModerationUi.canRemove(AccountRole.BOARD))
        assertTrue(SocialModerationUi.canRemove(AccountRole.ADMIN))
        assertFalse(SocialModerationUi.canRemove(AccountRole.MEMBER))
        assertFalse(SocialModerationUi.canRemove(AccountRole.TREASURER))
        assertFalse(SocialModerationUi.canRemove(null))
    }

    @Test
    fun canReport_falseForTheAuthor_regardlessOfState() {
        assertFalse(SocialModerationUi.canReport(isAuthor = true, state = SocialPostState.VISIBLE))
    }

    @Test
    fun canReport_falseForANonVisiblePost() {
        assertFalse(SocialModerationUi.canReport(isAuthor = false, state = SocialPostState.HIDDEN_BY_AUTHOR))
        assertFalse(SocialModerationUi.canReport(isAuthor = false, state = SocialPostState.REMOVED_LEGAL))
    }

    @Test
    fun canReport_trueForANonAuthorOnAVisiblePost() {
        assertTrue(SocialModerationUi.canReport(isAuthor = false, state = SocialPostState.VISIBLE))
    }

    @Test
    fun removalNoticeHeadline_differsByOwnPostFlag_bothNonBlank() {
        val own = SocialModerationUi.removalNoticeHeadline(isOwnPost = true)
        val other = SocialModerationUi.removalNoticeHeadline(isOwnPost = false)
        assertTrue(own.isNotBlank())
        assertTrue(other.isNotBlank())
    }

    // ── V1.9.223: pictures in posts ─────────────────────────────────────────────────────

    @Test
    fun socialPostImageTexts_nameTheWindow_theDailyLimit_andTheSocialMaximum() {
        assertTrue(galleryLockText(GalleryLockReason.SOCIAL_POST_PUBLISHED).contains("nachgereicht"))
        assertTrue(galleryLockedUploadText(AttachedImageOwnerType.SOCIAL_POST).contains("Zeit zum Nachreichen ist abgelaufen"))
        assertTrue(
            galleryUploadErrorText(GalleryUploadErrorCode.DAILY_LIMIT_REACHED, AttachedImageOwnerType.SOCIAL_POST)
                .contains("${GalleryRules.SOCIAL_POST_IMAGES_PER_AUTHOR_PER_DAY} Bilder"),
        )
        assertEquals(
            "Höchstens 4 Bilder.",
            galleryUploadErrorText(GalleryUploadErrorCode.GALLERY_FULL, AttachedImageOwnerType.SOCIAL_POST),
        )
        assertTrue(
            galleryUploadErrorText(GalleryUploadErrorCode.GALLERY_FULL, AttachedImageOwnerType.AUCTION_LISTING)
                .contains("Höchstens 10 Bilder."),
        )
    }

    @Test
    fun socialPostGalleryRules_haveNoCoverNoOrderNoCaption_andFourPictures() {
        val social = AttachedImageOwnerType.SOCIAL_POST
        assertFalse(GalleryRules.supportsCoverAndOrder(social))
        assertFalse(GalleryRules.supportsCaption(social))
        assertEquals(4, GalleryRules.maxImagesFor(social))
        AttachedImageOwnerType.entries.filter { it != social }.forEach {
            assertTrue(GalleryRules.supportsCoverAndOrder(it) && GalleryRules.supportsCaption(it))
            assertEquals(10, GalleryRules.maxImagesFor(it))
        }
    }
}

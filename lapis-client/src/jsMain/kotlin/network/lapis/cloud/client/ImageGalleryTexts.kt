package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.GalleryLockReason
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode

/**
 * V1.9.222 -- every text of the gallery that depends on a server code. The HTTP route answers with CODES only; the person reads these
 * texts.
 * The lock texts name the cause ("after the first bid", "after the board's decision"); both end with the same consequence.
 */
internal fun galleryLockText(reason: GalleryLockReason): String =
    when (reason) {
        GalleryLockReason.AUCTION_HAS_BIDS -> tr("Nach dem ersten Gebot können Bilder nicht mehr geändert werden.")
        GalleryLockReason.AUCTION_CLOSED -> tr("Die Auktion ist beendet; Bilder können nicht mehr geändert werden.")
        GalleryLockReason.CROWDFUNDING_DECIDED -> tr("Nach der Entscheidung des Vorstands können Bilder nicht mehr geändert werden.")
        GalleryLockReason.SOCIAL_POST_PUBLISHED -> tr("Bilder können nach dem Veröffentlichen nur kurz nachgereicht werden.")
    }

/** The lock text for an upload refused with GALLERY_LOCKED (the cause is known from the owner type). */
internal fun galleryLockedUploadText(ownerType: AttachedImageOwnerType): String =
    when (ownerType) {
        AttachedImageOwnerType.AUCTION_LISTING -> galleryLockText(GalleryLockReason.AUCTION_HAS_BIDS)
        AttachedImageOwnerType.CROWDFUNDING_PROJECT -> galleryLockText(GalleryLockReason.CROWDFUNDING_DECIDED)
        AttachedImageOwnerType.SOCIAL_POST -> tr("Die Zeit zum Nachreichen ist abgelaufen. Der Beitrag bleibt ohne dieses Bild.")
    }

internal fun galleryUploadErrorText(
    code: GalleryUploadErrorCode?,
    ownerType: AttachedImageOwnerType,
): String =
    when (code) {
        GalleryUploadErrorCode.IMAGE_TOO_LARGE -> tr("Datei ist größer als 5 MB.")
        GalleryUploadErrorCode.IMAGE_TYPE_NOT_ALLOWED -> tr("Nur JPEG, PNG oder WebP.")
        GalleryUploadErrorCode.IMAGE_UNREADABLE -> tr("Bild konnte nicht gelesen werden.")
        GalleryUploadErrorCode.IMAGE_DIMENSIONS_EXCEEDED -> tr("Das Bild ist zu groß (maximal 8000 Pixel Kantenlänge).")
        GalleryUploadErrorCode.GALLERY_FULL ->
            if (ownerType == AttachedImageOwnerType.SOCIAL_POST) {
                gettext("Höchstens %1 Bilder.", GalleryRules.SOCIAL_POST_MAX_IMAGES)
            } else {
                tr("Höchstens 10 Bilder.")
            }
        GalleryUploadErrorCode.GALLERY_BYTES_EXCEEDED -> tr("Die Bilder dieses Eintrags sind zusammen zu groß (maximal 30 MB).")
        GalleryUploadErrorCode.GALLERY_LOCKED -> galleryLockedUploadText(ownerType)
        GalleryUploadErrorCode.DAILY_LIMIT_REACHED ->
            gettext(
                "Sie haben heute schon %1 Bilder hochgeladen. Bitte versuchen Sie es morgen wieder.",
                GalleryRules.SOCIAL_POST_IMAGES_PER_AUTHOR_PER_DAY,
            )
        GalleryUploadErrorCode.RATE_LIMITED -> tr("Zu viele Uploads – bitte später erneut versuchen.")
        GalleryUploadErrorCode.SERVER_BUSY -> tr("Der Server ist ausgelastet – bitte erneut versuchen.")
        GalleryUploadErrorCode.NOT_FOUND -> tr("Nicht gefunden.")
        GalleryUploadErrorCode.FORBIDDEN -> tr("Keine Berechtigung für diese Aktion.")
        GalleryUploadErrorCode.INVALID_ORIGIN, null -> tr("Upload fehlgeschlagen.")
    }

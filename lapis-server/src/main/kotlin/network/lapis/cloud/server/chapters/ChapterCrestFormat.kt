package network.lapis.cloud.server.chapters

import io.ktor.http.ContentType
import network.lapis.cloud.server.events.CoverImageFormat

/**
 * Welle V1.9.21 -- the stored formats of a regional chapter crest: the two raster formats of the
 * shared cover pipeline plus the sanitized SVG. Deliberately a SEPARATE type from [CoverImageFormat]
 * (which stays raster-only for event covers, article covers and member photos): SVG must never be
 * accepted by any of those routes.
 */
internal enum class ChapterCrestFormat(
    val extension: String,
    val storedContentType: String,
    val deliveryContentType: ContentType,
    val downloadFileName: String,
) {
    JPEG("jpg", "image/jpeg", ContentType.Image.JPEG, "crest.jpg"),
    PNG("png", "image/png", ContentType.Image.PNG, "crest.png"),
    SVG("svg", "image/svg+xml", ContentType.Image.SVG.withParameter("charset", "utf-8"), "crest.svg"),
    ;

    companion object {
        fun fromRaster(format: CoverImageFormat): ChapterCrestFormat =
            when (format) {
                CoverImageFormat.JPEG -> JPEG
                CoverImageFormat.PNG -> PNG
            }

        fun fromStoredContentType(contentType: String): ChapterCrestFormat? = entries.firstOrNull { it.storedContentType == contentType }
    }
}

package network.lapis.cloud.server.gallery

import kotlin.uuid.Uuid

/**
 * V1.9.222 -- the single source of the delivery URL of a gallery picture. The default [baseUrl] is empty, i.e. a root-relative URL: the
 * picture is
 * private and same-origin (session cookie, `Cross-Origin-Resource-Policy: same-origin`), so a relative URL is what always works,
 * independent of the
 * configured public base URL. The `v` tag is the first 8 characters of the row's `storage_key`: it is as unique as a timestamp but
 * reveals
 * no time.
 */
internal object GalleryUrls {
    fun imageUrl(
        imageId: Uuid,
        storageKey: Uuid,
        baseUrl: String = "",
    ): String = "${baseUrl.trimEnd('/')}/api/images/$imageId?v=${versionTag(storageKey)}"

    fun versionTag(storageKey: Uuid): String = storageKey.toString().take(VERSION_TAG_LENGTH)

    private const val VERSION_TAG_LENGTH = 8
}

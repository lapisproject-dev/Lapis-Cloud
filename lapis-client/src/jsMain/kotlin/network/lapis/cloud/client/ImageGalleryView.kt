package network.lapis.cloud.client

import io.kvision.core.onEvent
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.i18n.gettext
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.AttachedImageDto

/**
 * V1.9.222 -- the read-only gallery: one large picture (4:3 box, `object-fit: contain`, so nothing is cropped) and a wrapping row of
 * thumbnails
 * under it. A tap on a thumbnail swaps only `src`/`alt` of the large picture -- no overlay, no animation, no scroller. Nothing is
 * rendered
 * for an
 * empty list. `alt` = the person's alternative text, else the caption, else "Bild N von {title}".
 */
internal fun SimplePanel.imageGalleryView(
    images: List<AttachedImageDto>,
    ownerTitle: String,
) {
    if (images.isEmpty()) return
    val safeTitle = sanitizeUntrustedI18nText(ownerTitle)

    fun altOf(
        number: Int,
        image: AttachedImageDto,
    ) = image.altText?.takeIf { it.isNotBlank() } ?: image.caption?.takeIf { it.isNotBlank() }
        ?: gettext("Bild %1 von %2", number, safeTitle)

    val box = vPanel(spacing = 4) { addCssClass("lapis-gallery-view") }
    val stage = box.div(className = "lapis-gallery-stage")
    val large =
        stage.image(images.first().url, altOf(1, images.first()), className = "lapis-gallery-large") {
            setAttribute("decoding", "async")
        }
    val caption = box.div(className = "small text-muted")

    fun showCaption(image: AttachedImageDto) {
        caption.content = image.caption?.let { sanitizeUntrustedI18nText(it) }.orEmpty()
    }
    showCaption(images.first())
    if (images.size > 1) {
        val strip = box.div(className = "lapis-gallery-strip")
        images.forEachIndexed { index, image ->
            val number = index + 1
            val alt = altOf(number, image)
            val thumb = strip.div(className = "lapis-gallery-strip-item")
            thumb.setAttribute("role", "button")
            thumb.setAttribute("tabindex", "0")
            thumb.setAttribute("aria-label", resolvedAttributeText(gettext("Bild %1 anzeigen", number)))
            thumb.image(image.url, alt, className = "lapis-gallery-thumb") {
                setAttribute("loading", "lazy")
                setAttribute("decoding", "async")
            }
            val show = {
                large.src = image.url
                large.alt = alt
                showCaption(image)
            }
            thumb.onEvent {
                click = { show() }
                keydown = { event ->
                    if (event.key == "Enter" || event.key == " ") {
                        event.preventDefault()
                        show()
                    }
                }
            }
        }
    }
}

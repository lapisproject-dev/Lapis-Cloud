package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.222 -- the read-only gallery. */
class ImageGalleryViewDomTest {
    @Test
    fun anEmptyList_rendersNothingAtAll(): Promise<Unit> =
        formTest {
            mountedForm("gallery-view-empty") { root, element ->
                (root as SimplePanel).imageGalleryView(emptyList(), "Fahrrad")
                assertEquals(0, element().allOf(".lapis-gallery-view").size)
                assertEquals(0, element().allOf("img").size)
            }
        }

    @Test
    fun aTapOnAThumbnail_swapsTheLargePicture_andOnlyThat(): Promise<Unit> =
        formTest {
            mountedForm("gallery-view-swap") { root, element ->
                root.imageGalleryView(
                    listOf(storedImage("i1", 0, caption = "Vorn"), storedImage("i2", 1, caption = "Hinten"), storedImage("i3", 2)),
                    "Fahrrad",
                )
                val large = element().allOf(".lapis-gallery-large").single() as HTMLImageElement
                assertTrue(large.src.contains("/api/images/i1"))
                assertEquals(
                    "Vorn",
                    element()
                        .allOf(".lapis-gallery-view .small.text-muted")
                        .first()
                        .textContent
                        ?.trim(),
                )
                val thumbs = element().allOf(".lapis-gallery-strip-item")
                assertEquals(3, thumbs.size)
                assertEquals("Bild 2 anzeigen", thumbs[1].getAttribute("aria-label"))
                thumbs[1].click()
                assertTrue(large.src.contains("/api/images/i2"))
                assertEquals(
                    "Hinten",
                    element()
                        .allOf(".lapis-gallery-view .small.text-muted")
                        .first()
                        .textContent
                        ?.trim(),
                )
                assertEquals(3, element().allOf(".lapis-gallery-strip-item").size, "the strip is unchanged")
                assertEquals("Bild 3 von Fahrrad", ((element().allOf(".lapis-gallery-strip-item img")[2]) as HTMLImageElement).alt)
            }
        }

    @Test
    fun nothingOverflowsAt375px(): Promise<Unit> =
        formTest {
            mountedForm("gallery-view-narrow") { root, element ->
                root.imageGalleryView((0 until 8).map { storedImage("i$it", it) }, "Fahrrad")
                val host: HTMLElement = element()
                host.style.width = "375px"
                assertTrue(
                    host.scrollWidth <= host.clientWidth + 1,
                    "scrollWidth ${host.scrollWidth} must not exceed clientWidth ${host.clientWidth}",
                )
            }
        }
}

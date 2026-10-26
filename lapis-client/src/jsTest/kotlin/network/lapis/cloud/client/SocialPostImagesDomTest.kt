package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.AttachedImageDto
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import network.lapis.cloud.shared.domain.SocialPostImagePreviewDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.223 -- pictures in social posts: the composer buffer, the publish-then-upload flow, the feed preview, the detail list and the dialog. */
class SocialPostImagesDomTest {
    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun images(
        count: Int,
        altOfFirst: String? = null,
    ): List<AttachedImageDto> = (0 until count).map { storedImage("s$it", it).copy(altText = if (it == 0) altOfFirst else null) }

    @Test
    fun composerEditor_hasNoCoverNoOrderNoCaption_andStopsAtFour(): Promise<Unit> =
        formTest {
            mountedForm("social-composer-buffer") { root, element ->
                val composer = SocialComposerImages(backend = FakeGalleryBackend(), onFinished = {})
                root.add(composer)
                composer.editor.addFiles((1..5).map { jpegFile("n$it.jpg") })
                awaitUntil("four previews") { element().allOf(".lapis-gallery-item").size == 4 }
                assertEquals(4, composer.editor.pendingCount())
                assertTrue(element().shows("Höchstens 4 Bilder."), "the fifth picture is refused with the social limit")
                assertTrue(element().shows("Höchstens 4 Bilder, je bis zu 5 MB (JPEG, PNG oder WebP)."))
                assertFalse(element().shows("Titelbild"), "a post has no cover image")
                assertEquals(0, element().allOf("button").count { (it.getAttribute("aria-label") ?: "").contains("nach oben") })
                assertEquals(0, element().allOf("button").count { (it.getAttribute("aria-label") ?: "").contains("nach unten") })
                val labels = element().allOf("label").map { it.textContent.orEmpty().trim() }
                assertFalse(labels.any { it.startsWith("Beschriftung") }, "no visible caption")
                assertEquals(4, labels.count { it.startsWith("Bildbeschreibung (für Menschen, die das Bild nicht sehen)") })
                assertTrue(element().shows("Ohne Beschreibung wird ein Ersatztext verwendet."))
            }
        }

    @Test
    fun aFailedPictureAfterPublishing_isReported_andARetryFinishes(): Promise<Unit> =
        formTest {
            mountedForm("social-composer-retry") { root, element ->
                val backend = FakeGalleryBackend().apply { failing["b.jpg"] = GalleryUploadErrorCode.IMAGE_UNREADABLE }
                var finished = 0
                val composer = SocialComposerImages(backend = backend, onFinished = { finished++ })
                root.add(composer)
                composer.editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg")))
                awaitUntil("previews") { element().allOf(".lapis-gallery-item").size == 2 }
                assertTrue(composer.hasPending())
                composer.afterPublish("post-1")
                awaitUntil(
                    "the message",
                ) { element().shows("Der Beitrag ist veröffentlicht, aber 1 Bild(er) konnten nicht hochgeladen werden.") }
                assertEquals(listOf("a.jpg"), backend.uploaded)
                assertEquals(0, finished, "not finished while a picture is missing")
                backend.failing.clear()
                element().allOf("button").first { it.textContent?.trim() == "Erneut versuchen" }.click()
                awaitUntil("retried and finished") { finished == 1 }
                assertEquals(listOf("a.jpg", "b.jpg"), backend.uploaded)
                assertEquals(0, composer.editor.pendingCount())
                assertFalse(element().shows("Der Beitrag ist veröffentlicht, aber"), "the message is gone")
            }
        }

    @Test
    fun whenTheWindowHasClosed_theReasonIsShown_noRetryIsOffered_andDoneFinishes(): Promise<Unit> =
        formTest {
            mountedForm("social-composer-expired") { root, element ->
                val backend = FakeGalleryBackend().apply { failing["b.jpg"] = GalleryUploadErrorCode.GALLERY_LOCKED }
                var finished = 0
                val composer = SocialComposerImages(backend = backend, onFinished = { finished++ })
                root.add(composer)
                composer.editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg")))
                awaitUntil("previews") { element().allOf(".lapis-gallery-item").size == 2 }
                composer.afterPublish("post-1")
                awaitUntil("expired text") { element().shows("Die Zeit zum Nachreichen ist abgelaufen.") }
                assertTrue(composer.editor.uploadWindowClosed())
                assertEquals(0, element().allOf("button").count { it.textContent?.trim() == "Erneut versuchen" })
                element().buttonNamed("Fertig").click()
                awaitUntil("finished") { finished == 1 }
                assertEquals(0, composer.editor.pendingCount(), "the buffer is empty again")
            }
        }

    @Test
    fun anAllGoodPublish_finishesAtOnce_andTheNextPostStartsWithAnEmptyBuffer(): Promise<Unit> =
        formTest {
            mountedForm("social-composer-ok") { root, _ ->
                val backend = FakeGalleryBackend()
                var finished = 0
                val composer = SocialComposerImages(backend = backend, onFinished = { finished++ })
                root.add(composer)
                composer.editor.addFiles(listOf(jpegFile("a.jpg")))
                composer.afterPublish("post-1")
                assertEquals(1, finished)
                assertEquals(listOf("a.jpg"), backend.uploaded)
                // a picture chosen for the NEXT post must not go to the previous one
                composer.editor.addFiles(listOf(jpegFile("c.jpg")))
                assertEquals(listOf("a.jpg"), backend.uploaded, "no upload without a new post")
                assertEquals(1, composer.editor.pendingCount())
            }
        }

    @Test
    fun theFeedPreview_isOneLazyPicture_withAPlusBadge_andOpensOnClickAndEnter(): Promise<Unit> =
        formTest {
            mountedForm("social-feed-preview") { root, element ->
                var opened = 0
                val preview = SocialPostImagePreviewDto(postId = "p1", first = storedImage("f1", 0), totalCount = 4)
                root.socialFeedImagePreview(preview, authorName = "Anna") { opened++ }
                val imgs = element().allOf("img")
                assertEquals(1, imgs.size)
                val img = imgs.single() as HTMLImageElement
                assertEquals("lazy", img.getAttribute("loading"))
                assertEquals("Bild 1 von 4 zum Beitrag von Anna", img.alt)
                val badge = element().allOf(".lapis-social-image-more").single()
                assertEquals("+3", badge.textContent?.trim())
                assertEquals("3 weitere Bilder", badge.getAttribute("aria-label"))
                val box = element().allOf(".lapis-social-image-box").single()
                assertEquals("button", box.getAttribute("role"))
                assertEquals("0", box.getAttribute("tabindex"))
                box.click()
                box.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true)))
                assertEquals(2, opened)
            }
        }

    @Test
    fun aSinglePicture_hasNoBadge(): Promise<Unit> =
        formTest {
            mountedForm("social-feed-single") { root, element ->
                root.socialFeedImagePreview(SocialPostImagePreviewDto("p1", storedImage("f1", 0), 1), authorName = "Anna") {}
                assertEquals(0, element().allOf(".lapis-social-image-more").size)
            }
        }

    @Test
    fun theDetailView_isOneColumn_withContain_andTheAuthorsAltText(): Promise<Unit> =
        formTest {
            mountedForm("social-detail") { root, element ->
                root.socialPostImagesDetail(images(3, altOfFirst = "Ein Hund am Strand"), authorName = "Anna")
                val boxes = element().allOf(".lapis-social-image-box--contain")
                assertEquals(3, boxes.size)
                assertEquals(
                    listOf("Ein Hund am Strand", "Bild 2 von 3 zum Beitrag von Anna", "Bild 3 von 3 zum Beitrag von Anna"),
                    element().allOf(".lapis-social-image-box img").map { (it as HTMLImageElement).alt },
                )
                assertTrue(element().allOf(".lapis-social-image-box img").all { it.getAttribute("loading") == "lazy" })
                measureAtWidth(element(), 375) { doc, host ->
                    val img = host.queryOne(".lapis-social-image-box--contain img")
                    assertEquals("contain", doc.defaultView!!.getComputedStyle(img).getPropertyValue("object-fit"))
                    val column = host.queryOne(".lapis-social-image-column")
                    assertEquals("column", doc.defaultView!!.getComputedStyle(column).getPropertyValue("flex-direction"))
                    assertTrue(
                        host.scrollWidth <= host.clientWidth + 1,
                        "scrollWidth ${host.scrollWidth} > clientWidth ${host.clientWidth}",
                    )
                }
            }
        }

    @Test
    fun theDialog_stepsThroughThePictures_disablesTheEdges_andGivesTheFocusBack(): Promise<Unit> =
        formTest {
            mountedForm("social-dialog") { root, element ->
                root.socialPostImagesDetail(images(2), authorName = "Anna")
                val opener = element().allOf(".lapis-social-image-box").first()
                opener.focus()
                opener.click()
                val dialog = assertNotNull(document.querySelector("dialog.lapis-social-dialog") as? HTMLElement, "no dialog")
                assertTrue(dialog.hasAttribute("open"), "the dialog is open (modal)")

                fun button(text: String) = dialog.allOf("button").first { it.textContent?.trim() == text }
                assertEquals("true", button("Vorheriges").getAttribute("aria-disabled"))
                assertEquals("false", button("Nächstes").getAttribute("aria-disabled"))
                assertTrue(dialog.shows("Bild 1 von 2 zum Beitrag von Anna"), "the alternative text is a visible line")
                button("Nächstes").click()
                assertEquals("false", button("Vorheriges").getAttribute("aria-disabled"))
                assertEquals("true", button("Nächstes").getAttribute("aria-disabled"))
                assertTrue(dialog.shows("Bild 2 von 2 zum Beitrag von Anna"))
                assertTrue((dialog.querySelector("img") as HTMLImageElement).src.contains("/api/images/s1"))
                button("Schließen").click()
                awaitUntil("the dialog left the document") { document.querySelector("dialog.lapis-social-dialog") == null }
                assertTrue(document.activeElement === opener, "the focus is back on the element that opened the dialog")
            }
        }

    @Test
    fun anEmptyListRendersNothing_andTheOwnerTypeIsSocial() {
        assertNull(document.querySelector("dialog.lapis-social-dialog"))
        assertEquals(AttachedImageOwnerType.SOCIAL_POST, AttachedImageOwnerType.entries.last())
    }
}

package network.lapis.cloud.client

import kotlinx.coroutines.async
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AttachedImageDto
import network.lapis.cloud.shared.domain.AttachedImageGalleryDto
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.GalleryLockReason
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FilePropertyBag
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A backend that answers from memory and records what the editor asked. */
internal class FakeGalleryBackend : GalleryBackend {
    val uploaded = mutableListOf<String>()
    val uploadCalls = mutableListOf<String>()
    var holdFirstUpload = false
    var holdUploadOf: String? = null
    var captionFails = false
    private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun releaseFirstUpload() {
        gate?.complete(Unit)
    }

    val failing = mutableMapOf<String, GalleryUploadErrorCode?>()
    var gallery: AttachedImageGalleryDto? = null
    val removed = mutableListOf<String>()
    val reorders = mutableListOf<List<String>>()

    override suspend fun upload(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        file: File,
        onProgress: (Double) -> Unit,
    ): AttachedImageHttp.Result {
        onProgress(0.4)
        uploadCalls += file.name
        if (holdFirstUpload || holdUploadOf == file.name) {
            holdFirstUpload = false
            holdUploadOf = null
            val g = kotlinx.coroutines.CompletableDeferred<Unit>()
            gate = g
            g.await()
        }
        if (failing.containsKey(file.name)) return AttachedImageHttp.Result.Error(failing[file.name])
        uploaded += file.name
        return AttachedImageHttp.Result.Ok(id = "id-${file.name}", url = "/api/images/id-${file.name}?v=1", position = uploaded.size - 1)
    }

    override suspend fun list(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
    ) = gallery

    override suspend fun reorder(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        orderedIds: List<String>,
    ): AttachedImageGalleryDto? {
        reorders += orderedIds
        val current = gallery ?: return null
        val byId = current.images.associateBy { it.id }
        gallery = current.copy(images = orderedIds.mapIndexed { index, id -> byId.getValue(id).copy(position = index) })
        return gallery
    }

    override suspend fun setCover(imageId: String): AttachedImageGalleryDto? = gallery

    override suspend fun updateCaption(
        imageId: String,
        caption: String?,
        altText: String?,
    ): AttachedImageGalleryDto? = if (captionFails) null else gallery

    override suspend fun delete(imageId: String): AttachedImageGalleryDto? {
        removed += imageId
        gallery =
            gallery?.let { g -> g.copy(images = g.images.filter { it.id != imageId }.mapIndexed { i, img -> img.copy(position = i) }) }
        return gallery
    }
}

internal fun jpegFile(
    name: String,
    size: Int = 10,
    type: String = "image/jpeg",
): File = File(arrayOf("x".repeat(size)), name, FilePropertyBag(type = type))

internal fun storedImage(
    id: String,
    position: Int,
    mine: Boolean = true,
    caption: String? = null,
) = AttachedImageDto(
    id = id,
    position = position,
    caption = caption,
    altText = null,
    url = "/api/images/$id?v=1",
    width = 100,
    height = 80,
    byteSize = 1000,
    createdAt = LocalDateTime(2026, 10, 25, 10, 0),
    uploadedByMe = mine,
)

internal fun gallery(
    images: List<AttachedImageDto>,
    canEdit: Boolean = true,
    canModerate: Boolean = false,
    lock: GalleryLockReason? = null,
) = AttachedImageGalleryDto(
    ownerType = AttachedImageOwnerType.AUCTION_LISTING,
    ownerId = "owner-1",
    images = images,
    canEdit = canEdit,
    canModerate = canModerate,
    lockReason = lock,
    maxImages = 10,
    maxTotalBytes = 30L * 1024 * 1024,
)

/** V1.9.222 -- the gallery editor in the browser: buffer mode (the create form) and server mode (frozen, moderation). */
class ImageGalleryEditorDomTest {
    private fun HTMLElement.items(): List<HTMLElement> = allOf(".lapis-gallery-item")

    private fun HTMLElement.alts(): List<String> = allOf(".lapis-gallery-item img").map { (it as HTMLImageElement).alt }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.byLabel(label: String): HTMLElement =
        assertNotNull(
            allOf("button").firstOrNull {
                it.getAttribute("aria-label") ==
                    label
            },
            "no button '$label'",
        )

    private fun HTMLElement.hasButtonLabelled(label: String): Boolean = allOf("button").any { it.getAttribute("aria-label") == label }

    private fun editor(
        root: io.kvision.panel.SimplePanel,
        backend: FakeGalleryBackend = FakeGalleryBackend(),
    ) = root.imageGalleryEditor(AttachedImageOwnerType.AUCTION_LISTING, ownerTitle = { "Fahrrad" }, backend = backend)

    private fun HTMLElement.nameImages() {
        // give every picture its own alternative text so the order can be read from the DOM after the next render
        allOf(".lapis-gallery-item").forEachIndexed { index, item -> item.typeInto("Alternativtext", "Bild-${'A' + index}") }
    }

    @Test
    fun theTwoFileInputs_haveTheirOwnAttributes(): Promise<Unit> =
        formTest {
            mountedForm("gallery-inputs") { root, element ->
                editor(root)
                val inputs = element().allOf("input[type=file]").map { it as HTMLInputElement }
                assertEquals(2, inputs.size)
                val photo = inputs.single { it.getAttribute("capture") == "environment" }
                val pick = inputs.single { it.multiple }
                assertFalse(photo.multiple, "the camera input takes one picture")
                assertNull(pick.getAttribute("capture"), "only the camera input asks for the camera")
                assertTrue(pick.accept.contains("image/webp") && pick.accept.contains("image/png") && pick.accept.contains("image/jpeg"))
                assertTrue(element().shows("Foto aufnehmen") && element().shows("Bilder auswählen"))
            }
        }

    @Test
    fun bufferMode_previewsTheFirstAsCover_andReordersWithTheArrows(): Promise<Unit> =
        formTest {
            mountedForm("gallery-buffer") { root, element ->
                val editor = editor(root)
                editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg")))
                awaitUntil("two previews") { element().items().size == 2 }
                assertEquals(1, element().allOf(".lapis-gallery-cover-badge").size)
                assertEquals(
                    "Titelbild",
                    element()
                        .allOf(".lapis-gallery-cover-badge")
                        .single()
                        .textContent
                        ?.trim(),
                )
                assertEquals(listOf("Bild 1 von Fahrrad", "Bild 2 von Fahrrad"), element().alts())
                assertEquals(2, editor.pendingCount())
                // first picture: the up arrow is aria-disabled (not natively disabled), the last: the down arrow
                assertEquals("true", element().byLabel("Bild 1 nach oben").getAttribute("aria-disabled"))
                assertEquals("true", element().byLabel("Bild 2 nach unten").getAttribute("aria-disabled"))
                element().nameImages()
                element().byLabel("Bild 1 nach unten").click()
                awaitUntil("the order changed") { element().alts() == listOf("Bild-B", "Bild-A") }
                assertEquals(1, element().allOf(".lapis-gallery-cover-badge").size, "the badge is on the new first picture only")
                assertEquals("Bild-B", (element().items().first().querySelector("img") as HTMLImageElement).alt)
                assertNotNull(element().items().first().querySelector(".lapis-gallery-cover-badge"))
            }
        }

    @Test
    fun asCoverLink_appearsFromThePositionOnThatArrowsDoNotReach(): Promise<Unit> =
        formTest {
            mountedForm("gallery-cover") { root, element ->
                val editor = editor(root)
                editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg")))
                awaitUntil("two previews") { element().items().size == 2 }
                assertEquals(0, element().allOf("button").count { it.textContent?.trim() == "Als Titelbild" })
                editor.addFiles(listOf(jpegFile("c.jpg")))
                awaitUntil("three previews") { element().items().size == 3 }
                assertEquals(1, element().allOf("button").count { it.textContent?.trim() == "Als Titelbild" })
                element().nameImages()
                element().buttonNamed("Als Titelbild").click()
                awaitUntil("the third is first now") { element().alts() == listOf("Bild-C", "Bild-A", "Bild-B") }
            }
        }

    @Test
    fun removing_asksFirst_aCancelKeepsThePicture(): Promise<Unit> =
        formTest {
            mountedForm("gallery-remove") { root, element ->
                val editor = editor(root)
                editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg")))
                awaitUntil("two previews") { element().items().size == 2 }
                element().byLabel("Bild 1 entfernen").click()
                awaitUntil("the question") { element().shows("Bild entfernen?") }
                assertEquals(2, element().items().size, "nothing is removed before the answer")
                element().buttonNamed("Abbrechen").click()
                awaitUntil("the question is gone") { !element().shows("Bild entfernen?") }
                assertEquals(2, element().items().size)
                element().byLabel("Bild 1 entfernen").click()
                awaitUntil("the question again") { element().shows("Bild entfernen?") }
                element().buttonNamed("Entfernen").click()
                awaitUntil("one picture is left") { element().items().size == 1 }
                assertEquals(1, editor.pendingCount())
            }
        }

    @Test
    fun theChecksBeforeTheUpload_name_theFileAndTheReason(): Promise<Unit> =
        formTest {
            mountedForm("gallery-precheck") { root, element ->
                val editor = editor(root)
                editor.addFiles(
                    listOf(jpegFile("riesig.jpg", size = 6 * 1024 * 1024), jpegFile("tier.gif", type = "image/gif"), jpegFile("ok.jpg")),
                )
                awaitUntil("messages") { element().shows("riesig.jpg") }
                assertTrue(element().shows("Datei ist größer als 5 MB."))
                assertTrue(element().shows("Nur JPEG, PNG oder WebP."))
                assertEquals(1, editor.pendingCount(), "only the good file is queued")
                editor.addFiles((1..10).map { jpegFile("n$it.jpg") })
                awaitUntil("limit message") { element().shows("Höchstens 10 Bilder.") }
                assertEquals(10, editor.pendingCount())
            }
        }

    @Test
    fun aFailedUpload_staysWithItsReason_andCanBeRetried(): Promise<Unit> =
        formTest {
            mountedForm("gallery-retry") { root, element ->
                val backend = FakeGalleryBackend().apply { failing["b.jpg"] = GalleryUploadErrorCode.IMAGE_UNREADABLE }
                val editor = editor(root, backend)
                editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg")))
                awaitUntil("previews") { element().items().size == 2 }
                val allDone = editor.uploadPending("owner-1")
                assertFalse(allDone, "one picture failed")
                assertEquals(listOf("a.jpg"), backend.uploaded)
                assertTrue(editor.hasFailures())
                assertEquals(1, editor.pendingCount(), "the uploaded one is gone from the buffer, the failed one stays")
                awaitUntil("the reason") { element().shows("Bild konnte nicht gelesen werden.") }
                backend.failing.clear()
                element().buttonNamed("Erneut versuchen").click()
                awaitUntil("retried and done") { backend.uploaded == listOf("a.jpg", "b.jpg") && editor.pendingCount() == 0 }
                assertFalse(editor.hasFailures())
            }
        }

    @Test
    fun aSecondAddDuringARunningUpload_doesNotUploadTheFirstPictureTwice(): Promise<Unit> =
        formTest {
            mountedForm("gallery-concurrent") { root, element ->
                val backend = FakeGalleryBackend().apply { gallery = gallery(emptyList()) }
                val editor = editor(root, backend)
                editor.showGallery(backend.gallery!!)
                // the first upload is slow: the second add happens while it is under way
                backend.holdFirstUpload = true
                editor.addFiles(listOf(jpegFile("a.jpg")))
                awaitUntil("the first upload started") { backend.uploadCalls == listOf("a.jpg") }
                editor.addFiles(listOf(jpegFile("b.jpg")))
                backend.releaseFirstUpload()
                awaitUntil("both uploaded, nothing left") { backend.uploaded.size == 2 && editor.pendingCount() == 0 }
                assertEquals(listOf("a.jpg", "b.jpg"), backend.uploadCalls, "every picture is sent exactly once")
                assertEquals(listOf("a.jpg", "b.jpg"), backend.uploaded)
            }
        }

    @Test
    fun aPictureAddedDuringTheRun_isUploadedToo_andTheRunReportsNothingLeft(): Promise<Unit> =
        formTest {
            mountedForm("gallery-added-during-run") { root, element ->
                val backend = FakeGalleryBackend()
                val editor = editor(root, backend)
                editor.addFiles(listOf(jpegFile("a.jpg")))
                backend.holdFirstUpload = true
                val run = AppScope.async { editor.uploadPending("owner-1") }
                awaitUntil("the first upload started") { backend.uploadCalls == listOf("a.jpg") }
                // buffer mode (no gallery shown): addFiles does not start an upload by itself
                editor.addFiles(listOf(jpegFile("b.jpg")))
                backend.releaseFirstUpload()
                assertTrue(run.await(), "nothing is left, so the form may close")
                assertEquals(listOf("a.jpg", "b.jpg"), backend.uploaded)
                assertEquals(0, editor.pendingCount())
            }
        }

    @Test
    fun aRetryDuringARunningRun_isPickedUpByThatRun(): Promise<Unit> =
        formTest {
            mountedForm("gallery-retry-during-run") { root, element ->
                val backend =
                    FakeGalleryBackend().apply {
                        failing["a.jpg"] = GalleryUploadErrorCode.IMAGE_UNREADABLE
                        holdUploadOf = "b.jpg"
                    }
                val editor = editor(root, backend)
                editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg"), jpegFile("c.jpg")))
                val run = AppScope.async { editor.uploadPending("owner-1") }
                awaitUntil("a failed, b is held") { backend.uploadCalls == listOf("a.jpg", "b.jpg") }
                awaitUntil("retry button") { element().allOf("button").any { it.textContent?.trim() == "Erneut versuchen" } }
                backend.failing.clear()
                element().buttonNamed("Erneut versuchen").click()
                backend.releaseFirstUpload()
                assertTrue(run.await(), "the retried picture was taken by the running run")
                assertEquals(setOf("a.jpg", "b.jpg", "c.jpg"), backend.uploaded.toSet())
                assertEquals(0, editor.pendingCount())
            }
        }

    @Test
    fun aPictureAddedAfterTheFirstRun_isUploadedInBufferModeToo(): Promise<Unit> =
        formTest {
            mountedForm("gallery-add-after-done") { root, element ->
                val backend = FakeGalleryBackend().apply { failing["a.jpg"] = GalleryUploadErrorCode.IMAGE_UNREADABLE }
                val editor = editor(root, backend)
                editor.addFiles(listOf(jpegFile("a.jpg")))
                assertFalse(editor.uploadPending("owner-1"), "a failed: the form shows 'Fertig'")
                backend.failing.clear()
                editor.addFiles(listOf(jpegFile("d.jpg")))
                awaitUntil("d is uploaded without any further call") { backend.uploaded.contains("d.jpg") }
                awaitUntil("nothing left") { editor.pendingCount() == 0 }
            }
        }

    @Test
    fun aCaptionThatCouldNotBeSaved_keepsTheFormOpenWithTheMessage(): Promise<Unit> =
        formTest {
            mountedForm("gallery-caption-failure") { root, element ->
                val backend = FakeGalleryBackend().apply { captionFails = true }
                val editor = editor(root, backend)
                editor.addFiles(listOf(jpegFile("a.jpg")))
                awaitUntil("preview") { element().items().size == 1 }
                element().typeInto("Beschriftung", "Hallo")
                assertFalse(editor.uploadPending("owner-1"), "not clean: a caption was lost")
                assertEquals(0, editor.pendingCount())
                assertTrue(editor.hasFailures(), "so the create form does not fold shut on its own")
                assertTrue(element().shows("Bildunterschrift und Alternativtext konnten nicht gespeichert werden."))
            }
        }

    @Test
    fun aLockedUpload_stopsTheRest_withTheLockText(): Promise<Unit> =
        formTest {
            mountedForm("gallery-locked-upload") { root, element ->
                val backend = FakeGalleryBackend().apply { failing["a.jpg"] = GalleryUploadErrorCode.GALLERY_LOCKED }
                val editor = editor(root, backend)
                editor.addFiles(listOf(jpegFile("a.jpg"), jpegFile("b.jpg")))
                assertFalse(editor.uploadPending("owner-1"))
                assertTrue(backend.uploaded.isEmpty(), "the second picture is not even tried after a lock")
                awaitUntil("lock text") { element().shows("Nach dem ersten Gebot können Bilder nicht mehr geändert werden.") }
            }
        }

    @Test
    fun aFrozenGallery_hasNoControls_butTheLockText(): Promise<Unit> =
        formTest {
            mountedForm("gallery-frozen") { root, element ->
                val editor = editor(root)
                editor.showGallery(
                    gallery(listOf(storedImage("i1", 0), storedImage("i2", 1)), canEdit = false, lock = GalleryLockReason.AUCTION_HAS_BIDS),
                )
                awaitUntil("lock text") { element().shows("Nach dem ersten Gebot können Bilder nicht mehr geändert werden.") }
                assertEquals(0, element().allOf("input[type=file]").size, "no way to add a picture")
                assertFalse(element().hasButtonLabelled("Bild 1 nach unten"))
                assertFalse(element().hasButtonLabelled("Bild 1 entfernen"), "the creator cannot remove from a frozen gallery")
            }
        }

    @Test
    fun moderation_canOnlyRemove(): Promise<Unit> =
        formTest {
            mountedForm("gallery-moderation") { root, element ->
                val backend =
                    FakeGalleryBackend().apply {
                        gallery =
                            gallery(listOf(storedImage("i1", 0), storedImage("i2", 1)), canEdit = false, canModerate = true)
                    }
                val editor = editor(root, backend)
                editor.showGallery(backend.gallery!!)
                awaitUntil("remove buttons") { element().hasButtonLabelled("Bild 1 entfernen") }
                assertFalse(element().hasButtonLabelled("Bild 1 nach unten"))
                assertEquals(0, element().allOf("input[type=file]").size)
                element().byLabel("Bild 1 entfernen").click()
                awaitUntil("question") { element().shows("Bild entfernen?") }
                element().buttonNamed("Entfernen").click()
                awaitUntil("removed on the server") { backend.removed == listOf("i1") }
                awaitUntil("one picture left") { element().items().size == 1 }
            }
        }

    @Test
    fun serverMode_reordersThroughTheService(): Promise<Unit> =
        formTest {
            mountedForm("gallery-server") { root, element ->
                val backend =
                    FakeGalleryBackend().apply {
                        gallery =
                            gallery(listOf(storedImage("i1", 0), storedImage("i2", 1), storedImage("i3", 2)))
                    }
                val editor = editor(root, backend)
                editor.showGallery(backend.gallery!!)
                awaitUntil("three pictures") { element().items().size == 3 }
                element().byLabel("Bild 3 nach oben").click()
                awaitUntil("the service was asked") { backend.reorders == listOf(listOf("i1", "i3", "i2")) }
                awaitUntil("the new order is shown") {
                    element()
                        .allOf(".lapis-gallery-item img")
                        .map { it.getAttribute("src") }
                        .first()
                        .orEmpty()
                        .contains("i1")
                }
            }
        }

    @Test
    fun nothingOverflowsAt375px(): Promise<Unit> =
        formTest {
            mountedForm("gallery-narrow") { root, element ->
                val editor = editor(root)
                editor.addFiles((1..4).map { jpegFile("n$it.jpg") })
                awaitUntil("previews") { element().items().size == 4 }
                val host = element()
                host.style.width = "375px"
                assertTrue(
                    host.scrollWidth <= host.clientWidth + 1,
                    "scrollWidth ${host.scrollWidth} must not exceed clientWidth ${host.clientWidth}",
                )
            }
        }
}

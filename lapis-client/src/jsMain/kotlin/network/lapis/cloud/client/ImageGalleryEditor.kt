package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.text.text
import io.kvision.form.upload.Capture
import io.kvision.form.upload.Upload
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttachedImageDto
import network.lapis.cloud.shared.domain.AttachedImageGalleryDto
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import network.lapis.cloud.shared.rpc.IAttachedImageService
import org.w3c.dom.url.URL
import org.w3c.files.File

/** Everything the editor needs from the server. The default is the real thing; a test injects a fake. */
internal interface GalleryBackend {
    suspend fun upload(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        file: File,
        onProgress: (Double) -> Unit,
    ): AttachedImageHttp.Result

    suspend fun list(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
    ): AttachedImageGalleryDto?

    suspend fun reorder(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        orderedIds: List<String>,
    ): AttachedImageGalleryDto?

    suspend fun setCover(imageId: String): AttachedImageGalleryDto?

    suspend fun updateCaption(
        imageId: String,
        caption: String?,
        altText: String?,
    ): AttachedImageGalleryDto?

    suspend fun delete(imageId: String): AttachedImageGalleryDto?
}

internal object RpcGalleryBackend : GalleryBackend {
    private fun service() = rpcService<IAttachedImageService>()

    override suspend fun upload(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        file: File,
        onProgress: (Double) -> Unit,
    ) = AttachedImageHttp.upload(ownerType = ownerType, ownerId = ownerId, file = file, onProgress = onProgress)

    override suspend fun list(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
    ) = guarded { service().list(ownerType, ownerId) }

    override suspend fun reorder(
        ownerType: AttachedImageOwnerType,
        ownerId: String,
        orderedIds: List<String>,
    ) = guarded { service().reorder(ownerType, ownerId, orderedIds) }

    override suspend fun setCover(imageId: String) = guarded { service().setCover(imageId) }

    override suspend fun updateCaption(
        imageId: String,
        caption: String?,
        altText: String?,
    ) = guarded { service().updateCaption(imageId, caption, altText) }

    override suspend fun delete(imageId: String) = guarded { service().delete(imageId) }
}

/** A picture chosen on this device, not (yet) on the server. */
internal class PendingImage(
    val key: String,
    val file: File,
    val previewUrl: String,
) {
    var caption: String = ""
    var altText: String = ""
    var state: PendingState = PendingState.Queued
}

internal sealed interface PendingState {
    data object Queued : PendingState

    data class Uploading(
        val fraction: Double,
    ) : PendingState

    data class Failed(
        val code: GalleryUploadErrorCode?,
    ) : PendingState

    data object Done : PendingState
}

private var galleryEditorCounter = 0

/**
 * V1.9.222 -- the one gallery component for crowdfunding projects, auctions and (V1.9.223) social posts, in two modes:
 *
 * - **Buffer mode** (no owner yet, the create form): the chosen pictures stay on this device with a preview; order, cover, captions and
 * removal
 * work on the local list. After the owner has been created the caller runs [uploadPending] with the new id -- the form stays open until
 * that is
 *   done, so a failed picture can be retried ("Erneut versuchen") without losing the object that already exists.
 * - **Server mode** ([showGallery]): the stored pictures with the server's rules. The creator edits while
 * [AttachedImageGalleryDto.canEdit];
 * otherwise the gallery is shown read-only with the lock text, and BOARD/ADMIN ([AttachedImageGalleryDto.canModerate]) can still remove
 * a
 * picture.
 *
 * Accessibility: every picture has an `alt` (the person's alternative text, else its caption, else "Bild N von {title}"), the three
 * icon
 * buttons carry
 * `aria-label`s with the picture number, the first/last arrow is `aria-disabled` (not natively disabled, so it stays focusable), and
 * removing a
 * picture asks inline first. Object URLs of the previews are revoked on remove, after upload and in [dispose].
 */
internal class ImageGalleryEditor(
    private val ownerType: AttachedImageOwnerType,
    private val ownerTitle: () -> String,
    private val backend: GalleryBackend = RpcGalleryBackend,
    private val onChange: () -> Unit = {},
    private val maxImages: Int = GalleryRules.maxImagesFor(ownerType),
) : SimplePanel() {
    /** A social post has no cover image, no re-ordering and no visible caption (V1.9.223). */
    private val coverAndOrder = GalleryRules.supportsCoverAndOrder(ownerType)
    private val withCaption = GalleryRules.supportsCaption(ownerType)
    private val pending = mutableListOf<PendingImage>()
    private var gallery: AttachedImageGalleryDto? = null
    private var ownerId: String? = null
    private var confirmingKey: String? = null
    private var counter = 0
    private var lockedByUpload = false
    private var uploadRunning = false
    private var captionProblem = false
    private val statusViews = HashMap<String, Div>()
    private val idPrefix = "lapis-gallery-${galleryEditorCounter++}"

    private val notice: Div
    private val lockNotice: Div
    private val controls: Div
    private val messages: Div
    private val list: Div
    private lateinit var pickUpload: Upload
    private lateinit var photoUpload: Upload

    init {
        addCssClass("lapis-gallery-editor")
        setAttribute("role", "group")
        setAttribute("aria-labelledby", "$idPrefix-label")
        div(tr("Bilder"), className = "fw-bold") { setAttribute("id", "$idPrefix-label") }
        notice =
            div(
                if (coverAndOrder) {
                    gettext(
                        "Höchstens %1 Bilder, je bis zu 5 MB (JPEG, PNG oder WebP). Das erste Bild ist das Titelbild.",
                        maxImages,
                    )
                } else {
                    gettext("Höchstens %1 Bilder, je bis zu 5 MB (JPEG, PNG oder WebP).", maxImages)
                },
                className = "text-muted small",
            )
        lockNotice = div(className = "small")
        lockNotice.hide()
        controls = div(className = "lapis-action-group")
        photoUpload = controls.upload(label = tr("Foto aufnehmen"), multiple = false)
        photoUpload.accept = listOf("image/*")
        photoUpload.capture = Capture.ENVIRONMENT
        pickUpload = controls.upload(label = tr("Bilder auswählen"), multiple = true)
        pickUpload.accept = GalleryRules.ACCEPT_ATTRIBUTE.split(",")
        photoUpload.onEvent { change = { takeFrom(photoUpload) } }
        pickUpload.onEvent { change = { takeFrom(pickUpload) } }
        messages = div(className = "text-danger small") { setAttribute("role", "alert") }
        list = div(className = "lapis-gallery-list")
        renderGallery()
    }

    // ── public API ────────────────────────────────────────────────────────

    fun pendingCount(): Int = pending.count { it.state !is PendingState.Done }

    /** A failed picture, or a stored picture whose caption / alternative text could not be saved (the message stays visible). */
    fun hasFailures(): Boolean = captionProblem || pending.any { it.state is PendingState.Failed }

    /** `true` once the server refused an upload because the gallery froze (a social post's 10-minute window ran out). */
    fun uploadWindowClosed(): Boolean = lockedByUpload

    /**
     * Back to an empty buffer without an owner (the composer of a social post, after a post was published and its pictures are done or
     * given up). Object URLs are revoked.
     */
    fun reset() {
        pending.forEach { dropPreview(it) }
        pending.clear()
        gallery = null
        ownerId = null
        confirmingKey = null
        lockedByUpload = false
        captionProblem = false
        messages.removeAll()
        renderGallery()
        onChange()
    }

    /** The server's view of the gallery (server mode). */
    fun showGallery(dto: AttachedImageGalleryDto) {
        gallery = dto
        ownerId = dto.ownerId
        renderGallery()
    }

    /**
     * Uploads every queued (or previously failed) picture of the buffer one after the other. Only ONE run is active at a time: a call
     * while another run is going returns `false` at once, and the active run picks up what was queued meanwhile (a second add, a
     * retry -- a retried picture is Queued again and is taken even if it failed earlier in this run), so no picture is sent twice.
     * `true` when nothing is left in the buffer (no failed, no still-queued picture) and no caption could not be saved.
     */
    suspend fun uploadPending(newOwnerId: String): Boolean {
        ownerId = newOwnerId
        if (uploadRunning) return false
        uploadRunning = true
        try {
            val attempted = HashSet<PendingImage>()
            val captionFailures = mutableListOf<String>()
            captionProblem = false
            while (true) {
                // Queued is always eligible (a retry re-queues a picture that failed earlier in this run); Failed only once per run
                val batch = pending.filter { it.state is PendingState.Queued || (it.state is PendingState.Failed && it !in attempted) }
                if (batch.isEmpty()) {
                    finishRun(newOwnerId, captionFailures)
                    // a picture added while the final list() was suspended: go on instead of leaving it Queued
                    if (pending.none { it.state is PendingState.Queued }) break
                    continue
                }
                for (item in batch) {
                    // removed by the user while an earlier picture was uploading
                    if (item !in pending) continue
                    attempted += item
                    if (lockedByUpload) {
                        item.state = PendingState.Failed(GalleryUploadErrorCode.GALLERY_LOCKED)
                        continue
                    }
                    item.state = PendingState.Uploading(0.0)
                    renderGallery()
                    val result =
                        backend.upload(ownerType = ownerType, ownerId = newOwnerId, file = item.file) { fraction ->
                            item.state = PendingState.Uploading(fraction)
                            statusViews[item.key]?.content = uploadingText(fraction)
                        }
                    when (result) {
                        is AttachedImageHttp.Result.Ok -> {
                            item.state = PendingState.Done
                            if (item.caption.isNotBlank() || item.altText.isNotBlank()) {
                                val saved =
                                    backend.updateCaption(result.id, item.caption.ifBlank { null }, item.altText.ifBlank { null })
                                if (saved == null) {
                                    captionFailures +=
                                        gettext(
                                            "„%1“: Bildunterschrift und Alternativtext konnten nicht gespeichert werden.",
                                            sanitizeUntrustedI18nText(item.file.name),
                                        )
                                }
                            }
                        }
                        is AttachedImageHttp.Result.Error -> {
                            item.state = PendingState.Failed(result.code)
                            if (result.code == GalleryUploadErrorCode.GALLERY_LOCKED) lockedByUpload = true
                        }
                    }
                    renderGallery()
                }
            }
            renderGallery()
            onChange()
            return pendingCount() == 0 && !captionProblem
        } finally {
            uploadRunning = false
        }
    }

    private suspend fun finishRun(
        newOwnerId: String,
        captionFailures: MutableList<String>,
    ) {
        pending.filter { it.state is PendingState.Done }.forEach { dropPreview(it) }
        pending.removeAll { it.state is PendingState.Done }
        if (captionFailures.isNotEmpty()) {
            captionProblem = true
            showMessages(captionFailures)
        }
        if (gallery != null) {
            backend.list(ownerType, newOwnerId)?.let { gallery = it }
        }
        renderGallery()
    }

    override fun dispose() {
        pending.forEach { dropPreview(it) }
        super.dispose()
    }

    // ── choosing files ────────────────────────────────────────────────────

    /** Takes the files of [source] into the buffer (pre-checked, unbinding), then empties the control so the same file can be chosen
     * again.
     * */
    internal fun takeFrom(source: Upload) {
        val chosen = source.value.orEmpty().mapNotNull { source.getNativeFile(it) }
        source.clearInput()
        addFiles(chosen)
    }

    internal fun addFiles(chosen: List<File>) {
        val refusals = mutableListOf<String>()
        val max = gallery?.maxImages ?: maxImages
        val tooMany = galleryUploadErrorText(GalleryUploadErrorCode.GALLERY_FULL, ownerType)
        chosen.forEach { file ->
            val problem = precheck(file)
            val stored = gallery?.images?.size ?: 0
            when {
                problem != null -> refusals += gettext("„%1“: %2", sanitizeUntrustedI18nText(file.name), problem)
                stored + pending.size >= max ->
                    refusals +=
                        gettext("„%1“: %2", sanitizeUntrustedI18nText(file.name), tooMany)
                else -> pending += PendingImage(key = "p${counter++}", file = file, previewUrl = URL.createObjectURL(file))
            }
        }
        showMessages(refusals)
        captionProblem = false
        renderGallery()
        onChange()
        val id = ownerId
        // also in buffer mode once the owner exists (create form kept open after creation)
        if (id != null && pending.any { it.state is PendingState.Queued }) {
            AppScope.launch { uploadPending(id) }
        }
    }

    private fun precheck(file: File): String? =
        when {
            file.size.toLong() > GalleryRules.MAX_UPLOAD_BYTES -> tr("Datei ist größer als 5 MB.")
            file.type !in GalleryRules.ACCEPT_ATTRIBUTE.split(",") -> tr("Nur JPEG, PNG oder WebP.")
            else -> null
        }

    private fun showMessages(texts: List<String>) {
        messages.removeAll()
        texts.distinct().forEach { messages.div(it) }
    }

    // ── rendering ─────────────────────────────────────────────────────────

    private fun renderGallery() {
        statusViews.clear()
        list.removeAll()
        val dto = gallery
        val editable = dto == null || dto.canEdit
        controls.visible = editable
        notice.visible = editable
        lockNotice.visible = dto?.lockReason != null
        if (dto?.lockReason != null) lockNotice.content = galleryLockText(dto.lockReason!!)
        if (dto != null) {
            val serverItems = dto.images
            serverItems.forEachIndexed { index, image -> buildServerItem(index, serverItems.size, image, dto) }
        }
        pending.forEachIndexed { index, item -> buildPendingItem(index, item, withOrder = dto == null) }
    }

    private fun title(): String = sanitizeUntrustedI18nText(ownerTitle())

    private fun altFor(
        number: Int,
        altText: String?,
        caption: String?,
    ): String = altText?.takeIf { it.isNotBlank() } ?: caption?.takeIf { it.isNotBlank() } ?: gettext("Bild %1 von %2", number, title())

    private fun uploadingText(fraction: Double): String = gettext("Wird hochgeladen … %1 %", (fraction * 100).toInt())

    private fun Container.thumb(
        url: String,
        alt: String,
    ) {
        image(url, alt, className = "lapis-gallery-thumb") {
            setAttribute("loading", "lazy")
            setAttribute("decoding", "async")
        }
    }

    private fun buildServerItem(
        index: Int,
        total: Int,
        image: AttachedImageDto,
        dto: AttachedImageGalleryDto,
    ) {
        val number = index + 1
        val card = list.vPanel(spacing = 4) { addCssClass("lapis-gallery-item") }
        card.thumb(image.url, altFor(number, image.altText, image.caption))
        if (coverAndOrder && index == 0) card.div(tr("Titelbild"), className = "lapis-gallery-cover-badge")
        if (dto.canEdit) {
            if (coverAndOrder) {
                orderControls(card, number = number, index = index, total = total, onUp = { moveServer(dto, index, index - 1) }, onDown = {
                    moveServer(dto, index, index + 1)
                })
                if (index >= 2) {
                    card.actionButton(ActionIcon.SET_COVER, tr("Als Titelbild"), style = ButtonStyle.LINK).onClick {
                        AppScope.launch {
                            backend.setCover(image.id)?.let {
                                showGallery(it)
                                onChange()
                            }
                        }
                    }
                }
            }
            // a social post's alternative text is set once, with the upload; later there is nothing to edit
            if (withCaption) {
                captionFields(card, image.caption.orEmpty(), image.altText.orEmpty()) { caption, alt ->
                    AppScope.launch {
                        backend.updateCaption(image.id, caption.ifBlank { null }, alt.ifBlank { null })?.let { gallery = it }
                    }
                }
            }
            removeControl(card, key = image.id, number = number, pendingItem = false) {
                AppScope.launch {
                    backend.delete(image.id)?.let {
                        showGallery(it)
                        onChange()
                    }
                }
            }
        } else {
            image.caption?.let { card.untrustedDiv(it, className = "small") }
            if (dto.canModerate) {
                removeControl(card, key = image.id, number = number, pendingItem = false) {
                    AppScope.launch {
                        backend.delete(image.id)?.let {
                            showGallery(it)
                            onChange()
                        }
                    }
                }
            }
        }
    }

    private fun buildPendingItem(
        index: Int,
        item: PendingImage,
        withOrder: Boolean,
    ) {
        val number = (gallery?.images?.size ?: 0) + index + 1
        val card = list.vPanel(spacing = 4) { addCssClass("lapis-gallery-item") }
        card.thumb(item.previewUrl, altFor(number, item.altText, item.caption))
        val ordered = withOrder && coverAndOrder
        if (ordered && index == 0) card.div(tr("Titelbild"), className = "lapis-gallery-cover-badge")
        val status = card.div(className = "small")
        statusViews[item.key] = status
        when (val state = item.state) {
            PendingState.Queued -> status.hide()
            is PendingState.Uploading -> status.content = uploadingText(state.fraction)
            PendingState.Done -> status.content = tr("Hochgeladen")
            is PendingState.Failed -> {
                status.addCssClass("text-danger")
                status.content = galleryUploadErrorText(state.code, ownerType)
                status.setAttribute("role", "alert")
                if (state.code != GalleryUploadErrorCode.GALLERY_LOCKED) {
                    card.actionButton(ActionIcon.REFRESH, tr("Erneut versuchen"), style = ButtonStyle.OUTLINESECONDARY).onClick {
                        item.state = PendingState.Queued
                        val id = ownerId
                        if (id != null) AppScope.launch { uploadPending(id) }
                    }
                }
            }
        }
        if (item.state is PendingState.Queued || item.state is PendingState.Failed) {
            if (ordered) {
                orderControls(card, number = number, index = index, total = pending.size, onUp = {
                    movePending(
                        index,
                        index - 1,
                    )
                }, onDown = {
                    movePending(index, index + 1)
                })
                if (index >=
                    2
                ) {
                    card
                        .actionButton(
                            ActionIcon.SET_COVER,
                            tr("Als Titelbild"),
                            style = ButtonStyle.LINK,
                        ).onClick { movePending(index, 0) }
                }
            }
            captionFields(card, item.caption, item.altText) { caption, alt ->
                item.caption = caption
                item.altText = alt
            }
            removeControl(card, key = item.key, number = number, pendingItem = true) {
                pending.remove(item)
                dropPreview(item)
                renderGallery()
                onChange()
            }
        }
    }

    private fun orderControls(
        card: SimplePanel,
        number: Int,
        index: Int,
        total: Int,
        onUp: () -> Unit,
        onDown: () -> Unit,
    ) {
        val group = card.div(className = "lapis-action-group")
        val up = group.tableActionButton(ActionIcon.MOVE_UP, resolvedAttributeText(gettext("Bild %1 nach oben", number)))
        val down = group.tableActionButton(ActionIcon.MOVE_DOWN, resolvedAttributeText(gettext("Bild %1 nach unten", number)))
        if (index == 0) up.setAttribute("aria-disabled", "true")
        if (index == total - 1) down.setAttribute("aria-disabled", "true")
        up.onClick { if (index > 0) onUp() }
        down.onClick { if (index < total - 1) onDown() }
    }

    /**
     * Caption (not for a social post) and alternative text of one picture. A social post has only the alternative text, which is an
     * optional description for people who cannot see the picture (V1.9.223); without one the client uses a generated text.
     */
    private fun captionFields(
        card: SimplePanel,
        caption: String,
        alt: String,
        onSave: (String, String) -> Unit,
    ) {
        val captionInput = if (withCaption) card.text(label = tr("Beschriftung")) { value = caption } else null
        val altLabel =
            if (withCaption) tr("Alternativtext") else tr("Bildbeschreibung (für Menschen, die das Bild nicht sehen)")
        val altInput = card.text(label = altLabel) { value = alt }
        captionInput?.input?.setAttribute("maxlength", GalleryRules.CAPTION_MAX.toString())
        altInput.input.setAttribute("maxlength", GalleryRules.ALT_MAX.toString())
        if (!withCaption) card.div(tr("Ohne Beschreibung wird ein Ersatztext verwendet."), className = "text-muted small")

        fun save() = onSave(captionInput?.value.orEmpty().trim(), altInput.value.orEmpty().trim())
        captionInput?.input?.onEvent { blur = { if (captionInput.value.orEmpty().trim() != caption) save() } }
        altInput.input.onEvent { blur = { if (altInput.value.orEmpty().trim() != alt) save() } }
    }

    /** The remove button; a click asks inline ("Bild entfernen?") before anything is removed. */
    private fun removeControl(
        card: SimplePanel,
        key: String,
        number: Int,
        pendingItem: Boolean,
        onConfirmed: () -> Unit,
    ) {
        if (confirmingKey == key) {
            val ask = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
            ask.div(tr("Bild entfernen?"), className = "small") { setAttribute("role", "alert") }
            val confirm = ask.actionButton(ActionIcon.DELETE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER, small = true)
            val cancel = ask.actionButton(ActionIcon.CANCEL, tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)
            confirm.onClick {
                confirmingKey = null
                onConfirmed()
            }
            cancel.onClick {
                confirmingKey = null
                renderGallery()
            }
            return
        }
        val remove =
            card.tableActionButton(
                if (pendingItem) ActionIcon.REMOVE else ActionIcon.DELETE,
                resolvedAttributeText(gettext("Bild %1 entfernen", number)),
                style = ButtonStyle.OUTLINEDANGER,
            )
        remove.onClick {
            confirmingKey = key
            renderGallery()
        }
    }

    private fun moveServer(
        dto: AttachedImageGalleryDto,
        from: Int,
        to: Int,
    ) {
        val ids = dto.images.map { it.id }.toMutableList()
        ids.add(to, ids.removeAt(from))
        AppScope.launch {
            backend.reorder(ownerType, dto.ownerId, ids)?.let {
                showGallery(it)
                onChange()
            }
        }
    }

    private fun movePending(
        from: Int,
        to: Int,
    ) {
        pending.add(to, pending.removeAt(from))
        renderGallery()
        onChange()
    }

    private fun dropPreview(item: PendingImage) {
        runCatching { URL.revokeObjectURL(item.previewUrl) }
    }
}

/** `imageGalleryEditor(...)` in the KVision DSL style. */
internal fun Container.imageGalleryEditor(
    ownerType: AttachedImageOwnerType,
    ownerTitle: () -> String,
    backend: GalleryBackend = RpcGalleryBackend,
    onChange: () -> Unit = {},
    maxImages: Int = GalleryRules.maxImagesFor(ownerType),
): ImageGalleryEditor {
    val editor =
        ImageGalleryEditor(ownerType = ownerType, ownerTitle = ownerTitle, backend = backend, onChange = onChange, maxImages = maxImages)
    add(editor)
    return editor
}

/**
 * Fills [this] with the gallery of one owner, LOADED ON DEMAND (call it when the disclosure that holds the host is first opened, so a
 * list
 * of
 * many cards makes no request per card). The creator gets the editor (server mode); BOARD/ADMIN get the editor in its read-only flavour
 * (each
 * picture can be removed); everybody else gets the plain viewer. The creator of a frozen gallery sees the lock text above the pictures.
 */
internal fun SimplePanel.loadGalleryInto(
    ownerType: AttachedImageOwnerType,
    ownerId: String,
    ownerTitle: String,
    backend: GalleryBackend = RpcGalleryBackend,
    onChange: () -> Unit = {},
) {
    val host = this
    host.removeAll()
    host.div(tr("Wird geladen …"), className = "text-muted small")
    AppScope.launch {
        val dto = backend.list(ownerType, ownerId)
        host.removeAll()
        if (dto == null) return@launch
        if (dto.canEdit || dto.canModerate) {
            host.imageGalleryEditor(ownerType, ownerTitle = { ownerTitle }, backend = backend, onChange = onChange).showGallery(dto)
            return@launch
        }
        val reason = dto.lockReason
        if (reason != null && dto.images.any { it.uploadedByMe }) host.div(galleryLockText(reason), className = "small")
        host.imageGalleryView(dto.images, ownerTitle)
    }
}

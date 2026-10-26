package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttachedImageDto
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostImagePreviewDto
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.rpc.IAttachedImageService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

// V1.9.223 "Bilder im Sozialen Netzwerk" -- the small, framework-free pieces around the pictures of a post: the feed preview (ONE
// picture and a "+N" badge), the single-column detail list, a native `<dialog>` for the large view, and the composer panel that
// uploads the buffered pictures AFTER the post exists. No carousel, no animation, no library.

/** A server call whose failure is not worth a toast (a missing picture is simply "no picture"); cancellation still propagates. */
private suspend fun <T> silently(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

/** The alternative text of a post picture: the author's own description, else a generated one that names position and author. */
internal fun socialImageAlt(
    image: AttachedImageDto,
    number: Int,
    total: Int,
    authorName: String,
): String =
    image.altText?.takeIf { it.isNotBlank() }
        ?: gettext("Bild %1 von %2 zum Beitrag von %3", number, total, sanitizeUntrustedI18nText(authorName))

/** The previews of the ROOT posts among [posts], fetched in chunks of the server's limit; any failure just means "no pictures". */
internal suspend fun loadSocialPreviews(posts: List<SocialPostDto>): Map<String, SocialPostImagePreviewDto> {
    val ids = posts.filter { it.depth == 0 && it.state == SocialPostState.VISIBLE }.map { it.id }
    val result = HashMap<String, SocialPostImagePreviewDto>()
    ids.chunked(GalleryRules.SOCIAL_PREVIEW_MAX_IDS).forEach { chunk ->
        silently { rpcService<IAttachedImageService>().listSocialPostPreviews(chunk) }?.forEach { result[it.postId] = it }
    }
    return result
}

private fun Div.asButton(
    label: String,
    activate: () -> Unit,
) {
    setAttribute("role", "button")
    setAttribute("tabindex", "0")
    setAttribute("aria-label", resolvedAttributeText(label))
    onEvent {
        click = { activate() }
        keydown = { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                activate()
            }
        }
    }
}

/**
 * The feed card's picture: the FIRST one in a 4:3 box (lazy, cropped to fill), "+N" bottom right when the post has more. The whole box
 * is one button; [onOpen] shows the post (detail view).
 */
internal fun Container.socialFeedImagePreview(
    preview: SocialPostImagePreviewDto,
    authorName: String,
    onOpen: () -> Unit,
) {
    val box = div(className = "lapis-social-image-box lapis-social-image-box--cover")
    box.asButton(tr("Thread öffnen"), onOpen)
    box.image(preview.first.url, socialImageAlt(preview.first, number = 1, total = preview.totalCount, authorName = authorName)) {
        setAttribute("loading", "lazy")
        setAttribute("decoding", "async")
    }
    val more = preview.totalCount - 1
    if (more > 0) {
        box.div("+$more", className = "lapis-social-image-more") {
            setAttribute("role", "img")
            setAttribute("aria-label", resolvedAttributeText(gettext("%1 weitere Bilder", more)))
        }
    }
}

/** The detail view: all pictures one below the other, each shown whole (`contain`) in a 4:3 box; a tap opens the large view. */
internal fun Container.socialPostImagesDetail(
    images: List<AttachedImageDto>,
    authorName: String,
) {
    if (images.isEmpty()) return
    val column = div(className = "lapis-social-image-column")
    images.forEachIndexed { index, image ->
        val alt = socialImageAlt(image, number = index + 1, total = images.size, authorName = authorName)
        val box = column.div(className = "lapis-social-image-box lapis-social-image-box--contain")
        box.asButton(alt) { openSocialImageDialog(images = images, startIndex = index, authorName = authorName) }
        box.image(image.url, alt) {
            setAttribute("loading", "lazy")
            setAttribute("decoding", "async")
        }
    }
}

/**
 * The large view in a native modal `<dialog>`: the picture (at most 90 % of the viewport height), the alternative text as a visible
 * line, previous / next (the edge button is `aria-disabled`, not removed, so focus never jumps) and close. Esc closes natively; the
 * focus returns to the element that opened it; the element leaves the DOM on close.
 */
internal fun openSocialImageDialog(
    images: List<AttachedImageDto>,
    startIndex: Int,
    authorName: String,
) {
    if (images.isEmpty()) return
    val opener = document.activeElement as? HTMLElement
    val dialog = document.createElement("dialog") as HTMLElement
    dialog.className = "lapis-social-dialog"
    dialog.setAttribute("aria-label", resolvedAttributeText(gettext("Bild %1 von %2", 1, images.size)))
    val picture = document.createElement("img") as HTMLImageElement
    picture.className = "lapis-social-dialog-image"
    val caption = document.createElement("p") as HTMLElement
    caption.className = "small lapis-social-dialog-caption"
    val bar = document.createElement("div") as HTMLElement
    bar.className = "lapis-action-group"

    fun control(text: String): HTMLButtonElement {
        val b = document.createElement("button") as HTMLButtonElement
        b.type = "button"
        b.className = "btn btn-outline-secondary"
        b.textContent = text
        return b
    }
    val previous = control(gettext("Vorheriges"))
    val next = control(gettext("Nächstes"))
    val close = control(gettext("Schließen"))
    bar.appendChild(previous)
    bar.appendChild(next)
    bar.appendChild(close)
    dialog.appendChild(picture)
    dialog.appendChild(caption)
    dialog.appendChild(bar)

    var index = startIndex.coerceIn(0, images.size - 1)

    fun show() {
        val image = images[index]
        val alt = socialImageAlt(image, number = index + 1, total = images.size, authorName = authorName)
        picture.src = image.url
        picture.alt = alt
        caption.textContent = alt
        dialog.setAttribute("aria-label", resolvedAttributeText(gettext("Bild %1 von %2", index + 1, images.size)))
        previous.setAttribute("aria-disabled", (index == 0).toString())
        next.setAttribute("aria-disabled", (index == images.size - 1).toString())
    }
    previous.addEventListener(
        "click",
        {
            if (index > 0) {
                index--
                show()
            }
        },
    )
    next.addEventListener(
        "click",
        {
            if (index < images.size - 1) {
                index++
                show()
            }
        },
    )
    close.addEventListener("click", { dialog.asDynamic().close() })
    dialog.addEventListener(
        "close",
        {
            dialog.parentNode?.removeChild(dialog)
            opener?.focus()
        },
    )
    show()
    document.body?.appendChild(dialog)
    dialog.asDynamic().showModal()
    close.focus()
}

/** The pictures of a ROOT post in the thread view: the author inside the window (and BOARD/ADMIN) get the editor, everybody else the list. */
internal fun SimplePanel.socialThreadImages(post: SocialPostDto) {
    val host = simplePanel()
    AppScope.launch {
        val dto = silently { rpcService<IAttachedImageService>().list(AttachedImageOwnerType.SOCIAL_POST, post.id) } ?: return@launch
        if (dto.canEdit || (dto.canModerate && dto.images.isNotEmpty())) {
            host
                .imageGalleryEditor(
                    AttachedImageOwnerType.SOCIAL_POST,
                    ownerTitle = { tr("Ihr Beitrag") },
                    maxImages = GalleryRules.SOCIAL_POST_MAX_IMAGES,
                ).showGallery(dto)
        } else {
            host.socialPostImagesDetail(dto.images, post.authorDisplayName)
        }
    }
}

/**
 * The picture part of the composer. The chosen pictures stay on this device until the post exists; [afterPublish] then uploads them one
 * after the other. A post cannot have a draft state, so the server accepts additions for ten minutes only: a picture that fails is
 * reported with its reason ("veröffentlicht, aber ...") and can be retried until the window closes. [onFinished] runs when everything
 * is uploaded or the person gave up ("Fertig").
 */
internal class SocialComposerImages(
    backend: GalleryBackend = RpcGalleryBackend,
    private val onFinished: () -> Unit,
) : SimplePanel() {
    val editor: ImageGalleryEditor
    private val problem: Div
    private val actions: SimplePanel
    private var postId: String? = null

    init {
        editor =
            imageGalleryEditor(
                AttachedImageOwnerType.SOCIAL_POST,
                ownerTitle = { tr("Ihr Beitrag") },
                backend = backend,
                // a retry on a single picture (its own button) can also complete the batch
                onChange = { if (postId != null && editor.pendingCount() == 0 && !editor.hasFailures()) finish() },
                maxImages = GalleryRules.SOCIAL_POST_MAX_IMAGES,
            )
        problem = div(className = "text-danger small") { setAttribute("role", "alert") }
        problem.hide()
        actions = hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        actions.hide()
    }

    fun hasPending(): Boolean = editor.pendingCount() > 0

    /** Uploads the buffer to the freshly published post. Returns when everything is done or the problem message is on screen. */
    suspend fun afterPublish(newPostId: String) {
        postId = newPostId
        attempt()
    }

    private suspend fun attempt() {
        val id = postId ?: return
        problem.hide()
        actions.hide()
        if (editor.uploadPending(id)) {
            finish()
            return
        }
        showProblem()
    }

    private fun showProblem() {
        val expired = editor.uploadWindowClosed()
        problem.content =
            if (expired) {
                galleryLockedUploadText(AttachedImageOwnerType.SOCIAL_POST)
            } else {
                gettext(
                    "Der Beitrag ist veröffentlicht, aber %1 Bild(er) konnten nicht hochgeladen werden. " +
                        "Sie können es in den nächsten Minuten erneut versuchen.",
                    editor.pendingCount(),
                )
            }
        problem.show()
        actions.removeAll()
        if (!expired) {
            actions.actionButton(ActionIcon.REFRESH, tr("Erneut versuchen"), style = ButtonStyle.OUTLINESECONDARY).onClick {
                AppScope.launch { attempt() }
            }
        }
        actions.button(tr("Fertig"), style = ButtonStyle.OUTLINESECONDARY).onClick { finish() }
        actions.show()
    }

    private fun finish() {
        if (postId == null) return
        postId = null
        problem.hide()
        actions.hide()
        editor.reset()
        onFinished()
    }

    /** Throws the buffer away without publishing anything (a failed `createPost` keeps it; this is for an explicit cancel). */
    fun clear() {
        postId = null
        problem.hide()
        actions.hide()
        editor.reset()
    }
}

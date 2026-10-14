package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayRules
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * V1.9.129 "Video-Overlays (Logos, Name, Rahmen) im eigenen Kamerabild" -- reine, DOM-freie Logik (jsTest-gedeckt): Einstellungen und
 * ihre Persistenz, Aufloesung gegen die Bibliothek, Geometrie der vier Ecken, Zeichenplan, Namens-Pille, Zustandsautomat. Das Zeichnen in
 * den Kamerastrom steht in `ConferenceVideoOverlayCompositor.kt`, die Oberflaeche in `ConferenceVideoOverlaySection.kt`; Architektur:
 * `docs/architecture/video-background-effects.adoc` (Abschnitt "Overlay step") und `docs/features/video-overlays.adoc`.
 *
 * **Begriff**: "Video-Overlays" stecken IM gesendeten Kamerabild und sind damit fuer alle sichtbar -- auch in Aufnahme und Livestream. Das
 * ist das Gegenteil der DOM-Overlays aus V1.9.127 (Reaktionen, Hand-Abzeichen), die nie in Aufnahme und Stream gelangen.
 *
 * **tr() vs. gettext()**: Texte, die in ein Attribut, einen Toast oder als Argument in ein weiteres `gettext(...)` fliessen, sind
 * `gettext(...)`; Texte, die DIREKT als Widget-Inhalt an KVision gehen, sind `tr(...)` (siehe `ConferenceBackgroundEffects.kt`).
 *
 * **Ablageort**: bewusst direkt unter `.../client/` (flach) -- die i18n-Waechter scannen dieses Verzeichnis nach Dateinamen.
 */

internal enum class OverlayCorner(
    val id: String,
) {
    TOP_LEFT("tl"),
    TOP_RIGHT("tr"),
    BOTTOM_LEFT("bl"),
    BOTTOM_RIGHT("br"),
}

/** Hoehe des Elements als Anteil der KURZEN Bildkante. */
internal enum class OverlaySize(
    val id: String,
    val heightFraction: Double,
) {
    SMALL("s", 0.10),
    MEDIUM("m", 0.15),
    LARGE("l", 0.22),
}

internal enum class OverlayOpacity(
    val id: String,
    val alpha: Double,
) {
    FULL("100", 1.0),
    STRONG("70", 0.7),
    LIGHT("40", 0.4),
}

internal sealed interface OverlayContent {
    data object Empty : OverlayContent

    data object OwnName : OverlayContent

    /** [assetId] is a canonical UUID or [VideoOverlayRules.INSTANCE_LOGO_ID] -- see [isValidOverlayAssetId]. */
    data class Library(
        val assetId: String,
    ) : OverlayContent
}

internal data class CornerSlot(
    val content: OverlayContent = OverlayContent.Empty,
    val size: OverlaySize = OverlaySize.MEDIUM,
    val opacity: OverlayOpacity = OverlayOpacity.FULL,
)

internal data class FrameSlot(
    val assetId: String,
    val opacity: OverlayOpacity = OverlayOpacity.FULL,
)

internal data class VideoOverlaySettings(
    val enabled: Boolean = false,
    val corners: Map<OverlayCorner, CornerSlot> = emptyMap(),
    val frame: FrameSlot? = null,
) {
    fun slot(corner: OverlayCorner): CornerSlot = corners[corner] ?: CornerSlot()
}

internal const val CONFERENCE_VIDEO_OVERLAY_STORAGE_KEY = "lapis-cloud-conference-video-overlays"

private const val STORAGE_VERSION = 1
private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val OVERLAY_PATH = Regex("^/api/video-overlays/[A-Za-z0-9_-]{43}$")
private const val INSTANCE_LOGO_PATH = "/api/branding/logo"

/** A canonical lower-case UUID or exactly [VideoOverlayRules.INSTANCE_LOGO_ID]. No trim, no case folding -- a manipulated value never "almost" matches. */
internal fun isValidOverlayAssetId(id: String): Boolean = id == VideoOverlayRules.INSTANCE_LOGO_ID || CANONICAL_UUID.matches(id)

/**
 * Persisted value -> settings. EVERY deviation (not JSON, wrong version, unknown corner, unknown size or opacity, an id that is not
 * canonical, extra structure of the wrong type) yields the default [VideoOverlaySettings] -- never a partial parse.
 */
internal fun parseStoredVideoOverlaySettings(raw: String?): VideoOverlaySettings {
    if (raw == null) return VideoOverlaySettings()
    return runCatching { parseStrict(raw) }.getOrNull() ?: VideoOverlaySettings()
}

private fun parseStrict(raw: String): VideoOverlaySettings? {
    val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
    if (root["v"]?.jsonPrimitive?.intOrNull != STORAGE_VERSION) return null
    val enabled = root["on"]?.jsonPrimitive?.booleanOrNull ?: return null
    val corners = mutableMapOf<OverlayCorner, CornerSlot>()
    val cornerObject = root["c"]?.let { it as? JsonObject ?: return null }
    if (cornerObject != null) {
        for ((key, value) in cornerObject) {
            val corner = OverlayCorner.entries.firstOrNull { it.id == key } ?: return null
            val slot = value as? JsonObject ?: return null
            val content =
                when (slot["k"]?.jsonPrimitive?.contentOrNull) {
                    "empty" -> OverlayContent.Empty
                    "name" -> OverlayContent.OwnName
                    "lib" -> {
                        val id = slot["a"]?.jsonPrimitive?.contentOrNull ?: return null
                        if (!isValidOverlayAssetId(id)) return null
                        OverlayContent.Library(id)
                    }
                    else -> return null
                }
            val size = OverlaySize.entries.firstOrNull { it.id == slot["s"]?.jsonPrimitive?.contentOrNull } ?: return null
            val opacity = OverlayOpacity.entries.firstOrNull { it.id == slot["o"]?.jsonPrimitive?.contentOrNull } ?: return null
            corners[corner] = CornerSlot(content = content, size = size, opacity = opacity)
        }
    }
    val frameObject = root["f"]?.let { it as? JsonObject ?: return null }
    val frame =
        frameObject?.let {
            val id = it["a"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!isValidOverlayAssetId(id)) return null
            val opacity = OverlayOpacity.entries.firstOrNull { o -> o.id == it["o"]?.jsonPrimitive?.contentOrNull } ?: return null
            FrameSlot(assetId = id, opacity = opacity)
        }
    return VideoOverlaySettings(enabled = enabled, corners = corners, frame = frame)
}

/** Value for `localStorage`; `null` == REMOVE the key (the default is never written -- lesson of the `deviceId` bug, V1.4.19). */
internal fun videoOverlayPersistValue(settings: VideoOverlaySettings): String? {
    val anyCorner = settings.corners.values.any { it.content != OverlayContent.Empty }
    if (!settings.enabled && !anyCorner && settings.frame == null) return null
    val json =
        buildJsonObject {
            put("v", STORAGE_VERSION)
            put("on", settings.enabled)
            putJsonObject("c") {
                for (corner in OverlayCorner.entries) {
                    val slot = settings.corners[corner] ?: continue
                    if (slot.content == OverlayContent.Empty) continue
                    putJsonObject(corner.id) {
                        when (val content = slot.content) {
                            OverlayContent.Empty -> put("k", "empty")
                            OverlayContent.OwnName -> put("k", "name")
                            is OverlayContent.Library -> {
                                put("k", "lib")
                                put("a", content.assetId)
                            }
                        }
                        put("s", slot.size.id)
                        put("o", slot.opacity.id)
                    }
                }
            }
            settings.frame?.let { frame ->
                putJsonObject("f") {
                    put("a", frame.assetId)
                    put("o", frame.opacity.id)
                }
            }
        }
    return json.toString()
}

/** Reads the remembered settings; `localStorage` may be blocked, then the default applies. */
internal fun loadStoredVideoOverlaySettings(): VideoOverlaySettings =
    parseStoredVideoOverlaySettings(
        try {
            localStorage[CONFERENCE_VIDEO_OVERLAY_STORAGE_KEY]
        } catch (e: Throwable) {
            null
        },
    )

/** Writes (or, for the default, removes) the remembered settings; a blocked storage only means "for this session". */
internal fun storeVideoOverlaySettings(settings: VideoOverlaySettings) {
    try {
        val value = videoOverlayPersistValue(settings)
        if (value == null) {
            localStorage.removeItem(CONFERENCE_VIDEO_OVERLAY_STORAGE_KEY)
        } else {
            localStorage[CONFERENCE_VIDEO_OVERLAY_STORAGE_KEY] = value
        }
    } catch (e: Throwable) {
        // Storage blocked -- the choice then only lasts for this session.
    }
}

/**
 * The ONLY place an overlay image URL comes into being: `/api/video-overlays/<43-character token>` or exactly `/api/branding/logo` for the
 * instance logo. Anything else (another origin, a `javascript:` URL, a traversal, a data URL) is `null`.
 */
internal fun videoOverlayAssetPath(item: VideoOverlayItemDto): String? {
    if (!isValidOverlayAssetId(item.id)) return null
    return if (item.id == VideoOverlayRules.INSTANCE_LOGO_ID) {
        if (item.imagePath == INSTANCE_LOGO_PATH && item.instanceLogo) INSTANCE_LOGO_PATH else null
    } else {
        if (!item.instanceLogo && OVERLAY_PATH.matches(item.imagePath)) item.imagePath else null
    }
}

/**
 * Settings resolved against the CURRENT library: a corner whose element no longer exists (deleted, kind changed, never offered) becomes
 * empty, a missing frame disappears -- silently, because a deleted element is not an error to alarm anyone with.
 */
internal fun resolveVideoOverlaySettings(
    settings: VideoOverlaySettings,
    library: List<VideoOverlayItemDto>,
): VideoOverlaySettings {
    val logos = library.filter { it.kind == VideoOverlayKind.LOGO }.map { it.id }.toSet()
    val frames = library.filter { it.kind == VideoOverlayKind.FRAME }.map { it.id }.toSet()
    val corners =
        settings.corners.mapValues { (_, slot) ->
            val content = slot.content
            if (content is OverlayContent.Library && content.assetId !in logos) slot.copy(content = OverlayContent.Empty) else slot
        }
    val frame = settings.frame?.takeIf { it.assetId in frames }
    return settings.copy(corners = corners, frame = frame)
}

internal sealed interface SceneCorner {
    val size: OverlaySize
    val opacity: OverlayOpacity

    data class Image(
        val item: VideoOverlayItemDto,
        override val size: OverlaySize,
        override val opacity: OverlayOpacity,
    ) : SceneCorner

    data class Name(
        val text: String,
        override val size: OverlaySize,
        override val opacity: OverlayOpacity,
    ) : SceneCorner
}

internal data class SceneFrame(
    val item: VideoOverlayItemDto,
    val opacity: OverlayOpacity,
)

/** What is drawn into the camera image, fully resolved. Never empty: [effectiveVideoOverlayScene] returns `null` instead. */
internal data class VideoOverlayScene(
    val corners: Map<OverlayCorner, SceneCorner>,
    val frame: SceneFrame?,
)

/**
 * The Tesler/Jobs rule "no invisible mode": `null` (no drawing step at all, zero cost) unless the option is offered, this browser can run
 * the processor, the person switched the overlays on AND at least one corner or the frame actually has something to draw.
 */
internal fun effectiveVideoOverlayScene(
    settings: VideoOverlaySettings,
    library: List<VideoOverlayItemDto>,
    offered: Boolean,
    availability: ConferenceBackgroundAvailability,
    displayName: String,
): VideoOverlayScene? {
    if (!offered || availability != ConferenceBackgroundAvailability.AVAILABLE || !settings.enabled) return null
    val resolved = resolveVideoOverlaySettings(settings, library)
    val byId = library.associateBy { it.id }
    val corners = mutableMapOf<OverlayCorner, SceneCorner>()
    for (corner in OverlayCorner.entries) {
        val slot = resolved.corners[corner] ?: continue
        when (val content = slot.content) {
            OverlayContent.Empty -> Unit
            OverlayContent.OwnName -> {
                val text = displayName.trim()
                if (text.isNotEmpty()) corners[corner] = SceneCorner.Name(text = text, size = slot.size, opacity = slot.opacity)
            }
            is OverlayContent.Library -> {
                val item = byId[content.assetId]
                if (item != null && videoOverlayAssetPath(item) != null) {
                    corners[corner] = SceneCorner.Image(item = item, size = slot.size, opacity = slot.opacity)
                }
            }
        }
    }
    val frame =
        resolved.frame?.let { slot ->
            byId[slot.assetId]?.takeIf { videoOverlayAssetPath(it) != null }?.let { SceneFrame(item = it, opacity = slot.opacity) }
        }
    if (corners.isEmpty() && frame == null) return null
    return VideoOverlayScene(corners = corners, frame = frame)
}

// ── Geometry ──────────────────────────────────────────────────────────────────────────

internal data class OverlayRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** [aspect] = content width / content height. */
internal data class OverlayItemGeometry(
    val size: OverlaySize,
    val aspect: Double,
)

private const val CORNER_MARGIN_FRACTION = 0.04
private const val MAX_WIDTH_FRACTION = 0.40

/**
 * Rectangles of the corner elements in a [frameWidth] x [frameHeight] image (Duarte/Atkinson). Rules:
 * - margin `m = 0.04 * short edge`, height `h = fraction * short edge`, width `w = h * aspect`;
 * - a width above 40 % of the frame is scaled down proportionally;
 * - if two elements of a row (top: TL+TR, bottom: BL+BR) would not fit (`wL + wR + 3m > W`) both are scaled by `(W-3m)/(wL+wR)`;
 *   the same per column with the heights;
 * - integer pixels, always inside the frame. Two elements never overlap (a diagonal pair cannot: each is at most 40 % wide).
 */
internal fun videoOverlayCornerRects(
    frameWidth: Int,
    frameHeight: Int,
    items: Map<OverlayCorner, OverlayItemGeometry>,
): Map<OverlayCorner, OverlayRect> {
    if (frameWidth <= 0 || frameHeight <= 0 || items.isEmpty()) return emptyMap()
    val w = frameWidth.toDouble()
    val h = frameHeight.toDouble()
    val short = min(w, h)
    val margin = CORNER_MARGIN_FRACTION * short
    val widths = mutableMapOf<OverlayCorner, Double>()
    val heights = mutableMapOf<OverlayCorner, Double>()
    for ((corner, geometry) in items) {
        var boxHeight = geometry.size.heightFraction * short
        var boxWidth = boxHeight * geometry.aspect
        if (boxWidth > MAX_WIDTH_FRACTION * w) {
            val factor = MAX_WIDTH_FRACTION * w / boxWidth
            boxWidth *= factor
            boxHeight *= factor
        }
        widths[corner] = boxWidth
        heights[corner] = boxHeight
    }

    fun scalePair(
        a: OverlayCorner,
        b: OverlayCorner,
        along: MutableMap<OverlayCorner, Double>,
        available: Double,
    ) {
        val first = along[a] ?: return
        val second = along[b] ?: return
        if (first + second + 3 * margin > available) {
            val factor = (available - 3 * margin) / (first + second)
            for (corner in listOf(a, b)) {
                widths[corner] = widths.getValue(corner) * factor
                heights[corner] = heights.getValue(corner) * factor
            }
        }
    }
    scalePair(OverlayCorner.TOP_LEFT, OverlayCorner.TOP_RIGHT, widths, w)
    scalePair(OverlayCorner.BOTTOM_LEFT, OverlayCorner.BOTTOM_RIGHT, widths, w)
    scalePair(OverlayCorner.TOP_LEFT, OverlayCorner.BOTTOM_LEFT, heights, h)
    scalePair(OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_RIGHT, heights, h)
    val result = mutableMapOf<OverlayCorner, OverlayRect>()
    for (corner in items.keys) {
        val boxWidth = widths.getValue(corner)
        val boxHeight = heights.getValue(corner)
        val x =
            when (corner) {
                OverlayCorner.TOP_LEFT, OverlayCorner.BOTTOM_LEFT -> margin
                OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_RIGHT -> w - margin - boxWidth
            }
        val y =
            when (corner) {
                OverlayCorner.TOP_LEFT, OverlayCorner.TOP_RIGHT -> margin
                OverlayCorner.BOTTOM_LEFT, OverlayCorner.BOTTOM_RIGHT -> h - margin - boxHeight
            }
        val px = max(0, floor(x).toInt())
        val py = max(0, floor(y).toInt())
        val pw = min(max(1, boxWidth.roundToInt()), frameWidth - px).coerceAtLeast(1)
        val ph = min(max(1, boxHeight.roundToInt()), frameHeight - py).coerceAtLeast(1)
        result[corner] = OverlayRect(x = px, y = py, width = pw, height = ph)
    }
    return result
}

internal sealed interface OverlayDrawOp {
    val alpha: Double

    /** The frame always fills the whole image. */
    data class Frame(
        val item: VideoOverlayItemDto,
        override val alpha: Double,
    ) : OverlayDrawOp

    data class Image(
        val corner: OverlayCorner,
        val item: VideoOverlayItemDto,
        val rect: OverlayRect,
        override val alpha: Double,
    ) : OverlayDrawOp

    data class Name(
        val corner: OverlayCorner,
        val text: String,
        val rect: OverlayRect,
        override val alpha: Double,
    ) : OverlayDrawOp
}

/**
 * The drawing order of one frame: first the frame, then the corners top-left, top-right, bottom-left, bottom-right. [aspectOf] answers the
 * content aspect (width / height) of a corner element -- the decoded bitmap for an image, the measured pill for a name.
 */
internal fun videoOverlayDrawPlan(
    scene: VideoOverlayScene,
    frameWidth: Int,
    frameHeight: Int,
    aspectOf: (SceneCorner) -> Double,
): List<OverlayDrawOp> {
    val ops = mutableListOf<OverlayDrawOp>()
    scene.frame?.let { ops += OverlayDrawOp.Frame(item = it.item, alpha = it.opacity.alpha) }
    val geometry = scene.corners.mapValues { (_, corner) -> OverlayItemGeometry(size = corner.size, aspect = aspectOf(corner)) }
    val rects = videoOverlayCornerRects(frameWidth = frameWidth, frameHeight = frameHeight, items = geometry)
    for (corner in OverlayCorner.entries) {
        val element = scene.corners[corner] ?: continue
        val rect = rects[corner] ?: continue
        ops +=
            when (element) {
                is SceneCorner.Image ->
                    OverlayDrawOp.Image(
                        corner = corner,
                        item = element.item,
                        rect = rect,
                        alpha = element.opacity.alpha,
                    )
                is SceneCorner.Name -> OverlayDrawOp.Name(corner = corner, text = element.text, rect = rect, alpha = element.opacity.alpha)
            }
    }
    return ops
}

// ── Name pill ─────────────────────────────────────────────────────────────────────────

internal data class NamePillMetrics(
    val fontPx: Double,
    val padPx: Double,
    val maxTextWidthPx: Double,
)

/** Font = 45 % of the box height, horizontal padding = 35 % of the font, the text may use at most 40 % of the frame width minus the padding. */
internal fun namePillMetrics(
    boxHeightPx: Double,
    frameWidthPx: Int,
): NamePillMetrics {
    val font = boxHeightPx * 0.45
    val pad = font * 0.35
    return NamePillMetrics(fontPx = font, padPx = pad, maxTextWidthPx = max(1.0, MAX_WIDTH_FRACTION * frameWidthPx - 2 * pad))
}

private const val ELLIPSIS = "…"

/**
 * The text of the name pill: `null` for a blank name (the corner then stays empty), the name unchanged when it fits, otherwise the longest
 * grapheme prefix followed by "…" that fits. A grapheme (family emoji, combining accent) is never cut in half: [graphemes] splits the text.
 */
internal fun truncateDisplayName(
    name: String,
    maxWidthPx: Double,
    measure: (String) -> Double,
    graphemes: (String) -> List<String>,
): String? {
    val text = name.trim()
    if (text.isEmpty()) return null
    if (measure(text) <= maxWidthPx) return text
    val parts = graphemes(text)
    var low = 0
    var high = parts.size
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (measure(parts.take(mid).joinToString("") + ELLIPSIS) <= maxWidthPx) low = mid else high = mid - 1
    }
    return parts.take(low).joinToString("") + ELLIPSIS
}

// ── State ─────────────────────────────────────────────────────────────────────────────

internal enum class VideoOverlayPhase { OFF, APPLYING, ACTIVE, FAILED_FALLBACK }

internal enum class VideoOverlayFailure { LOAD_FAILED, DRAW_FAILED }

internal data class VideoOverlayState(
    val phase: VideoOverlayPhase = VideoOverlayPhase.OFF,
    /** One message per cause per session -- no toast rain. */
    val notifiedFailures: Set<VideoOverlayFailure> = emptySet(),
)

internal sealed interface VideoOverlayEvent {
    /** A scene to draw was handed over (images are being loaded). */
    data object SceneRequested : VideoOverlayEvent

    /** Everything is loaded and the scene is in the drawing step. */
    data object SceneActive : VideoOverlayEvent

    /** Nothing to draw any more (switched off, library empty, camera off). */
    data object SceneCleared : VideoOverlayEvent

    data class Failed(
        val failure: VideoOverlayFailure,
    ) : VideoOverlayEvent
}

/** Pure reducer, throws for no combination. A failure never touches the person's settings (they live outside this state). */
internal fun videoOverlayReduce(
    current: VideoOverlayState,
    event: VideoOverlayEvent,
): VideoOverlayState =
    when (event) {
        VideoOverlayEvent.SceneRequested -> current.copy(phase = VideoOverlayPhase.APPLYING)
        VideoOverlayEvent.SceneActive -> current.copy(phase = VideoOverlayPhase.ACTIVE)
        VideoOverlayEvent.SceneCleared -> current.copy(phase = VideoOverlayPhase.OFF)
        is VideoOverlayEvent.Failed ->
            current.copy(
                phase = VideoOverlayPhase.FAILED_FALLBACK,
                notifiedFailures = current.notifiedFailures + event.failure,
            )
    }

internal fun videoOverlayShouldNotify(
    state: VideoOverlayState,
    failure: VideoOverlayFailure,
): Boolean = failure !in state.notifiedFailures

/** Two fixed, translated sentences -- never a raw error text, a URL or a file name (logging/privacy rule). */
internal fun videoOverlayFailureMessage(failure: VideoOverlayFailure): String =
    when (failure) {
        VideoOverlayFailure.LOAD_FAILED -> gettext("Ein Overlay konnte nicht geladen werden – die Kamera läuft ohne dieses Overlay weiter.")
        VideoOverlayFailure.DRAW_FAILED -> gettext("Die Overlays konnten nicht gezeichnet werden – die Kamera läuft ohne Overlays weiter.")
    }

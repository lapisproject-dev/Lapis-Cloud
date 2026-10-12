package network.lapis.cloud.shared.conference

import kotlin.math.abs
import kotlin.math.ceil

/**
 * V1.9.115 -- the pure layout of the switchable views of a video conference (live view in `lapis-client`). No DOM, no clock, no I/O.
 *
 * The four views: [ConferenceView.GALLERY] (tile grid, paged), [ConferenceView.SPEAKER] (the current speaker large, a filmstrip below),
 * [ConferenceView.PINNED] (one chosen person large), [ConferenceView.SCREEN_SHARE] (the newest screen share large).
 */
enum class ConferenceView { GALLERY, SPEAKER, PINNED, SCREEN_SHARE }

/** A person in the room. [joinOrder] is a monotone counter of when this client first saw the person (self may be anything). */
data class ConferenceViewParticipant(
    val identity: String,
    val joinOrder: Long,
    val hasVideo: Boolean,
)

/** A running screen share; the NEWEST (highest [startOrder]) is the large one. */
data class ConferenceViewScreenShare(
    val identity: String,
    val startOrder: Long,
)

/** A tile: a person's camera or a person's screen share. */
data class ConferenceTileRef(
    val identity: String,
    val screenShare: Boolean,
    val hasVideo: Boolean,
) {
    val key: String get() = if (screenShare) "$identity#screen" else identity
}

data class ConferenceViewInput(
    val view: ConferenceView,
    /** Everybody in the room, self included. */
    val participants: List<ConferenceViewParticipant>,
    val selfId: String,
    /** The SMOOTHED main speaker ([SpeakerStageSmoother]); the caller already left self out. */
    val mainSpeakerId: String?,
    /** The raw "is speaking" mark set -- only used for the page-arrow hint of the gallery. */
    val speakingIds: Set<String>,
    val pinnedId: String?,
    val screenShares: List<ConferenceViewScreenShare>,
    /** The view the user had before the share started (what [ConferenceView.SCREEN_SHARE] falls back to when the last share ends). */
    val fallbackView: ConferenceView = ConferenceView.GALLERY,
    val page: Int,
    val pageSize: Int,
)

data class ConferenceViewLayout(
    val effectiveView: ConferenceView,
    /** The large tile; `null` only when the room is empty. */
    val main: ConferenceTileRef?,
    /** The filmstrip (SPEAKER / PINNED / SCREEN_SHARE), in display order. Empty in the gallery. */
    val strip: List<ConferenceTileRef>,
    /** The tiles of the current gallery page (GALLERY only). */
    val galleryPage: List<ConferenceTileRef>,
    /** The current page, clamped to `0 until pageCount`. */
    val page: Int,
    val pageCount: Int,
    /** GALLERY: the page to turn to in order to reach a speaking person, `null` when somebody on this page speaks or nobody does. */
    val speakerPage: Int?,
    /** PINNED was asked for but the pinned person left: the view is SPEAKER now (the client announces it). */
    val pinnedLeft: Boolean,
)

/** Gallery page size by the width of the video area: wide 25 (5x5), medium 9 (3x3), narrow 4 (2x2). Unusable widths answer the smallest. */
fun conferenceGalleryPageSize(areaWidthPx: Double): Int =
    when {
        !areaWidthPx.isFinite() || areaWidthPx <= 0.0 -> 4
        areaWidthPx >= 1024.0 -> 25
        areaWidthPx >= 600.0 -> 9
        else -> 4
    }

/** Height of a filmstrip tile in CSS pixels. */
fun conferenceFilmstripTileHeight(areaWidthPx: Double): Int = if (areaWidthPx.isFinite() && areaWidthPx >= 600.0) 112 else 54

/** The recording shows at most this many tiles in the strip below the large one. */
const val CONFERENCE_SPEAKER_STRIP_MAX_RECORDING = 5

private fun ConferenceViewParticipant.tile() = ConferenceTileRef(identity = identity, screenShare = false, hasVideo = hasVideo)

/** Total: any input answers a layout, never an exception. */
fun computeConferenceViewLayout(input: ConferenceViewInput): ConferenceViewLayout {
    val self = input.participants.firstOrNull { it.identity == input.selfId }
    val others = input.participants.filter { it.identity != input.selfId }.sortedBy { it.joinOrder }
    val byIdentity = input.participants.associateBy { it.identity }
    val shares =
        input.screenShares
            .filter { it.identity in byIdentity }
            .sortedByDescending { it.startOrder }
            .map { ConferenceTileRef(identity = it.identity, screenShare = true, hasVideo = true) }
    val pinnedValid = input.pinnedId != null && input.pinnedId != input.selfId && others.any { it.identity == input.pinnedId }

    var pinnedLeft = false

    // SCREEN_SHARE without a share answers the remembered view (never SCREEN_SHARE itself, so the recursion ends), else the gallery.
    fun resolve(requested: ConferenceView): ConferenceView =
        when (requested) {
            ConferenceView.PINNED ->
                when {
                    pinnedValid -> ConferenceView.PINNED
                    input.pinnedId != null && input.pinnedId != input.selfId -> {
                        pinnedLeft = true
                        ConferenceView.SPEAKER
                    }
                    else -> ConferenceView.SPEAKER
                }
            ConferenceView.SCREEN_SHARE ->
                if (shares.isNotEmpty()) {
                    ConferenceView.SCREEN_SHARE
                } else {
                    resolve(input.fallbackView.takeIf { it != ConferenceView.SCREEN_SHARE } ?: ConferenceView.GALLERY)
                }
            else -> requested
        }
    val effective = resolve(input.view)

    val everybody = listOfNotNull(self) + others
    val pageSize = input.pageSize.coerceAtLeast(1)

    // One person alone: that person is the large tile in every view (a share still wins).
    if (everybody.size <= 1 && effective != ConferenceView.SCREEN_SHARE) {
        val only = everybody.firstOrNull()?.tile()
        return ConferenceViewLayout(
            effectiveView = effective,
            main = only,
            strip = emptyList(),
            galleryPage = emptyList(),
            page = 0,
            pageCount = 1,
            speakerPage = null,
            pinnedLeft = pinnedLeft,
        )
    }

    val ordered = everybody.map { it.tile() }
    return when (effective) {
        ConferenceView.GALLERY ->
            galleryLayout(
                input = input,
                ordered = ordered,
                shares = shares,
                pageSize = pageSize,
                pinnedLeft = pinnedLeft,
            )
        ConferenceView.SPEAKER -> {
            val speaker =
                others.firstOrNull { it.identity == input.mainSpeakerId }
                    ?: others.firstOrNull { it.hasVideo }
                    ?: others.firstOrNull()
            val main = speaker?.tile() ?: ordered.firstOrNull()
            stripLayout(
                effective = effective,
                main = main,
                extraShares = shares,
                input = input,
                ordered = ordered,
                others = others,
                pinnedLeft = pinnedLeft,
            )
        }
        ConferenceView.PINNED -> {
            val pinned = others.first { it.identity == input.pinnedId }.tile()
            stripLayout(
                effective = effective,
                main = pinned,
                extraShares = shares,
                input = input,
                ordered = ordered,
                others = others,
                pinnedLeft = pinnedLeft,
            )
        }
        ConferenceView.SCREEN_SHARE -> {
            val main = shares.first()
            stripLayout(
                effective = effective,
                main = main,
                extraShares = shares.drop(1),
                input = input,
                ordered = ordered,
                others = others,
                pinnedLeft = pinnedLeft,
            )
        }
    }
}

private fun stripLayout(
    effective: ConferenceView,
    main: ConferenceTileRef?,
    extraShares: List<ConferenceTileRef>,
    input: ConferenceViewInput,
    ordered: List<ConferenceTileRef>,
    others: List<ConferenceViewParticipant>,
    pinnedLeft: Boolean,
): ConferenceViewLayout {
    // Order: further screen shares, then self, then the pinned person (if not large), then everybody else by join order.
    val self = ordered.firstOrNull { it.identity == input.selfId }
    val pinned = input.pinnedId?.takeIf { it != input.selfId }?.let { id -> ordered.firstOrNull { it.identity == id } }
    val rest = others.map { it.tile() }
    val candidates = mutableListOf<ConferenceTileRef>()
    candidates += extraShares
    if (self != null) candidates += self
    if (pinned != null) candidates += pinned
    candidates += rest
    val seen = LinkedHashSet<String>()
    val strip = candidates.filter { it.key != main?.key && seen.add(it.key) }
    return ConferenceViewLayout(
        effectiveView = effective,
        main = main,
        strip = strip,
        galleryPage = emptyList(),
        page = 0,
        pageCount = 1,
        speakerPage = null,
        pinnedLeft = pinnedLeft,
    )
}

private fun galleryLayout(
    input: ConferenceViewInput,
    ordered: List<ConferenceTileRef>,
    shares: List<ConferenceTileRef>,
    pageSize: Int,
    pinnedLeft: Boolean,
): ConferenceViewLayout {
    // A share stays visible in the gallery (next to its owner) -- otherwise a manual gallery choice during a share would hide it.
    val tiles = mutableListOf<ConferenceTileRef>()
    for (tile in ordered) {
        tiles += tile
        shares.filter { it.identity == tile.identity }.forEach { tiles += it }
    }
    val pageCount = maxOf(1, ceil(tiles.size.toDouble() / pageSize).toInt())
    val page = input.page.coerceIn(0, pageCount - 1)
    val from = page * pageSize
    val pageTiles = tiles.subList(from.coerceAtMost(tiles.size), (from + pageSize).coerceAtMost(tiles.size))
    val speakingPages =
        tiles
            .mapIndexedNotNull { index, tile ->
                if (!tile.screenShare &&
                    tile.identity != input.selfId &&
                    tile.identity in input.speakingIds
                ) {
                    index / pageSize
                } else {
                    null
                }
            }.toSet()
    val speakerPage =
        when {
            speakingPages.isEmpty() || page in speakingPages -> null
            else -> speakingPages.minWithOrNull(compareBy<Int> { abs(it - page) }.thenBy { if (it > page) 0 else 1 })
        }
    return ConferenceViewLayout(
        effectiveView = ConferenceView.GALLERY,
        main = null,
        strip = emptyList(),
        galleryPage = pageTiles,
        page = page,
        pageCount = pageCount,
        speakerPage = speakerPage,
        pinnedLeft = pinnedLeft,
    )
}

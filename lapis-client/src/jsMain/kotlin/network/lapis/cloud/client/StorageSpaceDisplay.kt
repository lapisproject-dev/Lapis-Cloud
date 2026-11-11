package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.Icon
import io.kvision.html.Link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.utils.perc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import network.lapis.cloud.shared.rpc.IDocumentService

// V1.9.263 / V1.9.273 -- free-space indicator for ADMIN users: a bar with colour and sentence, shown as a tile in
// "Für Sie" (dashboard, always, a signal only at hint and warning level) and on the document page (all four states).
// The thresholds live on the server; the client only shows `StorageSpaceDto.level`. All arithmetic is Long-only
// (Long is emulated in Kotlin/JS, and a Double floor would turn 19.99999 % into 20 %). No <meter>: its thresholds
// would be computed in the client.

private const val BYTES_PER_MB = 1_000_000L
private const val BYTES_PER_GB = 1_000_000_000L
private const val BYTES_PER_TB = 1_000_000_000_000L
private const val TENTH_OF_GB = 100_000_000L
private const val TENTH_OF_TB = 100_000_000_000L

/** The one RPC of the indicator, injectable so a DOM test can feed (or refuse) a measurement. */
internal fun interface StorageSpaceRpc {
    suspend fun storageSpace(): StorageSpaceDto
}

internal fun liveStorageSpaceRpc() = StorageSpaceRpc { rpcService<IDocumentService>().storageSpace() }

/** Only an ADMIN sees the indicator (UX only; the server checks the role again). */
internal fun showsStorageSpace(session: SessionInfoDto?): Boolean = session?.role == AccountRole.ADMIN

/**
 * Decimal size (1 GB = 10^9 bytes), German decimal comma, truncated and never rounded:
 * < 1 GB -> whole "N MB", < 1 TB -> "N,N GB", else "N,N TB". Negative values count as 0.
 */
internal fun formatStorageBytes(bytes: Long): String {
    val clamped = bytes.coerceAtLeast(0L)
    return when {
        clamped < BYTES_PER_GB -> "${clamped / BYTES_PER_MB} MB"
        clamped < BYTES_PER_TB -> {
            val tenths = clamped / TENTH_OF_GB
            "${tenths / 10},${tenths % 10} GB"
        }
        else -> {
            val tenths = clamped / TENTH_OF_TB
            "${tenths / 10},${tenths % 10} TB"
        }
    }
}

/** `floor(free * 100 / total)` in Long arithmetic, without overflow for very large volumes. */
internal fun storagePercentFloor(
    free: Long,
    total: Long,
): Long {
    if (total <= 0L || free <= 0L) return 0L
    return if (free > Long.MAX_VALUE / 100L) free / (total / 100L) else free * 100L / total
}

internal fun storagePercentLabel(
    free: Long,
    total: Long,
): String {
    val percent = storagePercentFloor(free, total)
    return if (percent == 0L && free > 0L) gettext("unter 1 %") else gettext("%1 %", percent.toString())
}

internal fun storageIconClass(level: StorageSpaceLevel): String =
    when (level) {
        StorageSpaceLevel.INFO -> "fas fa-hard-drive"
        StorageSpaceLevel.HINT -> "fas fa-circle-exclamation"
        StorageSpaceLevel.WARNING -> "fas fa-triangle-exclamation"
        StorageSpaceLevel.UNKNOWN -> "fas fa-circle-question"
    }

private fun decorativeIcon(level: StorageSpaceLevel): Icon {
    val glyph = Icon(storageIconClass(level))
    glyph.setAttribute("aria-hidden", "true")
    return glyph
}

/** What the bar shows. [level] is the effective level: UNKNOWN whenever the bytes are missing or unusable. */
internal data class StorageBarModel(
    val level: StorageSpaceLevel,
    val usedPercent: Int?,
    val usedBytes: Long?,
    val freeBytes: Long?,
    val totalBytes: Long?,
)

internal fun storageBarModel(dto: StorageSpaceDto): StorageBarModel {
    val free = dto.freeBytes
    val total = dto.totalBytes
    if (dto.level == StorageSpaceLevel.UNKNOWN || free == null || total == null || total <= 0L || free < 0L) {
        return StorageBarModel(StorageSpaceLevel.UNKNOWN, null, null, null, null)
    }
    val freePercent = storagePercentFloor(free, total)
    val usedPercent = (100L - freePercent).coerceIn(0L, 100L).toInt()
    return StorageBarModel(dto.level, usedPercent, (total - free).coerceAtLeast(0L), free, total)
}

/** Plain `gettext` (not `tr`): the text also goes into an `aria-label`, where the marker string of `tr` would show up. */
internal fun storageHeadline(level: StorageSpaceLevel): String =
    when (level) {
        StorageSpaceLevel.INFO -> gettext("Speicherplatz")
        StorageSpaceLevel.HINT -> gettext("Speicherplatz knapp")
        StorageSpaceLevel.WARNING -> gettext("Speicherplatz fast voll")
        StorageSpaceLevel.UNKNOWN -> gettext("Speicherplatz nicht messbar")
    }

internal fun storageSentence(model: StorageBarModel): String {
    val used = model.usedBytes
    val free = model.freeBytes
    val total = model.totalBytes
    if (model.level == StorageSpaceLevel.UNKNOWN || used == null || free == null || total == null) {
        return gettext("Der freie Platz lässt sich derzeit nicht messen.")
    }
    return gettext(
        "Belegt %1 von %2, frei %3 (%4 frei)",
        formatStorageBytes(used),
        formatStorageBytes(total),
        formatStorageBytes(free),
        storagePercentLabel(free, total),
    )
}

private fun levelClass(level: StorageSpaceLevel): String =
    when (level) {
        StorageSpaceLevel.INFO -> "lapis-storage--info"
        StorageSpaceLevel.HINT -> "lapis-storage--hint"
        StorageSpaceLevel.WARNING -> "lapis-storage--warning"
        StorageSpaceLevel.UNKNOWN -> "lapis-storage--unknown"
    }

/**
 * The bar. The `role="img"` block (head, track, sentence) carries one label; the optional link is its sibling, because the children of an
 * `img` role are presentational and a link inside would be invisible to a screen reader.
 */
internal fun storageSpaceBar(
    dto: StorageSpaceDto,
    withLink: Boolean = false,
): Div {
    val model = storageBarModel(dto)
    val level = model.level
    val headline = storageHeadline(level)
    val sentence = storageSentence(model)

    val container = Div(className = "lapis-storage ${levelClass(level)}")
    val figure = Div(className = "lapis-storage__figure")
    figure.setAttribute("role", "img")
    figure.setAttribute("aria-label", "$headline. $sentence")

    val head = Div(className = "lapis-storage__head")
    head.add(decorativeIcon(level))
    val emphasis =
        when (level) {
            StorageSpaceLevel.HINT -> " text-warning-emphasis"
            StorageSpaceLevel.WARNING -> " text-danger-emphasis"
            else -> ""
        }
    head.add(Div(content = headline, className = "lapis-storage__title fw-semibold$emphasis"))
    figure.add(head)

    val track = Div(className = "lapis-storage__track")
    val used = model.usedPercent
    if (level != StorageSpaceLevel.UNKNOWN && used != null) {
        track.add(Div(className = "lapis-storage__fill").apply { width = used.perc })
    }
    figure.add(track)
    figure.add(Div(content = sentence, className = "lapis-storage__text small"))
    container.add(figure)

    if (withLink) {
        container.add(Link(label = tr("Zur Dokumentenablage"), url = "#${Routes.DOCUMENTS}", className = "lapis-storage__link small"))
    }
    return container
}

/**
 * The bar on a screen of its own (video conference, encounter rooms: the places where recordings fill the disk). Only an ADMIN gets
 * it; for everybody else nothing is added and nothing is asked. One call, no polling. A failed or refused call removes the slot again,
 * so the page never shows an error for what is only a hint. [rpc] and [session] are injectable for a DOM test.
 */
internal fun SimplePanel.storageSpaceSlot(
    session: SessionInfoDto? = AppState.session,
    rpc: StorageSpaceRpc = liveStorageSpaceRpc(),
): Div? {
    if (!showsStorageSpace(session)) return null
    val slot = Div(className = "lapis-storage-slot")
    add(slot)
    AppScope.launch {
        try {
            val dto = rpc.storageSpace()
            slot.add(storageSpaceBar(dto, withLink = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            remove(slot)
        }
    }
    return slot
}

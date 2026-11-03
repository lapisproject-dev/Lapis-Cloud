package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.Icon
import io.kvision.html.Link
import io.kvision.html.Span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import network.lapis.cloud.shared.rpc.IDocumentService

// V1.9.263 -- free-space indicator for ADMIN users: a card in "Für Sie" (dashboard, only at hint and warning level)
// and one line on the document page (all four states). The thresholds live on the server; the client only shows
// `StorageSpaceDto.level`. All arithmetic is Long-only (Long is emulated in Kotlin/JS, and a Double floor would turn
// 19.99999 % into 20 %).

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

/** The dashboard card; `null` for INFO, UNKNOWN and a measurement without bytes (nothing to warn about). */
internal fun storageSpaceCard(dto: StorageSpaceDto): Div? {
    val free = dto.freeBytes ?: return null
    val total = dto.totalBytes ?: return null
    val warning = dto.level == StorageSpaceLevel.WARNING
    if (!warning && dto.level != StorageSpaceLevel.HINT) return null

    val freeText = formatStorageBytes(free)
    val totalText = formatStorageBytes(total)
    val percentText = storagePercentLabel(free, total)
    val title = if (warning) tr("Speicherplatz fast voll") else tr("Speicherplatz wird knapp")
    val text =
        if (warning) {
            gettext(
                "Nur noch %1 frei von %2 (%3). Neue Uploads und Aufzeichnungen können bald scheitern. Bitte jetzt Platz schaffen.",
                freeText,
                totalText,
                percentText,
            )
        } else {
            gettext(
                "Noch %1 frei von %2 (%3). Bitte bei Gelegenheit alte Aufzeichnungen oder Dokumente aufräumen oder den Speicher erweitern.",
                freeText,
                totalText,
                percentText,
            )
        }

    val warningClasses = if (warning) " bg-warning-subtle border-warning-subtle text-warning-emphasis" else ""
    val card = Div(className = "card lapis-signal-card lapis-storage-card$warningClasses")
    card.setAttribute("role", "status")
    val head = Span(className = "lapis-signal-card__head")
    head.add(decorativeIcon(dto.level))
    head.add(Span(content = title, className = "lapis-signal-card__title"))
    card.add(head)
    card.add(Span(content = text, className = "lapis-signal-card__reason"))
    card.add(Link(label = tr("Zu den Dokumenten"), url = "#${Routes.DOCUMENTS}", className = "lapis-storage-card__link small"))
    return card
}

/** The one line on the document page, for all four states. */
internal fun storageSpaceLine(dto: StorageSpaceDto): Div {
    val free = dto.freeBytes
    val total = dto.totalBytes
    val text =
        if (dto.level == StorageSpaceLevel.UNKNOWN || free == null || total == null) {
            gettext("Speicherplatz: derzeit nicht messbar")
        } else {
            val freeText = formatStorageBytes(free)
            val totalText = formatStorageBytes(total)
            val percentText = storagePercentLabel(free, total)
            when (dto.level) {
                StorageSpaceLevel.HINT -> gettext("Speicherplatz: %1 frei von %2 (%3), wird knapp", freeText, totalText, percentText)
                StorageSpaceLevel.WARNING -> gettext("Speicherplatz: %1 frei von %2 (%3), fast voll", freeText, totalText, percentText)
                else -> gettext("Speicherplatz: %1 frei von %2 (%3)", freeText, totalText, percentText)
            }
        }
    val warning = if (dto.level == StorageSpaceLevel.WARNING) " text-warning-emphasis" else ""
    val line = Div(className = "small text-body-secondary lapis-storage-line$warning")
    line.add(decorativeIcon(dto.level))
    line.add(Span(content = " $text"))
    return line
}

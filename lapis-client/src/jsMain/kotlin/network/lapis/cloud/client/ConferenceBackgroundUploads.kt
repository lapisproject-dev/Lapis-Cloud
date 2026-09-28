package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import network.lapis.cloud.shared.domain.ConferenceBackgroundRules
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.Image
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.xhr.FormData
import kotlin.coroutines.resume
import kotlin.js.Promise

/**
 * V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- client-side normalization
 * (comfort + data minimization, NEVER a security boundary -- the server re-validates and
 * re-encodes everything itself, see `ConferenceBackgroundImageProcessor` KDoc) plus the HTTP
 * transport for the byte-carrying routes (mirrors [TravelExpenseHttp]'s own house style; same
 * reasoning as that object's KDoc for why this is a dedicated fetch, not Kilua RPC).
 *
 * Roh-Upload-Deckel VOR der Normalisierung -- ein absurd grosses Ausgangsbild (z. B. ein 100-MP-
 * Foto) soll nicht erst vollstaendig dekodiert werden, bevor es verworfen wird.
 */
internal const val CONFERENCE_BACKGROUND_MAX_RAW_FILE_BYTES = 25L * 1024 * 1024

/**
 * Pure. Identisch zur serverseitigen `backgroundTargetSize`-Logik (`ConferenceBackgroundImageProcessor.kt`):
 * skaliert die lange Kante auf hoechstens [ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX],
 * NIE hoch.
 */
internal fun conferenceBackgroundUploadTargetSize(
    width: Int,
    height: Int,
): Pair<Int, Int> {
    val maxLongEdge = ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return width to height
    val scale = maxLongEdge.toDouble() / longEdge.toDouble()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return targetWidth to targetHeight
}

/** Pure. Die kuerzere Seite der (bereits herunterskalierten) Zielgroesse unter `MIN_SIDE_PX`? Lokale, comfort-only Vorabpruefung -- der Server ist massgeblich. */
internal fun conferenceBackgroundUploadTooSmall(
    targetWidth: Int,
    targetHeight: Int,
): Boolean = minOf(targetWidth, targetHeight) < ConferenceBackgroundRules.MIN_SIDE_PX

internal sealed interface CustomBackgroundUploadResult {
    data class Ok(
        val id: String,
    ) : CustomBackgroundUploadResult

    data class Failed(
        val status: Int,
    ) : CustomBackgroundUploadResult
}

/**
 * Feste, uebersetzte Saetze -- eine rohe Server-Antwort wird NIE angezeigt (bewusste Abweichung von
 * [TravelExpenseHttp], siehe Umsetzungsplan Abschnitt 5.3 "Fehlertexte").
 */
internal fun conferenceBackgroundUploadErrorText(status: Int): String =
    when (status) {
        413 -> tr("Die Datei ist zu groß.")
        415 -> tr("Dieses Dateiformat wird nicht unterstützt.")
        422 -> tr("Das Bild hat eine ungültige Größe.")
        409 -> tr("Sie haben bereits die maximale Anzahl eigener Bilder erreicht.")
        429 -> tr("Zu viele Uploads – bitte später erneut versuchen.")
        else -> tr("Hochladen fehlgeschlagen.")
    }

internal object ConferenceBackgroundHttp {
    suspend fun upload(jpeg: Blob): CustomBackgroundUploadResult {
        val formData = FormData()
        formData.append("file", jpeg, "background.jpg")
        val response =
            window
                .fetch(
                    "/api/conference-backgrounds",
                    RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE),
                ).await()
        if (!response.ok) return CustomBackgroundUploadResult.Failed(response.status.toInt())
        val body = response.json().await()
        val id = body.asDynamic().id as? String ?: return CustomBackgroundUploadResult.Failed(response.status.toInt())
        return CustomBackgroundUploadResult.Ok(id)
    }

    /** `true` on success. A 404 counts as deleted too (already gone -- idempotent from the caller's perspective). */
    suspend fun delete(id: String): Boolean {
        val response =
            window
                .fetch(
                    "/api/conference-backgrounds/$id",
                    RequestInit(method = "DELETE", credentials = RequestCredentials.INCLUDE),
                ).await()
        return response.ok || response.status.toInt() == 404
    }
}

/**
 * Browser-seitige Normalisierung VOR dem Hochladen (Komfort + Datensparsamkeit -- der Server bleibt
 * massgeblich, siehe Datei-KDoc). Ablauf:
 * 1. `createImageBitmap(file, {imageOrientation: "from-image"})` -- wendet die EXIF-Rotation an,
 *    bevor irgendetwas gezeichnet wird. Wirft das (aeltere Safari-Versionen ohne die
 *    `imageOrientation`-Option), Ersatzweg ueber ein `<img>`-Element + Object-URL (S11 des
 *    Umsetzungsplans: dort greift die Browser-eigene EXIF-Handhabung beim Rendern, nicht immer
 *    identisch, aber ein besseres Ergebnis als kein Bild).
 * 2. Auf eine Canvas MIT DER ZIELGROESSE zeichnen (niemals eine Canvas mit der Originalgroesse --
 *    iOS begrenzt Canvas-Flaechen auf ca. 16,7 MP, S11).
 * 3. `toBlob("image/jpeg", 0.85)`.
 *
 * Ergebnis ist [NormalizeBackgroundResult]: [NormalizeBackgroundResult.TooSmall] wenn die
 * (bereits herunterskalierte) Zielgroesse [conferenceBackgroundUploadTooSmall] unterschreitet --
 * dieselbe lokale, comfort-only Vorabpruefung wie server-seitig, VOR jedem Netzwerk-Byte.
 * [NormalizeBackgroundResult.TooLarge] wenn die Rohdatei [CONFERENCE_BACKGROUND_MAX_RAW_FILE_BYTES]
 * ueberschreitet -- eigenes Ergebnis statt [NormalizeBackgroundResult.Failed] (Review-Befund), damit
 * der Aufrufer denselben Text wie der server-seitige 413-Fall zeigt ("Die Datei ist zu groß."),
 * nicht das generische "Hochladen fehlgeschlagen.". [NormalizeBackgroundResult.Failed] fuer jeden
 * anderen Fehler (Aufrufer zeigt "Hochladen fehlgeschlagen.").
 */
internal sealed interface NormalizeBackgroundResult {
    data class Ok(
        val blob: Blob,
    ) : NormalizeBackgroundResult

    data object TooSmall : NormalizeBackgroundResult

    data object TooLarge : NormalizeBackgroundResult

    data object Failed : NormalizeBackgroundResult
}

internal suspend fun normalizeBackgroundForUpload(file: File): NormalizeBackgroundResult {
    if (file.size.toLong() > CONFERENCE_BACKGROUND_MAX_RAW_FILE_BYTES) return NormalizeBackgroundResult.TooLarge

    val bitmap: dynamic =
        runCatching {
            val options = js("({})")
            options.imageOrientation = "from-image"
            window
                .asDynamic()
                .createImageBitmap(file, options)
                .unsafeCast<Promise<Any?>>()
                .await()
        }.getOrElse {
            // Safari-Ersatzweg: ueber ein <img>-Element + Object-URL dekodieren (S11).
            runCatching { loadImageBitmapViaImgElement(file) }.getOrNull()
        } ?: return NormalizeBackgroundResult.Failed

    return try {
        val sourceWidth = (bitmap.width as? Int) ?: return NormalizeBackgroundResult.Failed
        val sourceHeight = (bitmap.height as? Int) ?: return NormalizeBackgroundResult.Failed
        val (targetWidth, targetHeight) = conferenceBackgroundUploadTargetSize(width = sourceWidth, height = sourceHeight)
        if (conferenceBackgroundUploadTooSmall(targetWidth = targetWidth, targetHeight = targetHeight)) {
            return NormalizeBackgroundResult.TooSmall
        }

        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = targetWidth
        canvas.height = targetHeight
        val ctx = canvas.getContext("2d") as? CanvasRenderingContext2D ?: return NormalizeBackgroundResult.Failed
        ctx.asDynamic().drawImage(bitmap, 0, 0, targetWidth, targetHeight)

        val blob =
            suspendCancellableCoroutine { continuation ->
                canvas.asDynamic().toBlob(
                    { b: Blob? -> if (continuation.isActive) continuation.resume(b) },
                    "image/jpeg",
                    0.85,
                )
            }
        if (blob == null) NormalizeBackgroundResult.Failed else NormalizeBackgroundResult.Ok(blob)
    } finally {
        runCatching { bitmap.close() }
    }
}

/** Safari-Ersatzweg fuer [normalizeBackgroundForUpload] -- kein `imageOrientation`-Option noetig, weil `<img>` die EXIF-Rotation selbst beim Rendern anwendet. */
private suspend fun loadImageBitmapViaImgElement(file: File): dynamic {
    val objectUrl = URL.createObjectURL(file)
    val image = Image()
    var registered: ((Event) -> Unit)? = null
    try {
        suspendCancellableCoroutine { continuation ->
            val handler: (Event) -> Unit = { event ->
                if (continuation.isActive) {
                    if (event.type == "load") {
                        continuation.resume(Unit)
                    } else {
                        continuation.cancel()
                    }
                }
            }
            registered = handler
            image.addEventListener("load", handler)
            image.addEventListener("error", handler)
            image.src = objectUrl
        }
    } finally {
        registered?.let { handler ->
            image.removeEventListener("load", handler)
            image.removeEventListener("error", handler)
        }
    }
    val result =
        window
            .asDynamic()
            .createImageBitmap(image)
            .unsafeCast<Promise<Any?>>()
            .await()
    URL.revokeObjectURL(objectUrl)
    return result
}

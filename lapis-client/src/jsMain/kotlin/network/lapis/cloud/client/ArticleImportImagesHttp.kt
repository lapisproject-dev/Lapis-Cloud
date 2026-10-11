package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ArticleImportImagesPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportImagesResultDto
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File
import org.w3c.xhr.FormData

/**
 * Welle V1.9.106 -- the two multipart routes of the article import image ZIP (`POST /api/articles/import/images/{preview,commit}`, see
 * `network.lapis.cloud.server.routes.registerArticleImportImageRoutes`). The ZIP travels over these Ktor routes, not Kilua RPC (a ZIP does not
 * belong in a JSON-escaped RPC parameter). Same-origin cookie session, no token: the server checks the origin.
 */
object ArticleImportImagesHttp {
    /** Mirrors `ArticleImportZipPolicy.MAX_ZIP_BYTES` -- only a convenience pre-check, the server's 413 stays authoritative. */
    const val MAX_ZIP_BYTES: Long = 25L * 1024 * 1024

    private const val PREVIEW_URL = "/api/articles/import/images/preview"
    private const val COMMIT_URL = "/api/articles/import/images/commit"

    sealed interface Outcome<out T> {
        data class Ok<T>(
            val value: T,
        ) : Outcome<T>

        data class Error(
            val message: String,
        ) : Outcome<Nothing>
    }

    suspend fun preview(
        zip: File,
        slugs: List<String>,
    ): Outcome<ArticleImportImagesPreviewDto> =
        send(PREVIEW_URL, zip, "slugs", Json.encodeToString(ListSerializer(String.serializer()), slugs)) {
            imagesJson.decodeFromString(ArticleImportImagesPreviewDto.serializer(), it)
        }

    suspend fun commit(
        zip: File,
        importBatchId: String,
    ): Outcome<ArticleImportImagesResultDto> =
        send(COMMIT_URL, zip, "importBatchId", importBatchId) {
            imagesJson.decodeFromString(ArticleImportImagesResultDto.serializer(), it)
        }

    private suspend fun <T> send(
        url: String,
        zip: File,
        field: String,
        value: String,
        decode: (String) -> T,
    ): Outcome<T> {
        val formData = FormData()
        formData.append("zip", zip, zip.name)
        formData.append(field, value)
        return try {
            val response =
                window
                    .fetch(url, RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE))
                    .await()
            parseArticleImportImagesOutcome(response.status.toInt(), response.text().await(), decode)
        } catch (e: Throwable) {
            Outcome.Error(tr("Upload fehlgeschlagen."))
        }
    }
}

private val imagesJson = Json { ignoreUnknownKeys = true }

/** Pure status + body -> outcome mapping, directly unit-testable. */
internal fun <T> parseArticleImportImagesOutcome(
    status: Int,
    bodyText: String,
    decode: (String) -> T,
): ArticleImportImagesHttp.Outcome<T> =
    when (status) {
        200 ->
            runCatching { decode(bodyText) }.fold(
                onSuccess = { ArticleImportImagesHttp.Outcome.Ok(it) },
                onFailure = { ArticleImportImagesHttp.Outcome.Error(tr("Antwort konnte nicht gelesen werden.")) },
            )
        413 -> ArticleImportImagesHttp.Outcome.Error(tr("Das ZIP ist zu groß (maximal 25 MiB)."))
        422 -> ArticleImportImagesHttp.Outcome.Error(translateImageZipRejection(bodyText))
        429 -> ArticleImportImagesHttp.Outcome.Error(tr("Zu viele Anfragen. Bitte später erneut versuchen."))
        403 -> ArticleImportImagesHttp.Outcome.Error(tr("Dafür fehlt die Berechtigung."))
        400 -> ArticleImportImagesHttp.Outcome.Error(tr("Die Anfrage war ungültig. Bitte Seite neu laden und erneut versuchen."))
        else -> ArticleImportImagesHttp.Outcome.Error(gettext("Unbekannter Fehler (HTTP %1).", status))
    }

/**
 * The fixed German sentences of `ZipRejectReason.messageDe` (server), shown in the viewer's language. A sentence this build does not know stays
 * German text -- never an error. Hand-synced with the server enum (a server sentence never contains a file name).
 */
internal fun translateImageZipRejection(sentence: String): String =
    when (sentence.trim()) {
        "Die Datei ist kein lesbares ZIP-Archiv." -> tr("Die Datei ist kein lesbares ZIP-Archiv.")
        "Das ZIP enthält zu viele Dateien (höchstens 100)." -> tr("Das ZIP enthält zu viele Dateien (höchstens 100).")
        "Ein Bild im ZIP ist zu groß (höchstens 5 MB je Bild)." -> tr("Ein Bild im ZIP ist zu groß (höchstens 5 MB je Bild).")
        "Das ZIP ist entpackt zu groß." -> tr("Das ZIP ist entpackt zu groß.")
        "Das ZIP ist ungewöhnlich stark komprimiert und wird abgelehnt." ->
            tr("Das ZIP ist ungewöhnlich stark komprimiert und wird abgelehnt.")
        "Bilder müssen direkt im ZIP liegen, ohne Ordner." -> tr("Bilder müssen direkt im ZIP liegen, ohne Ordner.")
        "Das ZIP enthält Dateinamen, die nicht erlaubt sind (erwartet: slug.jpg, slug.jpeg oder slug.png)." ->
            tr("Das ZIP enthält Dateinamen, die nicht erlaubt sind (erwartet: slug.jpg, slug.jpeg oder slug.png).")
        "Das ZIP enthält verknüpfte Dateien (Symlinks) und wird abgelehnt." ->
            tr("Das ZIP enthält verknüpfte Dateien (Symlinks) und wird abgelehnt.")
        "Verschlüsselte ZIP-Archive werden nicht unterstützt." -> tr("Verschlüsselte ZIP-Archive werden nicht unterstützt.")
        "Das ZIP enthält ein weiteres Archiv und wird abgelehnt." -> tr("Das ZIP enthält ein weiteres Archiv und wird abgelehnt.")
        "Das ZIP verwendet ein nicht unterstütztes Kompressionsverfahren." ->
            tr("Das ZIP verwendet ein nicht unterstütztes Kompressionsverfahren.")
        "Sehr große oder mehrteilige ZIP-Archive werden nicht unterstützt." ->
            tr("Sehr große oder mehrteilige ZIP-Archive werden nicht unterstützt.")
        "Das ZIP ist beschädigt oder widersprüchlich aufgebaut." -> tr("Das ZIP ist beschädigt oder widersprüchlich aufgebaut.")
        else -> sentence
    }

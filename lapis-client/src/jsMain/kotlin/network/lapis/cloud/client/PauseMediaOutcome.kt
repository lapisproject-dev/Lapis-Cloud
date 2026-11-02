package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.PauseMediaResultDto
import network.lapis.cloud.shared.domain.PauseMediaUploadError
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.SAME_ORIGIN
import org.w3c.files.File
import org.w3c.xhr.FormData

/**
 * V1.9.255 -- the three same-origin requests of the pause picture / pause music (`PauseMediaRoutes` on the server). A refusal is decoded into
 * the fixed [PauseMediaUploadError] vocabulary, never shown as a raw server text.
 */
internal sealed interface PauseMediaOutcome {
    data class Ok(
        val result: PauseMediaResultDto,
    ) : PauseMediaOutcome

    data class Refused(
        val error: PauseMediaUploadError?,
    ) : PauseMediaOutcome
}

private external fun encodeURIComponent(value: String): String

private suspend fun decode(response: org.w3c.fetch.Response): PauseMediaOutcome {
    val result = runCatching { Json.decodeFromString(PauseMediaResultDto.serializer(), response.text().await()) }.getOrNull()
    return if (response.ok && result != null && result.ok) PauseMediaOutcome.Ok(result) else PauseMediaOutcome.Refused(result?.error)
}

internal suspend fun uploadPauseImage(file: File): PauseMediaOutcome {
    val form = FormData()
    form.append("file", file, "pause")
    return decode(
        window
            .fetch(
                "/api/conference/pause-media/image",
                RequestInit(method = "POST", body = form, credentials = RequestCredentials.SAME_ORIGIN),
            ).await(),
    )
}

internal suspend fun uploadPauseMusic(file: File): PauseMediaOutcome {
    val form = FormData()
    form.append("file", file, "pause")
    return decode(
        window
            .fetch(
                "/api/conference/pause-media/music?name=${encodeURIComponent(file.name)}",
                RequestInit(method = "POST", body = form, credentials = RequestCredentials.SAME_ORIGIN),
            ).await(),
    )
}

internal suspend fun deletePauseMedia(music: Boolean): PauseMediaOutcome =
    decode(
        window
            .fetch(
                if (music) "/api/conference/pause-media/music" else "/api/conference/pause-media/image",
                RequestInit(method = "DELETE", credentials = RequestCredentials.SAME_ORIGIN),
            ).await(),
    )

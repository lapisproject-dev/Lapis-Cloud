package network.lapis.cloud.client

import io.kvision.i18n.tr

/**
 * Welle V1.9.207 -- stream destination presets. Pure UX: a preset may prefill the RTMP base URL of the create form, nothing else.
 * Presets are stored as `GENERIC_RTMP` (no enum change, no migration, the server has no platform-specific code path), the stream
 * key is NEVER prefilled and never touched by a preset.
 *
 * **Verification status (2026-10-24):** none of the six server URLs could be confirmed against first-party vendor documentation
 * (the vendor help pages are login-gated or show the value only in screenshots; third-party guides disagree or omit it, and
 * Telegram, Rumble and Kick assign the ingest address per account or per stream). Therefore every preset is `serverUrl = null`:
 * the form prefills nothing and the hint tells the person where the platform shows its own server URL. [hostPattern] is used
 * only to RECOGNISE an already entered URL for the list label; a wrong or stale pattern merely shows "Generisches RTMP".
 */
internal data class ConferenceStreamPreset(
    /** Stable, lowercase, unique. */
    val id: String,
    /** Brand name, not translated. */
    val label: String,
    /** Prefill for the URL field; `null` = nothing is prefilled (see class KDoc). Must start with rtmp:// or rtmps://. */
    val serverUrl: String?,
    /** The platform shows the server URL per account or per event; nothing may be assumed. */
    val perEventUrl: Boolean,
    /** Host pattern (lower case host only) used to label a stored generic destination; `null` where no host could be confirmed. */
    val hostPattern: Regex?,
    /** Lazy so the language switch applies at render time. */
    val keyHint: () -> String,
)

internal const val PRESET_OPTION_PREFIX = "preset:"

internal val conferenceStreamPresets: List<ConferenceStreamPreset> =
    listOf(
        ConferenceStreamPreset(
            id = "facebook",
            label = "Facebook Live",
            serverUrl = null,
            perEventUrl = true,
            hostPattern = Regex("^live-api(-[a-z0-9]+)?\\.facebook\\.com$"),
            keyHint = {
                tr("Server-URL und Stream-Schlüssel zeigt Facebook beim Einrichten der Live-Übertragung an. Der Schlüssel kann wechseln.")
            },
        ),
        ConferenceStreamPreset(
            id = "telegram",
            label = "Telegram",
            serverUrl = null,
            perEventUrl = true,
            hostPattern = Regex("^dc\\d+-\\d+\\.rtmp\\.t\\.me$"),
            keyHint = {
                tr("Server-URL und Stream-Schlüssel zeigt Telegram beim Start der Übertragung an, wenn Sie mit anderer Software streamen.")
            },
        ),
        ConferenceStreamPreset(
            id = "linkedin",
            label = "LinkedIn Live",
            serverUrl = null,
            perEventUrl = true,
            hostPattern = null,
            keyHint = {
                tr("Server-URL und Stream-Schlüssel erhalten Sie von LinkedIn für jede einzelne Live-Veranstaltung.")
            },
        ),
        ConferenceStreamPreset(
            id = "rumble",
            label = "Rumble",
            serverUrl = null,
            perEventUrl = true,
            hostPattern = null,
            keyHint = {
                tr("Server-URL und Stream-Schlüssel zeigt Rumble beim Einrichten der Übertragung an. Der Schlüssel gilt oft nur einmal.")
            },
        ),
        ConferenceStreamPreset(
            id = "kick",
            label = "Kick",
            serverUrl = null,
            perEventUrl = true,
            hostPattern = null,
            keyHint = {
                tr("Server-URL und Stream-Schlüssel zeigt Kick im Creator-Dashboard an.")
            },
        ),
        ConferenceStreamPreset(
            id = "vimeo",
            label = "Vimeo Live",
            serverUrl = null,
            perEventUrl = true,
            hostPattern = Regex("^rtmp\\.cloud\\.vimeo\\.com$"),
            keyHint = {
                tr("Server-URL und Stream-Schlüssel zeigt Vimeo in den Einstellungen der Live-Veranstaltung an.")
            },
        ),
    )

internal fun conferenceStreamPresetById(id: String): ConferenceStreamPreset? = conferenceStreamPresets.firstOrNull { it.id == id }

/** Host of an rtmp(s) URL, lower case, without port/path/userinfo; `null` when it cannot be read. */
private fun rtmpHostOf(url: String): String? {
    val trimmed = url.trim()
    val rest =
        when {
            trimmed.startsWith("rtmp://", ignoreCase = true) -> trimmed.substring(7)
            trimmed.startsWith("rtmps://", ignoreCase = true) -> trimmed.substring(8)
            else -> return null
        }
    val authority = rest.substringBefore('/').substringAfterLast('@')
    return authority.substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
}

/** The preset whose [ConferenceStreamPreset.hostPattern] (or [ConferenceStreamPreset.serverUrl] host) matches [url]'s host. */
internal fun conferenceStreamPresetForUrl(url: String): ConferenceStreamPreset? {
    val host = rtmpHostOf(url) ?: return null
    return conferenceStreamPresets.firstOrNull { preset ->
        preset.hostPattern?.matches(host) == true || preset.serverUrl?.let { rtmpHostOf(it) } == host
    }
}

package network.lapis.cloud.server.conference

import network.lapis.cloud.shared.domain.ConferenceStreamKeyHint
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.rpc.ConferenceStreamViewerUrlRejectedException
import java.net.IDN
import java.net.URI
import java.net.URISyntaxException

/**
 * V1.9.274 -- checks and normalises the address of the viewer page of a broadcast (the page where the platform shows the live comments). The
 * moderator pastes it; the result is stored and later opened by the moderator with "Kommentare öffnen", so it must never be able to carry a
 * script, a credential or a look-alike host:
 *
 *  * `https` only (no `javascript:`, `data:`, `http:`), no user info, no `@` in the authority, port empty or 443, no IP literal, no `localhost`;
 *  * the host is compared EXACTLY against fixed sets (never `endsWith`/`contains`), after converting an international name to ASCII;
 *  * the result is assembled anew from `https://` + ASCII host + path + query (fragment dropped), for YouTube/Twitch from a validated id/channel only;
 *  * input longer than 2048 characters is refused before any parsing, the result is at most 512 characters.
 *
 * Every refusal is a [ConferenceStreamViewerUrlRejectedException] with a fixed text; the input is never echoed.
 */
object StreamViewerUrls {
    private const val MAX_INPUT_LENGTH = 2048
    const val MAX_RESULT_LENGTH = 512

    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
    private val TWITCH_HOSTS = setOf("twitch.tv", "www.twitch.tv")
    private val FACEBOOK_HOSTS = setOf("facebook.com", "www.facebook.com", "m.facebook.com", "fb.watch", "fb.com")
    private val X_HOSTS = setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com")

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val TWITCH_CHANNEL = Regex("^[A-Za-z0-9_]{4,25}$")
    private val IPV4_LIKE = Regex("^[0-9.]+$")
    private val ENCODED_CONTROL = Regex("%(0[0-9a-fA-F]|1[0-9a-fA-F]|7[fF])")
    private val TWITCH_RESERVED =
        setOf(
            "directory",
            "videos",
            "settings",
            "popout",
            "p",
            "search",
            "subscriptions",
            "inventory",
            "wallet",
            "drops",
            "friends",
            "messages",
            "downloads",
            "jobs",
            "turbo",
            "prime",
            "store",
            "user",
            "login",
            "signup",
            "logout",
            "team",
            "moderator",
            "u",
        )

    private fun reject(): Nothing = throw ConferenceStreamViewerUrlRejectedException()

    fun normalize(
        raw: String,
        platform: ConferenceStreamPlatform,
        keyHint: ConferenceStreamKeyHint,
    ): String {
        if (raw.length > MAX_INPUT_LENGTH) reject()
        val text = raw.trim()
        if (text.isEmpty()) reject()
        if (text.any { it.code <= 0x20 || it.code == 0x7F || it in "\\<>\"'`" }) reject()
        if (ENCODED_CONTROL.containsMatchIn(text)) reject()

        val uri =
            try {
                URI(text)
            } catch (e: URISyntaxException) {
                reject()
            }
        if (!"https".equals(uri.scheme, ignoreCase = true)) reject()
        if (uri.rawUserInfo != null) reject()
        val authority = uri.rawAuthority ?: reject()
        if ('@' in authority || '[' in authority || authority.isEmpty()) reject()

        // `URI.getHost()` is null for an international host name; the authority is split by hand then.
        val colon = authority.lastIndexOf(':')
        val rawHost = if (colon >= 0) authority.substring(0, colon) else authority
        val portText = if (colon >= 0) authority.substring(colon + 1) else ""
        if (portText.isNotEmpty() && portText != "443") reject()
        val host = asciiHost(rawHost)

        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery
        if (!(path + query.orEmpty()).all { it.code in 0x21..0x7E }) reject()

        val result =
            when {
                platform == ConferenceStreamPlatform.YOUTUBE -> {
                    if (host !in YOUTUBE_HOSTS) reject()
                    youtubeChat(host = host, path = path, query = query)
                }
                platform == ConferenceStreamPlatform.TWITCH -> {
                    if (host !in TWITCH_HOSTS) reject()
                    "https://www.twitch.tv/popout/${twitchChannel(path)}/chat"
                }
                platform == ConferenceStreamPlatform.X || keyHint == ConferenceStreamKeyHint.X -> {
                    if (host !in X_HOSTS) reject()
                    assemble(host = host, path = path, query = query)
                }
                keyHint == ConferenceStreamKeyHint.FACEBOOK -> {
                    if (host !in FACEBOOK_HOSTS) reject()
                    assemble(host = host, path = path, query = query)
                }
                // A plain generic destination that points to YouTube is treated as YouTube.
                platform == ConferenceStreamPlatform.GENERIC_RTMP && host in YOUTUBE_HOSTS ->
                    youtubeChat(host = host, path = path, query = query)
                else -> assemble(host = host, path = path, query = query)
            }
        if (result.length > MAX_RESULT_LENGTH) reject()
        return result
    }

    private fun youtubeChat(
        host: String,
        path: String,
        query: String?,
    ): String = "https://www.youtube.com/live_chat?is_popout=1&v=${youtubeId(host = host, path = path, query = query)}"

    private fun assemble(
        host: String,
        path: String,
        query: String?,
    ): String = "https://$host$path" + (if (query != null) "?$query" else "")

    /** ASCII, lower case, no trailing dot, a plain DNS name with at least one dot -- never an IP literal or `localhost`. */
    private fun asciiHost(rawHost: String): String {
        if (rawHost.isEmpty()) reject()
        val ascii =
            try {
                IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES)
            } catch (e: IllegalArgumentException) {
                reject()
            }.lowercase().trimEnd('.')
        if (ascii.isEmpty() || ascii.length > 253 || '.' !in ascii) reject()
        if (IPV4_LIKE.matches(ascii) || ':' in ascii) reject()
        if (ascii == "localhost" || ascii.endsWith(".localhost") || ascii.endsWith(".local") || ascii.endsWith(".internal")) reject()
        if (ascii.split('.').any { it.isEmpty() || it.length > 63 }) reject()
        return ascii
    }

    private fun queryParam(
        query: String?,
        name: String,
    ): String? {
        if (query == null) return null
        val values = query.split('&').filter { it.substringBefore('=') == name && it.contains('=') }
        if (values.size > 1) reject()
        return values.singleOrNull()?.substringAfter('=')
    }

    private fun youtubeId(
        host: String,
        path: String,
        query: String?,
    ): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val candidate: String? =
            when {
                host == "youtu.be" -> segments.singleOrNull()
                segments.size == 1 && (segments[0] == "watch" || segments[0] == "live_chat") -> queryParam(query = query, name = "v")
                segments.size == 2 && segments[0] in setOf("live", "shorts", "embed") -> segments[1]
                else -> null
            }
        if (candidate == null || !YOUTUBE_ID.matches(candidate)) reject()
        return candidate
    }

    private fun twitchChannel(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val channel =
            when {
                segments.size == 1 -> segments[0]
                segments.size == 3 && segments[0] == "popout" && segments[2] == "chat" -> segments[1]
                else -> reject()
            }
        if (channel.lowercase() in TWITCH_RESERVED || !TWITCH_CHANNEL.matches(channel)) reject()
        return channel.lowercase()
    }
}

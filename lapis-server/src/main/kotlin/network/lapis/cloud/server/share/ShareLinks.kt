package network.lapis.cloud.server.share

import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

internal data class ShareTarget(
    val network: ShareNetwork,
    val href: String,
)

/** Pure URL building for the share bar (V1.9.217). Every target host is a constant; user-influenced values are only ever percent-encoded parameters. */
internal object ShareLinks {
    const val TITLE_MAX_CODEPOINTS = 200
    const val TEXT_MAX_CODEPOINTS = 240

    private const val FACEBOOK = "https://www.facebook.com/sharer/sharer.php?u="
    private const val X = "https://twitter.com/intent/tweet?url="
    private const val LINKEDIN = "https://www.linkedin.com/sharing/share-offsite/?url="
    private const val WHATSAPP = "https://wa.me/?text="
    private const val TELEGRAM = "https://t.me/share/url?url="
    private const val REDDIT = "https://www.reddit.com/submit?url="
    private const val MAILTO = "mailto:?subject="

    private val WHITESPACE = Regex("\\s+")

    fun encode(value: String): String = URLEncoder.encode(value, UTF_8).replace("+", "%20")

    private fun isForbidden(cp: Int): Boolean =
        Character.isISOControl(cp) || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F

    /** Removes control characters and bidi controls, collapses whitespace runs to one space, trims. */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        raw.codePoints().forEach { cp ->
            when {
                Character.isWhitespace(cp) || cp == 0x2028 || cp == 0x2029 -> sb.append(' ')
                isForbidden(cp) -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    /** Cuts to at most [max] code points; the ellipsis counts inside [max]. Never splits a surrogate pair. */
    fun truncateCodepoints(
        value: String,
        max: Int,
    ): String {
        if (value.codePointCount(0, value.length) <= max) return value
        val end = value.offsetByCodePoints(0, max - 1)
        return value.substring(0, end).trimEnd() + "…"
    }

    private fun isLoopbackDev(url: String): Boolean =
        listOf("http://localhost", "http://127.0.0.1", "http://[::1]").any { prefix ->
            url == prefix || url.startsWith("$prefix:") || url.startsWith("$prefix/")
        }

    fun targets(
        url: String,
        title: String,
        text: String,
        networks: List<ShareNetwork>,
    ): List<ShareTarget> {
        require(url.startsWith("https://") || isLoopbackDev(url)) { "Share URL must be https or loopback" }
        val u = encode(url)
        val t = encode(title)
        val x = encode(text)
        return networks.mapNotNull { network ->
            val href =
                when (network) {
                    ShareNetwork.NATIVE -> null
                    ShareNetwork.COPY -> url
                    ShareNetwork.FACEBOOK -> "$FACEBOOK$u"
                    ShareNetwork.X -> "$X$u&text=$x"
                    ShareNetwork.LINKEDIN -> "$LINKEDIN$u"
                    ShareNetwork.WHATSAPP -> "$WHATSAPP$x%20$u"
                    ShareNetwork.TELEGRAM -> "$TELEGRAM$u&text=$x"
                    ShareNetwork.REDDIT -> "$REDDIT$u&title=$t"
                    ShareNetwork.EMAIL -> "$MAILTO$t&body=$x%0A%0A$u"
                }
            href?.let { ShareTarget(network = network, href = it) }
        }
    }
}

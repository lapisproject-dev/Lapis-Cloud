package network.lapis.cloud.server.share

/** A share target of the public share bar (V1.9.217). The declaration order IS the display order, never the order of the env value. */
internal enum class ShareNetwork(
    val configName: String,
    /** Brand name for the "Share on %s" label; `null` for the entries that are not a third-party network. */
    val brandName: String?,
) {
    NATIVE("native", null),
    COPY("copy", null),
    WHATSAPP("whatsapp", "WhatsApp"),
    TELEGRAM("telegram", "Telegram"),
    FACEBOOK("facebook", "Facebook"),
    X("x", "X"),
    LINKEDIN("linkedin", "LinkedIn"),
    REDDIT("reddit", "Reddit"),
    EMAIL("email", null),
    ;

    companion object {
        fun parse(token: String): ShareNetwork? = entries.firstOrNull { it.configName == token }
    }
}

package network.lapis.cloud.server.routes

/**
 * Weak-Vergleichssemantik (§ 5.1): auf beiden Seiten das optionale `W/`-Präfix und die
 * Anführungszeichen abstreifen, dann vergleichen; `If-None-Match: *` ⇒ immer Treffer; mehrere
 * komma-separierte Werte ⇒ Treffer, wenn einer passt.
 */
internal fun ifNoneMatchHits(
    headerValue: String?,
    etag: String,
): Boolean {
    if (headerValue == null) return false
    if (headerValue.trim() == "*") return true
    val target = etag.removePrefix("W/").trim('"')
    return headerValue.split(",").map { it.trim() }.any { candidate -> candidate.removePrefix("W/").trim('"') == target }
}

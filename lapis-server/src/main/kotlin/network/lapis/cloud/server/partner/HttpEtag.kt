package network.lapis.cloud.server.partner

/**
 * `If-None-Match` evaluation shared by the conditional-GET routes: `*` matches, a list is allowed, a weak prefix (`W/`) is ignored, the quotes are
 * ignored. Kept in the (neutral) partner package so the public partner feed does not have to import anything from a feature package.
 */
internal fun ifNoneMatchHits(
    headerValue: String?,
    etag: String,
): Boolean {
    if (headerValue == null) return false
    if (headerValue.trim() == "*") return true
    val target = etag.trim('"')
    return headerValue.split(",").map { it.trim().removePrefix("W/") }.any { it.trim('"') == target }
}

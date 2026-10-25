package network.lapis.cloud.server.theme

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.call
import io.ktor.server.http.content.suppressCompression
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.routes.ifNoneMatchHits

internal const val THEME_CSS_PATH = "/theme.css"
internal const val THEME_FONT_ROUTE = "/theme-fonts"

private const val IMMUTABLE = "public, max-age=31536000, immutable"
private const val REVALIDATE = "no-cache"

internal val WOFF2_CONTENT_TYPE: ContentType = ContentType("font", "woff2")

/**
 * V1.9.216 -- `GET /theme.css` (generated, in memory) and `GET /theme-fonts/{file}` (only files the theme loaded at startup).
 * Both answer from the [ResolvedTheme] held in memory: no disk access per request, so no path traversal and no TOCTOU surface.
 * Literal routes -- they win over the `staticFiles("/")` catch-all (which used to serve the raw client `theme.css` here).
 *
 * `?v=<hash12>` equal to the current hash makes the response immutable; anything else revalidates (`no-cache` + ETag, 304).
 * CSS carries a weak ETag with `Vary: Accept-Encoding` (global `Compression`); fonts are never compressed and carry a strong one.
 */
internal fun Route.registerThemeRoutes(theme: ResolvedTheme) {
    get(THEME_CSS_PATH) {
        val versioned = call.request.queryParameters["v"] == theme.hash12
        call.response.header(HttpHeaders.ETag, theme.etag)
        call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        call.response.header(HttpHeaders.CacheControl, if (versioned) IMMUTABLE else REVALIDATE)
        call.response.header("X-Content-Type-Options", "nosniff")
        if (ifNoneMatchHits(headerValue = call.request.headers[HttpHeaders.IfNoneMatch], etag = theme.etag)) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respondText(theme.css, ContentType.Text.CSS.withCharset(Charsets.UTF_8))
        }
    }
    get("$THEME_FONT_ROUTE/{file}") {
        val name = call.parameters["file"]
        val font = if (name == null) null else theme.fonts[name]
        if (font == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.suppressCompression()
        val etag = "\"${font.sha256Hex.take(32)}\""
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, if (call.request.queryParameters["v"] == font.hash12) IMMUTABLE else REVALIDATE)
        call.response.header("X-Content-Type-Options", "nosniff")
        if (ifNoneMatchHits(headerValue = call.request.headers[HttpHeaders.IfNoneMatch], etag = etag)) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respondBytes(font.bytes, WOFF2_CONTENT_TYPE)
        }
    }
}

package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.http.content.suppressCompression
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.membermap.PmtilesBasemap
import network.lapis.cloud.server.membermap.PmtilesProbe
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole

internal const val MEMBER_MAP_BASEMAP_PATH = "/api/board/member-map/basemap.pmtiles"

/**
 * Welle V1.9.5 "Vorstands-Karte" -- the ONE HTTP route the PMTiles basemap's bytes ever travel over
 * (bytes never travel over Kilua RPC, same "one HTTP escape hatch for large binary content" pattern
 * as [registerConferenceRecordingRoutes]/[registerDocumentRoutes]). A `pmtiles://.../basemap.pmtiles/{z}/{x}/{y}`
 * MapLibre source performs HUNDREDS of small `Range` requests against exactly this URL while a
 * member pans/zooms the map.
 *
 * **Auth before file probe, always** -- [resolveCurrentMember]/[requireRole] run FIRST, before
 * [PmtilesBasemap.probe] is even called, so an unauthorized caller learns NOTHING about whether the
 * map feature is configured at all (a 401/403 either way, never a 404-vs-401 timing/existence oracle).
 *
 * **Fail-closed, never fail-loud**: anything other than [PmtilesProbe.Available] (not configured,
 * file missing, wrong type, unreadable, invalid header) answers a plain 404 -- never a 500, never a
 * message distinguishing WHY (see [PmtilesBasemap.probe] KDoc; `MemberMapRoutesTest` pins all of
 * these to 404).
 *
 * **No path parameter, no rate limiter, deliberately.** The path is fixed and never derived from
 * request input (no traversal surface at all), and the `{z}/{x}/{y}` tile addressing MapLibre uses
 * is entirely a client-side `Range`-request concern against this ONE static path -- `PartialContent`
 * (already installed in [network.lapis.cloud.server.Application.module]) answers every range out of
 * the single file. A visit to the map screen legitimately fires hundreds of small range requests in
 * quick succession; a rate limiter tuned for any other route in this codebase would break normal map
 * panning, and there is no abuse case a limiter would meaningfully stop here that authentication does
 * not already stop.
 *
 * **Compression**: `ApplicationCall.suppressCompression()` (from `io.ktor.server.http.content`,
 * `ktor-server-core` -- verified in `ktor-server-core-jvm-3.5.2.jar`, class
 * `io.ktor.server.http.content.SuppressionAttributeKt`; an earlier draft of this wave incorrectly
 * looked for it in `ktor-server-compression-jvm` instead, didn't find it there, and concluded it did
 * not exist at all) is called on every response from this route, BEFORE `call.respond`.
 * `io.ktor.server.plugins.compression.CompressionKt` checks `isCompressionSuppressed(call)` before
 * wrapping any response, so this call-local marker is honored by the global `Compression` plugin
 * installed in [network.lapis.cloud.server.Application.module] without that plugin needing a
 * path-string `condition` naming this route. gzip-wrapping a 206 partial-content response would
 * corrupt the byte offsets PMTiles' own internal directory promises the client, breaking every tile
 * fetch after the first; suppressing compression up front for this route avoids that regardless of
 * Range/status code (`MemberMapRoutesTest` proves this against a full, non-Range 200 response too --
 * the only response size in this file's tests that clears Ktor's default gzip minimum size).
 */
fun Route.registerMemberMapRoutes(basemap: PmtilesBasemap) {
    get(MEMBER_MAP_BASEMAP_PATH) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)

        val probe = basemap.probe()
        if (probe !is PmtilesProbe.Available) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        call.suppressCompression()
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Cache-Control", "private, max-age=86400")
        call.respond(LocalFileContent(probe.file, ContentType.Application.OctetStream))
    }
}

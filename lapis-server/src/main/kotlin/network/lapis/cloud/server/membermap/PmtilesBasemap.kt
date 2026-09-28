package network.lapis.cloud.server.membermap

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The [PMTiles v3 spec](https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md) header: a
 * fixed 127-byte prefix starting with the 7-byte magic `"PMTiles"` followed by a 1-byte spec
 * version. Reading only these 127 bytes (never the full ~187 MB file) is enough to tell a real
 * PMTiles archive from an arbitrary/corrupted/truncated file an operator pointed the config at.
 */
internal object PmtilesHeader {
    const val HEADER_BYTES = 127
    private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)
    const val SPEC_VERSION: Byte = 3

    fun isValid(head: ByteArray): Boolean {
        if (head.size < HEADER_BYTES) return false
        for (i in MAGIC.indices) {
            if (head[i] != MAGIC[i]) return false
        }
        return head[MAGIC.size] == SPEC_VERSION
    }
}

/** Every possible outcome of [PmtilesBasemap.probe] -- deliberately a closed `sealed interface`, never an exception. */
sealed interface PmtilesProbe {
    /** `LAPIS_MAP_PMTILES_PATH` was never set (or was rejected by [MemberMapConfig.load]'s own string validation). */
    data object NotConfigured : PmtilesProbe

    /** Configured, but nothing exists at that path right now. */
    data object Missing : PmtilesProbe

    /** Configured, exists, but is a directory (or other non-regular file). */
    data object NotRegularFile : PmtilesProbe

    /** Configured, exists, is a regular file, but could not be opened/read (permissions, I/O error). */
    data object Unreadable : PmtilesProbe

    /** Readable, but the first 127 bytes do not match the PMTiles v3 header ([PmtilesHeader.isValid]). */
    data object InvalidHeader : PmtilesProbe

    /** Header-valid right now -- safe to serve. */
    data class Available(
        val file: File,
    ) : PmtilesProbe
}

/**
 * Welle V1.9.5 "Vorstands-Karte" -- the ONE place this feature ever touches the configured PMTiles
 * file's bytes before actually streaming them to a client. [probe] runs on EVERY RPC call
 * ([network.lapis.cloud.server.rpc.BoardMemberMapService.getMemberMap]) and before every single HTTP
 * range request ([network.lapis.cloud.server.routes.registerMemberMapRoutes]) -- reading only the
 * first [PmtilesHeader.HEADER_BYTES] bytes, never the whole ~187 MB file, so an operator can replace
 * the file on disk (a new Protomaps extract) and it takes effect on the very next request, no
 * restart needed -- same "no caching, re-probe every time" posture as
 * [network.lapis.cloud.server.branding.BrandingStartupCheck]'s file-existence check, just re-run
 * per-request instead of once at startup (this file additionally IS re-checked at startup by
 * [MemberMapStartupCheck], for the one-time operator-visible log line).
 *
 * **Never throws.** Every failure mode -- missing file, permission denied, wrong magic bytes, a
 * truncated file shorter than the header -- degrades to one of the non-[PmtilesProbe.Available]
 * cases; the RPC service turns that into `tilesAvailable = false` and the HTTP route turns it into a
 * plain 404, never a 500 (see that route's own KDoc "fail-closed, never fail-loud" -- an
 * unauthorized caller must learn NOTHING about whether/why the map is configured).
 */
class PmtilesBasemap(
    private val path: String?,
) {
    fun probe(): PmtilesProbe {
        val currentPath = path ?: return PmtilesProbe.NotConfigured
        val file = File(currentPath)
        if (!file.exists()) return PmtilesProbe.Missing
        if (!file.isFile) return PmtilesProbe.NotRegularFile
        val header =
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val buffer = ByteArray(PmtilesHeader.HEADER_BYTES)
                    val read = raf.read(buffer)
                    if (read < PmtilesHeader.HEADER_BYTES) return PmtilesProbe.InvalidHeader
                    buffer
                }
            } catch (_: IOException) {
                return PmtilesProbe.Unreadable
            } catch (_: SecurityException) {
                return PmtilesProbe.Unreadable
            }
        return if (PmtilesHeader.isValid(header)) PmtilesProbe.Available(file) else PmtilesProbe.InvalidHeader
    }
}

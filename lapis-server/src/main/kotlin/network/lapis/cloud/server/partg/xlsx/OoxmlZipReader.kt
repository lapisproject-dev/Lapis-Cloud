package network.lapis.cloud.server.partg.xlsx

import network.lapis.cloud.shared.domain.PartgImportErrorCode
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Limits of the OOXML container (an `.xlsx` is a ZIP). The upload is untrusted input of a BOARD/ADMIN member; everything stays in memory. */
internal object OoxmlZipPolicy {
    const val MAX_ENTRIES = 64
    const val MAX_ENTRY_UNCOMPRESSED = 8L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED = 20L * 1024 * 1024
    const val MAX_RATIO = 100L
}

/** Why the container is refused. Each maps to a fixed [PartgImportErrorCode]; no message ever echoes an entry name. */
internal enum class OoxmlRejectReason(
    val code: PartgImportErrorCode,
) {
    NOT_A_ZIP(PartgImportErrorCode.NOT_A_XLSX),
    TOO_MANY_ENTRIES(PartgImportErrorCode.TOO_LARGE),
    ENTRY_TOO_LARGE(PartgImportErrorCode.TOO_LARGE),
    TOTAL_TOO_LARGE(PartgImportErrorCode.TOO_LARGE),
    RATIO_EXCEEDED(PartgImportErrorCode.TOO_LARGE),
    BAD_NAME(PartgImportErrorCode.UNSUPPORTED_FILE),
    DUPLICATE_NAME(PartgImportErrorCode.UNSUPPORTED_FILE),
    SYMLINK(PartgImportErrorCode.UNSUPPORTED_FILE),
    ENCRYPTED(PartgImportErrorCode.UNSUPPORTED_FILE),
    UNSUPPORTED_METHOD(PartgImportErrorCode.UNSUPPORTED_FILE),
    ZIP64_OR_MULTIDISK(PartgImportErrorCode.UNSUPPORTED_FILE),
    INCONSISTENT(PartgImportErrorCode.INCONSISTENT),
}

internal class OoxmlRejectedException(
    val reason: OoxmlRejectReason,
) : RuntimeException(reason.name)

internal sealed interface OoxmlZipResult {
    /** [entries] maps every non-directory entry name (printable ASCII, `/` separated, unique) to its inflated bytes. */
    class Ok(
        val entries: Map<String, ByteArray>,
    ) : OoxmlZipResult

    data class Rejected(
        val reason: OoxmlRejectReason,
    ) : OoxmlZipResult
}

private class OoxmlCdEntry(
    val name: String,
    val nameBytes: ByteArray,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val ignored: Boolean,
    val directory: Boolean,
)

/**
 * V1.9.225 -- the two-pass reader of an `.xlsx` container, DERIVED FROM `ArticleImportZipReader` (V1.9.106, same parser and same checks).
 *
 * WHY A COPY: the plan preferred extracting a shared `HardenedZipReader` from the article reader and leaving its tests unchanged. The article
 * reader is fused with its slug/image classification and its localized rejection reasons, and a tripwire pins that file family; extracting
 * it would have meant rewriting a released, hardened security component inside a wave that is not about it (the plan allows this fallback).
 * THE SECURITY LOGIC NOW EXISTS TWICE: a fix to the central-directory parsing, the local-header cross check, the overlap check or the bounded
 * inflater has to be applied to BOTH files.
 *
 * Pass 1 is a strict central-directory parser (whitelist, nothing inflated), pass 2 cross-checks the local headers, refuses overlapping
 * entries and inflates with counters on the ACTUAL byte count. No `ZipInputStream`/`ZipFile`, no file system.
 *
 * Differences to the article reader: names may contain `/` (but never `..`, `.`, a leading `/`, a backslash, `:`, an empty segment, a duplicate
 * or a non-printable character); directory entries are skipped; `__MACOSX/` and `.DS_Store` entries are skipped without being inflated.
 */
internal object OoxmlZipReader {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CD_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val EOCD_MIN_SIZE = 22
    private const val CD_FIXED_SIZE = 46
    private const val LOCAL_FIXED_SIZE = 30
    private const val MAX_COMMENT = 65_535
    private const val MACOSX_PREFIX = "__MACOSX/"
    private const val DS_STORE = ".DS_Store"

    fun read(zip: ByteArray): OoxmlZipResult =
        try {
            readOrThrow(zip)
        } catch (e: OoxmlRejectedException) {
            OoxmlZipResult.Rejected(e.reason)
        } catch (_: Exception) {
            // Defence in depth: nothing a malformed archive can provoke may surface as a 500 with a stack trace.
            OoxmlZipResult.Rejected(OoxmlRejectReason.INCONSISTENT)
        }

    private fun readOrThrow(zip: ByteArray): OoxmlZipResult {
        val entries = parseCentralDirectory(zip)
        val used = entries.filter { !it.ignored && !it.directory }
        // Duplicate names are decided from the names alone, before anything is inflated.
        if (used.map { it.name }.toSet().size != used.size) throw OoxmlRejectedException(OoxmlRejectReason.DUPLICATE_NAME)

        checkLocalHeadersAndOverlap(zip = zip, entries = entries)

        var inflatedTotal = 0L
        val result = LinkedHashMap<String, ByteArray>()
        for (entry in used) {
            val bytes = inflate(zip = zip, entry = entry, remainingBudget = OoxmlZipPolicy.MAX_TOTAL_UNCOMPRESSED - inflatedTotal)
            inflatedTotal += bytes.size
            result[entry.name] = bytes
        }
        return OoxmlZipResult.Ok(result)
    }

    // ---- Pass 1 --------------------------------------------------------------------------------------------------------------

    private fun parseCentralDirectory(zip: ByteArray): List<OoxmlCdEntry> {
        if (zip.size < EOCD_MIN_SIZE) throw OoxmlRejectedException(OoxmlRejectReason.NOT_A_ZIP)
        val eocd = findEndOfCentralDirectory(zip)
        val diskNumber = zip.u16(eocd + 4)
        val cdDisk = zip.u16(eocd + 6)
        val entriesOnDisk = zip.u16(eocd + 8)
        val totalEntries = zip.u16(eocd + 10)
        val cdSize = zip.u32(eocd + 12)
        val cdOffset = zip.u32(eocd + 16)
        if (entriesOnDisk == 0xFFFF || totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw OoxmlRejectedException(OoxmlRejectReason.ZIP64_OR_MULTIDISK)
        }
        if (diskNumber != 0 ||
            cdDisk != 0 ||
            entriesOnDisk != totalEntries
        ) {
            throw OoxmlRejectedException(OoxmlRejectReason.ZIP64_OR_MULTIDISK)
        }
        // BEFORE any entry is read: the declared count.
        if (totalEntries > OoxmlZipPolicy.MAX_ENTRIES) throw OoxmlRejectedException(OoxmlRejectReason.TOO_MANY_ENTRIES)
        val cdEnd = cdOffset + cdSize
        if (cdOffset > eocd || cdEnd > eocd) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)

        val entries = mutableListOf<OoxmlCdEntry>()
        var pos = cdOffset
        var declaredTotal = 0L
        repeat(totalEntries) {
            if (pos + CD_FIXED_SIZE > cdEnd) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            val p = pos.toInt()
            if (zip.u32(p) != CD_SIGNATURE) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            val flags = zip.u16(p + 8)
            val method = zip.u16(p + 10)
            val crc = zip.u32(p + 16)
            val compressedSize = zip.u32(p + 20)
            val uncompressedSize = zip.u32(p + 24)
            val nameLength = zip.u16(p + 28)
            val extraLength = zip.u16(p + 30)
            val commentLength = zip.u16(p + 32)
            val diskStart = zip.u16(p + 34)
            val externalAttributes = zip.u32(p + 38)
            val localHeaderOffset = zip.u32(p + 42)
            val next = pos + CD_FIXED_SIZE + nameLength + extraLength + commentLength
            if (next > cdEnd) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            if (compressedSize == 0xFFFFFFFFL ||
                uncompressedSize == 0xFFFFFFFFL ||
                localHeaderOffset == 0xFFFFFFFFL ||
                diskStart == 0xFFFF
            ) {
                throw OoxmlRejectedException(OoxmlRejectReason.ZIP64_OR_MULTIDISK)
            }
            if (diskStart != 0) throw OoxmlRejectedException(OoxmlRejectReason.ZIP64_OR_MULTIDISK)
            // bit 0 = encrypted, bit 6 = strong encryption
            if (flags and 0x41 != 0) throw OoxmlRejectedException(OoxmlRejectReason.ENCRYPTED)
            if (method != 0 && method != 8) throw OoxmlRejectedException(OoxmlRejectReason.UNSUPPORTED_METHOD)
            if (uncompressedSize >
                OoxmlZipPolicy.MAX_ENTRY_UNCOMPRESSED
            ) {
                throw OoxmlRejectedException(OoxmlRejectReason.ENTRY_TOO_LARGE)
            }
            declaredTotal += uncompressedSize
            if (declaredTotal > OoxmlZipPolicy.MAX_TOTAL_UNCOMPRESSED) throw OoxmlRejectedException(OoxmlRejectReason.TOTAL_TOO_LARGE)
            if (method == 0 && compressedSize != uncompressedSize) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            if (uncompressedSize > 0 && (compressedSize == 0L || uncompressedSize > compressedSize * OoxmlZipPolicy.MAX_RATIO)) {
                throw OoxmlRejectedException(OoxmlRejectReason.RATIO_EXCEEDED)
            }
            val nameBytes = zip.copyOfRange(p + CD_FIXED_SIZE, p + CD_FIXED_SIZE + nameLength)
            val unixMode = (externalAttributes ushr 16) and 0xFFFFL
            val isSymlink = (unixMode and 0xF000L) == 0xA000L
            val isDirectory = (externalAttributes and 0x10L) != 0L || (unixMode and 0xF000L) == 0x4000L
            val classified = classify(nameBytes = nameBytes, isSymlink = isSymlink, isDirectory = isDirectory)
            entries +=
                OoxmlCdEntry(
                    name = classified.name,
                    nameBytes = nameBytes,
                    method = method,
                    crc = crc,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    ignored = classified.ignored,
                    directory = classified.directory,
                )
            pos = next
        }
        // The directory must end exactly where the end record says it does.
        if (pos != cdEnd) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
        return entries
    }

    private fun findEndOfCentralDirectory(zip: ByteArray): Int {
        val lowest = maxOf(0, zip.size - EOCD_MIN_SIZE - MAX_COMMENT)
        var p = zip.size - EOCD_MIN_SIZE
        while (p >= lowest) {
            if (zip.u32(p) == EOCD_SIGNATURE) {
                val commentLength = zip.u16(p + 20)
                // The record must close the file exactly (a trailing comment included), otherwise it is not THE end record.
                if (p + EOCD_MIN_SIZE + commentLength == zip.size) return p
            }
            p--
        }
        throw OoxmlRejectedException(OoxmlRejectReason.NOT_A_ZIP)
    }

    private class Classified(
        val name: String,
        val ignored: Boolean,
        val directory: Boolean,
    )

    private fun classify(
        nameBytes: ByteArray,
        isSymlink: Boolean,
        isDirectory: Boolean,
    ): Classified {
        // macOS resource forks keep the ORIGINAL (often non-ASCII) file name after the prefix, so the system-file test runs on the raw bytes
        // BEFORE the ASCII rule. Such entries are never inflated and never used.
        val prefix = MACOSX_PREFIX.toByteArray(Charsets.US_ASCII)
        if (nameBytes.size >= prefix.size && prefix.indices.all { nameBytes[it] == prefix[it] }) {
            return Classified(name = "", ignored = true, directory = false)
        }
        if (nameBytes.isEmpty() || nameBytes.any { it < 0x20 || it > 0x7E }) throw OoxmlRejectedException(OoxmlRejectReason.BAD_NAME)
        val name = String(nameBytes, Charsets.US_ASCII)
        if (name.substringAfterLast('/') == DS_STORE) return Classified(name = name, ignored = true, directory = false)
        if (isSymlink) throw OoxmlRejectedException(OoxmlRejectReason.SYMLINK)
        val directory = isDirectory || name.endsWith("/")
        val bad =
            name.startsWith("/") ||
                name.contains('\\') ||
                name.contains(':') ||
                name.split('/').any { it == ".." || it == "." } ||
                name.contains("//")
        if (bad) throw OoxmlRejectedException(OoxmlRejectReason.BAD_NAME)
        return Classified(name = name, ignored = false, directory = directory)
    }

    // ---- Pass 2 --------------------------------------------------------------------------------------------------------------

    /** The local view of every entry must agree with the central view, and no two entries may share bytes (overlapping-entry bombs). */
    private fun checkLocalHeadersAndOverlap(
        zip: ByteArray,
        entries: List<OoxmlCdEntry>,
    ) {
        val ranges = mutableListOf<LongRange>()
        for (entry in entries) {
            val p = entry.localHeaderOffset
            if (p + LOCAL_FIXED_SIZE > zip.size) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            val pos = p.toInt()
            if (zip.u32(pos) != LOCAL_SIGNATURE) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            val method = zip.u16(pos + 8)
            val nameLength = zip.u16(pos + 26)
            val extraLength = zip.u16(pos + 28)
            val nameEnd = pos + LOCAL_FIXED_SIZE + nameLength
            if (nameEnd > zip.size) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            if (method != entry.method || nameLength != entry.nameBytes.size) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            if (!zip.copyOfRange(pos + LOCAL_FIXED_SIZE, nameEnd).contentEquals(entry.nameBytes)) {
                throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            }
            val dataStart = nameEnd.toLong() + extraLength
            val dataEnd = dataStart + entry.compressedSize
            if (dataEnd > zip.size) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            ranges += p until dataEnd.coerceAtLeast(p + 1)
        }
        val sorted = ranges.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            if (sorted[i].first <= sorted[i - 1].last) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
        }
    }

    private fun inflate(
        zip: ByteArray,
        entry: OoxmlCdEntry,
        remainingBudget: Long,
    ): ByteArray {
        val pos = entry.localHeaderOffset.toInt()
        val dataStart = pos + LOCAL_FIXED_SIZE + zip.u16(pos + 26) + zip.u16(pos + 28)
        val compressedSize = entry.compressedSize.toInt()
        val declared = entry.uncompressedSize.toInt()
        val cap = minOf(OoxmlZipPolicy.MAX_ENTRY_UNCOMPRESSED, remainingBudget)
        val out: ByteArray =
            if (entry.method == 0) {
                zip.copyOfRange(dataStart, dataStart + compressedSize)
            } else {
                inflateRaw(zip = zip, start = dataStart, length = compressedSize, declared = declared, cap = cap)
            }
        if (out.size.toLong() > cap) throw OoxmlRejectedException(OoxmlRejectReason.ENTRY_TOO_LARGE)
        if (out.size != declared) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
        val crc = CRC32().apply { update(out) }.value
        if (crc != entry.crc) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
        return out
    }

    private fun inflateRaw(
        zip: ByteArray,
        start: Int,
        length: Int,
        declared: Int,
        cap: Long,
    ): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(zip, start, length)
            val buffer = ByteArray(8192)
            val result = java.io.ByteArrayOutputStream(declared.coerceIn(0, 1 shl 20))
            var total = 0L
            while (!inflater.finished()) {
                val n =
                    try {
                        inflater.inflate(buffer)
                    } catch (_: DataFormatException) {
                        throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
                    }
                if (n > 0) {
                    total += n
                    // The ACTUAL byte count decides, whatever the headers claimed.
                    if (total > cap) throw OoxmlRejectedException(OoxmlRejectReason.ENTRY_TOO_LARGE)
                    result.write(buffer, 0, n)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    // truncated stream or a preset dictionary: not an image of ours
                    throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
                }
            }
            if (inflater.remaining != 0) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
            return result.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ---- little-endian readers, every access bounds-checked ---------------------------------------------------------------------

    private fun ByteArray.u16(at: Int): Int {
        if (at < 0 || at + 2 > size) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
        return (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.u32(at: Int): Long {
        if (at < 0 || at + 4 > size) throw OoxmlRejectedException(OoxmlRejectReason.INCONSISTENT)
        return (this[at].toLong() and 0xFF) or
            ((this[at + 1].toLong() and 0xFF) shl 8) or
            ((this[at + 2].toLong() and 0xFF) shl 16) or
            ((this[at + 3].toLong() and 0xFF) shl 24)
    }
}

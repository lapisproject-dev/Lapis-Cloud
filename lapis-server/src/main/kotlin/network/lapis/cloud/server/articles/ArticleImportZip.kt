package network.lapis.cloud.server.articles

import network.lapis.cloud.server.events.EventCoverPolicy
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Welle V1.9.106 -- limits of the image ZIP of an article import. The ZIP is untrusted input of a BOARD/ADMIN member and is handled
 * **entirely in memory and never touches the file system**: no entry name is ever used to build a location, the stored cover files get
 * `Uuid.random()` names from `EventCoverStorage` (a tripwire pins that this file family references no `File`/`Files`/path API).
 */
internal object ArticleImportZipPolicy {
    /** Cap of the uploaded ZIP file itself. The request body cap adds a small multipart allowance on top (see `ARTICLE_IMPORT_ZIP_MAX_REQUEST_BYTES`). */
    const val MAX_ZIP_BYTES = 25L * 1024 * 1024

    /** Entry count of the central directory, INCLUDING `__MACOSX`/`.DS_Store` entries. Checked before anything is inflated. */
    const val MAX_ENTRIES = 100

    /** One image: the same cap as every other cover upload (referenced, not duplicated). */
    const val MAX_ENTRY_UNCOMPRESSED = EventCoverPolicy.MAX_UPLOAD_BYTES

    /** All entries together, declared (central directory) AND actually inflated. */
    const val MAX_TOTAL_UNCOMPRESSED = 64L * 1024 * 1024

    /** Maximum uncompressed/compressed ratio of one entry. */
    const val MAX_RATIO = 100L

    /** Stem of an image name = an import slug (`ArticleImportPolicy.SLUG_PATTERN`, at most 120 characters; underscores are allowed because legacy slugs have them). */
    const val MAX_STEM_LENGTH = ArticleImportPolicy.SLUG_MAX_LENGTH

    /** Accepted extensions, lowercase compare. */
    val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png")

    /** A ZIP inside the ZIP is refused outright. */
    val ARCHIVE_EXTENSIONS: Set<String> = setOf("zip", "gz", "tgz", "tar", "bz2", "xz", "7z", "rar", "jar")
}

/** Why a whole ZIP is refused. The route maps each to a fixed German sentence ([messageDe]); no message ever echoes a file name. */
internal enum class ZipRejectReason(
    val messageDe: String,
) {
    NOT_A_ZIP("Die Datei ist kein lesbares ZIP-Archiv."),
    TOO_MANY_ENTRIES("Das ZIP enthält zu viele Dateien (höchstens ${ArticleImportZipPolicy.MAX_ENTRIES})."),
    ENTRY_TOO_LARGE("Ein Bild im ZIP ist zu groß (höchstens 5 MB je Bild)."),
    TOTAL_TOO_LARGE("Das ZIP ist entpackt zu groß."),
    RATIO_EXCEEDED("Das ZIP ist ungewöhnlich stark komprimiert und wird abgelehnt."),
    FOLDER_NOT_ALLOWED("Bilder müssen direkt im ZIP liegen, ohne Ordner."),
    BAD_NAME("Das ZIP enthält Dateinamen, die nicht erlaubt sind (erwartet: slug.jpg, slug.jpeg oder slug.png)."),
    SYMLINK("Das ZIP enthält verknüpfte Dateien (Symlinks) und wird abgelehnt."),
    ENCRYPTED("Verschlüsselte ZIP-Archive werden nicht unterstützt."),
    NESTED_ARCHIVE("Das ZIP enthält ein weiteres Archiv und wird abgelehnt."),
    UNSUPPORTED_METHOD("Das ZIP verwendet ein nicht unterstütztes Kompressionsverfahren."),
    ZIP64_OR_MULTIDISK("Sehr große oder mehrteilige ZIP-Archive werden nicht unterstützt."),
    INCONSISTENT("Das ZIP ist beschädigt oder widersprüchlich aufgebaut."),
}

internal class ZipRejectedException(
    val reason: ZipRejectReason,
) : RuntimeException(reason.name)

/** One accepted image of the ZIP: [slug] is the file name without extension. */
internal class ZipImage(
    val slug: String,
    val fileName: String,
    val bytes: ByteArray,
)

internal sealed interface ZipReadResult {
    /**
     * [images] holds every UNIQUE slug once. A slug that appears more than once (`a.jpg` + `a.png`, or the very same name twice) is in
     * [duplicateSlugs] and none of its files is taken -- ambiguity is never resolved by guessing. [ignoredSystemFiles] counts `__MACOSX/`
     * and `.DS_Store` entries, which are skipped without being inflated.
     */
    class Ok(
        val images: List<ZipImage>,
        val duplicateSlugs: Set<String>,
        val ignoredSystemFiles: Int,
    ) : ZipReadResult

    data class Rejected(
        val reason: ZipRejectReason,
    ) : ZipReadResult
}

/** One entry of the central directory, all numbers already range-checked and non-negative. */
private class CdEntry(
    val name: String,
    val nameBytes: ByteArray,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val ignored: Boolean,
    val slug: String?,
    val fileName: String,
)

/**
 * Welle V1.9.106 -- the two-pass reader of an image ZIP.
 *
 * **Pass 1 (strict central-directory parser, a whitelist -- nothing is inflated):** the end-of-central-directory record is searched in the last
 * 64 KiB only and must close the file exactly; no ZIP64 marker, no multi-disk archive; the entry count (<= 100) is checked BEFORE any entry
 * is read; every length field is checked against the buffer; per entry: method STORED or DEFLATED only, no encryption bit, no symlink or
 * directory in the external attributes, the raw name bytes must be printable ASCII (this kills Unicode/NFC/RTL tricks; bit 11 is irrelevant),
 * the declared sizes and the declared compression ratio must be within the limits, and the name must be a flat `slug.jpg|jpeg|png`.
 *
 * **Pass 2 (inflate with hard counters, only the entries that are actually used):** the local header at the declared offset must carry the same
 * name and method as the central entry (the flags are deliberately not compared: decompression and the CRC check only use the central
 * values); a divergence between the two views is the classic way to smuggle content past a validator and
 * is refused as [ZipRejectReason.INCONSISTENT]. The data ranges of all entries must not overlap; inflating counts the bytes ACTUALLY produced
 * (never trusting a declared size) and aborts at the first byte over the per-entry or the total cap; the result must match the declared size
 * and the declared CRC-32 exactly. The decoder is `java.util.zip.Inflater` with raw input -- no `ZipInputStream`/`ZipFile`, whose name decoding
 * and attribute handling would have to be re-validated.
 *
 * Every failure is a [ZipReadResult.Rejected]; no exception of the parser or the inflater escapes.
 */
internal object ArticleImportZipReader {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CD_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val EOCD_MIN_SIZE = 22
    private const val CD_FIXED_SIZE = 46
    private const val LOCAL_FIXED_SIZE = 30
    private const val MAX_COMMENT = 65_535
    private const val MACOSX_PREFIX = "__MACOSX/"
    private const val DS_STORE = ".DS_Store"

    fun read(zip: ByteArray): ZipReadResult =
        try {
            readOrThrow(zip)
        } catch (e: ZipRejectedException) {
            ZipReadResult.Rejected(e.reason)
        } catch (_: Exception) {
            // Defence in depth: nothing a malformed archive can provoke may surface as a 500 with a stack trace.
            ZipReadResult.Rejected(ZipRejectReason.INCONSISTENT)
        }

    private fun readOrThrow(zip: ByteArray): ZipReadResult {
        val entries = parseCentralDirectory(zip)
        val ignoredCount = entries.count { it.ignored }

        // Duplicate slugs are decided from the names alone, before anything is inflated.
        val bySlug = entries.filter { it.slug != null }.groupBy { it.slug!! }
        val duplicates = bySlug.filterValues { it.size > 1 }.keys

        checkLocalHeadersAndOverlap(zip = zip, entries = entries)

        var inflatedTotal = 0L
        val images = mutableListOf<ZipImage>()
        for (entry in entries) {
            val slug = entry.slug ?: continue
            if (slug in duplicates) continue
            val bytes = inflate(zip = zip, entry = entry, remainingBudget = ArticleImportZipPolicy.MAX_TOTAL_UNCOMPRESSED - inflatedTotal)
            inflatedTotal += bytes.size
            images += ZipImage(slug = slug, fileName = entry.fileName, bytes = bytes)
        }
        return ZipReadResult.Ok(images = images, duplicateSlugs = duplicates, ignoredSystemFiles = ignoredCount)
    }

    // ---- Pass 1 --------------------------------------------------------------------------------------------------------------

    private fun parseCentralDirectory(zip: ByteArray): List<CdEntry> {
        if (zip.size < EOCD_MIN_SIZE) throw ZipRejectedException(ZipRejectReason.NOT_A_ZIP)
        val eocd = findEndOfCentralDirectory(zip)
        val diskNumber = zip.u16(eocd + 4)
        val cdDisk = zip.u16(eocd + 6)
        val entriesOnDisk = zip.u16(eocd + 8)
        val totalEntries = zip.u16(eocd + 10)
        val cdSize = zip.u32(eocd + 12)
        val cdOffset = zip.u32(eocd + 16)
        if (entriesOnDisk == 0xFFFF || totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw ZipRejectedException(ZipRejectReason.ZIP64_OR_MULTIDISK)
        }
        if (diskNumber != 0 || cdDisk != 0 || entriesOnDisk != totalEntries) throw ZipRejectedException(ZipRejectReason.ZIP64_OR_MULTIDISK)
        // BEFORE any entry is read: the declared count.
        if (totalEntries > ArticleImportZipPolicy.MAX_ENTRIES) throw ZipRejectedException(ZipRejectReason.TOO_MANY_ENTRIES)
        val cdEnd = cdOffset + cdSize
        if (cdOffset > eocd || cdEnd > eocd) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)

        val entries = mutableListOf<CdEntry>()
        var pos = cdOffset
        var declaredTotal = 0L
        repeat(totalEntries) {
            if (pos + CD_FIXED_SIZE > cdEnd) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            val p = pos.toInt()
            if (zip.u32(p) != CD_SIGNATURE) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
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
            if (next > cdEnd) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            if (compressedSize == 0xFFFFFFFFL ||
                uncompressedSize == 0xFFFFFFFFL ||
                localHeaderOffset == 0xFFFFFFFFL ||
                diskStart == 0xFFFF
            ) {
                throw ZipRejectedException(ZipRejectReason.ZIP64_OR_MULTIDISK)
            }
            if (diskStart != 0) throw ZipRejectedException(ZipRejectReason.ZIP64_OR_MULTIDISK)
            // bit 0 = encrypted, bit 6 = strong encryption
            if (flags and 0x41 != 0) throw ZipRejectedException(ZipRejectReason.ENCRYPTED)
            if (method != 0 && method != 8) throw ZipRejectedException(ZipRejectReason.UNSUPPORTED_METHOD)
            if (uncompressedSize >
                ArticleImportZipPolicy.MAX_ENTRY_UNCOMPRESSED
            ) {
                throw ZipRejectedException(ZipRejectReason.ENTRY_TOO_LARGE)
            }
            declaredTotal += uncompressedSize
            if (declaredTotal > ArticleImportZipPolicy.MAX_TOTAL_UNCOMPRESSED) throw ZipRejectedException(ZipRejectReason.TOTAL_TOO_LARGE)
            if (method == 0 && compressedSize != uncompressedSize) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            if (uncompressedSize > 0 && (compressedSize == 0L || uncompressedSize > compressedSize * ArticleImportZipPolicy.MAX_RATIO)) {
                throw ZipRejectedException(ZipRejectReason.RATIO_EXCEEDED)
            }
            val nameBytes = zip.copyOfRange(p + CD_FIXED_SIZE, p + CD_FIXED_SIZE + nameLength)
            val unixMode = (externalAttributes ushr 16) and 0xFFFFL
            val isSymlink = (unixMode and 0xF000L) == 0xA000L
            val isDirectory = (externalAttributes and 0x10L) != 0L || (unixMode and 0xF000L) == 0x4000L
            val classified = classify(nameBytes = nameBytes, isSymlink = isSymlink, isDirectory = isDirectory)
            entries +=
                CdEntry(
                    name = classified.name,
                    nameBytes = nameBytes,
                    method = method,
                    crc = crc,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    ignored = classified.ignored,
                    slug = classified.slug,
                    fileName = classified.name,
                )
            pos = next
        }
        // The directory must end exactly where the end record says it does.
        if (pos != cdEnd) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
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
        throw ZipRejectedException(ZipRejectReason.NOT_A_ZIP)
    }

    private class Classified(
        val name: String,
        val ignored: Boolean,
        val slug: String?,
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
            return Classified(name = "", ignored = true, slug = null)
        }
        if (nameBytes.isEmpty() || nameBytes.any { it < 0x20 || it > 0x7E }) throw ZipRejectedException(ZipRejectReason.BAD_NAME)
        val name = String(nameBytes, Charsets.US_ASCII)
        if (name.substringAfterLast('/') == DS_STORE) return Classified(name = name, ignored = true, slug = null)
        if (isSymlink) throw ZipRejectedException(ZipRejectReason.SYMLINK)
        if (isDirectory || name.endsWith("/") || name.contains('/') || name.contains('\\') || name.contains(':') || name.contains("..")) {
            throw ZipRejectedException(ZipRejectReason.FOLDER_NOT_ALLOWED)
        }
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension in ArticleImportZipPolicy.ARCHIVE_EXTENSIONS) throw ZipRejectedException(ZipRejectReason.NESTED_ARCHIVE)
        val stem = name.substringBeforeLast('.', missingDelimiterValue = "")
        if (extension !in ArticleImportZipPolicy.IMAGE_EXTENSIONS ||
            stem.isEmpty() ||
            stem.length > ArticleImportZipPolicy.MAX_STEM_LENGTH ||
            !ArticleImportPolicy.SLUG_PATTERN.matches(stem)
        ) {
            throw ZipRejectedException(ZipRejectReason.BAD_NAME)
        }
        return Classified(name = name, ignored = false, slug = stem)
    }

    // ---- Pass 2 --------------------------------------------------------------------------------------------------------------

    /** The local view of every entry must agree with the central view, and no two entries may share bytes (overlapping-entry bombs). */
    private fun checkLocalHeadersAndOverlap(
        zip: ByteArray,
        entries: List<CdEntry>,
    ) {
        val ranges = mutableListOf<LongRange>()
        for (entry in entries) {
            val p = entry.localHeaderOffset
            if (p + LOCAL_FIXED_SIZE > zip.size) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            val pos = p.toInt()
            if (zip.u32(pos) != LOCAL_SIGNATURE) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            val method = zip.u16(pos + 8)
            val nameLength = zip.u16(pos + 26)
            val extraLength = zip.u16(pos + 28)
            val nameEnd = pos + LOCAL_FIXED_SIZE + nameLength
            if (nameEnd > zip.size) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            if (method != entry.method || nameLength != entry.nameBytes.size) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            if (!zip.copyOfRange(pos + LOCAL_FIXED_SIZE, nameEnd).contentEquals(entry.nameBytes)) {
                throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            }
            val dataStart = nameEnd.toLong() + extraLength
            val dataEnd = dataStart + entry.compressedSize
            if (dataEnd > zip.size) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            ranges += p until dataEnd.coerceAtLeast(p + 1)
        }
        val sorted = ranges.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            if (sorted[i].first <= sorted[i - 1].last) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
        }
    }

    private fun inflate(
        zip: ByteArray,
        entry: CdEntry,
        remainingBudget: Long,
    ): ByteArray {
        val pos = entry.localHeaderOffset.toInt()
        val dataStart = pos + LOCAL_FIXED_SIZE + zip.u16(pos + 26) + zip.u16(pos + 28)
        val compressedSize = entry.compressedSize.toInt()
        val declared = entry.uncompressedSize.toInt()
        val cap = minOf(ArticleImportZipPolicy.MAX_ENTRY_UNCOMPRESSED, remainingBudget)
        val out: ByteArray =
            if (entry.method == 0) {
                zip.copyOfRange(dataStart, dataStart + compressedSize)
            } else {
                inflateRaw(zip = zip, start = dataStart, length = compressedSize, declared = declared, cap = cap)
            }
        if (out.size.toLong() > cap) throw ZipRejectedException(ZipRejectReason.ENTRY_TOO_LARGE)
        if (out.size != declared) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
        val crc = CRC32().apply { update(out) }.value
        if (crc != entry.crc) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
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
                        throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
                    }
                if (n > 0) {
                    total += n
                    // The ACTUAL byte count decides, whatever the headers claimed.
                    if (total > cap) throw ZipRejectedException(ZipRejectReason.ENTRY_TOO_LARGE)
                    result.write(buffer, 0, n)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    // truncated stream or a preset dictionary: not an image of ours
                    throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
                }
            }
            if (inflater.remaining != 0) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
            return result.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ---- little-endian readers, every access bounds-checked ---------------------------------------------------------------------

    private fun ByteArray.u16(at: Int): Int {
        if (at < 0 || at + 2 > size) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
        return (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.u32(at: Int): Long {
        if (at < 0 || at + 4 > size) throw ZipRejectedException(ZipRejectReason.INCONSISTENT)
        return (this[at].toLong() and 0xFF) or
            ((this[at + 1].toLong() and 0xFF) shl 8) or
            ((this[at + 2].toLong() and 0xFF) shl 16) or
            ((this[at + 3].toLong() and 0xFF) shl 24)
    }
}

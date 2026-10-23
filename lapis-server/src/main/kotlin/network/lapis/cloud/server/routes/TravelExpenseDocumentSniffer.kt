package network.lapis.cloud.server.routes

import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * V1.9.199 -- content classification of an uploaded organisation-wide travel-expense document (PDF, XLSX or XLS). Pure (no Ktor), runs on
 * the COMPLETE stored file after streaming, and never throws: any read error, out-of-range value or oddity means "not a document we
 * accept" ([FileRejection.TYPE]). The client-declared type and the file extension are never consulted.
 *
 * - **PDF**: `%PDF-` at offset 0; a `/Encrypt` token anywhere ([FileRejection.ENCRYPTED]) is found by a streaming scan with an overlapping
 *   window, so a token on a buffer boundary is not missed. (A name-obfuscated `/Encr#79pt` is not detected -- see the CHANGELOG.)
 * - **XLSX**: an own, bounded ZIP central-directory parser. `java.util.zip.ZipFile`/`ZipInputStream` are deliberately NOT used: they trust
 *   header values and iterate over unvetted structure. ZIP64, multi-disk archives, more than [MAX_ZIP_ENTRIES] entries, names over
 *   [MAX_ZIP_NAME_BYTES] bytes and encrypted entries are refused; macro parts (`vbaProject.bin`, `macrosheets/`, `activeX/`,
 *   `embeddings/`, `xl/workbook.bin`) and a macro-enabled content type are [FileRejection.MACRO]. The only entry that is ever inflated is
 *   `[Content_Types].xml`, capped at [MAX_CONTENT_TYPES_BYTES] of real output with a 100:1 ratio limit (the inflated bytes are counted,
 *   not the header value).
 * - **XLS (OLE2)**: header + directory walk only (no BIFF parse): the directory chain is followed through the FAT with a visited set and a
 *   sector cap. `EncryptionInfo`/`EncryptedPackage` is [FileRejection.ENCRYPTED] (an encrypted OOXML package), `_VBA_PROJECT_CUR`/`VBA` is
 *   [FileRejection.MACRO], no `Workbook`/`Book` stream is [FileRejection.TYPE] (a `.doc`, `.ppt`). A legacy RC4 `FILEPASS` record inside the
 *   workbook stream is NOT detected. Likewise NOT detected: Excel 4.0 (XLM) macro sheets, which live INSIDE the `Workbook` BIFF stream
 *   (a `BOUNDSHEET` record with a macro-sheet type), not in a VBA storage -- a legacy `.xls` with an `Auto_Open` XLM macro passes as
 *   "no macros". Known gap, accepted because uploads are limited to BOARD/ADMIN; a BIFF `BOUNDSHEET` scan is the follow-up.
 *
 * Nothing about the file is logged.
 */
internal object TravelExpenseDocumentSniffer {
    internal const val MAX_ZIP_ENTRIES = 2000
    internal const val MAX_ZIP_NAME_BYTES = 512
    internal const val MAX_CONTENT_TYPES_BYTES = 256 * 1024
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 1024 * 1024
    private const val MAX_INFLATE_RATIO = 100
    private const val EOCD_SEARCH_BYTES = 65_557
    private const val MAX_OLE_DIRECTORY_SECTORS = 128
    private const val OLE_END_OF_CHAIN = 0xFFFFFFFEL

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.ISO_8859_1)
    private val ZIP_LOCAL_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val OLE_MAGIC =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val ENCRYPT_TOKEN = "/Encrypt".toByteArray(Charsets.ISO_8859_1)

    private val TYPE = FileClassification.Rejected(FileRejection.TYPE)
    private val MACRO = FileClassification.Rejected(FileRejection.MACRO)
    private val ENCRYPTED = FileClassification.Rejected(FileRejection.ENCRYPTED)

    /** Entry point used as the `classify` callback of [streamSingleTravelExpenseFile]. */
    fun classify(
        file: File,
        head: ByteArray,
        filled: Int,
    ): FileClassification =
        try {
            when {
                file.length() <= 0L -> TYPE
                startsWith(bytes = head, filled = filled, prefix = PDF_MAGIC) -> classifyPdf(file)
                startsWith(bytes = head, filled = filled, prefix = ZIP_LOCAL_MAGIC) -> classifyOoxml(file)
                startsWith(bytes = head, filled = filled, prefix = OLE_MAGIC) -> classifyOle2(file)
                else -> TYPE
            }
        } catch (_: Exception) {
            TYPE
        }

    internal fun classifyPdf(file: File): FileClassification {
        val needle = ENCRYPT_TOKEN
        val overlap = needle.size - 1
        file.inputStream().use { input ->
            val buffer = ByteArray(8192 + overlap)
            var carried = 0
            var first = true
            while (true) {
                val read = input.read(buffer, carried, buffer.size - carried)
                if (read == -1) break
                val filled = carried + read
                if (first) {
                    if (!startsWith(bytes = buffer, filled = filled, prefix = PDF_MAGIC)) return TYPE
                    first = false
                }
                if (indexOf(bytes = buffer, filled = filled, needle = needle) >= 0) return ENCRYPTED
                carried = minOf(overlap, filled)
                System.arraycopy(buffer, filled - carried, buffer, 0, carried)
            }
            if (first) return TYPE
        }
        return FileClassification.Accepted(TravelExpenseDocumentRules.MIME_PDF)
    }

    internal fun classifyOoxml(file: File): FileClassification {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < EOCD_MIN_BYTES) return TYPE
            val tailSize = minOf(length, EOCD_SEARCH_BYTES.toLong()).toInt()
            val tailStart = length - tailSize
            val tail = ByteArray(tailSize)
            raf.seek(tailStart)
            raf.readFully(tail)
            var eocd = -1
            var i = tailSize - EOCD_MIN_BYTES
            while (i >= 0) {
                if (tail.u32(i) == EOCD_SIGNATURE) {
                    val commentLength = tail.u16(i + 20)
                    if (i + EOCD_MIN_BYTES + commentLength == tailSize) {
                        eocd = i
                        break
                    }
                }
                i--
            }
            if (eocd < 0) return TYPE
            // ZIP64 (locator right before the EOCD, or saturated fields) is refused: no honest spreadsheet needs it under 10 MiB.
            if (eocd >= ZIP64_LOCATOR_BYTES && tail.u32(eocd - ZIP64_LOCATOR_BYTES) == ZIP64_LOCATOR_SIGNATURE) return TYPE
            val diskNumber = tail.u16(eocd + 4)
            val cdDisk = tail.u16(eocd + 6)
            val entriesOnDisk = tail.u16(eocd + 8)
            val totalEntries = tail.u16(eocd + 10)
            val cdSize = tail.u32(eocd + 12)
            val cdOffset = tail.u32(eocd + 16)
            if (diskNumber != 0 || cdDisk != 0 || entriesOnDisk != totalEntries) return TYPE
            if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return TYPE
            if (totalEntries == 0 || totalEntries > MAX_ZIP_ENTRIES) return TYPE
            val eocdAbsolute = tailStart + eocd
            if (cdSize > MAX_CENTRAL_DIRECTORY_BYTES || cdOffset + cdSize > eocdAbsolute) return TYPE

            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)

            var position = 0
            var contentTypes: ZipEntry? = null
            var workbook: ZipEntry? = null
            var duplicateRequired = false
            var macro = false
            var encrypted = false
            repeat(totalEntries) {
                if (position + CD_FIXED_BYTES > cd.size || cd.u32(position) != CD_SIGNATURE) return TYPE
                val flags = cd.u16(position + 8)
                val method = cd.u16(position + 10)
                val compressedSize = cd.u32(position + 20)
                val uncompressedSize = cd.u32(position + 24)
                val nameLength = cd.u16(position + 28)
                val extraLength = cd.u16(position + 30)
                val commentLength = cd.u16(position + 32)
                val localOffset = cd.u32(position + 42)
                if (nameLength > MAX_ZIP_NAME_BYTES) return TYPE
                val entryEnd = position + CD_FIXED_BYTES + nameLength + extraLength + commentLength
                if (entryEnd > cd.size) return TYPE
                if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) return TYPE
                if (flags and 1 != 0) encrypted = true
                val name = String(cd, position + CD_FIXED_BYTES, nameLength, Charsets.UTF_8).lowercase()
                if (isMacroPart(name)) macro = true
                val entry =
                    ZipEntry(
                        method = method,
                        compressedSize = compressedSize,
                        uncompressedSize = uncompressedSize,
                        localOffset = localOffset,
                    )
                if (name == "[content_types].xml") {
                    if (contentTypes != null) duplicateRequired = true
                    contentTypes = entry
                } else if (name == "xl/workbook.xml") {
                    if (workbook != null) duplicateRequired = true
                    workbook = entry
                }
                position = entryEnd
            }
            if (encrypted) return ENCRYPTED
            if (macro) return MACRO
            val types = contentTypes
            if (duplicateRequired || types == null || workbook == null) return TYPE
            val text = readContentTypes(raf = raf, entry = types, limit = cdOffset, fileLength = length) ?: return TYPE
            val lowered = text.lowercase()
            if (lowered.contains("macroenabled") || lowered.contains("vbaproject")) return MACRO
        }
        return FileClassification.Accepted(TravelExpenseDocumentRules.MIME_XLSX)
    }

    private fun isMacroPart(name: String): Boolean =
        name.endsWith("vbaproject.bin") ||
            name.startsWith("xl/macrosheets/") ||
            name.startsWith("xl/activex/") ||
            name.startsWith("xl/embeddings/") ||
            name == "xl/workbook.bin"

    private class ZipEntry(
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long,
    )

    /** Reads `[Content_Types].xml` through its local header; `null` == anything about it is not plausible. */
    private fun readContentTypes(
        raf: RandomAccessFile,
        entry: ZipEntry,
        limit: Long,
        fileLength: Long,
    ): String? {
        if (entry.localOffset + LOCAL_FIXED_BYTES > fileLength) return null
        val local = ByteArray(LOCAL_FIXED_BYTES)
        raf.seek(entry.localOffset)
        raf.readFully(local)
        if (local.u32(0) != LOCAL_SIGNATURE) return null
        val dataStart = entry.localOffset + LOCAL_FIXED_BYTES + local.u16(26) + local.u16(28)
        val compressed = entry.compressedSize
        if (compressed > MAX_CONTENT_TYPES_BYTES || dataStart + compressed > limit) return null
        if (entry.uncompressedSize > MAX_CONTENT_TYPES_BYTES) return null
        val input = ByteArray(compressed.toInt())
        raf.seek(dataStart)
        raf.readFully(input)
        val output =
            when (entry.method) {
                0 -> if (entry.uncompressedSize == compressed) input else return null
                8 -> inflateBounded(input = input) ?: return null
                else -> return null
            }
        if (compressed == 0L) {
            if (output.isNotEmpty()) return null
        } else if (output.size / compressed > MAX_INFLATE_RATIO) {
            return null
        }
        return decodeXml(output)
    }

    /** Raw-deflate [input] with a hard cap on the REAL output; `null` if the cap is hit or the stream is broken/truncated. */
    private fun inflateBounded(input: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
                if (out.size() > MAX_CONTENT_TYPES_BYTES) return null
            }
            return out.toByteArray()
        } catch (_: Exception) {
            return null
        } finally {
            inflater.end()
        }
    }

    private fun decodeXml(bytes: ByteArray): String =
        when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.ISO_8859_1)
        }

    internal fun classifyOle2(file: File): FileClassification {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < OLE_HEADER_BYTES) return TYPE
            val header = ByteArray(OLE_HEADER_BYTES)
            raf.readFully(header)
            if (!startsWith(bytes = header, filled = header.size, prefix = OLE_MAGIC)) return TYPE
            if (header.u16(28) != 0xFFFE) return TYPE
            val sectorShift = header.u16(30)
            if (sectorShift != 9 && sectorShift != 12) return TYPE
            val sectorSize = 1 shl sectorShift
            val totalSectors = (length - sectorSize) / sectorSize
            if (totalSectors <= 0) return TYPE
            val fatSectorCount = header.u32(44)
            // More than the 109 header DIFAT entries would need the DIFAT chain: not supported, refused (documented limit).
            if (fatSectorCount < 1 || fatSectorCount > OLE_HEADER_DIFAT_ENTRIES) return TYPE
            val entriesPerSector = sectorSize / 4
            val sectorBuffer = ByteArray(sectorSize)

            fun sectorOffset(sector: Long): Long = (sector + 1) * sectorSize

            fun nextSector(sector: Long): Long? {
                if (sector < 0 || sector >= totalSectors) return null
                val fatIndex = (sector / entriesPerSector).toInt()
                if (fatIndex >= fatSectorCount) return null
                val fatSector = header.u32(76 + fatIndex * 4)
                if (fatSector >= totalSectors) return null
                raf.seek(sectorOffset(fatSector) + (sector % entriesPerSector) * 4)
                val raw = ByteArray(4)
                raf.readFully(raw)
                return raw.u32(0)
            }

            val names = mutableListOf<String>()
            val visited = HashSet<Long>()
            var sector = header.u32(48)
            var sectorsRead = 0
            while (sector != OLE_END_OF_CHAIN) {
                if (sector >= totalSectors || !visited.add(sector) || sectorsRead >= MAX_OLE_DIRECTORY_SECTORS) return TYPE
                raf.seek(sectorOffset(sector))
                raf.readFully(sectorBuffer)
                sectorsRead++
                var entry = 0
                while (entry + OLE_DIRECTORY_ENTRY_BYTES <= sectorSize) {
                    val type = sectorBuffer[entry + 66].toInt() and 0xFF
                    val nameBytes = sectorBuffer.u16(entry + 64)
                    if ((type == 1 || type == 2) && nameBytes in 2..64 && nameBytes % 2 == 0) {
                        names += String(sectorBuffer, entry, nameBytes - 2, Charsets.UTF_16LE)
                    }
                    entry += OLE_DIRECTORY_ENTRY_BYTES
                }
                sector = nextSector(sector) ?: return TYPE
            }
            if (names.any { it.equals("EncryptionInfo", true) || it.equals("EncryptedPackage", true) }) return ENCRYPTED
            if (names.any { it.equals("_VBA_PROJECT_CUR", true) || it.equals("VBA", true) }) return MACRO
            if (names.none { it.equals("Workbook", true) || it.equals("Book", true) }) return TYPE
        }
        return FileClassification.Accepted(TravelExpenseDocumentRules.MIME_XLS)
    }

    private fun startsWith(
        bytes: ByteArray,
        filled: Int,
        prefix: ByteArray,
    ): Boolean {
        if (filled < prefix.size) return false
        for (index in prefix.indices) if (bytes[index] != prefix[index]) return false
        return true
    }

    private fun indexOf(
        bytes: ByteArray,
        filled: Int,
        needle: ByteArray,
    ): Int {
        var start = 0
        while (start + needle.size <= filled) {
            var matched = true
            for (offset in needle.indices) {
                if (bytes[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
            start++
        }
        return -1
    }

    private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

    private const val EOCD_SIGNATURE = 0x06054B50L
    private const val EOCD_MIN_BYTES = 22
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
    private const val ZIP64_LOCATOR_BYTES = 20
    private const val CD_SIGNATURE = 0x02014B50L
    private const val CD_FIXED_BYTES = 46
    private const val LOCAL_SIGNATURE = 0x04034B50L
    private const val LOCAL_FIXED_BYTES = 30
    private const val OLE_HEADER_BYTES = 512
    private const val OLE_HEADER_DIFAT_ENTRIES = 109
    private const val OLE_DIRECTORY_ENTRY_BYTES = 128
}

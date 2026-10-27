package network.lapis.cloud.server.attachments

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * V1.9.232 -- a bounded, read-only ZIP central-directory reader for the content classification of OOXML and ODF mailing attachments
 * ([AttachmentSniffer]). `java.util.zip.ZipFile`/`ZipInputStream` are deliberately NOT used: they trust header values and iterate
 * over unvetted structure. ZIP64, multi-disk archives, more than [MAX_ENTRIES] entries and names over [MAX_NAME_BYTES] bytes make
 * [read] return `null` ("not a package we accept"). It never throws. The travel-expense sniffer keeps its own copy of this logic on
 * purpose (its behaviour is pinned byte for byte by existing tests).
 */
internal object AttachmentZip {
    internal const val MAX_ENTRIES = 2000
    internal const val MAX_NAME_BYTES = 512
    internal const val MAX_INFLATED_BYTES = 256 * 1024
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 1024 * 1024
    private const val MAX_INFLATE_RATIO = 100
    private const val EOCD_SEARCH_BYTES = 65_557
    private const val EOCD_SIGNATURE = 0x06054B50L
    private const val EOCD_MIN_BYTES = 22
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
    private const val ZIP64_LOCATOR_BYTES = 20
    private const val CD_SIGNATURE = 0x02014B50L
    private const val CD_FIXED_BYTES = 46
    private const val LOCAL_SIGNATURE = 0x04034B50L
    private const val LOCAL_FIXED_BYTES = 30

    class Entry(
        /** Lower-cased entry name. */
        val name: String,
        val flags: Int,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long,
    ) {
        val encrypted: Boolean get() = flags and 1 != 0
    }

    class Archive(
        val entries: List<Entry>,
        /** Offset of the central directory: no entry data may reach beyond it. */
        val centralDirectoryOffset: Long,
        val fileLength: Long,
    ) {
        fun find(name: String): List<Entry> = entries.filter { it.name == name }

        fun single(name: String): Entry? = find(name).singleOrNull()
    }

    /** `null` == anything about the archive is not plausible. */
    fun read(file: File): Archive? =
        try {
            RandomAccessFile(file, "r").use { raf -> readArchive(raf) }
        } catch (_: Exception) {
            null
        }

    private fun readArchive(raf: RandomAccessFile): Archive? {
        val length = raf.length()
        if (length < EOCD_MIN_BYTES) return null
        val tailSize = minOf(length, EOCD_SEARCH_BYTES.toLong()).toInt()
        val tailStart = length - tailSize
        val tail = ByteArray(tailSize)
        raf.seek(tailStart)
        raf.readFully(tail)
        var eocd = -1
        var i = tailSize - EOCD_MIN_BYTES
        while (i >= 0) {
            if (tail.u32(i) == EOCD_SIGNATURE && i + EOCD_MIN_BYTES + tail.u16(i + 20) == tailSize) {
                eocd = i
                break
            }
            i--
        }
        if (eocd < 0) return null
        if (eocd >= ZIP64_LOCATOR_BYTES && tail.u32(eocd - ZIP64_LOCATOR_BYTES) == ZIP64_LOCATOR_SIGNATURE) return null
        val diskNumber = tail.u16(eocd + 4)
        val cdDisk = tail.u16(eocd + 6)
        val entriesOnDisk = tail.u16(eocd + 8)
        val totalEntries = tail.u16(eocd + 10)
        val cdSize = tail.u32(eocd + 12)
        val cdOffset = tail.u32(eocd + 16)
        if (diskNumber != 0 || cdDisk != 0 || entriesOnDisk != totalEntries) return null
        if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null
        if (totalEntries == 0 || totalEntries > MAX_ENTRIES) return null
        if (cdSize > MAX_CENTRAL_DIRECTORY_BYTES || cdOffset + cdSize > tailStart + eocd) return null
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)

        var position = 0
        val entries = ArrayList<Entry>(totalEntries)
        repeat(totalEntries) {
            if (position + CD_FIXED_BYTES > cd.size || cd.u32(position) != CD_SIGNATURE) return null
            val flags = cd.u16(position + 8)
            val method = cd.u16(position + 10)
            val compressedSize = cd.u32(position + 20)
            val uncompressedSize = cd.u32(position + 24)
            val nameLength = cd.u16(position + 28)
            val extraLength = cd.u16(position + 30)
            val commentLength = cd.u16(position + 32)
            val localOffset = cd.u32(position + 42)
            if (nameLength > MAX_NAME_BYTES) return null
            val entryEnd = position + CD_FIXED_BYTES + nameLength + extraLength + commentLength
            if (entryEnd > cd.size) return null
            if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) return null
            if (localOffset >= cdOffset) return null
            val name = String(cd, position + CD_FIXED_BYTES, nameLength, Charsets.UTF_8).lowercase()
            entries +=
                Entry(
                    name = name,
                    flags = flags,
                    method = method,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localOffset = localOffset,
                )
            position = entryEnd
        }
        return Archive(entries = entries, centralDirectoryOffset = cdOffset, fileLength = length)
    }

    /**
     * Reads one entry's data through its LOCAL header, capped at [maxBytes] of REAL output (the inflated bytes are counted, never the
     * header value) with a 100:1 ratio limit. `null` == not plausible, encrypted, unsupported method, or the cap was hit.
     */
    fun readEntry(
        file: File,
        archive: Archive,
        entry: Entry,
        maxBytes: Int = MAX_INFLATED_BYTES,
    ): ByteArray? =
        try {
            RandomAccessFile(file, "r").use { raf -> readEntryData(raf = raf, archive = archive, entry = entry, maxBytes = maxBytes) }
        } catch (_: Exception) {
            null
        }

    private fun readEntryData(
        raf: RandomAccessFile,
        archive: Archive,
        entry: Entry,
        maxBytes: Int,
    ): ByteArray? {
        if (entry.encrypted) return null
        if (entry.localOffset + LOCAL_FIXED_BYTES > archive.fileLength) return null
        val local = ByteArray(LOCAL_FIXED_BYTES)
        raf.seek(entry.localOffset)
        raf.readFully(local)
        if (local.u32(0) != LOCAL_SIGNATURE) return null
        val dataStart = entry.localOffset + LOCAL_FIXED_BYTES + local.u16(26) + local.u16(28)
        val compressed = entry.compressedSize
        if (compressed > maxBytes || dataStart + compressed > archive.centralDirectoryOffset) return null
        if (entry.uncompressedSize > maxBytes) return null
        val input = ByteArray(compressed.toInt())
        raf.seek(dataStart)
        raf.readFully(input)
        val output =
            when (entry.method) {
                0 -> if (entry.uncompressedSize == compressed) input else return null
                8 -> inflateBounded(input = input, maxBytes = maxBytes) ?: return null
                else -> return null
            }
        if (compressed == 0L) {
            if (output.isNotEmpty()) return null
        } else if (output.size / compressed > MAX_INFLATE_RATIO) {
            return null
        }
        return output
    }

    private fun inflateBounded(
        input: ByteArray,
        maxBytes: Int,
    ): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) return null
            }
            return out.toByteArray()
        } catch (_: Exception) {
            return null
        } finally {
            inflater.end()
        }
    }

    private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
}

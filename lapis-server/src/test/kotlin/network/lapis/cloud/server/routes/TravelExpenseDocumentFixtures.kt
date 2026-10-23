package network.lapis.cloud.server.routes

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * V1.9.199 -- builds the test documents in memory (no binary fixtures are checked in): minimal but structurally valid PDF, XLSX (ZIP) and
 * XLS (OLE2 compound file) files plus targeted corruptions of them.
 */
internal object TravelExpenseDocumentFixtures {
    const val CONTENT_TYPES = "[Content_Types].xml"
    const val WORKBOOK = "xl/workbook.xml"
    const val PLAIN_CONTENT_TYPES =
        "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>"
    const val MACRO_CONTENT_TYPES =
        "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.ms-excel.sheet.macroEnabled.main+xml\"/></Types>"

    fun pdf(extra: String = ""): ByteArray =
        ("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n$extra\ntrailer << /Root 1 0 R >>\n%%EOF\n").toByteArray()

    class Part(
        val name: String,
        val bytes: ByteArray,
        val stored: Boolean = false,
    )

    fun zip(parts: List<Part>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { part ->
                val entry = ZipEntry(part.name)
                if (part.stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = part.bytes.size.toLong()
                    entry.compressedSize = part.bytes.size.toLong()
                    val crc =
                        java.util.zip
                            .CRC32()
                            .also { it.update(part.bytes) }
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(part.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun xlsx(
        contentTypes: String = PLAIN_CONTENT_TYPES,
        extra: List<Part> = emptyList(),
    ): ByteArray =
        zip(
            listOf(
                Part(name = CONTENT_TYPES, bytes = contentTypes.toByteArray()),
                Part(name = WORKBOOK, bytes = "<workbook/>".toByteArray()),
            ) +
                extra,
        )

    /** Replaces every occurrence of [from] by [to] (same length) -- used to forge a duplicate entry name ZipOutputStream refuses to write. */
    fun replaceBytes(
        zip: ByteArray,
        from: String,
        to: String,
    ): ByteArray {
        require(from.length == to.length)
        val copy = zip.copyOf()
        val needle = from.toByteArray()
        for (i in 0..copy.size - needle.size) {
            if (needle.indices.all { copy[i + it] == needle[it] }) to.toByteArray().forEachIndexed { k, b -> copy[i + k] = b }
        }
        return copy
    }

    /** Offset of the first central-directory header. */
    private fun firstCentralHeader(zip: ByteArray): Int {
        for (i in 0..zip.size - 4) {
            if (zip[i] == 0x50.toByte() &&
                zip[i + 1] == 0x4B.toByte() &&
                zip[i + 2] == 0x01.toByte() &&
                zip[i + 3] == 0x02.toByte()
            ) {
                return i
            }
        }
        error("no central directory")
    }

    private fun eocd(zip: ByteArray): Int {
        for (i in zip.size - 22 downTo 0) {
            if (zip[i] == 0x50.toByte() &&
                zip[i + 1] == 0x4B.toByte() &&
                zip[i + 2] == 0x05.toByte() &&
                zip[i + 3] == 0x06.toByte()
            ) {
                return i
            }
        }
        error("no eocd")
    }

    /** Sets the general-purpose "encrypted" bit of EVERY central-directory entry. */
    fun withEncryptionBit(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        var position = firstCentralHeader(copy)
        while (position + 46 <= copy.size &&
            copy[position] == 0x50.toByte() &&
            copy[position + 1] == 0x4B.toByte() &&
            copy[position + 2] == 0x01.toByte() &&
            copy[position + 3] == 0x02.toByte()
        ) {
            copy[position + 8] = (copy[position + 8].toInt() or 1).toByte()
            val n = copy.u16(position + 28) + copy.u16(position + 30) + copy.u16(position + 32)
            position += 46 + n
        }
        return copy
    }

    /** Saturates the entry counts of the EOCD -- the marker of a ZIP64 archive. */
    fun withZip64Marker(zip: ByteArray): ByteArray {
        val copy = zip.copyOf()
        val e = eocd(copy)
        copy[e + 8] = 0xFF.toByte()
        copy[e + 9] = 0xFF.toByte()
        copy[e + 10] = 0xFF.toByte()
        copy[e + 11] = 0xFF.toByte()
        return copy
    }

    /** Overwrites the declared uncompressed size (central directory) of the first entry. */
    fun withDeclaredUncompressedSize(
        zip: ByteArray,
        size: Int,
    ): ByteArray {
        val copy = zip.copyOf()
        val position = firstCentralHeader(copy)
        ByteBuffer.wrap(copy, position + 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(size)
        return copy
    }

    private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    /** One directory entry of the OLE2 builder: [type] 1 storage, 2 stream. */
    class OleEntry(
        val name: String,
        val type: Int = 2,
    )

    /**
     * A minimal OLE2 compound file with 512-byte sectors: header, ONE FAT sector, ONE directory sector (root + up to three entries).
     * [fatNextOfDirectory] is the FAT value of the directory sector (ENDOFCHAIN normally; its own index builds a cycle).
     */
    fun ole2(
        entries: List<OleEntry>,
        sectorShift: Int = 9,
        fatNextOfDirectory: Long = 0xFFFFFFFEL,
    ): ByteArray {
        require(entries.size <= 3)
        val sectorSize = 512
        val file = ByteArray(sectorSize * 3)
        val header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).forEachIndexed {
            i,
            b,
            ->
            file[i] =
                b
        }
        header.putShort(24, 0x3E)
        header.putShort(26, 3)
        header.putShort(28, 0xFFFE.toShort())
        header.putShort(30, sectorShift.toShort())
        header.putShort(32, 6)
        header.putInt(44, 1)
        header.putInt(48, 1)
        header.putInt(56, 4096)
        header.putInt(60, -2)
        header.putInt(68, -2)
        header.putInt(76, 0)
        for (i in 1 until 109) header.putInt(76 + i * 4, -1)
        // FAT sector (file sector 0 == bytes 512..1023)
        header.putInt(sectorSize, -3)
        header.putInt(sectorSize + 4, fatNextOfDirectory.toInt())
        for (i in 2 until sectorSize / 4) header.putInt(sectorSize + i * 4, -1)
        // directory sector (file sector 1 == bytes 1024..1535)
        val all = listOf(OleEntry(name = "Root Entry", type = 5)) + entries
        all.forEachIndexed { index, entry ->
            val base = sectorSize * 2 + index * 128
            val nameBytes = entry.name.toByteArray(Charsets.UTF_16LE)
            System.arraycopy(nameBytes, 0, file, base, nameBytes.size)
            header.putShort(base + 64, (nameBytes.size + 2).toShort())
            file[base + 66] = entry.type.toByte()
        }
        return file
    }
}

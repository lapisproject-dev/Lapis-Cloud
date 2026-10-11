package network.lapis.cloud.server.articles

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Hand-rolled ZIP writer for tests of [ArticleImportZipReader]: unlike `ZipOutputStream` it lets a test lie in every header field (declared
 * sizes, flags, external attributes, local name, entry count) so the malicious shapes the reader must refuse can be built byte by byte.
 */
internal class TestZipEntry(
    val name: ByteArray,
    val data: ByteArray,
    val method: Int = 8,
    val flags: Int = 0,
    val externalAttributes: Long = 0L,
    val declaredUncompressed: Long? = null,
    val declaredCompressed: Long? = null,
    val declaredCrc: Long? = null,
    val localName: ByteArray? = null,
    val rawCompressed: ByteArray? = null,
) {
    constructor(name: String, data: ByteArray, method: Int = 8, externalAttributes: Long = 0L) :
        this(name = name.toByteArray(Charsets.UTF_8), data = data, method = method, externalAttributes = externalAttributes)
}

internal object TestZipBuilder {
    fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(v: Long) {
        for (i in 0 until 4) write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun build(
        entries: List<TestZipEntry>,
        comment: ByteArray = ByteArray(0),
        eocdTotal: Int? = null,
        eocdDisk: Int = 0,
        zip64Marker: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        class Placed(
            val entry: TestZipEntry,
            val offset: Int,
            val crc: Long,
            val compressedSize: Long,
            val uncompressedSize: Long,
        )
        val placed = mutableListOf<Placed>()
        for (e in entries) {
            val compressed = e.rawCompressed ?: if (e.method == 0) e.data else deflateRaw(e.data)
            val crc = CRC32().apply { update(e.data) }.value
            val offset = out.size()
            val localName = e.localName ?: e.name
            out.le32(0x04034b50L)
            out.le16(20)
            out.le16(e.flags)
            out.le16(e.method)
            out.le16(0)
            out.le16(0x21)
            out.le32(e.declaredCrc ?: crc)
            out.le32(compressed.size.toLong())
            out.le32(e.data.size.toLong())
            out.le16(localName.size)
            out.le16(0)
            out.write(localName)
            out.write(compressed)
            placed += Placed(e, offset, crc, compressed.size.toLong(), e.data.size.toLong())
        }
        val cdStart = out.size()
        for (p in placed) {
            val e = p.entry
            out.le32(0x02014b50L)
            out.le16(0x031E) // made by Unix
            out.le16(20)
            out.le16(e.flags)
            out.le16(e.method)
            out.le16(0)
            out.le16(0x21)
            out.le32(e.declaredCrc ?: p.crc)
            out.le32(e.declaredCompressed ?: p.compressedSize)
            out.le32(e.declaredUncompressed ?: p.uncompressedSize)
            out.le16(e.name.size)
            out.le16(0)
            out.le16(0)
            out.le16(0)
            out.le16(0)
            out.le32(e.externalAttributes)
            out.le32(p.offset.toLong())
            out.write(e.name)
        }
        val cdSize = out.size() - cdStart
        out.le32(0x06054b50L)
        out.le16(eocdDisk)
        out.le16(0)
        val total = if (zip64Marker) 0xFFFF else eocdTotal ?: placed.size
        out.le16(total)
        out.le16(total)
        out.le32(cdSize.toLong())
        out.le32(cdStart.toLong())
        out.le16(comment.size)
        out.write(comment)
        return out.toByteArray()
    }

    fun simple(vararg entries: Pair<String, ByteArray>): ByteArray =
        build(
            entries =
                entries.map {
                    TestZipEntry(name = it.first, data = it.second)
                },
        )
}

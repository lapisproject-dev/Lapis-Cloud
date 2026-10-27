package network.lapis.cloud.server.attachments

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** V1.9.232 -- minimal REAL file fixtures for the mailing-attachment tests (built in memory, nothing is committed as a binary). */
internal object AttachmentTestFiles {
    val PDF: ByteArray = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
    val PNG: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
    val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46)
    val GIF: ByteArray = "GIF89a\u0001\u0000\u0001\u0000".toByteArray(Charsets.ISO_8859_1)

    /** `RIFF<len>WEBPVP8 ` with a RIFF length that fits the file exactly. */
    fun webp(payload: Int = 16): ByteArray {
        val body = ByteArray(payload)
        val riffLength = 4 + body.size
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(
            byteArrayOf(
                (riffLength and 0xFF).toByte(),
                ((riffLength shr 8) and 0xFF).toByte(),
                ((riffLength shr 16) and 0xFF).toByte(),
                ((riffLength shr 24) and 0xFF).toByte(),
            ),
        )
        out.write("WEBP".toByteArray())
        out.write(body)
        return out.toByteArray()
    }

    fun zip(
        entries: List<Pair<String, ByteArray>>,
        stored: Set<String> = emptySet(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().also { it.update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun contentTypes(extra: String = ""): ByteArray =
        (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>$extra</Types>"
        ).toByteArray()

    fun ooxml(
        mainPart: String,
        extraEntries: List<Pair<String, ByteArray>> = emptyList(),
        contentTypesExtra: String = "",
    ): ByteArray =
        zip(
            entries = listOf("[Content_Types].xml" to contentTypes(contentTypesExtra), mainPart to "<root/>".toByteArray()) + extraEntries,
        )

    fun docx(extraEntries: List<Pair<String, ByteArray>> = emptyList()) = ooxml(mainPart = "word/document.xml", extraEntries = extraEntries)

    fun xlsx(extraEntries: List<Pair<String, ByteArray>> = emptyList()) = ooxml(mainPart = "xl/workbook.xml", extraEntries = extraEntries)

    fun pptx(extraEntries: List<Pair<String, ByteArray>> = emptyList()) =
        ooxml(mainPart = "ppt/presentation.xml", extraEntries = extraEntries)

    fun odf(
        mime: String,
        extraEntries: List<Pair<String, ByteArray>> = emptyList(),
        manifest: String = manifest(),
        mimetypeStored: Boolean = true,
    ): ByteArray =
        zip(
            entries =
                listOf(
                    "mimetype" to mime.toByteArray(),
                    "content.xml" to "<office:document-content/>".toByteArray(),
                    "META-INF/manifest.xml" to manifest.toByteArray(),
                ) + extraEntries,
            stored = if (mimetypeStored) setOf("mimetype") else emptySet(),
        )

    const val MANIFEST_NS = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"

    /** A namespace-correct ODF manifest with the given file-entry elements (already serialized). */
    fun manifest(entries: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"$MANIFEST_NS\" manifest:version=\"1.3\">" +
            "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>" +
            entries + "</manifest:manifest>"

    const val ODT = "application/vnd.oasis.opendocument.text"
    const val ODS = "application/vnd.oasis.opendocument.spreadsheet"
    const val ODP = "application/vnd.oasis.opendocument.presentation"
}

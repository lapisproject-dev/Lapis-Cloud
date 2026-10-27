package network.lapis.cloud.server.attachments

import network.lapis.cloud.shared.domain.AttachmentRules
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Verdict of [AttachmentSniffer.classify]. The wire mapping to error codes lives in the route. */
internal sealed interface AttachmentVerdict {
    /** [mime] is the canonical server-chosen type; [charset] is set for txt/csv only. */
    data class Accepted(
        val mime: String,
        val charset: String? = null,
    ) : AttachmentVerdict

    /** The extension is not on the whitelist (exe, zip, html, svg, js, none, ...). */
    data object TypeNotAllowed : AttachmentVerdict

    /** The extension is allowed but the content belongs to another type, to none, or is encrypted/implausible. */
    data object ContentMismatch : AttachmentVerdict

    /** An Office/ODF package with macros, ActiveX or embedded objects. */
    data object MacroOrEmbedded : AttachmentVerdict
}

/**
 * V1.9.232 -- content classification of an uploaded mailing attachment. Pure (no Ktor), runs on the COMPLETE stored file after streaming
 * and never throws: any read error, out-of-range value or oddity is [AttachmentVerdict.ContentMismatch] (fail closed). The
 * client-declared `Content-Type` is never read; the MIME type that ends up in the mail is the canonical one of the extension.
 *
 * Per type:
 * - **PDF**: `%PDF-`. An ENCRYPTED PDF is deliberately accepted (mailing a protected PDF is legitimate); macros/JavaScript inside a PDF
 *   are NOT detected (see the CHANGELOG).
 * - **PNG/JPEG/GIF/WebP**: magic bytes; WebP additionally needs `RIFF????WEBP` with a RIFF length that fits the file (the 8-byte upload
 *   sniff buffer is too short for it, so this class reads the first 16 bytes from the file itself). Image polyglots are not detected.
 * - **docx/xlsx/pptx**: a bounded ZIP parse ([AttachmentZip]); the main part of the extension's package must exist; `vbaProject.bin`,
 *   `activeX/`, `embeddings/`, `macrosheets/`, `*.bin` main parts and a `macroEnabled` content type are [AttachmentVerdict.MacroOrEmbedded]
 *   (strict: a docx with an embedded Excel chart is refused too); encrypted entries are a type error.
 * - **odt/ods/odp**: the entry with the lowest local offset must be `mimetype`, STORED, with content exactly matching the extension;
 *   `content.xml` and `META-INF/manifest.xml` must exist; `Basic/`/`Scripts/` entries are macros; `encryption-data` in the manifest is a
 *   type error. (Encryption is only detected through the manifest.)
 * - **txt/csv**: no NUL and no C0 control other than TAB/LF/CR/FF anywhere; no known binary magic after optional BOM/whitespace; no HTML/SVG/XML
 *   markup in the first 512 bytes (a stored file must never be sniffable as active content); valid UTF-8 gives `UTF-8`, otherwise
 *   `windows-1252` unless one of its undefined bytes (0x81, 0x8D, 0x8F, 0x90, 0x9D) occurs.
 *
 * Nothing about the file is logged.
 */
internal object AttachmentSniffer {
    private const val HEAD_BYTES = 16
    private const val TEXT_MARKUP_WINDOW = 512
    private const val MAX_MIMETYPE_BYTES = 128
    private const val OPC_RELATIONSHIPS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val ODF_MANIFEST_NS = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"
    private val HYPERLINK_RELATIONSHIP_TYPES =
        setOf(
            "http://schemas.openxmlformats.org/officedocument/2006/relationships/hyperlink",
            "http://purl.oclc.org/ooxml/officedocument/relationships/hyperlink",
        )

    private val PDF = "%PDF-".toByteArray(Charsets.ISO_8859_1)
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF87 = "GIF87a".toByteArray(Charsets.ISO_8859_1)
    private val GIF89 = "GIF89a".toByteArray(Charsets.ISO_8859_1)
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val BINARY_MAGICS =
        listOf(
            "MZ".toByteArray(Charsets.ISO_8859_1),
            byteArrayOf(0x7F, 0x45, 0x4C, 0x46),
            ZIP_LOCAL,
            PDF.copyOf(4),
            "#!".toByteArray(Charsets.ISO_8859_1),
        )
    private val MARKUP_TOKENS =
        listOf(
            "<!doctype",
            "<html",
            "<head",
            "<body",
            "<script",
            "<iframe",
            "<svg",
            "<?xml",
            "<style",
            "<title",
            "<table",
            "<div",
            "<img",
            "<object",
            "<embed",
            "<form",
            "<meta",
        )
    private val UNDEFINED_1252 = intArrayOf(0x81, 0x8D, 0x8F, 0x90, 0x9D)

    private val OOXML_MAIN_PART =
        mapOf(
            "docx" to "word/document.xml",
            "xlsx" to "xl/workbook.xml",
            "pptx" to "ppt/presentation.xml",
        )
    private val OOXML_BINARY_MAIN_PARTS = setOf("word/document.bin", "xl/workbook.bin", "ppt/presentation.bin")
    private val ODF_EXTENSIONS = setOf("odt", "ods", "odp")

    fun classify(
        file: File,
        declaredExtension: String,
    ): AttachmentVerdict {
        val extension = declaredExtension.lowercase()
        val mime = AttachmentRules.ALLOWED[extension] ?: return AttachmentVerdict.TypeNotAllowed
        return try {
            if (file.length() <= 0L) return AttachmentVerdict.ContentMismatch
            val head = readHead(file)
            when (extension) {
                "pdf" -> expect(matches = startsWith(bytes = head, prefix = PDF), mime = mime)
                "png" -> expect(matches = startsWith(bytes = head, prefix = PNG), mime = mime)
                "jpg", "jpeg" -> expect(matches = startsWith(bytes = head, prefix = JPEG), mime = mime)
                "gif" -> expect(matches = startsWith(bytes = head, prefix = GIF87) || startsWith(bytes = head, prefix = GIF89), mime = mime)
                "webp" -> expect(matches = isWebp(head = head, fileLength = file.length()), mime = mime)
                "docx", "xlsx", "pptx" -> classifyOoxml(file = file, head = head, extension = extension, mime = mime)
                in ODF_EXTENSIONS -> classifyOdf(file = file, head = head, mime = mime)
                "txt", "csv" -> classifyText(file = file, mime = mime)
                else -> AttachmentVerdict.TypeNotAllowed
            }
        } catch (_: Exception) {
            AttachmentVerdict.ContentMismatch
        }
    }

    private fun expect(
        matches: Boolean,
        mime: String,
    ): AttachmentVerdict = if (matches) AttachmentVerdict.Accepted(mime = mime) else AttachmentVerdict.ContentMismatch

    private fun readHead(file: File): ByteArray {
        val buffer = ByteArray(HEAD_BYTES)
        val read =
            file.inputStream().use { input ->
                var total = 0
                while (total < HEAD_BYTES) {
                    val n = input.read(buffer, total, HEAD_BYTES - total)
                    if (n == -1) break
                    total += n
                }
                total
            }
        return buffer.copyOf(read)
    }

    private fun isWebp(
        head: ByteArray,
        fileLength: Long,
    ): Boolean {
        if (head.size < 12) return false
        if (!startsWith(bytes = head, prefix = "RIFF".toByteArray(Charsets.ISO_8859_1))) return false
        if (String(head, 8, 4, Charsets.ISO_8859_1) != "WEBP") return false
        val riffLength =
            (head[4].toLong() and 0xFF) or
                ((head[5].toLong() and 0xFF) shl 8) or
                ((head[6].toLong() and 0xFF) shl 16) or
                ((head[7].toLong() and 0xFF) shl 24)
        return riffLength >= 4 && riffLength + 8 <= fileLength
    }

    // ---- OOXML ------------------------------------------------------------------------------------------------------

    private fun classifyOoxml(
        file: File,
        head: ByteArray,
        extension: String,
        mime: String,
    ): AttachmentVerdict {
        if (!startsWith(bytes = head, prefix = ZIP_LOCAL)) return AttachmentVerdict.ContentMismatch
        val archive = AttachmentZip.read(file) ?: return AttachmentVerdict.ContentMismatch
        if (archive.entries.any { it.encrypted }) return AttachmentVerdict.ContentMismatch
        if (archive.entries.any { isMacroOrEmbeddedPart(it.name) }) return AttachmentVerdict.MacroOrEmbedded
        val required = OOXML_MAIN_PART.getValue(extension)
        if (archive.find(required).size != 1) return AttachmentVerdict.ContentMismatch
        val types = archive.single("[content_types].xml") ?: return AttachmentVerdict.ContentMismatch
        val typesDoc =
            AttachmentZip.readEntry(file = file, archive = archive, entry = types)?.let(::parseXml)
                ?: return AttachmentVerdict.ContentMismatch
        // Attribute values are compared after XML decoding (character references, entities), never on the raw text.
        if (allAttributeValues(typesDoc).any { it.contains("macroenabled") || it.contains("vbaproject") }) {
            return AttachmentVerdict.MacroOrEmbedded
        }
        // Relationship parts: external targets (remote templates, external OLE objects, frames) re-introduce active content on open.
        for (rels in archive.entries.filter { it.name.endsWith(".rels") }) {
            val bytes =
                AttachmentZip.readEntry(file = file, archive = archive, entry = rels)
                    ?: return AttachmentVerdict.ContentMismatch
            val relsDoc = parseXml(bytes) ?: return AttachmentVerdict.ContentMismatch
            if (hasForbiddenExternalRelationship(relsDoc)) return AttachmentVerdict.MacroOrEmbedded
        }
        return AttachmentVerdict.Accepted(mime = mime)
    }

    /**
     * True if any Relationship (OPC namespace, any prefix) has TargetMode="External" and is not a plain hyperlink (a missing Type
     * counts as forbidden). Works on the parsed DOM, so character references and attribute order cannot hide anything.
     */
    private fun hasForbiddenExternalRelationship(doc: Document): Boolean {
        val nodes = doc.getElementsByTagNameNS(OPC_RELATIONSHIPS_NS, "Relationship")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            if (!element.getAttribute("TargetMode").trim().equals("External", ignoreCase = true)) continue
            if (element.getAttribute("Type").trim().lowercase() !in HYPERLINK_RELATIONSHIP_TYPES) return true
        }
        return false
    }

    /** Hardened, namespace-aware DOM parse; DOCTYPE/external entities are disabled. Null on any parse error (fail closed). */
    private fun parseXml(bytes: ByteArray): Document? =
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(
                object : ErrorHandler {
                    override fun warning(exception: SAXParseException) = Unit

                    override fun error(exception: SAXParseException): Unit = throw exception

                    override fun fatalError(exception: SAXParseException): Unit = throw exception
                },
            )
            bytes.inputStream().use { builder.parse(it) }
        } catch (_: Exception) {
            null
        }

    /** All attribute values (lower-cased, already XML-decoded) of every element in the document. */
    private fun allAttributeValues(doc: Document): List<String> {
        val values = mutableListOf<String>()
        val all = doc.getElementsByTagName("*")
        for (index in 0 until all.length) {
            val attributes = all.item(index).attributes
            for (attr in 0 until attributes.length) values += attributes.item(attr).nodeValue.lowercase()
        }
        return values
    }

    private fun isMacroOrEmbeddedPart(name: String): Boolean =
        name.endsWith("vbaproject.bin") ||
            name.contains("/activex/") ||
            name.startsWith("activex/") ||
            name.contains("/embeddings/") ||
            name.startsWith("embeddings/") ||
            name.contains("/macrosheets/") ||
            name in OOXML_BINARY_MAIN_PARTS

    // ---- ODF --------------------------------------------------------------------------------------------------------

    private fun classifyOdf(
        file: File,
        head: ByteArray,
        mime: String,
    ): AttachmentVerdict {
        if (!startsWith(bytes = head, prefix = ZIP_LOCAL)) return AttachmentVerdict.ContentMismatch
        val archive = AttachmentZip.read(file) ?: return AttachmentVerdict.ContentMismatch
        if (archive.entries.any { it.encrypted }) return AttachmentVerdict.ContentMismatch
        val first = archive.entries.minByOrNull { it.localOffset } ?: return AttachmentVerdict.ContentMismatch
        if (first.name != "mimetype" || first.method != 0 || first.localOffset != 0L) return AttachmentVerdict.ContentMismatch
        if (archive.find("mimetype").size != 1) return AttachmentVerdict.ContentMismatch
        val declared = AttachmentZip.readEntry(file = file, archive = archive, entry = first, maxBytes = MAX_MIMETYPE_BYTES)
        if (declared == null || String(declared, Charsets.ISO_8859_1) != mime) return AttachmentVerdict.ContentMismatch
        if (
            archive.entries.any {
                it.name.startsWith("basic/") ||
                    it.name.startsWith("scripts/") ||
                    it.name.startsWith("object ") ||
                    it.name.startsWith("objectreplacements/")
            }
        ) {
            return AttachmentVerdict.MacroOrEmbedded
        }
        if (archive.find("content.xml").size != 1) return AttachmentVerdict.ContentMismatch
        val manifestEntry = archive.single("meta-inf/manifest.xml") ?: return AttachmentVerdict.ContentMismatch
        val manifest =
            AttachmentZip.readEntry(file = file, archive = archive, entry = manifestEntry)
                ?: return AttachmentVerdict.ContentMismatch
        val manifestDoc = parseXml(manifest) ?: return AttachmentVerdict.ContentMismatch
        if (manifestDoc.getElementsByTagNameNS("*", "encryption-data").length > 0) return AttachmentVerdict.ContentMismatch
        if (hasEmbeddedObject(manifestDoc)) return AttachmentVerdict.MacroOrEmbedded
        return AttachmentVerdict.Accepted(mime = mime)
    }

    /**
     * A manifest file-entry that declares an OLE object, an "Object N" part, or a directory of a sub-document (OASIS/StarOffice document
     * media types). Plain directory entries such as `Configurations2/` (written by LibreOffice into nearly every file) or `Pictures/`
     * are fine. Attribute values are compared after XML decoding.
     */
    private fun hasEmbeddedObject(doc: Document): Boolean {
        val entries = doc.getElementsByTagNameNS(ODF_MANIFEST_NS, "file-entry")
        for (index in 0 until entries.length) {
            val entry = entries.item(index) as Element
            val path = entry.getAttributeNS(ODF_MANIFEST_NS, "full-path").lowercase()
            val mediaType = entry.getAttributeNS(ODF_MANIFEST_NS, "media-type").lowercase()
            if (mediaType.contains("oleobject") || path.startsWith("object ")) return true
            if (path != "/" && path.endsWith("/") && isSubdocumentMediaType(mediaType)) return true
        }
        return false
    }

    private fun isSubdocumentMediaType(mediaType: String): Boolean =
        mediaType.startsWith("application/vnd.oasis.opendocument.") ||
            (mediaType.startsWith("application/vnd.sun.xml.") && mediaType != "application/vnd.sun.xml.ui.configuration")

    // ---- text -------------------------------------------------------------------------------------------------------

    private fun classifyText(
        file: File,
        mime: String,
    ): AttachmentVerdict {
        val decoder: CharsetDecoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(16 * 1024)
        val pending = ByteBuffer.allocate(16 * 1024 + 8)
        val buffer = ByteArray(16 * 1024)
        var utf8Valid = true
        var undefined1252 = false
        var position = 0L
        val window = ByteArray(TEXT_MARKUP_WINDOW)
        var windowFilled = 0
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                for (i in 0 until read) {
                    val b = buffer[i].toInt() and 0xFF
                    if (b == 0 || (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0C && b != 0x0D)) {
                        return AttachmentVerdict.ContentMismatch
                    }
                    if (b in UNDEFINED_1252) undefined1252 = true
                }
                if (windowFilled < TEXT_MARKUP_WINDOW) {
                    val take = minOf(TEXT_MARKUP_WINDOW - windowFilled, read)
                    System.arraycopy(buffer, 0, window, windowFilled, take)
                    windowFilled += take
                }
                position += read
                if (utf8Valid) {
                    pending.put(buffer, 0, read)
                    pending.flip()
                    while (true) {
                        out.clear()
                        val result = decoder.decode(pending, out, false)
                        if (result.isError) {
                            utf8Valid = false
                            break
                        }
                        if (result.isUnderflow) break
                    }
                    pending.compact()
                }
            }
        }
        if (position <= 0L) return AttachmentVerdict.ContentMismatch
        if (utf8Valid) {
            pending.flip()
            out.clear()
            val tail = decoder.decode(pending, out, true)
            if (tail.isError) utf8Valid = false
            if (utf8Valid) {
                out.clear()
                if (decoder.flush(out).isError) utf8Valid = false
            }
        }
        if (looksLikeBinaryOrMarkup(window = window, filled = windowFilled)) return AttachmentVerdict.ContentMismatch
        return when {
            utf8Valid -> AttachmentVerdict.Accepted(mime = mime, charset = "UTF-8")
            !undefined1252 -> AttachmentVerdict.Accepted(mime = mime, charset = "windows-1252")
            else -> AttachmentVerdict.ContentMismatch
        }
    }

    private fun looksLikeBinaryOrMarkup(
        window: ByteArray,
        filled: Int,
    ): Boolean {
        var start = 0
        if (filled >= 3 && window[0] == 0xEF.toByte() && window[1] == 0xBB.toByte() && window[2] == 0xBF.toByte()) start = 3
        while (start < filled && (window[start].toInt() and 0xFF) <= 0x20) start++
        val trimmed = window.copyOfRange(start, filled)
        if (BINARY_MAGICS.any { startsWith(bytes = trimmed, prefix = it) }) return true
        val lowered = String(window, 0, filled, Charsets.ISO_8859_1).lowercase()
        return MARKUP_TOKENS.any { lowered.contains(it) }
    }

    private fun startsWith(
        bytes: ByteArray,
        prefix: ByteArray,
    ): Boolean {
        if (bytes.size < prefix.size) return false
        for (index in prefix.indices) if (bytes[index] != prefix[index]) return false
        return true
    }
}

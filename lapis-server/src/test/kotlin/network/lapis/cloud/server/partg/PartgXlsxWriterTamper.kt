package network.lapis.cloud.server.partg

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Test helper: rewrites one cell of a template as a text cell, so a test can plant a bad value in an otherwise valid file. */
internal object PartgXlsxWriterTamper {
    fun replaceCell(
        xlsx: ByteArray,
        ref: String,
        text: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(xlsx.inputStream()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    var content = input.readBytes().toString(Charsets.UTF_8)
                    if (entry.name == "xl/worksheets/sheet1.xml") {
                        val replacement = """<c r="$ref" t="inlineStr"><is><t>$text</t></is></c>"""
                        content = content.replace(Regex("""<c r="$ref"[^>]*?(/>|>.*?</c>)"""), replacement)
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}

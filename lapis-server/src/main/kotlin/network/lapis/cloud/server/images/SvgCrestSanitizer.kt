package network.lapis.cloud.server.images

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private val logger = KotlinLogging.logger {}

internal enum class SvgRejection {
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    UNDECODABLE,
    DIMENSIONS_TOO_LARGE,
    SCRIPT,
    EXTERNAL_REFERENCE,
    TEXT_NOT_SUPPORTED,
    NO_DIMENSIONS,
    TOO_COMPLEX,
    UNSUPPORTED_CONTENT,
}

internal data class SvgViewBox(
    val minX: Double,
    val minY: Double,
    val width: Double,
    val height: Double,
)

internal sealed interface SvgSanitizeResult {
    class Accepted(
        val bytes: ByteArray,
        val viewBox: SvgViewBox,
    ) : SvgSanitizeResult

    data class Rejected(
        val reason: SvgRejection,
    ) : SvgSanitizeResult
}

/**
 * Welle V1.9.21 -- SVG crest sanitizer. Policy: ALLOWLIST, reject over repair, and the stored bytes are
 * a fresh serialization of what was understood, never the upload. No Ktor, database or DTO dependency.
 * No new library: the JDK's `java.xml` StAX parser only (DTDs, entities, PIs are refused, not resolved).
 *
 * Pipeline: size -> BOM/encoding -> strict UTF-8 -> control characters -> XML declaration -> DOCTYPE
 * pre-check -> [SvgCrestReader] -> [SvgCrestReferences] -> root/viewBox -> [SvgCrestWriter] -> output size.
 */
internal object SvgCrestSanitizer {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val XML_DECLARATION = Regex("^<\\?xml\\s[^?]{0,512}\\?>")
    private val ENCODING = Regex("encoding\\s*=\\s*[\"']([^\"']{1,64})[\"']")
    private val VERSION = Regex("version\\s*=\\s*[\"']([^\"']{1,16})[\"']")
    private val DTD_MARKUP = Regex("<!\\s*(?:doctype|entity|element|attlist|notation)", RegexOption.IGNORE_CASE)

    /** Cheap routing check without parsing: optional UTF-8 BOM, ASCII whitespace, then `<`. UTF-16/32 BOMs are not candidates. */
    fun isCandidate(bytes: ByteArray): Boolean {
        var index = if (bytes.startsWith(UTF8_BOM)) UTF8_BOM.size else 0
        while (index < bytes.size && bytes[index].isAsciiWhitespace()) index++
        return index < bytes.size && bytes[index] == '<'.code.toByte()
    }

    /** Never throws; every internal failure becomes `Rejected(UNDECODABLE)` and is logged without content. */
    fun sanitize(bytes: ByteArray): SvgSanitizeResult =
        try {
            sanitizeOrThrow(bytes)
        } catch (e: SvgRejectedException) {
            SvgSanitizeResult.Rejected(e.reason)
        } catch (e: Exception) {
            logger.warn { "SVG crest sanitizer internal error: ${e.javaClass.name}" }
            SvgSanitizeResult.Rejected(SvgRejection.UNDECODABLE)
        } catch (e: StackOverflowError) {
            logger.warn { "SVG crest sanitizer stack overflow" }
            SvgSanitizeResult.Rejected(SvgRejection.UNDECODABLE)
        }

    private fun sanitizeOrThrow(bytes: ByteArray): SvgSanitizeResult {
        if (bytes.size > SvgCrestPolicy.MAX_INPUT_BYTES) svgReject(SvgRejection.FILE_TOO_LARGE)
        if (hasUtf16Or32Bom(bytes)) svgReject(SvgRejection.UNSUPPORTED_FORMAT)
        val payload = if (bytes.startsWith(UTF8_BOM)) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
        val text = decodeStrict(payload)
        checkCharacters(text)
        checkDeclaration(text)
        if (DTD_MARKUP.containsMatchIn(text)) svgReject(SvgRejection.UNSUPPORTED_CONTENT)

        val root = SvgCrestReader.read(text)
        SvgCrestReferences.validate(root)
        val viewBoxText = root.attributes["viewBox"] ?: svgReject(SvgRejection.NO_DIMENSIONS)
        val viewBox = SvgCrestAttributes.parseViewBox(viewBoxText)
        val output = SvgCrestWriter.write(root = root, viewBox = viewBox)
        if (output.size > SvgCrestPolicy.MAX_OUTPUT_BYTES) svgReject(SvgRejection.TOO_COMPLEX)
        return SvgSanitizeResult.Accepted(bytes = output, viewBox = viewBox)
    }

    private fun decodeStrict(payload: ByteArray): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        } catch (_: CharacterCodingException) {
            svgReject(SvgRejection.UNSUPPORTED_FORMAT)
        }

    /** NUL and C0 controls (except tab, LF, CR), U+FFFE/U+FFFF and lone surrogates are never valid SVG text. */
    private fun checkCharacters(text: String) {
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> svgReject(SvgRejection.UNSUPPORTED_FORMAT)
                c == '\uFFFE' || c == '\uFFFF' -> svgReject(SvgRejection.UNSUPPORTED_FORMAT)
                Character.isHighSurrogate(c) ->
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        index++
                    } else {
                        svgReject(SvgRejection.UNSUPPORTED_FORMAT)
                    }
                Character.isLowSurrogate(c) -> svgReject(SvgRejection.UNSUPPORTED_FORMAT)
            }
            index++
        }
    }

    private fun checkDeclaration(text: String) {
        val declaration = XML_DECLARATION.find(text)?.value ?: return
        ENCODING.find(declaration)?.let {
            if (!it.groupValues[1].equals("utf-8", ignoreCase = true)) svgReject(SvgRejection.UNSUPPORTED_FORMAT)
        }
        VERSION.find(declaration)?.let { if (it.groupValues[1] != "1.0") svgReject(SvgRejection.UNSUPPORTED_FORMAT) }
    }

    private fun hasUtf16Or32Bom(bytes: ByteArray): Boolean {
        if (bytes.size >= 2) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            if ((a == 0xFE && b == 0xFF) || (a == 0xFF && b == 0xFE)) return true
        }
        return bytes.size >= 4 &&
            bytes[0].toInt() == 0 &&
            bytes[1].toInt() == 0 &&
            (bytes[2].toInt() and 0xFF) == 0xFE &&
            (bytes[3].toInt() and 0xFF) == 0xFF
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun Byte.isAsciiWhitespace(): Boolean =
        this == ' '.code.toByte() || this == '\t'.code.toByte() || this == '\n'.code.toByte() || this == '\r'.code.toByte()
}

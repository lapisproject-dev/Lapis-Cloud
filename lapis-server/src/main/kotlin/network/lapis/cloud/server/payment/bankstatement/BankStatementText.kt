package network.lapis.cloud.server.payment.bankstatement

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Result of [BankStatementText.decode] -- the decoded text plus which charset actually worked, purely for diagnostics (never persisted, never shown to anyone but a developer reading a log line at DEBUG). */
internal data class DecodedText(
    val text: String,
    val charsetName: String,
)

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". German bank CSV exports are not reliably UTF-8 -- older
 * online-banking exports (and some institutes' current ones) still use Windows-1252 ("ANSI"),
 * which silently mangles every umlaut/ß if decoded as UTF-8 with a lossy replacement-character
 * fallback instead. [decode] therefore tries STRICT UTF-8 first (a leading BOM is stripped, never
 * left in the first header cell), and only falls back to Windows-1252 if UTF-8 decoding actually
 * fails -- never a REPLACE-mode decode that would silently corrupt umlauts either way.
 */
internal object BankStatementText {
    private const val UTF8_BOM = '﻿'
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun decode(bytes: ByteArray): DecodedText {
        val strictUtf8Decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val decoded = strictUtf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
            DecodedText(text = stripBom(decoded), charsetName = "UTF-8")
        } catch (e: CharacterCodingException) {
            // windows-1252 has no unmappable byte sequences (every byte 0x00-0xFF decodes to
            // something) -- this fallback never itself throws.
            DecodedText(text = stripBom(String(bytes, WINDOWS_1252)), charsetName = "windows-1252")
        }
    }

    private fun stripBom(text: String): String = if (text.isNotEmpty() && text[0] == UTF8_BOM) text.substring(1) else text
}

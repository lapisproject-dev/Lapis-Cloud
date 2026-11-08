package network.lapis.cloud.server.partg

import kotlinx.serialization.json.Json
import network.lapis.cloud.server.partg.report.PartgReportDocument
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import network.lapis.cloud.shared.rpc.PartyReportVersionTooLargeException
import java.security.MessageDigest

/** Stable row keys of a kept version (the unit rows use the unit key itself). */
internal object PartgVersionKeys {
    const val SUM_ROW_KEY = "SUM"
    const val CONSOLIDATED_ROW_KEY = "CONSOLIDATED"
}

/**
 * V1.9.291 (R6) -- the stored form of a kept version: the computed [PartgReportDocument] as canonical JSON, and its SHA-256.
 *
 * FORMAT RULE (pinned by `PartgVersionFormatGoldenTest`): a kept version must stay readable forever. Fields may be added to the document only
 * with a default value. Renaming or removing a field, or changing the meaning of one, needs a NEW [CURRENT] and a decoder for the old format
 * that stays. The numbers in a version are final; only the RENDERING of a version by a later renderer may change (same numbers, other layout).
 *
 * Canonical: one [Json] instance with `encodeDefaults = true`, `explicitNulls = true`, no pretty printing; the maps of the document keep the
 * insertion order of the assembler, so building the same state twice gives the same bytes and the same hash.
 */
internal object PartgVersionFormat {
    const val CURRENT = 1

    /** The size budget of one kept version, in UTF-8 bytes of the JSON text. Also a CHECK in `V146`. */
    const val MAX_JSON_BYTES = 4 * 1024 * 1024

    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
            ignoreUnknownKeys = false
        }

    fun encode(document: PartgReportDocument): String = json.encodeToString(PartgReportDocument.serializer(), document)

    /** Decodes a stored text; an unknown format version or a text that does not decode is a [PartyReportVersionIntegrityException]. */
    fun decode(
        text: String,
        formatVersion: Int,
    ): PartgReportDocument {
        if (formatVersion != CURRENT) throw PartyReportVersionIntegrityException("Unknown format of a kept version")
        return try {
            json.decodeFromString(PartgReportDocument.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PartyReportVersionIntegrityException("A kept version cannot be read")
        } catch (e: IllegalArgumentException) {
            throw PartyReportVersionIntegrityException("A kept version cannot be read")
        }
    }

    /** SHA-256 (hex, lower case) over the UTF-8 bytes of exactly this text. A NEW [MessageDigest] per call (thread safety). */
    fun hash(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun byteSize(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** Throws [PartyReportVersionTooLargeException] when the text is over [maxBytes] (a parameter so a test can use a small budget). */
    fun requireWithinBudget(
        text: String,
        maxBytes: Int = MAX_JSON_BYTES,
    ): Int {
        val size = byteSize(text)
        if (size > maxBytes) throw PartyReportVersionTooLargeException()
        return size
    }
}

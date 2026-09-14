package network.lapis.cloud.server.payment.fints

import org.kapott.hbci.GV_Result.GVRKUms

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- resolves the plan's blocking open question
 * OQ-1 ("does hbci4j expose the raw MT940 the bank actually sent, or only pre-parsed [GVRKUms
 * .UmsLine] rows?").
 *
 * **Live-verified against `hbci4j-core:4.0.0` bytecode** (`javap -c -p`, this exact pinned version,
 * 2026-09-13) -- `GVRKUms` has NO public raw-MT940 getter: [GVRKUms.getFlatData] returns already-
 * PARSED [GVRKUms.UmsLine] rows, [GVRKUms.restMT940] is only the trailing UNPARSED remainder after
 * the last complete booking. The actual raw text lives in a PRIVATE field, `bufferMT940`
 * (`java.lang.StringBuffer`), populated by the framework itself: `GVKUmsAll.extractResults`
 * (disassembled, not decompiled -- no decompiler was available in the verification environment)
 * reads `HBCIMsgStatus.getData().getProperty("<segHeader>.booked")`, umlaut-decodes it via
 * `org.kapott.hbci.swift.Swift.decodeUmlauts` (a lossless SWIFT-charset transform, never a
 * re-serialization), and calls `result.appendMT940Data(decoded)` -- which does nothing but
 * `bufferMT940.append(decoded)`. By the time a caller's own code reaches `job.getJobResult()` after
 * `handle.execute()`, [bufferMT940] already holds EXACTLY the bytes this function returns: no
 * reparsing, no reconstruction from [GVRKUms.getFlatData], no dependency on hbci4j's internal
 * `GVRes[_N].SegHead.ref` property-key indexing scheme (which would have been the fragile
 * alternative -- deriving the same `Properties` key ourselves from job/segment counters that are
 * not exposed to a caller).
 *
 * **Why reflection is the RIGHT tool here, not a workaround.** The alternative from the plan's own
 * "if reachable" branch -- re-deriving the `<segHeader>.booked` key from `HBCIMsgStatus.getData()`
 * -- would require replicating `HBCIJobImpl.fillJobResult`'s `GVRes[_N].SegHead.ref` counter
 * arithmetic (job `idx`, `contentCounter`, a sorted `Hashtable` walk) with NO public API exposing
 * those counters to a caller at all. Reading the ALREADY-POPULATED private field the framework
 * itself filled in is strictly simpler and strictly more faithful (byte-identical, not
 * independently reconstructed) than reimplementing that indexing scheme from outside.
 *
 * **This is exactly the kind of hbci4j-version-coupled fragility the plan calls out.** [extract]
 * throws [Hbci4jRawMt940FieldMissingException] -- loudly, not a silent empty string -- if
 * `bufferMT940` is ever renamed, retyped, or removed by a future hbci4j release.
 * `Hbci4jRawMt940ExtractorTest` pins this against a real, unmodified [GVRKUms] instance (no fake,
 * no mock of hbci4j's own class) so a `hbci4j-core` version bump that breaks this assumption fails
 * loudly in CI, not silently in production.
 */
internal object Hbci4jRawMt940Extractor {
    private const val FIELD_NAME = "bufferMT940"

    /**
     * Returns the raw (post-umlaut-decode, pre-parse) MT940 text hbci4j accumulated into [result]
     * during job execution, UTF-8 encoded -- this is exactly the [ByteArray] that
     * [network.lapis.cloud.server.payment.bankstatement.BankStatementImportService.import] receives
     * as `bytes`, unmodified by this wave.
     */
    fun extract(result: GVRKUms): ByteArray {
        val field =
            try {
                GVRKUms::class.java.getDeclaredField(FIELD_NAME)
            } catch (e: NoSuchFieldException) {
                throw Hbci4jRawMt940FieldMissingException(cause = e)
            }
        field.isAccessible = true
        val buffer =
            try {
                field.get(result) as? StringBuffer
                    ?: throw Hbci4jRawMt940FieldMissingException(cause = null)
            } catch (e: IllegalAccessException) {
                throw Hbci4jRawMt940FieldMissingException(cause = e)
            }
        return buffer.toString().toByteArray(Charsets.UTF_8)
    }
}

/**
 * Thrown by [Hbci4jRawMt940Extractor.extract] when hbci4j's private `GVRKUms.bufferMT940` field is
 * missing, renamed, or retyped -- i.e. a future `hbci4j-core` version bump broke the assumption this
 * class documents. Deliberately a LOUD failure (mapped to [FinTsErrorCode.PROTOCOL_ERROR] by the
 * caller), never a silently empty statement file.
 */
internal class Hbci4jRawMt940FieldMissingException(
    cause: Throwable?,
) : Exception(
        "hbci4j-core's private GVRKUms.bufferMT940 field is missing or of an unexpected type -- check the pinned hbci4j-core version",
        cause,
    )

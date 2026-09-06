package network.lapis.cloud.shared.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Roundtrip and error-detection are checked over a large,
 * seeded random sample of the 2^25 possible payloads (33.5 million) plus the two boundary values --
 * not literally every payload, which would multiply out to billions of `isValid` calls across the
 * error-detection tests below and make this suite too slow to run routinely. The GF(32) algebra
 * (`PaymentReferenceCode` KDoc) guarantees every property checked here holds for EVERY payload, not
 * just the ones sampled -- these tests are the empirical spot-check of that algebraic proof, not a
 * brute-force substitute for it.
 */
class PaymentReferenceCodeTest {
    @Test
    fun encodeIsValidRoundtripHoldsForALargeRandomSamplePlusBothBoundaryPayloads() {
        val random = Random(1)
        val samplePayloads = listOf(0, (1 shl 25) - 1) + (1..20_000).map { random.nextInt(1 shl 25) }
        samplePayloads.forEach { payload ->
            val body = PaymentReferenceCode.encode(payload)
            assertEquals(PaymentReferenceCode.CANONICAL_LENGTH, body.length)
            assertTrue(PaymentReferenceCode.isValid(body))
        }
    }

    @Test
    fun everySingleCharacterSubstitutionAtEveryPositionIsDetectedForASampleOfPayloads() {
        val random = Random(42)
        val samplePayloads = (1..1_000).map { random.nextInt(1 shl 25) }
        samplePayloads.forEach { payload ->
            val body = PaymentReferenceCode.encode(payload)
            for (position in body.indices) {
                for (replacement in PaymentReferenceCode.ALPHABET) {
                    if (replacement == body[position]) continue
                    val corrupted = body.substring(0, position) + replacement + body.substring(position + 1)
                    assertTrue(!PaymentReferenceCode.isValid(corrupted), "expected '$corrupted' (from '$body') to be invalid")
                }
            }
        }
    }

    @Test
    fun everyTranspositionOfTwoDistinctCharactersIsDetectedForASampleOfPayloads() {
        val random = Random(7)
        val samplePayloads = (1..1_000).map { random.nextInt(1 shl 25) }
        samplePayloads.forEach { payload ->
            val body = PaymentReferenceCode.encode(payload)
            for (i in body.indices) {
                for (j in body.indices) {
                    if (i == j || body[i] == body[j]) continue
                    val chars = body.toCharArray()
                    val tmp = chars[i]
                    chars[i] = chars[j]
                    chars[j] = tmp
                    assertTrue(
                        !PaymentReferenceCode.isValid(chars.concatToString()),
                        "expected transposed '$body' at ($i,$j) to be invalid",
                    )
                }
            }
        }
    }

    @Test
    fun normalizeRepairsTyposAcceptsWithOrWithoutPrefixAndRejectsAnInvalidChecksum() {
        val body = PaymentReferenceCode.encode(12345)
        val full = PaymentReferenceCode.PREFIX + body
        assertEquals(full, PaymentReferenceCode.normalize(full))
        assertEquals(full, PaymentReferenceCode.normalize(body))
        assertEquals(full, PaymentReferenceCode.normalize(" ${full.lowercase()} "))

        val corruptedBody = body.dropLast(1) + PaymentReferenceCode.ALPHABET.first { it != body.last() }
        assertNull(PaymentReferenceCode.normalize(corruptedBody))
    }

    @Test
    fun findInLocatesAValidReferenceEmbeddedInFreeFormPurposeText() {
        val body = PaymentReferenceCode.encode(999)
        val full = PaymentReferenceCode.PREFIX + body
        assertEquals(full, PaymentReferenceCode.findIn("UEBERWEISUNG MITGLIEDSBEITRAG $full DANKE"))
        assertNull(PaymentReferenceCode.findIn("kein treffer hier"))
    }

    @Test
    fun encodeRejectsAPayloadOutsideValidRange() {
        assertFailsWith<IllegalArgumentException> { PaymentReferenceCode.encode(-1) }
        assertFailsWith<IllegalArgumentException> { PaymentReferenceCode.encode(1 shl 25) }
    }
}

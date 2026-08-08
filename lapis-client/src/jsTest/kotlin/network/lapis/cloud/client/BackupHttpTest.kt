package network.lapis.cloud.client

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Compliance UI wave, screen 2 of 5 -- covers only the pure, DOM/network-independent
 * [parseRestoreOutcome] function factored out of `BackupHttp.kt`/`BackupScreen.kt`: the "three
 * distinct restore error paths" design decision (400/409/422 must map to their own distinct
 * [RestoreOutcome] case, never folded together), the success decode path, and the honest fallback
 * for a response this client did not anticipate. Same scope posture as [AuditLogScreenTest]/
 * [ValidationTest] (no DOM/rendering test harness exists in this module).
 */
class BackupHttpTest {
    @Test
    fun parseRestoreOutcome_200_decodesSuccessBody() {
        val body =
            Json.encodeToString(
                RestoreSuccessResult.serializer(),
                RestoreSuccessResult(tablesRestored = 12, totalRowCount = 340, blobsRestored = 3, warnings = emptyList()),
            )
        val outcome = parseRestoreOutcome(200, body)
        assertIs<RestoreOutcome.Success>(outcome)
        assertEquals(12, outcome.result.tablesRestored)
        assertEquals(340L, outcome.result.totalRowCount)
        assertEquals(3, outcome.result.blobsRestored)
        assertTrue(outcome.result.warnings.isEmpty())
    }

    @Test
    fun parseRestoreOutcome_200_preservesWarnings() {
        val body =
            Json.encodeToString(
                RestoreSuccessResult.serializer(),
                RestoreSuccessResult(
                    tablesRestored = 5,
                    totalRowCount = 10,
                    blobsRestored = 0,
                    warnings = listOf("Bundle contains tables not present in this server's live schema (ignored): [foo]"),
                ),
            )
        val outcome = parseRestoreOutcome(200, body)
        assertIs<RestoreOutcome.Success>(outcome)
        assertEquals(1, outcome.result.warnings.size)
    }

    @Test
    fun parseRestoreOutcome_200_malformedBodyFallsBackToOtherRatherThanCrashing() {
        val outcome = parseRestoreOutcome(200, "{not valid json")
        assertIs<RestoreOutcome.Other>(outcome)
        assertEquals(200, outcome.status)
    }

    @Test
    fun parseRestoreOutcome_400_isIncompatibleBundle() {
        val outcome = parseRestoreOutcome(400, "Bundle formatVersion 3 is incompatible with this server's expected formatVersion 4")
        assertIs<RestoreOutcome.IncompatibleBundle>(outcome)
        assertTrue(outcome.message.contains("formatVersion"))
    }

    @Test
    fun parseRestoreOutcome_409_isNonEmptyTarget() {
        val outcome =
            parseRestoreOutcome(
                409,
                "Target database already holds data beyond the Flyway-seeded singleton rows (member (3 row(s)))",
            )
        assertIs<RestoreOutcome.NonEmptyTarget>(outcome)
        assertTrue(outcome.message.contains("already holds data"))
    }

    @Test
    fun parseRestoreOutcome_422_isIncomplete() {
        val outcome = parseRestoreOutcome(422, "Content checksum mismatch for table 'member' after restoring 12 row(s)")
        assertIs<RestoreOutcome.Incomplete>(outcome)
        assertTrue(outcome.message.contains("checksum mismatch"))
    }

    @Test
    fun parseRestoreOutcome_unclassifiedStatus_isOtherWithMessagePreserved() {
        val outcome = parseRestoreOutcome(413, "Max restore bundle size is 536870912 bytes")
        assertIs<RestoreOutcome.Other>(outcome)
        assertEquals(413, outcome.status)
        assertEquals("Max restore bundle size is 536870912 bytes", outcome.message)
    }

    @Test
    fun parseRestoreOutcome_unclassifiedStatus_blankBodyGetsAFallbackMessage() {
        val outcome = parseRestoreOutcome(500, "")
        assertIs<RestoreOutcome.Other>(outcome)
        assertTrue(outcome.message.contains("500"))
    }
}

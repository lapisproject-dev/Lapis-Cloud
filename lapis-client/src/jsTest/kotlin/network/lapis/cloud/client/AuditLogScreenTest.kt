package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AuditChainVerificationResultDto
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BoardMembershipSnapshot
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.DonationDuty
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.PartyDonationVerdictSnapshot
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionSnapshot
import network.lapis.cloud.shared.domain.ResolutionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Compliance UI wave -- covers only the pure, DOM-independent functions factored out of
 * `AuditLogScreen.kt`: [parseOptionalDateTime] (filter parsing), [chainVerificationPassDetailText]/
 * [chainVerificationFailHeadline] (D1's exact result copy), and [decodeAuditSnapshot] (D2's
 * structured-vs-raw-fallback decode step) -- same scope posture as [ValidationTest]/
 * [NonprofitComplianceReportsScreenTest] (no DOM/rendering test harness exists in this module).
 */
class AuditLogScreenTest {
    // ---- parseOptionalDateTime -----------------------------------------------------------

    @Test
    fun parseOptionalDateTime_nullIsNull() {
        assertNull(parseOptionalDateTime(null))
    }

    @Test
    fun parseOptionalDateTime_blankIsNull() {
        assertNull(parseOptionalDateTime(""))
        assertNull(parseOptionalDateTime("   "))
    }

    @Test
    fun parseOptionalDateTime_unparsableIsNull() {
        assertNull(parseOptionalDateTime("not-a-date"))
    }

    @Test
    fun parseOptionalDateTime_validParses() {
        assertEquals(LocalDateTime(2026, 1, 1, 10, 30), parseOptionalDateTime("2026-01-01T10:30"))
    }

    // ---- D1: chain-verification result copy -----------------------------------------------

    @Test
    fun chainVerificationPassDetailText_withRange() {
        val result =
            AuditChainVerificationResultDto(
                valid = true,
                checkedCount = 5,
                firstSequenceNumber = 1L,
                lastSequenceNumber = 5L,
                brokenAtSequenceNumber = null,
                reason = null,
            )
        assertEquals("5 Einträge geprüft (Sequenznummer 1–5).", chainVerificationPassDetailText(result))
    }

    @Test
    fun chainVerificationPassDetailText_emptyRangeNeverFabricatesNumbers() {
        val result =
            AuditChainVerificationResultDto(
                valid = true,
                checkedCount = 0,
                firstSequenceNumber = null,
                lastSequenceNumber = null,
                brokenAtSequenceNumber = null,
                reason = null,
            )
        assertEquals("Keine Einträge im geprüften Bereich.", chainVerificationPassDetailText(result))
    }

    @Test
    fun chainVerificationFailHeadline_namesTheBrokenSequenceNumber() {
        val result =
            AuditChainVerificationResultDto(
                valid = false,
                checkedCount = 2,
                firstSequenceNumber = 1L,
                lastSequenceNumber = 5L,
                brokenAtSequenceNumber = 3L,
                reason = "tampered",
            )
        assertEquals("✗ Kette gebrochen bei Sequenznummer 3", chainVerificationFailHeadline(result))
    }

    // ---- D2: structured snapshot decode -----------------------------------------------------

    @Test
    fun decodeAuditSnapshot_journalEntry_roundTrips() {
        val snapshot =
            JournalEntrySnapshot(
                entryDate = LocalDate(2026, 1, 1),
                description = "Testbuchung",
                voucherReference = "B-1",
                status = JournalEntryStatus.POSTED,
                postedAt = LocalDateTime(2026, 1, 1, 10, 0),
                createdBy = "member-1",
                donorMemberId = null,
                externalDonorId = null,
                donorCategory = null,
                postings = emptyList(),
            )
        val raw = Json.encodeToString(JournalEntrySnapshot.serializer(), snapshot)
        assertEquals(snapshot, decodeAuditSnapshot(AuditEntityType.JOURNAL_ENTRY, raw))
    }

    @Test
    fun decodeAuditSnapshot_resolution_roundTrips() {
        val snapshot =
            ResolutionSnapshot(
                meetingId = "meeting-1",
                number = "2026-01",
                title = "Testbeschluss",
                text = "Beschlusstext",
                votesYes = 5,
                votesNo = 1,
                votesAbstain = 0,
                quorumMet = true,
                status = ResolutionStatus.ADOPTED,
                decidedAt = LocalDateTime(2026, 1, 1, 10, 0),
                recordedBy = "member-2",
                resolutionMode = ResolutionMode.COMMITTEE_QUORUM,
            )
        val raw = Json.encodeToString(ResolutionSnapshot.serializer(), snapshot)
        assertEquals(snapshot, decodeAuditSnapshot(AuditEntityType.RESOLUTION, raw))
    }

    @Test
    fun decodeAuditSnapshot_boardMembership_roundTrips() {
        val snapshot =
            BoardMembershipSnapshot(
                memberId = "member-3",
                committeeRole = CommitteeRole.CHAIR,
                startedAt = LocalDate(2026, 1, 1),
                endedAt = null,
            )
        val raw = Json.encodeToString(BoardMembershipSnapshot.serializer(), snapshot)
        assertEquals(snapshot, decodeAuditSnapshot(AuditEntityType.BOARD_MEMBERSHIP, raw))
    }

    @Test
    fun decodeAuditSnapshot_partyDonationVerdict_roundTrips() {
        val snapshot =
            PartyDonationVerdictSnapshot(
                donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
                donationAmount = 100.0.toDecimal(),
                priorPostedTotalThisYear = 50.0.toDecimal(),
                verdict = "ALLOWED",
                duties = listOf(DonationDuty.ANNUAL_DISCLOSURE_REQUIRED),
            )
        val raw = Json.encodeToString(PartyDonationVerdictSnapshot.serializer(), snapshot)
        assertEquals(snapshot, decodeAuditSnapshot(AuditEntityType.PARTY_DONATION_VERDICT, raw))
    }

    @Test
    fun decodeAuditSnapshot_malformedJsonReturnsNullForFallback() {
        assertNull(decodeAuditSnapshot(AuditEntityType.JOURNAL_ENTRY, "{not valid json"))
    }

    @Test
    fun decodeAuditSnapshot_wellFormedButWrongShapeReturnsNullForFallback() {
        // Valid JSON, but missing every field JournalEntrySnapshot requires -- must fall back to
        // the raw-text display (D2), never throw out of this pure function.
        assertNull(decodeAuditSnapshot(AuditEntityType.JOURNAL_ENTRY, """{"unexpectedField":"value"}"""))
    }
}

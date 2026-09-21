package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AnniversaryEmphasis
import network.lapis.cloud.shared.domain.AnniversaryEntryDto
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningCaseDto
import network.lapis.cloud.shared.domain.MemberHonorCategory
import network.lapis.cloud.shared.domain.MemberHonorDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionDto
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.domain.SepaMandateDto
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.26 (W2) -- the client-side search filters the migrated screens gained, all pure.
 *
 * They matter more than they look: each one runs over a PARTIALLY loaded list (the server of these
 * screens has no search parameter), so a filter that silently matched on the wrong field would make a
 * treasurer believe a row does not exist. The tests therefore pin exactly WHICH fields are searchable --
 * including the fields that deliberately are NOT (a masked IBAN, a raw technical id).
 */
class HotTableFiltersTest {
    // ── SEPA mandates ─────────────────────────────────────────────────────────────────────────────

    private fun mandate(
        member: String,
        reference: String,
        ibanLast4: String = "1234",
    ) = SepaMandateDto(
        id = "m-$reference",
        memberId = "member-1",
        memberDisplayName = member,
        mandateReference = reference,
        debtorName = member,
        debtorIbanLast4 = ibanLast4,
        debtorBic = null,
        signatureDate = LocalDate(2026, 1, 1),
        sequenceType = SepaSequenceType.RCUR,
        status = SepaMandateStatus.ACTIVE,
        grantedAt = LocalDateTime(2026, 1, 1, 10, 0),
        revokedAt = null,
        revocationReason = null,
        lastUsedAt = null,
        lastDebitedAmount = null,
        expiresAt = LocalDate(2029, 1, 1),
        createdByMemberId = "member-1",
        createdByDisplayName = member,
        createdBySelf = true,
    )

    @Test
    fun sepaMandates_blankSearchKeepsEverything() {
        val rows = listOf(mandate("Ada Lovelace", "MND-1"), mandate("Grace Hopper", "MND-2"))
        assertEquals(rows, filterSepaMandates(rows, ""))
        assertEquals(rows, filterSepaMandates(rows, "   "))
    }

    @Test
    fun sepaMandates_matchesMemberOrReference_caseInsensitively() {
        val rows = listOf(mandate("Ada Lovelace", "MND-1"), mandate("Grace Hopper", "MND-2"))
        assertEquals(listOf(rows[0]), filterSepaMandates(rows, "lovelace"))
        assertEquals(listOf(rows[1]), filterSepaMandates(rows, "mnd-2"))
        assertEquals(rows, filterSepaMandates(rows, "MND"))
    }

    @Test
    fun sepaMandates_doesNotSearchTheMaskedIban() {
        // Only the last four digits exist in the DTO -- searching them would promise more than it can keep.
        val rows = listOf(mandate("Ada Lovelace", "MND-1", ibanLast4 = "9876"))
        assertTrue(filterSepaMandates(rows, "9876").isEmpty())
    }

    @Test
    fun sepaMandates_emptyTextNamesTheSelectedStatus() {
        val all = sepaMandatesEmptyText(null)
        val revoked = sepaMandatesEmptyText(SepaMandateStatus.REVOKED)
        // Two different sentences: "nothing here yet" vs. "nothing with THIS status".
        assertFalse(all == revoked)
        assertTrue(revoked.contains(sepaMandateStatusLabel(SepaMandateStatus.REVOKED)), revoked)
    }

    // ── Payment transactions ──────────────────────────────────────────────────────────────────────

    private fun transaction(
        member: String?,
        note: String?,
        memberId: String? = "member-1",
    ) = PaymentTransactionDto(
        id = "tx-${member ?: memberId}",
        provider = PaymentProvider.STRIPE,
        providerPaymentId = "pi_1",
        status = PaymentTransactionStatus.CAPTURED,
        amount = 10.0.toDecimal(),
        currency = "EUR",
        feeAmount = null,
        intent = PaymentIntent.CONTRIBUTION,
        contributionId = null,
        memberId = memberId,
        memberDisplayName = member,
        donorCategory = null,
        receivedAt = LocalDateTime(2026, 1, 1, 10, 0),
        journalEntryId = null,
        reconciliationNote = note,
    )

    @Test
    fun paymentTransactions_matchesMemberOrNote() {
        val rows =
            listOf(
                transaction(member = "Ada Lovelace", note = null),
                transaction(member = null, note = "Sammelüberweisung März"),
            )
        assertEquals(listOf(rows[0]), filterPaymentTransactions(rows, "ada"))
        assertEquals(listOf(rows[1]), filterPaymentTransactions(rows, "sammel"))
        assertEquals(rows, filterPaymentTransactions(rows, ""))
    }

    @Test
    fun paymentTransactions_doesNotSearchTheRawMemberId() {
        // A technical id is not text anybody types into a search field.
        val rows = listOf(transaction(member = null, note = null, memberId = "7f3a-technical"))
        assertTrue(filterPaymentTransactions(rows, "7f3a").isEmpty())
    }

    @Test
    fun paymentTransactions_emptyTextSeparatesTheWorkQueueFromTheWholeList() {
        assertFalse(paymentTransactionsEmptyText(unreconciledOnly = true) == paymentTransactionsEmptyText(unreconciledOnly = false))
    }

    // ── Dunning cases ─────────────────────────────────────────────────────────────────────────────

    private fun dunningCase(member: String) =
        DunningCaseDto(
            contributionId = "c-$member",
            memberId = "member-1",
            memberDisplayName = member,
            periodStart = LocalDate(2026, 1, 1),
            periodEnd = LocalDate(2026, 12, 31),
            amountDue = 10.0.toDecimal(),
            dueDate = LocalDate(2026, 2, 1),
            contributionStatus = ContributionStatus.OVERDUE,
            paymentMethod = ContributionPaymentMethod.MANUAL,
            currentCycleNumber = 1,
            highestLevelNumber = 1,
            lastNoticeIssuedAt = null,
            nextLevelNumber = 2,
            nextLevelDueOn = LocalDate(2026, 3, 1),
            totalFeesCharged = 0.0.toDecimal(),
            memberStatus = MemberStatus.ACTIVE,
        )

    @Test
    fun dunningCases_matchesTheMemberName() {
        val rows = listOf(dunningCase("Ada Lovelace"), dunningCase("Grace Hopper"))
        assertEquals(listOf(rows[1]), filterDunningCases(rows, "HOPPER"))
        assertEquals(rows, filterDunningCases(rows, "  "))
    }

    // ── Member honours ────────────────────────────────────────────────────────────────────────────

    private fun honor(
        title: String,
        member: String,
    ) = MemberHonorDto(
        id = "h-$title",
        memberId = "member-1",
        memberDisplayName = member,
        category = MemberHonorCategory.SERVICE_AWARD,
        title = title,
        awardedAt = LocalDate(2026, 1, 1),
        awardedBy = null,
        note = null,
        recordedById = "admin",
        recordedAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    @Test
    fun memberHonors_matchesTitleOrMember() {
        val rows = listOf(honor("Goldene Nadel", "Ada Lovelace"), honor("Ehrenurkunde", "Grace Hopper"))
        assertEquals(listOf(rows[0]), filterMemberHonors(rows, "nadel"))
        assertEquals(listOf(rows[1]), filterMemberHonors(rows, "grace"))
    }

    // ── Anniversaries ─────────────────────────────────────────────────────────────────────────────

    private fun anniversary(
        member: String,
        kind: AnniversaryEntryKind,
    ) = AnniversaryEntryDto(
        kind = kind,
        memberId = "member-$member",
        memberDisplayName = member,
        memberStatus = MemberStatus.ACTIVE,
        occursOn = LocalDate(2026, 3, 1),
        originalDate = LocalDate(1986, 3, 1),
        shiftedFromLeapDay = false,
        years = 40,
        emphasis = AnniversaryEmphasis.STANDARD,
    )

    @Test
    fun anniversaries_bothFiltersCombine() {
        val rows =
            listOf(
                anniversary("Ada Lovelace", AnniversaryEntryKind.BIRTHDAY),
                anniversary("Grace Hopper", AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY),
            )
        assertEquals(rows, filterAnniversaryEntries(rows, null, ""))
        assertEquals(listOf(rows[0]), filterAnniversaryEntries(rows, AnniversaryEntryKind.BIRTHDAY, ""))
        assertEquals(listOf(rows[1]), filterAnniversaryEntries(rows, null, "hopper"))
        // Kind and search must intersect, not union: a birthday search for "hopper" finds nothing.
        assertTrue(filterAnniversaryEntries(rows, AnniversaryEntryKind.BIRTHDAY, "hopper").isEmpty())
    }

    @Test
    fun anniversaries_noMatchTextNamesTheResponsibleFilter() {
        val searchText = anniversaryNoMatchText(kind = AnniversaryEntryKind.BIRTHDAY, search = "Schmidt")
        // A typed term is the one the reader can correct, so it wins over the segment.
        assertTrue(searchText.contains("Schmidt"), searchText)
        val birthdays = anniversaryNoMatchText(kind = AnniversaryEntryKind.BIRTHDAY, search = "")
        val jubilees = anniversaryNoMatchText(kind = AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY, search = "")
        val neither = anniversaryNoMatchText(kind = null, search = "")
        assertEquals(3, setOf(birthdays, jubilees, neither).size)
    }
}

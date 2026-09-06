package network.lapis.cloud.server.payment.bankstatement

import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.PaymentReferenceCode
import network.lapis.cloud.shared.domain.SepaMandateStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** The subset of a parsed/persisted bank line [BankStatementMatcher] needs -- the RAW (in-memory-only, never persisted) counterparty IBAN is required here for the R2 rule, even though `bank_statement_line` itself only ever stores the encrypted form. */
internal data class NormalizedLine(
    val amount: BigDecimal,
    val currency: String,
    val purpose: String?,
    val counterpartyName: String?,
    val counterpartyIbanRaw: String?,
)

/**
 * Review fix (MEDIUM, Runde-1-Fund #13): `contribution.amount_due` carries no currency column of
 * its own (see `ContributionTable`) -- it is implicitly denominated in this hardcoded organization
 * currency, the SAME "EUR only" assumption every other payment-adjacent module in this codebase
 * already makes (`EventPolicy.feeCurrency`, `PriceOracleService.SUPPORTED_DONATION_CURRENCIES`,
 * `AnonymousDonationCheckout`, `SepaPain008Writer`'s hardcoded `Ccy="EUR"`). Before this fix, R1
 * (see [matchByReference]) compared amounts ALONE -- a foreign-currency credit (CHF/USD) whose
 * numeric amount happened to equal an open EUR contribution's `amountDue`, carrying a valid payment
 * reference, would auto-post as a full settlement of that contribution: `payment_transaction
 * .currency` would then disagree with the amount actually recognised against the ledger in EUR, and
 * the only correction path is a manual storno (`requireAssignableLine` refuses to touch a `POSTED`
 * line). Same "amount/currency-tampering defense" precedent [network.lapis.cloud.server.payment.psp
 * .PspWebhookIngestion] already documents for the checkout-session path.
 */
private const val ORGANIZATION_CURRENCY = "EUR"

internal data class MatchOutcome(
    val status: BankStatementLineStatus,
    val explanation: String?,
    val contributionId: Uuid?,
    val autoPost: Boolean,
)

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Four rules, first match wins, applied in this exact order
 * -- see the plan's own "Reihenfolge, erste greifende Regel gewinnt". Must run inside an already
 * open transaction (read-only) -- see `BankStatementImportService` KDoc "Phase 1".
 *
 * **§25 PartG note (R4)**: a line that matches NOTHING is [BankStatementLineStatus.UNMATCHED], with
 * an explanation pointing at manual donation assignment -- NEVER auto-posted as a donation. A
 * donor's [network.lapis.cloud.shared.domain.DonorCategory] cannot be derived from a bank line
 * alone, and posting a donation without one would silently bypass the §25 PartG compliance check
 * `DonationPostingBridge.postDonationPayment` otherwise always applies.
 */
internal object BankStatementMatcher {
    fun match(
        line: NormalizedLine,
        secretBox: SecretBox?,
    ): MatchOutcome {
        // Vorregel -- an outgoing payment (or a zero-amount line) is never a membership-fee receipt.
        if (line.amount <= BigDecimal.ZERO) {
            return MatchOutcome(
                status = BankStatementLineStatus.IGNORED,
                explanation = "Ausgangsbuchung",
                contributionId = null,
                autoPost = false,
            )
        }

        matchByReference(line)?.let { return it }
        matchByIban(line = line, secretBox = secretBox)?.let { return it }
        matchByName(line)?.let { return it }

        return MatchOutcome(
            status = BankStatementLineStatus.UNMATCHED,
            explanation = "Kein Beitragstreffer -- als Spende zuordenbar",
            contributionId = null,
            autoPost = false,
        )
    }

    // ── R1 -- Referenz ──────────────────────────────────────────────────────────────────

    private fun matchByReference(line: NormalizedLine): MatchOutcome? {
        val referenceCode = line.purpose?.let { PaymentReferenceCode.findIn(it) } ?: return null
        val row =
            ContributionTable
                .innerJoin(MemberTable)
                .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
                .selectAll()
                .where { ContributionTable.paymentReference eq referenceCode }
                .singleOrNull() ?: return null // valid checksum but no allocated reference -- fall through to R2/R3

        val status = row[ContributionTable.status]
        val amountDue = row[ContributionTable.amountDue]
        val contributionId = row[ContributionTable.id]
        val memberName = row[MemberTable.displayName]
        val periodStart = row[ContributionTable.periodStart]
        val periodEnd = row[ContributionTable.periodEnd]

        val currencyMatches = line.currency.equals(ORGANIZATION_CURRENCY, ignoreCase = true)
        return if (status in ContributionStatusSets.OUTSTANDING && amountDue.compareTo(line.amount) == 0 && currencyMatches) {
            MatchOutcome(
                status = BankStatementLineStatus.POSTED,
                explanation =
                    "Referenz $referenceCode, Pruefzeichen gueltig; Beitrag $periodStart-$periodEnd " +
                        "$memberName; Betrag ${line.amount} = Sollbetrag $amountDue",
                contributionId = contributionId,
                autoPost = true,
            )
        } else {
            val reason =
                when {
                    status !in ContributionStatusSets.OUTSTANDING -> "der Beitrag ist nicht (mehr) offen (Status $status)"
                    // Review fix (MEDIUM, Runde-1-Fund #13): checked BEFORE the amount-mismatch
                    // fallback, since a currency mismatch is the more actionable diagnosis for an
                    // operator even when the numeric amount happens to also differ.
                    !currencyMatches -> "die Waehrung weicht ab (erhalten ${line.currency}, erwartet $ORGANIZATION_CURRENCY)"
                    else -> "der Betrag weicht vom Sollbetrag ab (erhalten ${line.amount}, erwartet $amountDue)"
                }
            MatchOutcome(
                status = BankStatementLineStatus.AMBIGUOUS,
                explanation = "Referenz $referenceCode, Pruefzeichen gueltig, aber $reason",
                contributionId = contributionId,
                autoPost = false,
            )
        }
    }

    // ── R2 -- IBAN (SEPA-Mandat) ────────────────────────────────────────────────────────

    private fun matchByIban(
        line: NormalizedLine,
        secretBox: SecretBox?,
    ): MatchOutcome? {
        if (secretBox == null || line.counterpartyIbanRaw == null) return null
        val normalizedRawIban = IbanValidator.normalize(line.counterpartyIbanRaw)
        if (normalizedRawIban.length < 4) return null
        val last4 = normalizedRawIban.takeLast(4)

        val candidates =
            SepaMandateTable
                .selectAll()
                .where { (SepaMandateTable.debtorIbanLast4 eq last4) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
                .toList()

        val matchedMemberIds = mutableSetOf<Uuid>()
        for (candidate in candidates) {
            val decrypted =
                try {
                    secretBox.open(
                        sealed = candidate[SepaMandateTable.debtorIbanCiphertext],
                        aad = candidate[SepaMandateTable.id].toString(),
                    )
                } catch (e: SecretBoxException) {
                    null
                }
            if (decrypted != null && IbanValidator.normalize(decrypted) == normalizedRawIban) {
                matchedMemberIds += candidate[SepaMandateTable.memberId]
            }
        }
        if (matchedMemberIds.size != 1) return null // no candidate, or an unresolved ambiguity -- fall through to R3

        val memberId = matchedMemberIds.single()
        val openContributions = openOutstandingContributions(memberId = memberId, amount = line.amount)
        return when (openContributions.size) {
            1 -> {
                val (contributionId, memberName, tierName, periodStart, periodEnd) = openContributions.single()
                MatchOutcome(
                    status = BankStatementLineStatus.SUGGESTED,
                    explanation = "IBAN-Abgleich: $memberName ($tierName, $periodStart-$periodEnd)",
                    contributionId = contributionId,
                    autoPost = false,
                )
            }
            0 -> null // IBAN identifies a member, but no open contribution of theirs matches this amount -- fall through to R3
            else ->
                MatchOutcome(
                    status = BankStatementLineStatus.AMBIGUOUS,
                    explanation = "IBAN-Abgleich fand ${openContributions.size} passende offene Beitraege fuer dasselbe Mitglied",
                    contributionId = null,
                    autoPost = false,
                )
        }
    }

    // ── R3 -- Name ──────────────────────────────────────────────────────────────────────

    private fun matchByName(line: NormalizedLine): MatchOutcome? {
        val counterpartyTokens = tokenize(line.counterpartyName.orEmpty())
        if (counterpartyTokens.isEmpty()) return null

        val candidates =
            ContributionTable
                .innerJoin(MemberTable)
                .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
                .selectAll()
                .where { ContributionTable.status inList ContributionStatusSets.OUTSTANDING.toList() }
                .filter { row -> row[ContributionTable.amountDue].compareTo(line.amount) == 0 }
                .filter { row ->
                    val memberTokens = tokenize(row[MemberTable.displayName])
                    val first = memberTokens.firstOrNull()
                    val last = memberTokens.lastOrNull()
                    first != null && last != null && first in counterpartyTokens && last in counterpartyTokens
                }

        val distinctMembers = candidates.map { it[ContributionTable.memberId] }.toSet()
        if (distinctMembers.size != 1) {
            return if (candidates.isEmpty()) {
                null
            } else {
                MatchOutcome(
                    status = BankStatementLineStatus.AMBIGUOUS,
                    explanation = "Namensabgleich fand mehrere passende Mitglieder fuer '${line.counterpartyName}'",
                    contributionId = null,
                    autoPost = false,
                )
            }
        }
        return when (candidates.size) {
            1 -> {
                val row = candidates.single()
                MatchOutcome(
                    status = BankStatementLineStatus.SUGGESTED,
                    explanation =
                        "Namensabgleich: ${row[MemberTable.displayName]} (${row[MembershipTierTable.name]}, " +
                            "${row[ContributionTable.periodStart]}-${row[ContributionTable.periodEnd]})",
                    contributionId = row[ContributionTable.id],
                    autoPost = false,
                )
            }
            else ->
                MatchOutcome(
                    status = BankStatementLineStatus.AMBIGUOUS,
                    explanation = "Namensabgleich fand ${candidates.size} passende offene Beitraege fuer dasselbe Mitglied",
                    contributionId = null,
                    autoPost = false,
                )
        }
    }

    private data class OpenContribution(
        val contributionId: Uuid,
        val memberName: String,
        val tierName: String,
        val periodStart: kotlinx.datetime.LocalDate,
        val periodEnd: kotlinx.datetime.LocalDate,
    )

    private fun openOutstandingContributions(
        memberId: Uuid,
        amount: BigDecimal,
    ): List<OpenContribution> =
        ContributionTable
            .innerJoin(MemberTable)
            .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
            .selectAll()
            .where {
                (ContributionTable.memberId eq memberId) and
                    (ContributionTable.status inList ContributionStatusSets.OUTSTANDING.toList())
            }.filter { it[ContributionTable.amountDue].compareTo(amount) == 0 }
            .map { row ->
                OpenContribution(
                    contributionId = row[ContributionTable.id],
                    memberName = row[MemberTable.displayName],
                    tierName = row[MembershipTierTable.name],
                    periodStart = row[ContributionTable.periodStart],
                    periodEnd = row[ContributionTable.periodEnd],
                )
            }

    /** Uppercase, umlaut-folded (Ä->AE, Ö->OE, Ü->UE, ß->SS), non-letter-stripped WORDS, in order -- used by R3's "Vor- und Nachname als Token" check. */
    private fun tokenize(text: String): List<String> =
        text
            .split(Regex("""\s+"""))
            .map { word ->
                word
                    .replace("ä", "ae")
                    .replace("Ä", "Ae")
                    .replace("ö", "oe")
                    .replace("Ö", "Oe")
                    .replace("ü", "ue")
                    .replace("Ü", "Ue")
                    .replace("ß", "ss")
                    .uppercase()
                    .filter { it in 'A'..'Z' }
            }.filter { it.isNotBlank() }
}

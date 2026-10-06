package network.lapis.cloud.server.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DunningComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.payment.bankstatement.PaymentReferenceAllocator
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.payment.sepa.SepaCharacterSet
import network.lapis.cloud.server.payment.sepa.SepaMandateReferenceGenerator
import network.lapis.cloud.server.rpc.ContributionPostingBridge
import network.lapis.cloud.server.rpc.DunningComplianceDisclaimer
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.OpenItemPostingBridge
import network.lapis.cloud.server.rpc.OpenItemPostingOutcome
import network.lapis.cloud.server.rpc.SepaComplianceDisclaimer
import network.lapis.cloud.server.rpc.applyOpenItemPostingOutcome
import network.lapis.cloud.server.rpc.batchSnapshotFrom
import network.lapis.cloud.server.rpc.sepaBatchMessageId
import network.lapis.cloud.server.rpc.toOpenItemSnapshot
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.CounterpartyKey
import network.lapis.cloud.shared.domain.DunningLevelSnapshot
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemSnapshot
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.SepaDebitBatchSnapshot
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateSnapshot
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Staging seed, area "finance" (Welle V1.9.63): six months of membership contributions in the states the treasurer sees
 * (paid, open, overdue, debit scheduled), the general ledger entries behind the paid ones, two open items, SEPA mandates plus
 * one SEPA batch in DRAFT, and the dunning levels.
 *
 * **Bookings only through the bridges.** A paid contribution is booked by [ContributionPostingBridge.postContributionPayment],
 * an open item by [OpenItemPostingBridge.postItemCreation] -- the same call the live services make, with the same guards and
 * the same audit entry. The seed never writes `journal_entry`, `posting` or `audit_log_entry` itself, and a bridge that
 * degrades (returns null / `Failed` because the account mapping is missing) is an error here, never a silently empty ledger.
 *
 * **What it deliberately does NOT create** (honest limits, see README "What it deliberately does not contain"): an issued
 * dunning notice (needs a PDF and a file), a SUBMITTED / RETURNED debit (needs a pain.008 file / `recordReturn`), manual
 * journal entries and an opening balance, a settled open item, and a waived contribution. SEPA only exists if an encryption key
 * is configured.
 *
 * No mail, no letter, no webhook, no file: [ContributionPaymentEvents] (the webhook outbox) and `notifyBatch` are not called.
 */
internal object StagingSeedFinance {
    private const val MONTHS = 6
    private const val PAYMENT_TERM_DAYS = 14
    private const val DEMO_CREDITOR_ID = "DE98ZZZ09999999999"
    private const val MANDATE_COUNT = 8
    private const val BATCH_ITEM_COUNT = 6

    fun JdbcTransaction.seedFinance(
        clock: SeedClock,
        actors: SeedActors,
        ledgerIds: Map<String, Uuid>,
        sepaKey: ByteArray?,
    ) {
        val currentMonthContributions = seedContributions(clock = clock, actors = actors)
        seedOpenItems(clock = clock, actors = actors, ledgerIds = ledgerIds)
        if (sepaKey != null) {
            seedSepa(clock = clock, actors = actors, sepaKey = sepaKey, candidates = currentMonthContributions)
        } else {
            logger.info { "SEPA demo data skipped (no usable LAPIS_SECRET_ENCRYPTION_KEY configured)." }
        }
        seedDunning(clock = clock, actors = actors)
    }

    /** One contribution row of the current month that a SEPA mandate may later be attached to. */
    private class OpenContribution(
        val id: Uuid,
        val memberId: Uuid,
        val memberName: String,
        val amount: BigDecimal,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
    )

    // ---- contributions ----------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.seedContributions(
        clock: SeedClock,
        actors: SeedActors,
    ): List<OpenContribution> {
        val tierAmounts =
            MembershipTierTable.selectAll().associate {
                it[MembershipTierTable.id] to
                    it[MembershipTierTable.contributionAmount]
            }
        val firstOfThisMonth = LocalDate(clock.today.year, clock.today.monthNumber, 1)
        val currentMonth = mutableListOf<OpenContribution>()
        for (monthsBack in MONTHS - 1 downTo 0) {
            val periodStart = firstOfThisMonth.minus(monthsBack, DateTimeUnit.MONTH)
            val periodEnd = periodStart.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            val billable =
                actors.members.filter { member ->
                    val sinceDay = member.activeSinceDaysAgo?.let { clock.dayAgo(it) }
                    val leftDay = member.leftDaysAgo?.let { clock.dayAgo(it) }
                    sinceDay != null && sinceDay <= periodStart && (leftDay == null || leftDay > periodStart)
                }
            billable.forEachIndexed { index, member ->
                val amount = tierAmounts.getValue(member.tierId)
                if (amount.signum() == 0) return@forEachIndexed
                val contributionId = Uuid.random()
                val regularDue = periodStart.plus(PAYMENT_TERM_DAYS, DateTimeUnit.DAY)
                val status = contributionStatus(monthsBack = monthsBack, index = index)
                // An OPEN line of the running month is shown as "not yet due": a real generation would set period start + term,
                // which for a seed run late in a month would already lie in the past.
                val dueDate =
                    if (status ==
                        ContributionStatus.OPEN
                    ) {
                        maxOf(regularDue, clock.today.plus(3, DateTimeUnit.DAY))
                    } else {
                        regularDue
                    }
                val paidAt =
                    if (status == ContributionStatus.PAID) {
                        clock.wallToUtc(regularDue.minus(PAYMENT_TERM_DAYS - 2 - index % 5, DateTimeUnit.DAY).atTime(LocalTime(9, 30)))
                    } else {
                        null
                    }
                ContributionTable.insert {
                    it[id] = contributionId
                    it[ContributionTable.periodStart] = periodStart
                    it[ContributionTable.periodEnd] = periodEnd
                    it[amountDue] = amount
                    it[ContributionTable.status] = status
                    it[ContributionTable.memberId] = member.id
                    it[membershipTierId] = member.tierId
                    it[ContributionTable.dueDate] = dueDate
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[createdAt] = minOf(clock.wallToUtc(periodStart.atTime(LocalTime(6, 0))), clock.now)
                    if (paidAt != null) {
                        it[ContributionTable.paidAt] = paidAt
                        it[paidAmount] = amount
                    }
                }
                PaymentReferenceAllocator.allocate(contributionId)
                if (paidAt != null) {
                    val journalEntryId =
                        ContributionPostingBridge.postContributionPayment(
                            contributionId = contributionId,
                            paidAmount = amount,
                            paidAt = paidAt,
                            source = ContributionPaymentMethod.MANUAL,
                            providerFee = null,
                            actorMemberId = actors.treasurer.id,
                            actorRole = actors.treasurerActor.role,
                            voucherReference = null,
                        )
                    // null = the account mapping is incomplete; the seed never accepts an unbooked "paid" contribution.
                    check(journalEntryId != null) { "ContributionPostingBridge did not book contribution $contributionId" }
                }
                if (status == ContributionStatus.OPEN) {
                    currentMonth +=
                        OpenContribution(
                            id = contributionId,
                            memberId = member.id,
                            memberName = member.displayName,
                            amount = amount,
                            periodStart = periodStart,
                            periodEnd = periodEnd,
                        )
                }
            }
        }
        return currentMonth
    }

    /** Older months paid, a few overdue in the last two, the running month open. */
    private fun contributionStatus(
        monthsBack: Int,
        index: Int,
    ): ContributionStatus =
        when {
            monthsBack == 0 -> ContributionStatus.OPEN
            monthsBack == 1 && index % 7 == 3 -> ContributionStatus.OVERDUE
            monthsBack == 2 && index % 11 == 5 -> ContributionStatus.OVERDUE
            else -> ContributionStatus.PAID
        }

    // ---- open items -------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.seedOpenItems(
        clock: SeedClock,
        actors: SeedActors,
        ledgerIds: Map<String, Uuid>,
    ) {
        // A receivable (overdue, so the receivable dunning shows a case) and a payable (not yet due).
        createOpenItem(
            clock = clock,
            actors = actors,
            id = SeedIds.finance(0x01),
            direction = OpenItemDirection.RECEIVABLE,
            counterparty = "Gesangverein Beispielhausen (fiktiv)",
            reference = "RE-DEMO-001",
            itemDaysAgo = 35,
            dueDaysAgo = 5,
            amount = BigDecimal("180.00"),
            contraAccountId = ledgerIds.getValue("42010"),
            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
            note = "Raumnutzung Vereinsheim (Demodaten).",
        )
        createOpenItem(
            clock = clock,
            actors = actors,
            id = SeedIds.finance(0x02),
            direction = OpenItemDirection.PAYABLE,
            counterparty = "Hausverwaltung Musterweg (fiktiv)",
            reference = "MIETE-DEMO-001",
            itemDaysAgo = 12,
            dueDaysAgo = -18,
            amount = BigDecimal("240.00"),
            contraAccountId = ledgerIds.getValue("63000"),
            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
            note = "Saalmiete (Demodaten).",
        )
    }

    /** Mirrors `OpenItemService.createOpenItem`: insert, creation booking through the bridge, outcome, then the CREATE audit entry last. */
    private fun JdbcTransaction.createOpenItem(
        clock: SeedClock,
        actors: SeedActors,
        id: Uuid,
        direction: OpenItemDirection,
        counterparty: String,
        reference: String,
        itemDaysAgo: Int,
        dueDaysAgo: Int,
        amount: BigDecimal,
        contraAccountId: Uuid,
        sphere: GemeinnuetzigkeitSphere,
        note: String,
    ) {
        val itemDate = clock.dayAgo(itemDaysAgo)
        val dueDate = clock.dayAgo(dueDaysAgo)
        val counterpartyKey = CounterpartyKey.of(counterparty)
        OpenItemTable.insert {
            it[OpenItemTable.id] = id
            it[OpenItemTable.direction] = direction
            it[counterpartyName] = counterparty
            it[OpenItemTable.counterpartyKey] = counterpartyKey
            it[crmContactId] = null
            it[OpenItemTable.reference] = reference
            it[OpenItemTable.itemDate] = itemDate
            it[OpenItemTable.dueDate] = dueDate
            it[OpenItemTable.amount] = amount
            it[OpenItemTable.contraAccountId] = contraAccountId
            it[OpenItemTable.sphere] = sphere
            it[status] = OpenItemStatus.OPEN
            it[OpenItemTable.note] = note
            it[createdByMemberId] = actors.treasurer.id
            it[createdAt] = clock.utcAt(daysAgo = itemDaysAgo)
        }
        val outcome =
            OpenItemPostingBridge.postItemCreation(
                itemId = id,
                direction = direction,
                counterpartyName = counterparty,
                reference = reference,
                amount = amount,
                contraAccountId = contraAccountId,
                sphere = sphere,
                itemDate = itemDate,
                actorMemberId = actors.treasurer.id,
                actorRole = actors.treasurerActor.role,
            )
        check(outcome is OpenItemPostingOutcome.Posted) { "OpenItemPostingBridge did not book open item $id: $outcome" }
        applyOpenItemPostingOutcome(itemId = id, outcome = outcome)
        val row = OpenItemTable.selectAll().where { OpenItemTable.id eq id }.single()
        AuditLogRecorder.record(
            actorMemberId = actors.treasurer.id,
            actorRole = actors.treasurerActor.role,
            entityType = AuditEntityType.OPEN_ITEM,
            entityId = id,
            action = AuditAction.CREATE,
            before = null,
            after = Json.encodeToString(OpenItemSnapshot.serializer(), row.toOpenItemSnapshot()),
        )
    }

    // ---- SEPA -------------------------------------------------------------------------------------------------------------

    /**
     * An IBAN that no bank can ever have issued: country DE, bank code `00000000` (never allocated), valid ISO 7064 check
     * digits, so [IbanValidator] accepts it while it belongs to nobody.
     */
    private fun testIban(n: Int): String {
        val bban = "00000000" + n.toString().padStart(10, '0')
        // "DE00" rearranged: bban + D(13) E(14) + 00
        val remainder = BigInteger(bban + "131400").mod(BigInteger.valueOf(97))
        val check = (98 - remainder.toInt()).toString().padStart(2, '0')
        return "DE$check$bban"
    }

    private fun JdbcTransaction.seedSepa(
        clock: SeedClock,
        actors: SeedActors,
        sepaKey: ByteArray,
        candidates: List<OpenContribution>,
    ) {
        val secretBox = SecretBox(sepaKey)
        // Compliance acknowledgment by the fictitious admin, then the creditor identity. NO bank IBAN is set: that column
        // feeds the legacy bank-account backfill, and an empty one makes the file generation ask for it (honest demo state).
        SepaComplianceAcknowledgmentTable.insert {
            it[id] = Uuid.random()
            it[acknowledgedByMemberId] = actors.admin.id
            it[acknowledgedAt] = clock.now
            it[disclaimerVersion] = SepaComplianceDisclaimer.VERSION
            it[disclaimerSha256] = SepaComplianceDisclaimer.SHA256
        }
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[sepaDebitEnabled] = true
            it[sepaCreditorId] = DEMO_CREDITOR_ID
            it[sepaCreditorName] = SepaCharacterSet.sanitize(raw = StagingSeedData.ORGANIZATION_NAME, maxLength = 70)
        }

        val mandateHolders = candidates.take(MANDATE_COUNT)
        val mandateIdByMember = mutableMapOf<Uuid, Uuid>()
        mandateHolders.forEachIndexed { index, holder ->
            val mandateId = Uuid.random()
            val signatureDate = clock.dayAgo(60 + index * 7)
            val iban = testIban(n = index + 1)
            val reference = SepaMandateReferenceGenerator.generate(memberId = holder.memberId, signatureDate = signatureDate)
            val grantedAt = clock.utcAt(daysAgo = 60 + index * 7)
            SepaMandateTable.insert {
                it[id] = mandateId
                it[memberId] = holder.memberId
                it[mandateReference] = reference
                it[debtorName] = SepaCharacterSet.sanitize(raw = holder.memberName, maxLength = 70)
                it[debtorIbanCiphertext] = secretBox.seal(plaintext = IbanValidator.requireValid(iban), aad = mandateId.toString())
                it[debtorIbanSetAt] = grantedAt
                it[debtorIbanLast4] = IbanValidator.last4(iban)
                it[debtorBic] = null
                it[SepaMandateTable.signatureDate] = signatureDate
                it[sequenceType] = SepaSequenceType.FRST
                it[status] = SepaMandateStatus.ACTIVE
                it[SepaMandateTable.grantedAt] = grantedAt
                it[revokedAt] = null
                it[revokedBy] = null
                it[revocationReason] = null
                it[lastUsedAt] = null
                it[lastDebitedAmount] = null
                // Entered by the treasurer on the member's behalf, exactly like a paper mandate.
                it[createdBy] = actors.treasurer.id
            }
            mandateIdByMember[holder.memberId] = mandateId
            AuditLogRecorder.record(
                actorMemberId = actors.treasurer.id,
                actorRole = actors.treasurerActor.role,
                entityType = AuditEntityType.SEPA_MANDATE,
                entityId = mandateId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        SepaMandateSnapshot.serializer(),
                        SepaMandateSnapshot(
                            memberId = holder.memberId.toString(),
                            mandateReference = reference,
                            status = SepaMandateStatus.ACTIVE,
                            sequenceType = SepaSequenceType.FRST,
                            signatureDate = signatureDate,
                            lastUsedAt = null,
                            createdBySelf = false,
                        ),
                    ),
            )
        }

        // One DRAFT debit run over the first BATCH_ITEM_COUNT mandate holders: exactly the database side of createDebitBatch
        // (batch + items, contributions to DEBIT_SCHEDULED / SEPA_DEBIT, audit). No notifyBatch (mail), no file, no submission.
        val batchLines = mandateHolders.take(BATCH_ITEM_COUNT)
        val batchId = SeedIds.finance(0x10)
        val messageId = sepaBatchMessageId(clock.now)
        val settings = OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
        SepaDebitBatchTable.insert {
            it[id] = batchId
            it[SepaDebitBatchTable.messageId] = messageId
            it[paymentInfoId] = "$messageId-P1"
            it[requestedCollectionDate] = clock.today.plus(5, DateTimeUnit.DAY)
            it[sequenceType] = SepaSequenceType.FRST
            it[status] = SepaDebitBatchStatus.DRAFT
            it[itemCount] = batchLines.size
            it[totalAmount] = batchLines.fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }
            it[createdBy] = actors.treasurer.id
            it[createdAt] = clock.now
            it[creditorId] = settings[OrganizationSettingsTable.sepaCreditorId]
            it[creditorName] = settings[OrganizationSettingsTable.sepaCreditorName]
            it[creditorIban] = settings[OrganizationSettingsTable.bankIban]
            it[creditorBic] = settings[OrganizationSettingsTable.bankBic]
        }
        batchLines.forEach { line ->
            val mandateId = mandateIdByMember.getValue(line.memberId)
            val mandateReference =
                SepaMandateTable
                    .selectAll()
                    .where {
                        SepaMandateTable.id eq mandateId
                    }.single()[SepaMandateTable.mandateReference]
            SepaDebitItemTable.insert {
                it[id] = Uuid.random()
                it[SepaDebitItemTable.batchId] = batchId
                it[contributionId] = line.id
                it[SepaDebitItemTable.mandateId] = mandateId
                it[endToEndId] =
                    line.id
                        .toString()
                        .replace("-", "")
                        .uppercase()
                it[amount] = line.amount
                it[remittanceInformation] =
                    SepaCharacterSet.sanitize(
                        raw = "Mitgliedsbeitrag ${line.periodStart}-${line.periodEnd} Mandat $mandateReference",
                        maxLength = 140,
                    )
                it[status] = SepaDebitItemStatus.PENDING
                it[settleableAt] = null
                it[journalEntryId] = null
            }
            ContributionTable.update({ ContributionTable.id eq line.id }) {
                it[status] = ContributionStatus.DEBIT_SCHEDULED
                it[paymentMethod] = ContributionPaymentMethod.SEPA_DEBIT
                it[sepaMandateId] = mandateId
            }
        }
        AuditLogRecorder.record(
            actorMemberId = actors.treasurer.id,
            actorRole = actors.treasurerActor.role,
            entityType = AuditEntityType.SEPA_DEBIT_BATCH,
            entityId = batchId,
            action = AuditAction.CREATE,
            before = null,
            after =
                Json.encodeToString(
                    SepaDebitBatchSnapshot.serializer(),
                    batchSnapshotFrom(SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq batchId }.single()),
                ),
        )
    }

    // ---- dunning ----------------------------------------------------------------------------------------------------------

    private class LevelSeed(
        val number: Int,
        val name: String,
        val graceDays: Int,
        val responseDays: Int,
        val fee: BigDecimal?,
    )

    /** Three dunning levels and the compliance acknowledgment; the overdue contributions then appear as dunning cases. No notice is issued. */
    private fun JdbcTransaction.seedDunning(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        DunningComplianceAcknowledgmentTable.insert {
            it[id] = Uuid.random()
            it[acknowledgedByMemberId] = actors.admin.id
            it[acknowledgedAt] = clock.now
            it[disclaimerVersion] = DunningComplianceDisclaimer.VERSION
            it[disclaimerSha256] = DunningComplianceDisclaimer.SHA256
        }
        listOf(
            LevelSeed(number = 1, name = "Zahlungserinnerung", graceDays = 7, responseDays = 10, fee = null),
            LevelSeed(number = 2, name = "1. Mahnung", graceDays = 14, responseDays = 10, fee = BigDecimal("5.00")),
            LevelSeed(number = 3, name = "Letzte Mahnung", graceDays = 28, responseDays = 7, fee = BigDecimal("10.00")),
        ).forEach { level ->
            DunningLevelTable.insert {
                it[id] = Uuid.random()
                it[levelNumber] = level.number
                it[name] = level.name
                it[graceDays] = level.graceDays
                it[responseDays] = level.responseDays
                it[feeAmount] = level.fee
                it[active] = true
                it[createdAt] = clock.now
            }
            AuditLogRecorder.record(
                actorMemberId = actors.admin.id,
                actorRole = actors.adminActor.role,
                entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                entityId = ORGANIZATION_SETTINGS_ID,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        DunningLevelSnapshot.serializer(),
                        DunningLevelSnapshot(
                            levelNumber = level.number,
                            name = level.name,
                            graceDays = level.graceDays,
                            responseDays = level.responseDays,
                            feeAmount = level.fee,
                            active = true,
                        ),
                    ),
            )
        }
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[dunningEnabled] = true
        }
    }
}

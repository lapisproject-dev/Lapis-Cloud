package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotSourceTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.db.generated.TravelExpenseLtrPayoutTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConversionRateBasis
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PriceStatus
import network.lapis.cloud.shared.domain.ReferenceDateFallback
import network.lapis.cloud.shared.domain.ReferenceRateBasis
import network.lapis.cloud.shared.domain.TravelExpenseLtrBlocker
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Tuesday 2026-10-13, 11:00 in Berlin. */
private const val PINNED_NOW = "2026-10-13T09:00:00Z"
private val FRIDAY = LocalDate(2026, 9, 18)
private val SATURDAY = LocalDate(2026, 9, 19)
private val TODAY = LocalDate(2026, 10, 13)

/** A distinctive gold price: a digit scan of an audit payload is meaningful. */
private val GOLD = BigDecimal("2012.345678")

/**
 * V1.9.325 -- the refund of a travel expense report in LTR against a real schema (H2, and PostgreSQL for the constraints and the locking).
 * Data is created directly in the database; the clock is pinned. The object is driven directly (the role gate lives in the service and is covered by
 * `TravelExpenseLtrPayoutAuthzTest`).
 */
abstract class TravelExpenseLtrPayoutScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = PollTestData()
        val sepa = TravelExpenseSepaTestData(data)
        lateinit var treasurer: CurrentMember
        lateinit var board: CurrentMember
        lateinit var admin: CurrentMember
        lateinit var payable: Uuid
        lateinit var settlement: Uuid
        val snapshots = mutableListOf<Uuid>()
        val reports = mutableListOf<Uuid>()

        fun setConfig(
            peg: BigDecimal = BigDecimal("0.0001"),
            anchor: AnchorAsset = AnchorAsset.GOLD_XAU,
            currency: String = "EUR",
        ) = transaction {
            PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                it[anchorAsset] = anchor
                it[donationCurrency] = currency
                it[anchorUnitsPerLtr] = peg
                it[cacheTtlSeconds] = 172_800
                it[minQuorum] = 2
                it[outlierThresholdBps] = 300
                it[maxSpreadBps] = 1000
                it[conversionRateBasis] = ConversionRateBasis.CURRENT
                it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }

        fun setSwitch(
            enabled: Boolean,
            payableId: Uuid? = payable,
            settlementId: Uuid? = settlement,
        ) = transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[travelLtrPayoutEnabled] = enabled
                it[travelLtrPayableAccountId] = payableId
                it[travelLtrSettlementAccountId] = settlementId
            }
        }

        fun snapshot(
            at: String,
            price: BigDecimal = GOLD,
        ): Uuid {
            val id = Uuid.random()
            val ts = LocalDateTime.parse(at)
            transaction {
                PriceOracleSnapshotTable.insert {
                    it[PriceOracleSnapshotTable.id] = id
                    it[anchorAsset] = AnchorAsset.GOLD_XAU
                    it[donationCurrency] = "EUR"
                    it[medianPrice] = price
                    it[priceStatus] = PriceStatus.LIVE
                    it[sourceCount] = 2
                    it[sourcesUsed] = "goldapi,metalpriceapi"
                    it[priceTimestamp] = ts
                    it[capturedAt] = ts
                }
                repeat(2) { i ->
                    PriceOracleSnapshotSourceTable.insert {
                        it[PriceOracleSnapshotSourceTable.id] = Uuid.random()
                        it[snapshotId] = id
                        it[sourceId] = "src-$i"
                        it[rawPrice] = price
                        it[observedAt] = ts
                        it[included] = true
                    }
                }
            }
            snapshots += id
            return id
        }

        fun report(
            subject: Uuid,
            requester: Uuid = subject,
            totalCents: Long = 10_000,
            advanceCents: Long = 2_000,
            payout: TravelExpensePayoutChoice = TravelExpensePayoutChoice.LTR,
            travelTo: LocalDate = FRIDAY,
            approvedAgainst: Uuid? = null,
        ): Uuid {
            val id =
                sepa.executedReport(
                    subject = subject,
                    requester = requester,
                    totalCents = totalCents,
                    advanceCents = advanceCents,
                    iban = null,
                    holder = null,
                    payout = payout,
                    travelTo = travelTo,
                    ltrPayableCreditAccount = if (payout == TravelExpensePayoutChoice.LTR) approvedAgainst ?: payable else null,
                )
            reports += id
            return id
        }

        fun member(label: String) = data.member(label = label, role = AccountRole.MEMBER)

        fun preview(
            id: Uuid,
            actor: CurrentMember = treasurer,
        ) = TimeTestSupport.withServerClock(instant = PINNED_NOW) { TravelExpenseLtrPayout.preview(reportId = id, actor = actor) }

        fun credit(
            id: Uuid,
            actor: CurrentMember = treasurer,
            reason: String? = null,
            fingerprint: String? = null,
        ) = TimeTestSupport.withServerClock(instant = PINNED_NOW) {
            val fp =
                fingerprint ?: requireNotNull(TravelExpenseLtrPayout.preview(reportId = id, actor = actor).fingerprint) {
                    "no fingerprint, blocked"
                }
            TravelExpenseLtrPayout.execute(reportId = id, fingerprint = fp, repeatReason = reason, actor = actor)
        }

        fun payouts(id: Uuid) =
            transaction {
                TravelExpenseLtrPayoutTable
                    .selectAll()
                    .where { TravelExpenseLtrPayoutTable.reportId eq id }
                    .orderBy(TravelExpenseLtrPayoutTable.attemptNo)
                    .toList()
            }

        fun ledger(member: Uuid) = transaction { LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.memberId eq member }.toList() }

        beforeSpec {
            db.activate()
            sepa.configureOrganization()
            treasurer = sepa.actor(label = "treasurer", role = AccountRole.TREASURER)
            board = sepa.actor(label = "board", role = AccountRole.BOARD)
            admin = sepa.actor(label = "admin", role = AccountRole.ADMIN)
            payable = sepa.ledgerAccount(LedgerAccountType.LIABILITY)
            settlement = sepa.ledgerAccount(LedgerAccountType.INCOME)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest {
            setConfig()
            setSwitch(enabled = true)
            snapshot("2026-09-18T12:00:00")
        }
        afterTest {
            transaction {
                val rows = TravelExpenseLtrPayoutTable.selectAll().where { TravelExpenseLtrPayoutTable.reportId inList reports }.toList()
                val journals =
                    rows.flatMap {
                        listOfNotNull(
                            it[TravelExpenseLtrPayoutTable.journalEntryId],
                            it[TravelExpenseLtrPayoutTable.reversalJournalEntryId],
                        )
                    }
                val ledgers =
                    rows.flatMap {
                        listOfNotNull(
                            it[TravelExpenseLtrPayoutTable.ltrLedgerEntryId],
                            it[TravelExpenseLtrPayoutTable.reversalLedgerEntryId],
                        )
                    }
                TravelExpenseLtrPayoutTable.deleteWhere { TravelExpenseLtrPayoutTable.reportId inList reports }
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.id inList ledgers }
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList data.memberIds }
                PostingTable.deleteWhere { PostingTable.journalEntryId inList journals }
                JournalEntryTable.deleteWhere { JournalEntryTable.id inList journals }
                PriceOracleReferenceRateTable.deleteAll()
                PriceOracleSnapshotSourceTable.deleteAll()
                PriceOracleSnapshotTable.deleteAll()
                AuditLogEntryTable.deleteWhere { AuditLogEntryTable.entityType eq AuditEntityType.TRAVEL_EXPENSE_LTR_PAYOUT }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reports }
            }
            reports.clear()
            snapshots.clear()
        }
        afterSpec {
            if (!db.isPostgres) {
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[travelLtrPayoutEnabled] = false
                        it[travelLtrPayableAccountId] = null
                        it[travelLtrSettlementAccountId] = null
                    }
                    PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                        it[anchorAsset] = AnchorAsset.BITCOIN_BTC
                        it[donationCurrency] = "EUR"
                        it[anchorUnitsPerLtr] = BigDecimal("0.000001")
                        it[cacheTtlSeconds] = 300
                    }
                }
                sepa.cleanUp()
                data.cleanUp()
            }
            db.deactivate()
        }

        test("happy path: one issuance row, a balanced settlement booking, a complete receipt, an audit entry without amounts") {
            val subject = member("hp")
            val id = report(subject)
            val preview = preview(id)
            preview.blocker shouldBe null
            preview.ltrAmount?.compareTo(
                BigDecimal("80.00").divide(BigDecimal("0.0001").multiply(GOLD), 2, java.math.RoundingMode.HALF_UP),
            ) shouldBe
                0
            credit(id)

            val entries = ledger(subject)
            entries.size shouldBe 1
            entries.single()[LtrLedgerEntryTable.entryType] shouldBe LtrLedgerEntryType.TRAVEL_REFUND
            entries.single()[LtrLedgerEntryTable.referenceType] shouldBe LtrLedgerReferenceType.TRAVEL_EXPENSE
            entries.single()[LtrLedgerEntryTable.referenceId] shouldBe id
            val payout = payouts(id).single()
            payout[TravelExpenseLtrPayoutTable.ltrAmount] shouldBe entries.single()[LtrLedgerEntryTable.amountLtr]
            payout[TravelExpenseLtrPayoutTable.eurAmount].compareTo(BigDecimal("80.00")) shouldBe 0
            payout[TravelExpenseLtrPayoutTable.rateBasis] shouldBe ReferenceRateBasis.SNAPSHOT
            payout[TravelExpenseLtrPayoutTable.effectiveDate] shouldBe FRIDAY
            payout[TravelExpenseLtrPayoutTable.fallbackReason] shouldBe ReferenceDateFallback.NONE
            payout[TravelExpenseLtrPayoutTable.activeReportId] shouldBe id
            payout[TravelExpenseLtrPayoutTable.anchorPrice].compareTo(GOLD) shouldBe 0
            val postings =
                transaction {
                    PostingTable
                        .selectAll()
                        .where { PostingTable.journalEntryId eq payout[TravelExpenseLtrPayoutTable.journalEntryId] }
                        .toList()
                }
            postings.size shouldBe 2
            postings.single { it[PostingTable.side] == PostingSide.DEBIT }[PostingTable.ledgerAccountId] shouldBe payable
            postings.single { it[PostingTable.side] == PostingSide.CREDIT }[PostingTable.ledgerAccountId] shouldBe settlement
            postings.forEach { it[PostingTable.amount].compareTo(BigDecimal("80.00")) shouldBe 0 }
            val audit =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.TRAVEL_EXPENSE_LTR_PAYOUT) and
                                (AuditLogEntryTable.entityId eq payout[TravelExpenseLtrPayoutTable.id])
                        }.single()
                }
            val json = audit[AuditLogEntryTable.afterSnapshot].orEmpty()
            json shouldNotContain "2012"
            json shouldNotContain "80.00"
            json shouldNotContain entries.single()[LtrLedgerEntryTable.amountLtr].toPlainString()
        }

        test("a Saturday trip end uses the Friday rate and stores the fallback reason") {
            val id = report(member("sat"), travelTo = SATURDAY)
            credit(id)
            val payout = payouts(id).single()
            payout[TravelExpenseLtrPayoutTable.referenceDate] shouldBe SATURDAY
            payout[TravelExpenseLtrPayoutTable.effectiveDate] shouldBe FRIDAY
            payout[TravelExpenseLtrPayoutTable.fallbackReason] shouldBe ReferenceDateFallback.WEEKEND
        }

        test("a stale fingerprint credits nothing") {
            val subject = member("fp")
            val id = report(subject)
            val ex = shouldThrow<ConflictException> { credit(id, fingerprint = "0".repeat(64)) }
            ex.message shouldBe "rate_or_amount_changed"
            ledger(subject).size shouldBe 0
            payouts(id).size shouldBe 0
        }

        test("a second credit of an active credit is refused") {
            val id = report(member("dbl"))
            credit(id)
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.ALREADY_PAID
            shouldThrow<ConflictException> { credit(id, fingerprint = "0".repeat(64)) }
            payouts(id).size shouldBe 1
        }

        test("the subject and the requester may never credit, whatever their role") {
            val subjectAdmin = admin
            val ownReport = report(subjectAdmin.memberId)
            shouldThrow<ForbiddenException> { credit(ownReport, actor = subjectAdmin, fingerprint = "x") }
            preview(ownReport, actor = subjectAdmin).blocker shouldBe TravelExpenseLtrBlocker.OWN_REPORT
            val onBehalf = report(member("behalf"), requester = board.memberId)
            shouldThrow<ForbiddenException> { credit(onBehalf, actor = board, fingerprint = "x") }
            payouts(ownReport).size + payouts(onBehalf).size shouldBe 0
        }

        test("no rate: blocked, a manual rate resolves it, the recipient must not have confirmed it") {
            transaction {
                PriceOracleSnapshotSourceTable.deleteAll()
                PriceOracleSnapshotTable.deleteAll()
            }
            val subject = member("norate")
            val id = report(subject)
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.RATE_MISSING
            shouldThrow<ConflictException> { credit(id, fingerprint = "x") }
            val confirmed =
                TimeTestSupport.withServerClock(instant = PINNED_NOW) {
                    TravelExpenseLtrPayout.confirmRate(
                        reportId = id,
                        price = BigDecimal("2000"),
                        evidenceText = "LBMA PM fixing vom 18.09.2026, abgelesen auf der Seite der LBMA.",
                        acknowledgeDeviation = false,
                        actor = treasurer,
                    )
                }
            confirmed.blocker shouldBe null
            credit(id)
            val payout = payouts(id).single()
            payout[TravelExpenseLtrPayoutTable.rateBasis] shouldBe ReferenceRateBasis.MANUAL
            payout[TravelExpenseLtrPayoutTable.rateConfirmedBy] shouldBe treasurer.memberId
            payout[TravelExpenseLtrPayoutTable.referenceRateId] shouldBe
                transaction { PriceOracleReferenceRateTable.selectAll().single()[PriceOracleReferenceRateTable.id] }
            // The treasurer confirmed the rate of that day: a report of the treasurer himself is then blocked by the own-report rule first,
            // and a report whose RECIPIENT confirmed the rate is refused by the dedicated blocker.
            val recipientIsConfirmer = report(treasurer.memberId, requester = treasurer.memberId)
            preview(recipientIsConfirmer, actor = board).blocker shouldBe TravelExpenseLtrBlocker.SELF_CONFIRMED_RATE
            shouldThrow<ForbiddenException> { credit(recipientIsConfirmer, actor = board, fingerprint = "x") }
        }

        test("a trip that ends today cannot be credited yet") {
            val id = report(member("today"), travelTo = TODAY)
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.REFERENCE_DATE_NOT_PAST
        }

        test("blockers: not an LTR report, nothing payable, switch off, accounts missing, oracle not gold or not EUR") {
            val ltrNo = report(member("pay"), payout = TravelExpensePayoutChoice.PAYOUT)
            preview(ltrNo).blocker shouldBe TravelExpenseLtrBlocker.NOT_LTR
            val nothing = report(member("zero"), totalCents = 5_000, advanceCents = 5_000)
            preview(nothing).blocker shouldBe TravelExpenseLtrBlocker.NOTHING_PAYABLE
            val id = report(member("blk"))
            setSwitch(enabled = false)
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.DISABLED
            val disabled = shouldThrow<ConflictException> { credit(id, fingerprint = "x") }
            disabled.message shouldBe LTR_PAYOUT_DISABLED
            setSwitch(enabled = true, settlementId = null)
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.ACCOUNTS_MISSING
            setSwitch(enabled = true)
            setConfig(anchor = AnchorAsset.BITCOIN_BTC, peg = BigDecimal("0.000001"))
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.ORACLE_NOT_READY
            setConfig(currency = "USD")
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.ORACLE_NOT_EUR
        }

        test("an amount that rounds to less than 0.01 LTR is refused") {
            setConfig(peg = BigDecimal("1"))
            val id = report(member("dust"), totalCents = 1, advanceCents = 0)
            preview(id).blocker shouldBe TravelExpenseLtrBlocker.AMOUNT_TOO_SMALL
        }

        test("the SEPA export never sees an LTR report") {
            val id = report(member("sepa"))
            val row = sepa.report(id)
            TravelExpenseSepaExport(SEPA_TEST_KEY).blockerOf(row = row, actor = board, bookedBankCreditCents = null) shouldBe
                network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker.NOT_PAYOUT
        }

        test("chk_ter_ltr_not_sepa: an LTR report can never carry an export mark or the outside-booked flag") {
            val id = report(member("chk"))
            shouldThrow<Exception> {
                transaction { TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq id }) { it[paidOutsideLapis] = true } }
            }
        }

        test("reversal: exact counter entries, then a new credit needs a reason of 10..500 characters") {
            val subject = member("rev")
            val id = report(subject)
            credit(id)
            val first = payouts(id).single()
            val payoutId = first[TravelExpenseLtrPayoutTable.id]
            shouldThrow<ForbiddenException> {
                TravelExpenseLtrPayout.reverse(
                    reportId = id,
                    payoutId = payoutId,
                    reason = "Falsche Abrechnung",
                    actor = CurrentMember(memberId = subject, role = AccountRole.BOARD, status = MemberStatus.ACTIVE),
                )
            }
            TimeTestSupport.withServerClock(instant = PINNED_NOW) {
                TravelExpenseLtrPayout.reverse(reportId = id, payoutId = payoutId, reason = "Falsche Abrechnung", actor = board)
            }
            ledger(subject).sumOf { it[LtrLedgerEntryTable.amountLtr] }.compareTo(BigDecimal.ZERO) shouldBe 0
            val reversed = payouts(id).single()
            reversed[TravelExpenseLtrPayoutTable.activeReportId] shouldBe null
            reversed[TravelExpenseLtrPayoutTable.reversedBy] shouldBe board.memberId
            val counter =
                transaction {
                    PostingTable
                        .selectAll()
                        .where {
                            PostingTable.journalEntryId eq
                                reversed[TravelExpenseLtrPayoutTable.reversalJournalEntryId]!!
                        }.toList()
                }
            counter.single { it[PostingTable.side] == PostingSide.CREDIT }[PostingTable.ledgerAccountId] shouldBe payable
            counter.single { it[PostingTable.side] == PostingSide.DEBIT }[PostingTable.ledgerAccountId] shouldBe settlement
            shouldThrow<ConflictException> {
                TimeTestSupport.withServerClock(instant = PINNED_NOW) {
                    TravelExpenseLtrPayout.reverse(reportId = id, payoutId = payoutId, reason = "Noch einmal stornieren", actor = board)
                }
            }.message shouldBe "ALREADY_REVERSED"

            preview(id).requiresRepeatReason shouldBe true
            shouldThrow<ConflictException> { credit(id) }
            shouldThrow<ConflictException> { credit(id, reason = "zu kurz") }
            shouldThrow<ConflictException> { credit(id, reason = "x".repeat(501)) }
            credit(id, reason = "Korrigierte Abrechnung vom Vorstand")
            val second = payouts(id)
            second.size shouldBe 2
            second.last()[TravelExpenseLtrPayoutTable.attemptNo] shouldBe 2
            second.last()[TravelExpenseLtrPayoutTable.ltrAmount] shouldBe first[TravelExpenseLtrPayoutTable.ltrAmount]
        }

        test("a reversal is refused when the member no longer holds the LTR, and changes nothing") {
            val subject = member("spent")
            val id = report(subject)
            credit(id)
            transaction {
                LtrLedgerEntryTable.insert {
                    it[LtrLedgerEntryTable.id] = Uuid.random()
                    it[memberId] = subject
                    it[entryType] = LtrLedgerEntryType.PEER_TRANSFER_OUT
                    it[amountLtr] = BigDecimal("-300.00")
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = null
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 10, 13, 9, 0)
                }
            }
            val before = ledger(subject).size
            shouldThrow<ConflictException> {
                TimeTestSupport.withServerClock(instant = PINNED_NOW) {
                    TravelExpenseLtrPayout.reverse(
                        reportId = id,
                        payoutId = payouts(id).single()[TravelExpenseLtrPayoutTable.id],
                        reason = "Falsche Abrechnung",
                        actor = board,
                    )
                }
            }.message shouldBe LTR_BALANCE_INSUFFICIENT
            ledger(subject).size shouldBe before
            payouts(id).single()[TravelExpenseLtrPayoutTable.reversedAt] shouldBe null
        }

        test("switching on needs gold in EUR and two suitable accounts; switching off always works") {
            setSwitch(enabled = false)
            TravelExpenseLtrPayout.setEnabled(enabled = true, actor = admin)
            transaction {
                OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.travelLtrPayoutEnabled]
            } shouldBe true
            TravelExpenseLtrPayout.setEnabled(enabled = false, actor = admin)
            setConfig(anchor = AnchorAsset.BITCOIN_BTC, peg = BigDecimal("0.000001"))
            shouldThrow<ConflictException> { TravelExpenseLtrPayout.setEnabled(enabled = true, actor = admin) }
            setConfig()
            setSwitch(enabled = false, settlementId = null)
            shouldThrow<ConflictException> { TravelExpenseLtrPayout.setEnabled(enabled = true, actor = admin) }
            val asset = sepa.ledgerAccount(LedgerAccountType.ASSET)
            setSwitch(enabled = false, settlementId = asset)
            shouldThrow<ConflictException> { TravelExpenseLtrPayout.setEnabled(enabled = true, actor = admin) }
            setSwitch(enabled = false, settlementId = payable)
            shouldThrow<ConflictException> { TravelExpenseLtrPayout.setEnabled(enabled = true, actor = admin) }
        }

        test("the settlement debits the liability the approval credited, even if the payable account was switched afterwards") {
            val subject = member("switch")
            val id = report(subject)
            val other = sepa.ledgerAccount(LedgerAccountType.LIABILITY)
            setSwitch(enabled = true, payableId = other)
            credit(id)
            val journal = payouts(id).single()[TravelExpenseLtrPayoutTable.journalEntryId]
            val postings = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journal }.toList() }
            postings.single { it[PostingTable.side] == PostingSide.DEBIT }[PostingTable.ledgerAccountId] shouldBe payable
            postings.single { it[PostingTable.side] == PostingSide.CREDIT }[PostingTable.ledgerAccountId] shouldBe settlement
        }

        test("candidates lists a ready report with no blocker and drops it once the LTR is credited") {
            val id = report(member("cand"))
            val before = TravelExpenseLtrPayout.candidates(treasurer).filter { it.first[TravelExpenseReportTable.id] == id }
            before.size shouldBe 1
            before.single().second shouldBe null
            credit(id)
            TravelExpenseLtrPayout.candidates(treasurer).none { it.first[TravelExpenseReportTable.id] == id } shouldBe true
        }

        test("candidates attaches the blocker of the own report") {
            val own = report(subject = treasurer.memberId)
            val row = TravelExpenseLtrPayout.candidates(treasurer).single { it.first[TravelExpenseReportTable.id] == own }
            row.second shouldBe TravelExpenseLtrBlocker.OWN_REPORT
        }

        test("saving the settings while the switch is on re-validates the LTR accounts") {
            val asset = sepa.ledgerAccount(LedgerAccountType.ASSET)
            shouldThrow<ConflictException> {
                transaction { TravelExpenseLtrPayout.requireValidAccounts(payableIdOrNull = payable, settlementIdOrNull = asset) }
            }.message shouldBe
                "LTR_ENABLE_SETTLEMENT_TYPE"
            shouldThrow<ConflictException> {
                transaction {
                    TravelExpenseLtrPayout.requireValidAccounts(
                        payableIdOrNull = asset,
                        settlementIdOrNull = settlement,
                    )
                }
            }.message shouldBe
                "LTR_ENABLE_PAYABLE_NOT_LIABILITY"
            shouldThrow<ConflictException> {
                transaction {
                    TravelExpenseLtrPayout.requireValidAccounts(
                        payableIdOrNull = payable,
                        settlementIdOrNull = payable,
                    )
                }
            }.message shouldBe
                "LTR_ENABLE_ACCOUNTS_SAME"
            shouldThrow<ConflictException> {
                transaction {
                    TravelExpenseLtrPayout.requireValidAccounts(
                        payableIdOrNull = null,
                        settlementIdOrNull = settlement,
                    )
                }
            }.message shouldBe
                "LTR_ENABLE_ACCOUNTS_MISSING"
            transaction { TravelExpenseLtrPayout.requireValidAccounts(payableIdOrNull = payable, settlementIdOrNull = settlement) }
        }
    })

class TravelExpenseLtrPayoutTest : TravelExpenseLtrPayoutScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TravelExpenseLtrPayoutPostgresTest : TravelExpenseLtrPayoutScenarios(TestDatabase.Postgres())

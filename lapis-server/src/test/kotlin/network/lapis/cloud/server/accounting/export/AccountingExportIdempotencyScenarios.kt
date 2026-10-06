package network.lapis.cloud.server.accounting.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.server.db.generated.AccountingExportRunTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportRunStatus
import network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * A provider double WITH a lookup: [providerSide] is what "exists at the provider" by voucher number; a successful push adds to it.
 * [pushCalls]/[lookupCalls] prove exactly how often the provider was touched.
 */
internal class LookupFakeAdapter : AccountingExportProviderAdapter {
    override val provider: AccountingExportProvider = AccountingExportProvider.LEXOFFICE
    var pushCalls = 0
    var lookupCalls = 0
    val pushOutcomes = ArrayDeque<VoucherPushOutcome>()
    val lookupOutcomes = ArrayDeque<VoucherLookupOutcome>()
    val providerSide = mutableMapOf<String, List<FoundVoucher>>()

    override suspend fun testConnection(token: String): ConnectionTestOutcome = ConnectionTestOutcome.Success(companyName = "Test e.V.")

    override suspend fun listCategories(token: String): CategoryListOutcome = CategoryListOutcome.Success(categories = emptyList())

    override suspend fun pushVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome {
        pushCalls++
        val outcome = pushOutcomes.removeFirstOrNull() ?: VoucherPushOutcome.Succeeded(externalVoucherId = "ext-${voucher.voucherNumber}")
        if (outcome is VoucherPushOutcome.Succeeded) {
            providerSide[voucher.voucherNumber] =
                listOf(
                    FoundVoucher(
                        id = outcome.externalVoucherId,
                        voucherType = "salesinvoice",
                        voucherDate = voucher.voucherDate,
                        totalAmount = voucher.grossAmount,
                    ),
                )
        }
        return outcome
    }

    override suspend fun findVouchersByNumber(
        token: String,
        voucherNumber: String,
    ): VoucherLookupOutcome {
        lookupCalls++
        lookupOutcomes.removeFirstOrNull()?.let { return it }
        return providerSide[voucherNumber]?.let { VoucherLookupOutcome.Found(it) } ?: VoucherLookupOutcome.NotFound
    }
}

private fun LocalDateTime.plus(duration: Duration): LocalDateTime = toInstant(TimeZone.UTC).plus(duration).toLocalDateTime(TimeZone.UTC)

/**
 * V1.9.65 -- idempotency of the accounting export: fencing, check-before-create, automatic reconciliation. Provider is a double,
 * the database is real (H2 and PostgreSQL), time is driven by an explicit clock.
 */
abstract class AccountingExportIdempotencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = ExportFixtures()
        var now = LocalDateTime(2026, 10, 1, 10, 0)

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterEach {
            fx.cleanup()
            now = LocalDateTime(2026, 10, 1, 10, 0)
        }
        afterSpec { db.deactivate() }

        fun connect(adapter: LookupFakeAdapter): AccountingExportPoller {
            val box = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))
            AccountingExportStore.upsertToken(
                provider = AccountingExportProvider.LEXOFFICE,
                token = "test-token-1234567890abcdef",
                secretBox = box,
                now = now,
            )
            return AccountingExportPoller(
                config = AccountingExportConfig(enabled = true, pollIntervalSeconds = 2, secretEncryptionKey = null),
                secretBox = box,
                adaptersByProvider = mapOf(AccountingExportProvider.LEXOFFICE to adapter),
                clock = { now },
            )
        }

        fun row(itemId: Uuid) =
            transaction { AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq itemId }.single() }

        fun status(itemId: Uuid) = row(itemId)[AccountingExportItemTable.status]

        fun runStatus(runId: Uuid) =
            transaction {
                AccountingExportRunTable
                    .selectAll()
                    .where {
                        AccountingExportRunTable.id eq runId
                    }.single()[AccountingExportRunTable.status]
            }

        test("an ambiguous timeout leaves the item UNKNOWN and the voucher is pushed exactly once, even across later ticks") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now)
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            adapter.pushOutcomes += VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
            poller.tick()
            status(items.single()) shouldBe AccountingExportItemStatus.UNKNOWN
            repeat(3) { poller.tick() }
            adapter.pushCalls shouldBe 1
        }

        test("a second run for an entry that already succeeded is skipped without any provider call") {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now)
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            poller.tick()
            status(items.single()) shouldBe AccountingExportItemStatus.SUCCEEDED
            val entryId = row(items.single())[AccountingExportItemTable.journalEntryId]
            AccountingExportStore.recomputeRunCounts(runId = runId, now = now)
            val second =
                AccountingExportStore.createRun(
                    provider = AccountingExportProvider.LEXOFFICE,
                    from = kotlinx.datetime.LocalDate(2026, 1, 1),
                    to = kotlinx.datetime.LocalDate(2026, 1, 31),
                    startedBy = actor,
                    now = now,
                    vouchers = listOf(fx.plannedVoucher(journalEntryId = entryId)),
                )
            fx.track(second)
            val pushesBefore = adapter.pushCalls
            val lookupsBefore = adapter.lookupCalls
            poller.tick()
            status(fx.itemIdsOf(second).single()) shouldBe AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED
            adapter.pushCalls shouldBe pushesBefore
            adapter.lookupCalls shouldBe lookupsBefore
        }

        test("restore of an older database: the voucher already exists at the provider, so the new run adopts it and pushes nothing") {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now)
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            poller.tick()
            adapter.pushCalls shouldBe 1
            val entryId = row(items.single())[AccountingExportItemTable.journalEntryId]
            // The restored backup does not contain the SUCCEEDED row.
            transaction {
                exec("DELETE FROM accounting_export_item WHERE run_id = '$runId'")
                exec("DELETE FROM accounting_export_run WHERE id = '$runId'")
            }
            val newRun =
                AccountingExportStore.createRun(
                    provider = AccountingExportProvider.LEXOFFICE,
                    from = kotlinx.datetime.LocalDate(2026, 1, 1),
                    to = kotlinx.datetime.LocalDate(2026, 1, 31),
                    startedBy = actor,
                    now = now,
                    vouchers = listOf(fx.plannedVoucher(journalEntryId = entryId)),
                )
            fx.track(newRun)
            poller.tick()
            val item = row(fx.itemIdsOf(newRun).single())
            item[AccountingExportItemTable.status] shouldBe AccountingExportItemStatus.SUCCEEDED
            item[AccountingExportItemTable.errorMessage].orEmpty() shouldContain "automatisch abgeglichen"
            adapter.pushCalls shouldBe 1
        }

        test("a rejected voucher in the middle of a run does not stop the others and the counts add up") {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now, items = 3)
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            adapter.pushOutcomes += VoucherPushOutcome.Succeeded(externalVoucherId = "ext-a")
            adapter.pushOutcomes += VoucherPushOutcome.Rejected(errorCode = "400", message = "abgelehnt")
            adapter.pushOutcomes += VoucherPushOutcome.Succeeded(externalVoucherId = "ext-c")
            poller.tick()
            items.map { status(it) }.count { it == AccountingExportItemStatus.SUCCEEDED } shouldBe 2
            items.map { status(it) }.count { it == AccountingExportItemStatus.FAILED } shouldBe 1
            adapter.pushCalls shouldBe 3
            runStatus(runId) shouldBe AccountingExportRunStatus.COMPLETED_WITH_ERRORS
        }

        test("fencing: a retry written after abortRun took the item away is discarded and the item is never sent") {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now)
            val itemId = items.single()
            val claim = AccountingExportStore.claim(id = itemId, now = now).let { requireNotNull(it) }
            AccountingExportStore.abortRun(runId = runId, now = now)
            status(itemId) shouldBe AccountingExportItemStatus.UNKNOWN
            val late =
                AccountingExportStore.markRetryScheduled(
                    id = itemId,
                    claimedGeneration = claim.claimGeneration,
                    nextAttemptAt = now,
                    errorCode = "RATE_LIMITED",
                    errorMessage = null,
                )
            late shouldBe AccountingExportStore.FencedWrite.LOST_FENCE
            status(itemId) shouldBe AccountingExportItemStatus.UNKNOWN
            val adapter = LookupFakeAdapter()
            connect(adapter).tick()
            adapter.pushCalls shouldBe 0
        }

        test("fencing: a late failure never overwrites UNKNOWN, a late success beats UNKNOWN") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now, items = 2)
            val (a, b) = items
            val claimA = requireNotNull(AccountingExportStore.claim(id = a, now = now))
            val claimB = requireNotNull(AccountingExportStore.claim(id = b, now = now))
            // The reaper declares both unknown while their senders are still working.
            AccountingExportStore.reapStaleClaims(staleCutoff = now.plus(1.minutes), now = now.plus(1.minutes))
            status(a) shouldBe AccountingExportItemStatus.UNKNOWN
            status(b) shouldBe AccountingExportItemStatus.UNKNOWN

            AccountingExportStore.markFailed(
                id = a,
                claimedGeneration = claimA.claimGeneration,
                errorCode = "X",
                errorMessage = null,
                now = now,
            ) shouldBe
                AccountingExportStore.FencedWrite.LOST_FENCE
            status(a) shouldBe AccountingExportItemStatus.UNKNOWN

            AccountingExportStore.markSucceeded(
                id = b,
                claimedGeneration = claimB.claimGeneration,
                provider = AccountingExportProvider.LEXOFFICE,
                journalEntryId = claimB.journalEntryId,
                externalVoucherId = "ext-late",
                now = now,
            ) shouldBe AccountingExportStore.FencedWrite.APPLIED
            row(b)[AccountingExportItemTable.status] shouldBe AccountingExportItemStatus.SUCCEEDED
            row(b)[AccountingExportItemTable.exportedKey].orEmpty() shouldContain "LEXOFFICE"
        }

        test("fencing: a stale token cannot write after the item was claimed again") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now)
            val itemId = items.single()
            val first = requireNotNull(AccountingExportStore.claim(id = itemId, now = now))
            AccountingExportStore.markRetryScheduled(
                id = itemId,
                claimedGeneration = first.claimGeneration,
                nextAttemptAt = now,
                errorCode = "E",
                errorMessage = null,
            ) shouldBe
                AccountingExportStore.FencedWrite.APPLIED
            val second = requireNotNull(AccountingExportStore.claim(id = itemId, now = now))
            second.claimGeneration shouldBe first.claimGeneration + 1
            AccountingExportStore.markFailed(
                id = itemId,
                claimedGeneration = first.claimGeneration,
                errorCode = "OLD",
                errorMessage = null,
                now = now,
            ) shouldBe
                AccountingExportStore.FencedWrite.LOST_FENCE
            status(itemId) shouldBe AccountingExportItemStatus.SENDING
        }

        test(
            "fencing: a token from before abortRun + resolveUnknown + retryFailed cannot write after the item was claimed again (no ABA)",
        ) {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now)
            val itemId = items.single()
            val first = requireNotNull(AccountingExportStore.claim(id = itemId, now = now))
            AccountingExportStore.abortRun(runId = runId, now = now)
            AccountingExportStore.resolveUnknown(
                id = itemId,
                resolution = AccountingExportUnknownItemResolution.CONFIRMED_NOT_SENT,
                externalVoucherId = null,
                now = now,
            )
            AccountingExportStore.retryFailed(runId = runId, now = now)
            val second = requireNotNull(AccountingExportStore.claim(id = itemId, now = now))
            second.claimGeneration shouldBe first.claimGeneration + 1
            AccountingExportStore.markSucceeded(
                id = itemId,
                claimedGeneration = first.claimGeneration,
                provider = AccountingExportProvider.LEXOFFICE,
                journalEntryId = first.journalEntryId,
                externalVoucherId = "late",
                now = now,
            ) shouldBe AccountingExportStore.FencedWrite.LOST_FENCE
            AccountingExportStore.markFailed(
                id = itemId,
                claimedGeneration = first.claimGeneration,
                errorCode = "OLD",
                errorMessage = null,
                now = now,
            ) shouldBe AccountingExportStore.FencedWrite.LOST_FENCE
            status(itemId) shouldBe AccountingExportItemStatus.SENDING
        }

        test("a hit with another amount is not adopted: UNKNOWN with PROVIDER_DUPLICATE_OR_MISMATCH and nothing is sent") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now)
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            adapter.lookupOutcomes +=
                VoucherLookupOutcome.Found(
                    listOf(
                        FoundVoucher(
                            id = "other",
                            voucherType = "salesinvoice",
                            voucherDate = FIXTURE_ENTRY_DATE,
                            totalAmount = BigDecimal("99.00"),
                        ),
                    ),
                )
            poller.tick()
            val item = row(items.single())
            item[AccountingExportItemTable.status] shouldBe AccountingExportItemStatus.UNKNOWN
            item[AccountingExportItemTable.errorCode] shouldBe "PROVIDER_DUPLICATE_OR_MISMATCH"
            adapter.pushCalls shouldBe 0
        }

        test("several hits are not adopted either") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now)
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            val hit = FoundVoucher(id = "a", voucherType = "salesinvoice", voucherDate = FIXTURE_ENTRY_DATE, totalAmount = FIXTURE_AMOUNT)
            adapter.lookupOutcomes += VoucherLookupOutcome.Found(listOf(hit, hit.copy(id = "b")))
            poller.tick()
            status(items.single()) shouldBe AccountingExportItemStatus.UNKNOWN
            adapter.pushCalls shouldBe 0
        }

        test("a failed lookup sends nothing, schedules a retry and becomes FAILED only after the attempt budget") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now)
            val itemId = items.single()
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            repeat(3) { adapter.lookupOutcomes += VoucherLookupOutcome.Failed(errorCode = "NETWORK_ERROR") }
            poller.tick()
            row(itemId)[AccountingExportItemTable.status] shouldBe AccountingExportItemStatus.PENDING
            row(itemId)[AccountingExportItemTable.errorCode] shouldBe "LOOKUP_NETWORK_ERROR"
            (row(itemId)[AccountingExportItemTable.nextAttemptAt] != null) shouldBe true
            now = now.plus(1.minutes)
            poller.tick()
            now = now.plus(1.minutes)
            poller.tick()
            status(itemId) shouldBe AccountingExportItemStatus.FAILED
            adapter.pushCalls shouldBe 0
        }

        test("an item of a run that is no longer RUNNING is never due") {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now)
            // A late write resurrected a PENDING item inside an aborted run.
            transaction {
                AccountingExportRunTable.update({ AccountingExportRunTable.id eq runId }) {
                    it[status] = AccountingExportRunStatus.ABORTED
                    it[activeKey] = null
                }
            }
            AccountingExportStore.duePendingItemIds(providers = listOf(AccountingExportProvider.LEXOFFICE), now = now, limit = 10) shouldBe
                emptyList()
            val adapter = LookupFakeAdapter()
            connect(adapter).tick()
            status(items.single()) shouldBe AccountingExportItemStatus.PENDING
            adapter.pushCalls shouldBe 0
        }

        test("reconciliation: NOT_FOUND keeps the item UNKNOWN, counts the check, and stops after six lookups") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now)
            val itemId = items.single()
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            adapter.pushOutcomes += VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
            poller.tick()
            status(itemId) shouldBe AccountingExportItemStatus.UNKNOWN
            val lookupsAfterSend = adapter.lookupCalls

            now = now.plus(5.minutes)
            poller.tick()
            adapter.lookupCalls shouldBe lookupsAfterSend // younger than ten minutes: not judged yet

            now = now.plus(6.minutes)
            poller.tick()
            adapter.lookupCalls shouldBe lookupsAfterSend + 1
            row(itemId)[AccountingExportItemTable.reconcileChecks] shouldBe 1
            row(itemId)[AccountingExportItemTable.reconcileLastResult] shouldBe "NOT_FOUND"
            status(itemId) shouldBe AccountingExportItemStatus.UNKNOWN

            repeat(8) {
                now = now.plus(25.hours)
                poller.tick()
            }
            adapter.lookupCalls shouldBe lookupsAfterSend + 6
            row(itemId)[AccountingExportItemTable.reconcileChecks] shouldBe 6
            status(itemId) shouldBe AccountingExportItemStatus.UNKNOWN
        }

        test("reconciliation: an oldest UNKNOWN item of a disconnected provider does not starve a younger due item of another provider") {
            val actor = fx.newMember()
            val (_, sevItems) = fx.newRun(actor = actor, now = now, provider = AccountingExportProvider.SEVDESK)
            val sevId = sevItems.single()
            testMarkUnknown(id = sevId, errorCode = "RESPONSE_LOST", now = now)
            now = now.plus(1.hours)
            val (_, lexItems) = fx.newRun(actor = actor, now = now)
            val lexId = lexItems.single()
            testMarkUnknown(id = lexId, errorCode = "RESPONSE_LOST", now = now)

            val adapter = LookupFakeAdapter()
            val box = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))
            // Only lexoffice has a token; sevDesk was disconnected.
            AccountingExportStore.upsertToken(
                provider = AccountingExportProvider.LEXOFFICE,
                token = "test-token-1234567890abcdef",
                secretBox = box,
                now = now,
            )
            val poller =
                AccountingExportPoller(
                    config = AccountingExportConfig(enabled = true, pollIntervalSeconds = 2, secretEncryptionKey = null),
                    secretBox = box,
                    adaptersByProvider =
                        mapOf(
                            AccountingExportProvider.LEXOFFICE to adapter,
                            AccountingExportProvider.SEVDESK to adapter,
                        ),
                    clock = { now },
                )
            now = now.plus(15.minutes)
            poller.tick() // sevDesk item is the oldest candidate: no token -> must record a check, not return silently
            row(sevId)[AccountingExportItemTable.reconcileChecks] shouldBe 1
            row(sevId)[AccountingExportItemTable.reconcileLastResult] shouldBe "NO_TOKEN"
            adapter.lookupCalls shouldBe 0

            poller.tick() // next tick: the sevDesk item is not due again, the lexoffice item is reconciled
            adapter.lookupCalls shouldBe 1
            row(lexId)[AccountingExportItemTable.reconcileChecks] shouldBe 1
            row(lexId)[AccountingExportItemTable.reconcileLastResult] shouldBe "NOT_FOUND"
        }

        test("reconciliation: a later exact hit resolves the item to SUCCEEDED and finalizes the run") {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now)
            val itemId = items.single()
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            adapter.pushOutcomes += VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
            poller.tick()
            runStatus(runId) shouldBe AccountingExportRunStatus.COMPLETED_WITH_ERRORS
            val voucherNumber = row(itemId)[AccountingExportItemTable.voucherNumber]
            adapter.providerSide[voucherNumber] =
                listOf(
                    FoundVoucher(
                        id = "ext-found",
                        voucherType = "salesinvoice",
                        voucherDate = FIXTURE_ENTRY_DATE,
                        totalAmount = FIXTURE_AMOUNT,
                    ),
                )
            now = now.plus(11.minutes)
            poller.tick()
            val item = row(itemId)
            item[AccountingExportItemTable.status] shouldBe AccountingExportItemStatus.SUCCEEDED
            item[AccountingExportItemTable.externalVoucherId] shouldBe "ext-found"
            item[AccountingExportItemTable.errorMessage].orEmpty() shouldStartWith "Automatisch abgeglichen"
            runStatus(runId) shouldBe AccountingExportRunStatus.COMPLETED
            adapter.pushCalls shouldBe 1
        }

        test("reconciliation: several hits stay UNKNOWN as PROVIDER_DUPLICATE and are not looked up again") {
            val actor = fx.newMember()
            val (_, items) = fx.newRun(actor = actor, now = now)
            val itemId = items.single()
            val adapter = LookupFakeAdapter()
            val poller = connect(adapter)
            adapter.pushOutcomes += VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
            poller.tick()
            val hit = FoundVoucher(id = "a", voucherType = "salesinvoice", voucherDate = FIXTURE_ENTRY_DATE, totalAmount = FIXTURE_AMOUNT)
            adapter.lookupOutcomes += VoucherLookupOutcome.Found(listOf(hit, hit.copy(id = "b")))
            now = now.plus(11.minutes)
            poller.tick()
            row(itemId)[AccountingExportItemTable.errorCode] shouldBe "PROVIDER_DUPLICATE"
            status(itemId) shouldBe AccountingExportItemStatus.UNKNOWN
            val lookups = adapter.lookupCalls
            now = now.plus(48.hours)
            poller.tick()
            adapter.lookupCalls shouldBe lookups
        }
    })

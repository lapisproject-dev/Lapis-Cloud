package network.lapis.cloud.server.accounting.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.server.db.generated.AccountingExportRunTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportRunStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Thread-safe provider double: a slow push, a per-voucher-number push counter and a lookup over what was pushed. */
private class ConcurrentFakeAdapter : AccountingExportProviderAdapter {
    override val provider: AccountingExportProvider = AccountingExportProvider.LEXOFFICE
    val pushesByNumber = ConcurrentHashMap<String, AtomicInteger>()
    private val providerSide = ConcurrentHashMap<String, FoundVoucher>()

    override suspend fun testConnection(token: String): ConnectionTestOutcome = ConnectionTestOutcome.Success(companyName = "Test e.V.")

    override suspend fun listCategories(token: String): CategoryListOutcome = CategoryListOutcome.Success(categories = emptyList())

    override suspend fun pushVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome {
        pushesByNumber.computeIfAbsent(voucher.voucherNumber) { AtomicInteger() }.incrementAndGet()
        delay(SLOW_PUSH_MILLIS)
        providerSide[voucher.voucherNumber] =
            FoundVoucher(
                id = "ext-${voucher.voucherNumber}",
                voucherType = "salesinvoice",
                voucherDate = voucher.voucherDate,
                totalAmount = voucher.grossAmount,
            )
        return VoucherPushOutcome.Succeeded(externalVoucherId = "ext-${voucher.voucherNumber}")
    }

    override suspend fun findVouchersByNumber(
        token: String,
        voucherNumber: String,
    ): VoucherLookupOutcome = providerSide[voucherNumber]?.let { VoucherLookupOutcome.Found(listOf(it)) } ?: VoucherLookupOutcome.NotFound

    private companion object {
        const val SLOW_PUSH_MILLIS = 40L
    }
}

private const val ITEMS = 20
private const val THREADS = 6
private const val BUDGET_MILLIS = 30_000L

/**
 * V1.9.65 -- two pollers (the multi-instance case) ticking on several threads each: the compare-and-set claim plus the fenced writes
 * must give every voucher exactly one push, no unique violation, no stuck item.
 */
abstract class AccountingExportConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = ExportFixtures()
        val now: () -> LocalDateTime = { LocalDateTime(2026, 10, 1, 10, 0) }

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterEach { fx.cleanup() }
        afterSpec { db.deactivate() }

        test("two pollers on several threads push each of 20 vouchers exactly once") {
            val actor = fx.newMember()
            val (runId, items) = fx.newRun(actor = actor, now = now(), items = ITEMS)
            val adapter = ConcurrentFakeAdapter()
            val box = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))
            AccountingExportStore.upsertToken(
                provider = AccountingExportProvider.LEXOFFICE,
                token = "test-token-1234567890abcdef",
                secretBox = box,
                now = now(),
            )

            fun poller() =
                AccountingExportPoller(
                    config = AccountingExportConfig(enabled = true, pollIntervalSeconds = 2, secretEncryptionKey = null),
                    secretBox = box,
                    adaptersByProvider = mapOf(AccountingExportProvider.LEXOFFICE to adapter),
                    clock = now,
                )
            val pollers = listOf(poller(), poller())

            fun pending(): Long =
                transaction {
                    AccountingExportItemTable
                        .selectAll()
                        .where { AccountingExportItemTable.runId eq runId }
                        .count { it[AccountingExportItemTable.status] != AccountingExportItemStatus.SUCCEEDED }
                        .toLong()
                }

            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                val deadline = System.nanoTime() + BUDGET_MILLIS * 1_000_000
                val futures =
                    (0 until THREADS).map { index ->
                        pool.submit {
                            val poller = pollers[index % pollers.size]
                            while (pending() > 0 && System.nanoTime() < deadline) {
                                poller.tick()
                                Thread.sleep(5)
                            }
                        }
                    }
                futures.forEach { it.get(BUDGET_MILLIS + 10_000, TimeUnit.MILLISECONDS) }
            } finally {
                pool.shutdownNow()
            }

            pending() shouldBe 0L
            adapter.pushesByNumber.size shouldBe ITEMS
            adapter.pushesByNumber.values
                .map { it.get() }
                .toSet() shouldBe setOf(1)
            items.forEach { id ->
                transaction {
                    AccountingExportItemTable
                        .selectAll()
                        .where {
                            AccountingExportItemTable.id eq id
                        }.single()[AccountingExportItemTable.status]
                } shouldBe
                    AccountingExportItemStatus.SUCCEEDED
            }
            transaction {
                AccountingExportRunTable
                    .selectAll()
                    .where {
                        AccountingExportRunTable.id eq runId
                    }.single()[AccountingExportRunTable.status]
            } shouldBe
                AccountingExportRunStatus.COMPLETED
        }
    })

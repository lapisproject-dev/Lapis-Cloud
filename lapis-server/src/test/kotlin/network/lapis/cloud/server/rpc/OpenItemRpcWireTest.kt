package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OpenItemSettlementTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CounterpartyKey
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.22, Audit-Nachtrag (MAJOR-4): [OpenItemServiceTest]'s new cases prove that
 * `settleOpenItem` THROWS the right type, but they call the service through hand-written throwaway
 * routes with their own `StatusPages` mapping -- so they cannot prove that the type actually crosses
 * the Kilua RPC wire, which is the entire premise of this wave (`network.lapis.cloud.client
 * .openItemGuarded` dispatches on the type, and Kilua RPC transmits ONLY the subclass
 * discriminator). If `registerRpcServiceExceptions()` did not know a new subclass, the client would
 * silently fall back to the generic conflict toast again -- exactly the bug this wave fixes.
 *
 * This test therefore posts a real JSON-RPC request to the generated route against the FULL
 * `module()` -- same shape as `AiFeatureKillSwitchTest`, which pins `AiFeatureDisabledException` the
 * same way.
 *
 * **Route index**: `/rpc/routeOpenItemServiceManager6` is `settleOpenItem` -- the 7th `bind(...)`
 * call in the KSP-generated `OpenItemServiceManager.init` (0-based). Reordering the methods of
 * `IOpenItemService` renumbers it; the first assertion below (a wrong index answers something else
 * entirely, never this exception) is what would fail then.
 *
 * `params` is a `List<String>` where each element is itself a JSON document (Kilua RPC's
 * `JsonRpcRequest`): openItemId, amount, settledOn, bankAccountId = `null` (the "use the
 * organization default" case, which is unconfigured here).
 */
private const val SETTLE_ROUTE = "/rpc/routeOpenItemServiceManager6"

class OpenItemRpcWireTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()
        val createdItemIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                OpenItemSettlementTable.deleteWhere { OpenItemSettlementTable.openItemId inList createdItemIds }
                OpenItemTable.deleteWhere { OpenItemTable.id inList createdItemIds }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[receivablesAccountId] = null
                    it[payablesAccountId] = null
                    it[paymentBankAccountId] = null
                }
                // A SUCCESSFUL settlement books a real journal entry through the full application, so
                // this spec has to clean up postings it never inserted itself -- otherwise the
                // ledger-account delete below hits fk_posting_ledger_account_id. Scoped to THIS spec's
                // accounts (never "delete every journal entry"), so a parallel spec's data survives.
                val ownPostings =
                    PostingTable
                        .selectAll()
                        .where { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                        .map { it[PostingTable.id] to it[PostingTable.journalEntryId] }
                PostingTable.deleteWhere { PostingTable.id inList ownPostings.map { it.first } }
                JournalEntryTable.deleteWhere { JournalEntryTable.id inList (createdJournalEntryIds + ownPostings.map { it.second }) }
                LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            createdItemIds.clear()
            createdJournalEntryIds.clear()
            createdLedgerAccountIds.clear()
            createdMemberIds.clear()
        }

        fun newTreasurer(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "OpenItem-RPC-Testmitglied"
                    it[email] = "open-item-rpc-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.TREASURER
                }
            }
            createdMemberIds += id
            return id
        }

        fun newLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            val number = "W${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Drahttestkonto $number"
                    it[accountClass] = 1
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        /**
         * A minimal `POSTED` journal entry, inserted directly: `settleOpenItem` only needs
         * `open_item.creation_journal_entry_id` to be non-null (the item counts as booked) before it
         * reaches the payment-account resolution this test is about. No postings are needed.
         */
        fun newJournalEntry(actor: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[entryDate] = LocalDate(2026, 1, 1)
                    it[description] = "Drahttest-Entstehungsbuchung"
                    it[voucherReference] = "WIRE-$id"
                    it[createdBy] = actor
                    it[status] = JournalEntryStatus.POSTED
                    it[postedAt] = LocalDateTime(2026, 1, 1, 9, 0)
                    it[createdAt] = LocalDateTime(2026, 1, 1, 9, 0)
                    it[donorMemberId] = null
                    it[externalDonorId] = null
                    it[donorCategory] = null
                }
            }
            createdJournalEntryIds += id
            return id
        }

        fun newBookedPayable(
            actor: Uuid,
            contraAccountId: Uuid,
            journalEntryId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                OpenItemTable.insert {
                    it[OpenItemTable.id] = id
                    it[direction] = OpenItemDirection.PAYABLE
                    it[counterpartyName] = "Muster GmbH"
                    it[counterpartyKey] = CounterpartyKey.of("Muster GmbH")
                    it[itemDate] = LocalDate(2026, 1, 1)
                    it[dueDate] = LocalDate(2026, 2, 1)
                    it[amount] = BigDecimal("240.00")
                    it[OpenItemTable.contraAccountId] = contraAccountId
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[status] = OpenItemStatus.OPEN
                    it[createdByMemberId] = actor
                    it[createdAt] = LocalDateTime(2026, 1, 1, 9, 0)
                    it[creationJournalEntryId] = journalEntryId
                }
            }
            createdItemIds += id
            return id
        }

        fun settleBody(openItemId: Uuid): String =
            """{"id":1,"jsonrpc":"2.0","method":"settleOpenItem","params":""" +
                """["\"$openItemId\"","10.0","\"2026-01-15\"","null"]}"""

        test("PaymentBankAccountNotConfiguredException crosses the real Kilua RPC wire as its own type") {
            testApplication {
                application { module() }
                val treasurer = newTreasurer()
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                val item = newBookedPayable(actor = treasurer, contraAccountId = expense, journalEntryId = newJournalEntry(treasurer))
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[payablesAccountId] = payables
                        it[paymentBankAccountId] = null
                    }
                }

                val response =
                    client.post(SETTLE_ROUTE) {
                        header("X-Member-Id", treasurer.toString())
                        contentType(ContentType.Application.Json)
                        setBody(settleBody(item))
                    }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                // The whole point: the TYPE is on the wire. `registerRpcServiceExceptions()` has to
                // know the new subclass, otherwise the client's dispatch (openItemGuarded) is dead.
                body shouldContain "PaymentBankAccountNotConfiguredException"
                // Not the generic conflict the client can say nothing specific about, and the call
                // really did reach the service (it was authenticated and authorized).
                body shouldNotContain "\"ConflictException\""
                body shouldNotContain "UnauthenticatedException"
                body shouldNotContain "ForbiddenException"

                withClueNothingWasBooked()
            }
        }

        test("a settlement that IS possible answers a normal result over the same wire (route/params are correct)") {
            testApplication {
                application { module() }
                val treasurer = newTreasurer()
                val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
                val payables = newLedgerAccount(LedgerAccountType.LIABILITY)
                val bank = newLedgerAccount(LedgerAccountType.ASSET)
                val item = newBookedPayable(actor = treasurer, contraAccountId = expense, journalEntryId = newJournalEntry(treasurer))
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[payablesAccountId] = payables
                        it[paymentBankAccountId] = bank
                    }
                }

                val body =
                    client
                        .post(SETTLE_ROUTE) {
                            header("X-Member-Id", treasurer.toString())
                            contentType(ContentType.Application.Json)
                            setBody(settleBody(item))
                        }.bodyAsText()

                // Same route, same params, only the configuration differs -- so the first test's
                // failure really is the exception and not a malformed request.
                body shouldNotContain "Exception"
                body shouldContain "PARTIALLY_SETTLED"
                transaction { OpenItemSettlementTable.selectAll().count() } shouldBe 1L
            }
        }
    })

/** No settlement row may survive a rejected settlement -- the transaction has to roll back. */
private fun withClueNothingWasBooked() {
    transaction { OpenItemSettlementTable.selectAll().count() } shouldBe 0L
}

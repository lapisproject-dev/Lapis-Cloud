package network.lapis.cloud.server.openitem.dunning

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.ReceivableDunningLevelTable
import network.lapis.cloud.server.db.generated.ReceivableDunningNoticeTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
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
 * Welle V1.4.15 -- [ReceivableDunningEngine]/[ReceivableDunningPoller] correctness. Structural
 * isolation test (last one) asserts the pre-existing member-contribution dunning domain
 * (`dunning_notice`) is never touched by this poller -- the whole point of keeping the two domains
 * independent (see [ReceivableDunningService] KDoc).
 */
class ReceivableDunningTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdLevelIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                val noticeIds = ReceivableDunningNoticeTable.selectAll().map { it[ReceivableDunningNoticeTable.id] }
                ReceivableDunningNoticeTable.deleteWhere { ReceivableDunningNoticeTable.id inList noticeIds }
                if (createdLevelIds.isNotEmpty()) {
                    ReceivableDunningLevelTable.deleteWhere { ReceivableDunningLevelTable.id inList createdLevelIds }
                }
                val itemIds = OpenItemTable.selectAll().map { it[OpenItemTable.id] }
                OpenItemTable.deleteWhere { OpenItemTable.id inList itemIds }
                val journalEntryIds = JournalEntryTable.selectAll().map { it[JournalEntryTable.id] }
                PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[receivablesAccountId] = null
                    it[payablesAccountId] = null
                    it[receivableDunningEnabled] = false
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
            createdMemberIds.clear()
            createdLedgerAccountIds.clear()
            createdLevelIds.clear()
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Dunning-Testmitglied"
                    it[email] = "receivable-dunning-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.ADMIN
                }
            }
            createdMemberIds += id
            return id
        }

        fun newLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            val number = "D${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun newLevel(
            levelNumber: Int,
            graceDays: Int,
            feeAmount: BigDecimal?,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ReceivableDunningLevelTable.insert {
                    it[ReceivableDunningLevelTable.id] = id
                    it[ReceivableDunningLevelTable.levelNumber] = levelNumber
                    it[name] = "Stufe $levelNumber"
                    it[ReceivableDunningLevelTable.graceDays] = graceDays
                    it[responseDays] = 14
                    it[ReceivableDunningLevelTable.feeAmount] = feeAmount
                    it[active] = true
                    it[createdAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                }
            }
            createdLevelIds += id
            return id
        }

        fun newOpenItem(
            direction: OpenItemDirection,
            contraAccountId: Uuid,
            dueDate: LocalDate,
            actor: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                OpenItemTable.insert {
                    it[OpenItemTable.id] = id
                    it[OpenItemTable.direction] = direction
                    it[counterpartyName] = "Schuldner GmbH"
                    it[counterpartyKey] = "schuldner gmbh"
                    it[itemDate] = LocalDate(2026, 1, 1)
                    it[OpenItemTable.dueDate] = dueDate
                    it[amount] = BigDecimal("100.00")
                    it[OpenItemTable.contraAccountId] = contraAccountId
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[status] = OpenItemStatus.OPEN
                    it[createdByMemberId] = actor
                    it[createdAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[creationJournalEntryId] = null
                }
            }
            return id
        }

        test("escalation over two levels: first tick issues level 1, a later tick (asOf past level 2's grace) issues level 2 too") {
            val admin = newMember()
            val income = newLedgerAccount(LedgerAccountType.INCOME)
            val receivables = newLedgerAccount(LedgerAccountType.ASSET)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[OrganizationSettingsTable.receivablesAccountId] =
                        receivables
                }
            }
            val itemId = newOpenItem(OpenItemDirection.RECEIVABLE, income, LocalDate(2026, 1, 1), admin)
            newLevel(levelNumber = 1, graceDays = 7, feeAmount = null)
            newLevel(levelNumber = 2, graceDays = 21, feeAmount = null)

            val firstOutcome =
                transaction {
                    ReceivableDunningEngine.issueNextLevel(
                        itemId = itemId,
                        asOf = LocalDate(2026, 1, 10),
                        respectGraceDays = true,
                        actorMemberId = admin,
                        actorRole = AccountRole.ADMIN,
                    )
                }
            (firstOutcome is ReceivableDunningEngine.IssueOutcome.Issued) shouldBe true
            transaction {
                ReceivableDunningNoticeTable.selectAll().where { ReceivableDunningNoticeTable.openItemId eq itemId }.count()
            } shouldBe
                1L

            // Not yet due for level 2 (grace 21 days from item_date/due_date == 2026-01-01 -> 2026-01-22).
            val tooEarly =
                transaction {
                    ReceivableDunningEngine.issueNextLevel(
                        itemId = itemId,
                        asOf = LocalDate(2026, 1, 15),
                        respectGraceDays = true,
                        actorMemberId = admin,
                        actorRole = AccountRole.ADMIN,
                    )
                }
            (tooEarly is ReceivableDunningEngine.IssueOutcome.NothingDue) shouldBe true

            val secondOutcome =
                transaction {
                    ReceivableDunningEngine.issueNextLevel(
                        itemId = itemId,
                        asOf = LocalDate(2026, 1, 25),
                        respectGraceDays = true,
                        actorMemberId = admin,
                        actorRole = AccountRole.ADMIN,
                    )
                }
            (secondOutcome is ReceivableDunningEngine.IssueOutcome.Issued) shouldBe true
            transaction {
                ReceivableDunningNoticeTable.selectAll().where { ReceivableDunningNoticeTable.openItemId eq itemId }.count()
            } shouldBe
                2L
        }

        test(
            "uq_rdn_slot idempotency: issuing the same level twice for the same item is impossible -- the second call finds no next level and is a no-op",
        ) {
            val admin = newMember()
            val income = newLedgerAccount(LedgerAccountType.INCOME)
            val receivables = newLedgerAccount(LedgerAccountType.ASSET)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[OrganizationSettingsTable.receivablesAccountId] =
                        receivables
                }
            }
            val itemId = newOpenItem(OpenItemDirection.RECEIVABLE, income, LocalDate(2026, 1, 1), admin)
            newLevel(levelNumber = 1, graceDays = 1, feeAmount = null)

            transaction {
                ReceivableDunningEngine.issueNextLevel(
                    itemId = itemId,
                    asOf = LocalDate(2026, 2, 1),
                    respectGraceDays = true,
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            }
            val second =
                transaction {
                    ReceivableDunningEngine.issueNextLevel(
                        itemId = itemId,
                        asOf = LocalDate(2026, 2, 1),
                        respectGraceDays = true,
                        actorMemberId = admin,
                        actorRole = AccountRole.ADMIN,
                    )
                }
            (second is ReceivableDunningEngine.IssueOutcome.NothingDue) shouldBe true
            transaction {
                ReceivableDunningNoticeTable.selectAll().where { ReceivableDunningNoticeTable.openItemId eq itemId }.count()
            } shouldBe
                1L
        }

        test("a PAYABLE item is never escalated -- issueNextLevel refuses it structurally") {
            val admin = newMember()
            val expense = newLedgerAccount(LedgerAccountType.EXPENSE)
            val itemId = newOpenItem(OpenItemDirection.PAYABLE, expense, LocalDate(2026, 1, 1), admin)
            newLevel(levelNumber = 1, graceDays = 1, feeAmount = null)

            shouldThrow<IllegalArgumentException> {
                transaction {
                    ReceivableDunningEngine.issueNextLevel(
                        itemId = itemId,
                        asOf = LocalDate(2026, 2, 1),
                        respectGraceDays = true,
                        actorMemberId = admin,
                        actorRole = AccountRole.ADMIN,
                    )
                }
            }
        }

        test("poller is a no-op while receivable_dunning_enabled is false, and never touches the pre-existing dunning_notice table") {
            val admin = newMember()
            val income = newLedgerAccount(LedgerAccountType.INCOME)
            val receivables = newLedgerAccount(LedgerAccountType.ASSET)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[OrganizationSettingsTable.receivablesAccountId] = receivables
                    it[receivableDunningEnabled] = false
                }
            }
            newOpenItem(OpenItemDirection.RECEIVABLE, income, LocalDate(2026, 1, 2), admin)
            newLevel(levelNumber = 1, graceDays = 1, feeAmount = null)

            val dunningNoticeCountBefore = transaction { DunningNoticeTable.selectAll().count() }
            val poller = ReceivableDunningPoller(config = ReceivableDunningConfig.load { null })
            poller.tick()
            transaction { ReceivableDunningNoticeTable.selectAll().count() } shouldBe 0L
            transaction { DunningNoticeTable.selectAll().count() } shouldBe dunningNoticeCountBefore
        }
    })

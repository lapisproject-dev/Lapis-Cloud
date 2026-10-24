package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PeerTransferTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/** The guards in front of the staging top-up (V1.9.197): every negative case must leave the database byte-for-byte as it was. */
class StagingSeedTopUpGuardTest :
    FunSpec({
        val password = "ein-starkes-testpasswort"

        fun envOf(vararg pairs: Pair<String, String?>): (String) -> String? = { key -> pairs.toMap()[key] }

        val enabledEnv =
            envOf(StagingSeedConfig.ENV_STAGING_MODE to "true", StagingSeedConfig.ENV_SEED_PASSWORD to password)

        // Every table a V1.9.205 part writes: count and sorted ids, so that "nothing written" means nothing in any of them.
        val partTables: List<Table> =
            listOf(
                CrowdfundingProjectTable,
                CrowdfundingReactionTable,
                AuctionTable,
                AuctionBidTable,
                SocialPostTable,
                SocialPostBoostTable,
                PoliticianProfileTable,
                PoliticianReactionTable,
                PublicRankingConsentEventTable,
                CommitteeTable,
                CommitteeMembershipTable,
                MeetingTable,
                AgendaItemTable,
                AttendanceTable,
                MotionTable,
                ResolutionTable,
                TenderTable,
                TenderBidTable,
                TenderAwardTable,
                EventTable,
                EventRegistrationTable,
                PriceOracleSnapshotTable,
                AuditLogEntryTable,
            )

        @Suppress("UNCHECKED_CAST")
        fun JdbcTransaction.partTableSnapshot(): List<String> =
            partTables.map { table ->
                val idColumn = table.columns.first { it.name == "id" } as Column<Any>
                val ids = table.select(idColumn).map { it[idColumn].toString() }.sorted()
                "T:${table.tableName}:${ids.size}:${ids.hashCode()}"
            }

        fun snapshot(db: Database): List<String> =
            transaction(db) {
                partTableSnapshot() +
                    LtrLedgerEntryTable.selectAll().map {
                        "L:${it[LtrLedgerEntryTable.id]}|${it[LtrLedgerEntryTable.memberId]}|${it[LtrLedgerEntryTable.entryType]}|" +
                            "${it[LtrLedgerEntryTable.amountLtr]}|${it[LtrLedgerEntryTable.note]}|${it[LtrLedgerEntryTable.createdAt]}"
                    } +
                    PeerTransferTable.selectAll().map {
                        "P:${it[PeerTransferTable.id]}|${it[PeerTransferTable.amountLtr]}|${it[PeerTransferTable.createdAt]}"
                    } +
                    MemberTable.selectAll().map {
                        "M:${it[MemberTable.id]}|${it[MemberTable.displayName]}|${it[MemberTable.email]}|${it[MemberTable.status]}"
                    } +
                    OrganizationSettingsTable.selectAll().map { "O:${it[OrganizationSettingsTable.name]}" }
            }

        fun seeded(): Database {
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
            System.setProperty("net.fortuna.ical4j.recur.maxincrementcount", "1000")
            val db = IsolatedH2Database.create()
            StagingSeedData.seedWith(seedPassword = password, database = db, sepaKey = null)
            return db
        }

        fun assertSkippedUntouched(
            db: Database,
            run: () -> StagingTopUpOutcome,
        ) {
            val before = snapshot(db)
            run().shouldBeInstanceOf<StagingTopUpOutcome.Skipped>()
            snapshot(db) shouldBe before
        }

        test("envGate: only an enabled staging mode passes, everything else is EnvDisabled; no database involved") {
            StagingSeedTopUpGuard.envGate(enabledEnv) shouldBe null
            val disabled =
                listOf(
                    envOf(),
                    envOf(StagingSeedConfig.ENV_SEED_PASSWORD to password),
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to "True", StagingSeedConfig.ENV_SEED_PASSWORD to password),
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to "1", StagingSeedConfig.ENV_SEED_PASSWORD to password),
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to " true", StagingSeedConfig.ENV_SEED_PASSWORD to password),
                    envOf(StagingSeedConfig.ENV_STAGING_MODE to "true"),
                )
            disabled.forEach { StagingSeedTopUpGuard.envGate(it) shouldBe StagingTopUpDecision.EnvDisabled }
        }

        test("env not enabled, on a fully seeded database: nothing is written") {
            val db = seeded()
            listOf(
                envOf(),
                envOf(StagingSeedConfig.ENV_STAGING_MODE to "True", StagingSeedConfig.ENV_SEED_PASSWORD to password),
                envOf(StagingSeedConfig.ENV_STAGING_MODE to "1", StagingSeedConfig.ENV_SEED_PASSWORD to password),
                envOf(StagingSeedConfig.ENV_STAGING_MODE to " true", StagingSeedConfig.ENV_SEED_PASSWORD to password),
                envOf(StagingSeedConfig.ENV_STAGING_MODE to "true"),
            ).forEach { env -> assertSkippedUntouched(db) { StagingSeedTopUp.topUpIfEligible(env = env, database = db) } }
        }

        test("empty database (default organization, no Anna): not a staging instance, nothing written") {
            val db = IsolatedH2Database.create()
            assertSkippedUntouched(db) { StagingSeedTopUp.topUpWith(database = db) }
            assertSkippedUntouched(db) { StagingSeedTopUp.topUpIfEligible(env = enabledEnv, database = db) }
        }

        test("seeded, but organization name differs (other name, trailing space, lower case): refused") {
            val names =
                listOf("Partei der Vernunft", StagingSeedData.ORGANIZATION_NAME + " ", StagingSeedData.ORGANIZATION_NAME.lowercase())
            names.forEach { name ->
                val db = seeded()
                transaction(db) {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }) {
                        it[this.name] =
                            name
                    }
                }
                assertSkippedUntouched(db) { StagingSeedTopUp.topUpWith(database = db) }
            }
        }

        test("seeded, but the seed member 1 differs (e-mail domain, display name): refused") {
            val db1 = seeded()
            transaction(db1) { MemberTable.update({ MemberTable.id eq SeedIds.member(1) }) { it[email] = "anna@example.org" } }
            assertSkippedUntouched(db1) { StagingSeedTopUp.topUpWith(database = db1) }

            val db2 = seeded()
            transaction(db2) { MemberTable.update({ MemberTable.id eq SeedIds.member(1) }) { it[displayName] = "Anna Echt" } }
            assertSkippedUntouched(db2) { StagingSeedTopUp.topUpWith(database = db2) }
        }

        test("right organization name but no seed member 1: refused") {
            val db = IsolatedH2Database.create()
            transaction(db) {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }) {
                    it[this.name] = StagingSeedData.ORGANIZATION_NAME
                }
            }
            assertSkippedUntouched(db) { StagingSeedTopUp.topUpWith(database = db) }
            transaction(
                db,
            ) { with(StagingSeedTopUpGuard) { instanceCheck() } }.shouldBeInstanceOf<StagingTopUpDecision.NotStagingInstance>()
        }

        test("every guard passes: Applied with rows, every part ran and none failed") {
            val db = seeded()
            val outcome = StagingSeedTopUp.topUpIfEligible(env = enabledEnv, database = db)
            outcome.shouldBeInstanceOf<StagingTopUpOutcome.Applied>()
            (outcome.result.mintsInserted > 0) shouldBe true
            outcome.parts.map { it.part } shouldBe StagingSeedTopUp.PARTS.map { it.name }
            outcome.parts.none { it.failed } shouldBe true
            outcome.parts.all { it.inserted > 0 } shouldBe true
            val plainLtrRows =
                transaction(db) {
                    LtrLedgerEntryTable
                        .selectAll()
                        .count { it[LtrLedgerEntryTable.entryType].name.let { n -> n == "MINT" || n.startsWith("PEER_TRANSFER") } }
                }
            plainLtrRows shouldBe (outcome.result.mintsInserted + 2 * outcome.result.transfersInserted)
        }

        test("the instance stops being a staging instance between the LTR step and the parts: the later parts write nothing") {
            val db = seeded()
            val flipper =
                object : StagingSeedPart {
                    override val name: String = "flipper"

                    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
                        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }) {
                            it[OrganizationSettingsTable.name] = "Partei der Vernunft"
                        }
                        return StagingSeedPartResult(inserted = 0, skipped = 0)
                    }
                }
            val outcome = StagingSeedTopUp.topUpWith(database = db, parts = listOf(flipper) + StagingSeedTopUp.PARTS)
            outcome.shouldBeInstanceOf<StagingTopUpOutcome.Applied>()
            outcome.parts.none { it.failed } shouldBe true
            outcome.parts.drop(1).all { it.inserted == 0 && it.skipped == 0 } shouldBe true
            // nothing of any part exists: only the first seed's own rows (the part tables hold what the first seed wrote, none of the new ids)
            transaction(db) {
                (EventTable.selectAll().map { it[EventTable.slug] }.filter { it.endsWith("-demodaten") }) shouldBe emptyList()
                PriceOracleSnapshotTable.selectAll().count() shouldBe 0L
                AuctionTable.selectAll().count() shouldBe 0L
                TenderTable.selectAll().count() shouldBe 0L
            }
        }
    })

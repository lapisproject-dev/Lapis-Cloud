package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PeerTransferTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
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

        fun snapshot(db: Database): List<String> =
            transaction(db) {
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
            assertSkippedUntouched(db) { StagingSeedTopUp.topUpWith(db) }
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
                assertSkippedUntouched(db) { StagingSeedTopUp.topUpWith(db) }
            }
        }

        test("seeded, but the seed member 1 differs (e-mail domain, display name): refused") {
            val db1 = seeded()
            transaction(db1) { MemberTable.update({ MemberTable.id eq SeedIds.member(1) }) { it[email] = "anna@example.org" } }
            assertSkippedUntouched(db1) { StagingSeedTopUp.topUpWith(db1) }

            val db2 = seeded()
            transaction(db2) { MemberTable.update({ MemberTable.id eq SeedIds.member(1) }) { it[displayName] = "Anna Echt" } }
            assertSkippedUntouched(db2) { StagingSeedTopUp.topUpWith(db2) }
        }

        test("right organization name but no seed member 1: refused") {
            val db = IsolatedH2Database.create()
            transaction(db) {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }) {
                    it[this.name] = StagingSeedData.ORGANIZATION_NAME
                }
            }
            assertSkippedUntouched(db) { StagingSeedTopUp.topUpWith(db) }
            transaction(
                db,
            ) { with(StagingSeedTopUpGuard) { instanceCheck() } }.shouldBeInstanceOf<StagingTopUpDecision.NotStagingInstance>()
        }

        test("every guard passes: Applied with rows") {
            val db = seeded()
            val outcome = StagingSeedTopUp.topUpIfEligible(env = enabledEnv, database = db)
            outcome.shouldBeInstanceOf<StagingTopUpOutcome.Applied>()
            (outcome.result.mintsInserted > 0) shouldBe true
            transaction(db) { LtrLedgerEntryTable.selectAll().count() } shouldBe
                (outcome.result.mintsInserted + 2 * outcome.result.transfersInserted).toLong()
        }
    })

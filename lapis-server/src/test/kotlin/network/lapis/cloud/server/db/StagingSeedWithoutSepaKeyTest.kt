package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.shared.domain.ContributionStatus
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Welle V1.9.63 -- without a usable `LAPIS_SECRET_ENCRYPTION_KEY` the seed must skip SEPA entirely (a mandate needs the encrypted IBAN, and a
 * plaintext fallback does not exist) and still deliver the rest of the dataset in full. Own isolated database, own seed run.
 */
class StagingSeedWithoutSepaKeyTest :
    FunSpec({
        test("no key: no SEPA data at all, SEPA stays off, the rest of the dataset is complete") {
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
            System.setProperty("net.fortuna.ical4j.recur.maxincrementcount", "1000")
            val db = IsolatedH2Database.create()
            StagingSeedData.seedWith(seedPassword = "ein-starkes-testpasswort", database = db, sepaKey = null)

            transaction(db) {
                SepaMandateTable.selectAll().count() shouldBe 0L
                SepaDebitBatchTable.selectAll().count() shouldBe 0L
                SepaComplianceAcknowledgmentTable.selectAll().count() shouldBe 0L
                OrganizationSettingsTable.selectAll().single().let {
                    it[OrganizationSettingsTable.sepaDebitEnabled] shouldBe false
                    it[OrganizationSettingsTable.sepaCreditorId].shouldBeNull()
                }
                // no contribution is bound to a debit run
                ContributionTable.selectAll().count { it[ContributionTable.status] == ContributionStatus.DEBIT_SCHEDULED } shouldBe 0
                // everything else is there
                MemberTable.selectAll().count() shouldBe 40L
                DunningLevelTable.selectAll().count() shouldBe 3L
                (ContributionTable.selectAll().count { it[ContributionTable.status] == ContributionStatus.PAID } > 0) shouldBe true
            }
        }
    })

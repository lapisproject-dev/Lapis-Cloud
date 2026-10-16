package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.backup.OrganizationSchemaCatalog
import network.lapis.cloud.server.db.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.144 -- the four finder tables are NOT part of the ADMIN-only whole-organization backup: whoever is interested in finding other members is itself
 * sensitive (Art. 9 GDPR), and a restore must leave the tables empty so that every member consents again. Documented consequence: the consent proofs are
 * gone after a restore.
 */
class MemberFinderBackupExclusionTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val tables = listOf("member_finder_consent_event", "member_finder_request", "member_finder_block", "member_finder_region_band")

        test("all four tables are excluded with a written reason") {
            for (t in tables) {
                OrganizationSchemaCatalog.EXCLUDED_TABLES.containsKey(t) shouldBe true
                OrganizationSchemaCatalog.EXCLUDED_TABLES.getValue(t).length shouldBe
                    OrganizationSchemaCatalog.EXCLUDED_TABLES
                        .getValue(t)
                        .trim()
                        .length
            }
        }

        test("the exportable table list contains none of them, the organization settings (with the switch) stay in") {
            val exportable = transaction { OrganizationSchemaCatalog.exportableTables(this).map { it.tableName } }
            for (t in tables) exportable.contains(t) shouldBe false
            exportable.contains("organization_settings") shouldBe true
        }
    })

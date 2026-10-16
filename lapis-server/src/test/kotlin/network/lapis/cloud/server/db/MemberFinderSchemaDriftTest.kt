package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MemberFinderBlockTable
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.db.generated.MemberFinderRegionBandTable
import network.lapis.cloud.server.db.generated.MemberFinderRequestTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.144 "Mitglieder-Finder". Verifies that `72-member-finder.kuml.kts` is a faithful model of the real,
 * Flyway-migrated schema (`V107__member_finder.sql`) and of the four Exposed objects -- and that no person row carries a region and the aggregate carries
 * no member.
 */
class MemberFinderSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "72-member-finder.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the four tables plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "member_finder_consent_event", "member_finder_request", "member_finder_block", "member_finder_region_band")
        }

        val tables: Map<String, Table> =
            mapOf(
                "member_finder_consent_event" to MemberFinderConsentEventTable,
                "member_finder_request" to MemberFinderRequestTable,
                "member_finder_block" to MemberFinderBlockTable,
                "member_finder_region_band" to MemberFinderRegionBandTable,
            )
        tables.forEach { (name, table) ->
            test("$name matches the real migrated schema and the Exposed object 1:1") {
                val entity = model.entities.single { it.name == name }
                val real = transaction { introspectNullability(name) }
                entity.attributes.map { it.name }.toSet() shouldBe real.keys
                entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
                entity.attributes.map { it.name } shouldContainExactlyInAnyOrder table.columns.map { it.name }
            }
        }

        test("no person row carries a region, a postal code or a city; the aggregate carries no member") {
            for (name in listOf("member_finder_consent_event", "member_finder_request", "member_finder_block")) {
                val columns = transaction { introspectNullability(name) }.keys
                columns.none { it.contains("region") || it.contains("postal") || it.contains("city") } shouldBe true
            }
            transaction { introspectNullability("member_finder_region_band") }.keys shouldBe setOf("region_key", "band", "computed_at")
        }

        test("the organization settings carry the two new columns, default off, notice version nullable") {
            val settings = transaction { introspectNullability("organization_settings") }
            settings.getValue("member_finder_enabled") shouldBe false
            settings.getValue("member_finder_notice_version") shouldBe true
        }
    })

private fun JdbcTransaction.introspectNullability(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

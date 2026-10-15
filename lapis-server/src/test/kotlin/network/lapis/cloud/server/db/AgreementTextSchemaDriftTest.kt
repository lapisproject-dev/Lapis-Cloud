package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AgreementTextDraftTable
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.134 "Vertragstexte". Verifies that `70-agreement-texts.kuml.kts` is a faithful model of the
 * real, Flyway-migrated schema (`V104__agreement_texts.sql`) and of the two Exposed objects. Mirrors [VideoOverlaySchemaDriftTest].
 */
class AgreementTextSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "70-agreement-texts.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the two tables plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "agreement_text_version", "agreement_text_draft")
        }

        test("agreement_text_version matches the real migrated schema and the Exposed object 1:1") {
            val entity = model.entities.single { it.name == "agreement_text_version" }
            val real = transaction { introspectNullability("agreement_text_version") }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AgreementTextVersionTable.columns.map { it.name }
        }

        test("agreement_text_draft matches the real migrated schema and the Exposed object 1:1, with kind as primary key") {
            val entity = model.entities.single { it.name == "agreement_text_draft" }
            val real = transaction { introspectNullability("agreement_text_draft") }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AgreementTextDraftTable.columns.map { it.name }
            entity.attributeByName("kind")?.primaryKey shouldBe true
            AgreementTextDraftTable.primaryKey
                ?.columns
                ?.map { it.name } shouldBe listOf("kind")
        }
    })

private fun JdbcTransaction.introspectNullability(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

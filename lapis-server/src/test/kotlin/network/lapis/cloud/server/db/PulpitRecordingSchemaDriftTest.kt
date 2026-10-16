package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.lapis.cloud.server.db.generated.PulpitRecordingDocumentTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.143 "Kanzel-Aufzeichnung". Verifies that `71-pulpit-recording.kuml.kts` is a faithful model of
 * the real, Flyway-migrated schema (`V106__pulpit_recording.sql`) and of the Exposed object -- and that the table carries NO column that could name
 * a person (it is a marker of a document, not a trail of who stood at the pulpit).
 */
class PulpitRecordingSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "71-pulpit-recording.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the one table plus the Document and EncounterSpace stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("document", "encounter_space", "pulpit_recording_document")
        }

        test("pulpit_recording_document matches the real migrated schema and the Exposed object 1:1, with document_id as primary key") {
            val entity = model.entities.single { it.name == "pulpit_recording_document" }
            val real = transaction { introspectNullability("pulpit_recording_document") }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PulpitRecordingDocumentTable.columns.map { it.name }
            entity.attributeByName("document_id")?.primaryKey shouldBe true
        }

        test("the table has no column that names a person") {
            val real = transaction { introspectNullability("pulpit_recording_document") }
            real.keys.none { it.contains("member") || it.contains("person") || it.contains("user") } shouldBe true
            real.keys shouldBe setOf("document_id", "space_id", "recorded_at")
        }

        test("the organization settings and the space carry the new columns, NOT NULL where the model says so") {
            val settings = transaction { introspectNullability("organization_settings") }
            settings.getValue("pulpit_recording_enabled") shouldBe false
            settings.getValue("pulpit_recording_require_confirmation") shouldBe false
            settings.getValue("pulpit_recording_notice_version") shouldBe true
            transaction { introspectNullability("encounter_space") }.getValue("pulpit_recording_allowed") shouldBe false
            transaction { introspectNullability("pulpit_recording_document") }["document_id"] shouldNotBe null
        }
    })

private fun JdbcTransaction.introspectNullability(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

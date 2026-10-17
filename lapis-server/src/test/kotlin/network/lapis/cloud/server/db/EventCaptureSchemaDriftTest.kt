package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.156. Verifies that `73-event-capture.kuml.kts` is a faithful model of the real, Flyway-migrated schema
 * (`V110__event_capture.sql`) and of the Exposed object -- and that the proof carries no name, no room and no time of day.
 */
class EventCaptureSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "73-event-capture.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the table plus the Event and Member stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("event", "member", "event_capture_acknowledgment")
        }

        test("event_capture_acknowledgment matches the real migrated schema and the Exposed object 1:1") {
            val entity = model.entities.single { it.name == "event_capture_acknowledgment" }
            val real = transaction { introspectNullability("event_capture_acknowledgment") }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder EventCaptureAcknowledgmentTable.columns.map { it.name }
        }

        test("the proof carries no name, no room and no time of day -- the date is a DATE") {
            val columns = transaction { introspectNullability("event_capture_acknowledgment") }.keys
            columns shouldBe setOf("id", "event_id", "member_id", "guest_key", "consent_version", "consent_sha256", "acknowledged_on")
            columns.none { it.contains("name") || it.contains("room") || it.endsWith("_at") } shouldBe true
        }

        test("the organization settings and the event carry the new columns: default off, notice version nullable") {
            val settings = transaction { introspectNullability("organization_settings") }
            settings.getValue("event_capture_enabled") shouldBe false
            settings.getValue("event_capture_notice_version") shouldBe true
            transaction { introspectNullability("event") }.getValue("capture_allowed") shouldBe false
        }
    })

private fun JdbcTransaction.introspectNullability(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

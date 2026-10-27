package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 -- V1.9.227. `39-events.kuml.kts` models `event.meeting_id` (nullable), the Exposed object and the migrated schema carry the same column
 * set, and `14-audit-log.kuml.kts` knows the new audit entity type.
 */
class EventMeetingLinkSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "39-events.kuml.kts")) }

        test("the event entity models meeting_id as a nullable column and matches the real schema and the Exposed object") {
            val entity = model.entities.single { it.name == "event" }
            val attribute = entity.attributes.single { it.name == "meeting_id" }
            attribute.nullable shouldBe true
            val real = transaction { nullability("event") }
            real.getValue("meeting_id") shouldBe true
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder EventTable.columns.map { it.name }
        }

        test("the audit model lists MEETING_EVENT_LINK and it fits the column width") {
            val auditScript = File(KumlModelLoader.kumlSourceDir, "14-audit-log.kuml.kts").readText()
            auditScript.contains("literal(name = \"MEETING_EVENT_LINK\")") shouldBe true
            (AuditEntityType.MEETING_EVENT_LINK.name.length <= 29) shouldBe true
        }
    })

private fun JdbcTransaction.nullability(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

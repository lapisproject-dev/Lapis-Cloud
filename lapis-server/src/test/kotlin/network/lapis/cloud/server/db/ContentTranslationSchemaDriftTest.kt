package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ContentTranslationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 -- V1.9.342. `85-content-translation.kuml.kts` models `content_translation` (composite primary key, no foreign key), `39-events.kuml.kts`
 * the three consent columns of `event`, `11-organization-settings.kuml.kts` the `content_language`; model, Exposed objects and the migrated schema agree.
 */
class ContentTranslationSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        fun load(name: String): ErmModel = KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, name))

        test(
            "content_translation: model, real schema and Exposed object carry the same columns; the key is composite; there is no foreign key",
        ) {
            val entity = load("85-content-translation.kuml.kts").entities.single { it.name == "content_translation" }
            val real = transaction { nullability("content_translation") }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}'") { real.getValue(attr.name!!) shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ContentTranslationTable.columns.map { it.name }
            val primaryKey = transaction { primaryKeyColumns("content_translation") }
            primaryKey shouldBe setOf("content_type", "content_id", "field", "source_sha256", "source_lang", "target_lang")
            ContentTranslationTable.primaryKey!!
                .columns
                .map { it.name }
                .toSet() shouldBe primaryKey
            entity.attributes.none { it.foreignKey != null } shouldBe true
            transaction { foreignKeyCount("content_translation") } shouldBe 0
        }

        test("event: the consent columns are modelled, real and in the Exposed object") {
            val entity = load("39-events.kuml.kts").entities.single { it.name == "event" }
            val real = transaction { nullability("event") }
            val expected = listOf("translation_consent" to false, "translation_consent_by" to true, "translation_consent_at" to true)
            expected.forEach { (column, nullable) ->
                withClue(column) {
                    entity.attributes.single { it.name == column }.nullable shouldBe nullable
                    real.getValue(column) shouldBe nullable
                }
            }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder EventTable.columns.map { it.name }
        }

        test("organization_settings.content_language is modelled, real, NOT NULL and in the Exposed object") {
            val entity = load("11-organization-settings.kuml.kts").entities.single { it.name == "organization_settings" }
            entity.attributes.single { it.name == "content_language" }.nullable shouldBe false
            transaction { nullability("organization_settings") }.getValue("content_language") shouldBe false
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OrganizationSettingsTable.columns.map { it.name }
        }
    })

private fun JdbcTransaction.nullability(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

private fun JdbcTransaction.primaryKeyColumns(tableName: String): Set<String> {
    val columns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
        WHERE tc.table_name = '$tableName' AND tc.constraint_type = 'PRIMARY KEY'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) columns += rs.getString("column_name")
    }
    return columns
}

private fun JdbcTransaction.foreignKeyCount(tableName: String): Int {
    var count = 0
    exec(
        "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_name = '$tableName' AND constraint_type = 'FOREIGN KEY'",
    ) { rs ->
        if (rs.next()) count = rs.getInt(1)
    }
    return count
}

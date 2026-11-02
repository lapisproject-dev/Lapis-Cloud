package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * V1.9.255 -- the six pause-media columns of `organization_settings` (V135) exist, with the same nullability, in the kUML model
 * (`11-organization-settings.kuml.kts`), the Flyway-migrated H2 schema and the Exposed object -- and the music duration has its CHECK.
 */
class PauseMediaSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val columns =
            listOf(
                "pause_image_key",
                "pause_image_updated_at",
                "pause_music_key",
                "pause_music_label",
                "pause_music_duration_seconds",
                "pause_music_updated_at",
            )
        val model: ErmModel by lazy {
            KumlModelLoader.loadErmModel(
                File(KumlModelLoader.kumlSourceDir, "11-organization-settings.kuml.kts"),
            )
        }

        test("the six columns are nullable in the model, the real table and the Exposed object") {
            val settings = model.entities.single { it.name == "organization_settings" }
            val real = mutableMapOf<String, String>()
            transaction {
                exec(
                    "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 'ORGANIZATION_SETTINGS' OR table_name = 'organization_settings'",
                ) { rs ->
                    while (rs.next()) real[rs.getString("column_name").lowercase()] = rs.getString("is_nullable")
                }
            }
            columns.forEach { column ->
                withClue("column $column") {
                    settings.attributes.first { it.name == column }.nullable shouldBe true
                    real[column] shouldBe "YES"
                    OrganizationSettingsTable.columns.any { it.name == column } shouldBe true
                }
            }
        }

        test("the music duration CHECK rejects 4 and 601 seconds and accepts NULL, 5 and 600") {
            fun trySet(value: String): Boolean =
                runCatching {
                    transaction {
                        exec("UPDATE organization_settings SET pause_music_duration_seconds = $value")
                        exec("UPDATE organization_settings SET pause_music_duration_seconds = NULL")
                    }
                }.isSuccess
            trySet("NULL") shouldBe true
            trySet("5") shouldBe true
            trySet("600") shouldBe true
            trySet("4") shouldBe false
            trySet("601") shouldBe false
        }
    })

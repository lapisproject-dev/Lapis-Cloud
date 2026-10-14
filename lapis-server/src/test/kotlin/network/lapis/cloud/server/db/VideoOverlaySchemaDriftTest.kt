package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.VideoOverlayAssetTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.129 "Video-Overlays". Verifies that `69-video-overlay.kuml.kts` is a faithful model of
 * the real, Flyway-migrated schema (`V102__video_overlays.sql`) and of the hand-written `VideoOverlayAssetTable` Exposed object, and that
 * the new `video_overlays_offered` column of `organization_settings` exists in the real schema and in its Exposed object. Mirrors
 * [ConferenceBackgroundSchemaDriftTest].
 */
class VideoOverlaySchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "69-video-overlay.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly video_overlay_asset (no member stub: the table carries no personal data)") {
            model.entities.map { it.name }.toSet() shouldBe setOf("video_overlay_asset")
        }

        test("video_overlay_asset matches the real migrated schema and the Exposed object 1:1") {
            val entity = model.entities.single { it.name == "video_overlay_asset" }
            val real = transaction { introspectNullability("video_overlay_asset") }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder VideoOverlayAssetTable.columns.map { it.name }
        }

        test("organization_settings.video_overlays_offered exists, is NOT NULL and is in the Exposed object") {
            val real = transaction { introspectNullability("organization_settings") }
            real["video_overlays_offered"] shouldBe false
            OrganizationSettingsTable.columns.map { it.name }.contains("video_overlays_offered") shouldBe true
        }
    })

private fun JdbcTransaction.introspectNullability(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

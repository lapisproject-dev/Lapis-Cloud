// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

public object ActivismMaterialDocumentTable : Table("activism_material_document") {
    public val id: Column<Uuid> = uuid("id")
    public val actionId: Column<Uuid> = uuid("action_id")
    public val documentId: Column<Uuid> = uuid("document_id")
    public val position: Column<Int> = integer("position")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AttachedImageTable
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- image gallery (V1.9.222). Verifies that `75-image-gallery.kuml.kts` is a faithful model of the
 * real,
 * Flyway-migrated H2 schema (`attached_image`) and of the Exposed object, column for column.
 */
class ImageGallerySchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "75-image-gallery.kuml.kts")) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("the model declares the attached_image entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "attached_image")
        }

        test("attached_image matches the migrated schema and AttachedImageTable 1:1") {
            val entity = model.entities.single { it.name == "attached_image" }
            val real = transaction { introspectGalleryTable("attached_image") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}' of attached_image") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder AttachedImageTable.columns.map { it.name }
            real.primaryKeyColumns shouldBe setOf("id")
            // the only foreign key is uploaded_by -> member; owner_id deliberately has none (one table serves several owner tables)
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId)
            } shouldBe real.foreignKeys
            real.foreignKeys shouldBe mapOf("uploaded_by" to "member")
            entity.attributeByName("owner_id")?.foreignKey shouldBe null
        }

        test("owner_type is the enum in exactly the order of the shared AttachedImageOwnerType") {
            // sized by an explicit sqlType (VARCHAR(32)), so the model type is a Varchar; the Kotlin type is the enum
            val entity = model.entities.single { it.name == "attached_image" }
            entity.attributeByName("owner_type")?.type shouldBe ErmDataType.Varchar(length = 32)
            AttachedImageOwnerType.entries.map { it.name } shouldBe listOf("CROWDFUNDING_PROJECT", "AUCTION_LISTING", "SOCIAL_POST")
            transaction { AttachedImageTable.ownerType.columnType.sqlType() } shouldBe "VARCHAR(32)"
        }

        test("positions are unique per owner and storage_key is unique") {
            transaction { introspectGalleryTable("attached_image") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("owner_type", "owner_id", "position"), setOf("storage_key"))
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedGalleryTable(
    val columns: Map<String, IntrospectedGalleryColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedGalleryColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectGalleryTable(tableName: String): IntrospectedGalleryTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    val pkColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            pkColumns += rs.getString("column_name")
        }
    }

    val uniqueColumnsByConstraint = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT tc.constraint_name AS name, kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT i.index_name AS name, ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedGalleryColumn(nullable = nullable) }
    return IntrospectedGalleryTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

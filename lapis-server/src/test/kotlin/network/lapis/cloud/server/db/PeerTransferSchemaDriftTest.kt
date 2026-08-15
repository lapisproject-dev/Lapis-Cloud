package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PeerTransferTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — direkte LTR-Peer-to-Peer-Uebertragung domain (V0.6.3).
 *
 * Verifies that `lapis-server/src/main/kuml/18-peer-transfer.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`peer_transfer`), and (b) the hand-written
 * [PeerTransferTable] Exposed object. Mirrors [CrowdfundingSchemaDriftTest]'s shape -- see
 * [SchemaDriftTest]'s KDoc for the full designModelStrategy option B rationale.
 *
 * The domain-specific structural point this test pins: `peer_transfer` has THREE separate FKs to
 * `member` (`sender_member_id`/`recipient_member_id`/`initiated_by`), ALL modelled as plain
 * «Column» attributes (never a UML association) -- see the `.kuml.kts` file header "FK-naming
 * choice" for why.
 */
class PeerTransferSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "18-peer-transfer.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the peer_transfer entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("peer_transfer", "member")
        }

        test("peer_transfer table shape matches the real migrated schema and PeerTransferTable 1:1") {
            val entity = model.entities.single { it.name == "peer_transfer" }
            val real = transaction { introspectPeerTransferTable("peer_transfer") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PeerTransferTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            // All three member FKs are plain «Column» attributes -- see file header "FK-naming choice".
            real.foreignKeys["sender_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("sender_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            real.foreignKeys["recipient_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("recipient_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            real.foreignKeys["initiated_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("initiated_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            entity.attributeByName("sender_member_id")?.nullable shouldBe false
            entity.attributeByName("recipient_member_id")?.nullable shouldBe false
            entity.attributeByName("initiated_by")?.nullable shouldBe true
            entity.attributeByName("purpose")?.nullable shouldBe true

            entity.attributeByName("amount_ltr")?.type shouldBe ErmDataType.Decimal(18, 2)
            entity.attributeByName("characterization")?.type shouldBe
                ErmDataType.Enum(
                    name = "PeerTransferCharacterization",
                    values = listOf("SCHENKUNG", "HONORAR", "PRIVATVERKAUF", "SONSTIGES"),
                    externalFqName = "network.lapis.cloud.shared.domain.PeerTransferCharacterization",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [CrowdfundingSchemaDriftTest]'s own private helper. */
private data class IntrospectedPeerTransferTable(
    val columns: Map<String, IntrospectedPeerTransferColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedPeerTransferColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectPeerTransferTable(tableName: String): IntrospectedPeerTransferTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedPeerTransferColumn(nullable = nullable) }
    return IntrospectedPeerTransferTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors SchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }

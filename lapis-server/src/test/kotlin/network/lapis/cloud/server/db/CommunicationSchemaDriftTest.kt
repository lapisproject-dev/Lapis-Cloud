package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — communication domain.
 *
 * Verifies that `lapis-server/src/main/kuml/03-communication.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`mailing_list`/`mailing_list_subscription`/
 * `mailing_message`/`mailing_delivery_log`/`direct_message`), and (b) the hand-written
 * `MailingListTable`/`MailingListSubscriptionTable`/`MailingMessageTable`/
 * `MailingDeliveryLogTable`/`DirectMessageTable` Exposed objects.
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain) and [DocumentSchemaDriftTest] (document domain) — see [SchemaDriftTest]'s KDoc for the
 * full designModelStrategy option B rationale (verification-only artifact; hand-written `Table`
 * objects remain the actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class CommunicationSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "03-communication.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the five communication entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "mailing_list",
                    "mailing_list_subscription",
                    "mailing_message",
                    "mailing_delivery_log",
                    "direct_message",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("mailing_list table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "mailing_list" }
            val real = transaction { introspectCommunicationTable("mailing_list") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // created_by has a real FK in the migrated schema. Same rationale as document's
            // folder_id/created_by — the derived association default name would be "member_id",
            // not the real schema's "created_by" — pinned instead via «Column».fkEntity.
            real.foreignKeys["created_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("mailing_list_subscription table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "mailing_list_subscription" }
            val real = transaction { introspectCommunicationTable("mailing_list_subscription") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // Both FKs on this table match UmlToErmTransformer's association-derived default
            // name exactly ("mailing_list_id" / "member_id"), so both are real UML associations.
            real.foreignKeys["mailing_list_id"] shouldBe "mailing_list"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("mailing_list_id")?.foreignKey?.targetEntityId ?: "") shouldBe "mailing_list"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("mailing_list_subscription's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_mailing_subscription_list_member UNIQUE (mailing_list_id, member_id) — pinned via
            // a class-level «Index» (composite, unique=true), same mechanism as contribution's
            // uq_contribution_member_tier_period and document's uq_document_version_number.
            val real = transaction { introspectCommunicationTable("mailing_list_subscription") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("mailing_list_id", "member_id"))

            val entity = model.entities.single { it.name == "mailing_list_subscription" }
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_mailing_subscription_list_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("mailing_list_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                    )
            }
        }

        test("mailing_message table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "mailing_message" }
            val real = transaction { introspectCommunicationTable("mailing_message") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // mailing_list_id matches the association-derived default and is a real UML
            // association; sent_by does not (would derive "member_id") and is a plain «Column»
            // pinned via «Column».fkEntity instead.
            real.foreignKeys["mailing_list_id"] shouldBe "mailing_list"
            real.foreignKeys["sent_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("mailing_list_id")?.foreignKey?.targetEntityId ?: "") shouldBe "mailing_list"
            model.entityNameOf(entity.attributeByName("sent_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("mailing_delivery_log table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "mailing_delivery_log" }
            val real = transaction { introspectCommunicationTable("mailing_delivery_log") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // Both FKs on this table match UmlToErmTransformer's association-derived default
            // name exactly ("mailing_message_id" / "member_id"), so both are real UML
            // associations.
            real.foreignKeys["mailing_message_id"] shouldBe "mailing_message"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("mailing_message_id")?.foreignKey?.targetEntityId ?: "") shouldBe "mailing_message"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("direct_message table shape matches the real schema (sender_id/recipient_id as plain columns, two-FK-collision gap pinned)") {
            val entity = model.entities.single { it.name == "direct_message" }
            val real = transaction { introspectCommunicationTable("direct_message") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // The two-associations-to-the-same-target collision case flagged in the retrofit
            // plan and in UmlToErmTransformer.addForeignKey's own KDoc. Empirically verified
            // during implementation (against both this script and
            // UmlToErmAssociationTest's own "two FK associations from the same class to the same
            // target disambiguate via role names" fixture) that the FIRST declared association of
            // such a pair always claims the plain class-derived default name ("member_id")
            // regardless of role, and only the SECOND falls back to a role-derived name — so a
            // real association pair here would never resolve to "sender_id" AND "recipient_id"
            // together (one side always ends up "member_id"). Both FKs are therefore modelled as
            // plain «Column» UUID attributes instead, per the retrofit plan's risk-note fallback
            // strategy, pinned via «Column».fkEntity.
            real.foreignKeys["sender_id"] shouldBe "member"
            real.foreignKeys["recipient_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("sender_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            model.entityNameOf(entity.attributeByName("recipient_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("mailing_list entity column-name set matches the hand-written MailingListTable 1:1") {
            model.entities
                .single { it.name == "mailing_list" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MailingListTable.columns.map { it.name }
        }

        test("mailing_list_subscription entity column-name set matches the hand-written MailingListSubscriptionTable 1:1") {
            model.entities
                .single { it.name == "mailing_list_subscription" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MailingListSubscriptionTable.columns.map { it.name }
        }

        test("mailing_message entity column-name set matches the hand-written MailingMessageTable 1:1") {
            model.entities
                .single { it.name == "mailing_message" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MailingMessageTable.columns.map { it.name }
        }

        test("mailing_delivery_log entity column-name set matches the hand-written MailingDeliveryLogTable 1:1") {
            model.entities
                .single { it.name == "mailing_delivery_log" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MailingDeliveryLogTable.columns.map { it.name }
        }

        test("direct_message entity column-name set matches the hand-written DirectMessageTable 1:1") {
            model.entities
                .single { it.name == "direct_message" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DirectMessageTable.columns.map { it.name }
        }

        test("mailing_message.status is modelled as a real ErmDataType.Enum column") {
            // Same gap-closure as MemberStatus/AccountRole/BillingInterval/ContributionStatus/
            // DocumentAccessLevel in the prior domains — with the «Column».sqlType override
            // removed, kUML's enum-to-Enum+CHECK fallback path applies.
            val status = model.entities.single { it.name == "mailing_message" }.attributeByName("status")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "MailingMessageStatus",
                    values = listOf("DRAFT", "QUEUED", "SENT", "FAILED"),
                    externalFqName = "network.lapis.cloud.shared.domain.MailingMessageStatus",
                )
        }

        test("mailing_delivery_log.delivery_status is modelled as a real ErmDataType.Enum column") {
            val deliveryStatus =
                model.entities
                    .single { it.name == "mailing_delivery_log" }
                    .attributeByName("delivery_status")
            deliveryStatus?.type shouldBe
                ErmDataType.Enum(
                    name = "DeliveryStatus",
                    values = listOf("SENT", "BOUNCED", "SKIPPED_UNSUBSCRIBED"),
                    externalFqName = "network.lapis.cloud.shared.domain.DeliveryStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedCommunicationTable(
    val columns: Map<String, IntrospectedCommunicationColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedCommunicationColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [DocumentSchemaDriftTest]'s (private,
 * document-domain-scoped) `introspectDocumentTable`.
 */
private fun JdbcTransaction.introspectCommunicationTable(tableName: String): IntrospectedCommunicationTable {
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

    // Detects both inline CONSTRAINT ... UNIQUE and standalone CREATE UNIQUE INDEX (generated via
    // a class-level «Index») — H2's information_schema.table_constraints only surfaces the
    // former, never a plain named unique index, so both sources are unioned.
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

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedCommunicationColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedCommunicationTable(
        columns = columns,
        foreignKeys = fkByColumn,
        compositeUniqueConstraints = compositeUniques,
    )
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

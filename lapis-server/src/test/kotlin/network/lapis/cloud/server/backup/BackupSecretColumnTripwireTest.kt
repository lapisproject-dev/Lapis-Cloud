package network.lapis.cloud.server.backup

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.274 -- every exported column that holds sealed or key-derived material is a conscious decision. A column named `*_ciphertext`, `*_sealed` or
 * `*_fingerprint` must either be redacted in the backup ([OrganizationSchemaCatalog.REDACTED_COLUMNS]) or be listed here with the reason it travels.
 * The allow list documents the state of the day for the other sealed values (bank, FinTS, webhooks, accounting export): they are NOT changed by this
 * wave, only reported. A new column therefore turns the build red until someone decides.
 */
class BackupSecretColumnTripwireTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val suffixes = listOf("_ciphertext", "_sealed", "_fingerprint")

        /** column name -> why it is exported as is. Not a recommendation: the current state, to be revisited on its own. */
        val allowed: Map<String, String> =
            mapOf(
                "counterparty_iban_ciphertext" to
                    "bank statement line: sealed IBAN of the counterparty, bound to the instance key (open: decide on its own)",
                "debtor_iban_ciphertext" to "SEPA mandate: sealed IBAN of the debtor (open: decide on its own)",
                "refund_iban_ciphertext" to "travel expense report: sealed refund IBAN (open: decide on its own)",
                "fints_user_id_ciphertext" to "bank account: sealed FinTS user id (open: decide on its own)",
                "fints_pin_ciphertext" to "bank account: sealed FinTS PIN (open: decide on its own)",
                "secret_sealed" to "webhook endpoint: sealed signing secret (open: decide on its own)",
                "token_ciphertext" to "accounting export connection: sealed API token (open: decide on its own)",
            )

        test("every exported *_ciphertext / *_sealed / *_fingerprint column is redacted or deliberately allowed") {
            val unaccounted =
                transaction {
                    OrganizationSchemaCatalog
                        .exportableTables(this)
                        .flatMap { table -> table.columns.map { table.tableName to it.name } }
                        .filter { (_, column) -> suffixes.any { column.endsWith(it) } }
                        .filter { (table, column) ->
                            OrganizationSchemaCatalog.REDACTED_COLUMNS[table]?.containsKey(column) != true && column !in allowed
                        }
                }
            withClue("decide: redact in OrganizationSchemaCatalog.REDACTED_COLUMNS or allow here with a reason: $unaccounted") {
                unaccounted.shouldBeEmpty()
            }
        }

        test("every redaction names a column that really exists in an exported table (a rename cannot silently drop it)") {
            val real =
                transaction {
                    OrganizationSchemaCatalog.exportableTables(this).associate { t -> t.tableName to t.columns.map { it.name }.toSet() }
                }
            OrganizationSchemaCatalog.REDACTED_COLUMNS.forEach { (table, columns) ->
                withClue("table $table") { (table in real) shouldBe true }
                columns.keys.forEach { column -> withClue("$table.$column") { (column in real.getValue(table)) shouldBe true } }
            }
        }

        test("the stream key columns are exactly the redacted ones") {
            OrganizationSchemaCatalog.REDACTED_COLUMNS
                .mapValues { it.value.keys }
                .toMap() shouldBe
                mapOf(
                    "conference_stream_target" to setOf("broadcast_key_ciphertext", "url_fingerprint"),
                    "conference_stream_destination" to setOf("stream_key_ciphertext", "stream_key_set_at"),
                )
        }
    })

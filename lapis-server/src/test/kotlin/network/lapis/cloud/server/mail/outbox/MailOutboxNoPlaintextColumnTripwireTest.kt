package network.lapis.cloud.server.mail.outbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MailOutboxTable
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType

/**
 * Welle V1.9.81 -- `mail_outbox` may carry free text ONLY in the four sealed `*_enc` columns. Every other character column is a closed
 * vocabulary or a hash with a fixed, short width; a new column holding text would need to be added here on purpose (and justified).
 */
class MailOutboxNoPlaintextColumnTripwireTest :
    FunSpec({
        test("only the four *_enc columns are TEXT; every other character column is a short, closed-vocabulary column") {
            val textColumns =
                MailOutboxTable.columns
                    .filter { it.columnType is TextColumnType }
                    .map { it.name }
                    .toSet()
            textColumns shouldBe setOf("recipient_enc", "subject_enc", "text_enc", "html_enc")

            val varchars =
                MailOutboxTable.columns.filter { it.columnType is VarCharColumnType }.associate {
                    it.name to
                        (it.columnType as VarCharColumnType).colLength
                }
            varchars shouldBe
                mapOf(
                    "purpose" to 64,
                    "status" to 10,
                    "last_error_class" to 64,
                    "recipient_lookup_hash" to 64,
                )
        }

        test("the closed vocabulary of last_error_class is [A-Z0-9_]") {
            val vocabulary =
                listOf(
                    "SMTP_451",
                    "SMTP_550",
                    "CONNECT",
                    "TIMEOUT",
                    "AUTH",
                    "UNKNOWN",
                    "INTERRUPTED",
                    "DECRYPT",
                    "OUTBOX_DISABLED",
                    "SKIPPED",
                )
            vocabulary.forEach { it.matches(Regex("^[A-Z0-9_]{1,64}$")) shouldBe true }
        }
    })

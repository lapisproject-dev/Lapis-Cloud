// Welle V1.9.81 -- hourly e-mail send budget and durable outbox for system mails (V80__mail_budget_and_outbox.sql). See
// network.lapis.cloud.server.mail.outbox.* / network.lapis.cloud.server.mail.budget.* and
// docs/architecture/mail-delivery-budget-and-outbox.adoc for the rationale.
//
// **Three new tables.**
//   * `mail_outbox` -- one row per queued system mail. The payload (recipient, subject, text and html body) exists ONLY as AES-GCM ciphertext
//     in the four `*_enc` columns and ONLY while the row is open (QUEUED / SENDING); a final row (SENT / FAILED / EXPIRED) carries NULL in
//     all of them and in `recipient_lookup_hash` (two CHECK constraints in the SQL enforce it -- they are not expressible in the ERM profile).
//     `recipient_lookup_hash` is an HMAC of the lower-cased address under a purpose-separated sub-key: it lets the data-subject export/erasure
//     find OPEN rows without ever decrypting. `last_error_class` is a closed vocabulary (SMTP_4xx / SMTP_5xx with the code, CONNECT, TIMEOUT,
//     AUTH, INTERRUPTED, DECRYPT, OUTBOX_DISABLED, UNKNOWN), never an exception message (those can echo addresses).
//   * `mail_send_slot` -- one row per reserved slot of the sliding 3600 s budget window (`reserved_at` is class A).
//   * `mail_budget_lock` -- singleton (id = 1): serialises slot reservation via SELECT ... FOR UPDATE and carries the global bulk pause.
//
// Status / lane / purpose columns are plain VARCHAR strings (no Exposed enumerations), same as every status column of this schema family
// since V1.9.57; their CHECK constraints live in the SQL only. No FK to `member` on purpose: a mail row must survive (and be erasable
// independently of) the member row, and a final row holds no personal data at all.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MailOutbox") {
    applyProfile(ermMappingProfile)

    val mailOutbox = classOf(name = "MailOutbox") {
        stereotype("Entity") { "tableName" to "mail_outbox"; "kotlinObjectName" to "MailOutboxTable" }
        stereotype("Index") { "columns" to listOf("status", "priority", "next_attempt_at"); "name" to "idx_mail_outbox_due" }
        stereotype("Index") { "columns" to listOf("recipient_lookup_hash"); "name" to "idx_mail_outbox_lookup" }
        stereotype("Index") { "columns" to listOf("status", "finished_at"); "name" to "idx_mail_outbox_finished" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "purpose", type = "String") {
            stereotype("Column") { "columnName" to "purpose"; "sqlType" to "VARCHAR(64)" }
        }
        // 0 = security-relevant, short-lived (password reset, e-mail change ...); 1 = everything else
        attribute(name = "priority", type = "Int") {
            stereotype("Column") { "columnName" to "priority"; "sqlType" to "SMALLINT" }
        }
        // QUEUED | SENDING | SENT | FAILED | EXPIRED
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "attemptCount", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "attempt_count" }
        }
        attribute(name = "nextAttemptAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "next_attempt_at" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "claimedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "claimed_at" }
        }
        attribute(name = "finishedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "finished_at" }
        }
        attribute(name = "lastErrorClass", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_error_class"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "recipientEnc", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "recipient_enc"; "sqlType" to "TEXT" }
        }
        attribute(name = "subjectEnc", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "subject_enc"; "sqlType" to "TEXT" }
        }
        attribute(name = "textEnc", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "text_enc"; "sqlType" to "TEXT" }
        }
        attribute(name = "htmlEnc", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "html_enc"; "sqlType" to "TEXT" }
        }
        attribute(name = "recipientLookupHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "recipient_lookup_hash"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "logRecipient", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "log_recipient" }
        }
    }

    val mailSendSlot = classOf(name = "MailSendSlot") {
        stereotype("Entity") { "tableName" to "mail_send_slot"; "kotlinObjectName" to "MailSendSlotTable" }
        stereotype("Index") { "columns" to listOf("reserved_at"); "name" to "idx_mail_send_slot_reserved" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "reservedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "reserved_at" }
        }
        // SYSTEM | BULK
        attribute(name = "lane", type = "String") {
            stereotype("Column") { "columnName" to "lane"; "sqlType" to "VARCHAR(6)" }
        }
    }

    val mailBudgetLock = classOf(name = "MailBudgetLock") {
        stereotype("Entity") { "tableName" to "mail_budget_lock"; "kotlinObjectName" to "MailBudgetLockTable" }

        // singleton: the CHECK (id = 1) lives in the SQL only
        attribute(name = "id", type = "Int") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id"; "sqlType" to "SMALLINT" }
        }
        attribute(name = "bulkPausedUntil", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "bulk_paused_until" }
        }
    }
}

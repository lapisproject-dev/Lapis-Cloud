// Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- accounting_export_connection/_run/_item/_category_map
// (V25__accounting_export.sql). Sibling of 40-bank-statement.kuml.kts (V1.4.5.1) and the DATEV
// file-export (V1.4.5.2, no new tables of its own -- see V22__datev_export.sql header). Where DATEV
// produces a downloadable CSV file for a human to hand to a Steuerberater, this file's tables track
// a LIVE, provider-authenticated push of the same underlying journal to an external bookkeeping
// SaaS (lexoffice first, sevDesk planned for V1.4.5.4 behind the same
// `AccountingExportProviderAdapter` interface -- see that interface's own KDoc in
// `network.lapis.cloud.server.accounting.export`).
//
// **Why a partial unique index (`CREATE UNIQUE INDEX ... WHERE`) is NOT used here.** The obvious
// idempotency anchor -- "a journal_entry is exported to a given provider at most once, but only
// while SUCCEEDED" -- reads naturally as a partial unique index. That is NOT portable to this
// repo's test suite: the WHOLE suite runs against H2 in MODE=PostgreSQL (`DatabaseConfig.kt`), and
// H2 rejects both the partial-index syntax itself AND the generated-column cross-dialect
// workaround (Postgres wants STORED, H2 rejects STORED) -- empirically verified twice already, see
// V8__sepa_mandates.sql (lines ~70-88) and V9__dunning.sql. This file uses the SAME portable
// alternative V18__events.sql already established for `event_registration.active_participant_key`:
// an application-maintained, NULLABLE shadow column plus a PLAIN unique index. Multiple `NULL`s are
// allowed under a unique index on both H2 and real Postgres (the same property
// `uq_crm_contact_email`/`uq_event_registration_active_participant` already rely on), so the shadow
// column is NULL whenever the row does NOT (yet, or any longer) hold the invariant, and set to a
// deterministic key otherwise. TWO such shadow columns exist below:
//   - `AccountingExportItem.exportedKey` -- NULL unless `status == SUCCEEDED`, then
//     `"<PROVIDER>:<journalEntryId>"`. Enforces "at most one SUCCEEDED export per (provider,
//     journal_entry)".
//   - `AccountingExportRun.activeKey` -- NULL unless `status` is non-terminal (PLANNED/RUNNING),
//     then the provider name itself. Enforces "at most one non-terminal run per provider" (there is
//     only ever one provider active at a time in this wave, but the key is provider-scoped so a
//     future second provider running concurrently is not accidentally blocked by this one).
// Both columns are written EXCLUSIVELY by `AccountingExportStore` -- same discipline V18's own
// header states for `active_participant_key`. See `docs/architecture/accounting-export-lexoffice.adoc`
// "Portability note: no partial unique index" for the full writeup.
//
// **Why `next_attempt_at` exists on `AccountingExportItem`** even though the Design-Memo this wave's
// plan responds to did not name it: lexoffice's rate limit (2 req/s, token bucket, see "Verified API
// facts" in the adoc above) means a `Retryable` outcome (429/5xx/timeout) needs a backed-off retry
// (2s/4s/8s, `AccountingExportPoller`), and a poller with NO in-memory state (same "every phase
// re-queries its candidates fresh every tick" discipline `WebhookDeliveryPoller` establishes) can
// only express "not due yet" as a persisted column, not a scheduled coroutine delay.
//
// **Why `direction` is its own two-value enum, not a reuse of `LedgerAccountType`.** `LedgerAccountType`
// (00-foundation.kuml.kts/10-accounting.kuml.kts) has five values (ASSET/LIABILITY/EQUITY/
// INCOME/EXPENSE); an `AccountingExportItem` structurally can only ever be a sales voucher (income
// side) or a purchase voucher (expense side) -- the other three values must never appear here. A
// dedicated `AccountingExportDirection { INCOME, EXPENSE }` makes that a TYPE guarantee rather than
// a CHECK constraint that merely forbids three values a five-valued column could otherwise carry.
//
// **Scope cuts this wave** (see plan "Umsetzungsplan V1.4.5.3" §9 "Scope cuts" for the full list):
// sevDesk (V1.4.5.4, behind the same adapter interface), no read-back/reconciliation sync from
// lexoffice, no USt-Schluessel modelling (every voucher travels at 0% -- see
// `network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer`), no contact/Stammdaten sync
// (`useCollectiveContact = true` always, never `contactId`), no file/receipt attachment upload.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "AccountingExport") {
    applyProfile(ermMappingProfile)

    // Cross-domain stubs -- id-only, mirror the pattern established across every prior domain file.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }
    val journalEntry = classOf(name = "JournalEntry") {
        stereotype("Entity") { "tableName" to "journal_entry"; "kotlinObjectName" to "JournalEntryTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }
    val ledgerAccount = classOf(name = "LedgerAccount") {
        stereotype("Entity") { "tableName" to "ledger_account"; "kotlinObjectName" to "LedgerAccountTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order load-bearing (AccountingExportSchemaDriftTest pins it against
    // network.lapis.cloud.shared.domain.AccountingExportProvider). Longest literal LEXOFFICE (9) ->
    // VARCHAR(9). Only one literal this wave -- sevDesk (V1.4.5.4) adds the second.
    val accountingExportProvider = enumOf(name = "AccountingExportProvider") { literal(name = "LEXOFFICE") }

    // Literal order load-bearing, same reason. Longest literal COMPLETED_WITH_ERRORS (22) ->
    // VARCHAR(22).
    val accountingExportRunStatus =
        enumOf(name = "AccountingExportRunStatus") {
            literal(name = "PLANNED")
            literal(name = "RUNNING")
            literal(name = "COMPLETED")
            literal(name = "COMPLETED_WITH_ERRORS")
            literal(name = "ABORTED")
        }

    // Literal order load-bearing, same reason. Longest literal SKIPPED_ALREADY_EXPORTED (25) ->
    // VARCHAR(25).
    val accountingExportItemStatus =
        enumOf(name = "AccountingExportItemStatus") {
            literal(name = "PENDING")
            literal(name = "SENDING")
            literal(name = "SUCCEEDED")
            literal(name = "FAILED")
            literal(name = "SKIPPED_ALREADY_EXPORTED")
            literal(name = "UNKNOWN")
        }

    // Literal order load-bearing, same reason. Longest literal EXPENSE (7) -> VARCHAR(7). See file
    // header "Why direction is its own two-value enum".
    val accountingExportDirection =
        enumOf(name = "AccountingExportDirection") {
            literal(name = "INCOME")
            literal(name = "EXPENSE")
        }

    val accountingExportConnection =
        classOf(name = "AccountingExportConnection") {
            stereotype("Entity") {
                "tableName" to "accounting_export_connection"
                "kotlinObjectName" to "AccountingExportConnectionTable"
            }
            stereotype("Index") {
                "columns" to listOf("provider")
                "unique" to true
                "name" to "uq_accounting_export_connection_provider"
            }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "provider", type = accountingExportProvider) {
                stereotype("Column") {
                    "columnName" to "provider"
                    "enumType" to "network.lapis.cloud.shared.domain.AccountingExportProvider"
                }
            }
            // SecretBox(AES-256-GCM)-sealed, AAD = this row's own id (see class KDoc "AAD binding to
            // the owning row" in SecretBox) -- the id therefore MUST stay stable across a token
            // replacement (UPDATE, never DELETE+INSERT). Never returned to a client -- only
            // tokenLast4 is.
            attribute(name = "tokenCiphertext", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "token_ciphertext"; "sqlType" to "VARCHAR(1024)" }
            }
            attribute(name = "tokenLast4", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "token_last4"; "sqlType" to "VARCHAR(4)" }
            }
            // Filled from GET /v1/profile's companyName on a successful testConnection -- the
            // "Verbunden mit: ..." confirmation the design team required (Kare).
            attribute(name = "connectedCompanyName", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "connected_company_name"; "sqlType" to "VARCHAR(300)" }
            }
            attribute(name = "lastTestedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "last_tested_at" }
            }
            // The Jobs "einmal, nicht bei jedem Lauf" zero-VAT-disclaimer quittance -- see
            // network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer. All three columns are set
            // together, cleared together only if the whole row is ever deleted (removeToken deliberately
            // does NOT delete the row -- see AccountingExportStore.removeToken KDoc "Stolperfalle 9").
            attribute(name = "zeroVatAcknowledgedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "zero_vat_acknowledged_at" }
            }
            attribute(name = "zeroVatAcknowledgedBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "zero_vat_acknowledged_by"; "fkEntity" to "Member" }
            }
            attribute(name = "zeroVatDisclaimerVersion", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "zero_vat_disclaimer_version"; "sqlType" to "VARCHAR(50)" }
            }
            attribute(name = "zeroVatDisclaimerSha256", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "zero_vat_disclaimer_sha256"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "updatedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "updated_at" }
            }
        }

    val accountingExportRun =
        classOf(name = "AccountingExportRun") {
            stereotype("Entity") { "tableName" to "accounting_export_run"; "kotlinObjectName" to "AccountingExportRunTable" }
            stereotype("Index") {
                "columns" to listOf("active_key")
                "unique" to true
                "name" to "uq_accounting_export_run_active"
            }
            stereotype("Index") { "columns" to listOf("started_at"); "name" to "idx_accounting_export_run_started" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "provider", type = accountingExportProvider) {
                stereotype("Column") {
                    "columnName" to "provider"
                    "enumType" to "network.lapis.cloud.shared.domain.AccountingExportProvider"
                }
            }
            attribute(name = "periodFrom", type = "LocalDate") {
                stereotype("Column") { "columnName" to "period_from" }
            }
            attribute(name = "periodTo", type = "LocalDate") {
                stereotype("Column") { "columnName" to "period_to" }
            }
            attribute(name = "status", type = accountingExportRunStatus) {
                stereotype("Column") {
                    "columnName" to "status"
                    "enumType" to "network.lapis.cloud.shared.domain.AccountingExportRunStatus"
                }
            }
            // See file header "Why a partial unique index is NOT used here" -- NULL unless status is
            // non-terminal (PLANNED/RUNNING). Written EXCLUSIVELY by AccountingExportStore.
            attribute(name = "activeKey", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "active_key"; "sqlType" to "VARCHAR(16)" }
            }
            attribute(name = "startedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "started_by"; "fkEntity" to "Member" }
            }
            attribute(name = "startedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "started_at" }
            }
            attribute(name = "finishedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "finished_at" }
            }
            attribute(name = "totalCount", type = "Int") {
                stereotype("Column") { "columnName" to "total_count" }
            }
            attribute(name = "succeededCount", type = "Int") {
                stereotype("Column") { "columnName" to "succeeded_count" }
            }
            attribute(name = "failedCount", type = "Int") {
                stereotype("Column") { "columnName" to "failed_count" }
            }
            attribute(name = "skippedCount", type = "Int") {
                stereotype("Column") { "columnName" to "skipped_count" }
            }
            attribute(name = "unknownCount", type = "Int") {
                stereotype("Column") { "columnName" to "unknown_count" }
            }
        }

    val accountingExportItem =
        classOf(name = "AccountingExportItem") {
            stereotype("Entity") { "tableName" to "accounting_export_item"; "kotlinObjectName" to "AccountingExportItemTable" }
            stereotype("Index") {
                "columns" to listOf("exported_key")
                "unique" to true
                "name" to "uq_accounting_export_item_exported"
            }
            stereotype("Index") {
                "columns" to listOf("run_id", "status")
                "name" to "idx_accounting_export_item_run_status"
            }
            stereotype("Index") {
                "columns" to listOf("journal_entry_id")
                "name" to "idx_accounting_export_item_journal"
            }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "runId", type = "UUID") {
                stereotype("Column") { "columnName" to "run_id"; "fkEntity" to "AccountingExportRun" }
            }
            attribute(name = "provider", type = accountingExportProvider) {
                stereotype("Column") {
                    "columnName" to "provider"
                    "enumType" to "network.lapis.cloud.shared.domain.AccountingExportProvider"
                }
            }
            attribute(name = "journalEntryId", type = "UUID") {
                stereotype("Column") { "columnName" to "journal_entry_id"; "fkEntity" to "JournalEntry" }
            }
            // Snapshot of journal_entry.entry_date at planning time -- the item row needs this for
            // OutboundVoucher.voucherDate at send time without re-joining journal_entry on every
            // poller tick. A snapshot, not a live re-read: the entry is POSTED (immutable) by the
            // time it is ever planned, so entryDate can never drift after the fact.
            attribute(name = "entryDate", type = "LocalDate") {
                stereotype("Column") { "columnName" to "entry_date" }
            }
            // Snapshot of the resolved accounting_export_category_map.external_category_id at
            // planning time -- see AccountingExportPlanner KDoc. Deliberately a snapshot, NOT a
            // live join to accounting_export_category_map at send time: a mapping changed by a
            // TREASURER AFTER a run started must not silently change what an already-planned,
            // in-flight item sends -- the preview the treasurer approved is what gets sent.
            attribute(name = "externalCategoryId", type = "String") {
                stereotype("Column") { "columnName" to "external_category_id"; "sqlType" to "VARCHAR(64)" }
            }
            // Deterministic, locally-assigned voucher reference -- see
            // network.lapis.cloud.server.accounting.export.voucherNumber KDoc. 23 characters
            // ("LAPIS-YYYYMMDD-" + 8 hex chars of the journal_entry id), VARCHAR(32) leaves headroom.
            attribute(name = "voucherNumber", type = "String") {
                stereotype("Column") { "columnName" to "voucher_number"; "sqlType" to "VARCHAR(32)" }
            }
            attribute(name = "direction", type = accountingExportDirection) {
                stereotype("Column") {
                    "columnName" to "direction"
                    "enumType" to "network.lapis.cloud.shared.domain.AccountingExportDirection"
                }
            }
            attribute(name = "grossAmount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "gross_amount"; "sqlType" to "DECIMAL(14,2)" }
            }
            attribute(name = "status", type = accountingExportItemStatus) {
                stereotype("Column") {
                    "columnName" to "status"
                    "enumType" to "network.lapis.cloud.shared.domain.AccountingExportItemStatus"
                }
            }
            // See file header "Why a partial unique index is NOT used here" -- NULL unless
            // status == SUCCEEDED. Written EXCLUSIVELY by AccountingExportStore.
            attribute(name = "exportedKey", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "exported_key"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "externalVoucherId", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "external_voucher_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "errorCode", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "error_code"; "sqlType" to "VARCHAR(50)" }
            }
            // Only ever the provider's OWN sanitized error message, never a raw response body and
            // never journal_entry.description -- see AccountingExportPoller class KDoc "DSGVO".
            attribute(name = "errorMessage", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "error_message"; "sqlType" to "VARCHAR(500)" }
            }
            attribute(name = "attempts", type = "Int") {
                stereotype("Column") { "columnName" to "attempts" }
            }
            // See file header "Why next_attempt_at exists" -- the persisted backoff clock.
            attribute(name = "nextAttemptAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "next_attempt_at" }
            }
            attribute(name = "claimedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "claimed_at" }
            }
            attribute(name = "finishedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "finished_at" }
            }
        }

    val accountingExportCategoryMap =
        classOf(name = "AccountingExportCategoryMap") {
            stereotype("Entity") {
                "tableName" to "accounting_export_category_map"
                "kotlinObjectName" to "AccountingExportCategoryMapTable"
            }
            stereotype("Index") {
                "columns" to listOf("provider", "ledger_account_id")
                "unique" to true
                "name" to "uq_accounting_export_category_map"
            }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "provider", type = accountingExportProvider) {
                stereotype("Column") {
                    "columnName" to "provider"
                    "enumType" to "network.lapis.cloud.shared.domain.AccountingExportProvider"
                }
            }
            attribute(name = "ledgerAccountId", type = "UUID") {
                stereotype("Column") { "columnName" to "ledger_account_id"; "fkEntity" to "LedgerAccount" }
            }
            attribute(name = "externalCategoryId", type = "String") {
                stereotype("Column") { "columnName" to "external_category_id"; "sqlType" to "VARCHAR(64)" }
            }
            // Display-only, so the mapping UI can show a human name without a second round-trip to
            // listCategories() on every render -- never used for any matching/lookup logic.
            attribute(name = "externalCategoryName", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "external_category_name"; "sqlType" to "VARCHAR(200)" }
            }
            attribute(name = "mappedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "mapped_by"; "fkEntity" to "Member" }
            }
            attribute(name = "mappedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "mapped_at" }
            }
        }

    // Deliberately NO association() for run_id/journal_entry_id/ledger_account_id/started_by/
    // mapped_by/zero_vat_acknowledged_by -- every one of them is already an explicit «Column».fkEntity
    // attribute above. Adding an association() on top would make UmlToErmTransformer ALSO
    // auto-derive a second, redundant FK column from the association's own default naming --
    // exactly the V1.4.5.1 mistake 40-bank-statement.kuml.kts's own header (lines ~262-271)
    // documents and warns against repeating.
}

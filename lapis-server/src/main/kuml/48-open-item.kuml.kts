// Open-item (Kreditoren-/Debitorenbuchhaltung) domain -- open_item/open_item_netting/
// open_item_settlement/receivable_dunning_level/receivable_dunning_notice
// (V35__open_items.sql), plus three new `organization_settings` columns forward-declared here
// and carried for real in 11-organization-settings.kuml.kts (OrganizationSettings is that file's
// owning domain -- see that file's own file header convention for why the real declaration
// always lives with the owning entity, never here).
//
// Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- open_item tracks a single payable or
// receivable line (OpenItemDirection: PAYABLE/RECEIVABLE, OpenItemStatus:
// OPEN/PARTIALLY_SETTLED/SETTLED/CANCELLED). Two-booking rule (Jobs/Atkinson-Ruling): a journal
// entry is posted at CREATION time already (not only when it is marked paid), because a netting
// against another counterparty's open item needs BOTH sides already booked -- see
// OpenItemPostingBridge KDoc. open_item_settlement records either a PAYMENT or a NETTING against
// exactly one open_item; open_item_netting records the pairing of exactly one payable and one
// receivable item settled against each other in a single booking. receivable_dunning_level/
// receivable_dunning_notice are a SECOND, fully independent dunning domain (see 34-dunning.kuml.kts
// file header for the pre-existing member-contribution dunning domain this deliberately does not
// touch/extend/polymorphise) -- see docs/architecture/open-items.adoc for the full rationale.
//
// No partial unique index (same portability constraint every other wave in this repo documents):
// the whole test suite runs H2 in MODE=PostgreSQL (DatabaseConfig.kt), which rejects
// `CREATE UNIQUE INDEX ... WHERE`. Every idempotency anchor here (creation/cancellation journal
// entry, netting journal entry) is instead a PLAIN (not partial) unique index directly on the
// nullable column -- multiple NULLs are allowed under a plain unique index on both H2 and real
// Postgres, same property `uq_ter_posted_journal_entry` already relies on. The
// double-netting guard (`uq_ois_netting_item` on `(netting_id, open_item_id)`) uses the exact
// same trick: every PAYMENT-kind settlement row has `netting_id = NULL`, so many of those may
// coexist, but a given `(netting_id, open_item_id)` pair for a NETTING-kind row can only ever
// exist once.
//
// No circular FK: open_item -> open_item_netting -> open_item_settlement is a strict order
// (open_item_netting.payable_item_id/receivable_item_id -> open_item,
// open_item_settlement.netting_id -> open_item_netting), no cycle for
// `OrganizationSchemaCatalog.restoreOrder`'s topological sort to resolve.
//
// Deliberately NO `association(...)` blocks -- every FK on these five entities is already
// generated from its own explicit «Column».fkEntity tag above, exactly the mechanism
// 45-travel-expense.kuml.kts's own file header documents for why a redundant `association(...)`
// block would synthesize a SECOND, independently-named FK column.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "OpenItem") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors the cross-domain-stub pattern every other domain
    // file establishes. Only exists here so UmlToErmTransformer can resolve
    // open_item.created_by_member_id / .cancelled_by_member_id, open_item_netting
    // .created_by_member_id / .reversed_by_member_id, open_item_settlement.created_by_member_id /
    // .reversed_by_member_id, receivable_dunning_notice.created_by_member_id within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 10-accounting.kuml.kts-owned stub -- id-only. Only exists here so UmlToErmTransformer can
    // resolve open_item.creation_journal_entry_id / .cancellation_journal_entry_id,
    // open_item_netting.journal_entry_id / .reversal_journal_entry_id,
    // open_item_settlement.journal_entry_id / .reversal_journal_entry_id,
    // receivable_dunning_notice.fee_journal_entry_id.
    val journalEntry = classOf(name = "JournalEntry") {
        stereotype("Entity") { "tableName" to "journal_entry"; "kotlinObjectName" to "JournalEntryTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 38-crm.kuml.kts-owned stub -- id-only. Only exists here so UmlToErmTransformer can resolve
    // open_item.crm_contact_id / open_item_netting.crm_contact_id.
    val crmContact = classOf(name = "CrmContact") {
        stereotype("Entity") { "tableName" to "crm_contact"; "kotlinObjectName" to "CrmContactTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 02-document.kuml.kts-owned stub -- id-only. Only exists here so UmlToErmTransformer can
    // resolve receivable_dunning_notice.document_id.
    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 10-accounting.kuml.kts-owned stub -- id-only. Only exists here so UmlToErmTransformer can
    // resolve open_item.contra_account_id.
    val ledgerAccount = classOf(name = "LedgerAccount") {
        stereotype("Entity") { "tableName" to "ledger_account"; "kotlinObjectName" to "LedgerAccountTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing (OpenItemSchemaDriftTest pins ErmDataType.Enum.values against
    // network.lapis.cloud.shared.domain.OpenItemDirection in exactly this order) -- append only,
    // never reorder/insert among these two.
    val openItemDirection = enumOf(name = "OpenItemDirection") {
        literal(name = "PAYABLE")
        literal(name = "RECEIVABLE")
    }

    // Literal order load-bearing, same reason as openItemDirection.
    val openItemStatus = enumOf(name = "OpenItemStatus") {
        literal(name = "OPEN")
        literal(name = "PARTIALLY_SETTLED")
        literal(name = "SETTLED")
        literal(name = "CANCELLED")
    }

    // Literal order load-bearing, same reason as openItemDirection.
    val openItemSettlementKind = enumOf(name = "OpenItemSettlementKind") {
        literal(name = "PAYMENT")
        literal(name = "NETTING")
    }

    // Literal order load-bearing, same reason as openItemDirection.
    val receivableDunningNoticeStatus = enumOf(name = "ReceivableDunningNoticeStatus") {
        literal(name = "ISSUED")
        literal(name = "SKIPPED")
        literal(name = "CANCELLED")
    }

    val openItem = classOf(name = "OpenItem") {
        stereotype("Entity") { "tableName" to "open_item"; "kotlinObjectName" to "OpenItemTable" }

        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_oi_status" }
        stereotype("Index") { "columns" to listOf("direction"); "name" to "idx_oi_direction" }
        stereotype("Index") { "columns" to listOf("due_date"); "name" to "idx_oi_due_date" }
        stereotype("Index") { "columns" to listOf("counterparty_key"); "name" to "idx_oi_counterparty_key" }
        stereotype("Index") { "columns" to listOf("crm_contact_id"); "name" to "idx_oi_crm_contact" }
        stereotype("Index") { "columns" to listOf("due_date", "id"); "name" to "idx_oi_keyset" }
        // Buchungs-Idempotenz -- plain (nicht partieller) Unique-Index auf der nullable Spalte,
        // siehe Datei-Header.
        stereotype("Index") {
            "columns" to listOf("creation_journal_entry_id")
            "unique" to true
            "name" to "uq_oi_creation_journal_entry"
        }
        stereotype("Index") {
            "columns" to listOf("cancellation_journal_entry_id")
            "unique" to true
            "name" to "uq_oi_cancellation_journal_entry"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "direction", type = openItemDirection) {
            stereotype("Column") {
                "columnName" to "direction"
                "enumType" to "network.lapis.cloud.shared.domain.OpenItemDirection"
            }
        }
        attribute(name = "counterpartyName", type = "String") {
            stereotype("Column") { "columnName" to "counterparty_name"; "sqlType" to "VARCHAR(200)" }
        }
        // lower(trim(collapse_whitespace(counterpartyName))) -- computed in Kotlin
        // (network.lapis.cloud.shared.domain.CounterpartyKey.of), never a DB generated column.
        attribute(name = "counterpartyKey", type = "String") {
            stereotype("Column") { "columnName" to "counterparty_key"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "crmContactId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "crm_contact_id"; "fkEntity" to "CrmContact" }
        }
        attribute(name = "reference", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reference"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "itemDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "item_date" }
        }
        attribute(name = "dueDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "due_date" }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "contraAccountId", type = "UUID") {
            stereotype("Column") { "columnName" to "contra_account_id"; "fkEntity" to "LedgerAccount" }
        }
        attribute(name = "sphere", type = "String") {
            stereotype("Column") {
                "columnName" to "sphere"
                "enumType" to "network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere"
                "sqlType" to "VARCHAR(34)"
            }
        }
        attribute(name = "status", type = openItemStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.OpenItemStatus"
            }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        // Idempotenz-Anker -- non-null gdw. Buchung erfolgreich war.
        attribute(name = "creationJournalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "creation_journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "creationPostingError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "creation_posting_error"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "cancelledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_at" }
        }
        attribute(name = "cancelledByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "cancellationReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancellation_reason"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "cancellationJournalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancellation_journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
    }

    val openItemNetting = classOf(name = "OpenItemNetting") {
        stereotype("Entity") { "tableName" to "open_item_netting"; "kotlinObjectName" to "OpenItemNettingTable" }

        stereotype("Index") { "columns" to listOf("payable_item_id"); "name" to "idx_oin_payable_item" }
        stereotype("Index") { "columns" to listOf("receivable_item_id"); "name" to "idx_oin_receivable_item" }
        stereotype("Index") {
            "columns" to listOf("journal_entry_id")
            "unique" to true
            "name" to "uq_oin_journal_entry"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "counterpartyKey", type = "String") {
            stereotype("Column") { "columnName" to "counterparty_key"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "crmContactId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "crm_contact_id"; "fkEntity" to "CrmContact" }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "payableItemId", type = "UUID") {
            stereotype("Column") { "columnName" to "payable_item_id"; "fkEntity" to "OpenItem" }
        }
        attribute(name = "receivableItemId", type = "UUID") {
            stereotype("Column") { "columnName" to "receivable_item_id"; "fkEntity" to "OpenItem" }
        }
        attribute(name = "journalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "postingError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "posting_error"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "reversedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversed_at" }
        }
        attribute(name = "reversedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversed_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "reversalJournalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversal_journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "reversalReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversal_reason"; "sqlType" to "VARCHAR(500)" }
        }
    }

    val openItemSettlement = classOf(name = "OpenItemSettlement") {
        stereotype("Entity") { "tableName" to "open_item_settlement"; "kotlinObjectName" to "OpenItemSettlementTable" }

        stereotype("Index") { "columns" to listOf("open_item_id"); "name" to "idx_ois_open_item" }
        stereotype("Index") { "columns" to listOf("netting_id"); "name" to "idx_ois_netting" }
        // Negativtest-Anker gegen Doppelverrechnung -- siehe Datei-Header.
        stereotype("Index") {
            "columns" to listOf("netting_id", "open_item_id")
            "unique" to true
            "name" to "uq_ois_netting_item"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "openItemId", type = "UUID") {
            stereotype("Column") { "columnName" to "open_item_id"; "fkEntity" to "OpenItem" }
        }
        attribute(name = "kind", type = openItemSettlementKind) {
            stereotype("Column") {
                "columnName" to "kind"
                "enumType" to "network.lapis.cloud.shared.domain.OpenItemSettlementKind"
            }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "settledOn", type = "LocalDate") {
            stereotype("Column") { "columnName" to "settled_on" }
        }
        attribute(name = "nettingId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "netting_id"; "fkEntity" to "OpenItemNetting" }
        }
        attribute(name = "journalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "postingError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "posting_error"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "reversedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversed_at" }
        }
        attribute(name = "reversedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversed_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "reversalJournalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversal_journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "reversalReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reversal_reason"; "sqlType" to "VARCHAR(500)" }
        }
    }

    val receivableDunningLevel = classOf(name = "ReceivableDunningLevel") {
        stereotype("Entity") { "tableName" to "receivable_dunning_level"; "kotlinObjectName" to "ReceivableDunningLevelTable" }

        stereotype("Index") {
            "columns" to listOf("level_number")
            "unique" to true
            "name" to "uq_rdl_level_number"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "levelNumber", type = "Int") {
            stereotype("Column") { "columnName" to "level_number" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "graceDays", type = "Int") {
            stereotype("Column") { "columnName" to "grace_days" }
        }
        attribute(name = "responseDays", type = "Int") {
            stereotype("Column") { "columnName" to "response_days" }
        }
        attribute(name = "feeAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fee_amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "active", type = "Boolean") {
            stereotype("Column") { "columnName" to "active" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    val receivableDunningNotice = classOf(name = "ReceivableDunningNotice") {
        stereotype("Entity") { "tableName" to "receivable_dunning_notice"; "kotlinObjectName" to "ReceivableDunningNoticeTable" }

        stereotype("Index") { "columns" to listOf("open_item_id"); "name" to "idx_rdn_open_item" }
        // Idempotenzanker -- Zyklus-Zaehler statt partiellem Index, siehe Datei-Header.
        stereotype("Index") {
            "columns" to listOf("open_item_id", "cycle_number", "level_number")
            "unique" to true
            "name" to "uq_rdn_slot"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "openItemId", type = "UUID") {
            stereotype("Column") { "columnName" to "open_item_id"; "fkEntity" to "OpenItem" }
        }
        attribute(name = "receivableDunningLevelId", type = "UUID") {
            stereotype("Column") { "columnName" to "receivable_dunning_level_id"; "fkEntity" to "ReceivableDunningLevel" }
        }
        attribute(name = "cycleNumber", type = "Int") {
            stereotype("Column") { "columnName" to "cycle_number" }
        }
        attribute(name = "levelNumber", type = "Int") {
            stereotype("Column") { "columnName" to "level_number" }
        }
        attribute(name = "levelName", type = "String") {
            stereotype("Column") { "columnName" to "level_name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "feeAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fee_amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "amountDue", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_due"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "status", type = receivableDunningNoticeStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus"
            }
        }
        attribute(name = "issuedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "issued_at" }
        }
        attribute(name = "respondBy", type = "LocalDate") {
            stereotype("Column") { "columnName" to "respond_by" }
        }
        attribute(name = "documentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "feeJournalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fee_journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "createdByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "cancelledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_at" }
        }
        attribute(name = "cancellationReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancellation_reason"; "sqlType" to "VARCHAR(500)" }
        }
    }
}

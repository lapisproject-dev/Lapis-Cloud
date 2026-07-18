// Accounting domain (SKR42, V0.3.1, swapped from SKR49 in V0.3.1.1 -- see that wave's research
// notes) — ledger_account/journal_entry/posting, the double-entry bookkeeping core for the
// Gemeinnützigkeit accounting stack. Generated into V1__baseline.sql alongside every other domain
// (see 87563ff, which replaced the per-domain hand-written migrations with one generated
// baseline).
//
// SKR42 (DATEV's current Kontenrahmen for Vereine/Stiftungen/gGmbHs, based on SKR04; it replaced
// SKR49, which DATEV has maintained no further since 01.01.2025) uses a five-digit account number
// whose first digit is the Kontenklasse (0-9). Unlike SKR49, SKR42 deliberately does *not*
// pre-partition the four Gemeinnützigkeit spheres into account-number ranges, and the
// Kontenklassen do not separate income from expense the way a naive reading might suggest: class 4
// holds *all* Erträge/Umsatzerlöse across all four spheres (e.g. 4000 Mitgliedsbeiträge, 4045
// Spenden, 4200/4201 Erlöse/Eintrittsgelder), class 5 is itself an *expense* class (Wareneingang /
// Aufwendungen für Roh-, Hilfs- und Betriebsstoffe, e.g. 5000/5200 Wareneingang), class 6 covers
// the remaining operating expenses (e.g. 6000 Löhne und Gehälter, 6310 Miete), and class 7 is the
// Finanzergebnis (e.g. 7110 Zinserträge, 7300 Zinsaufwand) -- covering both income and expense
// sub-accounts. None of classes 4-7 are sphere-partitioned; the same account can be booked to any
// of the four spheres. Sphere is assigned per booking via a cost center (DATEV KOST1: 1 = ideeller
// Bereich, 2 = Vermögensverwaltung, 3 = Zweckbetrieb, 4 = wirtschaftlicher Geschäftsbetrieb; DATEV
// itself additionally defines 9 = Sammelposten, which this model deliberately does NOT offer -- see
// the dedicated comment ahead of the `posting` classOf below), *not* derived from `account_class`.
// `account_class` (Int, 0-9) remains a reporting/grouping field only; the real per-posting
// cost-center/sphere attribute is `posting.sphere` (V0.3.3, mandatory NOT NULL) -- see below.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the generated Exposed Table objects
// (network.lapis.cloud.server.db.generated.{LedgerAccount,JournalEntry,Posting}Table.kt) by
// AccountingSchemaDriftTest. The generated `db/generated/*.kt` files ARE the compiled/imported-by-
// N-files source since 4756e69 ("swap production persistence to kUML-generated Exposed tables") --
// this model is the versioned source of truth for schema *shape*, not a verification-only artifact
// pointing at a hand-written Table object (that framing is stale; it belonged to the pre-4756e69
// era and must not be copied into new domain files).
//
// Naming collision avoided on purpose: `Account`/`account`/`AccountTable` are already taken by the
// foundation domain's user-login account (00-foundation.kuml.kts: Account/AccountRole
// MEMBER/BOARD/TREASURER/ADMIN). The accounting "Konto" is therefore named `LedgerAccount` /
// `ledger_account` / `LedgerAccountTable` throughout -- and the service class is
// `AccountingService`, not `AccountService`.
//
// Cross-domain stub: minimal id-only Member (foundation-owned), same pattern as every prior
// domain's own Member stub -- purely so UmlToErmTransformer can resolve journal_entry.created_by's
// association target within this single-file evaluation.
//
// FK-column-naming-mismatch fallback (same gap class already discovered in every prior domain --
// association-derived default name snake_case(singular(targetClass))+"_id" doesn't match the real
// column name), modelled as a plain «Column» UUID attribute instead of a UML association:
// - journal_entry.created_by -> member (id), NOT NULL: default would be "member_id" (mirrors
//   election.opened_by / systemic_consensus.opened_by).
// Its real FK existence/target/nullability is still independently pinned via
// AccountingSchemaDriftTest's information_schema introspection against the real migrated schema.
//
// FKs that DO match the association-derived default and are modelled as real UML associations:
// posting.journal_entry_id -> journal_entry (id), posting.ledger_account_id -> ledger_account (id).
// Neither entity has more than one competing FK to the same target, so the
// first-declared-association-claims-the-bare-default mechanism never causes a collision problem
// here.
//
// Reserved-SQL-keyword pitfalls avoided (DDL is unquoted, postgres dialect; "value"/"date" are
// reserved -- same gap class as 09-systemic-consensus.kuml.kts's "value" -> "resistance_value" and
// election's own dedicated attribute names): journal_entry.entryDate is mapped to column
// "entry_date", NOT "date"; journal_entry.voucherReference is mapped to column
// "voucher_reference", NOT "reference" (also reserved). "description"/"name"/"type"/"status"/
// "amount"/"side"/"position" are all safe in postgres and used as-is.
//
// ledger_account.account_number's UNIQUE constraint: Exposed's association-to-FK/«Column» plumbing
// only expresses single-column `unique` on plain, non-association attributes -- and this IS a
// plain attribute (no association involved), so the more direct route would be
// «Column».unique -- but for consistency with every other domain's UNIQUE pattern (composite or
// single-column: account.member_id, member.email, contribution's composite, election's composite
// uniques) this is pinned via a class-level «Index» (single-column, unique=true) instead, which
// renders as a named CREATE UNIQUE INDEX rather than an inline column constraint -- semantically
// identical, and keeps every UNIQUE constraint in this codebase discoverable via the same
// «Index» mechanism.
//
// Balance invariant (Σdebit = Σcredit per journal_entry) is NOT modelled here at all -- it is a
// cross-row aggregate, which no CHECK constraint (single-row) nor «Index» can express. It is
// enforced exclusively at the service layer (network.lapis.cloud.server.rpc.AccountingService,
// backed by the pure network.lapis.cloud.server.rpc.JournalEntryBalance helper) inside the same
// transaction that writes journal_entry + its postings, so an unbalanced post rolls back
// atomically. See that helper's KDoc for the BigDecimal-compareTo-not-equals pitfall.
//
// Four-sphere Gemeinnützigkeit separation (V0.3.3, §§ 51-68 AO): posting.sphere is a mandatory
// (NOT NULL) enum column -- see gemeinnuetzigkeitSphere below and the dedicated comment ahead of
// the `posting` classOf. Placement rationale: per-POSTING (not journal_entry, not ledger_account),
// because (a) the file header above already establishes that sphere is NOT derivable from the
// SKR42 account/Kontenklasse -- it is assigned per booking via DATEV KOST1, which is a per-line
// cost center, and (b) a per-line attribute is the only placement that lets a single balanced
// journal entry legitimately record an inter-sphere transfer (Umbuchung): debit line sphere X,
// credit line sphere Y. NOT NULL, no default value at the Kotlin/RPC layer either
// (PostingInput.sphere) -- this is the highest-legal-risk wave in the backlog ("no room for a
// posting to be silently unassigned or defaulted to the wrong sphere"), so the guarantee is
// structural at three layers (Kotlin type / kotlinx.serialization enum decode at the RPC boundary
// / DB NOT NULL+CHECK), not merely a service-layer check like the balance invariant is. This
// deliberately also forces a sphere onto Bestandskonten lines (Kasse/Bank), which are conceptually
// sphere-neutral -- accepted tradeoff, and the four-sphere report only aggregates
// INCOME/EXPENSE-typed accounts, so a cash line's sphere is informational only. No Sammelposten/
// KOST9 "unassigned" literal exists on purpose -- that would be a way to NOT assign, which
// contradicts strict separation; a collective-bucket/Kostenstellen mechanism is later V0.3.6 scope.
// A single journal entry MAY span multiple spheres (no single-sphere-per-entry invariant is
// enforced) -- sphere is per-line, so a cross-sphere Umbuchung is fully transparent because each
// line names its own sphere; inter-sphere transfers are representable this way but are not
// specially validated, tracked, or reported this wave (§58 AO Mittelweitergabe is V0.3.4 scope).
//
// Explicit non-goals for this wave (see 02 Projekte/Lapis Cloud V0.3.md for the full wave plan):
//  - P&L (GuV) / balance sheet (Bilanz) derivation -> V0.3.2. LedgerAccountType is shipped now so
//    that wave can classify normal-balance sides, but no statement is computed here.
//  - §55 AO Mittelverwendungsrechnung (use-of-funds/timely-use tracking) -> V0.3.4.
//  - Not in scope at all this wave: Kassenbuch, Kostenstellen, fiscal-year close/Saldenvortrag
//    (class 9 carryforward), opening balances beyond the dev seed, USt/VAT handling,
//    Storno/reversal postings (JournalEntryStatus is enum-extensible with e.g. REVERSED later),
//    multi-currency, Beleg/attachment storage, automatic Contribution<->journal reconciliation
//    (seam: a nullable journal_entry.contribution_id later), bank import (MT940/CAMT), DATEV/
//    ELSTER export.
//
// §62 AO Ruecklagen (V0.3.4): ledger_account.reserve_type (nullable enum, see `reserveType` below
// and the dedicated comment ahead of the `ledgerAccount` classOf) is the ONLY schema addition this
// wave makes. Reserves themselves are deliberately NOT a new persisted entity -- they are ordinary
// EQUITY ledger_account rows that funds get transferred into via normal balanced double-entry
// postings (see network.lapis.cloud.shared.domain.UseOfFunds / network.lapis.cloud.server.rpc
// .UseOfFundsCalculator's KDoc for the full §55/§62 AO Mittelverwendungsrechnung derivation, which
// is computed read-only from existing POSTED postings and needs no further schema). This mirrors
// V0.3.2/V0.3.3's "derive a report from the existing journal, add at most one classificatory
// column" pattern rather than introducing a parallel allocation ledger.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Accounting") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by every
    // prior domain's own Member stub. Only exists here so UmlToErmTransformer can resolve
    // journal_entry.created_by's FK target within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Normal-balance semantic (ASSET/EXPENSE = debit-normal; LIABILITY/EQUITY/INCOME =
    // credit-normal) -- drives GeneralLedgerCalculator's running-balance sign and, in a later
    // wave (V0.3.2), the P&L-vs-balance-sheet split.
    val ledgerAccountType = enumOf(name = "LedgerAccountType") {
        literal(name = "ASSET")
        literal(name = "LIABILITY")
        literal(name = "EQUITY")
        literal(name = "INCOME")
        literal(name = "EXPENSE")
    }

    // Soll/Haben.
    val postingSide = enumOf(name = "PostingSide") {
        literal(name = "DEBIT")
        literal(name = "CREDIT")
    }

    // Extend with e.g. REVERSED in a later Storno wave -- additive, cheap (see file header
    // "Explicit non-goals").
    val journalEntryStatus = enumOf(name = "JournalEntryStatus") {
        literal(name = "DRAFT")
        literal(name = "POSTED")
    }

    // The four strictly-separated Gemeinnützigkeit spheres (§§ 51-68 AO), assigned per posting
    // (DATEV KOST1 cost center 1/2/3/4) -- see the file header and the dedicated comment ahead of
    // `posting` below for the full placement/NOT-NULL rationale. Literal order is load-bearing:
    // AccountingSchemaDriftTest asserts ErmDataType.Enum.values in exactly this order, matching
    // network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere. Deliberately no fifth
    // "Sammelposten"/unassigned literal -- see comment ahead of `posting` classOf.
    val gemeinnuetzigkeitSphere = enumOf(name = "GemeinnuetzigkeitSphere") {
        literal(name = "IDEELLER_BEREICH") // DATEV KOST1 = 1
        literal(name = "VERMOEGENSVERWALTUNG") // KOST1 = 2
        literal(name = "ZWECKBETRIEB") // KOST1 = 3
        literal(name = "WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB") // KOST1 = 4
    }

    // §62 AO reserve categories (V0.3.4), settable per EQUITY ledger_account -- see the dedicated
    // comment ahead of `ledgerAccount.reserveType` below for the placement/nullability rationale.
    // Literal order is load-bearing: AccountingSchemaDriftTest asserts ErmDataType.Enum.values in
    // exactly this order, matching network.lapis.cloud.shared.domain.ReserveType. §62 Abs.1 Nr.4
    // (Ruecklage zum Erwerb von Gesellschaftsrechten) is deliberately not offered -- out of scope,
    // rare for a Verein/Partei. Subsection citations here are a modelling aid, not a legal opinion
    // -- verify against the current AO before relying on them.
    val reserveType = enumOf(name = "ReserveType") {
        literal(name = "PROJEKTRUECKLAGE") // §62 Abs.1 Nr.1 -- zweckgebundene/Projekt-/Zweckerhaltungsruecklage
        literal(name = "FREIE_RUECKLAGE") // §62 Abs.1 Nr.3 -- percentage-capped general reserve
        literal(name = "WIEDERBESCHAFFUNGSRUECKLAGE") // §62 Abs.1 Nr.2 -- replacement reserve
        literal(name = "BETRIEBSMITTELRUECKLAGE") // §62 Abs.1 Nr.1 sub-case -- operating-funds reserve
    }

    val ledgerAccount = classOf(name = "LedgerAccount") {
        stereotype("Entity") { "tableName" to "ledger_account"; "kotlinObjectName" to "LedgerAccountTable" }
        stereotype("Index") {
            "columns" to listOf("account_number")
            "unique" to true
            "name" to "uq_ledger_account_number"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // SKR42 account number, e.g. "18000" (Bank). String, not Int -- some SKR42 accounts carry
        // leading zeros that are semantically significant (Kontenklasse is the leading digit),
        // e.g. "06500".
        attribute(name = "accountNumber", type = "String") {
            stereotype("Column") { "columnName" to "account_number"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        // SKR42 Kontenklasse, 0-9 -- reference/reporting field only. See file header: unlike
        // SKR49, the Gemeinnützigkeit sphere is NOT derivable from this class under SKR42 -- it is
        // assigned per posting via a cost center (KOST1) -- see `posting.sphere` below (V0.3.3).
        attribute(name = "accountClass", type = "Int") {
            stereotype("Column") { "columnName" to "account_class" }
        }
        attribute(name = "type", type = ledgerAccountType) {
            stereotype("Column") { "columnName" to "type"; "enumType" to "network.lapis.cloud.shared.domain.LedgerAccountType" }
        }
        // Deactivate instead of delete -- an account with existing postings must never be removed.
        attribute(name = "active", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "active" }
        }
        // §62 AO reserve category (V0.3.4). NULLABLE -- most ledger_account rows are ordinary
        // Bestands-/Erfolgskonten, not reserves; only a treasurer-designated EQUITY reserve account
        // carries one. Placement is per-LedgerAccount (not per-Posting, unlike `sphere` above)
        // because reserves are modelled as ordinary EQUITY accounts (see file header) -- the
        // *account itself* is the reserve, so its §62 category is a property of the account, not of
        // any individual booking line into it. Cross-column rule "reserveType only on an EQUITY
        // account" is enforced at the service layer (AccountingService), not here -- same class of
        // constraint as the balance invariant, not expressible as a single-row CHECK. Deliberately
        // NO `sqlType` override -- see the proven nullable-enum path this follows (election
        // .targetRole/CommitteeRole, dsgvo_audit_log.actorRole/AccountRole): omitting `sqlType` lets
        // the generator auto-size VARCHAR to the longest literal ("WIEDERBESCHAFFUNGSRUECKLAGE" = 27
        // chars) and keeps the enum-fallback CHECK constraint.
        attribute(name = "reserveType", type = reserveType) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reserve_type"; "enumType" to "network.lapis.cloud.shared.domain.ReserveType" }
        }
    }

    val journalEntry = classOf(name = "JournalEntry") {
        stereotype("Entity") { "tableName" to "journal_entry"; "kotlinObjectName" to "JournalEntryTable" }
        stereotype("Index") { "columns" to listOf("entry_date"); "name" to "idx_journal_entry_date" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_journal_entry_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // NOT "date" -- reserved SQL keyword, see file header.
        attribute(name = "entryDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "entry_date" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(500)" }
        }
        // Belegnummer. NOT "reference" -- reserved SQL keyword, see file header.
        attribute(name = "voucherReference", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "voucher_reference"; "sqlType" to "VARCHAR(100)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "created_by".
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "status", type = journalEntryStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.JournalEntryStatus" }
        }
        // Set on DRAFT -> POSTED.
        attribute(name = "postedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "posted_at" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    val posting = classOf(name = "Posting") {
        stereotype("Entity") { "tableName" to "posting"; "kotlinObjectName" to "PostingTable" }
        stereotype("Index") { "columns" to listOf("journal_entry_id"); "name" to "idx_posting_journal_entry" }
        stereotype("Index") { "columns" to listOf("ledger_account_id"); "name" to "idx_posting_ledger_account" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "side", type = postingSide) {
            stereotype("Column") { "columnName" to "side"; "enumType" to "network.lapis.cloud.shared.domain.PostingSide" }
        }
        // Explicit sqlType override -- kUML's bare BigDecimal default is DECIMAL(19,2); pinned to
        // DECIMAL(15,2) here (same mechanism as contribution.amountDue -> DECIMAL(12,2),
        // ltr_balance.balance_ltr -> DECIMAL(18,2)). Always > 0 -- enforced at the service layer
        // (JournalEntryBalance), not via CHECK (Exposed emits no CHECK constraints for generated
        // Table objects in this wave, see every prior domain's own "Note: N check constraint(s)
        // ... not emitted" comment).
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "DECIMAL(15,2)" }
        }
        // V0.3.3: mandatory (NOT NULL) Gemeinnützigkeit sphere -- see the dedicated file-header
        // comment above for the full per-posting-placement / no-default rationale. Deliberately NOT
        // `multiplicity = Multiplicity(0, 1)` (the nullable idiom used e.g. by
        // journal_entry.voucherReference/postedAt) -- omitting multiplicity yields a NOT NULL
        // column, matching `side`/`amount` above. Deliberately no `sqlType` override either -- the
        // generator auto-sizes the VARCHAR to the longest literal
        // ("WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB" = 34 chars), exactly as `side` -> VARCHAR(6) and
        // `type`/`status` above do; an explicit sqlType would fight that auto-sizing and can
        // suppress the enum-fallback path that emits the CHECK constraint.
        attribute(name = "sphere", type = gemeinnuetzigkeitSphere) {
            stereotype("Column") { "columnName" to "sphere"; "enumType" to "network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere" }
        }
    }

    // posting.journal_entry_id -> journal_entry (id): association-derived default matches.
    association(source = journalEntry, target = posting, id = "assoc-journal-entry-posting") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "journalEntryId" }
    }

    // posting.ledger_account_id -> ledger_account (id): association-derived default matches.
    association(source = ledgerAccount, target = posting, id = "assoc-ledger-account-posting") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "ledgerAccountId" }
    }
}

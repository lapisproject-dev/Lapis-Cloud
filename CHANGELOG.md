# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [0.3.0] — 2026-07-19

### Added

**Accounting core** — SKR42 chart of accounts and double-entry bookkeeping (originally modeled on SKR49, switched to SKR42 since that is DATEV's current recommendation for new non-profit clients): ledger accounts, journal entries/postings with a server-enforced balance invariant (Σdebit = Σcredit, validated independently of client input, immutable once `POSTED`), a general ledger view, and treasurer/board/admin-tiered authorization throughout.

**Financial statements** — `GuV` (income statement), `Bilanz` (balance sheet), and a combined `Jahresabschluss` (annual financial statement), all derived purely from `POSTED` journal postings with no new persisted state. The balance sheet surfaces an explicit cumulative-result equity line so Aktiva = Passiva always holds, since income/expense are not closed to equity in this version.

**Four-sphere Gemeinnützigkeit separation** — every posting now carries a mandatory sphere (Ideeller Bereich / Vermögensverwaltung / Zweckbetrieb / Wirtschaftlicher Geschäftsbetrieb, DATEV-KOST1-flavored), enforced with no default and no nullable transition period, plus a per-sphere income-statement report.

**§55 AO Mittelverwendungsrechnung and §62 AO Rücklagenbildung** — reserve categories (Projektrücklage, freie Rücklage, Wiederbeschaffungsrücklage, Betriebsmittelrücklage) as an optional classification on equity ledger accounts, funded via ordinary double-entry transfers, plus a derived use-of-funds statement with a FIFO timely-use carry-forward and overdue-amount tracking anchored at inception. The freie-Rücklage percentage cap and the §55 small-organization exemption are deliberately not hard-coded — both are surfaced as data for human verification rather than enforced constants.

**Kassenbuch** — a chronological, gapless cash-book view for designated cash-register accounts, derived from existing immutable `POSTED` postings, with two GoBD-informed guards: no posting without a voucher reference for cash accounts, and the cash balance may never go negative (enforced with row-level locking to close a same-account race). This is explicitly a GoBD foundation only — cryptographic tamper-evidence, retention enforcement, and TSE integration remain out of scope, planned for V0.5.

**Kostenstellen/cost-center accounting** — an open-ended, user-created `CostCenter` entity (unlike the fixed sphere/reserve enums) with the same create/list/deactivate lifecycle as ledger accounts, optional per-posting assignment (most routine bookings have no project association), and a minimal per-cost-center income/expense/result report. Lays the general mechanism V0.6 (Crowdfunding/Auktion) will later attach campaigns to, without building any campaign-specific logic yet.

### Changed

Dependency bumps: Kotlin 2.4.0 → 2.4.10, KSP 2.3.9 → 2.3.10, kuml 0.35.0 → 0.36.1. JVM toolchain corrected from an accidental 26 pin to 25, the actual requirement for loading Kilua RPC's published jars.

### Security

- Fixed an unmapped `IllegalArgumentException` for an out-of-range `fiscalYear` in `getAnnualFinancialStatement`, replaced with a typed `BadRequestException`.
- Closed a check-then-act race in the Kassenbuch's never-negative-balance guard by adding row-level locking (`SELECT ... FOR UPDATE`) with a deterministic lock-acquisition order, preventing both a balance-check bypass under concurrent postings and a possible deadlock when a single entry locks more than one cash account.

### Known limitations (tracked for later versions)

- No mail-merge/PDF engine or postal-mail path yet — planned for V0.4.
- No compliance bundle yet (§25 PartG donation-acceptance check, §20 GwG transparency-register reporting, full GoBD audit-log/tamper-evidence/retention/TSE, backup/restore guarantee, full GDPR build-out) — planned for V0.5.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6; cost centers (this release) lay the groundwork for attaching campaigns/auctions.
- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).

## [0.2.0] — 2026-07-18

### Added

**Governance** — committee/working-group management and meeting management (agenda, resolution register, minutes template, attendance tracking, quorum check); motion management for general assemblies and committees.

**Voting — three orthogonal modes**:
- **Meritocratic votes** — LTR-weighted voting on substantive/project questions.
- **Democratic elections** — one-member-one-vote for legally mandated personnel and constitutional decisions (board elections, bylaw amendments), including election board oversight, eligible-voter snapshots, candidacy management, secret and open ballot modes, and a configurable N-of-M tally-approval step.
- **Systemic consensus** — resistance-based decision-finding (Visotschnig/Schrotta method): each voter rates every option 0–10, the option with the lowest cumulative resistance wins, with a group-conflict index, configurable tiebreak rules, and an automatic "status quo" option.

All three modes share the same resolution register (`Resolution`) and reuse a single anonymous/open ballot infrastructure end to end.

**MDA persistence pipeline fully wired** — the kUML UML→ERM→Exposed/Flyway pipeline (ADR-0016, tracked as a known limitation in 0.1.0) is now the actual production persistence layer: all hand-written Exposed tables were deleted and replaced with kUML-generated code from versioned `.kuml.kts` domain models, and the Flyway baseline migration is generated from the same source of truth. Multiple real kUML gaps surfaced and were fixed upstream along the way (enum-to-`VARCHAR` type fidelity, Kotlin object-name overrides, KMP-safe UUID/date-time representations, explicit FK targeting via `fkEntity`/`fkAttribute`, a new `«Index»` stereotype for composite unique constraints) — see [ADR-0016](https://github.com/kuml-dev/kUML) for details. The project now depends on the real Maven Central `kuml` artifact (currently 0.35.0); the temporary `mavenLocal` bridge used during development has been retired.

### Changed

**English-only domain terminology.** The entire governance/voting domain, previously named in German, was renamed to English end to end (entities, tables, classes, DTOs, services, tests): Gremium→Committee, Sitzung→Meeting, Tagesordnungspunkt→AgendaItem, Anwesenheit→Attendance, Antrag→Motion, Beschluss→Resolution, Abstimmung→Vote, Wahl→Election, Konsensierung→SystemicConsensus. `README.adoc` and `docs/architecture/domain-model.adoc` were fully translated to English. This aligns the codebase with this project's own documented convention (English documentation and class names for all `kuml-dev`/Lapis repos).

### Known limitations (tracked for later versions)

- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).
- No accounting core yet (chart of accounts, non-profit four-sphere separation, use-of-funds statement) — planned for V0.3.
- No mail-merge/PDF engine or postal-mail path yet — planned for V0.4.
- No compliance bundle yet (PartG donation-acceptance check, transparency-register reporting, GoBD audit log, backup/restore guarantee, full GDPR build-out) — planned for V0.5.

## [0.1.0] — 2026-07-12

### Added

**Project foundation** — Gradle multi-module build (`lapis-shared`, `lapis-server`, `lapis-client`) following the Kilua RPC fullstack convention: a Kotlin Multiplatform shared module holding RPC service interfaces and domain DTOs, a Ktor JVM server, and a KVision Kotlin/JS client. CI workflow runs `./gradlew clean check` on push/PR. Persistence via Exposed ORM + Flyway migrations against PostgreSQL.

**Member management** — member master data, join/leave workflow (application → approval → active, with exit transitioning to guest status per the PZB legal-framework reference), membership tiers and roles.

**Contributions, documents, communication** — basic recurring-contribution tracking per membership tier (manual payment marking, no SEPA/dunning automation yet), a versioned document store with access tiers, and mailing-list/direct-message data models with typed Kilua RPC services.

**GDPR basics** — a self-registering `PersonalDataContributor`/`PersonalDataRegistry` mechanism so future entities opt into data-subject-access-request coverage without hand-maintaining a table list, enforced by an `information_schema`-based coverage test. Erasure requests support both anonymization (default, since accounting retention will later require it for financial records) and hard deletion where legally unconstrained, via a request → decide → execute workflow with an audit trail, exposed over both RPC and HTTP with self-or-ADMIN access control.

### Security

- Enforced the `ADMIN_ONLY` document access tier and gated version-listing/double-send paths that were previously open.
- Closed an unauthenticated member email/role leak in `listMembers()`.
- Made demo-data seeding opt-in with a guard against running against a real database.
- Fixed an ambiguous-join bug where `ErasureRequestTable`'s three separate foreign keys to `MemberTable` made Exposed's implicit join throw `IllegalStateException` at runtime; replaced with an explicit join condition.

### Known limitations (tracked for later versions)

- The kUML MDA persistence pipeline (UML → ERM → Exposed/Flyway, per [ADR-0016](https://github.com/kuml-dev/kUML) in the sibling kUML project) is not yet wired into this repo's build — Exposed tables are hand-written for now, with a kUML diagram kept as documentation only (`docs/architecture/domain-model.adoc`). Wiring the generator is tracked as follow-up work.
- Contribution management has no SEPA direct-debit or dunning automation.
- No governance layer yet (committees, meetings, motions, votes) — planned for V0.2.

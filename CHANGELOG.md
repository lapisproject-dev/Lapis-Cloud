# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [0.7.0] — 2026-07-22

### Added

**Real authentication and revocable sessions (V0.7.1)** — replaces the `X-Member-Id` HTTP-header stand-in that `RequestContext.kt` has carried since V0.1 with a real password-login + server-side, revocable session mechanism, the first of three deploy-blockers found in a 2026-07-22 readiness review (no auth, no registration, no usable UI). Password hashing is bcrypt (cost 12) via `at.favre.lib:bcrypt`, chosen over Argon2id because the only mature JVM Argon2 binding ships native JNI code; passwords are SHA-256-pre-hashed and Base64-encoded before bcrypt to neutralize its 72-byte truncation and NUL-byte-stop behavior. Sessions are server-side and DB-persisted rather than stateless JWT (this codebase treats auditability/revocability as first-class, see the V0.5.3 GoBD audit chain and V0.5.4 backup) — a 256-bit `SecureRandom` token is issued on login, only its SHA-256 hash is ever persisted, delivered via an `HttpOnly`+`Secure`+`SameSite=Strict` cookie (a `Bearer` header is also accepted). Logout and `changePassword` revoke server-side immediately. `resolveCurrentMember` (`security/RequestContext.kt`) remains the single designed switch point every RPC service resolves the caller through, so swapping the resolution mechanism touched only this one file — all ~25 existing services and their `requireRole`/`isPrivileged`/`canAccessDocumentAtLevel` checks work unchanged. Hardening: identical error response + always-executed dummy-hash bcrypt compare for unknown-email/wrong-password/no-password-set (account-enumeration and timing-attack resistant), per-email and per-IP login rate limiting, `SameSite=Strict` as the interim CSRF control. Bootstrap for the very first admin password is an env-var-only CLI task, never a network-reachable "first login sets the password" path, and never overwrites an existing hash.

### Known limitations (tracked for later versions)

- OIDC login is not built (`account.oidc_subject` stays reserved) — planned for V0.8 (Federation).
- The join/registration workflow (self-service signup, board approval, admin member-creation) does not exist yet — this wave only logs in already-existing accounts. Planned for V0.7.2.
- No "forgot password" email flow yet — deferred to V0.7.2, where email infrastructure is added anyway. Only authenticated self-service `changePassword` exists.
- No admin-reset-of-others'-passwords path.
- Full double-submit CSRF tokens are deferred to the UI wave (V0.7.3) — `SameSite=Strict` is the interim control.
- The 905 existing `header("X-Member-Id", ...)` test call sites across ~40 `testApplication` blocks were deliberately not rewritten. Session-token resolution always runs first; only if it yields nothing does a trusted-header fallback run, gated behind two independent structural locks (a JVM system property set solely by the Gradle test task, and H2-in-memory detection) plus a third inner check in the fallback itself — a real Postgres deployment can never reach it.
- No usable multi-screen web UI yet (see V0.6.5/known limitations below for the client's current state) — planned for V0.7.3.
- Federation (multi-server operation) is not yet built — planned for V0.8.

## [0.6.0] — 2026-07-22

The LTR economy arc — internal currency, meritocratic marketplace mechanics, and the money-to-LTR
conversion boundary — including the auction, which the original V0.6 scope had deferred pending
legal review.

### Added

**Real LTR ledger + Internes Crowdfunding (V0.6.1)** — replaces the provisional LTR balance snapshot (`ltr_balance`) with a real, append-only, member-scoped ledger (`ltr_ledger_entry`, signed amounts, balance derived live as `SUM(amount_ltr)`). `LedgerBackedLtrBalanceProvider` swaps in for the earlier placeholder at `GovernanceService`'s single seam. Adds Internes Crowdfunding on top, with the two mechanisms the concept keeps deliberately separate: a **Sichtbarkeits-Gewicht** (LTR-staked project weight, decays 10%/day, entry hurdle requires matching the current top project's weight, race-safe via a genesis-singleton row lock) and a **Verteilungs-Korb** (one Like or Dislike per member per project, purely democratic, never LTR-weighted). Monthly EUR distribution deducts a fixed per-payer minimum contribution before apportioning the remainder across baskets with a new, exact BigInteger-cent `LargestRemainderApportionment` (also backported into the existing election-settlement rounding, which used a less precise method before). During this wave's own security loop, a real pre-existing gap was found and fixed: `castVoteBallot` (V0.2.3) validated `stake <= freeBalance` but never actually wrote a debiting ledger entry, so a member could stake the same LTR across unlimited concurrent votes and again via crowdfunding — now correctly debited via `LtrLedgerEntryType.VOTE_STAKE`.

**Direct LTR peer-to-peer transfer (V0.6.3)** — a member sends LTR directly to any other member, no auction/project/platform action in between. Extends `LtrLedgerEntryType` additively with `PEER_TRANSFER_OUT`/`PEER_TRANSFER_IN`. `transferLtr` (self-initiated, always debits the caller's own account) and `executeArbitrationTransfer` (TREASURER/BOARD/ADMIN only, mandatory non-blank purpose) as the sole correction path for fraud/identity-theft/coerced-donation cases — a regular, fully documented transfer, never a technical revert; there is deliberately no storno/cancel endpoint anywhere. Both accounts (not just the sender) are locked in canonical lexicographic-UUID order before any balance read, structurally preventing the classic A-to-B/B-to-A deadlock.

**Politiker-Profile und Politiker-Ranking (V0.6.4)** — an explicit, member-only Like/Dislike ranking layer for politicians, built on the LTR ledger. A BOARD/ADMIN grants/revokes `PoliticianProfile` status per member (upsert-by-member: a re-grant after revocation reactivates the same profile row, starting back at Korb=0 with no persisted rating history); any `AKTIV` member can cast one Like/Dislike per politician. Trust weight is a **single shared LTR pool** — the current free-LTR balance of every distinct rater across every active politician, summed once per person — apportioned across politicians in proportion to their basket via `LargestRemainderApportionment`, recomputed fresh on every read. `OrganizationSettings.politicianRankingEnabled` (default off) gates every endpoint. A manually-triggered, idempotent-per-month `snapshotWeights` action persists a historical trend line. Revoking status deletes all of that politician's ratings and snapshots; the profile row itself is retained.

**Price-Oracle für die Anker-Bindung (V0.6.5)** — the first real money-to-LTR conversion boundary this codebase has had. Three independent, free, no-API-key public exchange feeds (Coinbase, Kraken, Bitstamp) are queried in parallel; a provisional median is computed, outlier sources dropped, and if the survivors' own spread is still too wide the quote is rejected rather than trusted. A quote is `LIVE`, `DEGRADED`, or `CACHED`, governed by a single-row, ADMIN-tunable `price_oracle_config`. The load-bearing `convertDonationToLtr` (TREASURER/BOARD/ADMIN) books an already-received donation: fetches a quote and, if not halted, MINTs the computed LTR and writes a permanent `price_oracle_conversion` provenance row in the same transaction. Every oracle source resolves against a compile-time-fixed hostname allowlist — `price_oracle_config` carries no URL/host field at all — HTTPS-only, no redirects, bounded timeouts, 64 KiB response cap.

**LTR-Auktion, disabled by default (V0.6.2)** — the English proxy-bid auction from the concept doc, gated behind an opt-in the legal-risk analysis in that same document forced: ZAG/MiCAR/GewO/tax/consumer-protection/PartG/GwG classification depends on jurisdiction and organization type, which no single blanket legal review can resolve for every future deployment. `auctionEnabled` defaults to `false` and stays `false` until an ADMIN explicitly acknowledges a versioned, SHA-256-hashed disclaimer naming all six risk areas — responsibility for the enable decision moves to the organization operator. Mechanics: eBay-style proxy bidding, second-price settlement at close, optional Buy-It-Now, lazy close on next read (no scheduler). LTR-only, no platform commission, flat 0.01 LTR listing fee. Reservation design is real ledger holds (`AUCTION_HOLD`), not a derived calculation — only the current leader holds one, released on outbid/buyNow/settle, so every other debit path automatically sees the reservation without needing to know about auctions at all. `auctionEnabled`/`auctionMaxValueLtr` are deliberately absent from the generic `updateOrganizationSettings` write-set; the only way to flip the auction on is the dedicated `enableAuction` RPC with its constant-time disclaimer-hash re-verification.

### Fixed

**Build breakage in V0.6.4/V0.6.5, found and repaired before this release.** Both waves were originally authored in a sandboxed session that could never run a real `./gradlew clean check` (Gradle 9.6.1 wrapper download blocked by egress policy, local Gradle 8.14.3 incompatible with the Kilua/KVision plugins) — both waves' own changelog entries disclosed this and asked for a real build-verification pass. That pass found 7 genuine defects, all fixed prior to this release: two missing imports (`io.ktor.utils.io.readAvailable`, `kotlinx.datetime.atTime`) and one entirely missing import (`org.jetbrains.exposed.v1.jdbc.update`, breaking `politicianRankingEnabled` wiring at the test level); a variable-shadowing bug in `PriceOracleService.kt` where local `val`s with the same name as table columns broke the Exposed insert DSL; a real kUML modeling bug where `politician_reaction.rater_member_id` was declared as a UML association with a custom `role`, which does not rename the generated column in this kUML setup (fixed by switching to the established plain-`«Column»`+`fkEntity` idiom); a structural DSGVO capacity bug where the shared `outcome_summary` column (`VARCHAR(8000)`) overflowed to 8670 characters once seven more `PersonalDataContributor`s had been added since V0.2.5 (widened to unbounded `text`, matching the fix already applied to the V0.5.3 audit-log's analogous columns); and a test bug (not a product bug) in `PoliticianServiceTest`'s ordering assertion, which assumed a rater's own LTR balance directly inflates the politician they voted for — contradicting the shared-pool design the concept document actually specifies.

### Security

Every oracle source resolves against a compile-time-fixed hostname allowlist, HTTPS-only, no redirects, bounded timeouts, 64 KiB response cap, and a catch-all that maps every source failure to `null` without ever logging a response body or raw exception message (V0.6.5). V0.6.1's review/security loop closed a TOCTOU race on LTR debits by having every debit-causing write take a row lock on the member's own row before reading `freeBalance`. V0.6.2's auction reservation model uses real ledger holds specifically so no other debit path can be blind to an open reservation. V0.6.3's peer transfer locks both accounts in canonical UUID order, verified deadlock-free under a real two-thread concurrent test.

### Known limitations (tracked for later versions)

- **Guest (Gast) rating basket for Politiker-Profile (V0.6.4) is entirely cut — accepted scope, product-owner sign-off received 2026-07-22.** The concept's Mitglied/Gast two-basket mechanic needs an operational Gast identity that does not exist anywhere in this codebase yet (`MemberStatus.GAST` is an inert enum literal nothing currently sets or transitions into) — building a permanently-empty guest basket against it would be decorative, not functional. `PoliticianProfileDto` has `memberTrustWeight` only; a future wave adds `guestTrustWeight`/`combinedTrustWeight` additively once a real Gast identity model lands (tracked for V0.7.2/V0.8). Flagged during V0.6.4's own review loop as needing explicit product-owner sign-off before it could be considered accepted rather than merely documented — that sign-off is now given ("can stay like that for now"); revisit once a real Gast identity model exists.
- No LTR ↔ Gold/Fiat anchor sources wired (`AnchorAsset.GOLD_XAU`/`FIAT` are reserved enum literals only Bitcoin has real price sources for).
- The price-oracle quote cache is in-memory/per-server, not shared across a federation — tracked for V0.8.
- No persistent price-oracle halt-queue (`PriceStatus.DEFERRED` reserved-and-unused).
- Bound LTR stakes (Vote and Crowdfunding project stakes) are not released on vote-close/project-rejection — no release path built yet.
- Disabling the auction strands any already-open auction's holds until re-enabled (no fund loss, settle/release paths also require the gate to be on).
- No guest/Gast participants anywhere in the LTR economy yet (Crowdfunding, Peer-Transfer, Auction, Politician ratings) — all Member-only, since no operational Gast identity model exists. Tracked for V0.7.2/V0.8.
- No comment/discussion feed under a Crowdfunding project or Politician profile.
- No scheduler/cron infrastructure exists anywhere in this codebase — all periodic actions (monthly EUR distribution, politician-weight snapshots) are manually triggered by BOARD/ADMIN.
- Federation (multi-server operation) is not yet built — planned for V0.8.

## [0.5.1] — 2026-07-21

### Added

Completes the V0.5 compliance bundle that 0.5.0 deliberately narrowed in scope — the three remaining items from that release's "known limitations" list.

**GoBD audit log** — a hash-chained (SHA-256), append-only `AuditLogEntry` log written in the same transaction as the business mutation it records, serialized via a genesis-singleton `AuditLogChainState` row (`SELECT ... FOR UPDATE`). Covers the JournalEntry lifecycle (draft/post), Resolution creation, BoardMembership changes, and PartyDonationCompliance verdicts for postings that actually committed. Deliberately out of scope: ledger/cost-center master-data CRUD, DSGVO erasure (has its own separate, unchanged `dsgvo_audit_log`), and any retention/archival policy. Read access is TREASURER/BOARD/ADMIN-gated with capped pagination; before/after snapshots are excluded from a member's own GDPR export.

**Full-organization backup/restore/export** — an ADMIN-only, streamed ZIP export/restore covering every table in the schema (discovered dynamically via `information_schema`, not a hand-maintained list — any table a future domain wave adds is automatically in scope) plus document blobs. Export streams row-by-row without materializing the database in memory; restore is upsert-based, gated by a formatVersion + SHA-256 schema-checksum compatibility check and a non-empty-target pre-flight guard against accidental cross-organization merges. Zip-Slip is guarded on both the export and restore paths. Infrastructure-level backup (`pg_dump`/WAL archiving) remains explicitly out of scope — an operations concern, not solved here.

**DSGVO-Vollausbau (AVV, TOMs, DSFA, Datenpannenmeldung)** — four record-keeping/workflow tools, none of them automated legal advice: an AVV register for third-party processors (status/dates/document reference, coupled to the existing postal-mail opt-in only as a non-blocking advisory log, never a hard gate); TOM documentation across the eight Art. 32 / Anlage §64 BDSG categories; a DPIA template where the required-or-not verdict is always a stored human judgment (a `DpiaRiskMatrix` helper only renders a display band, it never decides); and a data-breach-incident workflow that surfaces the Art. 33 72-hour clock as a read-time warning without ever auto-filing a notification. Authorization is ADMIN-only for AVV/TOM writes, BOARD/ADMIN for DPIA/breach read and write.

### Known limitations (tracked for later versions)

- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Federation (multi-server operation) is not yet built — planned for V0.7.
- Audit log's hash chain is plain SHA-256 (no HMAC/external anchoring) and immutability is enforced only at the application layer (no DB-level UPDATE/DELETE grant restriction) — both accepted, documented residual risks, not defects against this wave's own requirements.
- Backup/restore has no decompression-ratio/zip-bomb cap beyond the 512 MiB compressed-upload limit — low severity given the actor is already ADMIN-only.

## [0.5.0] — 2026-07-19

### Added

**§25 PartG donation-acceptance check** — a pure, DB-free `PartyDonationComplianceCalculator` (same idiom as `JournalEntryBalance`/`UseOfFundsCalculator`) returning ALLOWED/PROHIBITED verdicts plus additional-duty flags (anonymous-forwarding, prompt Bundestag report, annual Rechenschaftsbericht disclosure) for donations to political parties, with all thresholds as named constants explicitly flagged as current understanding requiring legal verification. The accounting model gains an `ExternalDonor` entity and `DonorCategory` enum so a `JournalEntry` can attribute a donation to a non-member donor (mutually exclusive with the existing `donorMemberId`). The check is hooked into `postJournalEntry`/`postDraftEntry`, gated strictly on `OrganizationSettings.isPoliticalParty`, hard-blocking PROHIBITED donations while never blocking ALLOWED-with-duties postings. A new read-only, TREASURER/BOARD/ADMIN-gated report lists open prompt-report and annual-disclosure duties for a given calendar year.

**§20 GwG Transparenzregister board-change reminders** — a queryable board roster with history (`BoardMembership`: member, committee role, start/end), written in lockstep with the existing `CommitteeMembership` seating at election-tally time and via a new manual appoint/end-membership action for co-options, resignations, and recalls that don't go through a fresh election. `Member` gains the two missing beneficial-owner fields (date of birth, nationality), both nullable and covered by GDPR export/erasure. A persisted `TransparenzregisterReminder` log records every JOINED/LEFT board-change event, plus a read-only report of open reminders and members still missing beneficial-owner data — reminder/acknowledgement only, no automated filing to transparenzregister.de (no suitable public API exists). Unlike the PartG check, this duty is **not** gated on `isPoliticalParty` — §20 GwG transparency duties apply to every Verein/Partei.

### Known limitations (tracked for later versions)

- No automated filing to transparenzregister.de — reminders and reports only, filing itself stays a manual, human-triggered step.
- Audit-log/GoBD tamper-evidence, retention enforcement, and TSE integration, plus a full backup/restore/data-export guarantee and full GDPR build-out (AVV, TOMs, DSFA, breach reporting), are not yet implemented — the original V0.5 scope for these was narrowed to the two donation/transparency compliance checks above; the rest remains open, tentatively folded into a later wave.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Federation (multi-server operation) is not yet built — planned for V0.7.

## [0.4.0] — 2026-07-19

### Added

**Mail-merge/PDF engine** — Beitragsrechnung (membership dues invoice), a §50 EStDV Spendenbescheinigung (donation receipt, following the official BMF Muster pattern, distinguishing §10b EStG association donations from §34g EStG political-party donations), and an Einladung (invitation letter), all rendered with Apache PDFBox and delivered as raw PDF bytes over plain Ktor HTTP routes rather than Kilua RPC, mirroring the existing document-download idiom. Guessed or simplified legal wording in the donation receipt is explicitly flagged in code for human/tax-advisor review before real-world use. To make the templates fillable, this release also adds: a minimal nullable postal address on `Member` (with a new `updateMemberAddress` endpoint), a single-row admin-editable `OrganizationSettings` entity (letterhead, bank details, Gemeinnützigkeit tax-exemption reference), and an optional `donorMemberId` bridge on `JournalEntry` so a posted donation can be traced back to its donor for receipt generation. Beitragsrechnung and Spendenbescheinigung PDFs are additionally archived into the existing document store for retention.

**Letterxpress postal-mail dispatch** — an explicit, human-triggered path to mail a generated Beitragsrechnung, Spendenbescheinigung, or Einladung to members without email, via a new `PostalMailProvider` abstraction with a Letterxpress implementation. Gated behind a new `OrganizationSettings.postalMailEnabled` opt-in (default off), since enabling it in real operation requires a Data Processing Agreement (Auftragsverarbeitungsvertrag/AVV) with Letterxpress; defaults to Letterxpress's sandbox/non-live mode until explicitly switched to live dispatch. A new `PostalDeliveryLog` records every dispatch attempt (status, provider reference, a sanitized error message — never a raw exception or provider response body). Dispatch requires the same authorization tier as PDF generation and a bounded, explicit recipient list (no unbounded batch sends). The Letterxpress wire format could not be verified against live documentation in the build environment and is explicitly flagged in code as needing a human check before production use.

### Known limitations (tracked for later versions)

- The Letterxpress integration's exact API wire format (endpoints, field names, auth flow) is implemented from general knowledge, not verified against live/current Letterxpress documentation — verify before enabling live dispatch.
- Spendenbescheinigung is issued per single donation entry, not aggregated into an official BMF-style Sammelbestätigung across a period — aggregation rules need a human/tax-advisor check.
- No compliance bundle yet (§25 PartG donation-acceptance check, §20 GwG transparency-register reporting, full GoBD audit-log/tamper-evidence/retention/TSE, backup/restore guarantee, full GDPR build-out) — planned for V0.5.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).

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

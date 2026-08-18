// LTR-Ledger domain (V0.6.1, Internes Crowdfunding) -- RE-MODELLED, not merely re-generated.
//
// **Migration note**: this file previously modelled `ltr_balance` (see the module's own git
// history for the pre-V0.6.1 shape) -- a single-row-per-member balance SNAPSHOT with no debit/
// credit/reserve columns at all. That table's own KDoc/header comment explicitly flagged it as
// provisional and named "V0.6 (LTR-Wirtschaft/Auktion/Price-Oracle)" as the wave that would
// replace it with a real ledger. This is that wave (well, its first sub-wave, V0.6.1): `ltr_balance`
// is DROPPED and replaced by `ltr_ledger_entry`, an append-only, signed-amount, per-member ledger.
// `V1__baseline.sql` carries both the DROP and the new CREATE (see that file's own comment) --
// there was never a production deployment of this schema to migrate forward incrementally, so a
// destructive baseline edit is safe and preferred over a growing V2/V3/... migration chain per
// this codebase's "V1__baseline.sql is ONE consolidated migration" convention (see CLAUDE.md).
//
// **Why append-only signed-entry, not the double-entry Journal/Posting shape from
// 10-accounting.kuml.kts**: deliberate, reviewer-flagged design contrast. LTR is an internal
// participation/governance currency, not SKR49/GoBD-pflichtige EUR-Buchführung -- there is no
// legal or fachlich reason to invent a "System-LTR-Pool" counter-account for every mint just to
// satisfy a double-entry balance invariant that has no real-world referent here. A single signed
// `amount_ltr` per entry (credit positive, debit negative) is the simplest structure that still
// gives an auditable, append-only, `SUM(amount_ltr)`-derived balance -- no denormalized snapshot
// column, consistent with this codebase's "derive from the ledger, don't cache a running total"
// idiom already used for Kassenbuch/GeneralLedgerCalculator balances in 10-accounting.kuml.kts.
//
// **`entryType` (`LtrLedgerEntryType`) is additively extensible, not V0.6.1-closed**: `MINT`
// (an ADMIN/BOARD/TREASURER grant, see network.lapis.cloud.server.rpc.LtrLedgerService),
// `PROJECT_STAKE` (network.lapis.cloud.server.rpc.CrowdfundingService.submitProject binding LTR
// into a Crowdfunding project's visibility weight, see 17-crowdfunding.kuml.kts) and `VOTE_STAKE`
// (network.lapis.cloud.server.rpc.GovernanceService.castVoteBallot binding LTR into a Vote's
// Vickrey-auction stake -- a security fix landed after this wave's initial cut: `freeBalance` was
// blind to open vote stakes because castVoteBallot never wrote this row, letting the same LTR be
// staked on unlimited concurrent votes AND spent again via submitProject) are the three entry
// types actually written. `PROJECT_STAKE_RELEASE` is defined but DELIBERATELY UNUSED -- no path
// exists yet to release a bound stake back to its member (e.g. on project rejection, a vote
// recast-down, `closeVote` settling below the full stake, or `abortVote`); reserved so a later
// wave can add that release path as a pure additive literal, no ledger restructuring needed.
// V0.6.3 (direkte LTR-Peer-to-Peer-Uebertragung) adds `PEER_TRANSFER_OUT`/`PEER_TRANSFER_IN` the
// same additive way -- literal order is load-bearing (see LtrLedgerSchemaDriftTest, updated by
// this wave), cheap to extend, expensive to reorder, same note every other domain enum in this
// codebase carries. This wave ALSO additively extends `ltrLedgerReferenceType` below with
// `PEER_TRANSFER`, and introduces a new, separate business entity (`PeerTransfer`, see
// 18-peer-transfer.kuml.kts) rather than widening this file's own `ltr_ledger_entry` shape: the
// concept document ("03 Bereiche/Lapis Cloud/Meritokratisches System und Libertaler.md", "Direkte
// LTR-Übertragung (Peer-to-Peer-Transfer)") requires a stored, self-reported legal
// characterization (Schenkung/Honorar/Privatverkauf/Sonstiges) plus an optional free-text purpose
// per transfer -- neither has a home in this generic, polymorphic ledger row without widening its
// column shape for every OTHER entry type too. Exactly the same "business table carries the
// fachlich detail, ltr_ledger_entry carries only the generic signed booking effect via
// referenceType/referenceId" split `CROWDFUNDING_PROJECT`/`PROJECT_STAKE` already established
// (see 17-crowdfunding.kuml.kts).
//
// V0.6.2 (LTR-Auktion) additively appends FIVE more literals --
// `AUCTION_LISTING_FEE`/`AUCTION_HOLD`/`AUCTION_HOLD_RELEASE`/`AUCTION_SALE_OUT`/`AUCTION_SALE_IN`
// -- and a new `AUCTION` `ltrLedgerReferenceType` literal below, same "additive-only, order is
// load-bearing" discipline. New literals are deliberately named to stay <= 20 characters
// (`AUCTION_HOLD_RELEASE` is the longest, at 20) so `entry_type`'s existing `VARCHAR(21)` width
// (set by the pre-existing `PROJECT_STAKE_RELEASE`, 21 chars) does not need to widen -- avoid
// `AUCTION_SETTLEMENT_OUT`-style longer names that would force a churn of both the SQL column
// width and `LtrLedgerEntryTable.entryType`'s `enumerationByName(..., 21)` call. See
// 21-auction.kuml.kts's own header for the full reservation-hold rationale (real ledger holds so
// every OTHER debit path stays automatically aware of an open auction reservation, closing
// exactly the class of gap the V0.6.1 `castVoteBallot` security fix above had to retrofit).
//
// V1.1.1 (Soziales Netzwerk, Welle "Fundament & Post-Kern") additively appends `SOCIAL_POST_STAKE`
// (network.lapis.cloud.server.rpc.SocialNetworkService.createPost binding a post's chosen
// Sichtbarkeits-Gewicht into a real LTR debit, see 32-social-network.kuml.kts's own header) and a
// new `SOCIAL_POST` `ltrLedgerReferenceType` literal below -- same additive-only discipline. Fits
// the existing `VARCHAR(21)`/`VARCHAR(20)` column widths unchanged (18 and 11 characters
// respectively). `SOCIAL_POST_BOOST` (Welle V1.1.2 monetary Like) is deliberately NOT added yet --
// see 32-social-network.kuml.kts file header for why that wave's own tables/literals stay out of
// this codebase until their corresponding migration lands.
//
// Migration note (corrected 2026-08-18, Review-Fund G3 -- the previous wording here was wrong):
// `V1__baseline.sql`'s `entry_type`/`reference_type` CHECK constraints ARE edited in-place by this
// wave -- they are now explicitly NAMED (`chk_ltr_ledger_entry_entry_type`/
// `chk_ltr_ledger_entry_reference_type`) and already widened to include `SOCIAL_POST_STAKE`/
// `SOCIAL_POST` directly, exactly the same "fresh installs/tests get the widened schema straight
// from the baseline" convention V2/V3 already established for other domains (see
// `V1__baseline.sql`'s own header comment right above this table's `CREATE TABLE`). Editing the
// baseline in-place here is safe because a baseline edit only breaks Flyway's checksum validation
// for a database that already recorded `V1__baseline.sql` in its `flyway_schema_history` -- exactly
// what `V4__social_network_core.sql` exists for: it applies the SAME widening idempotently against
// an already-migrated deployment (pdv2) via a dual-DROP/ADD pair that targets both the pre-existing
// Postgres-auto-generated unnamed-constraint name and this new explicit name -- see that
// migration's own trailing header comment for the exact mechanics.
//
// **`referenceType`/`referenceId` are a polymorphic, DB-FK-less opaque pointer** -- directly
// mirrors `audit_log_entry.entity_type`/`entity_id` (14-audit-log.kuml.kts): no single FK
// constraint could express "targets crowdfunding_project today, possibly an auction lot or a
// peer member in a later wave", and this file (08, lower-numbered) would otherwise need a
// forward reference into 17-crowdfunding.kuml.kts (higher-numbered) to declare a real FK. Both
// are nullable together (`MINT` entries carry neither -- a grant is not "about" any other
// entity). See 17-crowdfunding.kuml.kts's own header for the other side of this pointer.
//
// **member_id is now a real UML association** (unlike the old ltr_balance.member_id, which HAD
// to be a plain «Column»+«Id» attribute because it doubled as the primary key -- see this file's
// pre-V0.6.1 history for why). `ltr_ledger_entry` has its own synthetic `id` primary key instead
// (many rows per member now, not one), so the ordinary association-derived FK naming applies
// cleanly: association-to-`member` default is "member_id", which is exactly the desired column
// name here -- no override needed, unlike `created_by` below.
//
// `created_by` (the ADMIN/BOARD/TREASURER who triggered a `MINT`, or null for a system-authored
// `PROJECT_STAKE`) is a second, OPTIONAL FK to member on this same entity -- plain «Column»
// UUID attribute with «Column».fkEntity, not an association (an association would collide with
// the primary `member_id` association's class-derived default "member_id"; disambiguating via
// role would still produce a less legible result than this codebase's established "actor-style
// FK -> plain Column" idiom, e.g. journal_entry.created_by/motion.reviewed_by).
//
// This file, symmetrically, carries a minimal id-only Member stub (owned by Foundation) purely
// so UmlToErmTransformer can resolve both the member_id association and the created_by «Column»
// .fkEntity override within this single-file evaluation -- same cross-domain-stub pattern every
// other domain in this codebase already establishes.
//
// amount_ltr uses an explicit «Column».sqlType="DECIMAL(18,2)" override, same precision as the
// old balance_ltr column (and the same reason contribution.amountDue/ltr_balance.balance_ltr
// needed one: UmlErmTypeMapper's bare "decimal" keyword defaults to DECIMAL(19,2)).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "LtrLedger") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: LtrLedgerSchemaDriftTest asserts ErmDataType.Enum.values in
    // exactly this order, matching network.lapis.cloud.shared.domain.LtrLedgerEntryType. See file
    // header for which literals this wave actually writes vs. reserves.
    val ltrLedgerEntryType = enumOf(name = "LtrLedgerEntryType") {
        literal(name = "MINT")
        literal(name = "PROJECT_STAKE")
        literal(name = "PROJECT_STAKE_RELEASE")
        literal(name = "VOTE_STAKE")
        literal(name = "PEER_TRANSFER_OUT")
        literal(name = "PEER_TRANSFER_IN")
        literal(name = "AUCTION_LISTING_FEE")
        literal(name = "AUCTION_HOLD")
        literal(name = "AUCTION_HOLD_RELEASE")
        literal(name = "AUCTION_SALE_OUT")
        literal(name = "AUCTION_SALE_IN")
        literal(name = "SOCIAL_POST_STAKE")
    }

    // Literal order is load-bearing, same reason as above. `CROWDFUNDING_PROJECT` is this wave's
    // original literal, `VOTE` the security-fix addition for `VOTE_STAKE` above, `PEER_TRANSFER`
    // the V0.6.3 addition for `PEER_TRANSFER_OUT`/`PEER_TRANSFER_IN`, `AUCTION` the V0.6.2
    // addition for every `AUCTION_*` entry type above -- additively extended by later waves
    // exactly like AuditEntityType.
    val ltrLedgerReferenceType = enumOf(name = "LtrLedgerReferenceType") {
        literal(name = "CROWDFUNDING_PROJECT")
        literal(name = "VOTE")
        literal(name = "PEER_TRANSFER")
        literal(name = "AUCTION")
        literal(name = "SOCIAL_POST")
    }

    val ltrLedgerEntry = classOf(name = "LtrLedgerEntry") {
        stereotype("Entity") { "tableName" to "ltr_ledger_entry"; "kotlinObjectName" to "LtrLedgerEntryTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_ltr_ledger_entry_member" }
        stereotype("Index") { "columns" to listOf("entry_type"); "name" to "idx_ltr_ledger_entry_type" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "entryType", type = ltrLedgerEntryType) {
            stereotype("Column") {
                "columnName" to "entry_type"
                "enumType" to "network.lapis.cloud.shared.domain.LtrLedgerEntryType"
            }
        }
        // Signed: credit positive, debit negative. freeBalance(member) = SUM(amount_ltr) across
        // every row for that member -- see file header for why this is deliberately NOT a
        // double-entry Journal/Posting pair.
        attribute(name = "amountLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        // Nullable together with referenceId -- MINT entries carry neither. See file header for
        // the polymorphic-pointer rationale (mirrors audit_log_entry.entity_type/entity_id).
        attribute(name = "referenceType", type = ltrLedgerReferenceType) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") {
                "columnName" to "reference_type"
                "enumType" to "network.lapis.cloud.shared.domain.LtrLedgerReferenceType"
            }
        }
        // Deliberately NO «Column».fkEntity / no association -- polymorphic pointer, target table
        // depends on referenceType. See file header.
        attribute(name = "referenceId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reference_id" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(500)" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", colliding with the primary member_id association
        // below. See file header for the full rationale (same idiom as journal_entry.created_by/
        // motion.reviewed_by).
        attribute(name = "createdBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    // ltr_ledger_entry.member_id -> member (id): association-derived default matches ("member_id"),
    // safe despite this entity ALSO having created_by, because that second FK is modelled as a
    // plain «Column» attribute (see above), never as a competing association -- same reasoning
    // attendance.member_id's own comment gives for coexisting with represented_by_member_id.
    association(source = member, target = ltrLedgerEntry, id = "assoc-member-ltr_ledger_entry") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }
}

// Audit log domain (V0.5.3, GoBD-Revisionssicherheit) -- a manipulation-evident, append-only
// audit trail for security-/legally-relevant mutations, building on the Kassenbuch GoBD-Vorstufe
// from V0.3.5 (see 10-accounting.kuml.kts's own "Kassenbuch" comment, which explicitly deferred
// "full GoBD tamper-evidence (hash-chaining, retention enforcement, audit-log infrastructure,
// TSE/Kassensicherungsverordnung integration)" to this wave).
//
// **This is a starting point for research, not a legal specification.** Every claim below about
// what GoBD Nachvollziehbarkeit/Nachpruefbarkeit/Unveraenderbarkeit actually requires -- the
// hash-chain mechanism being a sufficient tamper-evidence measure, "no purge path" being an
// adequate way to satisfy GoBD's multi-year retention expectation (no explicit archival/legal-hold
// policy exists), and this wave's bounded MUST/SHOULD/OUT-OF-SCOPE entity coverage being the right
// line to draw -- is the current *understanding* of GoBD (and, where BoardMembership/Transparenz-
// register/§25 PartG entries are concerned, the underlying company/party-law obligations too) at
// the time this wave was written, not a reviewed legal conclusion. **Verify against the current
// GoBD text and, ideally, a GoBD-erfahrener Steuerberater/Wirtschaftspruefer or lawyer before
// relying on this for a real Verein/Partei's actual Aussenpruefung.** This mirrors the same
// disclaimer class already established by
// [network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator] (§25 PartG) and
// [ReserveType.FREIE_RUECKLAGE][network.lapis.cloud.shared.domain.ReserveType]/
// [UseOfFundsCalculator.TIMELY_USE_YEARS] -- see that calculator's own KDoc for the fuller
// rationale of why this codebase treats "current best understanding, explicitly flagged" as
// preferable to either silence or false confidence.
//
// GENUINELY SEPARATE from dsgvo_audit_log (04-dsgvo.kuml.kts) -- the two logs serve different
// legal purposes and are deliberately never merged:
//   - dsgvo_audit_log: DSGVO Art. 5(2) accountability record of erasure/export ACTIONS taken on a
//     data subject's personal data (who exported/erased what, when) -- see that file's own header.
//   - audit_log_entry (this file): GoBD Nachvollziehbarkeit/Nachpruefbarkeit/Unveraenderbarkeit
//     record of security-/legally-relevant business-data MUTATIONS (who changed which
//     JournalEntry/Resolution/BoardMembership, with a before/after snapshot), hash-chained for
//     tamper-evidence. Neither log's rows ever reference the other.
//
// Scope decision for this wave (bounded, not "best effort over everything" -- see
// network.lapis.cloud.server.audit.AuditLogRecorder / AccountingService / GovernanceService /
// ElectionService / SystemicConsensusService / BoardMembershipEvents / BoardMembershipService
// KDoc for the exact call sites):
//   MUST (structurally lueckenlos): JournalEntry lifecycle (CREATE on saveDraftEntry/
//   postJournalEntry, POST on postDraftEntry) -- Kassenbuch-Bewegungen are Postings *within* a
//   JournalEntry and are therefore automatically covered, no separate entity type needed.
//   SHOULD (bounded, but genuinely gapless within that bound -- see auditResolutionCreate/
//   auditBoardMembershipCreate/auditBoardMembershipEnd KDoc for the review finding this closed):
//   Resolution (CREATE only -- resolutions are never mutated after recording -- at EVERY path
//   that inserts one: GovernanceService.recordResolution/resolveMotion/closeVote,
//   ElectionService.tally, SystemicConsensusService.evaluate, all funnelled through the single
//   insertResolutionRow write path), the §25 PartG donation-compliance verdict (CREATE, only for
//   the ALLOWED verdicts that actually reach a committed JournalEntry -- a PROHIBITED attempt
//   rolls back the whole transaction before any audit row could survive, see AuditLogRecorder
//   KDoc), and Transparenzregister BoardMembership changes (CREATE on
//   BoardMembershipService.appointBoardMember/GovernanceService.addCommitteeMember's
//   EXECUTIVE_BOARD co-option path/ElectionService.tally's EXECUTIVE_BOARD winner-seating; UPDATE
//   on BoardMembershipService.endBoardMembership/GovernanceService.endCommitteeMembership's
//   EXECUTIVE_BOARD removal path -- every BoardMembershipEvents.recordBoardJoin/recordBoardLeave
//   call site is audited, not just the administrative one).
//   EXPLICITLY OUT OF SCOPE this wave: LedgerAccount/CostCenter/ExternalDonor master-data CRUD,
//   Election/SystemicConsensus/Vote internals (everything except the Resolution/BoardMembership
//   they produce, per the coverage above), Contribution, Document/Mailing, Member CRUD, and
//   any retention/archival/TTL policy -- no purge path exists at all (see AuditLogPersonalData
//   KDoc), which this wave's current understanding takes to be a sufficient way to avoid
//   contradicting GoBD's multi-year retention expectation by omission. That is NOT the same as a
//   reviewed retention policy (legal-hold triggers, an actual minimum/maximum retention period,
//   archival once retention lapses) -- see this file's own top-of-file legal-verification
//   disclaimer; a real deployment needs that policy confirmed, not just "we never delete anything".
//
// Hash-chain mechanism (concept only -- the actual SHA-256 computation lives in
// network.lapis.cloud.server.audit.AuditHashChain, this model only carries the persisted shape):
// every audit_log_entry row stores its own entry_hash plus the *previous* row's hash
// (previous_entry_hash, NULL only for the very first/"genesis" row) -- a later row's stored
// previous_entry_hash must equal the immediately-preceding row's entry_hash, and any row's stored
// entry_hash must equal a fresh recomputation over that row's own fields. Tampering with any single
// row (including deleting one outright, which breaks the sequence_number's gapless-ness) is
// therefore algorithmically detectable by re-walking the chain -- see
// network.lapis.cloud.shared.rpc.IAuditLogService.verifyChainIntegrity.
//
// Genesis-singleton-row pattern, directly mirroring 11-organization-settings.kuml.kts's
// organization_settings (same "exactly one row by convention, seeded once, fixed sentinel id"
// idiom): audit_log_chain_state holds exactly one row (id =
// 00000000-0000-0000-0000-0000000000f3, the next unused '...-0000-0000000000fN' sentinel after
// organization_settings's own '...-f2') carrying last_sequence_number/last_entry_hash. A
// `SELECT ... FOR UPDATE` lock on THIS ONE row (not "the last audit_log_entry row ORDER BY
// sequence_number DESC LIMIT 1 FOR UPDATE", which locks nothing on an empty table and would leave
// the very first insert racy) is what serializes concurrent audit-log writers -- see
// network.lapis.cloud.server.audit.AuditLogRecorder KDoc for the full mechanism and its
// deadlock-avoidance contract (this lock must always be taken LAST in a transaction).
//
// entity_id (on audit_log_entry) is deliberately a plain UUID column with NO «Column».fkEntity /
// no association -- it is a polymorphic pointer whose target table depends on entity_type
// (JOURNAL_ENTRY/RESOLUTION/BOARD_MEMBERSHIP/PARTY_DONATION_VERDICT, the last of which also points
// at a journal_entry row), so no single FK constraint could express it. Documented, accepted
// modelling gap -- see the file-header note in AuditLogRecorder for the practical consequence
// (none of these referenced entities are ever deleted in this codebase, so an orphaned entity_id
// is a theoretical, not practical, concern).
//
// DSGVO: audit_log_entry.actor_member_id is the ONLY member-FK-bearing column this domain adds --
// see network.lapis.cloud.server.dsgvo.AuditLogPersonalData for its export/erasure coverage
// (retained unconditionally, same GoBD-overrides-erasure reasoning as AccountingPersonalData, in
// fact stronger: anonymizing the actor would undermine the very accountability trail this table
// exists to provide).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "AuditLog") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by every
    // prior domain's own Member stub. Only exists here so UmlToErmTransformer can resolve
    // audit_log_entry.actor_member_id's FK target within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Reuses foundation's AccountRole literal set (00-foundation.kuml.kts) — modelled locally
    // here too since kUML has no cross-file model-import mechanism (each domain script is
    // evaluated independently), same re-declaration idiom 04-dsgvo.kuml.kts's own dsgvo_audit_log
    // .actor_role already establishes. Nullable here too, for the same reason: a future SYSTEM/job
    // actor has no member role at all.
    val accountRole = enumOf(name = "AccountRole") {
        literal(name = "MEMBER")
        literal(name = "BOARD")
        literal(name = "TREASURER")
        literal(name = "ADMIN")
    }

    // Literal order is load-bearing: AuditLogSchemaDriftTest asserts ErmDataType.Enum.values in
    // exactly this order, matching network.lapis.cloud.shared.domain.AuditAction. Additively
    // extensible (e.g. a future VOID literal alongside a Storno mechanism) -- same "cheap to
    // extend, expensive to reorder" note every other domain enum in this codebase carries.
    val auditAction = enumOf(name = "AuditAction") {
        literal(name = "CREATE")
        literal(name = "UPDATE")
        literal(name = "POST")
    }

    // The entity kinds this wave's bounded scope audits -- see file header. Literal order is
    // load-bearing: AuditLogSchemaDriftTest asserts ErmDataType.Enum.values in exactly this order,
    // matching network.lapis.cloud.shared.domain.AuditEntityType. CONFERENCE_RECORDING (V1.0
    // Videokonferenzen Wave 2 "Aufzeichnung") was appended LAST, after the original four -- see
    // that domain's own ConferenceRecordingService KDoc for why start/stop are audited (this
    // wave's own §32 BGB/GoBD framing: "who started recording this meeting, when" is precisely the
    // fact that must be provable). Additive append only -- never reorder existing literals, see
    // this enum's own "cheap to extend, expensive to reorder" note class-wide.
    val auditEntityType = enumOf(name = "AuditEntityType") {
        literal(name = "JOURNAL_ENTRY")
        literal(name = "PARTY_DONATION_VERDICT")
        literal(name = "RESOLUTION")
        literal(name = "BOARD_MEMBERSHIP")
        literal(name = "CONFERENCE_RECORDING")
    }

    // Genesis-singleton row (see file header) -- gapless sequence_number + hash-chain
    // race-free continuation point. Exactly one row, seeded directly in V1__baseline.sql at a
    // fixed sentinel id (mirrors organization_settings), never inserted a second time by
    // application code.
    val auditLogChainState = classOf(name = "AuditLogChainState") {
        stereotype("Entity") { "tableName" to "audit_log_chain_state"; "kotlinObjectName" to "AuditLogChainStateTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "lastSequenceNumber", type = "Long") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "last_sequence_number" }
        }
        // NULL only ever means "no entry written yet" (the pre-genesis state of the singleton
        // row) -- the first audit_log_entry row itself always stores previous_entry_hash = NULL
        // too, see file header.
        attribute(name = "lastEntryHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_entry_hash"; "sqlType" to "VARCHAR(64)" }
        }
    }

    val auditLogEntry = classOf(name = "AuditLogEntry") {
        stereotype("Entity") { "tableName" to "audit_log_entry"; "kotlinObjectName" to "AuditLogEntryTable" }
        stereotype("Index") {
            "columns" to listOf("sequence_number")
            "unique" to true
            "name" to "uq_audit_log_entry_sequence"
        }
        stereotype("Index") { "columns" to listOf("entity_id"); "name" to "idx_audit_log_entry_entity_id" }
        stereotype("Index") { "columns" to listOf("occurred_at"); "name" to "idx_audit_log_entry_occurred_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Gapless, monotonically increasing, assigned by AuditLogRecorder under the
        // audit_log_chain_state row lock -- see file header. UNIQUE (pinned via the class-level
        // «Index» above, same "single-column UNIQUE via named «Index»" idiom every other domain in
        // this codebase uses, e.g. ledger_account.account_number).
        attribute(name = "sequenceNumber", type = "Long") {
            stereotype("Column") { "columnName" to "sequence_number" }
        }
        attribute(name = "occurredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "occurred_at" }
        }
        // Nullable -- reserved for a future SYSTEM/job actor with no member. Plain «Column» UUID
        // attribute, not a UML association -- association-derived FK naming would still yield
        // "member_id", but every other domain's own equivalent (dsgvo_audit_log.actor_member_id,
        // journal_entry.created_by) uses the explicit «Column».fkEntity form for consistency, so
        // this follows suit.
        attribute(name = "actorMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "actor_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "actorRole", type = accountRole) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "actor_role"; "enumType" to "network.lapis.cloud.shared.domain.AccountRole" }
        }
        attribute(name = "entityType", type = auditEntityType) {
            stereotype("Column") {
                "columnName" to "entity_type"
                "enumType" to "network.lapis.cloud.shared.domain.AuditEntityType"
            }
        }
        // Deliberately NO «Column».fkEntity / no association -- polymorphic pointer, target table
        // depends on entityType. See file header for the full rationale and its accepted
        // limitation (no DB-level FK constraint backs this column).
        attribute(name = "entityId", type = "UUID") {
            stereotype("Column") { "columnName" to "entity_id" }
        }
        attribute(name = "action", type = auditAction) {
            stereotype("Column") { "columnName" to "action"; "enumType" to "network.lapis.cloud.shared.domain.AuditAction" }
        }
        // Unbounded TEXT, NOT a capped VARCHAR -- a JournalEntrySnapshot's serialized size grows
        // with its Postings list (no cap on posting count anywhere in AccountingService), so ANY
        // fixed VARCHAR length is just a bigger deadline until the same failure recurs. Originally
        // VARCHAR(8000) (mirroring dsgvo_audit_log.outcome_summary), but that was falsified during
        // review: an empirically ordinary ~44-Posting JournalEntry already serializes past 8000
        // chars, and because AuditLogRecorder.record() writes in the SAME transaction as the
        // JournalEntry/Posting insert it accompanies (see that object's KDoc), a snapshot
        // overflowing the column would abort the whole transaction -- silently rejecting a
        // legitimate, balanced business mutation for a reason entirely unrelated to accounting
        // rules. GoBD Vollstaendigkeit also rules out silently truncating the snapshot instead: a
        // half-written before/after JSON payload is a worse audit record than none at all. See
        // AuditLogRecorder/JournalEntrySnapshot KDoc for the full rationale.
        attribute(name = "beforeSnapshot", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "before_snapshot"; "sqlType" to "text" }
        }
        attribute(name = "afterSnapshot", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "after_snapshot"; "sqlType" to "text" }
        }
        attribute(name = "entryHash", type = "String") {
            stereotype("Column") { "columnName" to "entry_hash"; "sqlType" to "VARCHAR(64)" }
        }
        // NULL only for the very first ("genesis") row -- see file header. No CHECK expresses
        // "NULL iff sequence_number == 1" (cross-row invariant); enforced exclusively by
        // AuditLogRecorder always being the sole write path (append-only, no update/delete path at
        // all -- see that object's KDoc and AuditLogImmutabilityTest).
        attribute(name = "previousEntryHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "previous_entry_hash"; "sqlType" to "VARCHAR(64)" }
        }
    }
}

// LTR-Balance domain — ltr_balance (created inside V8__meritokratische_voteen.sql, NOT its
// own V-file: the migration's own header comment explains why — "ltr_balance -> ALTER TABLE
// resolution (FK-Ziel vote existiert erst danach)" — i.e. ltr_balance's CREATE TABLE had to
// land physically before the later `ALTER TABLE resolution ADD COLUMN vote_id ...` in the
// same migration file, so it was folded into V8 rather than getting a dedicated V-number).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table object
// (network.lapis.cloud.server.db.tables.LtrBalanceTable.kt) by LtrBalanceSchemaDriftTest. Per
// ADR-0016's designModelStrategy option B, this is a verification-only artifact for now: the
// hand-written Table object remains the actually-compiled/actually-imported source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the full
// rationale (enum-to-VARCHAR type-fidelity gap — not actually triggered in this domain, there are
// no enum columns here — and the Kotlin-object-naming-override gap).
//
// Explicitly, deliberately provisional table (per LtrBalanceTable.kt's own KDoc): just a balance
// snapshot per member, no debit/credit/reserve ledger columns yet. V0.6 (LTR-Wirtschaft/Auktion/
// Price-Oracle) replaces this with a real ledger — at that point this .kuml.kts file will need
// RE-MODELLING (new entity shape, likely a proper Ledger/Transaction pair), not just
// re-generation from the same shape. Noted here so a future agent doesn't mistake this file for a
// stable, settled model.
//
// The load-bearing modelling decision for this domain: member_id is simultaneously the PRIMARY
// KEY and the FK to member — `PrimaryKey(memberId)` in the hand-written table, no separate
// synthetic `id` column at all. UmlToErmTransformer's default synthetic-PK behaviour
// (syntheticPkTemplate) only backs off when at least one ColumnTemplate already has
// primaryKey=true (mapAttributeToColumn: `isPk = attr.name.equals("id", ignoreCase = true) ||
// idStereo != null`) — so tagging member_id's attribute directly with the «Id» stereotype (not
// «FK» alone, and NOT modelling it as a UML association) suppresses the synthetic `id` column
// entirely, matching the real schema/hand-written table exactly.
//
// This also means member_id cannot be modelled as a UML association at all here: verified by
// reading UmlToErmTransformer.addForeignKey (kuml-transform-uml-to-erm) directly — every
// association-derived FK column is unconditionally created with `primaryKey = false` (line ~597,
// no override mechanism of any kind), so an association could never produce a column that is also
// the primary key. Modelling member_id as a plain «Column» UUID attribute carrying BOTH «Id» and
// an explicit columnName tag is therefore the only way to reproduce this exact shape — consistent
// with (and an even more direct case of) the plain-«Column»-attribute fallback already used
// throughout document/communication/dsgvo/governance/vote/election for FK-name-mismatch cases;
// here the reason isn't a name mismatch but a structural one (PK-that-is-also-FK). The real FK
// target (member) is still independently pinned via LtrBalanceSchemaDriftTest's
// information_schema introspection against the live H2-migrated schema, exactly like every prior
// plain-«Column»-FK case.
//
// No enum columns in this domain. balance_ltr uses an explicit «Column».sqlType="DECIMAL(18,2)"
// override to match the real schema/hand-written table's wider precision (DECIMAL(18,2), vs.
// contribution.amountDue's narrower DECIMAL(12,2)) — UmlErmTypeMapper's bare "decimal" keyword
// defaults to DECIMAL(19,2), so the override is required here for the same reason contribution's
// amountDue needed one.
//
// member_id's FK target is pinned via «Column».fkEntity (not an association — see above), which
// is why this file, symmetrically, carries a minimal id-only Member stub (owned by Foundation)
// purely so UmlToErmTransformer can resolve it within this single-file evaluation — same
// cross-domain-stub pattern established by contribution/document/dsgvo's own Member stubs.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "LtrBalance") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val ltrBalance = classOf(name = "LtrBalance") {
        stereotype("Entity") { "tableName" to "ltr_balance"; "kotlinObjectName" to "LtrBalanceTable" }

        // Real column is simultaneously PRIMARY KEY and FK -> member (id). Modelled as a plain
        // «Column» UUID attribute carrying «Id» (not a UML association — see file header comment
        // for why an association can never produce primaryKey=true here). FK target pinned via
        // «Column».fkEntity, independently cross-checked via LtrBalanceSchemaDriftTest's
        // information_schema introspection.
        attribute(name = "memberId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "balanceLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "balance_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }
}

// Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- see network.lapis.cloud.server.crm.* for the
// store/policy, network.lapis.cloud.server.dsgvo.CrmPersonalData for Auskunft/Loeschung, and
// network.lapis.cloud.server.rpc.CrmService for the RPC-facing read/write path.
//
// **This file models exactly two new tables.** `crm_contact` is a standalone address-book entity
// for people who are NEITHER a `member` NOR (necessarily) an `external_donor` -- an interested
// party who attended an event, a sympathizer who signed up at an Infostand, someone who once
// donated and left, a journalist. `crm_interaction` is its append-only interaction log (one row
// per contact, phone call, meeting note, ...).
//
// **Why NOT `external_donor`**: that entity (10-accounting.kuml.kts) is scoped tightly to §25
// PartG/§10b EStG donation bookkeeping -- donor_category is a NOT NULL legal classification a
// treasurer assigns, and the entity has no interaction history, no lawful-basis/consent tracking,
// and no retention-review workflow. Widening it to also carry CRM contact-management data would
// conflate two different legal regimes (Spendenrecht vs. general Art. 6(1) DSGVO processing) in one
// table. `crm_contact.external_donor_id` links the two where a real person is both, without merging
// their schemas.
//
// **Why NOT `MemberStatus.FRIEND`**: that status (00-foundation.kuml.kts) is deliberately scoped to
// "self-registered, verified, video-conference/LTR-adjacent access" -- a FRIEND has a login account
// and an `account` row. A CRM contact has neither; forcing every interested party through the
// member/account machinery just to record "spoke to them at a table" would be a category error and
// would pull them into every member-shaped surface (roster exports, election eligibility checks)
// that were never meant to see them.
//
// **Why NOT `audit_log_entry`**: that table (14-audit-log.kuml.kts) is the GoBD hash-chained,
// append-only ledger for financially/legally load-bearing mutations, and its own file header
// explicitly excludes ordinary master-data CRUD and free-text content from it (see that file's
// `AuditEntityType` rationale). `crm_interaction.summary` is exactly the kind of free-text content
// that doctrine excludes -- hashing/chaining it would misrepresent what the chain is a guarantee
// about. `crm_contact`/`crm_interaction` writes are therefore never routed through
// `AuditLogRecorder`; the only DSGVO-relevant record of what happened to a contact's data is
// `dsgvo_audit_log` (Art. 15/17 export/erasure only, counts/metadata never payload -- see
// 04-dsgvo.kuml.kts's own file header for that table's payload-freedom doctrine, which this wave's
// new `subject_kind` column keeps intact).
//
// **Why `crm_contact` is a DSGVO subject ROOT, not a leaf covered by an existing contributor**: a
// `crm_contact` who is NOT linked to a `member`/`external_donor` row is a natural person whose PII
// exists ONLY in this table -- there is no member-FK walk that could ever discover them. Making
// `crm_contact` itself a subject root (alongside `member`) is what lets
// `network.lapis.cloud.server.dsgvo.PersonalDataCoverageTest`'s `information_schema` walk extend to
// non-member subjects mechanically, instead of hand-listing `crm_interaction` in an allowlist that
// would silently stop being checked the day a THIRD crm_contact-referencing table appears. See
// `network.lapis.cloud.server.dsgvo.DataSubject`/`PersonalDataRegistry` KDoc for the framework this
// plugs into.
//
// **FK-naming choice**: every `*_member_id`/`external_donor_id`/`contact_id` reference is a plain
// «Column» UUID attribute with «Column».fkEntity, NEVER a UML association -- same domain-wide policy
// 21-auction.kuml.kts's own header documents at length, already followed by every later domain file
// including 37-webhook.kuml.kts.
//
// **This file carries minimal id-only Member and ExternalDonor stubs** (owned by Foundation resp.
// by 10-accounting.kuml.kts), purely so `UmlToErmTransformer` can resolve this file's «Column».
// fkEntity overrides within this single-file evaluation -- same cross-domain-stub pattern
// 37-webhook.kuml.kts already establishes for its own Member/ApiKey stubs.
//
// **Cross-field CHECK constraints are SQL-only, not modelled here** -- same posture
// 10-accounting.kuml.kts's own header takes for its cross-column invariants (the erm-to-exposed
// codegen has no typed representation for a multi-column CHECK; see every prior domain's own
// "N check constraint(s) declared on this entity are not emitted" comment on its generated Table
// object). `V17__crm_contacts.sql` carries the actual `chk_crm_contact_*` constraints.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Crm") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub. Resolves
    // crm_contact.member_id/created_by and crm_interaction.recorded_by's «Column».fkEntity
    // overrides within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 10-accounting.kuml.kts-owned stub -- id-only. Resolves crm_contact.external_donor_id's
    // «Column».fkEntity override within this single-file evaluation.
    val externalDonor = classOf(name = "ExternalDonor") {
        stereotype("Entity") { "tableName" to "external_donor"; "kotlinObjectName" to "ExternalDonorTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Kind of interested party -- a treasurer/board member picks this at creation; purely
    // descriptive, no compliance logic hangs off it (unlike accounting's DonorCategory).
    val crmContactType = enumOf(name = "CrmContactType") {
        literal(name = "INTERESSENT")
        literal(name = "SYMPATHISANT")
        literal(name = "FOERDERER")
        literal(name = "EHEMALIGES_MITGLIED")
        literal(name = "PRESSE")
    }

    // Art. 6(1) DSGVO lawful basis for processing THIS contact's data -- a mandatory, deliberate
    // choice at creation (see CrmContactPolicy.validate), never inferred or defaulted. Drives
    // `mayReceiveEmail` (CrmContactPolicy) together with the consent_* columns below.
    val crmLawfulBasis = enumOf(name = "CrmLawfulBasis") {
        literal(name = "CONSENT") // Art. 6(1)(a) -- requires consent_source + consent_given_at, see CHECK below
        literal(name = "LEGITIMATE_INTEREST") // Art. 6(1)(f)
        literal(name = "CONTRACT") // Art. 6(1)(b)
    }

    // Append-only interaction kind -- see crm_interaction below.
    val crmInteractionKind = enumOf(name = "CrmInteractionKind") {
        literal(name = "CALL")
        literal(name = "MEETING")
        literal(name = "EMAIL")
        literal(name = "LETTER")
        literal(name = "EVENT")
        literal(name = "NOTE")
    }

    val crmContact = classOf(name = "CrmContact") {
        stereotype("Entity") { "tableName" to "crm_contact"; "kotlinObjectName" to "CrmContactTable" }
        stereotype("Index") { "columns" to listOf("email"); "name" to "uq_crm_contact_email"; "unique" to true }
        stereotype("Index") { "columns" to listOf("external_donor_id"); "name" to "uq_crm_contact_external_donor"; "unique" to true }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "uq_crm_contact_member"; "unique" to true }
        stereotype("Index") { "columns" to listOf("archived_at", "retention_review_due_at"); "name" to "idx_crm_contact_retention_due" }
        stereotype("Index") { "columns" to listOf("contact_type"); "name" to "idx_crm_contact_type" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "displayName", type = "String") {
            stereotype("Column") { "columnName" to "display_name"; "sqlType" to "VARCHAR(300)" }
        }
        // Server-side lowercase-normalized (see CrmContactPolicy.normalizeEmail). Nullable, but
        // UNIQUE where present -- H2/Postgres both allow multiple NULLs under a unique index.
        attribute(name = "email", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "email"; "sqlType" to "VARCHAR(320)" }
        }
        attribute(name = "phone", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "phone"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "street", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "street"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "postalCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "postal_code"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "city", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "city"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "country", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "country"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "contactType", type = crmContactType) {
            stereotype("Column") {
                "columnName" to "contact_type"
                "enumType" to "network.lapis.cloud.shared.domain.CrmContactType"
            }
        }
        attribute(name = "lawfulBasis", type = crmLawfulBasis) {
            stereotype("Column") {
                "columnName" to "lawful_basis"
                "enumType" to "network.lapis.cloud.shared.domain.CrmLawfulBasis"
            }
        }
        attribute(name = "consentSource", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consent_source"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "consentGivenAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consent_given_at" }
        }
        attribute(name = "consentWithdrawnAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consent_withdrawn_at" }
        }
        attribute(name = "externalDonorId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "external_donor_id"; "fkEntity" to "ExternalDonor" }
        }
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "lastInteractionAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_interaction_at" }
        }
        // coalesce(last_interaction_at, created_at) + 24 months, recomputed by CrmContactStore on
        // every recordInteraction -- see CrmContactPolicy.retentionReviewDueAt.
        attribute(name = "retentionReviewDueAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "retention_review_due_at" }
        }
        // "Out of sight" -- deliberately NOT a deletion path. See CrmPersonalData.erase for the one
        // actual deletion path (Art. 17), which is a hard DELETE, not this flag.
        attribute(name = "archivedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "archived_at" }
        }
    }

    // Append-only -- no UPDATE/DELETE service path except the wholesale deletion
    // CrmPersonalData.erase performs for Art. 17. See file header "Why NOT audit_log_entry" for why
    // this lives here rather than in the GoBD chain.
    val crmInteraction = classOf(name = "CrmInteraction") {
        stereotype("Entity") { "tableName" to "crm_interaction"; "kotlinObjectName" to "CrmInteractionTable" }
        stereotype("Index") { "columns" to listOf("contact_id", "occurred_at"); "name" to "idx_crm_interaction_contact" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "contactId", type = "UUID") {
            stereotype("Column") { "columnName" to "contact_id"; "fkEntity" to "CrmContact" }
        }
        attribute(name = "occurredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "occurred_at" }
        }
        attribute(name = "kind", type = crmInteractionKind) {
            stereotype("Column") {
                "columnName" to "kind"
                "enumType" to "network.lapis.cloud.shared.domain.CrmInteractionKind"
            }
        }
        // The only mandatory field on the capture form -- see CrmContactsScreen.kt KDoc.
        attribute(name = "summary", type = "String") {
            stereotype("Column") { "columnName" to "summary"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "recordedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "recorded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "recordedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "recorded_at" }
        }
    }
}

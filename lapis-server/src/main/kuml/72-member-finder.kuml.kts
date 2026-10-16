// Welle V1.9.144 "Mitglieder-Finder" (server) -- see network.lapis.cloud.server.memberfinder (the only package that reads or writes these four
// tables, apart from the GDPR contributor), docs/features/member-finder.adoc and docs/architecture/member-finder.adoc.
//
// Party membership is a special category of data (Art. 9 GDPR and comparable rules), so this model stores as little as possible:
//   * **No region on any person row.** The region (the first two digits of the German postal code) is derived from member.postal_code on every
//     query (MemberFinderRegion.keyOf) and is never stored next to a member. The ONLY region key lives in member_finder_region_band, which has no
//     member column at all (an aggregate: region + coarse band).
//   * **to_member_id NULL** on member_finder_request means a PHANTOM request (nobody was found; the sender must not learn that) or a request whose
//     counterpart link was detached after the retention period. A sender only ever sees derived states, never the stored status.
//   * **Excluded from the organization backup** (OrganizationSchemaCatalog.EXCLUDED_TABLES): the ADMIN-only whole-organization backup must not
//     become a way to read who is interested in finding other members. Restore targets an empty database, so every member consents again.
//   * **FK naming**: plain «Column» UUID attributes with «Column».fkEntity, never a UML association (the domain-wide policy 21-auction.kuml.kts
//     documents). A minimal id-only Member stub (owned by Foundation) resolves them.
//   * **time-fields:** every timestamp is class A (DbClock, UTC) -- see lapis-server/src/test/resources/time-fields.tsv.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberFinder") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Append-only proof of consent. The open row of a member has superseded_at NULL. GRANT carries the version label and hash of the consent text
    // (FINDER agreement kind), the interest and the adult confirmation; REVOKE carries the reason (MEMBER | STATUS_LOST | INSTANCE_OFF).
    val consentEvent = classOf(name = "MemberFinderConsentEvent") {
        stereotype("Entity") { "tableName" to "member_finder_consent_event"; "kotlinObjectName" to "MemberFinderConsentEventTable" }
        stereotype("Index") { "columns" to listOf("member_id", "superseded_at"); "name" to "idx_member_finder_consent_event_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "eventType", type = "String") {
            stereotype("Column") { "columnName" to "event_type"; "sqlType" to "VARCHAR(6)" }
        }
        attribute(name = "occurredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "occurred_at" }
        }
        attribute(name = "supersededAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "superseded_at" }
        }
        attribute(name = "consentVersion", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consent_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "consentSha256", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consent_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "interest", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "interest"; "sqlType" to "VARCHAR(14)" }
        }
        attribute(name = "adultConfirmed", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "adult_confirmed" }
        }
        attribute(name = "revokeReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoke_reason"; "sqlType" to "VARCHAR(12)" }
        }
    }

    // A request from one member to ONE randomly chosen counterpart. NO region column. to_member_id NULL = phantom or detached.
    val request = classOf(name = "MemberFinderRequest") {
        stereotype("Entity") { "tableName" to "member_finder_request"; "kotlinObjectName" to "MemberFinderRequestTable" }
        stereotype("Index") { "columns" to listOf("to_member_id", "status"); "name" to "idx_member_finder_request_to" }
        stereotype("Index") { "columns" to listOf("from_member_id", "status"); "name" to "idx_member_finder_request_from" }
        stereotype("Index") { "columns" to listOf("status", "expires_at"); "name" to "idx_member_finder_request_expiry" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "fromMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "from_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "toMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "to_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "purpose", type = "String") {
            stereotype("Column") { "columnName" to "purpose"; "sqlType" to "VARCHAR(14)" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(9)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_at" }
        }
        // The sender withdrew the request (any stored status). Kept apart from status so a withdrawal never overwrites the addressee's own decision.
        // The time of the withdrawal drives retention (deleted 7 days later whatever the stored status), so every withdrawal vanishes at the same age.
        attribute(name = "senderWithdrawnAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "sender_withdrawn_at" }
        }
    }

    // A block set by one member. The only table that names two members besides member_finder_request.
    val block = classOf(name = "MemberFinderBlock") {
        stereotype("Entity") { "tableName" to "member_finder_block"; "kotlinObjectName" to "MemberFinderBlockTable" }
        stereotype("Index") { "columns" to listOf("blocked_member_id"); "name" to "idx_member_finder_block_blocked" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "blockerMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "blocker_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "blockedMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "blocked_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    // The daily aggregate. NO member column. Only regions with at least MEMBER_FINDER_MIN_REGION_SIZE consenting members have a row.
    val regionBand = classOf(name = "MemberFinderRegionBand") {
        stereotype("Entity") { "tableName" to "member_finder_region_band"; "kotlinObjectName" to "MemberFinderRegionBandTable" }

        attribute(name = "regionKey", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "region_key"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "band", type = "String") {
            stereotype("Column") { "columnName" to "band"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "computedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "computed_at" }
        }
    }
}

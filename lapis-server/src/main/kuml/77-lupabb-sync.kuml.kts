// LupaBB member master-data sync -- member_lupabb_profile / lupabb_sync_config / lupabb_sync_run / lupabb_sync_suppression
// (V122__lupabb_sync.sql). The two new columns of `member` (lupabb_person_number, lupabb_missing_since) are modelled in 00-foundation.kuml.kts.
//
// Welle V1.9.226 "LupaBB-Stammdatenabgleich" (ADMIN, manual): a one-way comparison of the members with the data set of the LupaBB API
// (the former PZB member administration). See network.lapis.cloud.server.lupabb (the only package that reads or writes these tables, apart from
// the personal-data contributor) and docs/features/lupabb-sync.adoc.
//
// DECISIONS THAT SHAPE THE TABLES -- do not "repair" any of them:
//   * member_lupabb_profile is a side table (PK = member_id): given/family name, title, affix, company, person type, telephone numbers, address
//     supplement and post box have no column in `member`. member.display_name is DERIVED from them by the sync.
//     last_touched_fields is a comma separated list of fixed field keys (never values).
//   * lupabb_sync_config is a ONE ROW table (id = 1, chk_lupabb_sync_config_singleton). credentials_ciphertext is the SecretBox seal of the user,
//     password and API key; it is bound to the key LAPIS_SECRET_ENCRYPTION_KEY and therefore NOT part of the organisation backup. running_since /
//     run_claim / last_fetch_started_at are the compare-and-set guard against concurrent and too frequent runs.
//   * lupabb_sync_run is the log of the APPLIED runs (counters and the SHA-256 of the data, no personal data).
//   * lupabb_sync_suppression holds the HMAC-SHA256 of the person number of every LupaBB person that was erased under Art. 17 GDPR, so the next
//     sync does not create the member again. The person number itself is not kept.
//   * FK naming: plain «Column» attributes with fkEntity, never a UML association (the domain-wide policy 21-auction.kuml.kts documents).
//
// NOT expressible in the ERM profile (SQL only, V122): chk_lupabb_person_number_positive, chk_member_lupabb_profile_person_type,
// chk_lupabb_sync_config_singleton, chk_lupabb_sync_config_transport, and the widened audit_log_entry / member_status_history CHECKs.
//
// time-fields: every timestamp is class A (DbClock, UTC); member.lupabb_missing_since is a class D date (organisation zone).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "LupaBbSync") {
    applyProfile(ermMappingProfile)

    classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "MemberLupaBbProfile") {
        stereotype("Entity") { "tableName" to "member_lupabb_profile"; "kotlinObjectName" to "MemberLupaBbProfileTable" }

        attribute(name = "memberId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "givenName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "given_name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "familyName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "family_name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "title", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "nameAffix", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "name_affix"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "company", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "company"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "personType", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "person_type"; "sqlType" to "VARCHAR(7)" }
        }
        attribute(name = "phone", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "phone"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "mobile", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "mobile"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "addressSupplement", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "address_supplement"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "poBox", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "po_box"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "lastSyncedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "last_synced_at" }
        }
        attribute(name = "lastTouchedFields", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_touched_fields"; "sqlType" to "VARCHAR(400)" }
        }
    }

    classOf(name = "LupaBbSyncConfig") {
        stereotype("Entity") { "tableName" to "lupabb_sync_config"; "kotlinObjectName" to "LupaBbSyncConfigTable" }

        attribute(name = "id", type = "Int") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "credentialsCiphertext", type = "String") {
            stereotype("Column") { "columnName" to "credentials_ciphertext"; "sqlType" to "TEXT" }
        }
        attribute(name = "apiKeyTransport", type = "String") {
            stereotype("Column") { "columnName" to "api_key_transport"; "sqlType" to "VARCHAR(6)" }
        }
        attribute(name = "setAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "set_at" }
        }
        attribute(name = "setBy", type = "UUID") {
            stereotype("Column") { "columnName" to "set_by"; "fkEntity" to "Member" }
        }
        attribute(name = "runningSince", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "running_since" }
        }
        attribute(name = "runClaim", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "run_claim" }
        }
        attribute(name = "lastFetchStartedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_fetch_started_at" }
        }
    }

    classOf(name = "LupaBbSyncRun") {
        stereotype("Entity") { "tableName" to "lupabb_sync_run"; "kotlinObjectName" to "LupaBbSyncRunTable" }
        stereotype("Index") { "columns" to listOf("finished_at", "id"); "name" to "ix_lupabb_sync_run_finished" }
        stereotype("Index") { "columns" to listOf("started_by"); "name" to "ix_lupabb_sync_run_started_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "startedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "started_by"; "fkEntity" to "Member" }
        }
        attribute(name = "startedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "started_at" }
        }
        attribute(name = "finishedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "finished_at" }
        }
        attribute(name = "createdCount", type = "Int") {
            stereotype("Column") { "columnName" to "created_count" }
        }
        attribute(name = "updatedCount", type = "Int") {
            stereotype("Column") { "columnName" to "updated_count" }
        }
        attribute(name = "unchangedCount", type = "Int") {
            stereotype("Column") { "columnName" to "unchanged_count" }
        }
        attribute(name = "skippedCount", type = "Int") {
            stereotype("Column") { "columnName" to "skipped_count" }
        }
        attribute(name = "ambiguousCount", type = "Int") {
            stereotype("Column") { "columnName" to "ambiguous_count" }
        }
        attribute(name = "errorCount", type = "Int") {
            stereotype("Column") { "columnName" to "error_count" }
        }
        attribute(name = "missingCount", type = "Int") {
            stereotype("Column") { "columnName" to "missing_count" }
        }
        attribute(name = "dataSha256", type = "String") {
            stereotype("Column") { "columnName" to "data_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }

    classOf(name = "LupaBbSyncSuppression") {
        stereotype("Entity") { "tableName" to "lupabb_sync_suppression"; "kotlinObjectName" to "LupaBbSyncSuppressionTable" }

        attribute(name = "personNumberHmac", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "person_number_hmac"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }
}

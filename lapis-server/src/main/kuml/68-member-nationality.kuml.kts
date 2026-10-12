// Welle V1.9.121 "Land und Staatsangehoerigkeit als Auswahlliste" -- see network.lapis.cloud.server.member.MemberCountryStore,
// network.lapis.cloud.shared.domain.CountryCodes and docs/features/member-country-nationality.adoc for the full rationale
// (V96__member_country_and_nationality_codes.sql).
//
// **This file models exactly one new table.** `member_nationality` holds the 0..10 nationalities a member has chosen from the
// selection list: one row per (member, ISO 3166-1 alpha-2 code | XK | XS | ZZ). The country of residence is NOT here -- it is the
// new nullable column `member.country_code` (modelled in 00-foundation.kuml.kts).
//
// **`XS` (stateless, ICAO XXA) and `ZZ` (unclear)** are internal codes and stand alone; that exclusivity and the 10-row cap are
// enforced by the application under the member row lock (a CHECK with a subquery or a trigger is not portable between H2 and
// Postgres). The DB CHECK only pins the shape (two upper-case characters) -- plain SQL in V96, not expressible in the ERM profile.
//
// **Composite primary key (member_id, country_code), no surrogate id** -- the same pattern as member_status_history.
// **No CASCADE on the FK** (house convention): rows are removed by MemberNationalityPersonalData on erasure.
// **DSGVO**: personal data (nationality is GwG beneficial-owner data), exported and erased via MemberNationalityPersonalData; part
// of backup and restore (organisational member data).
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same single-file-evaluation pattern every
// later domain file's own header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberNationality") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val memberNationality = classOf(name = "MemberNationality") {
        stereotype("Entity") { "tableName" to "member_nationality"; "kotlinObjectName" to "MemberNationalityTable" }

        stereotype("Index") { "columns" to listOf("country_code"); "name" to "idx_member_nationality_country" }

        // Composite primary key (member_id, country_code).
        attribute(name = "memberId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // ISO 3166-1 alpha-2 | XK (Kosovo) | XS (stateless) | ZZ (unclear)
        attribute(name = "countryCode", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "country_code"; "sqlType" to "CHAR(2)" }
        }
    }
}

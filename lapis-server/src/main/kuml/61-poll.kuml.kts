// Poll domain -- poll / poll_option / poll_participation / poll_response (V65__polls.sql).
//
// Welle V1.9.30 "Umfragen auf LTR-Basis" (Server) -- a NON-BINDING opinion poll (Stimmungsbild) that
// a Vorstand/Gremien leader can put to the active members. Deliberately separate from the elections
// and votes (nothing here is a resolution, `PollDto.binding` is always false) and WITHOUT any LTR
// flow: the responder's free LTR balance is only READ and snapshotted as the response's weight, no
// ledger entry is ever written.
//
// ANONYMITY MODEL -- do NOT "repair" any of the following, each is on purpose:
//   * poll_participation says WHO answered, poll_response says WHAT was answered. The two share no
//     key. poll_response has NO member_id and NO time column; poll_participation has no time column
//     either. Both carry random UUIDv4 ids (never sequential). A constant time column would carry no
//     information, an omitted one is stricter than the constant voting_opened_at the secret
//     elections use (V1.9.23).
//   * poll_response.weight_ltr is a SNAPSHOT of the responder's free LTR balance at cast time,
//     clamped to >= 0. Only aggregates ever leave the database layer (see PollService.getPollResult).
//
// LAZY EXPIRY -- `poll.status` is the STORED status (OPEN | CLOSED | ABORTED). An OPEN poll whose
// closes_at has passed is CLOSED for every reader; that is never written back (no background job, no
// "system actor" in the audit log). Every status filter therefore uses the EFFECTIVE status, see
// PollLifecycle.
//
// DSGVO -- poll_participation.member_id and poll.created_by/closed_by are personal data;
// network.lapis.cloud.server.dsgvo.PollPersonalData exports/erases them, registered in
// PersonalDataRegistry. poll_option and poll_response carry no member FK (poll_response on purpose,
// see above) and sit in the registry's noPersonalDataAllowlist.
//
// created_by / closed_by / poll_id / option_id are plain «Column» attributes with fkEntity, NO
// association(...) blocks -- same reasoning as 57-carpool.kuml.kts / 45-travel-expense.kuml.kts
// (association-to-FK naming would derive different column names and synthesise a second FK column).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Poll") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val pollStatus =
        enumOf(name = "PollStatus") {
            literal(name = "OPEN")
            literal(name = "CLOSED")
            literal(name = "ABORTED")
        }

    val poll =
        classOf(name = "Poll") {
            stereotype("Entity") { "tableName" to "poll"; "kotlinObjectName" to "PollTable" }
            stereotype("Index") { "columns" to listOf("status", "created_at"); "name" to "idx_poll_status_created" }
            stereotype("Index") { "columns" to listOf("created_by"); "name" to "idx_poll_created_by" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "question", type = "String") {
                stereotype("Column") { "columnName" to "question"; "sqlType" to "VARCHAR(500)" }
            }
            attribute(name = "description", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(2000)" }
            }
            // The STORED status -- see file header "LAZY EXPIRY".
            attribute(name = "status", type = pollStatus) {
                stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.PollStatus" }
            }
            attribute(name = "createdBy", type = "UUID") {
                stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            // Optional deadline.
            attribute(name = "closesAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "closes_at" }
            }
            // Manual close/abort instant (never set by a deadline expiring).
            attribute(name = "closedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "closed_at" }
            }
            attribute(name = "closedBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "closed_by"; "fkEntity" to "Member" }
            }
        }

    val pollOption =
        classOf(name = "PollOption") {
            stereotype("Entity") { "tableName" to "poll_option"; "kotlinObjectName" to "PollOptionTable" }
            stereotype("Index") { "columns" to listOf("poll_id", "position"); "unique" to true; "name" to "uq_poll_option_position" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "pollId", type = "UUID") {
                stereotype("Column") { "columnName" to "poll_id"; "fkEntity" to "Poll" }
            }
            attribute(name = "position", type = "Int") {
                stereotype("Column") { "columnName" to "position" }
            }
            attribute(name = "text", type = "String") {
                stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(200)" }
            }
        }

    // WHO answered -- no time column, see file header.
    val pollParticipation =
        classOf(name = "PollParticipation") {
            stereotype("Entity") { "tableName" to "poll_participation"; "kotlinObjectName" to "PollParticipationTable" }
            stereotype("Index") { "columns" to listOf("poll_id", "member_id"); "unique" to true; "name" to "uq_poll_participation_member" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "pollId", type = "UUID") {
                stereotype("Column") { "columnName" to "poll_id"; "fkEntity" to "Poll" }
            }
            attribute(name = "memberId", type = "UUID") {
                stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
            }
        }

    // WHAT was answered -- deliberately NO member_id and NO time column, see file header. Do not "repair".
    val pollResponse =
        classOf(name = "PollResponse") {
            stereotype("Entity") { "tableName" to "poll_response"; "kotlinObjectName" to "PollResponseTable" }
            stereotype("Index") { "columns" to listOf("poll_id"); "name" to "idx_poll_response_poll" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "pollId", type = "UUID") {
                stereotype("Column") { "columnName" to "poll_id"; "fkEntity" to "Poll" }
            }
            attribute(name = "optionId", type = "UUID") {
                stereotype("Column") { "columnName" to "option_id"; "fkEntity" to "PollOption" }
            }
            // Snapshot of the responder's free LTR balance at cast time, >= 0.
            attribute(name = "weightLtr", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "weight_ltr"; "sqlType" to "DECIMAL(18,2)" }
            }
        }
}

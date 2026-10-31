// Welle V1.9.246 "Aktionen A1" -- see network.lapis.cloud.server.activism.ActivismStore, V133__activism_actions.sql,
// docs/features/activism.adoc and docs/architecture/activism.adoc for the full rationale.
//
// **This file models nine tables.** `activism_action` is the action itself (plan, forms, goal, optional links to a cost centre and an event,
// status, result). Children: `activism_responsible` (1..10 per action), `activism_task` (checklist), `activism_text_block` (copy-and-paste texts),
// `activism_material_document` (link to an EXISTING document, no upload), `activism_contribution` (progress reports of members),
// `activism_signal` ("I would take part"), `activism_report` (a member reports an action to the operator), `activism_notice_text` (per country and
// kind the operator's notice text).
//
// **activism_signal is deliberately minimal**: no time column, no free text, no photo. It is excluded from the backup (OrganizationSchemaCatalog)
// and never audited; only an aggregate (>= 5) is shown. Association tables carry a surrogate `id` plus a UNIQUE pair, because the ERM profile has
// no composite primary key. CHECK constraints, ON DELETE CASCADE / SET NULL and the audit CHECK widening are plain SQL in V133.
// `activism_action.status` / `kind` and the other enum-like columns are Strings here; the hand-edited Exposed objects map them to enums.
// Cross-domain stubs (id-only): Member, CostCenter, Event, Document (owned by their own model files).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Activism") {
    applyProfile(ermMappingProfile)

    classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "CostCenter") {
        stereotype("Entity") { "tableName" to "cost_center"; "kotlinObjectName" to "CostCenterTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "Event") {
        stereotype("Entity") { "tableName" to "event"; "kotlinObjectName" to "EventTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "ActivismAction") {
        stereotype("Entity") { "tableName" to "activism_action"; "kotlinObjectName" to "ActivismActionTable" }

        stereotype("Index") { "columns" to listOf("status", "id"); "name" to "idx_activism_action_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "description", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "goalText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "goal_text"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "location", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "location"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "startsAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "starts_at" }
        }
        attribute(name = "endsAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ends_at" }
        }
        attribute(name = "kind", type = "String") {
            stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "formAppointment", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "form_appointment" }
        }
        attribute(name = "formProgress", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "form_progress" }
        }
        attribute(name = "formSignal", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "form_signal" }
        }
        attribute(name = "goalQuantity", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "goal_quantity" }
        }
        attribute(name = "unit", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "unit"; "sqlType" to "VARCHAR(40)" }
        }
        attribute(name = "confirmContributions", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "confirm_contributions" }
        }
        attribute(name = "ownNotice", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "own_notice"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "costCenterId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cost_center_id"; "fkEntity" to "CostCenter" }
        }
        attribute(name = "eventId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "event_id"; "fkEntity" to "Event" }
        }
        attribute(name = "status", type = "String") {
            defaultValue = "IDEA"
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "withdrawnReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "withdrawn_reason"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "reviewRejectReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "review_reject_reason"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "resultParticipants", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "result_participants" }
        }
        attribute(name = "resultQuantity", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "result_quantity" }
        }
        attribute(name = "resultText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "result_text"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "resultRecordedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "result_recorded_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "approvedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "approved_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "revision", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "revision" }
        }
    }

    classOf(name = "ActivismResponsible") {
        stereotype("Entity") { "tableName" to "activism_responsible"; "kotlinObjectName" to "ActivismResponsibleTable" }

        stereotype("Index") { "columns" to listOf("action_id", "member_id"); "unique" to true; "name" to "uq_activism_responsible_pair" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_activism_responsible_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "actionId", type = "UUID") {
            stereotype("Column") { "columnName" to "action_id"; "fkEntity" to "ActivismAction" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "ActivismTask") {
        stereotype("Entity") { "tableName" to "activism_task"; "kotlinObjectName" to "ActivismTaskTable" }

        stereotype("Index") { "columns" to listOf("action_id", "position"); "unique" to true; "name" to "uq_activism_task_position" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "actionId", type = "UUID") {
            stereotype("Column") { "columnName" to "action_id"; "fkEntity" to "ActivismAction" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
        attribute(name = "text", type = "String") {
            stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "assigneeMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "assignee_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "dueOn", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "due_on" }
        }
        attribute(name = "done", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "done" }
        }
        attribute(name = "doneAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "done_at" }
        }
        attribute(name = "doneBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "done_by"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "ActivismTextBlock") {
        stereotype("Entity") { "tableName" to "activism_text_block"; "kotlinObjectName" to "ActivismTextBlockTable" }

        stereotype("Index") { "columns" to listOf("action_id", "position"); "unique" to true; "name" to "uq_activism_text_block_position" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "actionId", type = "UUID") {
            stereotype("Column") { "columnName" to "action_id"; "fkEntity" to "ActivismAction" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "VARCHAR(4000)" }
        }
    }

    classOf(name = "ActivismMaterialDocument") {
        stereotype("Entity") { "tableName" to "activism_material_document"; "kotlinObjectName" to "ActivismMaterialDocumentTable" }

        stereotype("Index") { "columns" to listOf("action_id", "document_id"); "unique" to true; "name" to "uq_activism_material_pair" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "actionId", type = "UUID") {
            stereotype("Column") { "columnName" to "action_id"; "fkEntity" to "ActivismAction" }
        }
        attribute(name = "documentId", type = "UUID") {
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
    }

    classOf(name = "ActivismContribution") {
        stereotype("Entity") { "tableName" to "activism_contribution"; "kotlinObjectName" to "ActivismContributionTable" }

        stereotype("Index") { "columns" to listOf("action_id", "member_id", "reported_at"); "name" to "idx_activism_contribution_member" }
        stereotype("Index") { "columns" to listOf("action_id", "status", "id"); "name" to "idx_activism_contribution_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "actionId", type = "UUID") {
            stereotype("Column") { "columnName" to "action_id"; "fkEntity" to "ActivismAction" }
        }
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "quantity", type = "Int") {
            stereotype("Column") { "columnName" to "quantity" }
        }
        attribute(name = "area", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "area"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "status", type = "String") {
            defaultValue = "REPORTED"
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "rejectReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reject_reason"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "decidedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_by"; "fkEntity" to "Member" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_at" }
        }
        attribute(name = "reportedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "reported_at" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }

    classOf(name = "ActivismSignal") {
        stereotype("Entity") { "tableName" to "activism_signal"; "kotlinObjectName" to "ActivismSignalTable" }

        stereotype("Index") { "columns" to listOf("action_id", "member_id"); "unique" to true; "name" to "uq_activism_signal_pair" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_activism_signal_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "actionId", type = "UUID") {
            stereotype("Column") { "columnName" to "action_id"; "fkEntity" to "ActivismAction" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "ActivismReport") {
        stereotype("Entity") { "tableName" to "activism_report"; "kotlinObjectName" to "ActivismReportTable" }

        stereotype("Index") { "columns" to listOf("reporter_member_id", "created_at"); "name" to "idx_activism_report_reporter" }
        stereotype("Index") { "columns" to listOf("status", "created_at", "id"); "name" to "idx_activism_report_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "actionId", type = "UUID") {
            stereotype("Column") { "columnName" to "action_id"; "fkEntity" to "ActivismAction" }
        }
        attribute(name = "reporterMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reporter_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "reason", type = "String") {
            stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "status", type = "String") {
            defaultValue = "OPEN"
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "resolvedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_at" }
        }
        attribute(name = "resolvedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_by"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "ActivismNoticeText") {
        stereotype("Entity") { "tableName" to "activism_notice_text"; "kotlinObjectName" to "ActivismNoticeTextTable" }

        stereotype("Index") { "columns" to listOf("country_code", "kind"); "unique" to true; "name" to "uq_activism_notice_text_key" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "countryCode", type = "String") {
            stereotype("Column") { "columnName" to "country_code"; "sqlType" to "VARCHAR(2)" }
        }
        attribute(name = "kind", type = "String") {
            stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }
}

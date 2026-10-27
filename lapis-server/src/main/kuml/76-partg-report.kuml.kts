// Party report domain -- partg_report_position / partg_report_sheet / partg_report_value (V121__partg_report.sql).
//
// Welle V1.9.225 "Rechenschaftsbericht (PartG), R1": the position model of the report of a political party (section 24 PartG), direct entry
// and Excel import per reporting unit and year. R1 stores values; it creates no report and no PDF (that is R2).
//
// DECISIONS THAT SHAPE THE TABLES -- do not "repair" any of them:
//   * partg_report_position is the legally FIXED catalogue (42 rows: 35 LEAF, 7 SUM), seeded by the migration with fixed ids. Only org_hint
//     (the organisation's own hint) is ever updated. `id` exists for the audit log (AuditLogRecorder needs a UUID entity id), `code` is the
//     natural key.
//   * partg_report_sheet is the header per (fiscal_year, unit_key); it carries the revision (optimistic locking) and is the audit entity.
//     unit_key is 'FEDERAL', 'STATE:<chapter uuid>' or 'SUB:<chapter uuid>'.
//   * partg_report_value points to a sheet (sheet_id) and a position CODE. A missing row means "not entered", a row with 0 "entered as zero".
//
// NOT expressible in the ERM profile (SQL only, V121): the foreign keys partg_report_position.parent_code -> partg_report_position.code and
// partg_report_value.position_code -> partg_report_position.code (they target the natural key, not the primary key), the CHECKs
// chk_partg_position_section/kind, chk_partg_sheet_year (2000..2100), chk_partg_sheet_unit_level, chk_partg_sheet_revision (>= 0),
// chk_partg_sheet_unit_consistency (unit_level, regional_chapter_id and unit_key agree), chk_partg_value_amount (0..99999999999999),
// chk_partg_value_source, and the rule "a value may only point to a LEAF position" (the service enforces it; same approach as V59).
//
// DSGVO -- partg_report_sheet.updated_by, partg_report_value.updated_by and partg_report_position.org_hint_updated_by are personal data
// (who changed what); network.lapis.cloud.server.dsgvo.PartgReportPersonalData exports them as "changed by you" without amounts. The rows are
// RETAINED on erasure (retention duties of section 24 and section 19a PartG).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PartgReport") {
    applyProfile(ermMappingProfile)

    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val regionalChapter =
        classOf(name = "RegionalChapter") {
            stereotype("Entity") { "tableName" to "regional_chapter"; "kotlinObjectName" to "RegionalChapterTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    // Literal order is load-bearing: the drift test pins the values in exactly this order (= network.lapis.cloud.shared.domain.*).
    val partgSection =
        enumOf(name = "PartgSection") {
            literal(name = "INCOME")
            literal(name = "EXPENSE")
            literal(name = "ASSET")
            literal(name = "LIABILITY")
        }
    val partgKind =
        enumOf(name = "PartgKind") {
            literal(name = "LEAF")
            literal(name = "SUM")
        }
    val partgUnitLevel =
        enumOf(name = "PartgUnitLevel") {
            literal(name = "FEDERAL")
            literal(name = "STATE")
            literal(name = "SUBORDINATE")
        }
    val partgValueSource =
        enumOf(name = "PartgValueSource") {
            literal(name = "MANUAL")
            literal(name = "IMPORT")
        }

    classOf(name = "PartgReportPosition") {
        stereotype("Entity") { "tableName" to "partg_report_position"; "kotlinObjectName" to "PartgReportPositionTable" }
        stereotype("Index") { "columns" to listOf("code"); "unique" to true; "name" to "uq_partg_report_position_code" }
        stereotype("Index") { "columns" to listOf("sort_order"); "unique" to true; "name" to "uq_partg_report_position_sort_order" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "code", type = "String") {
            stereotype("Column") { "columnName" to "code"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "section", type = partgSection) {
            stereotype("Column") {
                "columnName" to "section"
                "sqlType" to "VARCHAR(9)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgSection"
            }
        }
        attribute(name = "kind", type = partgKind) {
            stereotype("Column") {
                "columnName" to "kind"
                "sqlType" to "VARCHAR(4)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgKind"
            }
        }
        // No foreign key in the model: it targets the natural key `code`, see the file header.
        attribute(name = "parentCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "parent_code"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "sortOrder", type = "Int") {
            stereotype("Column") { "columnName" to "sort_order" }
        }
        attribute(name = "numberLabel", type = "String") {
            stereotype("Column") { "columnName" to "number_label"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "labelDe", type = "String") {
            stereotype("Column") { "columnName" to "label_de"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "legalRef", type = "String") {
            stereotype("Column") { "columnName" to "legal_ref"; "sqlType" to "VARCHAR(80)" }
        }
        attribute(name = "orgHint", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "org_hint"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "orgHintUpdatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "org_hint_updated_at" }
        }
        attribute(name = "orgHintUpdatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "org_hint_updated_by"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "PartgReportSheet") {
        stereotype("Entity") { "tableName" to "partg_report_sheet"; "kotlinObjectName" to "PartgReportSheetTable" }
        stereotype("Index") { "columns" to listOf("fiscal_year", "unit_key"); "unique" to true; "name" to "uq_partg_report_sheet_year_unit" }
        stereotype("Index") { "columns" to listOf("regional_chapter_id"); "name" to "ix_partg_report_sheet_regional_chapter" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_sheet_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "fiscalYear", type = "Int") {
            stereotype("Column") { "columnName" to "fiscal_year" }
        }
        attribute(name = "unitLevel", type = partgUnitLevel) {
            stereotype("Column") {
                "columnName" to "unit_level"
                "sqlType" to "VARCHAR(11)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgUnitLevel"
            }
        }
        attribute(name = "regionalChapterId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "regional_chapter_id"; "fkEntity" to "RegionalChapter" }
        }
        attribute(name = "unitKey", type = "String") {
            stereotype("Column") { "columnName" to "unit_key"; "sqlType" to "VARCHAR(48)" }
        }
        attribute(name = "revision", type = "Long") {
            stereotype("Column") { "columnName" to "revision" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "PartgReportValue") {
        stereotype("Entity") { "tableName" to "partg_report_value"; "kotlinObjectName" to "PartgReportValueTable" }
        stereotype("Index") { "columns" to listOf("sheet_id", "position_code"); "unique" to true; "name" to "uq_partg_report_value_sheet_position" }
        stereotype("Index") { "columns" to listOf("sheet_id"); "name" to "ix_partg_report_value_sheet" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "sheetId", type = "UUID") {
            stereotype("Column") { "columnName" to "sheet_id"; "fkEntity" to "PartgReportSheet" }
        }
        // No foreign key in the model: it targets the natural key `code`, see the file header.
        attribute(name = "positionCode", type = "String") {
            stereotype("Column") { "columnName" to "position_code"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "amountCents", type = "Long") {
            stereotype("Column") { "columnName" to "amount_cents" }
        }
        attribute(name = "valueSource", type = partgValueSource) {
            stereotype("Column") {
                "columnName" to "value_source"
                "sqlType" to "VARCHAR(6)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgValueSource"
            }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }
}

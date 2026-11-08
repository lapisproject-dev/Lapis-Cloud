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
// Welle V1.9.269 "Rechenschaftsbericht R3" (V139__partg_report_r3.sql) adds FIVE tables: partg_report_annex (deductions A + member count E per
// year and unit, audit entity), partg_report_large_donor (statement B), partg_report_notes_sheet (notes per year, audit entity),
// partg_report_note (five fixed blocks) and partg_report_holding (structured holdings). NOT expressible in the ERM profile (SQL only, V139): every
// CHECK (year, unit consistency, revision, ranges, enum literals, amount > 1,000,000 cents of a large donor, relief fields only in RELIEF_28), and
// the row limits (500 donors, 50 holdings; checked by the service under the lock of the header row).
// Welle V1.9.283 "Rechenschaftsbericht R3b" (V142__partg_report_r3b.sql) adds THREE tables: partg_report_sponsor + partg_report_sponsoring_entry
// (statement C) and partg_report_youth_org (statement F). NOT expressible in the ERM profile (SQL only, V142): every CHECK and the row limits
// (500 sponsors, 100 entries per sponsor, 2000 entries per annex, 20 youth organisations; checked by the service under the lock of the annex).
// Welle V1.9.287 "Rechenschaftsbericht R3c" (V143__partg_report_r3c.sql) adds ONE table: partg_report_enterprise (statement D, own business
// operations per annex). NOT expressible in the ERM profile (SQL only, V143): every CHECK and the row limit (50 per annex; checked by the service
// under the lock of the annex). DSGVO (R3c): organisation data; created_by/updated_by are "changed by you" data.
// Welle V1.9.289 "Rechenschaftsbericht R4a" (V144__partg_report_r4.sql) adds TWO tables: partg_account_mapping (ledger account -> report
// position, n:1, or "deliberately not in the report"; a missing row = not mapped) and partg_report_unit_mode (bookkeeping mode A/B/C per unit
// and year; a missing row = C; sub-units are never stored). value_source gains LEDGER (a value proposed from the bookings). NOT expressible in
// the ERM profile (SQL only, V144): the foreign key position_code -> partg_report_position.code (natural key), every CHECK, and the rule that
// the target is a LEAF of the matching section (shared PartgLedgerRules, enforced by the service). DSGVO: organisation data; updated_by is
// "changed by you" data.
// Welle V1.9.290 "Rechenschaftsbericht R5" (V145__partg_report_r5.sql) adds FOUR tables: partg_consolidation_sheet (header per year: lock and
// revision fence), partg_intra_transfer (one pair per year, kind, payer unit and payee unit: the matching table for the elimination of
// transfers between units), partg_donor_combination and partg_donor_combination_row (a manual group of large-donor rows of DIFFERENT units that
// are one person; ids only, never a name). NOT expressible in the ERM profile (SQL only, V145): every CHECK, the unit consistency of both sides,
// and the rules checked by the service (same year as the header, rows of one group from different units, 2..20 rows per group, at most 1000
// pairs and 500 groups per year). DSGVO: organisation data; created_by/updated_by are "changed by you" data; the free-text note of a pair
// may hold personal data and is never logged or audited.
// DSGVO (R3b): sponsor and sponsoring entry are PERSONAL DATA OF THIRD PARTIES (listed in knownUncoveredSubjectRoots); youth_org is organisation data.
// DSGVO (R3): partg_report_large_donor is PERSONAL DATA OF THIRD PARTIES (name, address, yearly sum above 10,000 EUR); no member foreign key,
// listed in PersonalDataRegistry.knownUncoveredSubjectRoots; created_by/updated_by of all R3 tables are "changed by you" data.
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
            literal(name = "LEDGER")
        }

    // V1.9.289 (R4a). Literal order is load-bearing (= network.lapis.cloud.shared.domain.PartgBookkeepingMode).
    val partgBookkeepingMode =
        enumOf(name = "PartgBookkeepingMode") {
            literal(name = "A")
            literal(name = "B")
            literal(name = "C")
        }

    // V1.9.290 (R5). Literal order is load-bearing (= network.lapis.cloud.shared.domain.PartgTransferKind).
    val partgTransferKind =
        enumOf(name = "PartgTransferKind") {
            literal(name = "SUBSIDY")
            literal(name = "BALANCE")
        }

    // V1.9.269 (R3). Literal order is load-bearing (= network.lapis.cloud.shared.domain.*).
    val partgPersonKind =
        enumOf(name = "PartgPersonKind") {
            literal(name = "NATURAL")
            literal(name = "LEGAL")
        }
    val partgContributionKind =
        enumOf(name = "PartgContributionKind") {
            literal(name = "DONATION")
            literal(name = "FEE")
            literal(name = "BOTH")
        }
    val partgNoteBlock =
        enumOf(name = "PartgNoteBlock") {
            literal(name = "GENERAL_ACCOUNTING")
            literal(name = "RELIEF_28")
            literal(name = "HOLDINGS")
            literal(name = "MEDIA_PRODUCTS")
            literal(name = "REAL_ESTATE_VALUATION")
        }
    val partgTriState =
        enumOf(name = "PartgTriState") {
            literal(name = "YES")
            literal(name = "NO")
            literal(name = "UNSPECIFIED")
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

    // V1.9.269 (R3)
    classOf(name = "PartgReportAnnex") {
        stereotype("Entity") { "tableName" to "partg_report_annex"; "kotlinObjectName" to "PartgReportAnnexTable" }
        stereotype("Index") { "columns" to listOf("fiscal_year", "unit_key"); "unique" to true; "name" to "uq_partg_report_annex_year_unit" }
        stereotype("Index") { "columns" to listOf("regional_chapter_id"); "name" to "ix_partg_report_annex_regional_chapter" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_annex_updated_by" }

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
        attribute(name = "deductionUnassignableCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deduction_unassignable_cents" }
        }
        attribute(name = "deductionCashOver1000Cents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deduction_cash_over_1000_cents" }
        }
        attribute(name = "deductionOver3300Cents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deduction_over_3300_cents" }
        }
        attribute(name = "deductionWronglyReportedCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deduction_wrongly_reported_cents" }
        }
        attribute(name = "memberCount", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_count" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.269 (R3)
    classOf(name = "PartgReportLargeDonor") {
        stereotype("Entity") { "tableName" to "partg_report_large_donor"; "kotlinObjectName" to "PartgReportLargeDonorTable" }
        stereotype("Index") { "columns" to listOf("annex_id"); "name" to "ix_partg_report_large_donor_annex" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "ix_partg_report_large_donor_created_by" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_large_donor_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "annexId", type = "UUID") {
            stereotype("Column") { "columnName" to "annex_id"; "fkEntity" to "PartgReportAnnex" }
        }
        attribute(name = "personKind", type = partgPersonKind) {
            stereotype("Column") {
                "columnName" to "person_kind"
                "sqlType" to "VARCHAR(7)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgPersonKind"
            }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "street", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "street"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "postalCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "postal_code"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "city", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "city"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "country", type = "String") {
            stereotype("Column") { "columnName" to "country"; "sqlType" to "VARCHAR(2)" }
        }
        attribute(name = "contributionKind", type = partgContributionKind) {
            stereotype("Column") {
                "columnName" to "contribution_kind"
                "sqlType" to "VARCHAR(8)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgContributionKind"
            }
        }
        attribute(name = "amountCents", type = "Long") {
            stereotype("Column") { "columnName" to "amount_cents" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.269 (R3)
    classOf(name = "PartgReportNotesSheet") {
        stereotype("Entity") { "tableName" to "partg_report_notes_sheet"; "kotlinObjectName" to "PartgReportNotesSheetTable" }
        stereotype("Index") { "columns" to listOf("fiscal_year"); "unique" to true; "name" to "uq_partg_report_notes_sheet_year" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_notes_sheet_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "fiscalYear", type = "Int") {
            stereotype("Column") { "columnName" to "fiscal_year" }
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

    // V1.9.269 (R3)
    classOf(name = "PartgReportNote") {
        stereotype("Entity") { "tableName" to "partg_report_note"; "kotlinObjectName" to "PartgReportNoteTable" }
        stereotype("Index") { "columns" to listOf("notes_sheet_id", "block_code"); "unique" to true; "name" to "uq_partg_report_note_sheet_block" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_note_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "notesSheetId", type = "UUID") {
            stereotype("Column") { "columnName" to "notes_sheet_id"; "fkEntity" to "PartgReportNotesSheet" }
        }
        attribute(name = "blockCode", type = partgNoteBlock) {
            stereotype("Column") {
                "columnName" to "block_code"
                "sqlType" to "VARCHAR(21)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgNoteBlock"
            }
        }
        attribute(name = "included", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "included" }
        }
        attribute(name = "body", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "body"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "reliefAbs1", type = partgTriState) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") {
                "columnName" to "relief_abs1"
                "sqlType" to "VARCHAR(11)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgTriState"
            }
        }
        attribute(name = "reliefAbs3", type = partgTriState) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") {
                "columnName" to "relief_abs3"
                "sqlType" to "VARCHAR(11)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgTriState"
            }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.269 (R3)
    classOf(name = "PartgReportHolding") {
        stereotype("Entity") { "tableName" to "partg_report_holding"; "kotlinObjectName" to "PartgReportHoldingTable" }
        stereotype("Index") { "columns" to listOf("notes_sheet_id", "sort_order"); "unique" to true; "name" to "uq_partg_report_holding_sheet_order" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_holding_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "notesSheetId", type = "UUID") {
            stereotype("Column") { "columnName" to "notes_sheet_id"; "fkEntity" to "PartgReportNotesSheet" }
        }
        attribute(name = "sortOrder", type = "Int") {
            stereotype("Column") { "columnName" to "sort_order" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "seat", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "seat"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "shareBasisPoints", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "share_basis_points" }
        }
        attribute(name = "capitalCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "capital_cents" }
        }
        attribute(name = "resultCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "result_cents" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.283 (R3b)
    classOf(name = "PartgReportSponsor") {
        stereotype("Entity") { "tableName" to "partg_report_sponsor"; "kotlinObjectName" to "PartgReportSponsorTable" }
        stereotype("Index") { "columns" to listOf("annex_id"); "name" to "ix_partg_report_sponsor_annex" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "ix_partg_report_sponsor_created_by" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_sponsor_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "annexId", type = "UUID") {
            stereotype("Column") { "columnName" to "annex_id"; "fkEntity" to "PartgReportAnnex" }
        }
        attribute(name = "personKind", type = partgPersonKind) {
            stereotype("Column") {
                "columnName" to "person_kind"
                "sqlType" to "VARCHAR(7)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgPersonKind"
            }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "street", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "street"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "postalCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "postal_code"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "city", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "city"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "country", type = "String") {
            stereotype("Column") { "columnName" to "country"; "sqlType" to "VARCHAR(2)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.283 (R3b)
    classOf(name = "PartgReportSponsoringEntry") {
        stereotype("Entity") { "tableName" to "partg_report_sponsoring_entry"; "kotlinObjectName" to "PartgReportSponsoringEntryTable" }
        stereotype("Index") { "columns" to listOf("sponsor_id"); "name" to "ix_partg_report_sponsoring_entry_sponsor" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "ix_partg_report_sponsoring_entry_created_by" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_sponsoring_entry_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "sponsorId", type = "UUID") {
            stereotype("Column") { "columnName" to "sponsor_id"; "fkEntity" to "PartgReportSponsor" }
        }
        attribute(name = "sponsoredOn", type = "LocalDate") {
            stereotype("Column") { "columnName" to "sponsored_on" }
        }
        attribute(name = "amountCents", type = "Long") {
            stereotype("Column") { "columnName" to "amount_cents" }
        }
        attribute(name = "purpose", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "purpose"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.283 (R3b)
    classOf(name = "PartgReportYouthOrg") {
        stereotype("Entity") { "tableName" to "partg_report_youth_org"; "kotlinObjectName" to "PartgReportYouthOrgTable" }
        stereotype("Index") { "columns" to listOf("annex_id"); "name" to "ix_partg_report_youth_org_annex" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "ix_partg_report_youth_org_created_by" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_youth_org_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "annexId", type = "UUID") {
            stereotype("Column") { "columnName" to "annex_id"; "fkEntity" to "PartgReportAnnex" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "relationship", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "relationship"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "incomeCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "income_cents" }
        }
        attribute(name = "expensesCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "expenses_cents" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.287 (R3c)
    classOf(name = "PartgReportEnterprise") {
        stereotype("Entity") { "tableName" to "partg_report_enterprise"; "kotlinObjectName" to "PartgReportEnterpriseTable" }
        stereotype("Index") { "columns" to listOf("annex_id"); "name" to "ix_partg_report_enterprise_annex" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "ix_partg_report_enterprise_created_by" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_enterprise_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "annexId", type = "UUID") {
            stereotype("Column") { "columnName" to "annex_id"; "fkEntity" to "PartgReportAnnex" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "legalForm", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "legal_form"; "sqlType" to "VARCHAR(60)" }
        }
        attribute(name = "seat", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "seat"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "shareBasisPoints", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "share_basis_points" }
        }
        attribute(name = "incomeCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "income_cents" }
        }
        attribute(name = "expensesCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "expenses_cents" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.289 (R4a)
    val ledgerAccountStub =
        classOf(name = "LedgerAccount") {
            stereotype("Entity") { "tableName" to "ledger_account"; "kotlinObjectName" to "LedgerAccountTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    classOf(name = "PartgAccountMapping") {
        stereotype("Entity") { "tableName" to "partg_account_mapping"; "kotlinObjectName" to "PartgAccountMappingTable" }
        stereotype("Index") { "columns" to listOf("ledger_account_id"); "unique" to true; "name" to "uq_partg_account_mapping_account" }
        stereotype("Index") { "columns" to listOf("position_code"); "name" to "ix_partg_account_mapping_position" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_account_mapping_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "ledgerAccountId", type = "UUID") {
            stereotype("Column") { "columnName" to "ledger_account_id"; "fkEntity" to "LedgerAccount" }
        }
        // FK to partg_report_position.code (natural key, SQL only). NULL iff `excluded`.
        attribute(name = "positionCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "position_code"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "excluded", type = "Boolean") {
            stereotype("Column") { "columnName" to "excluded" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "PartgReportUnitMode") {
        stereotype("Entity") { "tableName" to "partg_report_unit_mode"; "kotlinObjectName" to "PartgReportUnitModeTable" }
        stereotype("Index") { "columns" to listOf("fiscal_year", "unit_key"); "unique" to true; "name" to "uq_partg_report_unit_mode_year_unit" }
        stereotype("Index") { "columns" to listOf("regional_chapter_id"); "name" to "ix_partg_report_unit_mode_chapter" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_report_unit_mode_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "fiscalYear", type = "Int") {
            stereotype("Column") { "columnName" to "fiscal_year" }
        }
        attribute(name = "unitKey", type = "String") {
            stereotype("Column") { "columnName" to "unit_key"; "sqlType" to "VARCHAR(48)" }
        }
        attribute(name = "regionalChapterId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "regional_chapter_id"; "fkEntity" to "RegionalChapter" }
        }
        attribute(name = "bookkeepingMode", type = partgBookkeepingMode) {
            stereotype("Column") {
                "columnName" to "bookkeeping_mode"
                "sqlType" to "VARCHAR(1)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgBookkeepingMode"
            }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // V1.9.290 (R5)
    classOf(name = "PartgConsolidationSheet") {
        stereotype("Entity") { "tableName" to "partg_consolidation_sheet"; "kotlinObjectName" to "PartgConsolidationSheetTable" }
        stereotype("Index") { "columns" to listOf("fiscal_year"); "unique" to true; "name" to "uq_partg_consolidation_sheet_year" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_consolidation_sheet_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "fiscalYear", type = "Int") {
            stereotype("Column") { "columnName" to "fiscal_year" }
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

    classOf(name = "PartgIntraTransfer") {
        stereotype("Entity") { "tableName" to "partg_intra_transfer"; "kotlinObjectName" to "PartgIntraTransferTable" }
        stereotype("Index") {
            "columns" to listOf("consolidation_id", "transfer_kind", "payer_unit_key", "payee_unit_key")
            "unique" to true
            "name" to "uq_partg_intra_transfer_pair"
        }
        stereotype("Index") { "columns" to listOf("consolidation_id"); "name" to "ix_partg_intra_transfer_consolidation" }
        stereotype("Index") { "columns" to listOf("payer_chapter_id"); "name" to "ix_partg_intra_transfer_payer_chapter" }
        stereotype("Index") { "columns" to listOf("payee_chapter_id"); "name" to "ix_partg_intra_transfer_payee_chapter" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "ix_partg_intra_transfer_created_by" }
        stereotype("Index") { "columns" to listOf("updated_by"); "name" to "ix_partg_intra_transfer_updated_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "consolidationId", type = "UUID") {
            stereotype("Column") { "columnName" to "consolidation_id"; "fkEntity" to "PartgConsolidationSheet" }
        }
        attribute(name = "transferKind", type = partgTransferKind) {
            stereotype("Column") {
                "columnName" to "transfer_kind"
                "sqlType" to "VARCHAR(7)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgTransferKind"
            }
        }
        attribute(name = "payerLevel", type = partgUnitLevel) {
            stereotype("Column") {
                "columnName" to "payer_level"
                "sqlType" to "VARCHAR(11)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgUnitLevel"
            }
        }
        attribute(name = "payerChapterId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payer_chapter_id"; "fkEntity" to "RegionalChapter" }
        }
        attribute(name = "payerUnitKey", type = "String") {
            stereotype("Column") { "columnName" to "payer_unit_key"; "sqlType" to "VARCHAR(48)" }
        }
        attribute(name = "payeeLevel", type = partgUnitLevel) {
            stereotype("Column") {
                "columnName" to "payee_level"
                "sqlType" to "VARCHAR(11)"
                "enumType" to "network.lapis.cloud.shared.domain.PartgUnitLevel"
            }
        }
        attribute(name = "payeeChapterId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payee_chapter_id"; "fkEntity" to "RegionalChapter" }
        }
        attribute(name = "payeeUnitKey", type = "String") {
            stereotype("Column") { "columnName" to "payee_unit_key"; "sqlType" to "VARCHAR(48)" }
        }
        attribute(name = "payerAmountCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payer_amount_cents" }
        }
        attribute(name = "payeeAmountCents", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payee_amount_cents" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "PartgDonorCombination") {
        stereotype("Entity") { "tableName" to "partg_donor_combination"; "kotlinObjectName" to "PartgDonorCombinationTable" }
        stereotype("Index") { "columns" to listOf("consolidation_id"); "name" to "ix_partg_donor_combination_consolidation" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "ix_partg_donor_combination_created_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "consolidationId", type = "UUID") {
            stereotype("Column") { "columnName" to "consolidation_id"; "fkEntity" to "PartgConsolidationSheet" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "PartgDonorCombinationRow") {
        stereotype("Entity") { "tableName" to "partg_donor_combination_row"; "kotlinObjectName" to "PartgDonorCombinationRowTable" }
        stereotype("Index") { "columns" to listOf("combination_id"); "name" to "ix_partg_donor_combination_row_combination" }
        stereotype("Index") { "columns" to listOf("large_donor_id"); "unique" to true; "name" to "uq_partg_donor_combination_row_donor" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "combinationId", type = "UUID") {
            stereotype("Column") { "columnName" to "combination_id"; "fkEntity" to "PartgDonorCombination" }
        }
        attribute(name = "largeDonorId", type = "UUID") {
            stereotype("Column") { "columnName" to "large_donor_id"; "fkEntity" to "PartgReportLargeDonor" }
        }
    }
}

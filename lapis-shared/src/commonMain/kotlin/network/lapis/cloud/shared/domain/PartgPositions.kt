package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.225 "Rechenschaftsbericht (PartG), R1" -- the four parts of the report of a political party (section 24 PartG).
 * Literal order is load-bearing: it is the stored order of `partg_report_position.section` (a CHECK, not an enum column).
 */
@Serializable
enum class PartgSection { INCOME, EXPENSE, ASSET, LIABILITY }

/** A position is either typed in ([LEAF]) or computed from the others ([SUM]). Only [LEAF] positions ever have a stored value. */
@Serializable
enum class PartgKind { LEAF, SUM }

/** The level of a reporting unit. `STATE` and `SUBORDINATE` belong to one regional chapter (Landesverband). */
@Serializable
enum class PartgUnitLevel { FEDERAL, STATE, SUBORDINATE }

/** Where a stored value comes from: typed in by a person, or taken over from an Excel file. */
@Serializable
enum class PartgValueSource { MANUAL, IMPORT }

/**
 * The fixed catalogue of the positions of the report (sections 24 (4) income, (5) expenses, (6) assets and liabilities PartG), in the order
 * of the official layout. The server seeds exactly this list (migration `V121__partg_report.sql`, pinned by `PartgPositionsSeedTest`); the
 * client shows the German labels through `partgLabel` (literal `tr(...)` calls, so the catalogue extraction finds them).
 *
 * 35 positions are [PartgKind.LEAF] (typed in or imported), 7 are [PartgKind.SUM] (always computed by [sums], never stored). The result
 * [RES] and the net assets [NET] may be negative; every typed-in amount is non-negative.
 */
object PartgPositions {
    const val EXPENSE_TOTAL = "A04"
    const val INCOME_TOTAL = "E11"
    const val RESULT = "RES"
    const val ASSET_TOTAL = "BP-SUM"
    const val LIABILITY_TOTAL = "SP-SUM"
    const val NET = "NET"
    const val EXPENSE_MATERIAL = "A02"

    /** Number of typed-in positions. */
    const val LEAF_COUNT = 35
    val leafCount: Int get() = LEAF_COUNT

    data class Position(
        val id: String,
        val code: String,
        val section: PartgSection,
        val kind: PartgKind,
        val parent: String?,
        val sortOrder: Int,
        val numberLabel: String,
        val labelDe: String,
        val legalRef: String,
    )

    private fun position(
        code: String,
        section: PartgSection,
        kind: PartgKind,
        parent: String?,
        number: String,
        label: String,
        legalRef: String,
        order: Int,
    ) = Position(
        id = "7a6e0000-0000-4000-8000-0000000000" + order.toString().padStart(2, '0'),
        code = code,
        section = section,
        kind = kind,
        parent = parent,
        sortOrder = order,
        numberLabel = number,
        labelDe = label,
        legalRef = legalRef,
    )

    private val definitions: List<Triple<String, String, String>> =
        listOf(
            Triple("E01", "Mitgliedsbeiträge und ähnliche regelmäßige Beiträge", "1"),
            Triple("E02", "Mandatsträgerbeiträge und ähnliche regelmäßige Beiträge", "2"),
            Triple("E03", "Spenden von natürlichen Personen", "3"),
            Triple("E04", "Spenden von juristischen Personen", "4"),
            Triple("E05", "Einnahmen aus Unternehmenstätigkeit", "5"),
            Triple("E05A", "Einnahmen aus Beteiligungen", "5a"),
            Triple("E06", "Einnahmen aus sonstigem Vermögen", "6"),
            Triple(
                "E07",
                "Einnahmen aus Veranstaltungen, Vertrieb von Druckschriften und Veröffentlichungen und sonstiger mit Einnahmen " +
                    "verbundener Tätigkeit",
                "7",
            ),
            Triple("E08", "Staatliche Mittel", "8"),
            Triple("E09", "Sonstige Einnahmen", "9"),
            Triple("E10", "Zuschüsse von Gliederungen", "10"),
        )

    /** All 42 positions in the order of the official layout (= `sort_order`). */
    val ALL: List<Position> =
        buildList {
            var order = 0

            fun row(
                code: String,
                section: PartgSection,
                kind: PartgKind,
                parent: String?,
                number: String,
                label: String,
                ref: String,
            ) {
                order += 1
                add(
                    position(
                        code = code,
                        section = section,
                        kind = kind,
                        parent = parent,
                        number = number,
                        label = label,
                        legalRef = ref,
                        order = order,
                    ),
                )
            }
            definitions.forEach { (code, label, number) ->
                row(code, PartgSection.INCOME, PartgKind.LEAF, INCOME_TOTAL, number, label, "§ 24 Abs. 4 PartG")
            }
            row(INCOME_TOTAL, PartgSection.INCOME, PartgKind.SUM, null, "11", "Gesamteinnahmen", "§ 24 Abs. 4 PartG")
            row("A01", PartgSection.EXPENSE, PartgKind.LEAF, EXPENSE_TOTAL, "1", "Ausgaben für Personal", "§ 24 Abs. 5 PartG")
            val material =
                listOf(
                    "A02A" to "Ausgaben des laufenden Geschäftsbetriebs",
                    "A02B" to "Ausgaben für allgemeine politische Arbeit",
                    "A02C" to "Ausgaben für Wahlkämpfe",
                    "A02D" to "Ausgaben für die Vermögensverwaltung einschließlich sich ergebender Zinsen",
                    "A02E" to "Sonstige Zinsen",
                    "A02F" to "Ausgaben aus Unternehmenstätigkeit",
                    "A02G" to "Sonstige Ausgaben",
                )
            material.forEachIndexed { index, (code, label) ->
                row(code, PartgSection.EXPENSE, PartgKind.LEAF, EXPENSE_MATERIAL, "2 " + ('a' + index) + ")", label, "§ 24 Abs. 5 PartG")
            }
            row(
                EXPENSE_MATERIAL,
                PartgSection.EXPENSE,
                PartgKind.SUM,
                EXPENSE_TOTAL,
                "2",
                "Ausgaben für Sachaufwand insgesamt",
                "§ 24 Abs. 5 PartG",
            )
            row("A03", PartgSection.EXPENSE, PartgKind.LEAF, EXPENSE_TOTAL, "3", "Zuschüsse an Gliederungen", "§ 24 Abs. 5 PartG")
            row(EXPENSE_TOTAL, PartgSection.EXPENSE, PartgKind.SUM, null, "4", "Gesamtausgaben", "§ 24 Abs. 5 PartG")
            row(RESULT, PartgSection.EXPENSE, PartgKind.SUM, null, "", "Überschuss bzw. Defizit", "§ 24 Abs. 5 PartG")
            val assets =
                listOf(
                    "BP01" to "Haus- und Grundvermögen",
                    "BP02" to "Geschäftsstellenausstattung",
                    "BP03" to "Beteiligungen an Unternehmen",
                    "BP04" to "Sonstige Finanzanlagen",
                    "BP05" to "Forderungen an Gliederungen",
                    "BP06" to "Forderungen aus der staatlichen Teilfinanzierung",
                    "BP07" to "Geldbestände",
                    "BP08" to "Sonstige Vermögensgegenstände",
                )
            assets.forEachIndexed { index, (code, label) ->
                row(code, PartgSection.ASSET, PartgKind.LEAF, ASSET_TOTAL, "A" + (index + 1), label, "§ 24 Abs. 6 PartG")
            }
            row(ASSET_TOTAL, PartgSection.ASSET, PartgKind.SUM, null, "A", "Summe der Besitzposten", "§ 24 Abs. 6 PartG")
            val liabilities =
                listOf(
                    "SP01" to "Rückstellungen für Pensionsverpflichtungen",
                    "SP02" to "Sonstige Rückstellungen",
                    "SP03" to "Rückzahlungsverpflichtungen aus der staatlichen Teilfinanzierung",
                    "SP04" to "Verbindlichkeiten gegenüber Gebietsverbänden",
                    "SP05" to "Verbindlichkeiten gegenüber Kreditinstituten",
                    "SP06" to "Verbindlichkeiten gegenüber sonstigen Darlehensgebern",
                    "SP07" to "Sonstige Verbindlichkeiten",
                )
            liabilities.forEachIndexed { index, (code, label) ->
                row(code, PartgSection.LIABILITY, PartgKind.LEAF, LIABILITY_TOTAL, "S" + (index + 1), label, "§ 24 Abs. 6 PartG")
            }
            row(LIABILITY_TOTAL, PartgSection.LIABILITY, PartgKind.SUM, null, "S", "Summe der Schuldposten", "§ 24 Abs. 6 PartG")
            row(
                NET,
                PartgSection.LIABILITY,
                PartgKind.SUM,
                null,
                "",
                "Reinvermögen (Besitzposten abzüglich Schuldposten)",
                "§ 24 Abs. 6 PartG",
            )
        }

    val LEAF_CODES: Set<String> = ALL.filter { it.kind == PartgKind.LEAF }.map { it.code }.toSet()

    private val byCode: Map<String, Position> = ALL.associateBy { it.code }

    fun find(code: String): Position? = byCode[code]

    /** The typed-in positions of a section, in layout order. */
    fun leaves(section: PartgSection): List<Position> = ALL.filter { it.section == section && it.kind == PartgKind.LEAF }

    /**
     * The seven computed positions for the given typed-in [values] (cents per leaf code). A leaf without a value counts as 0 here; whether a
     * position was entered at all is tracked separately by the caller ("not entered" is not the same as 0). Values of non-leaf codes are
     * ignored. [RES] and [NET] can be negative.
     */
    fun sums(values: Map<String, Long>): Map<String, Long> {
        fun leafSum(codes: List<String>) = codes.sumOf { values[it] ?: 0L }

        fun codesOf(section: PartgSection) = leaves(section).map { it.code }
        val income = leafSum(codesOf(PartgSection.INCOME))
        val material = leafSum(ALL.filter { it.parent == EXPENSE_MATERIAL }.map { it.code })
        val expenses = (values["A01"] ?: 0L) + material + (values["A03"] ?: 0L)
        val assets = leafSum(codesOf(PartgSection.ASSET))
        val liabilities = leafSum(codesOf(PartgSection.LIABILITY))
        return linkedMapOf(
            INCOME_TOTAL to income,
            EXPENSE_MATERIAL to material,
            EXPENSE_TOTAL to expenses,
            RESULT to income - expenses,
            ASSET_TOTAL to assets,
            LIABILITY_TOTAL to liabilities,
            NET to assets - liabilities,
        )
    }
}

package network.lapis.cloud.server.partg.report

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgStatementStore
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgTriState
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.uuid.Uuid

/** A small, fixed party: the federal level and two regional chapters (state unit + subordinate summary each), partly entered. */
internal object ReportFixtures {
    const val YEAR = 2025
    const val CHAPTER_A = "11111111-1111-4111-8111-111111111111"
    const val CHAPTER_B = "22222222-2222-4222-8222-222222222222"

    fun unitDto(
        level: PartgUnitLevel,
        chapter: String? = null,
        name: String? = null,
    ): PartgUnitDto {
        val unit: PartgUnit =
            when (level) {
                PartgUnitLevel.FEDERAL -> PartgUnit.Federal
                PartgUnitLevel.STATE -> PartgUnit.State(checkNotNull(chapter))
                PartgUnitLevel.SUBORDINATE -> PartgUnit.Subordinate(checkNotNull(chapter))
            }
        return PartgUnitDto(unitKey = unit.key(), level = level, chapterId = chapter, chapterName = name)
    }

    fun units(): List<PartgUnitDto> =
        listOf(
            unitDto(level = PartgUnitLevel.FEDERAL),
            unitDto(level = PartgUnitLevel.STATE, chapter = CHAPTER_A, name = "Alpha"),
            unitDto(level = PartgUnitLevel.SUBORDINATE, chapter = CHAPTER_A, name = "Alpha"),
            unitDto(level = PartgUnitLevel.STATE, chapter = CHAPTER_B, name = "Beta"),
            unitDto(level = PartgUnitLevel.SUBORDINATE, chapter = CHAPTER_B, name = "Beta"),
        )

    /** Distinct non-zero amounts, so a stray "0,00" can only come from a wrong rendering of "not entered". */
    fun leafValues(
        skip: Set<String> = emptySet(),
        factor: Long = 1,
    ): Map<String, Long> =
        PartgPositions.ALL
            .filter { it.kind == network.lapis.cloud.shared.domain.PartgKind.LEAF && it.code !in skip }
            .withIndex()
            .associate { (i, p) -> p.code to (i + 1) * 12_345L * factor + 7L }

    fun entered(): Map<Int, Map<String, Map<String, Long>>> {
        val federal = leafValues(skip = setOf("E05A", "A03")) + ("A01" to 9_999_900L)
        return mapOf(
            YEAR to
                mapOf(
                    PartgUnit.FEDERAL_KEY to federal,
                    PartgUnit.State(CHAPTER_A).key() to leafValues(factor = 2),
                    PartgUnit.State(CHAPTER_B).key() to mapOf("E01" to 1_234_567L),
                ),
            YEAR - 1 to mapOf(PartgUnit.FEDERAL_KEY to mapOf("E01" to 55_555L)),
        )
    }

    fun doc(
        scope: PartgReportScope = PartgReportScope.All,
        org: String = "Test Partei e.V.",
        units: List<PartgUnitDto> = units(),
        entered: Map<Int, Map<String, Map<String, Long>>> = entered(),
    ): PartgReportDocument =
        PartgReportAssembler.assemble(organizationName = org, year = YEAR, scope = scope, units = units, entered = entered)

    private fun donor(
        name: String,
        cents: Long,
        kind: PartgPersonKind = PartgPersonKind.NATURAL,
        street: String? = "Hauptstraße 1",
        postal: String? = "38100",
        city: String? = "Braunschweig",
    ) = PartgAnnexStore.DonorRow(
        id = Uuid.random(),
        annexId = Uuid.random(),
        personKind = kind,
        name = name,
        street = street,
        postalCode = postal,
        city = city,
        country = "DE",
        contributionKind = PartgContributionKind.DONATION,
        amountCents = cents,
    )

    /** Hostile and exotic donor names on purpose: formula starters, Umlauts, Cyrillic. */
    fun federalDonors(): List<PartgAnnexStore.DonorRow> =
        PartgAnnexStore.sortDonors(
            listOf(
                donor(name = "=HYPERLINK(\"http://evil.example\",\"x\")", cents = 9_000_000L),
                donor(name = "+1 Plus", cents = 8_000_000L),
                donor(name = "-x Minus", cents = 7_000_000L),
                donor(name = "@x At", cents = 6_000_000L),
                donor(name = "Müller & Söhne <GmbH>", cents = 5_000_000L, kind = PartgPersonKind.LEGAL),
                donor(name = "Иван Петров", cents = 4_000_000L, street = null, postal = null, city = null),
            ),
        )

    fun annexData(): Map<Int, Map<String, PartgAnnexStore.AnnexData>> =
        mapOf(
            YEAR to
                mapOf(
                    PartgUnit.FEDERAL_KEY to
                        PartgAnnexStore.AnnexData(
                            revision = 3,
                            deductions =
                                mapOf(
                                    PartgDeduction.UNASSIGNABLE to 11_100L,
                                    PartgDeduction.CASH_OVER_1000 to 0L,
                                    PartgDeduction.OVER_3300 to null,
                                    PartgDeduction.WRONGLY_REPORTED to 33_300L,
                                ),
                            memberCount = 1_234,
                            donors = federalDonors(),
                        ),
                    PartgUnit.State(CHAPTER_A).key() to
                        PartgAnnexStore.AnnexData(
                            revision = 1,
                            deductions = PartgDeduction.entries.associateWith { 2_200L },
                            memberCount = null,
                            donors = emptyList(),
                        ),
                ),
        )

    fun entry(
        date: LocalDate = LocalDate(YEAR, 3, 1),
        cents: Long,
        purpose: String? = null,
    ) = PartgStatementStore.EntryRow(id = Uuid.random(), sponsorId = Uuid.random(), date = date, amountCents = cents, purpose = purpose)

    fun sponsor(
        name: String,
        entries: List<PartgStatementStore.EntryRow>,
        kind: PartgPersonKind = PartgPersonKind.LEGAL,
        street: String? = "Hauptstraße 1",
        postal: String? = "10115",
        city: String? = "Berlin",
    ) = PartgStatementStore.SponsorRow(
        id = Uuid.random(),
        annexId = Uuid.random(),
        personKind = kind,
        name = name,
        street = street,
        postalCode = postal,
        city = city,
        country = "DE",
        entries = PartgStatementStore.sortEntries(entries),
    )

    /** Hostile names and purposes on purpose; one sponsor under both limits (must NOT be exported), one with a single service, one yearly. */
    fun federalSponsors(): List<PartgStatementStore.SponsorRow> =
        PartgStatementStore.sortSponsors(
            listOf(
                sponsor(
                    name = "=cmd|' /C calc'!A0",
                    entries = listOf(entry(cents = 75_001L, purpose = "+1 Zweck"), entry(cents = 10_000L)),
                ),
                sponsor(name = "Jahres GmbH", entries = List(9) { entry(cents = 75_000L, purpose = "@x") }),
                sponsor(name = "Beide & Söhne <AG>", entries = listOf(entry(cents = 700_000L, purpose = "Иван"))),
                sponsor(name = "Unter den Grenzen", entries = List(8) { entry(cents = 74_999L) }, street = null),
            ),
        )

    fun federalYouthOrgs(): List<PartgStatementStore.YouthOrgRow> =
        PartgStatementStore.sortYouthOrgs(
            listOf(
                PartgStatementStore.YouthOrgRow(
                    id = Uuid.random(),
                    annexId = Uuid.random(),
                    name = "-Jugend Alpha",
                    relationship = "=Vereinigung",
                    incomeCents = 500_000L,
                    expensesCents = 400_000L,
                ),
                PartgStatementStore.YouthOrgRow(
                    id = Uuid.random(),
                    annexId = Uuid.random(),
                    name = "Jugend Beta",
                    relationship = null,
                    incomeCents = null,
                    expensesCents = null,
                ),
            ),
        )

    /** The R3 data plus statements C and F of the federal unit. */
    fun annexDataR3b(): Map<Int, Map<String, PartgAnnexStore.AnnexData>> {
        val base = annexData()
        val federal = base.getValue(YEAR).getValue(PartgUnit.FEDERAL_KEY)
        return mapOf(
            YEAR to
                base.getValue(YEAR) +
                (PartgUnit.FEDERAL_KEY to federal.copy(sponsors = federalSponsors(), youthOrgs = federalYouthOrgs())),
        )
    }

    fun docR3b(scope: PartgReportScope = PartgReportScope.All): PartgReportDocument {
        val findings =
            PartgPlausibility.check(
                year = YEAR,
                units = units(),
                entered = entered(),
                annex = annexDataR3b(),
                notes = notesData(),
            )
        return PartgReportAssembler.assemble(
            organizationName = "Test Partei e.V.",
            year = YEAR,
            scope = scope,
            units = units(),
            entered = entered(),
            annex = annexDataR3b(),
            notes = notesData(),
            findings = findings,
        )
    }

    fun notesData(): Map<Int, PartgAnnexStore.NotesData> =
        mapOf(
            YEAR to
                PartgAnnexStore.NotesData(
                    revision = 2,
                    notes =
                        listOf(
                            PartgNoteDto(
                                block = PartgNoteBlock.GENERAL_ACCOUNTING,
                                included = true,
                                body = "Erste Zeile\nZweite Zeile: =SUMME(A1)",
                                reliefAbs1 = null,
                                reliefAbs3 = null,
                            ),
                            PartgNoteDto(
                                block = PartgNoteBlock.RELIEF_28,
                                included = true,
                                body = "Begründung",
                                reliefAbs1 = PartgTriState.YES,
                                reliefAbs3 = PartgTriState.UNSPECIFIED,
                            ),
                            PartgNoteDto(
                                block = PartgNoteBlock.HOLDINGS,
                                included = true,
                                body = null,
                                reliefAbs1 = null,
                                reliefAbs3 = null,
                            ),
                            PartgNoteDto(
                                block = PartgNoteBlock.MEDIA_PRODUCTS,
                                included = false,
                                body = "Nicht aufgenommen, darf nicht erscheinen",
                                reliefAbs1 = null,
                                reliefAbs3 = null,
                            ),
                            PartgNoteDto(
                                block = PartgNoteBlock.REAL_ESTATE_VALUATION,
                                included = false,
                                body = null,
                                reliefAbs1 = null,
                                reliefAbs3 = null,
                            ),
                        ),
                    holdings =
                        listOf(
                            PartgHoldingDto(
                                name = "Pressehaus GmbH",
                                seat = "Berlin",
                                shareBasisPoints = 2_550,
                                capitalCents = 25_000_00L,
                                resultCents = -1_500_00L,
                            ),
                        ),
                ),
        )

    /** The standard document plus the R3 statements, notes and the hints computed from them. */
    fun docR3(scope: PartgReportScope = PartgReportScope.All): PartgReportDocument {
        val findings =
            PartgPlausibility.check(
                year = YEAR,
                units = units(),
                entered = entered(),
                annex = annexData(),
                notes = notesData(),
            )
        return PartgReportAssembler.assemble(
            organizationName = "Test Partei e.V.",
            year = YEAR,
            scope = scope,
            units = units(),
            entered = entered(),
            annex = annexData(),
            notes = notesData(),
            findings = findings,
        )
    }
}

/** A parsed XLSX cell: `type` is the `t` attribute (`n` when absent), `style` the `s` attribute. */
internal data class XlsxCell(
    val type: String,
    val value: String,
    val style: Int,
)

internal class XlsxContents(
    val sheetNames: List<String>,
    val sheets: Map<String, Map<String, XlsxCell>>,
    val parts: Map<String, ByteArray>,
)

internal fun readXlsx(bytes: ByteArray): XlsxContents {
    val parts = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            parts[e.name] = zip.readBytes()
        }
    }

    fun reader(data: ByteArray) =
        XMLInputFactory
            .newFactory()
            .apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }.createXMLStreamReader(ByteArrayInputStream(data))

    val names = mutableListOf<String>()
    reader(parts.getValue("xl/workbook.xml")).let { r ->
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "sheet") names += r.getAttributeValue(null, "name")
        }
    }
    val sheets = LinkedHashMap<String, Map<String, XlsxCell>>()
    names.forEachIndexed { i, name ->
        val cells = LinkedHashMap<String, XlsxCell>()
        val r = reader(parts.getValue("xl/worksheets/sheet${i + 1}.xml"))
        var ref = ""
        var type = "n"
        var style = 0
        var text = StringBuilder()
        var inValue = false
        var inText = false
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT ->
                    when (r.localName) {
                        "c" -> {
                            ref = r.getAttributeValue(null, "r")
                            type = r.getAttributeValue(null, "t") ?: "n"
                            style = r.getAttributeValue(null, "s")?.toInt() ?: 0
                            text = StringBuilder()
                        }
                        "v" -> inValue = true
                        "t" -> inText = true
                    }
                XMLStreamConstants.CHARACTERS -> if (inValue || inText) text.append(r.text)
                XMLStreamConstants.END_ELEMENT ->
                    when (r.localName) {
                        "v" -> inValue = false
                        "t" -> inText = false
                        "c" -> cells[ref] = XlsxCell(type = type, value = text.toString(), style = style)
                    }
            }
        }
        sheets[name] = cells
    }
    return XlsxContents(sheetNames = names, sheets = sheets, parts = parts)
}

package network.lapis.cloud.server.partg.report

import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

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

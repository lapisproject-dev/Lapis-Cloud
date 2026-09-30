package network.lapis.cloud.client

import io.kvision.dropdown.dropDown
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.table.cell
import io.kvision.table.row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.17 (follow-up check of the v0.25.0 "Table/DataColumn cells" security item): the proof that a raw table
 * cell value is an i18n sink, and that the central helpers ([textCell], [numCell], [cellText]) close it.
 *
 * KVision's `Cell`, `HeaderCell` and `Row` are `Tag`s; `Tag.render` runs every content string through
 * `Widget.translate` -> `I18n.trans`, which resolves a leading `###KvI18nS###` through gettext and a
 * `###KvI18nP###a###KvI18nP###b###KvI18nP###n` payload through ngettext -- independent of `rich`. So a raw cell
 * value that starts with a marker is REPLACED by catalog text (text spoofing; HTML stays text, `rich` is never set).
 *
 * The `*_documentsVulnerability` tests keep the proof against KVision itself: they build the cell the OLD way
 * (raw `cell(content = payload)`), which no production code does any more (tripwire T-R1 in
 * `ClientUntrustedWidgetTextTripwireTest`), and assert that the marker IS resolved. The other tests assert that the
 * helper path renders the payload as inert, marker-free text.
 */
class TableCellI18nMarkerDomTest {
    private val catalog = mapOf("Ja" to "Yes")

    /** P1: a singular marker in front of a catalog key -> the translation of that key. */
    private val singularPayload = KV_I18N_MARKER + "Ja"

    /** P2: a plural payload (singular key, plural key, count). */
    private val pluralPayload =
        KV_I18N_MARKER_PLURAL + "Ja" + KV_I18N_MARKER_PLURAL + "Jas" + KV_I18N_MARKER_PLURAL + "1"

    /** P3: markup, must stay text. */
    private val htmlPayload = "<img src=x onerror=alert(1)>"

    /** Tamper: a marker spliced together from two halves around a nested marker (needs the fixed-point sanitizer). */
    private val splitPayload = "###KvI" + KV_I18N_MARKER + "18nS###Ja"

    /** Tamper: separator and value sentinel in front of a marker (the forged-amount shape). */
    private val forgedAmountPayload = KV_I18N_MARKER + I18N_VALUE_SENTINEL + MONEY_KIND_LTR + "9999"

    private val separatorPayload = KV_I18N_MARKER + "Ja" + I18N_ARG_SEPARATOR + KV_I18N_MARKER + "Ja"

    private val markerPayloads = listOf(singularPayload, pluralPayload, splitPayload, forgedAmountPayload, separatorPayload)

    private fun assertInert(
        text: String,
        payload: String,
    ) {
        assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
        assertFalse(text.contains(KV_I18N_MARKER_PLURAL), "plural marker must not survive: $text")
        assertFalse(text.contains(I18N_VALUE_SENTINEL), "sentinel must not survive: $text")
        assertFalse(text.contains(I18N_ARG_SEPARATOR), "separator must not survive: $text")
        assertEquals(sanitizeUntrustedI18nText(payload), text, "the cell shows the sanitized payload, nothing else")
    }

    // ---------------------------------------------------------------- the vulnerability, documented

    @Test
    fun rawCellResolvesMarker_documentsVulnerability() {
        withTranslations(catalog) {
            withMountedRoot("cell-raw-marker-test") { root, element ->
                val table = root.standardTable(emptyList())
                table.row { cell(content = singularPayload) }
                assertEquals("Yes", element().querySelector("td")!!.textContent, "KVision resolved the forged marker in a raw cell")
            }
        }
    }

    @Test
    fun rawCellResolvesPluralMarker_documentsVulnerability() {
        withTranslations(catalog) {
            withMountedRoot("cell-raw-plural-test") { root, element ->
                val table = root.standardTable(emptyList())
                table.row { cell(content = pluralPayload) }
                val text = element().querySelector("td")!!.textContent.orEmpty()
                assertNotEquals(pluralPayload, text, "the plural payload was consumed by KVision, not shown as text")
                assertFalse(text.contains(KV_I18N_MARKER_PLURAL), "the plural marker was resolved: $text")
            }
        }
    }

    @Test
    fun rawSpanInAColumnLambda_documentsVulnerability() {
        withTranslations(catalog) {
            withMountedRoot("cell-raw-column-test") { root, element ->
                val column = DataColumn<String>(title = "x", cell = { container, value -> container.span(value) })
                root.plainDataTable(listOf(column), listOf(singularPayload), FakeNarrowViewport(narrow = false))
                assertEquals("Yes", element().querySelector("td")!!.textContent)
            }
        }
    }

    @Test
    fun rawHtmlStaysText_evenInARawCell() {
        withMountedRoot("cell-raw-html-test") { root, element ->
            val table = root.standardTable(emptyList())
            table.row { cell(content = htmlPayload) }
            assertNull(element().querySelector("img"), "HTML is only interpreted for rich = true")
            assertTrue(
                element()
                    .querySelector("td")!!
                    .textContent
                    .orEmpty()
                    .contains("<img"),
            )
        }
    }

    // ---------------------------------------------------------------- the helper path

    @Test
    fun textCell_rendersEveryPayloadAsInertText() {
        withTranslations(catalog) {
            markerPayloads.forEachIndexed { index, payload ->
                withMountedRoot("cell-text-cell-test-$index") { root, element ->
                    val table = root.standardTable(emptyList())
                    table.row { textCell(payload) }
                    assertInert(element().querySelector("td")!!.textContent.orEmpty(), payload)
                }
            }
        }
    }

    @Test
    fun numCell_rendersEveryPayloadAsInertText_andKeepsTheNumericClass() {
        withTranslations(catalog) {
            markerPayloads.forEachIndexed { index, payload ->
                withMountedRoot("cell-num-cell-test-$index") { root, element ->
                    val table = root.standardTable(emptyList())
                    table.row { numCell(payload) }
                    val td = element().querySelector("td")!!
                    assertInert(td.textContent.orEmpty(), payload)
                    assertTrue(td.classList.contains(NUMERIC_CELL_CLASS), "numeric class stays")
                }
            }
        }
    }

    @Test
    fun cellText_inATableColumn_rendersEveryPayloadAsInertText() {
        withTranslations(catalog) {
            markerPayloads.forEachIndexed { index, payload ->
                withMountedRoot("cell-column-table-test-$index") { root, element ->
                    val column = DataColumn<String>(title = "x", cell = { container, value -> container.cellText(value) })
                    root.plainDataTable(listOf(column), listOf(payload), FakeNarrowViewport(narrow = false))
                    assertInert(element().querySelector("td")!!.textContent.orEmpty(), payload)
                }
            }
        }
    }

    @Test
    fun cellText_inTheCardList_rendersEveryPayloadAsInertText() {
        withTranslations(catalog) {
            markerPayloads.forEachIndexed { index, payload ->
                withMountedRoot("cell-column-card-test-$index") { root, element ->
                    val columns =
                        listOf(
                            DataColumn<String>(title = "a", primary = true, cell = { container, value -> container.cellText(value) }),
                            DataColumn<String>(title = "b", cell = { container, value -> container.cellText(value) }),
                        )
                    root.plainDataTable(columns, listOf(payload), FakeNarrowViewport(narrow = true))
                    assertInert(element().querySelector(".lapis-data-card-title")!!.textContent.orEmpty(), payload)
                    assertInert(element().querySelector("dd")!!.textContent.orEmpty(), payload)
                }
            }
        }
    }

    @Test
    fun aHtmlPayloadNeverBecomesAnElement() {
        withMountedRoot("cell-helper-html-test") { root, element ->
            val table = root.standardTable(emptyList())
            table.row {
                textCell(htmlPayload)
                numCell(htmlPayload)
            }
            assertNull(element().querySelector("img"))
            assertEquals(htmlPayload + htmlPayload, element().querySelector("tbody tr")!!.textContent)
        }
    }

    @Test
    fun aTrustedTrResult_staysLiveTranslatable_inACell() {
        withTranslations(catalog) {
            withMountedRoot("cell-trusted-tr-test") { root, element ->
                val table = root.standardTable(emptyList())
                table.row { textCell(trusted(tr("Ja"))) }
                assertEquals("Yes", element().querySelector("td")!!.textContent)
            }
        }
    }

    @Test
    fun aBlankOrNullValue_stillBuildsTheCell() {
        withMountedRoot("cell-blank-test") { root, element ->
            val table = root.standardTable(emptyList())
            table.row {
                textCell(null)
                textCell("")
                numCell()
            }
            assertEquals(3, element().querySelectorAll("td").length)
        }
    }

    // ---------------------------------------------------------------- the navbar label (dropDown)

    @Test
    fun dropDownLabel_sanitizedBeforeItBecomesTheTrigger() {
        withTranslations(catalog) {
            markerPayloads.forEachIndexed { index, payload ->
                withMountedRoot("cell-dropdown-test-$index") { root, element ->
                    root.dropDown(sanitizeUntrustedI18nText(payload), forNavbar = true) {}
                    assertInert(
                        element()
                            .querySelector(".dropdown-toggle")!!
                            .textContent
                            .orEmpty()
                            .trim(),
                        payload,
                    )
                }
            }
        }
    }

    @Test
    fun dropDownLabel_rawMarker_documentsVulnerability() {
        withTranslations(catalog) {
            withMountedRoot("cell-dropdown-raw-test") { root, element ->
                root.dropDown(singularPayload, forNavbar = true) {}
                val text =
                    element()
                        .querySelector(".dropdown-toggle")!!
                        .textContent
                        .orEmpty()
                        .trim()
                assertNotEquals(singularPayload, text, "KVision consumed the marker of a raw dropDown label: $text")
            }
        }
    }

    @Test
    fun resolveCellText_contract() {
        assertEquals(null, resolveCellText(null))
        assertEquals("Ja", resolveCellText(singularPayload))
        assertEquals(singularPayload, resolveCellText(trusted(singularPayload)))
        assertEquals("<b>", resolveCellText("<b>"))
    }
}

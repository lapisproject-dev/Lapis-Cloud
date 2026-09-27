package network.lapis.cloud.client

import io.kvision.html.span
import io.kvision.i18n.I18n
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W7 S5 (mirrors [MoneyTokenDomTest]): a date/time that is widget content follows a language switch
 * WITHOUT a screen rebuild, and neither the marker nor the sentinel ever shows in the DOM. Only this
 * class, [MoneyTokenDomTest] and `LanguageChangeDomTest` may touch `I18n.language` (its setter restarts
 * the root), always restored in `finally`.
 */
class DateTimeTokenDomTest {
    private val sample = LocalDate(2026, 9, 24)
    private val sampleDateTime = LocalDateTime(2026, 9, 24, 14, 30)

    private fun leaks(text: String): Boolean =
        text.contains(KV_I18N_MARKER) || text.contains(I18N_VALUE_SENTINEL) || text.contains(I18N_ARG_SEPARATOR)

    @Test
    fun dateSpan_rendersLocalized_andFollowsALanguageSwitch() {
        withMountedRoot("datetime-token-switch") { root, element ->
            val span = root.dateSpan(sample)
            assertEquals("24.09.2026", span.getElement()?.textContent)
            try {
                I18n.language = "en"
                span.refresh()
                assertEquals("2026-09-24", element().querySelector("span")?.textContent)
            } finally {
                I18n.language = "de"
            }
            span.refresh()
            assertEquals("24.09.2026", element().querySelector("span")?.textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun dateTimeSpan_neverLeaksTheMarker() {
        withMountedRoot("datetime-token-dt") { root, element ->
            root.dateTimeSpan(sampleDateTime)
            assertEquals("24.09.2026,${NBSP}14:30", element().querySelector("span")?.textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun timestampSpan_neverLeaksTheMarker() {
        withMountedRoot("datetime-token-ts") { root, element ->
            root.timestampSpan(LocalDateTime(2026, 9, 24, 14, 30, 7))
            assertEquals("24.09.2026,${NBSP}14:30:07", element().querySelector("span")?.textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun timeSpan_neverLeaksTheMarker() {
        withMountedRoot("datetime-token-time") { root, element ->
            root.timeSpan(sampleDateTime)
            assertEquals("14:30", element().querySelector("span")?.textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun dayMonthSpan_neverLeaksTheMarker() {
        withMountedRoot("datetime-token-daymonth") { root, element ->
            root.dayMonthSpan(sample)
            assertEquals("24.09.", element().querySelector("span")?.textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun forgedPayload_isEscaped() {
        withMountedRoot("datetime-token-forgery") { root, element ->
            root.span(KV_I18N_MARKER + I18N_VALUE_SENTINEL + DATE_KIND_DATE + "<img src=x onerror=alert(1)>")
            assertEquals(0, element().querySelectorAll("img").length)
        }
    }

    @Test
    fun serverTextThatLooksLikeAToken_isNotSpecial() {
        withMountedRoot("datetime-token-lookalike") { root, element ->
            root.span("Termin d2026-09-24")
            assertEquals("Termin d2026-09-24", element().querySelector("span")?.textContent)
        }
    }

    /**
     * OF-4: whether a KVision `Row.cell(content = token)` (the `<td>` path used by e.g.
     * `AccountingExportScreen.kt`/`OpenItemsScreen.kt`, as opposed to `textColumn`'s `container.span(...)`
     * path) resolves a value token the same way a `span` does. If this test goes red, that population
     * needs `formatDateTime(...)`-style plain strings instead of tokens (a frozen-language gap to record
     * in the CHANGELOG), not a silent skip.
     */
    @Test
    fun cellPath_resolvesATokenInATableCell() {
        withMountedRoot("datetime-token-cell") { root, element ->
            root.table {
                row {
                    cell(content = dateTimeToken(sampleDateTime))
                }
            }
            val text = element().querySelector("td")?.textContent
            assertEquals("24.09.2026,${NBSP}14:30", text)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun dateColumn_producesATrustedToken() {
        withMountedRoot("datetime-token-column") { root, element ->
            data class Row(
                val at: LocalDate?,
            )
            val column = dateColumn<Row>(title = "Datum") { it.at }
            column.cell(root, Row(at = sample))
            assertEquals("24.09.2026", element().textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun dateColumn_missingValueShowsThePlaceholder() {
        withMountedRoot("datetime-token-column-missing") { root, element ->
            data class Row(
                val at: LocalDate?,
            )
            val column = dateColumn<Row>(title = "Datum") { it.at }
            column.cell(root, Row(at = null))
            assertEquals("–", element().textContent)
        }
    }

    @Test
    fun dateTimeColumn_producesATrustedToken() {
        withMountedRoot("datetime-token-column-dt") { root, element ->
            data class Row(
                val at: LocalDateTime?,
            )
            val dtColumn = dateTimeColumn<Row>(title = "Zeitpunkt") { it.at }
            dtColumn.cell(root, Row(at = sampleDateTime))
            assertEquals("24.09.2026,${NBSP}14:30", element().textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun dateTimeColumn_missingValueShowsThePlaceholder() {
        withMountedRoot("datetime-token-column-dt-missing") { root, element ->
            data class Row(
                val at: LocalDateTime?,
            )
            val dtColumn = dateTimeColumn<Row>(title = "Zeitpunkt") { it.at }
            dtColumn.cell(root, Row(at = null))
            assertEquals("–", element().textContent)
        }
    }

    /**
     * Review-Befund 2026-09-24: `timestampColumn` (DateTime.kt) had ZERO test coverage of its own --
     * the previous version of this test's name promised it ("...AndTimestampColumn...") but the body
     * only ever called `dateTimeColumn`. If [timestampColumn]'s `trusted(timestampToken(it))` call were
     * ever dropped, `textColumn`'s cell renderer would sanitize the plain token string unconditionally
     * (see the `dateColumn` KDoc's "sanitisation-loss" warning) and this table would silently show the
     * raw ISO string instead of a formatted timestamp -- with no other test in the repo able to catch it.
     */
    @Test
    fun timestampColumn_producesATrustedToken() {
        withMountedRoot("datetime-token-column-ts") { root, element ->
            data class Row(
                val at: LocalDateTime?,
            )
            val tsColumn = timestampColumn<Row>(title = "Protokolliert") { it.at }
            tsColumn.cell(root, Row(at = LocalDateTime(2026, 9, 24, 14, 30, 7)))
            assertEquals("24.09.2026,${NBSP}14:30:07", element().textContent)
            assertFalse(leaks(element().textContent.orEmpty()))
        }
    }

    @Test
    fun timestampColumn_missingValueShowsThePlaceholder() {
        withMountedRoot("datetime-token-column-ts-missing") { root, element ->
            data class Row(
                val at: LocalDateTime?,
            )
            val tsColumn = timestampColumn<Row>(title = "Protokolliert") { it.at }
            tsColumn.cell(root, Row(at = null))
            assertEquals("–", element().textContent)
        }
    }

    /** Mirrors [MoneyTokenDomTest.everyMoneySpanCarriesTheTabularNumsClass] -- the five `*Span` builders
     * here had the same zero-coverage gap for [TABULAR_NUMS_CLASS] (Review-Befund 2026-09-24). */
    @Test
    fun everyDateTimeSpanCarriesTheTabularNumsClass() {
        withMountedRoot("datetime-token-tnum") { root, _ ->
            val spans =
                listOf(
                    root.dateSpan(sample),
                    root.dayMonthSpan(sample),
                    root.dateTimeSpan(sampleDateTime),
                    root.timestampSpan(LocalDateTime(2026, 9, 24, 14, 30, 7)),
                    root.timeSpan(sampleDateTime),
                )
            spans.forEach { span ->
                assertTrue(
                    span.getElement()?.classList?.contains(TABULAR_NUMS_CLASS) == true,
                    "expected .$TABULAR_NUMS_CLASS on ${span.getElement()?.outerHTML}",
                )
            }
        }
    }
}

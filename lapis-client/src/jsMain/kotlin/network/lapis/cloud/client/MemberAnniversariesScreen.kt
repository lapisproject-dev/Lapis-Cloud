package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.AnniversaryEmphasis
import network.lapis.cloud.shared.domain.AnniversaryEntryDto
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.MemberAnniversaryOverviewDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.IMemberAnniversaryService

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- UI/UX-Design-Team-Entscheidungen (siehe Welle-Plan §3.3
 * für die volle Begründung):
 *
 * - **Eine Liste, kein zweiter Bildschirm**: Geburtstage und Jubiläen teilen sich EINE nach
 *   `occursOn` sortierte Tabelle; ein reiner Client-Filter (Art-Select) blendet nur die eine bereits
 *   geladene Liste ein/aus, statt zwei getrennte Server-Abfragen zu laden. Die dominante Frage ist
 *   "wann steht das an", nicht "welche Art ist es" -- die Art ist ein sekundäres, formales (nie nur
 *   farbliches) Merkmal je Zeile.
 * - **Zeitraum-Presets statt Freitext**: 30/60/90 Tage (`AnniversaryCalendar.WINDOW_PRESETS`), kein
 *   Datumsbereich-Eingabefeld -- in dieser Codebase existiert noch keine wiederverwendbare Datum/
 *   Zeit-Eingabekomponente (dieselbe offengelegte Vereinfachung, die `CrmContactsScreen.kt`'s eigene
 *   KDoc bereits für ihr `occurred_at`-Feld dokumentiert); Presets schließen diese Lücke, ohne eine
 *   neue Komponente zu erfinden.
 * - **Beschriftung folgt Status, nicht der Feldname** -- siehe [anniversaryRowLabel]: ein
 *   `MEMBERSHIP_ANNIVERSARY`-Eintrag mit `memberStatus == DONOR` heisst "N Jahre Förderer", niemals
 *   "N Jahre Mitgliedschaft" (Tesler-Fund im Design-Review, siehe [AnniversaryEntryDto] KDoc).
 * - **Kein Geburtsjahr**: nur das abgeleitete Alter (`entry.years`) wird gezeigt, nie das exakte
 *   Geburtsdatum -- Zurückhaltung, keine DSGVO-Anonymisierungs-Behauptung (Ive/Atkinson).
 * - **Abdeckungszeile ist Pflicht, kein Kann**: fehlt einem berücksichtigten Mitglied das
 *   Geburtsdatum, sagt die Liste das explizit ("N von M ... kein Geburtsdatum hinterlegt"), statt
 *   eine unvollständige Liste stillschweigend als vollständig darzustellen (Norman).
 * - **Bewusst NICHT Teil dieser Welle** (Jobs im Abschluss-Review): kein CSV-/PDF-Export, kein
 *   Mail-/Benachrichtigungsversand, kein freier Datumsbereich, keine Familienmitgliedschaften/
 *   Ehrungen/Sterbefall-Workflows -- jedes davon eine eigene, spätere Scoping-Entscheidung.
 *
 * Rollen ([IMemberAnniversaryService] KDoc): BOARD/ADMIN, keine Selbstauskunft -- serverseitig via
 * `current.requireRole(BOARD, ADMIN)` in `MemberAnniversaryService`, route-level ebenso in
 * `Routes.MEMBER_ANNIVERSARIES`.
 */
fun renderMemberAnniversariesScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Geburtstage & Jubiläen"))
    root.div(
        tr(
            "Bevorstehende Geburtstage und Mitgliedschafts-/Förderer-Jubiläen -- eine gemeinsame, " +
                "chronologische Liste für den Vorstand.",
        ),
    ) { addCssClasses("text-muted small") }

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val windowOptions = AnniversaryCalendar.WINDOW_PRESETS.map { it.toString() to gettext("%1 Tage", it) }
    val windowSelect =
        filterRow.select(
            options = windowOptions,
            value = AnniversaryCalendar.DEFAULT_WINDOW_DAYS.toString(),
            label = tr("Zeitraum"),
        )
    val kindOptions =
        listOf(
            "" to tr("-- Alle --"),
            AnniversaryEntryKind.BIRTHDAY.name to tr("Nur Geburtstage"),
            AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY.name to tr("Nur Jubiläen"),
        )
    val kindSelect = filterRow.select(options = kindOptions, value = "", label = tr("Art"))

    val resultPanel = root.vPanel(spacing = 8)
    var currentDto: MemberAnniversaryOverviewDto? = null

    fun renderResults() {
        val dto = currentDto ?: return
        resultPanel.removeAll()
        anniversaryCoverageText(dto)?.let { text -> resultPanel.div(text) { addCssClasses("text-muted small") } }

        val filterKind = runCatching { AnniversaryEntryKind.valueOf(kindSelect.value.orEmpty()) }.getOrNull()
        val visibleEntries = if (filterKind == null) dto.entries else dto.entries.filter { it.kind == filterKind }

        if (visibleEntries.isEmpty()) {
            resultPanel.p(tr("Keine Treffer in diesem Zeitraum."))
            return
        }
        val table =
            resultPanel.table(
                headerNames = listOf(tr("Datum"), tr("Mitglied"), tr("Anlass")),
                types = setOf(TableType.STRIPED, TableType.HOVER),
            )
        visibleEntries.forEach { entry -> renderAnniversaryRow(table = table, entry = entry, today = dto.from) }
    }

    fun loadOverview() {
        val windowDays = windowSelect.value?.toIntOrNull() ?: AnniversaryCalendar.DEFAULT_WINDOW_DAYS
        AppScope.launch {
            val dto = guarded { rpcService<IMemberAnniversaryService>().getUpcomingAnniversaries(windowDays) } ?: return@launch
            currentDto = dto
            renderResults()
        }
    }

    windowSelect.subscribe { loadOverview() }
    kindSelect.subscribe { renderResults() }
    loadOverview()
}

private fun renderAnniversaryRow(
    table: Table,
    entry: AnniversaryEntryDto,
    today: LocalDate,
) {
    table.row {
        val dateCell = cell()
        dateCell.span(anniversaryDateLabel(entry))
        if (entry.occursOn == today) {
            dateCell.typeBadge(tr("Heute"), "success")
        }
        cell(entry.memberDisplayName)
        val occasionCell = cell()
        occasionCell.icon(anniversaryKindIcon(entry.kind))
        occasionCell.span(anniversaryRowLabel(entry)) { addCssClass(anniversaryEmphasisCssClass(entry.emphasis)) }
    }
}

private fun anniversaryKindIcon(kind: AnniversaryEntryKind): String =
    when (kind) {
        AnniversaryEntryKind.BIRTHDAY -> "fas fa-cake-candles"
        AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY -> "fas fa-award"
    }

internal fun anniversaryRowLabel(entry: AnniversaryEntryDto): String =
    when (entry.kind) {
        AnniversaryEntryKind.BIRTHDAY -> gettext("wird %1", entry.years)
        AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY ->
            if (entry.memberStatus == MemberStatus.DONOR) {
                gettext("%1 Jahre Förderer", entry.years)
            } else {
                gettext("%1 Jahre Mitgliedschaft", entry.years)
            }
    }

internal fun anniversaryDateLabel(entry: AnniversaryEntryDto): String =
    if (entry.shiftedFromLeapDay) {
        gettext("29.02. · in diesem Jahr am %1", formatDayMonth(entry.occursOn))
    } else {
        formatDayMonth(entry.occursOn)
    }

internal fun anniversaryEmphasisCssClass(emphasis: AnniversaryEmphasis): String =
    when (emphasis) {
        AnniversaryEmphasis.MAJOR -> "fw-bold"
        AnniversaryEmphasis.NOTABLE -> "fw-semibold"
        AnniversaryEmphasis.FIRST_YEAR -> "fst-italic"
        AnniversaryEmphasis.STANDARD -> ""
    }

internal fun anniversaryCoverageText(dto: MemberAnniversaryOverviewDto): String? =
    if (dto.membersWithoutDateOfBirth > 0) {
        gettext(
            "Für %1 von %2 berücksichtigten Personen ist kein Geburtsdatum hinterlegt -- deren Geburtstage fehlen in dieser Liste.",
            dto.membersWithoutDateOfBirth,
            dto.eligibleMemberCount,
        )
    } else {
        null
    }

/**
 * `dd.MM.` -- day and month only, zero-padded, no year (see [AnniversaryEntryDto] KDoc "Kein
 * Geburtsjahr"). Non-deprecated `.day`/`.month.number` API (NOT `.dayOfMonth`/`.monthNumber`,
 * deprecated in kotlinx-datetime 0.8.0), same idiom `ConferenceScreen.kt`'s
 * `conferenceDefaultRoomTitle` already establishes -- via `.padStart`, not `String.format` (JVM-only,
 * unavailable in this jsMain target).
 */
internal fun formatDayMonth(date: LocalDate): String {
    val day = date.day.toString().padStart(2, '0')
    val month =
        date.month.number
            .toString()
            .padStart(2, '0')
    return "$day.$month."
}

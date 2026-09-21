package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import kotlinx.browser.window
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
    val root = container.dataScreenRoot()
    root.h1(tr("Geburtstage & Jubiläen"))
    root.div(
        tr(
            "Bevorstehende Geburtstage und Mitgliedschafts-/Förderer-Jubiläen -- eine gemeinsame, " +
                "chronologische Liste für den Vorstand.",
        ),
    ) { addCssClasses("text-muted small") }

    // Welle V1.4.26 (W2): Filterleiste in der Reihenfolge der Richtlinie 2.4 -- Suchfeld, Segment, Detailfilter.
    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val searchInput = filterRow.text(label = tr("Suche nach Name"))
    var searchTerm = ""
    val kindSegmentHost = filterRow.simplePanel()
    val windowOptions = AnniversaryCalendar.WINDOW_PRESETS.map { it.toString() to gettext("%1 Tage", it) }
    val windowSelect =
        filterRow.select(
            options = windowOptions,
            value = AnniversaryCalendar.DEFAULT_WINDOW_DAYS.toString(),
            label = tr("Zeitraum"),
        )

    // The client-side kind filter of Welle V1.4.4.4 stays a CLIENT filter (see the file KDoc: one list,
    // one request) -- it only re-renders [contentHost], it never reloads. `dataSection` therefore cannot
    // decide "no match" for it; [renderFiltered] says that sentence itself.
    var loadedDto: MemberAnniversaryOverviewDto? = null
    var contentHost: SimplePanel? = null
    var kindFilter: AnniversaryEntryKind? = null

    fun renderFiltered() {
        val dto = loadedDto ?: return
        val host = contentHost ?: return
        host.removeAll()
        anniversaryCoverageText(dto)?.let { text -> host.div(text) { addCssClasses("text-muted small") } }

        val visibleEntries = filterAnniversaryEntries(dto.entries, kindFilter, searchTerm)
        if (visibleEntries.isEmpty()) {
            host.p(anniversaryNoMatchText(kindFilter, searchTerm)) { addCssClasses("text-muted") }
            return
        }
        host.div(
            dataCountText(
                shown = visibleEntries.size,
                loaded = dto.entries.size,
                filtered = kindFilter != null || searchTerm.isNotBlank(),
            ),
        ) { addCssClasses("text-muted small") }
        host.dataTable(
            columns = anniversaryColumns(today = dto.from),
            rows = visibleEntries,
        )
    }

    val section =
        root.dataSection<MemberAnniversaryOverviewDto>(
            // The window is the view's scope, not a filter the reader typed -- so an empty window is
            // "nothing coming up", never "nothing found".
            emptyText = tr("In diesem Zeitraum stehen keine Geburtstage oder Jubiläen an."),
            isEmpty = { it.entries.isEmpty() },
            load = {
                // Hier, nicht nur im Zeitraum-Handler: jeder Ladevorgang -- auch der über „Erneut
                // versuchen" -- muss den gehaltenen Stand fallen lassen, sonst könnte ein Tastendruck im
                // Suchfeld nach einem gescheiterten Abruf in ein abgehängtes Panel schreiben.
                loadedDto = null
                contentHost = null
                val windowDays = windowSelect.value?.toIntOrNull() ?: AnniversaryCalendar.DEFAULT_WINDOW_DAYS
                guarded { rpcService<IMemberAnniversaryService>().getUpcomingAnniversaries(windowDays) }
            },
            render = { panel, dto ->
                loadedDto = dto
                contentHost = panel.simplePanel()
                renderFiltered()
            },
        )

    kindSegmentHost.segmentedControl(
        options =
            listOf(
                null to tr("Alle"),
                AnniversaryEntryKind.BIRTHDAY to tr("Geburtstage"),
                AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY to tr("Jubiläen"),
            ),
        selected = kindFilter,
        ariaLabel = tr("Art"),
    ) { kind ->
        kindFilter = kind
        renderFiltered()
    }

    // Same synthetic-first-event guard as the search field below: without it KVisions `subscribe`
    // registration alone would fire one `getUpcomingAnniversaries` request on top of the explicit
    // `section.reload()` at the end of this function (two round trips per screen mount, as before this wave).
    var isInitialWindowEvent = true
    windowSelect.subscribe {
        if (isInitialWindowEvent) {
            isInitialWindowEvent = false
            return@subscribe
        }
        section.reload()
    }
    // 300 ms Debounce ohne Suchknopf, mit `isInitialSearchEvent`-Guard -- Muster aus
    // `MemberAdministrationScreen` (KVisions `subscribe` feuert bei der Registrierung synthetisch mit).
    var isInitialSearchEvent = true
    var debounceHandle: Int? = null
    searchInput.subscribe { value ->
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        debounceHandle?.let { window.clearTimeout(it) }
        debounceHandle =
            window.setTimeout({
                searchTerm = value.orEmpty()
                renderFiltered()
            }, 300)
    }
    section.reload()
}

/**
 * The two client-side filters of this screen as ONE pure function (see `MemberAnniversariesScreenTest`):
 * the kind segment and the name search. A blank [search] filters nothing; matching is case-insensitive on
 * the display name only -- there is no other free text in a row.
 */
internal fun filterAnniversaryEntries(
    entries: List<AnniversaryEntryDto>,
    kind: AnniversaryEntryKind?,
    search: String,
): List<AnniversaryEntryDto> {
    val term = search.trim()
    return entries.filter { entry ->
        (kind == null || entry.kind == kind) &&
            (term.isEmpty() || entry.memberDisplayName.contains(term, ignoreCase = true))
    }
}

/**
 * "Nothing matches" -- and it names WHICH of the two client filters is responsible (pure, see
 * `MemberAnniversariesScreenTest`). Both active quotes the search term, because that is the one the
 * reader typed and can correct.
 */
internal fun anniversaryNoMatchText(
    kind: AnniversaryEntryKind?,
    search: String,
): String {
    val term = search.trim()
    return when {
        term.isNotEmpty() -> gettext("Kein Eintrag passt zu \"%1\".", term)
        kind == AnniversaryEntryKind.BIRTHDAY -> gettext("In diesem Zeitraum steht kein Geburtstag an.")
        kind == AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY -> gettext("In diesem Zeitraum steht kein Jubiläum an.")
        else -> gettext("Kein Eintrag passt zu den gewählten Filtern.")
    }
}

/** Columns of the anniversary table / card list; the member is the row's identity, so it is the card title. */
private fun anniversaryColumns(today: LocalDate): List<DataColumn<AnniversaryEntryDto>> =
    listOf(
        textColumn(title = tr("Mitglied"), primary = true) { entry: AnniversaryEntryDto -> entry.memberDisplayName },
        DataColumn(
            title = tr("Datum"),
            cell = { container, entry ->
                container.span(anniversaryDateLabel(entry))
                if (entry.occursOn == today) container.typeBadge(tr("Heute"), "success")
            },
        ),
        DataColumn(
            title = tr("Anlass"),
            cell = { container, entry -> container.renderAnniversaryOccasion(entry) },
        ),
    )

private fun Container.renderAnniversaryOccasion(entry: AnniversaryEntryDto) {
    icon(anniversaryKindIcon(entry.kind))
    span(anniversaryRowLabel(entry)) { addCssClass(anniversaryEmphasisCssClass(entry.emphasis)) }
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

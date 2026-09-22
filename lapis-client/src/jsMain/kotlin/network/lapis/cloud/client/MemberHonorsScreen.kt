package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberHonorCategory
import network.lapis.cloud.shared.domain.MemberHonorCategory.HONORARY_MEMBERSHIP
import network.lapis.cloud.shared.domain.MemberHonorCategory.LOYALTY_AWARD
import network.lapis.cloud.shared.domain.MemberHonorCategory.OTHER
import network.lapis.cloud.shared.domain.MemberHonorCategory.SERVICE_AWARD
import network.lapis.cloud.shared.domain.MemberHonorDto
import network.lapis.cloud.shared.domain.MemberHonorInput
import network.lapis.cloud.shared.domain.MemberHonorLimits
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IMemberHonorService
import network.lapis.cloud.shared.rpc.IMemberService
import kotlin.time.Clock

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- UI/UX-Design-Team-Entscheidungen:
 *
 * - **`Modal`, nicht modeless** -- anders als [CrmContactsScreen] (häufiger, beiläufiger Vorgang):
 *   das Erfassen einer Ehrung ist ein seltener, bewusster Vorgang, der die volle Aufmerksamkeit des
 *   Vorstands verdient (Welle-Plan §13 "S6").
 * - **Zwei Einstiege, eine Implementierung**: board-weite Liste ([Routes.MEMBER_HONORS] ohne
 *   Parameter) und gefilterte Sicht für ein einzelnes Mitglied (`?member=<id>`, zweiter Roster-Knopf
 *   in [MemberAdministrationScreen]) -- gleiche Route, gleicher Screen, wie
 *   [renderMemberFinancialHistoryScreen]/[Routes.MEMBER_FINANCES] es vormachen. Im gefilterten
 *   Zustand entfällt die Mitglieds-Spalte der Tabelle, UND es erscheint IMMER ein sichtbarer
 *   Rücksprung-Link zur ungefilterten Liste (Atkinson-Bedingung, nicht optional) -- unabhängig
 *   davon, ob der Name des gefilterten Mitglieds bereits bekannt ist.
 * - **Alle Felder editierbar** -- eine Ehrung ist kein append-only Log wie `crm_interaction`; ein
 *   Tippfehler im Titel oder ein falsches Datum muss vollständig korrigierbar sein.
 * - **Löschen ist ADMIN-only, UND der Knopf wird für BOARD gar nicht erst gerendert** (nicht nur
 *   deaktiviert) -- Präzedenz [CrmContactsScreen], vermeidet einen sichtbar toten Knopf.
 *
 * Rollen ([IMemberHonorService] KDoc): BOARD/ADMIN für Lesen/Erfassen/Bearbeiten, ADMIN-only für
 * [IMemberHonorService.deleteHonor] -- serverseitig durchgesetzt, hier nur gespiegelt.
 *
 * The DOM-free helper functions below ([memberHonorsRoute]/[memberHonorCategoryLabel]/
 * [memberHonorCategoryIcon]/[memberHonorsEmptyStateText]) are the only pure
 * surface of this screen -- see `MemberHonorsScreenTest`.
 */
fun renderMemberHonorsScreen(
    container: SimplePanel,
    requestedMemberId: String?,
) {
    val root = container.dataScreenRoot()

    val headingPanel = root.vPanel(spacing = 2)
    // W5: constant title; the member the list is narrowed to is a data value and goes into the subtitle.
    val pageHead = headingPanel.pageHeader(tr("Ehrungen & Auszeichnungen"), subtitle = "")
    if (requestedMemberId != null) {
        headingPanel.link(tr("Alle Ehrungen anzeigen"), url = "#${memberHonorsRoute(null)}") {
            addCssClasses("small")
        }
    }
    root.div(
        tr(
            "Ehrenmitgliedschaften, Verdienst- und Treueauszeichnungen -- eine Übersicht für den " +
                "Vorstand.",
        ),
    ) { addCssClasses("text-muted small") }

    // Welle V1.4.26 (W2): Filterleiste nach Richtlinie 2.4 -- Suchfeld, Kategorie-Segment (der
    // `select` mit fünf gegenseitig ausschließenden Werten trug keinen Aktivzustand und kein
    // `aria-pressed`), Primäraktion rechts.
    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val searchInput = filterRow.text(label = tr("Suche nach Titel oder Mitglied"))
    val categorySegmentHost = filterRow.simplePanel()
    val newHonorButton = filterRow.button(tr("Ehrung erfassen"), style = ButtonStyle.PRIMARY)

    var members: List<MemberSummaryDto> = emptyList()
    AppScope.launch {
        members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
    }

    val countsLabel = root.div().apply { addCssClasses("text-muted small") }
    val statusRegion = root.dataStatusRegion()
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }
    var loadedOffset = 0
    var totalCount = 0
    var hasMore = false
    var searchTerm = ""
    var categoryFilter: MemberHonorCategory? = null
    var resolvedMemberDisplayName: String? = null
    var generation = 0
    val loaded = mutableListOf<MemberHonorDto>()
    var loading = false
    // Fehlerzustand des Erstabrufs: solange er steht, darf die Suche ihn nicht wegzeichnen.
    var failed = false

    // Forward-reference break: `loadPage`'s own row callbacks need to call back into `refresh`,
    // defined further below -- same nullable-function-reference-var idiom `BoardMembershipScreen.kt`
    // establishes for its own cross-section refresh (Kotlin has no forward-referencing local funs).
    var refresh: (Boolean) -> Unit = {}

    fun renderList() {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): solange der erste Ladevorgang läuft, sagt allein
        // die Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld während
        // des Ladens den Leertext über den Ladehinweis -- „lädt" und „keine Daten" gleichzeitig.
        if (loading && loaded.isEmpty()) return
        // Der Fehlerkasten mit `Erneut versuchen` bleibt stehen (kein „Keine Ehrungen erfasst." darüber).
        if (failed) return
        listPanel.removeAll()
        if (loaded.isEmpty()) {
            countsLabel.content = ""
            listPanel.p(memberHonorsEmptyStateText(resolvedMemberDisplayName, categoryFilter)) { addCssClasses("text-muted") }
            return
        }
        val visible = filterMemberHonors(loaded, searchTerm)
        countsLabel.content =
            dataCountText(
                shown = visible.size,
                loaded = loaded.size,
                total = totalCount,
                hasMore = hasMore,
                filtered = searchTerm.isNotBlank(),
            )
        if (visible.isEmpty()) {
            listPanel.p(loadedSubsetNoMatchText(term = searchTerm.trim(), hasMore = hasMore)) { addCssClasses("text-muted") }
            return
        }
        listPanel.dataTable(
            columns = memberHonorColumns(showMemberColumn = requestedMemberId == null),
            rows = visible,
            actions = { actions, honor ->
                actions.renderHonorActions(honor = honor, members = { members }, onChanged = { refresh(true) })
            },
        )
    }

    fun loadPage(reset: Boolean) {
        if (reset) {
            generation++
            loadedOffset = 0
            totalCount = 0
            hasMore = false
            loaded.clear()
            listPanel.removeAll()
            countsLabel.content = ""
            loadMoreButton.hide()
            statusRegion.showLoading()
            loading = true
            failed = false
        }
        val mine = generation
        val requestOffset = loadedOffset
        val requestCategory = categoryFilter
        loadMoreButton.disabled = true
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IMemberHonorService>().listHonors(
                        memberId = requestedMemberId,
                        category = requestCategory,
                        limit = MemberHonorLimits.MAX_LIMIT,
                        offset = requestOffset,
                    )
                }
            if (mine != generation) return@launch // ein neuerer Ladevorgang hat übernommen
            statusRegion.clearStatus()
            loading = false
            loadMoreButton.disabled = false
            if (page == null) {
                loadMoreButton.hide()
                if (reset) {
                    failed = true
                    listPanel.dataErrorState(onRetry = { loadPage(true) })
                }
                return@launch
            }
            page.entries.firstOrNull()?.let { first ->
                if (requestedMemberId != null && resolvedMemberDisplayName == null) {
                    resolvedMemberDisplayName = first.memberDisplayName
                    pageHead.setSubtitle(resolvedMemberDisplayName)
                }
            }
            loaded += page.entries
            loadedOffset += page.entries.size
            totalCount = page.totalCount
            hasMore = loadedOffset < totalCount && page.entries.isNotEmpty()
            if (hasMore) loadMoreButton.show() else loadMoreButton.hide()
            renderList()
        }
    }
    loadMoreButton.onClick { loadPage(false) }

    refresh = { reset -> loadPage(reset) }

    categorySegmentHost.segmentedControl(
        options = listOf(null to tr("Alle")) + MemberHonorCategory.entries.map { it to memberHonorCategoryLabel(it) },
        selected = categoryFilter,
        ariaLabel = tr("Kategorie"),
    ) { category ->
        categoryFilter = category
        loadPage(true)
    }
    newHonorButton.onClick {
        openMemberHonorEditorDialog(
            existing = null,
            defaultMemberId = requestedMemberId,
            members = { members },
            onSaved = { refresh(true) },
        )
    }

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
                renderList()
            }, 300)
    }
    refresh(true)
}

/** Clientseitige Suche über die geladene Teilmenge (pur, siehe `MemberHonorsScreenTest`): Titel oder Mitglied. */
internal fun filterMemberHonors(
    honors: List<MemberHonorDto>,
    search: String,
): List<MemberHonorDto> {
    val term = search.trim()
    if (term.isEmpty()) return honors
    return honors.filter {
        it.title.contains(term, ignoreCase = true) || it.memberDisplayName.contains(term, ignoreCase = true)
    }
}

/**
 * Spalten der Ehrungstabelle / Kartenliste. In der ungefilterten Sicht ist das Mitglied die Identität
 * der Zeile (Kartentitel); in der auf ein Mitglied gefilterten Sicht entfällt die Spalte -- dann ist die
 * Ehrung selbst die Identität. Bewusst keine Sortierköpfe: `listHonors` sortiert serverseitig und kennt
 * keinen Sortierparameter, ein clientseitiges Sortieren würde nur die geladene Teilmenge umordnen und die
 * Serverordnung der nächsten Seite widersprechen.
 */
private fun memberHonorColumns(showMemberColumn: Boolean): List<DataColumn<MemberHonorDto>> =
    buildList {
        if (showMemberColumn) {
            add(textColumn(title = tr("Mitglied"), primary = true) { honor: MemberHonorDto -> honor.memberDisplayName })
        }
        add(textColumn(title = tr("Datum"), numeric = true) { honor: MemberHonorDto -> honor.awardedAt.toString() })
        add(
            DataColumn(
                title = tr("Ehrung"),
                primary = !showMemberColumn,
                cell = { container, honor ->
                    container.icon(memberHonorCategoryIcon(honor.category))
                    container.span(" ${honor.title} ")
                    container.typeBadge(memberHonorCategoryLabel(honor.category), memberHonorCategoryColor(honor.category))
                },
            ),
        )
        add(textColumn(title = tr("Verliehen durch")) { honor: MemberHonorDto -> honor.awardedBy.orEmpty() })
    }

/**
 * Zeilenaktionen. Gegenüber V1.4.4.3 unverändert: Bearbeiten für BOARD/ADMIN, Löschen **nur** für ADMIN
 * und für BOARD gar nicht gerendert (kein sichtbar toter Knopf), Bestätigungsdialog mit demselben Text.
 * Neu ist allein der Weg über [tableActionButton] -- dadurch tragen beide Knöpfe jetzt auch ein
 * `aria-label` (vorher nur `title`, R39).
 */
private fun Container.renderHonorActions(
    honor: MemberHonorDto,
    members: () -> List<MemberSummaryDto>,
    onChanged: () -> Unit,
) {
    val group = tableActionGroup()
    val editButton = group.tableActionButton("fas fa-pen", tr("Bearbeiten"), ButtonStyle.OUTLINEPRIMARY)
    editButton.onClick {
        openMemberHonorEditorDialog(existing = honor, defaultMemberId = null, members = members, onSaved = onChanged)
    }
    if (!AppState.hasRole(AccountRole.ADMIN)) return
    val deleteButton = group.tableActionButton("fas fa-trash", tr("Eintrag korrigieren (löschen)"), ButtonStyle.OUTLINEDANGER)
    deleteButton.onClick {
        confirmDialog(
            title = tr("Eintrag korrigieren (löschen)"),
            message =
                gettext(
                    "Diese Ehrung (%1) unwiderruflich löschen? Dies ist eine Datenkorrektur, keine " +
                        "Aberkennung -- für eine echte Aberkennung ist ein eigener Vorstandsbeschluss " +
                        "vorgesehen, keine Löschung dieses Eintrags.",
                    honor.title,
                ),
            confirmLabel = tr("Löschen"),
            onConfirm = {
                AppScope.launch {
                    val result = guarded { rpcService<IMemberHonorService>().deleteHonor(honor.id) }
                    if (result != null) {
                        notifySuccess(tr("Eintrag gelöscht."))
                        onChanged()
                    }
                }
            },
        )
    }
}

private fun openMemberHonorEditorDialog(
    existing: MemberHonorDto?,
    defaultMemberId: String?,
    members: () -> List<MemberSummaryDto>,
    onSaved: () -> Unit,
) {
    val modal =
        Modal(
            caption = if (existing == null) tr("Ehrung erfassen") else gettext("Ehrung bearbeiten -- %1", existing.title),
        )

    val memberOptions =
        run {
            val base = untrustedOptions(members().map { it.id to it.displayName })
            val currentId = existing?.memberId
            if (currentId != null && base.none { it.first == currentId }) {
                base + (currentId to sanitizeUntrustedI18nText(existing.memberDisplayName))
            } else {
                base
            }
        }
    val memberSelect =
        modal.select(
            options = memberOptions,
            value = existing?.memberId ?: defaultMemberId ?: memberOptions.firstOrNull()?.first,
            label = tr("Mitglied"),
        )
    val categorySelect =
        modal.select(
            options = MemberHonorCategory.entries.map { it.name to memberHonorCategoryLabel(it) },
            value = (existing?.category ?: MemberHonorCategory.SERVICE_AWARD).name,
            label = tr("Kategorie"),
        )
    val titleInput = modal.text(value = existing?.title, label = tr("Titel"))
    val awardedAtInput = modal.text(value = existing?.awardedAt?.toString() ?: todayIso(), label = tr("Verliehen am (JJJJ-MM-TT)"))
    val awardedByInput = modal.text(value = existing?.awardedBy, label = tr("Verliehen durch (optional)"))
    val noteInput = modal.textArea(value = existing?.note, label = tr("Notiz (optional)"), rows = 3)
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val saveButton = modal.button(if (existing == null) tr("Erfassen") else tr("Speichern"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val memberId = memberSelect.value
        val title = titleInput.value.orEmpty().trim()
        val awardedAt = runCatching { LocalDate.parse(awardedAtInput.value.orEmpty().trim()) }.getOrNull()
        val category = categorySelect.value?.let { runCatching { MemberHonorCategory.valueOf(it) }.getOrNull() }
        if (memberId == null || title.isEmpty() || awardedAt == null || category == null) {
            errorBox.content = tr("Bitte Mitglied, Titel, Kategorie und ein gültiges Datum (JJJJ-MM-TT) angeben.")
            errorBox.show()
            return@onClick
        }
        val input =
            MemberHonorInput(
                memberId = memberId,
                category = category,
                title = title,
                awardedAt = awardedAt,
                awardedBy = awardedByInput.value?.trim()?.takeIf { it.isNotBlank() },
                note = noteInput.value?.trim()?.takeIf { it.isNotBlank() },
            )
        AppScope.launch {
            val result =
                guarded {
                    if (existing == null) {
                        rpcService<IMemberHonorService>().createHonor(input)
                    } else {
                        rpcService<IMemberHonorService>().updateHonor(existing.id, input)
                    }
                }
            if (result != null) {
                notifySuccess(if (existing == null) tr("Ehrung erfasst.") else tr("Ehrung gespeichert."))
                modal.hide()
                onSaved()
            }
        }
    }
    modal.show()
}

internal fun memberHonorsRoute(memberId: String?): String =
    if (memberId == null) Routes.MEMBER_HONORS else "${Routes.MEMBER_HONORS}?member=$memberId"

internal fun memberHonorCategoryLabel(category: MemberHonorCategory): String =
    when (category) {
        HONORARY_MEMBERSHIP -> tr("Ehrenmitgliedschaft")
        SERVICE_AWARD -> tr("Verdienstauszeichnung")
        LOYALTY_AWARD -> tr("Treueauszeichnung")
        OTHER -> tr("Sonstige")
    }

internal fun memberHonorCategoryIcon(category: MemberHonorCategory): String =
    when (category) {
        HONORARY_MEMBERSHIP -> "fas fa-crown"
        SERVICE_AWARD -> "fas fa-medal"
        LOYALTY_AWARD -> "fas fa-hourglass-half"
        OTHER -> "fas fa-certificate"
    }

private fun memberHonorCategoryColor(category: MemberHonorCategory): String =
    when (category) {
        HONORARY_MEMBERSHIP -> "warning"
        SERVICE_AWARD -> "primary"
        LOYALTY_AWARD -> "info"
        OTHER -> "secondary"
    }

/**
 * Leertext der Ehrungsliste. Bei aktivem Kategorie-Segment sagt er „keine in dieser Kategorie" statt „noch
 * keine erfasst" -- sonst behauptet ein leeres Segment, es gäbe überhaupt keine Ehrung.
 */
internal fun memberHonorsEmptyStateText(
    memberDisplayName: String?,
    category: MemberHonorCategory? = null,
): String =
    if (category != null) {
        gettext("Keine Ehrung in der Kategorie \"%1\".", resolvedAttributeText(memberHonorCategoryLabel(category)))
    } else if (memberDisplayName == null) {
        tr("Keine Ehrungen erfasst.")
    } else {
        gettext("Für %1 sind noch keine Ehrungen erfasst.", memberDisplayName)
    }

/** Today's date as `JJJJ-MM-TT` -- mirrors `BoardMembershipScreen.todayIso`'s own `kotlin.time.Clock` idiom. */
private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

package network.lapis.cloud.client

import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
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
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberFamilyDetailDto
import network.lapis.cloud.shared.domain.MemberFamilyLimits
import network.lapis.cloud.shared.domain.MemberFamilySummaryDto
import network.lapis.cloud.shared.domain.UpcomingMajorityEntryDto
import network.lapis.cloud.shared.domain.UpcomingMajorityOverviewDto
import network.lapis.cloud.shared.rpc.IMemberFamilyService
import network.lapis.cloud.shared.rpc.IMemberService

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- UI/UX-Design-Team-
 * Entscheidungen:
 *
 * - **Abschnitt A (Volljährigkeits-Arbeitsliste) ganz oben, nur sichtbar wenn es überhaupt
 *   Angehörige im System gibt** (Kares Regel: wer nichts zu tun hat, sieht kein Pixel) -- innerhalb
 *   dieses Gates zeigt der Abschnitt auch einen "nichts im gewählten Zeitraum"-Hinweis statt
 *   komplett zu verschwinden, damit das Fenster-Preset weiterhin explorierbar bleibt.
 * - **Genau eine Aktion pro Zeile** ("Aus Familie lösen") -- kein Tarif-Zuweisungs-Shortcut hier;
 *   die Tarifzuordnung ist ein separater, bewusster Akt über den Editor in
 *   [MemberAdministrationScreen] ([IMemberService.updateMemberMembershipTier]).
 * - **Mitglieds-Picker nutzt `listMembersForAdministration`, NICHT `listMembers()`** -- letzteres
 *   liefert nur ACTIVE-Mitglieder und wäre bei ~400 Mitgliedern ein unbrauchbares `<select>` ohne
 *   Suchfunktion (Plan-Abweichung von [MemberHonorsScreen], dort begründet ausreichend).
 * - **Zahlerlose Familien mit Warn-Badge, oben in der Liste** -- Server liefert bereits
 *   entsprechend sortiert ([IMemberFamilyService.listFamilies]).
 * - **Löschen nur für ADMIN gerendert, nicht nur deaktiviert** -- Präzedenz [MemberHonorsScreen]/
 *   [CrmContactsScreen].
 *
 * The DOM-free helper functions below are the only pure surface of this screen -- see
 * `MemberFamiliesScreenTest`.
 */
fun renderMemberFamiliesScreen(
    container: SimplePanel,
    requestedFamilyId: String?,
) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }

    root.h1(tr("Familienmitgliedschaften"))
    root.div(
        tr(
            "Haushalts-/Familienverbünde für die Beitragsabrechnung -- ein Zahler, beliebig viele " +
                "beitragsfreie Angehörige.",
        ),
    ) { addCssClasses("text-muted small") }

    val majorityPanel = root.vPanel(spacing = 6)

    val searchRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val searchInput = searchRow.text(label = tr("Suche"))
    val newFamilyButton = searchRow.button(tr("Familie anlegen"), style = ButtonStyle.PRIMARY)

    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }
    var loadedOffset = 0
    var table: Table? = null

    var refreshFamilies: (Boolean) -> Unit = {}
    var refreshMajorities: () -> Unit = {}

    fun loadFamilyPage() {
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IMemberFamilyService>().listFamilies(
                        search = searchInput.value?.trim()?.takeIf { it.isNotBlank() },
                        limit = MemberFamilyLimits.MAX_LIMIT,
                        offset = loadedOffset,
                    )
                } ?: return@launch

            if (loadedOffset == 0) {
                listPanel.removeAll()
                table = null
            }
            if (page.entries.isEmpty() && loadedOffset == 0) {
                listPanel.p(familiesEmptyStateText())
                loadMoreButton.hide()
                return@launch
            }
            val currentTable =
                table ?: listPanel
                    .table(
                        headerNames = listOf(tr("Familie"), tr("Mitglieder"), tr("Zahler"), tr("Aktionen")),
                        types = setOf(TableType.STRIPED, TableType.HOVER),
                    ).also { table = it }
            page.entries.forEach { family ->
                renderFamilyRow(currentTable, family, onOpen = { openFamilyDetailDialog(family.id) { refreshFamilies(true) } })
            }
            loadedOffset += page.entries.size
            if (loadedOffset < page.totalCount) loadMoreButton.show() else loadMoreButton.hide()
        }
    }
    loadMoreButton.onClick { loadFamilyPage() }
    refreshFamilies = { reset ->
        if (reset) loadedOffset = 0
        loadFamilyPage()
    }
    // Review fix (Welle V1.4.4.4, LOW finding): KVision's `subscribe` invokes the observer
    // IMMEDIATELY with the field's current value on registration, not just on later user input --
    // same guard idiom `MemberAdministrationScreen.kt`'s `searchInput.subscribe {}` already
    // establishes (`isInitialSearchEvent`). Without it, this synthetic first call fired
    // `refreshFamilies(true)` a SECOND time on top of the explicit call at the bottom of this
    // function -- two identical `listFamilies` roundtrips per screen mount, and since
    // `loadFamilyPage` calls `listPanel.removeAll()` at `loadedOffset == 0`, an unlucky response
    // order could transiently render a doubly-populated or half-emptied table.
    var isInitialSearchEvent = true
    searchInput.subscribe {
        if (isInitialSearchEvent) {
            isInitialSearchEvent = false
            return@subscribe
        }
        refreshFamilies(true)
    }
    newFamilyButton.onClick { openCreateFamilyDialog { refreshFamilies(true) } }

    fun loadMajorities(windowDays: Int) {
        AppScope.launch {
            val overview = guarded { rpcService<IMemberFamilyService>().listUpcomingMajorities(windowDays = windowDays) } ?: return@launch
            renderMajoritySection(majorityPanel, overview) { newWindowDays ->
                if (newWindowDays != null) {
                    loadMajorities(newWindowDays)
                } else {
                    refreshMajorities()
                    refreshFamilies(true)
                }
            }
        }
    }
    refreshMajorities = { loadMajorities(AnniversaryCalendar.DEFAULT_WINDOW_DAYS) }

    if (requestedFamilyId != null) {
        openFamilyDetailDialog(requestedFamilyId) { refreshFamilies(true) }
    }

    refreshFamilies(true)
    refreshMajorities()
}

/**
 * Rebuilds [container] from scratch. Renders NOTHING at all when [overview.dependentCount] is 0
 * (Kares Regel) -- there is no Angehöriger anywhere in the system this list could ever be about.
 * Otherwise always renders the window-preset select (even if the current window has zero hits),
 * so the board can widen the window without the control itself vanishing.
 */
private fun renderMajoritySection(
    container: SimplePanel,
    overview: UpcomingMajorityOverviewDto,
    onAction: (newWindowDays: Int?) -> Unit,
) {
    container.removeAll()
    if (overview.dependentCount == 0) return

    container.h2(tr("Bald volljährig")) { addCssClass("h5") }

    val windowRow = container.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val windowSelect =
        windowRow.select(
            options = AnniversaryCalendar.WINDOW_PRESETS.map { it.toString() to gettext("%1 Tage", it) },
            value = overview.windowDays.toString(),
            label = tr("Zeitraum"),
        )
    // CRITICAL review fix (Welle V1.4.4.4): KVision's `subscribe` invokes the observer IMMEDIATELY
    // with the field's current value on registration -- verified against the real KVision 9.6.0
    // source (`Select.subscribe` -> `SelectInput.subscribe`: `observers += observer; observer(value)`).
    // `renderMajoritySection` runs on EVERY reload (including the one triggered from inside
    // `onAction` itself), building a fresh `windowSelect` each time -- without this guard, the
    // synthetic initial call re-invoked `onAction(overview.windowDays)`, which re-triggers
    // `loadMajorities` -> RPC -> `renderMajoritySection` -> new select -> synthetic subscribe fire
    // -> `onAction` again, forever (same guard idiom `MemberAdministrationScreen.kt`'s
    // `searchInput.subscribe {}` already establishes for the identical KVision behaviour).
    var isInitialWindowEvent = true
    windowSelect.subscribe { value ->
        if (isInitialWindowEvent) {
            isInitialWindowEvent = false
            return@subscribe
        }
        value?.toIntOrNull()?.let { onAction(it) }
    }

    majorityCoverageText(overview)?.let { text ->
        container.div(text) { addCssClasses("alert alert-secondary small") }
    }

    if (overview.entries.isEmpty()) {
        container.p(tr("Keine bevorstehenden Volljährigkeiten im gewählten Zeitraum."))
        return
    }

    val list = container.vPanel(spacing = 4)
    overview.entries.forEach { entry ->
        val row = list.hPanel(spacing = 10) { addCssClasses("align-items-center flex-wrap") }
        row.span(majorityRowLabel(entry)) {
            if (entry.alreadyMajor) addCssClass("text-danger") else addCssClass("text-body")
        }
        val removeButton = row.button(tr("Aus Familie lösen"), style = ButtonStyle.OUTLINESECONDARY)
        removeButton.onClick {
            confirmDialog(
                title = tr("Aus Familie lösen"),
                message =
                    gettext(
                        "%1 aus %2 lösen? Es ist danach kein Beitragstarif zugeordnet -- die Beitragspflicht " +
                            "entsteht erst, wenn ein Schatzmeister/Admin einen Tarif zuweist.",
                        entry.memberDisplayName,
                        entry.familyName,
                    ),
                confirmLabel = tr("Lösen"),
                onConfirm = {
                    AppScope.launch {
                        val result = guarded { rpcService<IMemberFamilyService>().removeFamilyMember(entry.linkId) }
                        if (result != null) {
                            notifySuccess(
                                tr(
                                    "Aus der Familie gelöst. Es ist noch kein Beitragstarif zugeordnet -- die " +
                                        "Beitragspflicht entsteht erst, wenn der Schatzmeister einen Tarif zuweist.",
                                ),
                            )
                            onAction(null)
                        }
                    }
                },
            )
        }
    }
}

private fun renderFamilyRow(
    table: Table,
    family: MemberFamilySummaryDto,
    onOpen: () -> Unit,
) {
    table.row {
        val nameCell = cell()
        nameCell.span(family.name) { addCssClass("fw-semibold") }
        if (!family.hasPayer) {
            nameCell.typeBadge(payerlessWarningText(), "warning")
        }
        cell(family.memberCount.toString())
        cell(family.payerDisplayName.orEmpty())
        val actionsCell = cell()
        val openButton = actionsCell.button("", icon = "fas fa-arrow-right", style = ButtonStyle.OUTLINEPRIMARY)
        openButton.title = tr("Öffnen")
        openButton.onClick { onOpen() }
    }
}

private fun openCreateFamilyDialog(onSaved: () -> Unit) {
    val modal = Modal(caption = tr("Familie anlegen"))
    val nameInput = modal.text(label = tr("Familienname"))
    val picker = modal.memberPicker(tr("Zahler"))
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val saveButton = modal.button(tr("Anlegen"), style = ButtonStyle.PRIMARY)
    saveButton.onClick {
        errorBox.hide()
        val name = nameInput.value.orEmpty().trim()
        val payerId = picker.selectedId()
        if (name.isEmpty() || payerId == null) {
            errorBox.content = tr("Bitte einen Familiennamen und einen Zahler angeben.")
            errorBox.show()
            return@onClick
        }
        AppScope.launch {
            val result = guarded { rpcService<IMemberFamilyService>().createFamily(name = name, payerMemberId = payerId) }
            if (result != null) {
                notifySuccess(tr("Familie angelegt."))
                modal.hide()
                onSaved()
            }
        }
    }
    modal.show()
}

private fun openFamilyDetailDialog(
    familyId: String,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = tr("Familie"))
    val body = modal.vPanel(spacing = 10)

    fun reload() {
        body.removeAll()
        AppScope.launch {
            val detail = guarded { rpcService<IMemberFamilyService>().getFamily(familyId) } ?: return@launch
            renderFamilyDetailBody(body, detail, onChanged = {
                reload()
                onChanged()
            }, onClosed = {
                modal.hide()
                onChanged()
            })
        }
    }
    reload()
    modal.show()
}

private fun renderFamilyDetailBody(
    body: SimplePanel,
    detail: MemberFamilyDetailDto,
    onChanged: () -> Unit,
    onClosed: () -> Unit,
) {
    val headerRow = body.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val nameInput = headerRow.text(value = detail.name, label = tr("Name"))
    val renameButton = headerRow.button(tr("Umbenennen"), style = ButtonStyle.OUTLINESECONDARY)
    renameButton.onClick {
        val newName = nameInput.value.orEmpty().trim()
        if (newName.isEmpty()) return@onClick
        AppScope.launch {
            val result = guarded { rpcService<IMemberFamilyService>().renameFamily(detail.id, newName) }
            if (result != null) {
                notifySuccess(tr("Familie umbenannt."))
                onChanged()
            }
        }
    }

    val table =
        body.table(
            headerNames = listOf(tr("Mitglied"), tr("Status"), tr("Rolle"), tr("Tarif"), tr("Aktionen")),
            types = setOf(TableType.STRIPED),
        )
    detail.links.forEach { link ->
        table.row {
            cell(link.memberDisplayName)
            cell { memberStatusRoleBadge(link.memberStatus) }
            cell { typeBadge(familyRoleLabel(link.role), familyRoleBadgeColor(link.role)) }
            cell(link.membershipTierName ?: tr("beitragsfrei"))
            val actionsCell = cell()
            if (link.role != FamilyMemberRole.PAYER) {
                val payerButton = actionsCell.button(tr("Als Zahler festlegen"), style = ButtonStyle.OUTLINESECONDARY)
                payerButton.onClick {
                    AppScope.launch {
                        val result = guarded { rpcService<IMemberFamilyService>().changePayer(detail.id, link.memberId) }
                        if (result != null) {
                            notifySuccess(tr("Zahler geändert."))
                            onChanged()
                        }
                    }
                }
            }
            val removeButton = actionsCell.button("", icon = "fas fa-user-minus", style = ButtonStyle.OUTLINEDANGER)
            removeButton.title = tr("Entfernen")
            removeButton.onClick {
                confirmDialog(
                    title = tr("Mitglied entfernen"),
                    message = gettext("%1 aus dieser Familie entfernen?", link.memberDisplayName),
                    confirmLabel = tr("Entfernen"),
                    onConfirm = {
                        AppScope.launch {
                            val result = guarded { rpcService<IMemberFamilyService>().removeFamilyMember(link.id) }
                            if (result != null) {
                                notifySuccess(tr("Mitglied entfernt."))
                                onChanged()
                            }
                        }
                    },
                )
            }
        }
    }

    body.div { addCssClass("mt-2") }
    body.h2(tr("Mitglied hinzufügen")) { addCssClass("h6") }
    val addRow = body.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val addPicker = addRow.memberPicker(tr("Mitglied"))
    val addButton = addRow.button(tr("Hinzufügen"), style = ButtonStyle.PRIMARY)
    addButton.onClick {
        val memberId = addPicker.selectedId() ?: return@onClick
        AppScope.launch {
            val result = guarded { rpcService<IMemberFamilyService>().addFamilyMember(detail.id, memberId) }
            if (result != null) {
                notifySuccess(tr("Mitglied hinzugefügt."))
                onChanged()
            }
        }
    }

    body.div { addCssClass("mt-3") }
    val footerRow = body.hPanel(spacing = 8)
    if (AppState.hasRole(AccountRole.ADMIN)) {
        val deleteButton = footerRow.button(tr("Familie löschen"), style = ButtonStyle.OUTLINEDANGER)
        deleteButton.onClick {
            confirmDialog(
                title = tr("Familie löschen"),
                message = gettext("Familie %1 samt aller Verknüpfungen unwiderruflich löschen?", detail.name),
                confirmLabel = tr("Löschen"),
                onConfirm = {
                    AppScope.launch {
                        val succeeded =
                            guarded {
                                rpcService<IMemberFamilyService>().deleteFamily(detail.id)
                                true
                            } ?: false
                        if (succeeded) {
                            notifySuccess(tr("Familie gelöscht."))
                            onClosed()
                        }
                    }
                },
            )
        }
    }
    footerRow.button(tr("Schließen"), style = ButtonStyle.SECONDARY).onClick { onClosed() }
}

/**
 * Minimal search-driven member picker -- text input + a [Select] populated on demand via
 * [IMemberService.listMembersForAdministration]. Deliberately NOT [IMemberService.listMembers]
 * (ACTIVE-only, no search) -- see file KDoc.
 */
private class MemberPickerHandle(
    val select: Select,
) {
    fun selectedId(): String? = select.value
}

private fun SimplePanel.memberPicker(labelText: String): MemberPickerHandle {
    val row = hPanel(spacing = 6) { addCssClasses("align-items-end flex-wrap") }
    val searchInput = row.text(label = gettext("%1 suchen", labelText))
    val select = row.select(options = emptyList(), label = labelText)
    val searchButton = row.button(tr("Suchen"), style = ButtonStyle.OUTLINESECONDARY)

    fun runSearch() {
        val term = searchInput.value?.trim()
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IMemberService>().listMembersForAdministration(
                        MemberAdminQuery(search = term?.takeIf { it.isNotBlank() }, limit = 20),
                    )
                } ?: return@launch
            select.options = page.rows.map { it.id to gettext("%1 (%2)", it.displayName, it.email) }
        }
    }
    searchButton.onClick { runSearch() }
    runSearch()
    return MemberPickerHandle(select)
}

internal fun memberFamiliesRoute(familyId: String?): String =
    if (familyId == null) Routes.MEMBER_FAMILIES else "${Routes.MEMBER_FAMILIES}?family=$familyId"

internal fun familyRoleLabel(role: FamilyMemberRole): String =
    when (role) {
        FamilyMemberRole.PAYER -> tr("Zahler")
        FamilyMemberRole.DEPENDENT -> tr("Angehörige/r")
    }

internal fun familyRoleBadgeColor(role: FamilyMemberRole): String =
    when (role) {
        FamilyMemberRole.PAYER -> "primary"
        FamilyMemberRole.DEPENDENT -> "secondary"
    }

internal fun familyRosterBadgeText(
    familyName: String,
    role: FamilyMemberRole?,
): String =
    when (role) {
        FamilyMemberRole.PAYER -> gettext("Familie %1 · Zahler", familyName)
        FamilyMemberRole.DEPENDENT -> gettext("Familie %1 · beitragsfrei", familyName)
        null -> familyName
    }

internal fun payerlessWarningText(): String = tr("Kein Zahler -- bitte zuweisen")

internal fun familiesEmptyStateText(): String = tr("Keine Familien angelegt.")

internal fun majorityRowLabel(entry: UpcomingMajorityEntryDto): String =
    if (entry.alreadyMajor) {
        gettext("%1 · %2 · bereits volljährig seit %3", entry.memberDisplayName, entry.familyName, entry.turnsMajorOn.toString())
    } else {
        gettext("%1 · %2 · wird volljährig am %3", entry.memberDisplayName, entry.familyName, entry.turnsMajorOn.toString())
    }

/** `null` when every dependent already has a recorded birthdate -- the mandatory coverage line otherwise. */
internal fun majorityCoverageText(overview: UpcomingMajorityOverviewDto): String? =
    if (overview.dependentsWithoutDateOfBirth <= 0) {
        null
    } else {
        gettext(
            "%1 von %2 Angehörigen haben kein Geburtsdatum hinterlegt -- für sie kann die Volljährigkeit nicht berechnet werden.",
            overview.dependentsWithoutDateOfBirth,
            overview.dependentCount,
        )
    }

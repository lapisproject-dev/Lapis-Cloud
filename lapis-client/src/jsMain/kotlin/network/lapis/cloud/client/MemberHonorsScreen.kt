package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.icon
import io.kvision.html.link
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
 * [memberHonorCategoryIcon]/[memberHonorsHeading]/[memberHonorsEmptyStateText]) are the only pure
 * surface of this screen -- see `MemberHonorsScreenTest`.
 */
fun renderMemberHonorsScreen(
    container: SimplePanel,
    requestedMemberId: String?,
) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }

    val headingPanel = root.vPanel(spacing = 2)
    val headingText = headingPanel.h1(memberHonorsHeading(null))
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

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val categoryOptions =
        listOf("" to tr("-- Alle Kategorien --")) + MemberHonorCategory.entries.map { it.name to memberHonorCategoryLabel(it) }
    val categorySelect = filterRow.select(options = categoryOptions, value = "", label = tr("Kategorie"))
    val newHonorButton = filterRow.button(tr("Ehrung erfassen"), style = ButtonStyle.PRIMARY)

    var members: List<MemberSummaryDto> = emptyList()
    AppScope.launch {
        members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
    }

    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }
    var loadedOffset = 0
    var resolvedMemberDisplayName: String? = null
    var table: Table? = null

    fun categoryFilter(): MemberHonorCategory? = runCatching { MemberHonorCategory.valueOf(categorySelect.value.orEmpty()) }.getOrNull()

    // Forward-reference break: `loadPage`'s own row callbacks need to call back into `refresh`,
    // defined further below -- same nullable-function-reference-var idiom `BoardMembershipScreen.kt`
    // establishes for its own cross-section refresh (Kotlin has no forward-referencing local funs).
    var refresh: (Boolean) -> Unit = {}

    fun loadPage() {
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IMemberHonorService>().listHonors(
                        memberId = requestedMemberId,
                        category = categoryFilter(),
                        limit = MemberHonorLimits.MAX_LIMIT,
                        offset = loadedOffset,
                    )
                } ?: return@launch

            if (loadedOffset == 0) {
                listPanel.removeAll()
                table = null
            }

            if (page.entries.isEmpty() && loadedOffset == 0) {
                listPanel.p(memberHonorsEmptyStateText(resolvedMemberDisplayName))
                loadMoreButton.hide()
                return@launch
            }

            page.entries.firstOrNull()?.let { first ->
                if (requestedMemberId != null && resolvedMemberDisplayName == null) {
                    resolvedMemberDisplayName = first.memberDisplayName
                    headingText.content = memberHonorsHeading(resolvedMemberDisplayName)
                }
            }

            val currentTable =
                table ?: listPanel
                    .table(
                        headerNames =
                            if (requestedMemberId == null) {
                                listOf(tr("Datum"), tr("Mitglied"), tr("Ehrung"), tr("Verliehen durch"), tr("Aktionen"))
                            } else {
                                listOf(tr("Datum"), tr("Ehrung"), tr("Verliehen durch"), tr("Aktionen"))
                            },
                        types = setOf(TableType.STRIPED, TableType.HOVER),
                    ).also { table = it }

            page.entries.forEach { honor ->
                renderHonorRow(
                    table = currentTable,
                    honor = honor,
                    showMemberColumn = requestedMemberId == null,
                    members = { members },
                    onChanged = { refresh(true) },
                )
            }
            loadedOffset += page.entries.size
            if (loadedOffset < page.totalCount) loadMoreButton.show() else loadMoreButton.hide()
        }
    }
    loadMoreButton.onClick { loadPage() }

    refresh = { reset ->
        if (reset) loadedOffset = 0
        loadPage()
    }

    categorySelect.subscribe { refresh(true) }
    newHonorButton.onClick {
        openMemberHonorEditorDialog(
            existing = null,
            defaultMemberId = requestedMemberId,
            members = { members },
            onSaved = { refresh(true) },
        )
    }
    refresh(true)
}

private fun renderHonorRow(
    table: Table,
    honor: MemberHonorDto,
    showMemberColumn: Boolean,
    members: () -> List<MemberSummaryDto>,
    onChanged: () -> Unit,
) {
    table.row {
        cell(honor.awardedAt.toString())
        if (showMemberColumn) cell(honor.memberDisplayName)
        val honorCell = cell()
        honorCell.icon(memberHonorCategoryIcon(honor.category))
        honorCell.span(" ${honor.title} ")
        honorCell.typeBadge(memberHonorCategoryLabel(honor.category), memberHonorCategoryColor(honor.category))
        cell(honor.awardedBy.orEmpty())
        val actionsCell = cell()
        val editButton = actionsCell.button("", icon = "fas fa-pen", style = ButtonStyle.OUTLINEPRIMARY)
        editButton.title = tr("Bearbeiten")
        editButton.onClick {
            openMemberHonorEditorDialog(existing = honor, defaultMemberId = null, members = members, onSaved = onChanged)
        }
        if (AppState.hasRole(AccountRole.ADMIN)) {
            val deleteButton = actionsCell.button("", icon = "fas fa-trash", style = ButtonStyle.OUTLINEDANGER)
            deleteButton.title = tr("Eintrag korrigieren (löschen)")
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
            val base = members().map { it.id to it.displayName }
            val currentId = existing?.memberId
            if (currentId != null && base.none { it.first == currentId }) {
                base + (currentId to existing.memberDisplayName)
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

internal fun memberHonorsHeading(memberDisplayName: String?): String =
    if (memberDisplayName == null) tr("Ehrungen & Auszeichnungen") else gettext("Ehrungen · %1", memberDisplayName)

internal fun memberHonorsEmptyStateText(memberDisplayName: String?): String =
    if (memberDisplayName == null) {
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

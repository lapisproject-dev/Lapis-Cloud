package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.LupaBbApplyResultDto
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewPageDto
import network.lapis.cloud.shared.domain.LupaBbPreviewRowDto
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto
import network.lapis.cloud.shared.rpc.LupaBbPreviewExpiredException

private const val PAGE_SIZE = 50

/** The four calls of the preview block; the screen owns the RPC reads (and their loading state), this block only decides what to show. */
internal interface LupaSyncApi {
    suspend fun loadPreview(): LupaBbPreviewSummaryDto

    suspend fun previewPage(
        token: String,
        filter: LupaBbPreviewFilter,
        offset: Int,
        limit: Int,
    ): LupaBbPreviewPageDto

    suspend fun apply(token: String): LupaBbApplyResultDto

    suspend fun link(
        token: String,
        personNumber: Long,
        memberId: String,
    ): LupaBbPreviewPageDto
}

/**
 * V1.9.226 -- the comparison block of the sync page: load the preview (one fetch from LupaBB), look at it by class, decide ambiguous records
 * by hand, apply. The rows come page by page from the server (`getPreviewPage`); only the token is held here. Nothing is written before
 * "Übernehmen", and an expired token (15 minutes) offers "Vorschau neu laden".
 */
internal class LupaSyncPreview(
    private val root: Container,
    private val api: LupaSyncApi,
    private val statusProvider: () -> LupaBbSyncStatusDto?,
    private val onApplied: () -> Unit,
) {
    private val host: SimplePanel = root.vPanel(spacing = 10)
    private var summary: LupaBbPreviewSummaryDto? = null
    private var counts: Map<LupaBbCategory, Int> = emptyMap()
    private var filter: LupaBbPreviewFilter = LupaBbPreviewFilter.ALL
    private var rows: List<LupaBbPreviewRowDto> = emptyList()
    private var total = 0
    private var expired = false
    private var applied: LupaBbApplyResultDto? = null
    private var busy = false

    fun paint() {
        host.removeAll()
        host.h2(tr("Abgleich")) { addCssClass("h5") }
        host.div(
            tr(
                "Der Abgleich liest nur aus LupaBB. Er löscht und deaktiviert niemanden, legt keine Anmeldekonten an und versendet keine " +
                    "E-Mails. Die E-Mail-Adresse bestehender Mitglieder bleibt unverändert.",
            ),
        ) { addCssClass("text-muted") }
        statusProvider()?.lastRun?.let { last ->
            host.div(
                gettext(
                    "Letzter Lauf am %1: %2 angelegt, %3 aktualisiert, %4 Fehler.",
                    formatSystemDateTime(last.finishedAt),
                    last.created,
                    last.updated,
                    last.errors,
                ),
            ) { addCssClasses("text-muted small") }
        }
        applied?.let { paintResult(it) }
        val current = summary
        val load =
            host.actionButton(
                ActionIcon.VIEW,
                if (current ==
                    null
                ) {
                    tr("Vorschau laden")
                } else {
                    tr("Vorschau neu laden")
                },
                style = ButtonStyle.PRIMARY,
            )
        load.disabled = busy || !LupaSyncLogic.canLoadPreview(statusProvider())
        load.onClick { loadPreview() }
        if (!LupaSyncLogic.canLoadPreview(statusProvider())) {
            host.div(tr("Bitte zuerst die Zugangsdaten eingeben.")) { addCssClasses("text-muted small") }
        }
        if (expired) {
            host.div(tr("Die Vorschau ist abgelaufen. Bitte laden Sie sie neu.")) {
                addCssClasses("alert alert-warning mb-0")
                setAttribute("role", "status")
            }
            return
        }
        if (current != null) paintPreview(current)
    }

    private fun loadPreview() {
        busy = true
        applied = null
        paint()
        AppScope.launch {
            val result = guarded { api.loadPreview() }
            busy = false
            expired = false
            summary = result
            if (result != null) {
                counts = result.counts
                filter = LupaSyncLogic.defaultFilter(result.counts)
                loadPage(0, replace = true)
            } else {
                paint()
            }
        }
    }

    private fun loadPage(
        offset: Int,
        replace: Boolean,
    ) {
        val token = summary?.token ?: return
        val wanted = filter
        AppScope.launch {
            val page =
                guarded {
                    try {
                        api.previewPage(token, wanted, offset, PAGE_SIZE)
                    } catch (e: LupaBbPreviewExpiredException) {
                        expired = true
                        throw e
                    }
                }
            if (page != null) {
                rows = if (replace) page.rows else rows + page.rows
                total = page.total
                if (page.counts.isNotEmpty()) counts = page.counts
            }
            paint()
        }
    }

    private fun paintPreview(current: LupaBbPreviewSummaryDto) {
        current.notices.forEach { host.div(lupaNoticeText(it)) { addCssClasses("alert alert-info mb-0") } }
        host.div(
            gettext(
                "%1 neu · %2 geändert · %3 unverändert · %4 unklar · %5 übersprungen · %6 Fehler · %7 nicht mehr in LupaBB",
                counts[LupaBbCategory.NEW] ?: 0,
                counts[LupaBbCategory.CHANGED] ?: 0,
                counts[LupaBbCategory.UNCHANGED] ?: 0,
                counts[LupaBbCategory.AMBIGUOUS] ?: 0,
                counts[LupaBbCategory.SKIPPED] ?: 0,
                counts[LupaBbCategory.ERROR] ?: 0,
                counts[LupaBbCategory.MISSING] ?: 0,
            ),
        ) {
            addCssClass("fw-bold")
            setAttribute("role", "status")
        }
        host.segmentedScroll {
            segmentedControl(
                options = LupaSyncLogic.filterOptions(counts),
                selected = filter,
                ariaLabel = tr("Datensätze filtern"),
            ) {
                filter = it
                loadPage(0, replace = true)
            }
        }
        if (rows.isEmpty()) {
            host.div(tr("Keine Datensätze in dieser Ansicht.")) { addCssClass("text-muted") }
        } else {
            host.dataTable(
                columns = lupaColumns(),
                rows = rows,
                actions = { container, row -> lupaRowActions(container, row) },
            )
            if (rows.size < total) {
                host.actionButton(ActionIcon.NEXT, tr("Weitere laden")) { onClick { loadPage(rows.size, replace = false) } }
            }
        }
        val apply = host.actionButton(ActionIcon.SAVE, LupaSyncLogic.applyLabel(current), style = ButtonStyle.PRIMARY)
        apply.disabled = busy || !LupaSyncLogic.canApply(current)
        apply.onClick { confirmThenApply(current) }
    }

    private fun confirmThenApply(current: LupaBbPreviewSummaryDto) {
        if (!LupaSyncLogic.needsConfirmation(current)) {
            apply(current)
            return
        }
        confirmDialog(
            title = tr("Statuswechsel übernehmen?"),
            message = gettext("Der Abgleich ändert bei %1 Mitgliedern den Status.", current.statusChangeCount),
            extraLines =
                listOfNotNull(
                    if (current.severeStatusChangeCount > 0) {
                        gettext(
                            "Bei %1 davon hat das Folgen für die Mitgliedschaft (Austritt, Tod oder Rückstufung).",
                            current.severeStatusChangeCount,
                        )
                    } else {
                        null
                    },
                ),
            confirmLabel = tr("Übernehmen"),
            confirmIcon = ActionIcon.SAVE,
            confirmStyle = ButtonStyle.PRIMARY,
            onConfirm = { apply(current) },
        )
    }

    private fun apply(current: LupaBbPreviewSummaryDto) {
        busy = true
        paint()
        AppScope.launch {
            val result =
                guarded {
                    try {
                        api.apply(current.token)
                    } catch (e: LupaBbPreviewExpiredException) {
                        expired = true
                        throw e
                    }
                }
            busy = false
            if (result != null) {
                applied = result
                summary = null
                rows = emptyList()
                notifySuccess(tr("Der Abgleich wurde übernommen."))
                onApplied()
            }
            paint()
        }
    }

    private fun paintResult(result: LupaBbApplyResultDto) {
        host.div(
            gettext(
                "%1 angelegt · %2 aktualisiert · %3 unverändert · %4 übersprungen · %5 unklar · %6 Fehler · %7 als nicht mehr in LupaBB markiert",
                result.created,
                result.updated,
                result.unchanged,
                result.skipped,
                result.ambiguous,
                result.errors,
                result.missing,
            ),
        ) {
            addCssClasses("alert alert-success mb-0")
            setAttribute("role", "status")
        }
        if (result.failures.isNotEmpty()) {
            host.div(tr("Nicht gespeichert:")) { addCssClass("fw-bold") }
            result.failures.forEach { failure ->
                host.div {
                    span(gettext("Person %1", failure.personNumber))
                    span(": ")
                    span(lupaReasonText(failure.code))
                }
            }
        }
    }

    private fun lupaColumns(): List<DataColumn<LupaBbPreviewRowDto>> =
        listOf(
            textColumn<LupaBbPreviewRowDto>(title = tr("Personennummer"), numeric = true) {
                it.personNumber
                    .takeIf { n ->
                        n > 0
                    }?.toString()
                    .orEmpty()
            },
            textColumn<LupaBbPreviewRowDto>(title = tr("Name"), primary = true) { it.name },
            textColumn<LupaBbPreviewRowDto>(title = tr("E-Mail"), cssClasses = "text-muted small") { it.email.orEmpty() },
            DataColumn(
                title = tr("Einordnung"),
                cell = { cell, row ->
                    cell.span("") { addCssClasses(lupaCategoryCss(row.category)) }.also {
                        it.span(sanitizeUntrustedI18nText(lupaCategorySymbol(row.category))) { setAttribute("aria-hidden", "true") }
                        it.span(" ")
                        it.span(lupaCategoryLabel(row.category))
                    }
                },
            ),
            DataColumn(title = tr("Einzelheiten"), cell = { cell, row -> lupaDetails(cell, row) }),
        )

    /** The reasons in words and, for a record with differences, an expandable "field | before | after" comparison. */
    private fun lupaDetails(
        cell: Container,
        row: LupaBbPreviewRowDto,
    ) {
        row.reasons.forEach { reason ->
            cell.div {
                span(lupaReasonText(reason.code))
                reason.param?.let { param ->
                    span(" ")
                    span(sanitizeUntrustedI18nText(param)) { addCssClasses("text-muted small") }
                }
            }
        }
        row.candidates.forEach { candidate ->
            cell.div {
                addCssClasses("small")
                span(tr("Kandidat"))
                span(": ")
                span(sanitizeUntrustedI18nText(candidate.name))
                // several candidates often share a name: the (unique) e-mail address is what tells them apart
                span(" (${sanitizeUntrustedI18nText(candidate.email)})") { addCssClasses("text-muted") }
            }
        }
        if (row.diffs.isEmpty()) return
        val details = Tag(TAG.DETAILS, className = "small")
        details.add(Tag(TAG.SUMMARY, content = gettext("%1 Felder im Vergleich", row.diffs.size)))
        val table = Tag(TAG.TABLE, className = "lapis-lupabb-diff")
        val head = Tag(TAG.THEAD)
        val headRow = Tag(TAG.TR)
        listOf(tr("Feld"), tr("bisher"), tr("neu")).forEach { headRow.add(Tag(TAG.TH, content = it)) }
        head.add(headRow)
        table.add(head)
        val body = Tag(TAG.TBODY)
        row.diffs.forEach { diff ->
            val tableRow = Tag(TAG.TR)
            tableRow.add(Tag(TAG.TD, content = lupaFieldLabel(diff.field)))
            tableRow.add(
                Tag(
                    TAG.TD,
                    content = sanitizeUntrustedI18nText(lupaDiffValue(diff.field, diff.before)),
                    className = "lapis-lupabb-diff-old",
                ),
            )
            tableRow.add(Tag(TAG.TD, content = sanitizeUntrustedI18nText(lupaDiffValue(diff.field, diff.after))))
            body.add(tableRow)
        }
        table.add(body)
        details.add(table)
        cell.add(details)
    }

    private fun lupaRowActions(
        container: Container,
        row: LupaBbPreviewRowDto,
    ) {
        if (row.category != LupaBbCategory.AMBIGUOUS) return
        row.candidates.forEach { candidate ->
            val who = "${sanitizeUntrustedI18nText(candidate.name)} (${sanitizeUntrustedI18nText(candidate.email)})"
            val label = gettext("Mit diesem Mitglied verknüpfen") + ": " + who
            container.actionButton(ActionIcon.LINK, label, iconOnly = true, small = true, accessibleName = label) {
                title = resolvedAttributeText(label)
                onClick {
                    confirmDialog(
                        title = tr("Mit diesem Mitglied verknüpfen?"),
                        message =
                            tr(
                                "Die Personennummer aus LupaBB wird diesem Mitglied zugeordnet. Die Daten werden beim nächsten Abgleich übernommen.",
                            ),
                        extraLines = listOf(sanitizeUntrustedI18nText(candidate.name), sanitizeUntrustedI18nText(candidate.email)),
                        confirmLabel = tr("Verknüpfen"),
                        confirmIcon = ActionIcon.LINK,
                        confirmStyle = ButtonStyle.PRIMARY,
                    ) { link(row, candidate.memberId) }
                }
            }
        }
    }

    private fun link(
        row: LupaBbPreviewRowDto,
        memberId: String,
    ) {
        val token = summary?.token ?: return
        AppScope.launch {
            val page =
                guarded {
                    try {
                        api.link(token, row.personNumber, memberId)
                    } catch (e: LupaBbPreviewExpiredException) {
                        expired = true
                        throw e
                    }
                }
            if (page != null) {
                notifySuccess(tr("Das Mitglied wurde verknüpft."))
                counts = page.counts.ifEmpty { counts }
                loadPage(0, replace = true)
            } else {
                paint()
            }
        }
    }
}

package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.TextArea
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgExtensionTemplate
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgVersionDeletionPreviewDto
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto
import network.lapis.cloud.shared.domain.PartgVersionRetention
import network.lapis.cloud.shared.domain.PartgVersionStatus

private class VersionsLoad(
    val overview: PartgVersionOverviewDto,
    val units: List<PartgUnitDto>,
)

/**
 * V1.9.291 (R6) "Versionen und Freigabe" -- BOARD and ADMIN only (the server refuses everybody else). One page, fixed order: what a version is, the
 * status of the year and the deadline (with a template for a request to extend it), the kept versions as cards (status as symbol AND word, the footer
 * of their export, downloads, "Versichern" / "Final setzen" for the whole-party versions), the comparison, and -- for an ADMIN only -- the deletion
 * routine with a preview.
 *
 * Everything here is a DRAFT aid. "Intern final" is an internal mark: the texts never say that the report was filed or approved by anyone else.
 */
internal fun SimplePanel.renderPartyReportVersions(context: PartyReportContext) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    div(className = "alert alert-warning mb-0") {
        setAttribute("role", "note")
        div(
            tr(
                "Eine festgehaltene Version ist ein unveränderlicher Stand des Entwurfs, mit Prüfsumme. " +
                    "Sie ist als Arbeitshilfe gedacht und " +
                    "kein eingereichter Bericht. Die Zahlen einer Version bleiben, das Aussehen einer Datei kann sich nach einem Update ändern.",
            ),
        )
    }
    val live = div { setAttribute("aria-live", "polite") }
    live.addCssClasses("small text-muted")
    lateinit var section: DataSection
    section =
        dataSection<VersionsLoad>(
            isEmpty = { false },
            load = {
                guarded {
                    val rpc = partyReportRpc()
                    VersionsLoad(overview = rpc.versionOverview(context.year), units = rpc.listUnits())
                }
            },
            render = {
                body,
                loaded,
                ->
                body.renderVersionsBody(context = context, loaded = loaded, live = live, reload = { section.reload() })
            },
        )
    section.reload()
}

private fun SimplePanel.renderVersionsBody(
    context: PartyReportContext,
    loaded: VersionsLoad,
    live: Div,
    reload: () -> Unit,
) {
    val overview = loaded.overview
    val changed: (String) -> Unit = { message ->
        live.content = message
        context.onSaved()
        reload()
    }
    renderYearBlock(context = context, overview = overview, onChanged = changed)
    actionGroup {
        actionButton(ActionIcon.KEEP_VERSION, tr("Version festhalten"), ButtonStyle.PRIMARY) {
            id = "partg-keep-version"
            onClick {
                openKeepVersionDialog(context, loaded.units) { kept ->
                    changed(gettext("Version %1 wurde festgehalten.", kept.versionNumber.toString()))
                }
            }
        }
    }
    renderVersionCards(overview = overview, units = loaded.units, onChanged = changed)
    renderVersionCompare(versions = overview.versions, units = loaded.units)
    if (AppState.session?.role == AccountRole.ADMIN) renderDeletionBlock(onChanged = changed)
}

private fun SimplePanel.renderYearBlock(
    context: PartyReportContext,
    overview: PartgVersionOverviewDto,
    onChanged: (String) -> Unit,
) {
    div(tr("Stand und Frist"), className = "fw-bold mt-2") { setAttribute("data-partg-year-block", "1") }
    val status = partgVersionStatusLook(overview.yearStatus)
    div(className = "d-flex flex-wrap align-items-center gap-2") {
        span(className = "badge rounded-pill text-bg-${status.color}") {
            icon(status.icon).setAttribute("aria-hidden", "true")
            span(" " + status.word)
        }
        span(partgDeadlineText(overview.deadline)).setAttribute("data-partg-deadline", "1")
        partgDeadlineLook(overview.deadline.level)?.let { look ->
            span(className = "badge rounded-pill text-bg-${look.color}") {
                icon(look.icon).setAttribute("aria-hidden", "true")
                span(" " + look.word)
            }
        }
    }
    partgDeviationText(overview.draftDeviation)?.let { div(it, className = "small text-warning-emphasis") }
    actionGroup {
        actionButton(ActionIcon.EDIT, tr("Frist bestätigen"), ButtonStyle.OUTLINEPRIMARY) {
            id = "partg-deadline-edit"
            onClick {
                openDeadlineDialog(context, overview.deadline) { saved ->
                    onChanged(gettext("Frist bestätigt: %1", partgGermanDate(saved.deadline)))
                }
            }
        }
    }
    renderExtensionTemplate(context = context, overview = overview)
}

/** A text to start from; nothing is stored or sent, there is no recipient field. */
private fun SimplePanel.renderExtensionTemplate(
    context: PartyReportContext,
    overview: PartgVersionOverviewDto,
) {
    val details = io.kvision.html.Tag(io.kvision.html.TAG.DETAILS, className = "small border rounded p-2 mb-2")
    details.setAttribute("data-partg-extension", "1")
    details.add(
        io.kvision.html.Tag(io.kvision.html.TAG.SUMMARY, content = tr("Vorlage: Antrag auf Fristverlängerung"), className = "fw-semibold"),
    )
    val partyName = tr("[Name der Partei]")
    val area =
        TextArea(
            value = PartgExtensionTemplate.text(partyName = partyName, year = context.year, deadline = overview.deadline.deadline),
            rows = 10,
            label = tr("Text zum Kopieren"),
        )
    area.readonly = true
    area.id = "partg-extension-text"
    details.add(area)
    details.add(
        newActionButton(ActionIcon.COPY, tr("Text kopieren"), ButtonStyle.OUTLINESECONDARY, small = true).apply {
            onClick {
                val clipboard: dynamic = window.navigator.asDynamic().clipboard
                if (clipboard != null && clipboard != undefined) {
                    clipboard.writeText(area.value.orEmpty())
                    notifySuccess(tr("Text kopiert."))
                }
            }
        },
    )
    add(details)
}

private fun SimplePanel.renderVersionCards(
    overview: PartgVersionOverviewDto,
    units: List<PartgUnitDto>,
    onChanged: (String) -> Unit,
) {
    div(tr("Festgehaltene Versionen"), className = "fw-bold mt-3") { setAttribute("data-partg-versions", "1") }
    if (overview.versions.isEmpty()) {
        div(tr("Noch keine Version festgehalten."), className = "small text-muted")
        return
    }
    overview.versions.forEach { version -> renderVersionCard(version = version, units = units, onChanged = onChanged) }
}

private fun SimplePanel.renderVersionCard(
    version: PartgVersionDto,
    units: List<PartgUnitDto>,
    onChanged: (String) -> Unit,
) {
    val look = partgVersionStatusLook(version.status)
    div(className = "card mb-2") {
        setAttribute("data-partg-version", version.id)
        div(className = "card-body") {
            div(className = "d-flex flex-wrap align-items-center gap-2") {
                span(gettext("Version %1", version.versionNumber.toString()), className = "fw-semibold")
                span(partgVersionScopeText(version.scopeKey, units), className = "text-muted")
                span(className = "badge rounded-pill text-bg-${look.color}") {
                    icon(look.icon).setAttribute("aria-hidden", "true")
                    span(" " + look.word)
                }
            }
            untrustedDiv(
                gettext("Festgehalten von %1 am %2", version.frozenByName, formatSystemDateTime(version.frozenAt)),
                className = "small text-muted",
            )
            version.comment?.let { untrustedDiv(it, className = "small") }
            if (version.declaredAt != null) {
                untrustedDiv(
                    gettext(
                        "Versichert von %1 am %2",
                        version.declaredByName.orEmpty(),
                        formatSystemDateTime(checkNotNull(version.declaredAt)),
                    ),
                    className = "small text-muted",
                )
            }
            if (version.finalizedAt != null) {
                untrustedDiv(
                    gettext(
                        "Intern final gesetzt von %1 am %2",
                        version.finalizedByName.orEmpty(),
                        formatSystemDateTime(checkNotNull(version.finalizedAt)),
                    ),
                    className = "small text-muted",
                )
            }
            if (version.hasThirdPartyData) {
                div(tr("Enthält Namen und Anschriften aus Ausweis B"), className = "small text-warning-emphasis")
            }
            div(partgFooterPreview(version), className = "small text-muted text-break")
            actionGroup {
                actionButton(ActionIcon.DOWNLOAD, tr("PDF"), ButtonStyle.OUTLINEPRIMARY, small = true) {
                    onClick { downloadAttachment(PartyReportHttp.versionUrl(PartgReportFormat.PDF, version.id)) }
                }
                actionButton(ActionIcon.DOWNLOAD, tr("Excel"), ButtonStyle.OUTLINEPRIMARY, small = true) {
                    onClick { downloadAttachment(PartyReportHttp.versionUrl(PartgReportFormat.XLSX, version.id)) }
                }
                if (version.scopeKey == PartgReportScope.ALL_KEY && version.status == PartgVersionStatus.FROZEN) {
                    actionButton(ActionIcon.DECLARE, tr("Versichern"), ButtonStyle.OUTLINEPRIMARY, small = true) {
                        onClick {
                            openDeclareDialog(version) {
                                onChanged(gettext("Version %1 wurde versichert.", version.versionNumber.toString()))
                            }
                        }
                    }
                }
                if (version.scopeKey == PartgReportScope.ALL_KEY && version.status == PartgVersionStatus.DECLARED) {
                    actionButton(ActionIcon.FINALIZE, tr("Final setzen"), ButtonStyle.OUTLINEDANGER, small = true) {
                        onClick {
                            openFinalizeDialog(version) {
                                onChanged(gettext("Version %1 ist jetzt intern final.", version.versionNumber.toString()))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ADMIN only: the versions after the retention period, with a preview of exactly what would be deleted. */
private fun SimplePanel.renderDeletionBlock(onChanged: (String) -> Unit) {
    div(tr("Alte Versionen löschen (nur Administration)"), className = "fw-bold mt-3") { setAttribute("data-partg-deletion", "1") }
    div(
        tr(
            "Gelöscht werden kann eine Version erst, wenn sowohl ihr Berichtsjahr als auch der Tag des Festhaltens länger als " +
                "die gewählte Zahl " +
                "von Jahren zurückliegen. Die Mindestfrist von 10 Jahren ist eine Annahme und rechtlich nicht geprüft.",
        ),
        className = "small text-muted",
    )
    val toolbar = lapisToolbar()
    val years = (PartgVersionRetention.MIN_YEARS..PartgVersionRetention.MAX_YEARS).map { it.toString() to it.toString() }
    val select = toolbar.select(options = years, value = PartgVersionRetention.MIN_YEARS.toString(), label = tr("Aufbewahrung in Jahren"))
    val result = simplePanel()
    toolbar.actionGroup {
        actionButton(ActionIcon.SEARCH, tr("Vorschau"), ButtonStyle.OUTLINEPRIMARY) {
            id = "partg-deletion-preview"
            onClick {
                val n = select.value?.toIntOrNull() ?: return@onClick
                AppScope.launch {
                    val preview = guarded { partyReportRpc().previewVersionDeletion(n) }
                    result.removeAll()
                    if (preview != null) result.renderDeletionPreview(preview = preview, onChanged = onChanged)
                }
            }
        }
    }
}

private fun SimplePanel.renderDeletionPreview(
    preview: PartgVersionDeletionPreviewDto,
    onChanged: (String) -> Unit,
) {
    if (preview.candidates.isEmpty()) {
        div(tr("Keine Version erfüllt die Bedingungen."), className = "small")
        return
    }
    div(gettext("%1 Versionen würden gelöscht:", preview.candidates.size.toString()), className = "small fw-semibold")
    preview.candidates.forEach { c ->
        div(gettext("Jahr %1, %2, Version %3", c.year.toString(), c.scopeKey, c.versionNumber.toString()), className = "small")
    }
    if (preview.truncated) div(tr("Es gibt weitere; sie werden in mehreren Durchgängen gelöscht."), className = "small text-muted")
    actionGroup {
        actionButton(ActionIcon.DELETE, tr("Diese Versionen endgültig löschen"), ButtonStyle.OUTLINEDANGER) {
            id = "partg-deletion-run"
            onClick {
                confirmDialog(
                    title = tr("Versionen endgültig löschen?"),
                    message =
                        gettext(
                            "%1 Versionen werden unwiderruflich gelöscht. Die Nummern werden nicht wieder vergeben.",
                            preview.candidates.size.toString(),
                        ),
                    confirmLabel = tr("Löschen"),
                    focusCancel = true,
                    onConfirm = {
                        AppScope.launch {
                            val removed =
                                guarded {
                                    partyReportRpc().deleteOldVersions(
                                        preview.olderThanYears,
                                        preview.candidates.map { it.versionId },
                                    )
                                }
                            if (removed != null) onChanged(gettext("%1 Versionen gelöscht.", removed.toString()))
                        }
                    },
                )
            }
        }
    }
}

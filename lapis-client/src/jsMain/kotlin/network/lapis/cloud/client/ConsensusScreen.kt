package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusParticipationDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/**
 * V1.9.28 "Konsensieren" -- the web client of the systemic consensus (`ISystemicConsensusService`, V0.2.5): every participant rates
 * EVERY option with a resistance from 0 ("I can live with it") to 10 ("not acceptable for me"); the option with the lowest resistance
 * wins. Until now the server could do all of this, but no screen could reach it.
 *
 * Two views on one route family: `/consensus` is the list, `/consensus/:id` the detail of one consensus. A consensus is never created
 * here -- it is opened from a scheduled motion (`MotionsScreen`, "Konsensieren eröffnen"), like an election.
 *
 * What the screens never do (enforced by `ConsensusSecrecyTripwireTest`): show a server message (Kilua RPC transmits only the exception
 * type, and the server's text can carry member UUIDs), write a receipt code or a rating anywhere but the one DOM node that shows it, or
 * log anything about a rating. Every read of this feature lives in this file (the list, the detail snapshot, the quiet probe), so the
 * other files stay free of loading logic; the one exception is the list of named ratings of an open consensus in `ConsensusResultView`.
 */
internal data class ConsensusUiContext(
    val currentMemberId: String,
)

fun renderConsensusScreen(
    container: SimplePanel,
    initialConsensusId: String? = null,
) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val ctx = ConsensusUiContext(session.memberId)
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Konsensieren"))

    if (initialConsensusId != null) {
        val backButton = root.button(tr("Zur Übersicht"), style = ButtonStyle.OUTLINESECONDARY)
        backButton.onClick { navigateTo(Routes.CONSENSUS) }
        // While a receipt is on screen it cannot be shown again, so the in-app way out is closed until "Fertig".
        consensusReceiptVisibilityHook = { showing -> backButton.visible = !showing }
        val detailPanel = root.vPanel(spacing = 10)
        renderConsensusDetail(detailPanel, initialConsensusId, ctx)
        return
    }

    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val statusFilterSelect =
        filterRow.select(
            options = listOf("" to tr("Alle Status")) + SystemicConsensusStatus.entries.map { it.name to consensusStatusLabel(it) },
            value = "",
            label = tr("Status"),
        )
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val section =
        root.dataSection<ConsensusListData>(
            emptyText = gettext("Noch kein Konsensieren vorhanden. Ein Konsensieren wird aus einem terminierten Antrag heraus eröffnet."),
            isEmpty = { it.consensuses.isEmpty() },
            load = {
                val status = statusFilterSelect.value?.takeIf { it.isNotBlank() }?.let { SystemicConsensusStatus.valueOf(it) }
                loadConsensusList(status)
            },
            render = { panel, data -> renderConsensusTable(panel, data) },
        )
    refreshButton.onClick { section.reload() }
    root.button(tr("Zu den Anträgen"), style = ButtonStyle.OUTLINESECONDARY).onClick { navigateTo(Routes.MOTIONS) }
    section.reload()
}

/** The list and, per entry, the viewer's own participation (one batched call per 100 entries, never one call per row). */
internal class ConsensusListData(
    val consensuses: List<SystemicConsensusDto>,
    val own: Map<String, SystemicConsensusParticipationDto>,
)

private const val PARTICIPATION_BATCH = 100

private suspend fun loadConsensusList(status: SystemicConsensusStatus?): ConsensusListData? {
    val service = rpcService<ISystemicConsensusService>()
    val consensuses = guarded { service.listSystemicConsensuses(null, status) } ?: return null
    val sorted = consensuses.sortedByDescending { it.openedAt }
    val own =
        sorted
            .map { it.id }
            .chunked(PARTICIPATION_BATCH)
            .flatMap { ids -> guarded { service.listSystemicConsensusParticipations(ids) } ?: emptyList() }
            .associateBy { it.systemicConsensusId }
    return ConsensusListData(consensuses = sorted, own = own)
}

/** What the list says about the viewer: only a running rating has a personal state worth a column. */
internal fun consensusOwnStatus(
    c: SystemicConsensusDto,
    p: SystemicConsensusParticipationDto?,
): String =
    when {
        c.status != SystemicConsensusStatus.RATING || p == null -> ""
        p.hasRated -> gettext("Abgegeben")
        p.canRate -> gettext("Offen für Sie")
        else -> gettext("Nicht stimmberechtigt")
    }

private fun renderConsensusTable(
    panel: SimplePanel,
    data: ConsensusListData,
) {
    panel.dataTable(
        columns =
            listOf(
                textColumn<SystemicConsensusDto>(title = tr("Titel"), primary = true, cssClasses = "fw-bold") { it.title },
                DataColumn(
                    title = tr("Status"),
                    cell = { container, c -> container.statusBadge(consensusStatusLabel(c.status), consensusStatusColor(c.status)) },
                ),
                textColumn(title = tr("Abstimmung")) { consensusSecrecyLabel(it.secret) },
                textColumn(title = tr("Art")) { consensusBindingnessLabel(it.bindingness) },
                textColumn(title = tr("Runde")) { consensusRoundLabel(it) },
                textColumn(title = tr("Ihr Stand")) { consensusOwnStatus(it, data.own[it.id]) },
                textColumn(title = tr("Eröffnet am")) { formatDateTime(it.openedAt) },
            ),
        rows = data.consensuses,
        actions = { container, c ->
            container
                .tableActionButton("fas fa-eye", gettext("Konsensieren öffnen"))
                .onClick { navigateTo("/consensus/${c.id}") }
        },
    )
}

/** Everything one detail view shows, loaded together so the screen is built from one consistent snapshot. */
internal class ConsensusDetailData(
    val consensus: SystemicConsensusDto,
    val participation: SystemicConsensusParticipationDto,
    val motion: MotionDto?,
    val result: SystemicConsensusResultDto?,
)

internal suspend fun loadConsensusDetail(consensusId: String): ConsensusDetailData? {
    val service = rpcService<ISystemicConsensusService>()
    val consensus = guarded { service.getSystemicConsensus(consensusId) } ?: return null
    val participation = guarded { service.getSystemicConsensusParticipation(consensusId) } ?: return null
    // The motion text is context, not a precondition: a failed load leaves the rest of the screen intact.
    val motion = guarded { rpcService<IGovernanceService>().getMotion(consensus.motionId) }
    val result =
        if (consensus.status == SystemicConsensusStatus.EVALUATED) guarded { service.getSystemicConsensusResult(consensusId) } else null
    return ConsensusDetailData(consensus = consensus, participation = participation, motion = motion, result = result)
}

/** The consensuses of one motion, for the motion's resolution section. Lives here so every consensus read sits next to the state blocks. */
internal suspend fun loadMotionConsensuses(motionId: String): List<SystemicConsensusDto> =
    guarded { rpcService<ISystemicConsensusService>().listSystemicConsensuses(motionId = motionId) } ?: emptyList()

/** What a failed rating leaves to find out: whether the member has rated and whether the rating is still open. `null` means unknown. */
internal class ConsensusProbe(
    val participation: SystemicConsensusParticipationDto?,
    val consensus: SystemicConsensusDto?,
)

/** Reads the state silently (no toast, no log): a failed read is simply unknown, never an error to show. */
internal suspend fun probeConsensusState(consensusId: String): ConsensusProbe {
    val participation = probeQuietly { rpcService<ISystemicConsensusService>().getSystemicConsensusParticipation(consensusId) }
    val consensus = probeQuietly { rpcService<ISystemicConsensusService>().getSystemicConsensus(consensusId) }
    return ConsensusProbe(participation, consensus)
}

private suspend fun <T> probeQuietly(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

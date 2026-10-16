package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.MemberFinderBoardOverviewDto
import network.lapis.cloud.shared.domain.MemberFinderRegionBandDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberFinderService
import network.lapis.cloud.shared.rpc.MemberFinderUnavailableException

/*
 * V1.9.145 -- the board overview of the member finder: which regions (the first two digits of the postal code) have a visible group, as a BAND --
 * never a count and never a person. For BOARD and ADMIN, on the member administration page.
 *
 * The card is quiet: while the finder is switched off, or the caller is not an ACTIVE member (the server answers "unavailable" or a plain refusal),
 * nothing is drawn at all -- no heading, no toast, no hint that something is missing.
 */

private sealed interface BoardOverviewLoad {
    data class Shown(
        val overview: MemberFinderBoardOverviewDto,
    ) : BoardOverviewLoad

    data object Hidden : BoardOverviewLoad
}

/** Renders the card into [root]; the caller guarantees the viewer is BOARD or ADMIN (the server checks it again). */
internal fun renderMemberFinderBoardCard(root: SimplePanel) {
    val host = root.vPanel(spacing = 8)
    host
        .dataSection<BoardOverviewLoad>(
            isEmpty = { false },
            load = {
                try {
                    BoardOverviewLoad.Shown(rpcService<IMemberFinderService>().getBoardOverview())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MemberFinderUnavailableException) {
                    BoardOverviewLoad.Hidden
                } catch (e: ForbiddenException) {
                    BoardOverviewLoad.Hidden
                } catch (e: Throwable) {
                    guarded { throw e }
                }
            },
            render = { panel, loaded ->
                when (loaded) {
                    BoardOverviewLoad.Hidden -> Unit
                    is BoardOverviewLoad.Shown -> renderBoardOverview(panel, loaded.overview)
                }
            },
        ).reload()
}

private fun renderBoardOverview(
    panel: SimplePanel,
    overview: MemberFinderBoardOverviewDto,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3 mt-2") }
    card.h2(tr("Mitglieder-Finder: Regionen")) { addCssClass("h5") }
    val regions = overview.regions.sortedBy { it.regionLabel }
    if (regions.isEmpty()) {
        card.p(tr("Noch keine Region mit mindestens fünf Teilnehmenden."), className = "text-muted mb-0")
    } else {
        card.plainDataTable(
            columns =
                listOf(
                    textColumn<MemberFinderRegionBandDto>(title = tr("Region"), primary = true) { "DE-${it.regionLabel}" },
                    textColumn<MemberFinderRegionBandDto>(title = tr("Teilnehmende")) { memberFinderBandLabel(it.band) },
                ),
            rows = regions,
            label = gettext("Regionen mit mindestens fünf Teilnehmenden"),
        )
    }
    overview.computedOn?.let { card.div(gettext("Stand: %1", formatDate(it)), className = "text-muted small") }
}

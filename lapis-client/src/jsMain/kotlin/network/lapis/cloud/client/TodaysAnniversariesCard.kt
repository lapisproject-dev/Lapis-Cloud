package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.Icon
import io.kvision.html.P
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnniversaryEmphasis
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TodaysAnniversariesDto
import network.lapis.cloud.shared.domain.TodaysAnniversaryDto
import network.lapis.cloud.shared.rpc.IMemberAnniversaryService

// V1.9.276 -- dashboard card "Heute" (BOARD/ADMIN): today's birthdays and membership milestones. One RPC, once per mount (no polling, no
// refresh over midnight), no toast on failure. The card appears only when there is something to say AND "Für Sie" has settled for the
// first time, so it never pushes the signal cards around. No live region.

/** The one RPC of the card, injectable so a DOM test can feed (or refuse) an answer. */
internal fun interface TodaysAnniversariesRpc {
    suspend fun today(): TodaysAnniversariesDto
}

internal fun liveTodaysAnniversariesRpc() = TodaysAnniversariesRpc { rpcService<IMemberAnniversaryService>().getTodaysAnniversaries() }

/** Only BOARD and ADMIN see the card (UX only; the server checks the role again). */
internal fun showsTodaysAnniversaries(session: SessionInfoDto?): Boolean =
    session?.role == AccountRole.BOARD || session?.role == AccountRole.ADMIN

/** Lines shown at most; the rest is summarised in one sentence. */
internal const val TODAY_CARD_ROWS = 6

internal fun todaysAnniversaryLine(entry: TodaysAnniversaryDto): String =
    when (entry.kind) {
        AnniversaryEntryKind.BIRTHDAY ->
            ngettext("%1 wird %2", "%1 wird %2", entry.years, entry.memberDisplayName, entry.years)
        AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY ->
            ngettext("%1: seit %2 Jahr dabei", "%1: seit %2 Jahren dabei", entry.years, entry.memberDisplayName, entry.years)
    }

/** A word instead of a type weight, so the emphasis does not depend on colour or font. */
internal fun todaysAnniversaryMarker(emphasis: AnniversaryEmphasis): String? =
    when (emphasis) {
        AnniversaryEmphasis.FIRST_YEAR -> tr("erstes Jahr")
        AnniversaryEmphasis.NOTABLE, AnniversaryEmphasis.MAJOR -> tr("Meilenstein")
        AnniversaryEmphasis.STANDARD -> null
    }

/**
 * The empty, invisible placeholder [host] is filled once the answer is in AND [signalsSettled] was called -- whichever comes last. Below the
 * placeholder there is nothing, so filling it moves nothing.
 */
internal class TodaysAnniversariesSlot(
    root: SimplePanel,
    private val rpc: TodaysAnniversariesRpc,
) {
    private val host = Div(className = "lapis-dashboard-today")
    private var gone = false
    private var started = false
    private var settled = false
    private var answer: TodaysAnniversariesDto? = null
    private var rendered = false

    init {
        root.addWithLifecycle(
            host,
            onInsert = {
                gone = false
                if (!started) {
                    started = true
                    load()
                }
            },
            onDestroy = { gone = true },
        )
    }

    /** Called once by "Für Sie" when its first load has ended (answer or failure). */
    fun signalsSettled() {
        settled = true
        fill()
    }

    private fun load() {
        AppScope.launch {
            try {
                val dto = rpc.today()
                if (!gone) {
                    answer = dto
                    fill()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // nothing shown, no toast
            }
        }
    }

    private fun fill() {
        val dto = answer ?: return
        if (gone || !settled || rendered || dto.entries.isEmpty()) return
        rendered = true
        val card = Div(className = "card")
        val body = Div(className = "card-body")
        card.add(body)
        body.h2(tr("Heute")) { addCssClass("h5") }
        val list = Tag(TAG.UL, className = "list-unstyled mb-2")
        list.setAttribute("aria-label", gettext("Geburtstage und Jubiläen heute"))
        dto.entries.take(TODAY_CARD_ROWS).forEach { entry ->
            val item = Tag(TAG.LI, className = "text-break")
            val glyph = Icon(anniversaryKindIcon(entry.kind))
            glyph.setAttribute("aria-hidden", "true")
            item.add(glyph)
            item.add(Span(" "))
            item.add(Span(todaysAnniversaryLine(entry)))
            todaysAnniversaryMarker(entry.emphasis)?.let { marker ->
                item.add(Span(" "))
                item.add(Span(marker, className = "small text-body-secondary"))
            }
            list.add(item)
        }
        body.add(list)
        if (dto.totalCount > TODAY_CARD_ROWS) {
            val rest = dto.totalCount - TODAY_CARD_ROWS
            body.add(
                P(
                    ngettext("und %1 weiterer Eintrag", "und %1 weitere Einträge", rest, rest),
                    className = "small mb-2",
                ),
            )
        }
        body.link(tr("Alle Geburtstage und Jubiläen"), url = "#${Routes.MEMBER_ANNIVERSARIES}")
        host.add(card)
    }
}

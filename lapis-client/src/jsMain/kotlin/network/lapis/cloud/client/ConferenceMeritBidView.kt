package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ILtrLedgerService

/*
 * V1.9.27 -- the bid view of a meritocratic vote, shown INSIDE the booth host of the voting panel (so the panel's close lock, the "no
 * auto-open while it is open" rule and the unload prompt apply without any new state). Bidding binds LTR, so it is always a first bid
 * (`createOnly`) with one confirmation; a changed bid, custom options and aborting stay on the motion page.
 *
 * The stake of the member lives ONLY in the form field and in the confirmation text: never in storage, never in the console, and the
 * ballot the server returns is not rendered. An exception's message is never read.
 */

/** The member's free LTR balance, or `null` when it cannot be read (the balance section then shows the shared error state with a retry). */
internal suspend fun defaultLoadBalance(): LtrLedgerBalanceDto? =
    try {
        rpcService<ILtrLedgerService>().getMyBalance()
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Throwable) {
        null
    }

/** `true` iff [stakeText] is a number larger than the free balance (a pre-check only; the server decides). Unknown balance or text: `false`. */
internal fun stakeExceedsBalance(
    stakeText: String,
    balance: LtrLedgerBalanceDto?,
): Boolean {
    val stake = stakeText.trim().toDoubleOrNull() ?: return false
    val free = balance?.freeBalanceLtr?.toString()?.toDoubleOrNull() ?: return false
    return stake > free
}

/**
 * Renders the bid view into [host]. [cast] is the raw write; a [ConflictException] (the vote closed, a bid already exists, ...) leads to
 * [onConflict] with a fixed note -- never to the exception's text. [onDone] runs after a stored bid. [onBusyChanged] reports the sending
 * state to the panel (close lock + unload prompt).
 */
internal fun renderMeritBidView(
    host: SimplePanel,
    ballot: RoomBallotDto,
    loadBalance: suspend () -> LtrLedgerBalanceDto? = { defaultLoadBalance() },
    cast: suspend (VoteBallotInput) -> VoteBallotDto,
    onDone: () -> Unit,
    onConflict: () -> Unit,
    onBusyChanged: (Boolean) -> Unit,
): BallotFormHandle {
    host.removeAll()
    val view = host.vPanel(spacing = 10) { addCssClasses("lapis-booth lapis-merit-bid") }
    view.h2(tr("Gebot abgeben")) { addCssClass("h5") }
    view.untrustedP(ballot.title, className = "fw-bold mb-0")
    var balance: LtrLedgerBalanceDto? = null
    // The shared load states: while loading a quiet status, on failure the one fixed error sentence with "Erneut versuchen" -- never the exception.
    view
        .dataSection<LtrLedgerBalanceDto>(
            isEmpty = { false },
            onSettled = { balance = it },
            load = { loadBalance() },
            render = { body, loaded ->
                body.div(trFormat(tr("Verfügbar: %1"), trusted(ltrToken(loaded.freeBalanceLtr)))) { addCssClasses("small") }
            },
        ).reload()
    view.div(
        tr(
            "Ihr Einsatz wird sofort von Ihrem verfügbaren Guthaben abgezogen und bleibt gebunden. " +
                "Gewinnt Ihre Option, wird nach dem Zweitpreis abgerechnet; eine Rückgabe nicht verbrauchter Beträge gibt es derzeit noch nicht.",
        ),
    ) { addCssClasses("alert alert-warning mb-0") }

    val send: suspend (VoteBallotInput) -> VoteBallotDto? = { input ->
        onBusyChanged(true)
        try {
            cast(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConflictException) {
            onConflict()
            null
        } catch (e: Throwable) {
            electionGuarded<VoteBallotDto>(conflictMessage = "") { throw e }
        } finally {
            onBusyChanged(false)
        }
    }
    return renderBallotForm(
        panel = view,
        model = BallotFormModel(ballot.id, ballot.options.map { BallotFormOption(it.id, it.label, it.position) }),
        currentOptionId = null,
        onChanged = onDone,
        confirm = { optionLabel, stakeText, proceed ->
            if (stakeExceedsBalance(stakeText, balance)) {
                notifyError(tr("Das Gebot übersteigt Ihr verfügbares Guthaben."))
            } else {
                confirmDialog(
                    title = tr("Gebot abgeben"),
                    message =
                        gettext(
                            "%1 LTR auf „%2“ setzen? Der Betrag wird sofort von Ihrem Guthaben abgezogen und bleibt gebunden.",
                            stakeText,
                            sanitizeUntrustedI18nText(optionLabel),
                        ),
                    confirmLabel = tr("Gebot abgeben"),
                    confirmStyle = ButtonStyle.PRIMARY,
                    focusCancel = true,
                ) { proceed() }
            }
        },
        cast = send,
        createOnly = true,
    )
}

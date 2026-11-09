package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import io.kvision.core.Container
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.utils.rem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * V1.9.297 -- the free LTR balance for the boost controls of ONE screen, loaded at most once per render: the social network draws a boost control
 * on every post and every comment, a notice board on every notice, and each of them shows "Freies Guthaben" -- N cards must not mean N requests.
 * Concurrent askers share one in-flight request. A failed load is NOT cached (the next asker tries again) and answers `null` ("unknown", the
 * server decides anyway). [invalidate] after a boost, and at the start of every screen that uses [sharedLtrBalanceSource].
 */
internal class LtrBalanceSource(
    private val load: suspend () -> Decimal? = { defaultLoadBalance()?.freeBalanceLtr },
) {
    private var pending: Deferred<Decimal?>? = null

    /** The free balance, or `null` when it cannot be read. */
    suspend fun current(): Decimal? {
        val request = pending ?: AppScope.async { load() }.also { pending = it }
        val value = request.await()
        if (value == null && pending === request) pending = null
        return value
    }

    fun invalidate() {
        pending = null
    }
}

/** The one source of the running screen; each screen that draws boost controls calls [LtrBalanceSource.invalidate] when it starts to render. */
internal val sharedLtrBalanceSource = LtrBalanceSource()

/**
 * The boost control of the social network and of the bulletin board (one component, one confirmation text): the free balance line, the amount
 * field (decimal keypad on phones), a "Boosten" button. The amount is captured and validated INLINE first, THEN confirmed with [confirmDialog]
 * (nothing is typed into the modal). A third decimal place is refused with a message, never rounded. The button is disabled while the call runs.
 * [onBoost] performs the call and answers `true` on success; [onChanged] then reloads the host screen.
 *
 * [confirmTitle] and [successText] are static `tr(...)` constants of the caller.
 */
internal fun ltrBoostControl(
    row: Container,
    balance: LtrBalanceSource,
    confirmTitle: String,
    successText: String,
    onBoost: suspend (Decimal) -> Boolean,
    onChanged: () -> Unit,
) {
    lateinit var amountInput: Text
    lateinit var boostButton: Button
    val balanceLine = row.div("", className = "text-muted small lapis-boost-balance")
    AppScope.launch {
        val free = balance.current() ?: return@launch
        balanceLine.content = gettext("Freies Guthaben: %1", formatLtr(free))
    }
    row.inlineFieldGroup {
        // The label keeps its unit: "(LTR)" says what the number is. The field is 8 rem wide, a decimal keypad on phones.
        amountInput =
            text(label = tr("Boost-Betrag (LTR)")) {
                placeholder = "LTR"
                width = 8.rem
            }
        amountInput.input.setAttribute("inputmode", "decimal")
        amountInput.input.setAttribute("aria-label", gettext("Boost-Betrag in LTR"))
        boostButton = button(tr("Boosten"), style = ButtonStyle.OUTLINEPRIMARY)
    }
    val errorBox =
        row.div().apply {
            addCssClass("text-danger")
            hide()
        }
    boostButton.onClick {
        errorBox.hide()
        val amountParsed = parseLtrAmountInput(amountInput.value.orEmpty().trim())
        if (amountParsed !is AmountInput.Valid) {
            errorBox.content = amountErrorText(amountParsed, tr("Bitte einen positiven Betrag (LTR) angeben."))
            errorBox.show()
            return@onClick
        }
        val amount = amountParsed.value
        confirmDialog(
            title = confirmTitle,
            message =
                gettext(
                    "Sie zahlen %1 aus Ihrem freien LTR-Guthaben. Dieser Betrag wird NICHT zurückerstattet.",
                    formatLtr(amount),
                ),
            confirmLabel = tr("Boosten"),
        ) {
            runGuardedAction(boostButton) {
                if (onBoost(amount)) {
                    notifySuccess(successText)
                    amountInput.value = null
                    balance.invalidate()
                    onChanged()
                }
            }
        }
    }
}

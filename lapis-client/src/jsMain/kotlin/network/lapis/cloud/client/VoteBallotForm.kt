package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.rpc.IGovernanceService

/*
 * The bid form of a meritocratic vote. Moved here (V1.9.27) from `MotionsScreen.kt` so ONE small file is the only place that may write a
 * stake -- the conference room and the motion page both go through it, and `ElectionSecrecyTripwireTest` watches this file (the stake
 * field of `VoteBallotInput` is built here and nowhere in the room code). The returned ballot is never rendered: it carries the stake.
 */

/** One option of the form: only what the select needs. Label is untrusted free text. */
internal data class BallotFormOption(
    val id: String,
    val label: String,
    val position: Int,
)

/** What the form needs of a vote: its id and its options -- deliberately NOT the basket totals. */
internal data class BallotFormModel(
    val voteId: String,
    val options: List<BallotFormOption>,
)

/** Maps only id/label/position of each option -- never the basket total. */
internal fun VoteDto.toBallotFormModel(): BallotFormModel =
    BallotFormModel(
        voteId = id,
        options = options.map { BallotFormOption(id = it.id, label = it.label, position = it.position) },
    )

internal class BallotFormHandle(
    /** `true` once the member typed or changed anything. Discarding a dirty form is harmless, so this never locks anything. */
    val isDirty: () -> Boolean,
)

/**
 * Renders the "Gebot abgeben" form into [panel].
 *
 * With [confirm] `null` (motion page) the form behaves exactly as before V1.9.27: validate, submit. With a [confirm] callback (conference
 * room) the validated input first goes to [confirm] -- which must call `proceed` to actually send -- so the room can ask once before money
 * is bound. [cast] returns the stored ballot or `null` when the call failed (the caller has already explained that); [createOnly] makes the
 * server refuse to overwrite an existing bid.
 */
internal fun renderBallotForm(
    panel: SimplePanel,
    model: BallotFormModel,
    currentOptionId: String?,
    onChanged: () -> Unit,
    confirm: ((optionLabel: String, stakeText: String, proceed: () -> Unit) -> Unit)? = null,
    cast: suspend (VoteBallotInput) -> VoteBallotDto? = { input -> guarded { rpcService<IGovernanceService>().castVoteBallot(input) } },
    createOnly: Boolean = false,
): BallotFormHandle {
    val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    formPanel.p(tr("Gebot abgeben")) { addCssClass("fw-bold") }
    val form = formPanel.lapisForm()
    val optionOptions = untrustedOptions(model.options.sortedBy { it.position }.map { it.id to it.label })
    val optionField =
        form.selectField(
            label = tr("Option"),
            options = optionOptions,
            value = currentOptionId ?: optionOptions.firstOrNull()?.first,
        )
    val stakeField =
        form.textField(
            label = tr("Einsatz (LTR)"),
            required = true,
            rule = { value ->
                if (Validation.isPositiveDecimal(value.trim())) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(gettext("Bitte einen positiven Betrag (LTR) angeben."))
                }
            },
            // The room form is used on phones in a call: the numeric keypad. The motion page keeps its DOM unchanged.
            init =
                if (confirm != null) {
                    { control -> (control.input as? Widget)?.setAttribute("inputmode", "decimal") }
                } else {
                    null
                },
        )

    val castButton = Button(tr("Gebot abgeben"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = castButton)

    fun send() {
        form.submit(castButton) {
            val optionId = optionField.value.takeIf { it.isNotBlank() }
            if (optionId == null) {
                form.showFormError(tr("Bitte eine Option und einen positiven LTR-Einsatz angeben."))
                return@submit
            }
            val result =
                cast(
                    VoteBallotInput(
                        voteId = model.voteId,
                        optionId = optionId,
                        stakeLtr =
                            stakeField.value
                                .trim()
                                .toDouble()
                                .toDecimal(),
                        createOnly = createOnly,
                    ),
                )
            if (result != null) {
                notifySuccess(tr("Gebot gespeichert."))
                onChanged()
            }
        }
    }
    castButton.onClick {
        if (confirm == null) {
            send()
        } else if (form.validateAndReport()) {
            val optionId = optionField.value
            val label = model.options.firstOrNull { it.id == optionId }?.label ?: ""
            confirm(label, stakeField.value.trim()) { send() }
        }
    }
    return BallotFormHandle(isDirty = { form.fields.any { it.dirty } })
}

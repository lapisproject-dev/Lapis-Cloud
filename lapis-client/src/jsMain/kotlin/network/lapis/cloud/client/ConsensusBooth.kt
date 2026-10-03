package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus

/*
 * V1.9.28 -- the resistance booth (V1.9.41: now a thin adapter of the shared [renderRatingBooth]; the flow and its secrecy rules live in `RatingBooth.kt`). An irreversible, single-shot action, so it is its own mode: it REPLACES the whole detail view (the
 * route does not change) and offers exactly three steps (rate every option -> review -> submit), ending in a state that explains itself.
 *
 * Secrecy rules (guarded by `ConsensusSecrecyTripwireTest`): no `console`/`println`, no `localStorage`/`sessionStorage`, no
 * `history.pushState`, no field in `AppState`, no server message, no `data-*` attribute, and neither the ratings nor the receipt code
 * ever appear in a toast. The ratings live in this controller's one private map (and in the radio buttons' own checked state); the map is
 * emptied the moment the request has been sent, and the form DOM is thrown away with it.
 *
 * The radio groups are named by a running group index and the radio buttons carry a running index as id -- never an option id and never the
 * value that button stands for. What each option is and what a rating means exists only in this class's closure.
 *
 * A conflict or a network failure never claims more than is known: the booth reads the participation state again and tells the member what
 * actually happened. `e.message` is never read -- Kilua RPC does not transmit it and the server's text can carry member UUIDs.
 */
internal fun renderConsensusBooth(
    panel: SimplePanel,
    consensus: SystemicConsensusDto,
    roomHost: ConsensusBoothRoomHost? = null,
    onExit: (refresh: Boolean) -> Unit,
) {
    val numbers = consensusOptionNumbers(consensus.options)
    val headLines =
        buildList {
            add(RatingHeadLine(tr("Bewerten Sie jede Option: Wie groß ist Ihr Widerstand?"), "mb-0"))
            add(
                RatingHeadLine(
                    gettext("0 = kein Widerstand, ich kann gut damit leben · %1 = für mich nicht tragbar.", consensus.scaleMax),
                    "text-muted small mb-1",
                ),
            )
            if (roomHost != null) {
                add(RatingHeadLine(tr("Gewählt wird die Option mit dem geringsten Gesamtwiderstand."), "text-muted small mb-1"))
            }
            add(
                RatingHeadLine(
                    if (consensus.secret) {
                        tr("Ihre Bewertung ist anonym: Es wird gespeichert, dass Sie bewertet haben, aber nicht, wie.")
                    } else {
                        tr("Dieses Konsensieren ist offen: Ihre Bewertung wird mit Ihrem Namen gespeichert.")
                    },
                    "text-muted small mb-1",
                ),
            )
        }
    val spec =
        RatingBoothSpec(
            // The status quo option (P) first, then the real proposals -- the same order and numbers as the options list (V1.9.39).
            items =
                consensusOrderedOptions(consensus.options).map { option ->
                    RatingItem(
                        key = option.id,
                        number = numbers.getValue(option.id),
                        text = consensusOptionText(option),
                        explanationRaw = if (option.isStatusQuoOption) null else option.rationale,
                    )
                },
            scaleMax = consensus.scaleMax,
            texts =
                RatingBoothTexts(
                    heading = tr("Bewertung"),
                    subjectRaw = consensus.title,
                    headLines = headLines,
                    anchors = RatingAnchors(low = tr("kein"), middle = tr("deutliche Bedenken"), high = tr("nicht tragbar")),
                    explanationClosedLabel = tr("Begründung"),
                    reviewLine = { text, value -> gettext("%1: Widerstand %2 von %3", text, value, consensus.scaleMax) },
                    finalNote = tr("Nach der Abgabe kann Ihre Bewertung nicht mehr geändert werden."),
                    exitLabel = tr("Zurück zum Konsensieren"),
                    doneHeading = tr("Ihre Bewertung wurde gezählt"),
                    doneText = tr("Danke, Ihre Bewertung ist eingegangen."),
                    forbiddenHeading = tr("Keine Stimmberechtigung"),
                    forbiddenText = tr("Sie sind für diese Runde nicht stimmberechtigt."),
                    alreadyHeading = tr("Bereits bewertet"),
                    alreadyLines = listOf(tr("Ihre Bewertung ist bereits eingegangen.")),
                    alreadyLostLines =
                        listOf(
                            tr(
                                "Ihre Bewertung wurde gezählt. Die Bestätigung ist wegen eines Verbindungsabbruchs nicht bei Ihnen angekommen.",
                            ),
                        ),
                    closedHeading = tr("Bewertung beendet"),
                    closedText = tr("Die Bewertung ist geschlossen."),
                    noConnectionHeading = tr("Keine Verbindung"),
                    noConnectionLostText =
                        tr(
                            "Die Verbindung wurde unterbrochen. Ob Ihre Bewertung gezählt wurde, konnte nicht geprüft werden. Bitte laden Sie die Seite neu.",
                        ),
                    noConnectionConflictText = tr("Der Stand hat sich geändert. Bitte erneut prüfen."),
                ),
            cast = { values ->
                when (val outcome = castConsensusBallotGuarded(SystemicConsensusBallotInput(consensus.id, values))) {
                    is ConsensusCastOutcome.Ok -> {
                        val receipt = outcome.result.receiptCode
                        if (consensus.secret && receipt != null && isConsensusReceiptCode(receipt)) {
                            RatingCastResult.Ok { booth, leave ->
                                renderConsensusReceipt(booth = booth, title = consensus.title, code = receipt, onDone = leave)
                            }
                        } else {
                            RatingCastResult.Ok()
                        }
                    }
                    is ConsensusCastOutcome.Forbidden -> RatingCastResult.Forbidden
                    is ConsensusCastOutcome.Failed -> RatingCastResult.Failed
                    is ConsensusCastOutcome.Conflict -> RatingCastResult.Conflict
                }
            },
            probe = {
                val probed = probeConsensusState(consensus.id)
                RatingProbe(
                    alreadyRated = probed.participation?.hasRated == true,
                    closed = probed.consensus != null && probed.consensus.status != SystemicConsensusStatus.RATING,
                    unknown = probed.consensus == null && probed.participation == null,
                )
            },
        )
    renderRatingBooth(
        host = panel,
        spec = spec,
        roomHost =
            roomHost?.let {
                RatingBoothRoomHost(
                    exitLabel = it.exitLabel,
                    ballotLock = it.ballotLock,
                    onBusyChanged = it.onBusyChanged,
                    compact = it.compact,
                )
            },
        onExit = onExit,
    )
}

/**
 * V1.9.32 -- what the conference room panel adds to the booth. `null` (the consensus screen) leaves the booth exactly as it was.
 * [exitLabel] is the label of the way out of a terminal state; [ballotLock] is the stream lock of an ANONYMOUS consensus (`null` for an open
 * one, which never pauses a stream); [onBusyChanged] is `true` while the rating request is in flight (the panel locks its close button and
 * asks before the page is left); [compact] is the narrow grid of the side panel. The hook never sees a rating or a receipt.
 */
internal class ConsensusBoothRoomHost(
    val exitLabel: String,
    val ballotLock: BallotLockHook?,
    val onBusyChanged: (Boolean) -> Unit,
    val compact: Boolean,
)

package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollRatingInput
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollStatus

/*
 * V1.9.41 -- the answer booth of the consensus polls (SK_DECISION / SK_PRIORITY): a thin adapter of the shared [renderRatingBooth]. The flow,
 * the secrecy rules and the radio handling live there (guarded by `PollSecrecyTripwireTest`); this file only supplies the poll's items, texts
 * and the two service calls. It never keeps a rating anywhere: the booth hands the complete vector to [castPollRatingsGuarded] and forgets it.
 *
 * The anchors name what the number means ("kein Widerstand" / "Bedenken" / "starker Widerstand"); the head says in plain words that this is
 * a mood picture and that the answer is anonymous. The passive option ("Keine Änderung") is listed first, like the "P" option of the
 * consensus screens.
 */
internal fun renderPollRatingBooth(
    panel: SimplePanel,
    poll: PollDto,
    onReview: (Boolean) -> Unit,
    onExit: (refresh: Boolean) -> Unit,
) {
    val spec =
        RatingBoothSpec(
            items =
                pollRatingOrderedOptions(poll).map { (number, option) ->
                    RatingItem(
                        key = option.id,
                        number = number,
                        text = pollRatingOptionText(option),
                        explanationRaw = if (option.isPassive) null else option.explanation,
                        subtitle = if (option.isPassive) gettext("Passivlösung") else null,
                    )
                },
            scaleMax = PollRules.SK_SCALE_MAX,
            texts =
                RatingBoothTexts(
                    heading = tr("Bewertung"),
                    subjectRaw = poll.question,
                    headLines =
                        listOf(
                            RatingHeadLine(tr("Unverbindliches Stimmungsbild, keine Abstimmung."), "text-muted small mb-1"),
                            RatingHeadLine(tr("Wie groß ist Ihr Widerstand gegen jede Option?"), "mb-0"),
                            RatingHeadLine(
                                tr("Ihre Antwort ist anonym: Es wird gespeichert, dass Sie geantwortet haben, aber nicht, wie."),
                                "text-muted small mb-1",
                            ),
                        ),
                    anchors =
                        RatingAnchors(low = tr("kein Widerstand"), middle = tr("Bedenken"), high = tr("starker Widerstand")),
                    explanationClosedLabel = tr("Erklärung"),
                    reviewLine = { text, value -> gettext("%1: Widerstand %2 von %3", text, value, PollRules.SK_SCALE_MAX) },
                    finalNote = tr("Nach der Abgabe kann Ihre Bewertung nicht mehr geändert werden."),
                    exitLabel = tr("Zurück zur Umfrage"),
                    doneHeading = tr("Danke"),
                    doneText = tr("Ihre Antwort ist gespeichert."),
                    forbiddenHeading = tr("Keine Berechtigung"),
                    forbiddenText = gettext("Nur aktive Mitglieder können antworten."),
                    alreadyHeading = tr("Bereits geantwortet"),
                    alreadyLines = listOf(gettext("Ihre Antwort ist gespeichert.")),
                    alreadyLostLines =
                        listOf(
                            gettext("Ihre Antwort ist gespeichert."),
                            gettext("Die Bestätigung ist wegen eines Verbindungsabbruchs nicht angekommen."),
                        ),
                    closedHeading = tr("Umfrage beendet"),
                    closedText = gettext("Die Umfrage ist beendet."),
                    noConnectionHeading = tr("Keine Verbindung"),
                    noConnectionLostText = pollNoConnectionText(),
                    noConnectionConflictText = pollNoConnectionText(),
                ),
            cast = { values ->
                when (castPollRatingsGuarded(PollRatingInput(pollId = poll.id, ratings = values))) {
                    is PollCastOutcome.Ok -> RatingCastResult.Ok()
                    PollCastOutcome.Conflict -> RatingCastResult.Conflict
                    PollCastOutcome.Forbidden -> RatingCastResult.Forbidden
                    PollCastOutcome.Failed -> RatingCastResult.Failed
                }
            },
            probe = {
                val probed = probePollState(poll.id)
                RatingProbe(
                    alreadyRated = probed.participation?.hasResponded == true,
                    closed = probed.poll != null && probed.poll.status != PollStatus.OPEN,
                    unknown = probed.poll == null && probed.participation == null,
                )
            },
            extraCssClass = "lapis-booth-poll",
        )
    renderRatingBooth(host = panel, spec = spec, onReview = onReview, onExit = onExit)
}

private fun pollNoConnectionText(): String =
    gettext(
        "Die Verbindung wurde unterbrochen. Ob Ihre Antwort gespeichert wurde, konnte nicht geprüft werden. " +
            "Bitte laden Sie die Seite neu.",
    )

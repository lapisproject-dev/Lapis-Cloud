package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotStatus

/*
 * V1.9.27 "Abstimmen im Konferenzraum", Welle 4 -- the live card of a meritocratic vote (Vote with LTR bids) in the room panel.
 *
 * Nothing in this file touches the content of a bid: the room DTO carries no amount, no basket total and no other member, so the card can
 * only say WHETHER the member may bid / already bid and, once decided, which option won. Bidding itself is `ConferenceMeritBidView.kt`.
 */

/** The in-app address of a motion's detail page; `null` unless [id] has the shape of an id this server mints. Never a foreign URL. */
internal fun conferenceMotionDetailHref(id: String): String? = if (ROOM_ID_PATTERN.matches(id)) "#/motions/$id" else null

/** Your own standing in one vote: bids are not "votes", so the wording differs from the election card. */
private fun ownBidLabel(ballot: RoomBallotDto): String =
    when {
        ballot.ownHasVoted -> tr("Sie haben geboten")
        ballot.ownEligible -> tr("Sie sind stimmberechtigt")
        else -> tr("Nicht stimmberechtigt")
    }

/**
 * One meritocratic vote as a card: title, state, your standing, and either the single "Gebot abgeben" button, a link to the motion page
 * (where an own bid can be viewed or changed), or the result. Title, motion title and the winner's label are member-written free text and go
 * through the untrusted helpers. A winner id that is not among [RoomBallotDto.options] shows no result line at all (never a guess).
 */
internal fun renderMeritVoteLiveCard(
    parent: Container,
    ballot: RoomBallotDto,
    onBid: (Button) -> Unit,
): SimplePanel {
    val card = parent.vPanel(spacing = 4) { addCssClasses("lapis-vote-card lapis-merit-vote-card border rounded p-2") }
    card.untrustedP(ballot.title, className = "fw-bold mb-0")
    if (ballot.motionTitle.isNotBlank() && ballot.motionTitle != ballot.title) {
        card.untrustedSpan(ballot.motionTitle, className = "text-muted small")
    }
    val badges = card.hPanel(spacing = 6) { addCssClasses("flex-wrap align-items-center") }
    badges.votingBadge(tr("Meritokratische Abstimmung"), "secondary", "fas fa-scale-balanced")
    badges.votingBadge(
        roomBallotStatusLabel(ballot.status),
        if (ballot.status ==
            RoomBallotStatus.OPEN
        ) {
            "success"
        } else {
            "secondary"
        },
        "fas fa-circle",
    )
    badges.votingBadge(tr("offen – namentlich"), "info", "fas fa-user-pen")
    card.div(ownBidLabel(ballot)) { addCssClasses("small") }
    val href = conferenceMotionDetailHref(ballot.motionId)
    when (ballot.status) {
        RoomBallotStatus.OPEN ->
            when {
                ballot.ownEligible && !ballot.ownHasVoted -> {
                    val bid = card.button(tr("Gebot abgeben"), style = ButtonStyle.PRIMARY) { addCssClass("w-100") }
                    bid.onClick { onBid(bid) }
                }
                ballot.ownHasVoted && href != null -> card.openInNewTab(tr("Gebot ansehen oder ändern"), href)
            }
        else -> {
            if (ballot.winnerOptionId == null) {
                card.div(tr("Unentschieden – kein Gewinner")) { addCssClasses("small fw-bold") }
            } else {
                val winner = ballot.options.firstOrNull { it.id == ballot.winnerOptionId }
                if (winner != null) {
                    card.div(gettext("Ergebnis: %1", sanitizeUntrustedI18nText(winner.label))) { addCssClasses("small fw-bold") }
                }
            }
            if (href != null) card.openInNewTab(tr("Ergebnis im Detail"), href)
        }
    }
    return card
}

private fun Container.openInNewTab(
    label: String,
    href: String,
) {
    link(label, url = href, target = "_blank") {
        setAttribute("rel", "noopener noreferrer")
    }
}

/** Shown ONCE above the open vote cards: a meritocratic vote is saved by name, exactly like an open election (same text, same catalog entry). */
internal fun renderMeritOpenNotice(parent: Container) {
    parent.div(tr("Diese Abstimmung ist offen und wird namentlich gespeichert")) { addCssClasses("text-muted small") }
}

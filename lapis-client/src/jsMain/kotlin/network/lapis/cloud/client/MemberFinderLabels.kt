package network.lapis.cloud.client

import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderRules
import network.lapis.cloud.shared.rpc.MemberFinderAdultConfirmationRequiredException
import network.lapis.cloud.shared.rpc.MemberFinderConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberFinderConsentRequiredException
import network.lapis.cloud.shared.rpc.MemberFinderLimitReachedException
import network.lapis.cloud.shared.rpc.MemberFinderNotEligibleException
import network.lapis.cloud.shared.rpc.MemberFinderNoteInvalidException
import network.lapis.cloud.shared.rpc.MemberFinderNoticeOutdatedException
import network.lapis.cloud.shared.rpc.MemberFinderRateLimitedException
import network.lapis.cloud.shared.rpc.MemberFinderRegionNotVisibleException
import network.lapis.cloud.shared.rpc.MemberFinderRegrantCooldownException
import network.lapis.cloud.shared.rpc.MemberFinderUnavailableException

/*
 * V1.9.145 -- the fixed texts of the member finder client. Pure functions, no DOM, so they can be tested in isolation. Every text is a literal
 * `tr()`: the group size is NEVER a number (only the coarse band), and a request is NEVER free text -- the recipient sees one of two fixed templates.
 */

internal fun memberFinderPurposeLabel(purpose: MemberFinderPurpose): String =
    when (purpose) {
        MemberFinderPurpose.GROUP_FOUNDING -> tr("Ortsgruppe gründen")
        MemberFinderPurpose.MEETUP -> tr("Stammtisch besuchen")
    }

internal fun memberFinderInterestLabel(interest: MemberFinderInterest): String =
    when (interest) {
        MemberFinderInterest.GROUP_FOUNDING -> tr("Ortsgruppe gründen")
        MemberFinderInterest.MEETUP -> tr("Stammtisch besuchen")
        MemberFinderInterest.BOTH -> tr("Beides")
    }

/** The group size is shown only as a band; `null` means the own region is not visible yet (fewer than five participants). */
internal fun memberFinderBandLabel(band: MemberFinderBand?): String =
    when (band) {
        null -> tr("Noch nicht sichtbar (weniger als fünf Teilnehmende)")
        MemberFinderBand.B5_9 -> tr("5 bis 9 Teilnehmende")
        MemberFinderBand.B10_19 -> tr("10 bis 19 Teilnehmende")
        MemberFinderBand.B20_PLUS -> tr("20 oder mehr Teilnehmende")
    }

internal fun memberFinderOutgoingStateLabel(state: MemberFinderOutgoingState): String =
    when (state) {
        MemberFinderOutgoingState.PENDING -> tr("Offen")
        MemberFinderOutgoingState.ENDED -> tr("Beendet")
        MemberFinderOutgoingState.WITHDRAWN -> tr("Zurückgezogen")
        MemberFinderOutgoingState.CONNECTED -> tr("Verbunden")
    }

/** The fixed text a recipient sees for a request: the sender is not named, and the purpose is the only content besides an optional note. */
internal fun memberFinderRequestTemplate(purpose: MemberFinderPurpose): String =
    when (purpose) {
        MemberFinderPurpose.GROUP_FOUNDING -> tr("Ein Mitglied aus Ihrer Region möchte eine Ortsgruppe gründen.")
        MemberFinderPurpose.MEETUP -> tr("Ein Mitglied aus Ihrer Region möchte sich zu einem Stammtisch treffen.")
    }

/** The purposes a member may choose for a request: both for BOTH, exactly the own interest otherwise (the server rejects any other). */
internal fun memberFinderPurposesFor(interest: MemberFinderInterest?): List<MemberFinderPurpose> =
    when (interest) {
        null -> emptyList()
        MemberFinderInterest.GROUP_FOUNDING -> listOf(MemberFinderPurpose.GROUP_FOUNDING)
        MemberFinderInterest.MEETUP -> listOf(MemberFinderPurpose.MEETUP)
        MemberFinderInterest.BOTH -> listOf(MemberFinderPurpose.GROUP_FOUNDING, MemberFinderPurpose.MEETUP)
    }

/** From this many characters on the counter turns into a warning (the limit is [MemberFinderRules.NOTE_MAX_LENGTH]). */
internal const val MEMBER_FINDER_NOTE_WARN_AT: Int = 180

/** Length of the note as the server will count it (the same normalisation); over the limit it is the raw length. */
internal fun memberFinderNoteCount(raw: String): Int =
    try {
        MemberFinderRules.normalizeNote(raw)?.length ?: 0
    } catch (e: MemberFinderNoteInvalidException) {
        raw.length
    }

internal fun memberFinderNoteSendable(raw: String): Boolean =
    try {
        MemberFinderRules.normalizeNote(raw)
        true
    } catch (e: MemberFinderNoteInvalidException) {
        false
    }

/**
 * The fixed sentence for a typed refusal of the finder, or `null` for every other failure (those go through `guarded`). Kilua RPC transmits only the
 * exception TYPE, never the server's message, so every type has its own text here.
 */
internal fun memberFinderErrorText(e: Throwable): String? =
    when (e) {
        is MemberFinderConsentOutdatedException -> tr("Der Text wurde aktualisiert, bitte neu lesen.")
        is MemberFinderAdultConfirmationRequiredException -> tr("Bitte bestätigen Sie, dass Sie volljährig sind.")
        is MemberFinderNotEligibleException -> tr("Der Mitglieder-Finder steht für Ihr Konto derzeit nicht zur Verfügung.")
        is MemberFinderConsentRequiredException -> tr("Bitte willigen Sie zuerst in den Mitglieder-Finder ein.")
        is MemberFinderRegrantCooldownException -> tr("Eine erneute Teilnahme ist erst 24 Stunden nach Ihrem Widerruf möglich.")
        is MemberFinderRegionNotVisibleException -> tr("Ihre Region ist noch nicht sichtbar. Es fehlen noch Teilnehmende.")
        is MemberFinderLimitReachedException ->
            tr("Sie haben bereits die höchste Zahl offener Anfragen. Ziehen Sie eine zurück oder warten Sie, bis eine endet.")
        is MemberFinderNoteInvalidException -> tr("Die Notiz ist zu lang oder enthält unzulässige Zeichen.")
        is MemberFinderRateLimitedException -> tr("Zu viele Aktionen in kurzer Zeit. Bitte versuchen Sie es später erneut.")
        is MemberFinderUnavailableException -> tr("Der Mitglieder-Finder ist auf dieser Instanz nicht eingeschaltet.")
        is MemberFinderNoticeOutdatedException -> tr("Der Hinweis wurde aktualisiert, bitte erneut lesen.")
        else -> null
    }

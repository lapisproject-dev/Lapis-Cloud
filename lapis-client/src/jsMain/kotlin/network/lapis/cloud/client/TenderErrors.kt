package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.rpc.ConflictException

/*
 * V1.9.202 -- the German text for a refusal of the tender service.
 *
 * HONEST LIMIT: Kilua RPC transmits only the TYPE of a service exception, never its message (see the KDoc of `guarded`), so on today's transport
 * the message of a [ConflictException] reaches this file EMPTY and every refusal shows [fallbackText]. The table below is kept exact anyway
 * (a scan test on the server, `TenderErrorsCoverageTest`, fails the build as soon as a `ConflictException("...")` literal of the tender code has no
 * entry here), so the specific text appears the moment a message can travel -- and it documents, per refusal, what the person should be told.
 * The screens additionally PREVENT the common refusals before the call (lengths, deadline, amount, guest rule), so a refusal is a race, not a routine.
 * A raw server text is never shown. In the keys, `#` stands for a number (the limit).
 */

/** The text for every refusal that is not (or not yet) known. */
internal fun tenderFallbackText(): String = gettext("Die Aktion ist gerade nicht möglich. Bitte laden Sie die Seite neu.")

private fun exactTexts(): Map<String, String> =
    mapOf(
        "Only a draft can be changed" to gettext("Nur ein Entwurf kann geändert werden."),
        "Only a draft can be published" to gettext("Nur ein Entwurf kann veröffentlicht werden."),
        "The deadline must be at least one hour in the future" to gettext("Die Frist muss mindestens eine Stunde in der Zukunft liegen."),
        "The tender was changed concurrently, please reload" to
            gettext("Die Ausschreibung wurde zwischenzeitlich geändert. Bitte laden Sie die Seite neu."),
        "The tender is not available for this action" to gettext("Diese Aktion ist im jetzigen Stand der Ausschreibung nicht möglich."),
        "Guests can only bid on tenders paid in euro" to
            gettext("Gäste können nur auf Ausschreibungen bieten, die in Euro vergütet werden."),
        "The tender cannot take more bids" to gettext("Die Ausschreibung kann keine weiteren Angebote aufnehmen."),
        "There is no bid to withdraw" to gettext("Es gibt kein Angebot, das zurückgezogen werden kann."),
        "An award motion exists only for the criterion BEST_OFFER" to
            gettext("Einen Vergabeantrag gibt es nur bei der Vergabe nach dem besten Angebot."),
        "The tender must be closed first" to gettext("Die Frist der Ausschreibung muss zuerst abgelaufen sein."),
        "The tender has no bid to decide on" to gettext("Die Ausschreibung hat kein Angebot, über das entschieden werden könnte."),
        "The registered award motion cannot be replaced" to gettext("Der eingetragene Vergabeantrag kann nicht mehr ersetzt werden."),
        "The bid is not in the running" to gettext("Dieses Angebot ist nicht mehr im Rennen."),
        "The bidder is no longer eligible" to gettext("Der Bieter darf für diese Ausschreibung nicht mehr berücksichtigt werden."),
        "The cheapest bid must be chosen" to gettext("Der Zuschlag muss an das günstigste Angebot gehen."),
        "A justification is required for a price tie" to gettext("Bei gleichem Preis ist eine Begründung nötig."),
        "A justification is required" to gettext("Bitte geben Sie eine Begründung an."),
        "No award motion is registered" to gettext("Es ist noch kein Vergabeantrag festgelegt."),
        "The award motion is not decided yet" to gettext("Über den Vergabeantrag ist noch nicht entschieden."),
        "The award motion was not adopted" to gettext("Der Vergabeantrag wurde nicht angenommen."),
        "The award motion was decided before the deadline" to gettext("Der Vergabeantrag wurde vor Ablauf der Frist entschieden."),
        "A rejection needs a reason" to gettext("Eine Ablehnung braucht eine Begründung."),
        "The description cannot be rendered" to gettext("Die Beschreibung kann nicht dargestellt werden. Bitte vereinfachen Sie sie."),
        "Only LTR is available for tenders" to gettext("Ausschreibungen sind vorerst nur in LTR möglich."),
        "The price must have at most 2 decimal places" to gettext("Der Preis darf höchstens zwei Nachkommastellen haben."),
        "The price is too large" to gettext("Der Preis ist zu groß."),
        "The binding motion is not an adopted resolution of its committee" to
            gettext("Der Antrag ist kein angenommener Beschluss seines Gremiums."),
        "This motion cannot serve as the award motion of the tender" to
            gettext("Dieser Antrag kann nicht als Vergabeantrag dienen."),
        "The award motion of a tender can only be decided by the committee quorum or a meritocratic vote" to
            gettext("Über einen Vergabeantrag entscheidet nur die Beschlussfähigkeit des Gremiums oder eine meritokratische Abstimmung."),
    )

/** Messages with a number in them: the key carries `#` for the number, the text carries `%1` for it. */
private fun numberedTexts(): List<Pair<String, (String) -> String>> =
    listOf(
        "The title must have 1 to # characters" to { n -> gettext("Der Titel muss 1 bis %1 Zeichen haben.", n) },
        "The description must have 1 to # characters" to { n -> gettext("Die Beschreibung muss 1 bis %1 Zeichen haben.", n) },
        "The expected result must have 1 to # characters" to { n -> gettext("Das erwartete Ergebnis muss 1 bis %1 Zeichen haben.", n) },
        "The bid text must have 1 to # characters" to { n -> gettext("Der Angebotstext muss 1 bis %1 Zeichen haben.", n) },
        "The reason must have 1 to # characters" to { n -> gettext("Der Grund muss 1 bis %1 Zeichen haben.", n) },
        "The reason must have at most # characters" to { n -> gettext("Der Grund darf höchstens %1 Zeichen haben.", n) },
        "The justification must have at most # characters" to { n -> gettext("Die Begründung darf höchstens %1 Zeichen haben.", n) },
        "The price must be at least #" to { n -> gettext("Der Preis muss mindestens %1 betragen.", n) },
        "The deadline must be within # days" to { n -> gettext("Die Frist darf höchstens %1 Tage in der Zukunft liegen.", n) },
    )

private val NUMBERED_PARTS: Regex = Regex("#")

/** `Motion <uuid> has an ...` of the decision-path guards: one text for all of them. */
private val MOTION_PATH: Regex = Regex("^Motion \\S+ (has an?|is) .*")

/**
 * The German text for the message of a refusal; `null`/blank/unknown gives [tenderFallbackText]. A raw server text is NEVER returned.
 */
internal fun tenderErrorText(serverMessage: String?): String {
    val message = serverMessage?.trim().orEmpty()
    if (message.isEmpty()) return tenderFallbackText()
    exactTexts()[message]?.let { return it }
    numberedTexts().forEach { (template, text) ->
        val parts = template.split(NUMBERED_PARTS)
        val pattern = Regex("^" + Regex.escape(parts[0]) + "([0-9.]+)" + Regex.escape(parts[1]) + "$")
        pattern.matchEntire(message)?.let { return text(it.groupValues[1]) }
    }
    if (MOTION_PATH.matches(message)) return gettext("Für diesen Antrag läuft bereits ein Entscheidungsweg oder er ist schon entschieden.")
    return tenderFallbackText()
}

/** The result of [tenderCall]: the value, or the German text of the tender's own refusal (a failure of any other kind is handled by [guarded]). */
internal class TenderOutcome<T>(
    val value: T?,
    val refusal: String?,
)

/**
 * Runs a call of a tender screen. A [ConflictException] is the tender's own refusal: its German text (see [tenderErrorText]) is RETURNED, no toast
 * is shown; every other failure goes the usual way through [guarded] (toast, session expiry) and gives a `null` value without a refusal.
 */
internal suspend fun <T> tenderCall(block: suspend () -> T): TenderOutcome<T> {
    var refusal: String? = null
    val result =
        guarded {
            try {
                block()
            } catch (e: ConflictException) {
                refusal = tenderErrorText(e.message)
                null
            }
        }
    return TenderOutcome(value = result, refusal = refusal)
}

/** Like [tenderCall], but shows the refusal as an error toast and returns only the value. */
internal suspend fun <T> tenderGuarded(block: suspend () -> T): T? {
    val outcome = tenderCall(block)
    outcome.refusal?.let { notifyError(it) }
    return outcome.value
}

package network.lapis.cloud.client.encounter

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceRole

/**
 * V1.9.67 Begegnungsraum Stufe 1 -- the ONE place that knows which words and which picture belong to which [EncounterProfile]. Everything
 * else in `client/encounter/` asks [EncounterTerms] (`termsFor(space.profile)`) and never branches on the profile itself, never writes
 * "Kanzel", "Ordner", "Gemeinde", "Amen" or "Gottesdienst" as a literal: `ClientEncounterVocabularyTripwireTest` fails the build if it does.
 *
 * All texts are FIXED literals inside `tr("...")`/`gettext("...")` calls (never composed from data), so the catalog extraction finds
 * them and there is no path for free text. Two flavours of the same sentence exist where a call site needs both: a `...Content` text
 * (`tr`, for widget content, resolved by KVision when it renders) and a plain one (`gettext`, for attributes and option lists, where the
 * KVision marker must not appear).
 *
 * The profile describes the ROOM, never a person.
 */
internal class EncounterTerms(
    val profile: EncounterProfile,
) {
    private val church: Boolean get() = profile == EncounterProfile.CHURCH_SERVICE

    /** Floor-plan scene of this profile: the front (altar or podium, once at the top) and the repeating row of benches or chairs. */
    val sceneFrontPath: String
        get() = if (church) "$SCENE_DIR/church/front.svg" else "$SCENE_DIR/hall/front.svg"
    val sceneRowPath: String
        get() = if (church) "$SCENE_DIR/church/row.svg" else "$SCENE_DIR/hall/row.svg"

    fun profileName(): String = if (church) gettext("Gottesdienst") else gettext("Versammlung")

    fun profileDescription(): String =
        if (church) {
            gettext("Eine Kanzel spricht, die Gemeinde hört zu. Reaktionen: Hand und Amen.")
        } else {
            gettext("Podium und Moderation sprechen, die Teilnehmenden hören zu. Reaktionen: Hand und Applaus.")
        }

    /** Label of the person's role inside a running session. The steward is NOT the bare msgid "Ordner" (it already means "folder"). */
    fun presenceRoleLabel(role: EncounterPresenceRole): String =
        when (role) {
            EncounterPresenceRole.PULPIT -> if (church) gettext("Kanzel") else gettext("Podium")
            EncounterPresenceRole.STEWARD -> if (church) gettext("Ordner im Gottesdienst") else gettext("Moderation")
            EncounterPresenceRole.CONGREGATION -> if (church) gettext("Gemeinde") else gettext("Teilnehmende")
        }

    /** Label of an OFFICE of the room in the role editor. */
    fun spaceRoleLabel(role: EncounterSpaceRole): String =
        when (role) {
            EncounterSpaceRole.PULPIT -> if (church) gettext("Kanzel (spricht)") else gettext("Podium (spricht)")
            EncounterSpaceRole.STEWARD ->
                if (church) {
                    gettext(
                        "Ordner (spricht und moderiert)",
                    )
                } else {
                    gettext("Moderation (spricht und moderiert)")
                }
        }

    fun enterAsSpeakerLabel(): String =
        if (church) gettext("Eintreten und Kanzel übernehmen") else gettext("Eintreten und Podium übernehmen")

    fun enterAsStewardLabel(): String = if (church) gettext("Eintreten als Ordner") else gettext("Eintreten als Moderation")

    /** V1.9.95 -- the blessing exists in the church profile only; `null` = no blessing (assembly). */
    fun blessingLabel(): String? = if (church) tr("Segen") else null

    /** V1.9.95 -- the one fixed sentence a screen reader hears when the blessing is spoken. */
    fun blessingAnnouncement(): String = tr("Der Segen wird gesprochen")

    /** V1.9.95 -- the word under the cross in the display. */
    fun blessingWord(): String = tr("Segen")

    /** V1.9.96 -- the bell exists in the church profile only; `null` = no bell (assembly). The ONE place that decides whether a room has it. */
    fun bellLabel(): String? = if (church) tr("Glocke läuten") else null

    /** V1.9.96 -- the word next to the bell symbol in the sign. */
    fun bellWord(): String = tr("Glocke")

    /** V1.9.96 -- the one fixed sentence a screen reader hears when the bell rings. */
    fun bellAnnouncement(): String = tr("Die Glocke läutet")

    /** V1.9.98 -- neutral: the second line of the bell sign and of the blessing sign while this browser's sound is off. */
    fun soundOffNote(): String = tr("Ton aus")

    /** V1.9.96 -- the second line of the bell sign while this browser's bell sound is off (V1.9.98: delegates to [soundOffNote]). */
    fun bellSoundOffNote(): String = soundOffNote()

    /** V1.9.96 -- the label of the bell sound switch in the device panel. */
    fun bellSoundSwitchLabel(): String = tr("Glockenton")

    /** V1.9.96 -- the hint under the switch: the choice is local. */
    fun bellSoundSwitchHint(): String = tr("Gilt nur für diesen Browser.")

    /** V1.9.97 -- what the switch does, above the hint. */
    fun bellSoundSwitchEffect(): String = tr("Erklingt beim Ruf und beim Segen.")

    /** V1.9.96 -- the name of the gear button when the bell switch is all it holds. */
    fun bellDeviceButtonLabel(): String = gettext("Glockenton einstellen")

    // ── V1.9.138: the temporary floor ("Rederecht") ─────────────────────────────────────────────────────────────

    /** The heading of the list of raised hands (the order is the order of the requests). */
    fun floorQueueHeadingContent(): String = tr("Wortmeldungen")

    fun floorGrantLabel(): String = gettext("Wort erteilen")

    fun floorTakeBackLabel(): String = gettext("Wort entziehen")

    fun floorHandBackLabel(): String = gettext("Wort zurückgeben")

    fun floorExtendShortLabel(): String = gettext("+1 Min")

    fun floorExtendLongLabel(): String = gettext("+2 Min")

    fun floorMicrophoneOnLabel(): String = gettext("Mikrofon einschalten")

    /** The banner of the person who has the floor; [time] is `MM:SS`. */
    fun floorSelfBanner(time: String): String = gettext("Sie haben das Wort · noch %1", time)

    /** The line of the office holder's list: [name] is untrusted and must already be sanitised by the caller; [time] is `MM:SS`. */
    fun floorHolderLine(
        name: String,
        time: String,
    ): String = gettext("%1 spricht · noch %2", name, time)

    /** The banner everybody else sees: NO name, no seat -- only that somebody of the room speaks. */
    fun floorCommunityBanner(time: String): String =
        if (church) {
            gettext("Gerade spricht jemand aus der Gemeinde · noch %1", time)
        } else {
            gettext("Gerade spricht jemand aus der Versammlung · noch %1", time)
        }

    fun floorFinalNote(): String = gettext("Gleich zu Ende")

    fun floorBusyNote(): String = gettext("Es spricht gerade jemand.")

    fun floorMaxReachedNote(): String = gettext("Höchstdauer 10 Min erreicht")

    fun floorMicrophoneOnState(): String = gettext("Mikrofon an")

    fun floorMicrophoneOffState(): String = gettext("Mikrofon aus")

    /** The four fixed sentences a screen reader hears: the floor was given (to somebody else / to me), ten seconds are left, it ended. */
    fun floorGivenAnnouncement(): String = gettext("Wort erteilt")

    fun floorSelfAnnouncement(): String = gettext("Sie haben das Wort")

    fun floorTenSecondsAnnouncement(): String = gettext("Noch 10 Sekunden")

    fun floorEndedAnnouncement(): String = gettext("Wort beendet")

    /** The one text of every refused grant (the server's reason does not reach the client, Kilua transmits the exception type only). */
    fun floorGrantFailedNote(): String = gettext("Das Wort konnte nicht erteilt werden. Bitte versuchen Sie es erneut.")

    fun emptyStageContent(): String = if (church) tr("Die Kanzel ist noch leer.") else tr("Das Podium ist noch leer.")

    fun emptyStage(): String = if (church) gettext("Die Kanzel ist noch leer.") else gettext("Das Podium ist noch leer.")

    /** Accessible name of the stage region with the (untrusted, already sanitised) [names]. */
    fun stageNamed(names: String): String = if (church) gettext("Kanzel: %1", names) else gettext("Podium: %1", names)

    /** Fact line of a room in the list: who speaks. [names] is untrusted and goes through an untrusted helper by the caller. */
    fun speakersFact(names: String): String = stageNamed(names)

    fun silencedNoteContent(): String =
        if (church) tr("Sie wurden von einem Ordner stummgeschaltet.") else tr("Sie wurden von der Moderation stummgeschaltet.")

    fun eventEndedContent(): String = if (church) tr("Der Gottesdienst ist beendet.") else tr("Die Versammlung ist beendet.")

    fun removalWarningContent(): String =
        if (church) {
            tr("Die Person verlässt den Raum und kann bis zum Ende dieses Gottesdienstes nicht wieder eintreten.")
        } else {
            tr("Die Person verlässt den Raum und kann bis zum Ende dieser Versammlung nicht wieder eintreten.")
        }

    fun transmissionNoteContent(): String =
        if (church) {
            tr(
                "Wird die Kanzel übertragen, sehen Zuschauer außerhalb des Raums nur Bild und Ton der Kanzel. " +
                    "Sie selbst werden nicht übertragen.",
            )
        } else {
            tr(
                "Wird das Podium übertragen, sehen Zuschauer außerhalb des Raums nur Bild und Ton des Podiums. " +
                    "Sie selbst werden nicht übertragen.",
            )
        }

    fun article9NoteContent(): String =
        if (church) {
            tr(
                "Die Teilnahme an einem Gottesdienst kann Rückschlüsse auf religiöse Überzeugungen zulassen (besondere Kategorie personenbezogener Daten, Art. 9 DSGVO). Dieser Hinweis ist keine Rechtsberatung.",
            )
        } else {
            tr(
                "Die Teilnahme an einer Versammlung kann Rückschlüsse auf politische Meinungen oder weltanschauliche Überzeugungen zulassen (besondere Kategorie personenbezogener Daten, Art. 9 DSGVO). Dieser Hinweis ist keine Rechtsberatung.",
            )
        }

    fun liveBadgeContent(): String = if (church) tr("Live (nur Kanzel)") else tr("Live (nur Podium)")

    fun streamOnlyStageContent(): String = if (church) tr("Nur Kanzel") else tr("Nur Podium")

    fun nobodyOnStageContent(): String = if (church) tr("Niemand ist auf der Kanzel.") else tr("Niemand ist auf dem Podium.")

    /** The polite live-region sentence for an event reaction of the audience: a fixed sentence, never a count. */
    fun reactionFromAudience(option: EncounterReactionOption): String =
        when (option) {
            EncounterReactionOption.HAND -> ""
            EncounterReactionOption.AMEN -> if (church) gettext("Amen aus der Gemeinde") else gettext("Amen von den Teilnehmenden")
            EncounterReactionOption.APPLAUSE ->
                if (church) {
                    gettext(
                        "Applaus aus der Gemeinde",
                    )
                } else {
                    gettext("Applaus von den Teilnehmenden")
                }
            EncounterReactionOption.HEART -> if (church) gettext("Herz aus der Gemeinde") else gettext("Herz von den Teilnehmenden")
        }

    // ── V1.9.79 Stufe 2a: Sitzplatzwahl ────────────────────────────────────────────────────────────────

    /** Accessible name of the whole seat plan (a group of buttons). */
    fun seatPlanLabel(): String = gettext("Sitzplan")

    /** Described-by sentence of the seat plan: the audience never has a microphone (listen-only). */
    fun audienceMutedNote(): String =
        if (church) gettext("Die Mikrofone der Gemeinde sind aus.") else gettext("Die Mikrofone der Teilnehmenden sind aus.")

    /** A free seat: row, position and the invitation to take it. No name anywhere. */
    fun freeSeatLabel(
        row: Int,
        position: Int,
    ): String =
        if (church) {
            gettext("Reihe %1, Platz %2, frei. Diesen Platz wählen", row, position)
        } else {
            gettext("Reihe %1, Stuhl %2, frei. Diesen Stuhl wählen", row, position)
        }

    /** A free seat for somebody who cannot sit (an office holder): no invitation. */
    fun freeSeatPlainLabel(
        row: Int,
        position: Int,
    ): String = if (church) gettext("Reihe %1, Platz %2, frei", row, position) else gettext("Reihe %1, Stuhl %2, frei", row, position)

    /** A taken seat: row, position and the INITIALS spelled letter by letter ("M S"), never the name. */
    fun takenSeatLabel(
        row: Int,
        position: Int,
        spelledInitials: String,
    ): String =
        if (church) {
            gettext("Reihe %1, Platz %2, besetzt, %3", row, position, spelledInitials)
        } else {
            gettext("Reihe %1, Stuhl %2, besetzt, %3", row, position, spelledInitials)
        }

    /** The seat the viewer chose. */
    fun ownSeatLabel(
        row: Int,
        position: Int,
    ): String =
        if (church) gettext("Reihe %1, Platz %2, Ihr Platz", row, position) else gettext("Reihe %1, Stuhl %2, Ihr Stuhl", row, position)

    /** Appended to a taken seat's name while its occupant's hand is up. */
    fun handRaisedSuffix(): String = gettext(", Hand erhoben")

    /** Appended to a taken seat's name while a reaction is shown at it. */
    fun reactionSuffix(option: EncounterReactionOption): String = gettext(", Reaktion: %1", reactionLabel(option))

    /** The short visible marker at the viewer's own seat. */
    fun ownSeatMarker(): String = gettext("Sie")

    /** Polite sentence after the viewer chose a seat. */
    fun seatedAnnouncement(
        row: Int,
        position: Int,
    ): String =
        if (church) {
            gettext("Sie sitzen jetzt in Reihe %1, Platz %2.", row, position)
        } else {
            gettext("Sie sitzen jetzt in Reihe %1, auf Stuhl %2.", row, position)
        }

    fun seatReleasedAnnouncement(): String =
        if (church) gettext("Sie haben Ihren Platz freigegeben.") else gettext("Sie haben Ihren Stuhl freigegeben.")

    /** The chosen seat was taken by somebody else in the meantime. */
    fun seatTakenAnnouncement(): String =
        if (church) {
            gettext("Dieser Platz ist inzwischen besetzt. Bitte wählen Sie einen anderen.")
        } else {
            gettext("Dieser Stuhl ist inzwischen besetzt. Bitte wählen Sie einen anderen.")
        }

    /** The hint above the pews for somebody who has no seat yet. */
    fun chooseSeatHint(): String =
        if (church) {
            gettext("Tippen Sie auf einen freien Platz, um sich zu setzen.")
        } else {
            gettext("Tippen Sie auf einen freien Stuhl, um sich zu setzen.")
        }

    /** The row of people who have not chosen a seat. */
    fun unseatedRowLabel(): String = gettext("Noch ohne Platz")

    fun releaseSeatLabel(): String = if (church) gettext("Platz freigeben") else gettext("Stuhl freigeben")

    /** The same label as widget content (`tr`), for the visible button. */
    fun releaseSeatLabelContent(): String = if (church) tr("Platz freigeben") else tr("Stuhl freigeben")

    /** V1.9.80: the control that turns the pulpit's sound up again while one sits at a table (icon-only bar control and its tooltip). */
    fun pulpitLouderLabel(): String = if (church) tr("Kanzel lauter") else tr("Podium lauter")

    /** The summary of the list alternative to the seat plan (for people who do not use the picture). */
    fun chooseFromListSummary(): String = if (church) gettext("Platz über eine Liste wählen") else gettext("Stuhl über eine Liste wählen")

    fun yourSeatStatus(
        row: Int,
        position: Int,
    ): String =
        if (church) gettext("Ihr Platz: Reihe %1, Platz %2", row, position) else gettext("Ihr Stuhl: Reihe %1, Stuhl %2", row, position)

    fun noSeatYetStatus(): String = if (church) gettext("Sie haben noch keinen Platz.") else gettext("Sie haben noch keinen Stuhl.")

    fun noFreeSeatNote(): String = if (church) gettext("Es ist kein Platz mehr frei.") else gettext("Es ist kein Stuhl mehr frei.")

    /** A button of the list alternative: one free seat. */
    fun listSeatButtonLabel(
        row: Int,
        position: Int,
    ): String = if (church) gettext("Reihe %1, Platz %2 wählen", row, position) else gettext("Reihe %1, Stuhl %2 wählen", row, position)

    /** Accessible name of the list of the seats. */
    fun audienceName(): String = presenceRoleLabel(EncounterPresenceRole.CONGREGATION)

    /** Accessible name of the strip of the stewards. */
    fun stewardsName(): String = presenceRoleLabel(EncounterPresenceRole.STEWARD)

    // ── V1.9.143: the optional recording of the pulpit ───────────────────────────────────────────────────────────

    /** The banner everybody in the room sees while a recording runs (the long form). */
    fun recordingBanner(): String =
        if (church) {
            gettext("Aufzeichnung läuft · Nur die Kanzel wird aufgezeichnet")
        } else {
            gettext("Aufzeichnung läuft · Nur das Podium wird aufgezeichnet")
        }

    /** The banner in a narrow room or the full screen (the short form). */
    fun recordingBannerShort(): String = gettext("Aufnahme")

    /** The two fixed sentences a screen reader hears when the recording starts / ends. */
    fun recordingStartedAnnouncement(): String = gettext("Aufzeichnung gestartet")

    fun recordingEndedAnnouncement(): String = gettext("Aufzeichnung beendet")

    /** Accessible names of the toggle in the bar (the state is `aria-pressed`, the label stays the verb of the next step). */
    fun recordingStartLabel(): String = gettext("Aufzeichnung starten")

    fun recordingStopLabel(): String = gettext("Aufzeichnung beenden")

    fun recordingAwaitingLabel(): String =
        if (church) gettext("Warte auf Zustimmung der Kanzel") else gettext("Warte auf Zustimmung des Podiums")

    /** The option in the room administration. */
    fun recordingRoomOptionLabel(): String =
        if (church) gettext("Kanzel-Aufzeichnung in diesem Raum erlauben") else gettext("Podium-Aufzeichnung in diesem Raum erlauben")

    /** The same option where the room's kind is not chosen yet (the create form): neutral, names both words once. */
    fun recordingRoomOptionNeutralLabel(): String = gettext("Aufzeichnung von Kanzel bzw. Podium in diesem Raum erlauben")

    /** The hints under the option. */
    fun recordingRoomOptionHint(): String =
        gettext("Aufgezeichnet wird nur, wer das Amt der Kanzel bzw. des Podiums innehat, nie die Gemeinde.")

    fun recordingRoomOptionNotOfferedHint(): String = gettext("Von der Instanz nicht freigegeben.")

    fun recordingRoomOptionClosedOnlyHint(): String = gettext("Nur bei geschlossenem Raum einschaltbar.")

    /** The sentence in the entry panel of a room whose pulpit may be recorded (the operator's own notice follows it). */
    fun recordingEntryNoteContent(): String =
        if (church) {
            tr("Lapis Cloud speichert keine Anwesenheitsliste. In diesem Raum kann die Kanzel aufgezeichnet werden, die Gemeinde nicht.")
        } else {
            tr(
                "Lapis Cloud speichert keine Anwesenheitsliste. In diesem Raum kann das Podium aufgezeichnet werden, die Teilnehmenden nicht.",
            )
        }

    /** The consent dialog of the person at the pulpit. */
    fun recordingConsentTitle(): String = gettext("Aufzeichnung zustimmen?")

    fun recordingConsentAccept(): String = gettext("Aufzeichnung zustimmen")

    fun recordingConsentDecline(): String = gettext("Nicht aufzeichnen")

    fun recordingMaxReached(): String = gettext("Höchstzahl gleichzeitiger Aufnahmen erreicht")

    /** One fixed text per refused start (the server's reason does not reach the client, Kilua transmits the exception type only). */
    fun recordingAskedNote(): String =
        if (church) gettext("Die Kanzel wurde um Zustimmung gebeten.") else gettext("Das Podium wurde um Zustimmung gebeten.")

    fun recordingNobodyThereNote(): String = if (church) gettext("Niemand ist auf der Kanzel.") else gettext("Niemand ist auf dem Podium.")

    fun recordingFloorNote(): String =
        if (church) {
            gettext("Solange jemand aus der Gemeinde spricht, wird nicht aufgezeichnet.")
        } else {
            gettext("Solange jemand aus der Versammlung spricht, wird nicht aufgezeichnet.")
        }

    fun recordingUnavailableNote(): String = gettext("Der Aufzeichnungsdienst ist gerade nicht verfügbar.")

    fun recordingFailedNote(): String = gettext("Die Aufzeichnung konnte nicht geändert werden. Bitte versuchen Sie es erneut.")

    private companion object {
        const val SCENE_DIR = "/assets/encounter-themes"
    }
}

internal fun termsFor(profile: EncounterProfile): EncounterTerms = EncounterTerms(profile)

/** Visible label of a configurable reaction (plain text, also the accessible name of its button). */
internal fun reactionLabel(option: EncounterReactionOption): String =
    when (option) {
        EncounterReactionOption.HAND -> gettext("Hand heben")
        EncounterReactionOption.AMEN -> gettext("Amen")
        EncounterReactionOption.APPLAUSE -> gettext("Applaus")
        EncounterReactionOption.HEART -> gettext("Herz")
    }

/** The same label as widget content (`tr`). */
internal fun reactionLabelContent(option: EncounterReactionOption): String =
    when (option) {
        EncounterReactionOption.HAND -> tr("Hand heben")
        EncounterReactionOption.AMEN -> tr("Amen")
        EncounterReactionOption.APPLAUSE -> tr("Applaus")
        EncounterReactionOption.HEART -> tr("Herz")
    }

/** Icon class (decorative, `aria-hidden`) of a reaction at a seat and on its button. */
internal fun reactionGlyph(option: EncounterReactionOption): String =
    when (option) {
        EncounterReactionOption.HAND -> ActionIcon.HAND.css
        EncounterReactionOption.AMEN -> ActionIcon.AMEN.css
        EncounterReactionOption.APPLAUSE -> ActionIcon.APPLAUSE.css
        EncounterReactionOption.HEART -> ActionIcon.HEART.css
    }

/** The [ActionIcon] of a reaction button. */
internal fun reactionActionIcon(option: EncounterReactionOption): ActionIcon =
    when (option) {
        EncounterReactionOption.HAND -> ActionIcon.HAND
        EncounterReactionOption.AMEN -> ActionIcon.AMEN
        EncounterReactionOption.APPLAUSE -> ActionIcon.APPLAUSE
        EncounterReactionOption.HEART -> ActionIcon.HEART
    }

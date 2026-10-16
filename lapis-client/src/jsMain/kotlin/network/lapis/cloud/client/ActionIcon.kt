package network.lapis.cloud.client

/**
 * The one table "action -> Font Awesome icon" (V1.9.43, guideline R57). One picture means one verb: the same icon is never used for
 * two different actions, and a verb has exactly one icon. Font Awesome 7 (bundled through `kvision-fontawesome`, no CDN) -- every
 * name below is verified against the bundled stylesheet by `ActionButtonDomTest`.
 *
 * The aliases `fa-times`, `fa-edit` and `fa-refresh` are forbidden in sources (tripwire R57): use [CLEAR]/[CANCEL]/[CLOSE],
 * [EDIT] and [REFRESH].
 */
enum class ActionIcon(
    val css: String,
) {
    /** Refresh a view; V1.9.75: also "generate a fresh value" (the temporary password's "Neu erzeugen"). */
    REFRESH("fas fa-arrows-rotate"),
    EDIT("fas fa-pen"),
    DELETE("fas fa-trash"),

    /** Detach an assignment (remove a member from a list); NOT the bin -- the object itself survives. */
    REMOVE("fas fa-circle-minus"),
    SAVE("fas fa-floppy-disk"),
    CANCEL("fas fa-xmark"),
    CLOSE("fas fa-xmark"),
    CLEAR("fas fa-xmark"),
    ADD("fas fa-plus"),
    SEARCH("fas fa-magnifying-glass"),
    FILTER("fas fa-filter"),
    DOWNLOAD("fas fa-download"),
    UPLOAD("fas fa-upload"),
    COPY("fas fa-copy"),
    PRINT("fas fa-print"),
    VIEW("fas fa-eye"),
    OPEN_EXTERNAL("fas fa-arrow-up-right-from-square"),
    BACK("fas fa-arrow-left"),
    NEXT("fas fa-arrow-right"),
    APPROVE("fas fa-circle-check"),
    REJECT("fas fa-circle-xmark"),
    SEND("fas fa-paper-plane"),
    REVOKE("fas fa-ban"),
    LOCK("fas fa-lock"),

    /** Change who may see an object (visibility / access level). NOT [LOCK] (reserved). */
    ACCESS("fas fa-user-lock"),

    /** V1.9.75: change one's own password / set a member's password. NOT [LOCK] (reserved), NOT [ACCESS]. */
    PASSWORD("fas fa-key"),
    EXPORT("fas fa-file-export"),

    /** V1.9.94: the encounter room's device picker (microphone, camera, speaker); in the room used for that purpose only. */
    SETTINGS("fas fa-gear"),
    UNDO("fas fa-rotate-left"),

    /**
     * Welle V1.9.57: "protected" -- a value or an action the peer protection withholds (an administrator's address data, an action against
     * another administrator that needs the second administrator). It marks the REASON text next to a disabled control and the masked
     * value; it is never an action of its own and NOT [LOCK] (reserved) or [ACCESS] (who may see an object).
     */
    PROTECTED("fas fa-shield-halved"),

    /**
     * Welle V1.9.62 "Begegnungsraum": the verbs of the encounter room, one picture each and none of them reused: [ENTER] a room
     * (also the "open this room" link), [OPEN_DOORS]/[CLOSE_DOORS] the room itself, [ARCHIVE] a room away, [HAND] raise or lower the
     * hand, [AMEN] the one reaction, [CHAT] show the chat, [SCENE] show or hide the decorative scene, [SILENCE] withdraw a person's
     * right to send, [BROADCAST] start/stop the transmission of the pulpit, [OFFICES] who holds which office of a room, [PEOPLE] the list of the people present, [MICROPHONE]/[CAMERA] the office holder's own devices.
     * V1.9.83: [ENTER] is also "sign in" (login button, navbar link) -- the counterpart of [LEAVE] (sign out).
     */
    ENTER("fas fa-right-to-bracket"),

    /** Leave a room (V1.9.62) or leave the whole app = sign out (V1.9.75); one verb, one picture. */
    LEAVE("fas fa-right-from-bracket"),
    OPEN_DOORS("fas fa-door-open"),
    CLOSE_DOORS("fas fa-door-closed"),
    ARCHIVE("fas fa-box-archive"),
    HAND("fas fa-hand"),
    AMEN("fas fa-hands-praying"),
    CHAT("fas fa-comments"),
    SCENE("fas fa-image"),
    SILENCE("fas fa-volume-xmark"),

    /** Transmission of the pulpit; V1.9.66: also the conference live stream (same verb: transmitting live). */
    BROADCAST("fas fa-tower-broadcast"),
    OFFICES("fas fa-user-tie"),
    PEOPLE("fas fa-users"),
    MICROPHONE("fas fa-microphone"),
    CAMERA("fas fa-video"),

    /** V1.9.66: start/stop the recording of a conference (record symbol). Shown in the conference bar only. */
    RECORD("fas fa-circle-dot"),

    /** V1.9.66: end the meeting for everyone (power off). Deliberately NOT [LEAVE] (own exit) and NOT [CLOSE_DOORS] (encounter room). */
    END_FOR_ALL("fas fa-power-off"),

    /** V1.9.67: the configurable reactions of an encounter room besides [HAND] and [AMEN]: applause and a heart. */
    APPLAUSE("fas fa-hands-clapping"),
    HEART("fas fa-heart"),

    /** V1.9.67: stage mode of the encounter room, enter and leave the full screen. */
    FULLSCREEN("fas fa-expand"),
    FULLSCREEN_EXIT("fas fa-compress"),

    /** V1.9.70: share the screen / stop sharing it (conference dock bar). */
    SCREEN_SHARE("fas fa-display"),

    /**
     * V1.9.115: the views of the video conference -- [VIEW_GALLERY] the tile grid (also the face of the bar's "Ansicht" button while the gallery
     * is shown), [VIEW_SPEAKER] the speaker large, [PIN] pin one person (a toggle on a tile and the "Person anheften" view). The screen-share
     * view reuses [SCREEN_SHARE] (same verb: the shared screen).
     */
    VIEW_GALLERY("fas fa-table-cells-large"),
    VIEW_SPEAKER("fas fa-chalkboard-user"),
    PIN("fas fa-thumbtack"),

    /** V1.9.70: leave a running call from the dock bar (hang up). Same picture as the call's own "Verlassen" button. */
    HANG_UP("fas fa-phone-slash"),

    /**
     * V1.9.71: the verbs of the floating conference window -- [FLOAT_CORNER] jump to the next corner, [FLOAT_SIZE] cycle the size,
     * [COLLAPSE] fold the window into the bar, [FLOAT_WINDOW] show the conference as a floating window again (bar button).
     */
    FLOAT_CORNER("fas fa-arrows-up-down-left-right"),
    FLOAT_SIZE("fas fa-up-right-and-down-left-from-center"),
    COLLAPSE("fas fa-window-minimize"),
    FLOAT_WINDOW("fas fa-window-restore"),

    /**
     * V1.9.74: show the "Mehr" sheet -- the controls of an encounter-room bar that did not fit. (The conference bar's own "Mehr" keeps its raw string.)
     * V1.9.101: also "Weitere anzeigen", the next page of the person search ([RemoteMemberPicker]) -- the same verb, "show more".
     */
    MORE("fas fa-ellipsis"),

    /** V1.9.79: give up one's own seat in the encounter room ("Platz freigeben"). Used once, in the room's seat area. */
    RELEASE_SEAT("fas fa-chair"),

    /** V1.9.80: get up from a table of the encounter room ("Tisch verlassen"): back to the plenum. */
    LEAVE_TABLE("fas fa-users-slash"),

    /** V1.9.80: a moderator lifts the quiet of a table ("Beruhigung aufheben"); the opposite of [SILENCE] at table level. */
    LIFT_QUIET("fas fa-volume-high"),

    /** V1.9.80: a person at a table turns the pulpit's sound up again ("Kanzel lauter"); the pulpit is quieter while one sits at a table. */
    PULPIT_LOUDER("fas fa-bullhorn"),

    /** V1.9.80: a moderator sends a person from a table back to the plenum ("Ins Plenum setzen"); NOT [REMOVE] (the person stays in the room). */
    SEND_TO_PLENUM("fas fa-people-roof"),

    /**
     * V1.9.85: the active-speaker mark of the video conference (tile, floating window, roster). A state display, NEVER the face of a
     * button (R58) -- it is not an action. Not [LIFT_QUIET] (a button there) although the glyph is the same.
     */
    SPEAKING("fas fa-volume-high"),

    /** V1.9.95: the blessing of the pulpit in the encounter room (church profile, PULPIT office only); symbol only, liturgy group. Assigned to this purpose only. */
    BLESSING("fas fa-cross"),

    /**
     * V1.9.96: the bell of the pulpit in the encounter room (church profile, PULPIT office only); symbol only, liturgy group. Assigned to
     * this purpose only in the encounter room's bar (the same symbol also stands for the entry notice and for the dunning levels elsewhere,
     * see `docs/architecture/action-icons.adoc`).
     */
    BELL("fas fa-bell"),

    /**
     * V1.9.143: start/stop of the pulpit recording in the encounter room (PULPIT/STEWARD present, room option on), and the recording mark of the banner
     * that tells everybody. Assigned to this purpose only (NOT [RECORD], which the conference bar keeps); the bar button is the icon-only factory of
     * the encounter bar (R58 named exception c).
     */
    PULPIT_RECORD("fas fa-clapperboard"),

    /**
     * V1.9.108: the three microphone states a moderator sees in the conference roster, STATE displays next to their text (never the face
     * of a button): [MIC_OFF] the person is muted, [MIC_MODERATION] the moderators muted them (a CSS badge sets it apart from [MIC_OFF]),
     * [MIC_LOCKED] the moderators locked the room's microphones. [MIC_LOCKED] uses the glyph that [LOCK] reserves; [LOCK] itself stays
     * unused.
     */
    MIC_OFF("fas fa-microphone-slash"),
    MIC_MODERATION("fas fa-microphone-lines-slash"),
    MIC_LOCKED("fas fa-lock"),

    /**
     * V1.9.125 "Mitfahrten": the verbs of the carpool centre -- [REQUEST_RIDE] ask to ride along on an offer ("Mitfahrt anfragen"; NOT [SEND], which
     * "Kontakt aufnehmen" keeps on the same card), [PASSENGERS] open the passenger management of an own offer, [ADD_PERSON] add or invite a passenger.
     * Confirming and declining use [APPROVE]/[REJECT], withdrawing [UNDO], removing a passenger [REMOVE].
     */
    REQUEST_RIDE("fas fa-car-side"),
    PASSENGERS("fas fa-user-group"),
    ADD_PERSON("fas fa-user-plus"),

    /**
     * V1.9.127: the "Reaktion" button of the video conference bar (opens the menu of the five reactions). The raised hand reuses [HAND] (the same
     * verb as in the encounter room). A button only; the reactions themselves are shown as emoji on the tiles.
     */
    REACT("fas fa-face-smile"),

    /**
     * V1.9.129 "Video-Overlays": move an element of the overlay library one place up or down (admin card of the video conference). Only used
     * for that ordering, never for scrolling or navigation (those use [BACK]/[NEXT]).
     */
    MOVE_UP("fas fa-arrow-up"),
    MOVE_DOWN("fas fa-arrow-down"),

    /**
     * V1.9.139 "Mitfahr-Nachrichten": open the short message history of a CONFIRMED ride ([MESSAGES]; NOT [CHAT], which is the chat of a room, and NOT
     * [SEND], which sends the message). Closing the history of both sides for good is a text button without a picture of its own ([REVOKE]'s
     * glyph would suggest a ban).
     */
    MESSAGES("fas fa-comment"),
}

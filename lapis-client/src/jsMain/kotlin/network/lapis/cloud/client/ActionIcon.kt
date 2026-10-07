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
    EXPORT("fas fa-file-export"),
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
     */
    ENTER("fas fa-right-to-bracket"),
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
}

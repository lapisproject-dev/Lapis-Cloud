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
    EXPORT("fas fa-file-export"),
    SETTINGS("fas fa-gear"),
    UNDO("fas fa-rotate-left"),
}

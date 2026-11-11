package network.lapis.cloud.shared.domain

/**
 * V151 -- the ONLY place that composes a member title and a name. `member.display_name` is never recomposed from the title: members
 * imported from LupaBB already carry the title inside their display name ("Dr. Max Muster"), so [withTitle] must not prepend it twice.
 */
object MemberNames {
    /**
     * `"<title> <displayName>"`, unless the title is blank or the display name already is / starts with the title (case-insensitive,
     * followed by a space). Titles are not split: "Prof. Dr." plus "Dr. X" gives "Prof. Dr. Dr. X" on purpose.
     */
    fun withTitle(
        title: String?,
        displayName: String,
    ): String {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return displayName
        val d = displayName.trim().lowercase()
        val tl = t.lowercase()
        if (d == tl || d.startsWith("$tl ")) return displayName
        return "$t $displayName"
    }
}

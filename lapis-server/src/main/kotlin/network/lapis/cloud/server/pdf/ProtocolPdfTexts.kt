package network.lapis.cloud.server.pdf

/**
 * V1.9.240 -- the fixed texts of the Protokoll PDF, behind STABLE KEYS. Server PDFs are German only (like every other server PDF, see
 * [EinladungPdfGenerator]); there is no server-side gettext. The keys and the `lang` parameter are the hook for the "language of the template per
 * organisation" of the concept: a second language is a second table, no generator code changes. Unknown language falls back to German.
 */
internal object ProtocolPdfTexts {
    const val TITLE = "protocol.title"
    const val VERSION = "protocol.version"
    const val CORRECTION_HEADING = "protocol.correction"
    const val DATE = "protocol.date"
    const val LOCATION = "protocol.location"
    const val CHAIR = "protocol.chair"
    const val MINUTE_TAKER = "protocol.minuteTaker"
    const val ATTENDANCE = "protocol.attendance"
    const val PRESENT = "protocol.present"
    const val EXCUSED = "protocol.excused"
    const val ABSENT = "protocol.absent"
    const val REPRESENTED = "protocol.represented"
    const val GUESTS = "protocol.guests"
    const val NONE = "protocol.none"
    const val QUORUM = "protocol.quorum"
    const val QUORUM_OVERRIDDEN = "protocol.quorumOverridden"
    const val ITEM = "protocol.item"
    const val NO_COURSE = "protocol.noCourse"
    const val RESOLUTION = "protocol.resolution"
    const val FIGURES = "protocol.figures"
    const val FIGURES_WITHHELD = "protocol.figuresWithheld"
    const val SECRET_BALLOT = "protocol.secretBallot"
    const val STATUS_ADOPTED = "protocol.statusAdopted"
    const val STATUS_REJECTED = "protocol.statusRejected"
    const val STATUS_POSTPONED = "protocol.statusPostponed"
    const val TASKS = "protocol.tasks"
    const val TASK_DUE = "protocol.taskDue"
    const val APPROVAL = "protocol.approval"
    const val APPROVAL_APPROVED = "protocol.approvalApproved"
    const val APPROVAL_CORRECTIONS = "protocol.approvalCorrections"
    const val APPROVAL_REJECTED = "protocol.approvalRejected"
    const val UNASSIGNED = "protocol.unassigned"
    const val CONFIRMED_BY = "protocol.confirmedBy"
    const val ROLE_LEAD = "protocol.roleLead"
    const val ROLE_MINUTES = "protocol.roleMinutes"
    const val ROLE_BOTH = "protocol.roleBoth"
    const val MINUTES_VACANT = "protocol.minutesVacant"
    const val CHECKSUM = "protocol.checksum"
    const val DISCLAIMER = "protocol.disclaimer"

    private val de =
        mapOf(
            TITLE to "Protokoll",
            VERSION to "Fassung",
            CORRECTION_HEADING to "Korrekturvermerk",
            DATE to "Termin",
            LOCATION to "Ort",
            CHAIR to "Sitzungsleitung",
            MINUTE_TAKER to "Protokollführung",
            ATTENDANCE to "Anwesenheit",
            PRESENT to "Anwesend",
            EXCUSED to "Entschuldigt",
            ABSENT to "Unentschuldigt abwesend",
            REPRESENTED to "Vertreten",
            GUESTS to "Gäste",
            NONE to "keine",
            QUORUM to "Beschlussfähigkeit",
            QUORUM_OVERRIDDEN to "ausdrücklich festgestellt",
            ITEM to "TOP",
            NO_COURSE to "(kein Verlauf festgehalten)",
            RESOLUTION to "Beschluss",
            FIGURES to "Ja %1, Nein %2, Enthaltungen %3",
            FIGURES_WITHHELD to "Ergebnis zurückgehalten (zu wenige Stimmen)",
            SECRET_BALLOT to "geheime Abstimmung",
            STATUS_ADOPTED to "angenommen",
            STATUS_REJECTED to "abgelehnt",
            STATUS_POSTPONED to "vertagt",
            TASKS to "Aufgaben",
            TASK_DUE to "bis %1",
            APPROVAL to "Genehmigung des Protokolls der Sitzung vom %1",
            APPROVAL_APPROVED to "genehmigt",
            APPROVAL_CORRECTIONS to "mit Korrekturen genehmigt",
            APPROVAL_REJECTED to "nicht genehmigt",
            UNASSIGNED to "Beschlüsse ohne Tagesordnungspunkt",
            CONFIRMED_BY to "Digital bestätigt von",
            ROLE_LEAD to "Sitzungsleitung",
            ROLE_MINUTES to "Protokollführung",
            ROLE_BOTH to "als Sitzungsleitung und Protokollführung",
            MINUTES_VACANT to "Protokollführung: nicht besetzt",
            CHECKSUM to "Prüfsumme (SHA-256):",
            DISCLAIMER to "Einfache elektronische Bestätigung in Lapis Cloud, keine qualifizierte Signatur.",
        )

    private val tables = mapOf("de" to de)

    /** The text for [key] in [lang] (German when unknown); `%1`, `%2`, ... are replaced by [args]. A missing key is the key itself. */
    fun text(
        key: String,
        lang: String = "de",
        vararg args: String,
    ): String {
        var result = (tables[lang] ?: de)[key] ?: de[key] ?: key
        args.forEachIndexed { index, value -> result = result.replace("%${index + 1}", value) }
        return result
    }
}

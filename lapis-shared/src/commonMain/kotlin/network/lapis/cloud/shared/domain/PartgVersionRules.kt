package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * V1.9.291 (R6) -- the deadline of the accountability report. ASSUMPTION (not confirmed by a lawyer, no section number quoted): the report is
 * due on 30 September of the year after the report year. The proposal is only a proposal; the board confirms or changes the date.
 */
object PartgDeadlineRules {
    const val INFO_DAYS = 90
    const val WARNING_DAYS = 30

    fun proposal(year: Int): LocalDate = LocalDate(year + 1, 9, 30)

    /** A stored deadline lies between 1 January of the year after and 31 December of the third year after the report year. */
    fun valid(
        year: Int,
        deadline: LocalDate,
    ): Boolean = deadline >= LocalDate(year + 1, 1, 1) && deadline <= LocalDate(year + 3, 12, 31)

    fun daysUntil(
        today: LocalDate,
        deadline: LocalDate,
    ): Int = today.daysUntil(deadline)

    /** A final year is never loud. Overdue = `DANGER`, at most 30 days = `WARNING`, at most 90 days = `INFO`. */
    fun level(
        today: LocalDate,
        deadline: LocalDate,
        final: Boolean,
    ): PartgDeadlineLevel {
        if (final) return PartgDeadlineLevel.NONE
        val days = daysUntil(today = today, deadline = deadline)
        return when {
            days < 0 -> PartgDeadlineLevel.DANGER
            days <= WARNING_DAYS -> PartgDeadlineLevel.WARNING
            days <= INFO_DAYS -> PartgDeadlineLevel.INFO
            else -> PartgDeadlineLevel.NONE
        }
    }

    /** Strict ISO date (`yyyy-MM-dd`), `null` for anything else. */
    fun parseIso(raw: String): LocalDate? =
        if (Regex("""\d{4}-\d{2}-\d{2}""").matches(raw)) runCatching { LocalDate.parse(raw) }.getOrNull() else null
}

/**
 * The retention period of kept versions. OPEN LEGAL QUESTION (Q4): the concept says 10 years; this is a floor, not legal advice. A version may
 * only be removed when BOTH its report year and the year it was kept lie more than N full years back.
 */
object PartgVersionRetention {
    const val MIN_YEARS = 10
    const val MAX_YEARS = 30

    fun validYears(n: Int): Boolean = n in MIN_YEARS..MAX_YEARS

    fun eligible(
        fiscalYear: Int,
        frozenAtYear: Int,
        today: LocalDate,
        n: Int,
    ): Boolean = today.year > fiscalYear + n && today.year > frozenAtYear + n
}

object PartgVersionRules {
    const val MAX_COMMENT = 500

    /** At most this many kept versions per (year, scope); the counter never reuses a number. */
    const val MAX_PER_SCOPE = 200
    const val MAX_DELETE_IDS = 500

    /** Result of [checkComment]: `ok = false` means invalid; `value` is the trimmed comment or `null` for "none". */
    data class CommentCheck(
        val ok: Boolean,
        val value: String?,
    )

    /** Trim, 1..500 characters, no control characters (so no line break either); blank or `null` means no comment. */
    fun checkComment(raw: String?): CommentCheck {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return CommentCheck(ok = true, value = null)
        if (trimmed.length > MAX_COMMENT || trimmed.any { it.isISOControl() }) return CommentCheck(ok = false, value = null)
        return CommentCheck(ok = true, value = trimmed)
    }

    fun matchesTypedNumber(
        typed: Int,
        versionNumber: Int,
    ): Boolean = typed == versionNumber

    /** Strict lower-case 64-hex SHA-256. */
    fun isHash(raw: String): Boolean = Regex("""[0-9a-f]{64}""").matches(raw)

    /** The most senior status among whole-party versions; `DRAFT` when there is none. */
    fun yearStatus(wholePartyStatuses: Collection<PartgVersionStatus>): PartgVersionStatus =
        wholePartyStatuses.maxByOrNull { it.ordinal } ?: PartgVersionStatus.DRAFT
}

/**
 * The fixed assurance text of the Finanzvorstand. A DRAFT template, not reviewed legally, no qualified signature. The text is never edited:
 * a new wording gets a new code and is added, so the code stored with a declaration always names the exact text that was shown.
 */
object PartgDeclarationTemplate {
    const val CODE = "ENTWURF-V1"

    private const val TEXT_V1 =
        "Ich habe die Zahlen und Angaben der Version {version} des Rechenschaftsberichts für das Jahr {year} geprüft. " +
            "Nach meinem Kenntnisstand sind sie vollständig und richtig. Diese Versicherung bezieht sich ausdrücklich auf den " +
            "festgehaltenen Inhalt mit der unten genannten Prüfsumme. Sie ist ein interner Vermerk als Arbeitshilfe; " +
            "sie ist keine qualifizierte " +
            "elektronische Signatur und ersetzt weder die Unterschrift noch die Einreichung."

    fun text(
        code: String,
        versionNumber: Int,
        year: Int,
    ): String? =
        when (code) {
            CODE -> TEXT_V1.replace("{version}", versionNumber.toString()).replace("{year}", year.toString())
            else -> null
        }
}

/** A starting text for a request to extend the deadline. Only shown and copied; nothing is stored or sent. No section number is quoted. */
object PartgExtensionTemplate {
    fun text(
        partyName: String,
        year: Int,
        deadline: LocalDate,
    ): String =
        "Betreff: Antrag auf Verlängerung der Frist zur Einreichung des Rechenschaftsberichts $year\n\n" +
            "Sehr geehrte Damen und Herren,\n\n" +
            "hiermit beantragen wir für den Rechenschaftsbericht $year der Partei $partyName eine Verlängerung der Einreichungsfrist " +
            "(bisher: $deadline). Die Gründe sind: [Begründung eintragen].\n\n" +
            "Wir bitten um eine Bestätigung der neuen Frist.\n\n" +
            "Mit freundlichen Grüßen\n[Name, Funktion]"
}

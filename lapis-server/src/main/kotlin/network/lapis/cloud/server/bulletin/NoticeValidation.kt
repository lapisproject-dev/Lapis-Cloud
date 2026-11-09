package network.lapis.cloud.server.bulletin

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.NoticeRules
import network.lapis.cloud.shared.rpc.ConflictException
import kotlin.time.Duration.Companion.days

/**
 * V1.9.297 -- the text and date rules of a notice. Text: trimmed, length-checked, no control characters (a line break only in the body). Dates: the
 * author picks the LAST day on which the notice is shown (an organization calendar day, class D); the stored `expires_at` is a class-A UTC instant,
 * the start of the NEXT organization day. The window is `[today + 1, day of (created_at + 90 days) - 1]`, which keeps
 * `expires_at <= created_at + 90 days` (the database CHECK) for every time zone.
 */
internal object NoticeValidation {
    fun title(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.any { it.isISOControl() }) throw ConflictException("title must not contain control characters")
        if (trimmed.length < NoticeRules.TITLE_MIN || trimmed.length > NoticeRules.TITLE_MAX) {
            throw ConflictException("title must have ${NoticeRules.TITLE_MIN} to ${NoticeRules.TITLE_MAX} characters")
        }
        return trimmed
    }

    fun body(raw: String): String {
        val trimmed = raw.replace("\r\n", "\n").trim()
        if (trimmed.any { it.isISOControl() && it != '\n' }) throw ConflictException("text must not contain control characters")
        if (trimmed.isEmpty() || trimmed.length > NoticeRules.BODY_MAX) {
            throw ConflictException("text must have 1 to ${NoticeRules.BODY_MAX} characters")
        }
        return trimmed
    }

    /** `null` for blank. A longer note is refused, never cut. */
    fun removalNote(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isISOControl() && it != '\n' }) throw ConflictException("note must not contain control characters")
        if (trimmed.length > NoticeRules.REMOVAL_NOTE_MAX) {
            throw ConflictException("note must have at most ${NoticeRules.REMOVAL_NOTE_MAX} characters")
        }
        return trimmed
    }

    fun replyText(raw: String): String {
        val trimmed = raw.replace("\r\n", "\n").trim()
        if (trimmed.isEmpty() || trimmed.length > NoticeRules.REPLY_MAX) {
            throw ConflictException("message must have 1 to ${NoticeRules.REPLY_MAX} characters")
        }
        return trimmed
    }

    /** The stored instant for "last visible day [expiresOn]": the start of the following organization day, as a class-A UTC value. */
    fun expiresAtFor(
        expiresOn: LocalDate,
        orgZone: TimeZone = OrganizationTimeZone.current(),
    ): LocalDateTime = expiresOn.plus(1, DateTimeUnit.DAY).atStartOfDayIn(orgZone).toLocalDateTime(ServerClock.zone)

    /** The inverse: the last visible organization day of a stored `expires_at`. */
    fun expiresOnOf(expiresAt: LocalDateTime): LocalDate = OrganizationTimeZone.dateOf(expiresAt).plus(-1, DateTimeUnit.DAY)

    /** The earliest day an author may pick: tomorrow (organization calendar). */
    fun minExpiresOn(now: LocalDateTime): LocalDate = OrganizationTimeZone.dateOf(now).plus(1, DateTimeUnit.DAY)

    /** The latest day: keeps `expires_at <= created_at + 90 days`. */
    fun maxExpiresOn(createdAt: LocalDateTime): LocalDate {
        val limit = (createdAt.toInstant(ServerClock.zone) + NoticeRules.MAX_DAYS.days).toLocalDateTime(ServerClock.zone)
        return OrganizationTimeZone.dateOf(limit).plus(-1, DateTimeUnit.DAY)
    }

    /** The default last visible day of a new notice. */
    fun defaultExpiresOn(now: LocalDateTime): LocalDate = OrganizationTimeZone.dateOf(now).plus(NoticeRules.DEFAULT_DAYS, DateTimeUnit.DAY)

    /**
     * Checks a picked day against the window and returns the `expires_at` to store. [currentExpiresAt] (an edit) lets the SAME day stand, so that
     * a text edit of an old notice is not blocked by the window.
     */
    fun checkedExpiresAt(
        expiresOn: LocalDate,
        createdAt: LocalDateTime,
        now: LocalDateTime,
        currentExpiresAt: LocalDateTime? = null,
    ): LocalDateTime {
        val candidate = expiresAtFor(expiresOn = expiresOn)
        if (candidate == currentExpiresAt) return candidate
        if (expiresOn < minExpiresOn(now) || expiresOn > maxExpiresOn(createdAt)) {
            throw ConflictException("expiresOn is outside the allowed period")
        }
        return candidate
    }
}

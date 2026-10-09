package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventVisibility

/**
 * Welle V1.4.3.x "Veranstaltungen: BOARD/ADMIN-Verwaltungsoberfläche" -- pure, DOM-independent
 * validation for [EventsScreen]'s create/edit form. Mirrors
 * `network.lapis.cloud.server.events.EventPolicy.validate` field for field (title/description
 * blank+length, location-or-online required, start/end parse+ordering, registration-close-before-
 * start, capacity positive integer, fee non-negative with at most two decimal places, and the
 * past-start-date check with the same [EventFormRawInput]/`existingStartsAt` edit-exception
 * `EventPolicy.validate` itself documents), so an obviously-invalid submit never round-trips to the
 * server only to bounce back as a `BadRequestException`. The server re-validates everything
 * authoritatively regardless -- same "loose mirror, not the security boundary" posture
 * `Validation.kt`'s own KDoc documents; this file is deliberately separate from `Validation.kt`
 * (composed-form validation that builds a whole [EventInput], not individual predicates) rather than
 * folded into it. `feeCurrency` is not part of [EventFormRawInput] -- always `"EUR"`, the form
 * offers no currency choice (see `EventPolicy.validate`'s own hardcoded-EUR check).
 */
sealed class EventFormResult {
    data class Ok(
        val input: EventInput,
    ) : EventFormResult()

    data class Error(
        val message: String,
    ) : EventFormResult()
}

data class EventFormRawInput(
    val title: String,
    val description: String,
    val locationText: String,
    val onlineUrl: String,
    val startsAtRaw: String,
    val endsAtRaw: String,
    val registrationClosesAtRaw: String,
    val capacityRaw: String,
    val feeAmountRaw: String,
    val visibility: EventVisibility,
    val roomId: String?,
    /** V1.9.82 -- additive and defaulted, so no pre-existing caller breaks. */
    val summary: String = "",
    val coverImageAlt: String = "",
    val onlineUrlPublic: Boolean = false,
)

private const val MAX_TITLE_LENGTH = 200
private const val MAX_DESCRIPTION_LENGTH = 8000
private const val MAX_SUMMARY_LENGTH = 300
private const val MAX_COVER_IMAGE_ALT_LENGTH = 500

/** V1.9.82 -- public teaser limit, shared with the live counter in the form. */
const val EVENT_SUMMARY_MAX_LENGTH: Int = MAX_SUMMARY_LENGTH

// Host: no whitespace, no userinfo (`@` before the first `/`, `?` or `#`) and only host characters (no `_`, which makes URI.host null on the server).
private val HTTPS_LINK = Regex("^https://[A-Za-z0-9.\\-\\[\\]:]+([/?#]\\S*)?$", RegexOption.IGNORE_CASE)

/** Shape check mirroring the server's `EventText.isHttpsUrl` (the server stays authoritative): an `https://` link with a plain host, no userinfo. */
fun isHttpsLink(raw: String): Boolean = HTTPS_LINK.matches(raw.trim())

/**
 * [existingStartsAt] gespiegelt aus `EventPolicy.validate`: die Vergangenheits-Prüfung greift nur,
 * wenn `startsAt` gegenüber dem geladenen `EventDto.startsAt` geändert wurde. `null` bei Neuanlage.
 * [now] defaults to the real current time (same `Clock.System.now().toLocalDateTime(...)` idiom
 * this client already uses elsewhere, e.g. `CommitteesScreen.kt`) but is a parameter so the
 * past-date checks are deterministically unit-testable.
 */
fun validateEventForm(
    raw: EventFormRawInput,
    existingStartsAt: LocalDateTime?,
    now: LocalDateTime = organizationNow(),
): EventFormResult {
    val title = raw.title.trim()
    if (title.isBlank()) return EventFormResult.Error(tr("Bitte einen Titel angeben."))
    if (title.length > MAX_TITLE_LENGTH) {
        return EventFormResult.Error(gettext("Titel ist zu lang (maximal %1 Zeichen).", MAX_TITLE_LENGTH))
    }

    val description = raw.description.trim()
    if (description.isBlank()) return EventFormResult.Error(tr("Bitte eine Beschreibung angeben."))
    if (description.length > MAX_DESCRIPTION_LENGTH) {
        return EventFormResult.Error(gettext("Beschreibung ist zu lang (maximal %1 Zeichen).", MAX_DESCRIPTION_LENGTH))
    }

    val locationText = raw.locationText.trim().takeIf { it.isNotBlank() }
    val onlineUrl = raw.onlineUrl.trim().takeIf { it.isNotBlank() }
    if (locationText == null && onlineUrl == null) {
        return EventFormResult.Error(tr("Mindestens ein Veranstaltungsort (Adresse oder Online-Link) ist erforderlich."))
    }

    val summary = raw.summary.trim().takeIf { it.isNotBlank() }
    if (summary != null && summary.length > MAX_SUMMARY_LENGTH) {
        return EventFormResult.Error(gettext("Kurztext ist zu lang (maximal %1 Zeichen).", MAX_SUMMARY_LENGTH))
    }
    val coverImageAlt = raw.coverImageAlt.trim().takeIf { it.isNotBlank() }
    if (coverImageAlt != null && coverImageAlt.length > MAX_COVER_IMAGE_ALT_LENGTH) {
        return EventFormResult.Error(gettext("Bildbeschreibung ist zu lang (maximal %1 Zeichen).", MAX_COVER_IMAGE_ALT_LENGTH))
    }
    if (raw.onlineUrlPublic && (onlineUrl == null || !isHttpsLink(onlineUrl))) {
        return EventFormResult.Error(tr("Der Online-Link kann nur öffentlich angezeigt werden, wenn er mit https:// beginnt."))
    }

    val startsAt =
        runCatching { LocalDateTime.parse(raw.startsAtRaw.trim()) }.getOrNull()
            ?: return EventFormResult.Error(tr("Bitte einen gültigen Beginn angeben."))
    val endsAt =
        runCatching { LocalDateTime.parse(raw.endsAtRaw.trim()) }.getOrNull()
            ?: return EventFormResult.Error(tr("Bitte ein gültiges Ende angeben."))
    if (endsAt < startsAt) return EventFormResult.Error(tr("Ende darf nicht vor dem Beginn liegen."))

    // Mirrors EventPolicy.validate's `existingStartsAt` edit-exception exactly: the past-date check
    // only fires when startsAt is genuinely changing (or this is a brand-new event, existingStartsAt
    // == null) -- editing an already-started event's title/description/capacity/etc. must stay
    // possible.
    if (startsAt != existingStartsAt && startsAt < now) {
        return EventFormResult.Error(tr("Beginn darf nicht in der Vergangenheit liegen."))
    }

    val registrationClosesAtRawTrimmed = raw.registrationClosesAtRaw.trim()
    val registrationClosesAt =
        if (registrationClosesAtRawTrimmed.isBlank()) {
            null
        } else {
            val parsed =
                runCatching { LocalDateTime.parse(registrationClosesAtRawTrimmed) }.getOrNull()
                    ?: return EventFormResult.Error(tr("Bitte einen gültigen Anmeldeschluss angeben oder das Feld leer lassen."))
            if (parsed > startsAt) {
                return EventFormResult.Error(tr("Anmeldeschluss darf nicht nach dem Veranstaltungsbeginn liegen."))
            }
            parsed
        }

    val capacityRawTrimmed = raw.capacityRaw.trim()
    val capacity =
        if (capacityRawTrimmed.isBlank()) {
            null
        } else {
            val parsed = capacityRawTrimmed.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                return EventFormResult.Error(tr("Kapazität muss eine positive ganze Zahl sein, wenn angegeben."))
            }
            parsed
        }

    val feeAmountRawTrimmed = raw.feeAmountRaw.trim()
    if (!isNonNegativeDecimalWithAtMostTwoDecimals(feeAmountRawTrimmed)) {
        return EventFormResult.Error(tr("Bitte eine gültige, nicht-negative Teilnahmegebühr mit höchstens zwei Nachkommastellen angeben."))
    }
    val feeAmount = feeAmountRawTrimmed.toDouble().toDecimal()

    return EventFormResult.Ok(
        EventInput(
            title = title,
            description = description,
            locationText = locationText,
            onlineUrl = onlineUrl,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = capacity,
            feeAmount = feeAmount,
            feeCurrency = "EUR",
            visibility = raw.visibility,
            registrationClosesAt = registrationClosesAt,
            roomId = raw.roomId,
            summary = summary,
            coverImageAlt = coverImageAlt,
            onlineUrlPublic = raw.onlineUrlPublic,
        ),
    )
}

/**
 * Unlike `Validation.isPositiveDecimal` (which requires `> 0`, e.g. a donation amount), an event's
 * fee is explicitly allowed to be `0` (a free event) -- see `EventPolicy.validate`'s own
 * `feeAmount.compareTo(BigDecimal.ZERO) < 0` check (`>= 0`, not `> 0`) and its `scale() > 2` check
 * (at most two decimal places).
 */
private fun isNonNegativeDecimalWithAtMostTwoDecimals(value: String): Boolean {
    val amount = value.toDoubleOrNull() ?: return false
    if (!amount.isFinite() || amount < 0.0) return false
    val decimalIndex = value.indexOf('.')
    if (decimalIndex < 0) return true
    return value.length - decimalIndex - 1 <= 2
}

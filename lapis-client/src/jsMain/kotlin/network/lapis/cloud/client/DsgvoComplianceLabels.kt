package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import network.lapis.cloud.shared.domain.DataBreachIncidentDto

// V1.9.49: the pure helpers of `DsgvoComplianceScreen.kt` (banner texts, breach ordering, tri-state and date parsing) moved here unchanged, in the
// same package, so `DsgvoComplianceScreenTest` still reaches them.

// ================================================================================================
// Pure helpers -- covered by DsgvoComplianceScreenTest.kt
// ================================================================================================

/** D11's exact inline caption for a `SIGNED`-but-now-inactive AVV row. */
fun avvReviewOverdueCaption(): String = tr("Prüftermin überschritten -- als inaktiv markiert, bis neu geprüft.")

/** D8(a)'s exact DSFA-tab banner copy. */
fun dsfaBannerText(): String =
    tr(
        "Die Risikoeinstufung (LOW/MEDIUM/HIGH/CRITICAL) hier ist eine Visualisierungshilfe aus " +
            "Eintrittswahrscheinlichkeit × Schadenshöhe -- keine Art. 35 DSGVO Erforderlichkeits-" +
            "Feststellung. Ob eine Datenschutz-Folgenabschätzung tatsächlich erforderlich ist, legen " +
            "ausschließlich Sie im Feld \"DSFA erforderlich\" fest; dieses System berechnet das nicht.",
    )

/** D8(a)'s exact Breach-tab banner copy. */
fun breachBannerText(): String =
    tr(
        "Die angezeigte Frist ist die gesetzliche 72-Stunden-Uhr nach Art. 33 Abs. 1 DSGVO ab " +
            "Kenntnisnahme -- sie entscheidet nicht, ob überhaupt eine Meldepflicht besteht (das legen " +
            "ausschließlich Sie im Feld \"Meldung an Aufsichtsbehörde erforderlich\" fest) und ersetzt " +
            "keine rechtliche Prüfung des Meldezeitpunkts. Bei einem echten Vorfall: " +
            "Datenschutzbeauftragte/n oder Anwalt/Anwältin hinzuziehen.",
    )

/** D7's exact display group order: `OVERDUE` first, then `DUE_SOON`, `WITHIN_WINDOW`, `SATISFIED`. */
fun breachDeadlineDisplayRank(status: BreachDeadlineStatus): Int =
    when (status) {
        BreachDeadlineStatus.OVERDUE -> 0
        BreachDeadlineStatus.DUE_SOON -> 1
        BreachDeadlineStatus.WITHIN_WINDOW -> 2
        BreachDeadlineStatus.SATISFIED -> 3
    }

/** D7: re-sorts the server's newest-first list into the design's escalation-first order --
 * grouped by [breachDeadlineDisplayRank], each group by [DataBreachIncidentDto.authorityNotificationDeadline]
 * ascending, so an overdue incident is never below the fold. */
fun sortBreachIncidentsForDisplay(incidents: List<DataBreachIncidentDto>): List<DataBreachIncidentDto> =
    incidents.sortedWith(
        compareBy(
            { breachDeadlineDisplayRank(it.deadlineStatus) },
            { it.authorityNotificationDeadline },
        ),
    )

/** Renders a `Boolean?` human-input field's three real states as plain text -- deliberately NOT a
 * colored badge, since this is always a human-entered legal call (`dpiaRequired`/
 * `authorityNotificationRequired`), not a lifecycle status or fixed classification this client
 * itself derives.
 *
 * gettext() (not tr()) -- this returns a plain String, not passed directly to a widget
 * constructor, so tr()'s deferred marker never resolves. See I18nCatalogManager KDoc. */
fun triStateBooleanLabel(value: Boolean?): String =
    when (value) {
        true -> gettext("Ja")
        false -> gettext("Nein")
        null -> gettext("Noch nicht festgelegt")
    }

/** Inverse of [triStateBooleanLabel]'s underlying select value -- `""`/`null` means "not set". */
fun parseTriStateBoolean(raw: String?): Boolean? =
    when (raw) {
        "true" -> true
        "false" -> false
        else -> null
    }

/** Blank means "not selected" -- generic helper for any nullable enum backed by an optional
 * `select` with a leading blank "Nicht festgelegt" option (mirrors [parseOptionalDateTime]'s own
 * "blank means no value" posture for the risk-level/status filters and inputs on this screen). */
inline fun <reified T : Enum<T>> parseOptionalEnum(raw: String?): T? = raw?.trim()?.takeIf { it.isNotBlank() }?.let { enumValueOf<T>(it) }

/** Blank/unparsable input means "no value" -- mirrors [parseOptionalDateTime] (`AuditLogScreen.kt`),
 * but for a plain `LocalDate` (AVV `signedDate`/`reviewDueDate`). */
fun parseOptionalDate(raw: String?): LocalDate? =
    raw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** A required `LocalDateTime` field (Breach `discoveredAt`) -- blank or unparsable both resolve to
 * `null`, which the caller then treats as a validation failure rather than silently defaulting to
 * "now" or any other guessed value. */
fun parseRequiredDateTime(raw: String?): LocalDateTime? =
    raw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

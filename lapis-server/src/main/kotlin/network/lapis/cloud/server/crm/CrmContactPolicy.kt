package network.lapis.cloud.server.crm

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.rpc.BadRequestException

/**
 * Pure fachlogik for `crm_contact` -- no DB access, no transaction, so every function here is
 * unit-testable in isolation (`CrmContactPolicyTest`).
 */
object CrmContactPolicy {
    /** Wiedervorlage-Intervall -- see `crm_contact.retention_review_due_at` KDoc (38-crm.kuml.kts). */
    const val RETENTION_REVIEW_INTERVAL_MONTHS = 24

    // Mirror the VARCHAR widths declared in `V17__crm_contacts.sql` -- these are the friendlier
    // first gate with a real German message, the CHECK-less VARCHAR truncation-turned-error
    // (`ExposedSQLException` 22001 "value too long") is the backstop, same posture `validate`'s own
    // KDoc documents for the blank/consent checks already here.
    const val MAX_DISPLAY_NAME_LENGTH = 300
    const val MAX_EMAIL_LENGTH = 320
    const val MAX_PHONE_LENGTH = 50
    const val MAX_STREET_LENGTH = 200
    const val MAX_POSTAL_CODE_LENGTH = 20
    const val MAX_CITY_LENGTH = 200
    const val MAX_COUNTRY_LENGTH = 100
    const val MAX_CONSENT_SOURCE_LENGTH = 200

    /** Mirrors `crm_interaction.summary VARCHAR(4000)` (`V17__crm_contacts.sql`). */
    const val MAX_INTERACTION_SUMMARY_LENGTH = 4000

    /**
     * `coalesce(last_interaction_at, created_at) + 24 months`, exact calendar-month arithmetic
     * (`java.time.LocalDateTime.plusMonths`, same java-interop idiom `DbClock`/`AuctionService`
     * already establish for `LocalDateTime` math this codebase's own kotlinx-datetime surface has
     * no direct equivalent for) -- this is a review REMINDER, not a to-the-day legal deadline
     * (contrast the §25 PartG calendar-year cutoffs elsewhere in this codebase, which ARE exact),
     * but "24 months" should still mean 24 months, not a ~2-day-off approximation.
     */
    fun retentionReviewDueAt(
        lastInteractionAt: LocalDateTime?,
        createdAt: LocalDateTime,
    ): LocalDateTime {
        val base = lastInteractionAt ?: createdAt
        return base
            .toJavaLocalDateTime()
            .plusMonths(RETENTION_REVIEW_INTERVAL_MONTHS.toLong())
            .toKotlinLocalDateTime()
    }

    /** Trim + lowercase; blank becomes `null`. Server-side normalization, applied before every write and every uniqueness check. */
    fun normalizeEmail(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * Art. 6(1)(a) DSGVO + §7 UWG: email outreach requires a DOCUMENTED, non-withdrawn consent --
     * never merely "an email address happens to be on file". [CrmLawfulBasis.LEGITIMATE_INTEREST]/
     * [CrmLawfulBasis.CONTRACT] alone never suffice for marketing-style outreach.
     *
     * **No CRM mailing path exists in this wave.** This function exists as the ONE gate a future
     * mailing/newsletter feature MUST call -- never `email != null` directly. See
     * `CrmContactDto.mayReceiveEmail` KDoc.
     */
    fun mayReceiveEmail(
        lawfulBasis: CrmLawfulBasis,
        consentGivenAt: LocalDateTime?,
        consentWithdrawnAt: LocalDateTime?,
        email: String?,
    ): Boolean {
        if (email == null) return false
        if (lawfulBasis != CrmLawfulBasis.CONSENT) return false
        if (consentGivenAt == null) return false
        if (consentWithdrawnAt != null) return false
        return true
    }

    /**
     * Server-side validation -- the authority, independent of any client-side pre-check (house
     * rule, see `WebhookService.setWebhookUrl` KDoc "D6" for the identical posture on a different
     * screen). Mirrors the CHECK constraints in `V17__crm_contacts.sql` (the DB is the backstop,
     * this is the friendlier first gate with a real message).
     *
     * [now] is the caller's [network.lapis.cloud.server.db.DbClock] snapshot, passed in rather
     * than read here -- same posture [validateInteraction] already established, kept pure/unit-
     * testable. Guards a future [CrmContactInput.consentGivenAt]: without this, an operator typo
     * (wrong year, e.g. "2036" instead of "2026") is accepted verbatim, immediately flips
     * [mayReceiveEmail] to `true`, and -- because a plain lawful-basis edit can never remove
     * consent evidence again, only overwrite it (see [CrmContactStore.update]'s "Consent evidence
     * is preserved" KDoc) -- is then very hard to correct.
     */
    fun validate(
        input: CrmContactInput,
        now: LocalDateTime,
    ) {
        if (input.displayName.isBlank()) {
            throw BadRequestException("Name darf nicht leer sein.")
        }
        requireMaxLength(value = input.displayName, maxLength = MAX_DISPLAY_NAME_LENGTH, fieldLabel = "Name")
        requireMaxLength(value = normalizeEmail(input.email), maxLength = MAX_EMAIL_LENGTH, fieldLabel = "E-Mail-Adresse")
        requireMaxLength(value = input.phone, maxLength = MAX_PHONE_LENGTH, fieldLabel = "Telefonnummer")
        requireMaxLength(value = input.street, maxLength = MAX_STREET_LENGTH, fieldLabel = "Straße")
        requireMaxLength(value = input.postalCode, maxLength = MAX_POSTAL_CODE_LENGTH, fieldLabel = "PLZ")
        requireMaxLength(value = input.city, maxLength = MAX_CITY_LENGTH, fieldLabel = "Ort")
        requireMaxLength(value = input.country, maxLength = MAX_COUNTRY_LENGTH, fieldLabel = "Land")
        requireMaxLength(value = input.consentSource, maxLength = MAX_CONSENT_SOURCE_LENGTH, fieldLabel = "Herkunft der Einwilligung")
        if (input.clearConsentEvidence && input.lawfulBasis == CrmLawfulBasis.CONSENT) {
            // See CrmContactInput.clearConsentEvidence KDoc -- an active CONSENT basis requires
            // exactly the evidence this flag would erase, so the two are mutually exclusive.
            throw BadRequestException(
                "Der Einwilligungsnachweis kann nicht entfernt werden, solange die Rechtsgrundlage 'Einwilligung' aktiv ist.",
            )
        }
        if (input.lawfulBasis == CrmLawfulBasis.CONSENT) {
            if (input.consentSource.isNullOrBlank()) {
                throw BadRequestException("Bei Rechtsgrundlage 'Einwilligung' ist die Herkunft der Einwilligung Pflicht.")
            }
            if (input.consentGivenAt == null) {
                throw BadRequestException("Bei Rechtsgrundlage 'Einwilligung' ist der Zeitpunkt der Einwilligung Pflicht.")
            }
        }
        // Local `val` -- `input.consentGivenAt` is a nullable property declared in a DIFFERENT
        // module (`lapis-shared`), so Kotlin cannot smart-cast the property access itself across
        // the module boundary even after the null check above.
        val consentGivenAt = input.consentGivenAt
        if (consentGivenAt != null && consentGivenAt > now) {
            throw BadRequestException("Zeitpunkt der Einwilligung darf nicht in der Zukunft liegen.")
        }
        if (input.externalDonorId != null && input.memberId != null) {
            throw BadRequestException("Ein Kontakt kann nicht gleichzeitig mit einem Spender UND einem Mitglied verknüpft sein.")
        }
    }

    /**
     * Server-side validation for [CrmInteractionInput] -- mirrors [validate]'s posture: the
     * authority, independent of the client-side pre-check `CrmContactsScreen.kt`'s capture form
     * already does. [now] is the caller's [network.lapis.cloud.server.db.DbClock] snapshot, passed
     * in rather than read here, so this function stays pure/unit-testable like every other one in
     * this object.
     *
     * Rejects a future [CrmInteractionInput.occurredAt]: without this, a caller could push
     * `retention_review_due_at` arbitrarily far into the future AND make
     * `crmLastInteractionRelativeText` (`CrmContactsScreen.kt`) misreport a stale contact as
     * contacted "heute" (`days <= 0`).
     */
    fun validateInteraction(
        input: CrmInteractionInput,
        now: LocalDateTime,
    ) {
        val trimmedSummary = input.summary.trim()
        if (trimmedSummary.isBlank()) {
            throw BadRequestException("Notiz darf nicht leer sein.")
        }
        requireMaxLength(value = trimmedSummary, maxLength = MAX_INTERACTION_SUMMARY_LENGTH, fieldLabel = "Notiz")
        val occurredAt = input.occurredAt
        if (occurredAt != null && occurredAt > now) {
            throw BadRequestException("Zeitpunkt darf nicht in der Zukunft liegen.")
        }
    }

    private fun requireMaxLength(
        value: String?,
        maxLength: Int,
        fieldLabel: String,
    ) {
        if (value != null && value.length > maxLength) {
            throw BadRequestException("$fieldLabel darf höchstens $maxLength Zeichen lang sein.")
        }
    }
}

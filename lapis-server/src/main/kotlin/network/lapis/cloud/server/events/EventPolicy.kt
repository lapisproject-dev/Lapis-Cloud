package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import java.math.BigDecimal
import java.security.SecureRandom
import java.text.Normalizer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Pure fachlogik for `event`/`event_registration` -- no DB access, no transaction, so every
 * function here is unit-testable in isolation (`EventPolicyTest`), same posture
 * `network.lapis.cloud.server.crm.CrmContactPolicy` already establishes.
 */
object EventPolicy {
    /** Mirrors `event.slug VARCHAR(120)` (`V18__events.sql`). */
    const val SLUG_MAX_LENGTH = 120

    /** Mirrors `event.title VARCHAR(200)`. */
    const val MAX_TITLE_LENGTH = 200

    /** Mirrors `event.description VARCHAR(8000)`. */
    const val MAX_DESCRIPTION_LENGTH = 8000

    /** Mirrors `event.location_text VARCHAR(500)`. */
    const val MAX_LOCATION_TEXT_LENGTH = 500

    /** Mirrors `event.online_url VARCHAR(2048)`. */
    const val MAX_ONLINE_URL_LENGTH = 2048

    /** Mirrors `event_registration.guest_name VARCHAR(300)`. */
    const val MAX_GUEST_NAME_LENGTH = 300

    /** Mirrors `event_registration.guest_email VARCHAR(320)`. */
    const val MAX_GUEST_EMAIL_LENGTH = 320

    /**
     * How long a `PENDING_PAYMENT` registration holds its seat before it is lazily treated as
     * expired (see `EventCapacityGuard`/`EventWaitlist` KDoc -- this codebase has no scheduler).
     * Same 30-minute value Stripe's own checkout expiry effectively floors at.
     */
    val STANDARD_HOLD: Duration = 30.minutes

    /** How long a waitlist-promoted registrant has to pay before the seat is offered onward. */
    val WAITLIST_OFFER_WINDOW: Duration = 48.hours

    /**
     * Caps how long this codebase ever treats a `payment_checkout_session` as still reusable --
     * see `EventRegistrationSubmission.startStripeCheckout` KDoc (review finding "Session-Dedup
     * greift nur 30 Minuten"). Stripe's own Checkout Sessions default to a fixed ~24h validity
     * window (`StripeCheckoutClient` sends no `expires_at`, so this is Stripe's own default, not
     * a value we control) -- binding the LOCAL session's `expires_at` to [WAITLIST_OFFER_WINDOW]
     * (48h) without this cap would let the reuse check consider a session "still good" long after
     * Stripe itself would refuse to complete it.
     */
    val STRIPE_SESSION_LIFETIME_CAP: Duration = 24.hours

    /** DoS deckel -- a waitlist is otherwise an unbounded insert surface for disposable addresses. */
    const val MAX_WAITLIST = 500

    /** Slug segment used only when the title normalizes to nothing usable (e.g. all-emoji/all-punctuation titles). */
    private const val SLUG_FALLBACK_PREFIX = "veranstaltung-"

    private val UMLAUT_TRANSLITERATION =
        mapOf(
            'ä' to "ae",
            'ö' to "oe",
            'ü' to "ue",
            'ß' to "ss",
            'Ä' to "ae",
            'Ö' to "oe",
            'Ü' to "ue",
        )

    /**
     * Throws [BadRequestException] on the first violation found -- server-side authority regardless
     * of any client-side pre-check. [existingStartsAt] is `null` for a brand-new event
     * (`EventService.createEvent`); for an edit (`EventService.updateEvent`) the caller passes the
     * CURRENTLY STORED `startsAt` (Review MAJOR fix): the past-date check below is skipped when
     * [input]'s `startsAt` is unchanged from that value, so editing an event's title/description/
     * capacity/etc. after it has already started stays possible (there is no other change path --
     * `publishEvent`/`cancelEvent` only ever touch `status`) -- a GENUINE move of `startsAt` into the
     * past, whether on create or update, is still rejected either way.
     */
    fun validate(
        input: EventInput,
        now: LocalDateTime,
        existingStartsAt: LocalDateTime? = null,
    ) {
        if (input.title.isBlank()) throw BadRequestException("Titel darf nicht leer sein.")
        if (input.title.length > MAX_TITLE_LENGTH) throw BadRequestException("Titel ist zu lang (maximal $MAX_TITLE_LENGTH Zeichen).")
        if (input.description.isBlank()) throw BadRequestException("Beschreibung darf nicht leer sein.")
        if (input.description.length > MAX_DESCRIPTION_LENGTH) {
            throw BadRequestException("Beschreibung ist zu lang (maximal $MAX_DESCRIPTION_LENGTH Zeichen).")
        }
        val locationText = input.locationText?.trim()?.takeIf { it.isNotBlank() }
        val onlineUrl = input.onlineUrl?.trim()?.takeIf { it.isNotBlank() }
        if (locationText == null && onlineUrl == null) {
            throw BadRequestException("Mindestens ein Veranstaltungsort (Adresse oder Online-Link) ist erforderlich.")
        }
        if (locationText != null && locationText.length > MAX_LOCATION_TEXT_LENGTH) {
            throw BadRequestException("Ortsangabe ist zu lang (maximal $MAX_LOCATION_TEXT_LENGTH Zeichen).")
        }
        if (onlineUrl != null && onlineUrl.length > MAX_ONLINE_URL_LENGTH) {
            throw BadRequestException("Online-Link ist zu lang (maximal $MAX_ONLINE_URL_LENGTH Zeichen).")
        }
        if (input.endsAt < input.startsAt) throw BadRequestException("Ende darf nicht vor dem Beginn liegen.")
        // Review MINOR fix: `now` used to be an entirely unused parameter -- a BOARD/ADMIN could
        // create (or edit into) an event whose `startsAt` is years in the past, which `publishEvent`
        // happily accepted, `listEvents(includePast = true)`/the public detail page then displayed,
        // and which `isRegistrationOpen` silently made permanently un-registrable (no error anywhere
        // in that path) -- a silent data-garbage state, not a caught validation error.
        //
        // Review MAJOR follow-up fix: that check alone made `updateEvent` reject EVERY edit of an
        // event whose `startsAt` had already passed -- even one that left `startsAt` completely
        // untouched (fixing a typo in the description, say) -- because `updateEvent` used to call
        // this same `validate` with no way to distinguish "genuinely moving startsAt into the past"
        // from "startsAt already was, and still is, in the past". `existingStartsAt` (set only by
        // `updateEvent`) makes that distinction possible.
        if (input.startsAt != existingStartsAt && input.startsAt < now) {
            throw BadRequestException("Beginn darf nicht in der Vergangenheit liegen.")
        }
        val capacity = input.capacity
        if (capacity != null && capacity <= 0) throw BadRequestException("Kapazität muss positiv sein, wenn angegeben.")
        if (input.feeAmount.compareTo(BigDecimal.ZERO) < 0) throw BadRequestException("Teilnahmegebühr darf nicht negativ sein.")
        if (input.feeAmount.scale() > 2) throw BadRequestException("Teilnahmegebühr darf höchstens zwei Nachkommastellen haben.")
        if (input.feeCurrency != "EUR") throw BadRequestException("Nur EUR wird als Währung unterstützt.")
        val registrationClosesAt = input.registrationClosesAt
        if (registrationClosesAt != null && registrationClosesAt > input.startsAt) {
            throw BadRequestException("Anmeldeschluss darf nicht nach dem Veranstaltungsbeginn liegen.")
        }
    }

    /**
     * Derives a URL-safe slug from [title]: NFD-normalize, transliterate umlauts/ß, drop every
     * remaining non-`[a-z0-9-]` character, collapse repeated `-`, trim, cap at 100 characters (20
     * below [SLUG_MAX_LENGTH] to leave room for a numeric collision suffix). An empty result (an
     * all-emoji/all-punctuation title) falls back to `"veranstaltung-<8 hex chars>"`. On collision,
     * appends `-2`, `-3`, ... until [isTaken] returns `false`.
     */
    fun slugFor(
        title: String,
        isTaken: (String) -> Boolean,
    ): String {
        val transliterated = buildString { for (c in title) append(UMLAUT_TRANSLITERATION[c] ?: c) }
        val normalized = Normalizer.normalize(transliterated, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val slugified =
            normalized
                .lowercase()
                .replace(Regex("[^a-z0-9-]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .take(100)
        val base = slugified.ifBlank { SLUG_FALLBACK_PREFIX + randomHex(4) }
        if (!isTaken(base)) return base
        var suffix = 2
        while (true) {
            val candidate = "$base-$suffix"
            if (!isTaken(candidate)) return candidate
            suffix++
        }
    }

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return buffer.joinToString(separator = "") { "%02x".format(it) }
    }

    private val TOKEN_RANDOM = SecureRandom()

    /**
     * Cryptographically random 64-hex-char token -- used by [EventWaitlist.promoteWhileCapacityFree]
     * to rotate a registration's `event_registration.cancel_token_sha256` at the moment it is
     * promoted to `PENDING_PAYMENT` on a paid event (Review MAJOR fix: that promotion previously
     * mailed a "please pay" message with no way to ever actually pay -- see
     * `EventStore.findByCancelTokenHash` KDoc, which this same column now also backs). A cached
     * [SecureRandom] instance (unlike [randomHex]'s own per-call `SecureRandom()`) -- this is called
     * far more often, once per waitlist promotion rather than once per slug collision.
     */
    fun randomToken(): String {
        val buffer = ByteArray(32)
        TOKEN_RANDOM.nextBytes(buffer)
        return buffer.joinToString(separator = "") { "%02x".format(it) }
    }

    /** Trim + lowercase; blank becomes `null` -- same normalization `CrmContactPolicy.normalizeEmail` already establishes. */
    fun normalizeGuestEmail(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * The `event_registration.active_participant_key` value -- see `39-events.kuml.kts` file header
     * "active_participant_key" for why this shadow column exists at all. Exactly one of [memberId]/
     * [normalizedGuestEmail] must be non-null (mirrors `chk_event_registration_identity`).
     * [normalizedGuestEmail] must already be normalized (via [normalizeGuestEmail]) -- this function
     * does not normalize it again.
     */
    fun activeParticipantKey(
        memberId: Uuid?,
        normalizedGuestEmail: String?,
    ): String {
        require((memberId == null) != (normalizedGuestEmail == null)) {
            "exactly one of memberId/normalizedGuestEmail must be set"
        }
        return if (memberId != null) "m:$memberId" else "g:$normalizedGuestEmail"
    }

    /**
     * `true` iff a registration may currently be attempted: the event is `PUBLISHED`, the
     * registration window (`registrationClosesAt`, if set) has not closed, and the event has not
     * already started. Does NOT check capacity -- that is `EventCapacityGuard`'s job, under the row
     * lock.
     */
    fun isRegistrationOpen(
        status: EventStatus,
        registrationClosesAt: LocalDateTime?,
        startsAt: LocalDateTime,
        now: LocalDateTime,
    ): Boolean {
        if (status != EventStatus.PUBLISHED) return false
        if (registrationClosesAt != null && now > registrationClosesAt) return false
        if (now >= startsAt) return false
        return true
    }
}

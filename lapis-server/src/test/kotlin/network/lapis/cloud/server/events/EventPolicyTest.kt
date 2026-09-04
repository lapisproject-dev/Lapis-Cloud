package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.BadRequestException
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- [EventPolicy] is pure fachlogik (no DB, no transaction), so
 * every branch is unit-testable in isolation, same posture `CrmContactPolicyTest` already
 * establishes for its own policy object.
 */
class EventPolicyTest :
    FunSpec({
        val now = LocalDateTime(2026, 9, 1, 10, 0)

        fun baseInput(
            title: String = "Sommerfest",
            startsAt: LocalDateTime = LocalDateTime(2026, 9, 10, 18, 0),
            endsAt: LocalDateTime = LocalDateTime(2026, 9, 10, 22, 0),
        ) = EventInput(
            title = title,
            description = "Ein schönes Fest.",
            locationText = "Vereinsheim",
            onlineUrl = null,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = 50,
            feeAmount = BigDecimal("10.00"),
            feeCurrency = "EUR",
            visibility = EventVisibility.PUBLIC,
            registrationClosesAt = null,
        )

        // ── validate ──────────────────────────────────────────────────────────────────

        test("validate accepts a well-formed input") {
            EventPolicy.validate(input = baseInput(), now = now)
        }

        test("validate rejects a blank title") {
            shouldThrow<BadRequestException> { EventPolicy.validate(input = baseInput(title = " "), now = now) }
        }

        test("validate rejects neither locationText nor onlineUrl set") {
            val input = baseInput().copy(locationText = null, onlineUrl = null)
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, now = now) }
        }

        test("validate accepts onlineUrl alone, without locationText") {
            val input = baseInput().copy(locationText = null, onlineUrl = "https://example.org/stream")
            EventPolicy.validate(input = input, now = now)
        }

        test("validate rejects endsAt before startsAt") {
            val input = baseInput(startsAt = LocalDateTime(2026, 9, 10, 20, 0), endsAt = LocalDateTime(2026, 9, 10, 19, 0))
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, now = now) }
        }

        // Review MINOR fix regression coverage: `now` used to be an entirely unused parameter, so
        // `validate` silently accepted an event whose `startsAt` was years in the past.
        test("validate rejects a startsAt in the past") {
            val input = baseInput(startsAt = LocalDateTime(2020, 1, 1, 18, 0), endsAt = LocalDateTime(2020, 1, 1, 22, 0))
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, now = now) }
        }

        test("validate accepts a startsAt exactly at now") {
            EventPolicy.validate(input = baseInput(startsAt = now, endsAt = now), now = now)
        }

        // Review MAJOR fix regression coverage: `updateEvent` used to call `validate` the same way
        // `createEvent` does, with no way to edit ANY field of an event whose `startsAt` had already
        // passed -- even one that left `startsAt` completely untouched. `existingStartsAt` (only ever
        // passed by `updateEvent`) fixes that.
        test(
            "validate accepts an unchanged startsAt in the past when existingStartsAt matches it (updateEvent editing an already-started event)",
        ) {
            val pastStartsAt = LocalDateTime(2020, 1, 1, 18, 0)
            val input = baseInput(startsAt = pastStartsAt, endsAt = LocalDateTime(2020, 1, 1, 22, 0))
            EventPolicy.validate(input = input, now = now, existingStartsAt = pastStartsAt)
        }

        test("validate still rejects a startsAt moved to a DIFFERENT point in the past, even with existingStartsAt set (updateEvent)") {
            val originalStartsAt = LocalDateTime(2020, 1, 1, 18, 0)
            val newPastStartsAt = LocalDateTime(2019, 6, 1, 18, 0)
            val input = baseInput(startsAt = newPastStartsAt, endsAt = LocalDateTime(2019, 6, 1, 22, 0))
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, now = now, existingStartsAt = originalStartsAt) }
        }

        test("validate still rejects a brand-new event (existingStartsAt == null) with a past startsAt") {
            val input = baseInput(startsAt = LocalDateTime(2020, 1, 1, 18, 0), endsAt = LocalDateTime(2020, 1, 1, 22, 0))
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, now = now, existingStartsAt = null) }
        }

        test("validate rejects a zero or negative capacity") {
            shouldThrow<BadRequestException> { EventPolicy.validate(input = baseInput().copy(capacity = 0), now = now) }
        }

        test("validate accepts a null capacity (unbounded)") {
            EventPolicy.validate(input = baseInput().copy(capacity = null), now = now)
        }

        test("validate rejects a negative fee amount") {
            val input = baseInput().copy(feeAmount = BigDecimal("-1.00"))
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, now = now) }
        }

        test("validate accepts a zero fee amount (free event)") {
            EventPolicy.validate(input = baseInput().copy(feeAmount = BigDecimal.ZERO), now = now)
        }

        test("validate rejects a non-EUR currency") {
            shouldThrow<BadRequestException> { EventPolicy.validate(input = baseInput().copy(feeCurrency = "USD"), now = now) }
        }

        test("validate rejects registrationClosesAt after startsAt") {
            val input = baseInput().copy(registrationClosesAt = LocalDateTime(2026, 9, 10, 19, 0))
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, now = now) }
        }

        test("validate accepts registrationClosesAt before startsAt") {
            val input = baseInput().copy(registrationClosesAt = LocalDateTime(2026, 9, 9, 23, 59))
            EventPolicy.validate(input = input, now = now)
        }

        // ── slugFor ───────────────────────────────────────────────────────────────────

        test("slugFor transliterates umlauts and lowercases") {
            EventPolicy.slugFor(title = "Frühjahrsfest für Mitglieder") { false } shouldBe "fruehjahrsfest-fuer-mitglieder"
        }

        test("slugFor strips punctuation and collapses repeated separators") {
            EventPolicy.slugFor(title = "Sommerfest 2026 -- Save the Date!!!") { false } shouldBe "sommerfest-2026-save-the-date"
        }

        test("slugFor falls back to a random slug for an all-emoji title") {
            val slug = EventPolicy.slugFor(title = "🎉🎈") { false }
            slug.startsWith("veranstaltung-") shouldBe true
        }

        test("slugFor appends a numeric suffix on collision, incrementing until free") {
            val taken = setOf("sommerfest", "sommerfest-2")
            EventPolicy.slugFor(title = "Sommerfest") { candidate -> candidate in taken } shouldBe "sommerfest-3"
        }

        // ── randomToken (Review MAJOR fix -- EventWaitlist's payment-resume token) ──────

        test("randomToken produces a 64-hex-char string") {
            val token = EventPolicy.randomToken()
            token.length shouldBe 64
            token.all { it in "0123456789abcdef" } shouldBe true
        }

        test("randomToken never repeats across calls") {
            val tokens = List(50) { EventPolicy.randomToken() }
            tokens.toSet().size shouldBe tokens.size
        }

        // ── normalizeGuestEmail ───────────────────────────────────────────────────────

        test("normalizeGuestEmail trims and lowercases") {
            EventPolicy.normalizeGuestEmail("  Alice@Example.ORG ") shouldBe "alice@example.org"
        }

        test("normalizeGuestEmail returns null for a blank/null input") {
            EventPolicy.normalizeGuestEmail(null) shouldBe null
            EventPolicy.normalizeGuestEmail("   ") shouldBe null
        }

        // ── activeParticipantKey ──────────────────────────────────────────────────────

        test("activeParticipantKey builds a member-prefixed key for a member") {
            val memberId = Uuid.parse("00000000-0000-0000-0000-000000000001")
            EventPolicy.activeParticipantKey(memberId = memberId, normalizedGuestEmail = null) shouldBe "m:$memberId"
        }

        test("activeParticipantKey builds a guest-prefixed key for a guest") {
            EventPolicy.activeParticipantKey(memberId = null, normalizedGuestEmail = "bob@example.org") shouldBe "g:bob@example.org"
        }

        test("activeParticipantKey throws if both or neither identity is set") {
            val memberId = Uuid.random()
            shouldThrow<IllegalArgumentException> {
                EventPolicy.activeParticipantKey(
                    memberId = memberId,
                    normalizedGuestEmail = "x@example.org",
                )
            }
            shouldThrow<IllegalArgumentException> { EventPolicy.activeParticipantKey(memberId = null, normalizedGuestEmail = null) }
        }

        // ── isRegistrationOpen ────────────────────────────────────────────────────────

        test("isRegistrationOpen is false for a DRAFT event") {
            EventPolicy.isRegistrationOpen(
                status = EventStatus.DRAFT,
                registrationClosesAt = null,
                startsAt = LocalDateTime(2026, 9, 10, 18, 0),
                now = now,
            ) shouldBe false
        }

        test("isRegistrationOpen is false once the registration deadline has passed") {
            EventPolicy.isRegistrationOpen(
                status = EventStatus.PUBLISHED,
                registrationClosesAt = LocalDateTime(2026, 8, 31, 0, 0),
                startsAt = LocalDateTime(2026, 9, 10, 18, 0),
                now = now,
            ) shouldBe false
        }

        test("isRegistrationOpen is false once the event has already started") {
            EventPolicy.isRegistrationOpen(
                status = EventStatus.PUBLISHED,
                registrationClosesAt = null,
                startsAt = LocalDateTime(2026, 8, 31, 0, 0),
                now = now,
            ) shouldBe false
        }

        test("isRegistrationOpen is true for a published, not-yet-started, not-yet-closed event") {
            EventPolicy.isRegistrationOpen(
                status = EventStatus.PUBLISHED,
                registrationClosesAt = LocalDateTime(2026, 9, 9, 0, 0),
                startsAt = LocalDateTime(2026, 9, 10, 18, 0),
                now = now,
            ) shouldBe true
        }
    })

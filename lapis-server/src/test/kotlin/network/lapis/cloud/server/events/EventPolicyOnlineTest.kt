package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.BadRequestException
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- the online fields of [EventPolicy]: the kind and the link must not contradict each other, an encounter entry needs a
 * valid space and cannot be tied to a registration deadline, a Lapis conference is capped at 12 hours, a Lapis kind is a venue of its own,
 * and the pre-V1.9.113 shape (only a link, no kind) keeps its meaning.
 */
class EventPolicyOnlineTest :
    FunSpec({
        val now = LocalDateTime(2026, 9, 1, 10, 0)
        val starts = LocalDateTime(2026, 9, 10, 18, 0)

        fun input(
            kind: EventOnlineKind = EventOnlineKind.NONE,
            location: String? = null,
            url: String? = null,
            space: String? = null,
            urlPublic: Boolean = false,
            deadline: LocalDateTime? = null,
            ends: LocalDateTime = LocalDateTime(2026, 9, 10, 20, 0),
        ) = EventInput(
            title = "Online",
            description = "d",
            locationText = location,
            onlineUrl = url,
            startsAt = starts,
            endsAt = ends,
            capacity = null,
            feeAmount = BigDecimal.ZERO,
            feeCurrency = "EUR",
            visibility = EventVisibility.PUBLIC,
            registrationClosesAt = deadline,
            onlineUrlPublic = urlPublic,
            onlineKind = kind,
            encounterSpaceId = space,
        )

        fun rejects(input: EventInput) {
            shouldThrow<BadRequestException> { EventPolicy.validate(input = input, wallNow = now) }
        }

        test("a Lapis conference and an encounter entry are venues of their own; an event with no place, no link and no such kind is not") {
            EventPolicy.validate(input = input(kind = EventOnlineKind.LAPIS_CONFERENCE), wallNow = now)
            EventPolicy.validate(input = input(kind = EventOnlineKind.LAPIS_ENCOUNTER, space = Uuid.random().toString()), wallNow = now)
            rejects(input(kind = EventOnlineKind.NONE))
            rejects(input(kind = EventOnlineKind.EXTERNAL_LINK)) // an external kind without a link degrades to NONE: no venue
        }

        test("a Lapis kind never carries an external link or a public link flag") {
            rejects(input(kind = EventOnlineKind.LAPIS_CONFERENCE, url = "https://meet.example/x"))
            rejects(input(kind = EventOnlineKind.LAPIS_CONFERENCE, urlPublic = true))
            rejects(input(kind = EventOnlineKind.LAPIS_ENCOUNTER, space = Uuid.random().toString(), url = "https://meet.example/x"))
        }

        test("an encounter entry needs a valid space id; any other kind must not name one") {
            rejects(input(kind = EventOnlineKind.LAPIS_ENCOUNTER))
            rejects(input(kind = EventOnlineKind.LAPIS_ENCOUNTER, space = "not-a-uuid"))
            rejects(input(kind = EventOnlineKind.LAPIS_CONFERENCE, space = Uuid.random().toString()))
            rejects(input(kind = EventOnlineKind.NONE, location = "Saal", space = Uuid.random().toString()))
        }

        test("an encounter space is permanent: it cannot be limited to registered people") {
            rejects(
                input(
                    kind = EventOnlineKind.LAPIS_ENCOUNTER,
                    space = Uuid.random().toString(),
                    deadline = LocalDateTime(2026, 9, 9, 12, 0),
                ),
            )
            // a conference may have a deadline: only the registered come in
            EventPolicy.validate(
                input = input(kind = EventOnlineKind.LAPIS_CONFERENCE, deadline = LocalDateTime(2026, 9, 9, 12, 0)),
                wallNow = now,
            )
        }

        test("a Lapis conference lasts at most 12 hours (exactly 12 is fine)") {
            EventPolicy.validate(
                input =
                    input(
                        kind = EventOnlineKind.LAPIS_CONFERENCE,
                        ends =
                            starts.let {
                                LocalDateTime(2026, 9, 11, 6, 0)
                            },
                    ),
                wallNow = now,
            )
            rejects(input(kind = EventOnlineKind.LAPIS_CONFERENCE, ends = LocalDateTime(2026, 9, 11, 6, 1)))
        }

        test("the pre-V1.9.113 shape keeps its meaning: a link without a kind is the external link, a blank link is none") {
            val legacy = EventPolicy.normalizedOnline(input(url = "  https://meet.example/x  "))
            legacy.onlineKind shouldBe EventOnlineKind.EXTERNAL_LINK
            legacy.onlineUrl shouldBe "https://meet.example/x"
            EventPolicy.normalizedOnline(input(kind = EventOnlineKind.EXTERNAL_LINK, url = "   ", location = "Saal")).onlineKind shouldBe
                EventOnlineKind.NONE
            EventPolicy.normalizedOnline(input(kind = EventOnlineKind.LAPIS_CONFERENCE)).onlineKind shouldBe
                EventOnlineKind.LAPIS_CONFERENCE
            // idempotent
            EventPolicy.normalizedOnline(legacy) shouldBe legacy
            EventPolicy.validate(input = input(url = "https://meet.example/x"), wallNow = now)
            // the public link flag still needs an https link
            rejects(input(kind = EventOnlineKind.EXTERNAL_LINK, url = "http://meet.example/x", urlPublic = true))
        }

        test("isLapisOnline names exactly the two hosted kinds") {
            EventOnlineKind.entries.filter { EventPolicy.isLapisOnline(it) }.toSet() shouldBe
                setOf(EventOnlineKind.LAPIS_CONFERENCE, EventOnlineKind.LAPIS_ENCOUNTER)
        }
    })

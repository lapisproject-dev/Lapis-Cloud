package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime

/**
 * V1.9.259 -- the signed-out STANDARD detail page, pinned byte for byte. `event-page-standard-pre-v1.9.259.html` was generated from the
 * code of `master` BEFORE the member-aware registration card existed; `event-page-standard.html` is the current page. The two differ by
 * exactly the member hint block (see the second test).
 */
class EventPublicGoldenStandardTest :
    FunSpec({
        fun resource(name: String): String =
            EventPublicGoldenStandardTest::class.java
                .getResourceAsStream("/golden/$name")!!
                .readBytes()
                .toString(Charsets.UTF_8)
                .trimEnd('\n')

        // The block V1.9.259 added for a signed-out STANDARD visitor -- the ONLY difference to the pre-change page.
        val hintBlock =
            "<div class=\"event-member-hint\"><p>${EventPublicHtml.MEMBER_HINT_TEXT} " +
                "<a href=\"https://example.org/app#/login\">Anmelden</a></p></div>"

        test("golden: the signed-out STANDARD detail page matches the checked-in fixture byte for byte") {
            EventPublicHtml.eventPage(ctx = eventTestContext(), view = standardGoldenView()) shouldBe resource("event-page-standard.html")
        }

        test("the signed-out STANDARD page equals the pre-V1.9.259 page plus exactly the member hint block") {
            val current = EventPublicHtml.eventPage(ctx = eventTestContext(), view = standardGoldenView())
            current shouldContain hintBlock
            current.replace(hintBlock, "") shouldBe resource("event-page-standard-pre-v1.9.259.html")
        }

        test("the EMBED page is untouched by V1.9.259 (no hint, no account block)") {
            val embed = EventPublicHtml.eventPage(ctx = eventTestContext(embed = true), view = standardGoldenView())
            embed shouldNotContain "event-member-hint"
            embed shouldNotContain "Angemeldet als"
        }
    })

internal fun standardGoldenView(): EventPublicHtml.View =
    EventPublicHtml.View(
        title = "Sommerfest",
        slug = "sommerfest",
        description = "Zeile eins\nZeile zwei\n\nAbsatz zwei",
        locationText = "Gemeindehaus",
        onlineUrl = null,
        startsAt = LocalDateTime(2026, 10, 20, 20, 15),
        endsAt = LocalDateTime(2026, 10, 20, 22, 15),
        feeLabel = "kostenlos",
        full = false,
        registrationOpen = true,
    )

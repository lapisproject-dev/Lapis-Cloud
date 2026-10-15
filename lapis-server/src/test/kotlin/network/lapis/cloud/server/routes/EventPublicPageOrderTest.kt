package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime

/** V1.9.140 -- order of the event detail page (facts, cover, description, callout, registration card) and the "Zur Anmeldung" jump link. */
class EventPublicPageOrderTest :
    FunSpec({
        fun view(
            description: String = "Zeile eins\n\nAbsatz zwei",
            full: Boolean = false,
            registrationOpen: Boolean = true,
            hasEnded: Boolean = false,
            coverImageUrl: String? = null,
        ) = EventPublicHtml.View(
            title = "Sommerfest",
            slug = "sommerfest",
            description = description,
            locationText = "Gemeindehaus",
            onlineUrl = null,
            startsAt = LocalDateTime(2030, 10, 20, 20, 15),
            endsAt = LocalDateTime(2030, 10, 20, 22, 15),
            feeLabel = "kostenlos",
            full = full,
            registrationOpen = registrationOpen,
            hasEnded = hasEnded,
            coverImageUrl = coverImageUrl,
        )

        fun anchors(html: String) = Regex("href=\"#anmeldung\"").findAll(html).count()

        test("order: facts, cover, description, callout, registration card") {
            val html =
                EventPublicHtml.eventPage(
                    ctx = eventTestContext(),
                    view = view(full = true, coverImageUrl = "https://example.org/bild.png"),
                )
            val facts = html.indexOf("event-facts")
            val cover = html.indexOf("event-cover")
            val description = html.indexOf("event-description")
            val callout = html.indexOf("event-callout")
            val card = html.indexOf("class=\"event-card\"")
            listOf(facts, cover, description, callout, card).forEach { it shouldBeGreaterThan -1 }
            facts shouldBeLessThan cover
            cover shouldBeLessThan description
            description shouldBeLessThan callout
            callout shouldBeLessThan card
        }

        test("open registration with a description: exactly one jump link and the target id") {
            for (ctx in listOf(eventTestContext(), eventTestContext(embed = true))) {
                val html = EventPublicHtml.eventPage(ctx = ctx, view = view())
                anchors(html) shouldBe 1
                html shouldContain "id=\"anmeldung\""
                html shouldContain "Zur Anmeldung"
            }
        }

        test("the jump link sits in the Plätze row and is a plain same-page link without target") {
            val html = EventPublicHtml.eventPage(ctx = eventTestContext(embed = true), view = view())
            html shouldContain "Plätze frei · <a href=\"#anmeldung\">Zur Anmeldung</a>"
            html shouldNotContain "#anmeldung\" target"
        }

        test("no jump link when the event has ended, registration is closed, or the description is blank") {
            val ended = EventPublicHtml.eventPage(ctx = eventTestContext(), view = view(hasEnded = true))
            anchors(ended) shouldBe 0
            ended shouldNotContain "id=\"anmeldung\""
            val closed = EventPublicHtml.eventPage(ctx = eventTestContext(), view = view(registrationOpen = false))
            anchors(closed) shouldBe 0
            closed shouldNotContain "id=\"anmeldung\""
            val blank = EventPublicHtml.eventPage(ctx = eventTestContext(), view = view(description = ""))
            withClue("blank description") {
                anchors(blank) shouldBe 0
                blank shouldContain "id=\"anmeldung\""
            }
        }

        test("a full event shows the waitlist wording next to the jump link, never a seat count") {
            val html = EventPublicHtml.eventPage(ctx = eventTestContext(), view = view(full = true))
            html shouldContain "Ausgebucht – Warteliste möglich · <a href=\"#anmeldung\">Zur Anmeldung</a>"
        }

        test("the detail page never carries a robots meta in the standard mode, the embed page exactly the plain noindex") {
            val standard = EventPublicHtml.eventPage(ctx = eventTestContext(), view = view())
            standard shouldNotContain "name=\"robots\""
            val embed = EventPublicHtml.eventPage(ctx = eventTestContext(embed = true), view = view())
            embed shouldContain "<meta name=\"robots\" content=\"noindex\">"
            Regex("name=\"robots\"").findAll(embed).count() shouldBe 1
        }
    })

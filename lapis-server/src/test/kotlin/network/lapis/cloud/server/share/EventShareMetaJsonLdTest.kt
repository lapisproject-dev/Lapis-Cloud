package network.lapis.cloud.server.share

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.routes.EventPublicHtml
import java.math.BigDecimal

private const val BASE = "https://example.org"
private const val SHARE_URL = "$BASE/veranstaltung/x"

private fun view(
    title: String = "Gottesdienst",
    fee: BigDecimal = BigDecimal.ZERO,
    currency: String = "EUR",
    full: Boolean = false,
    open: Boolean = true,
    ended: Boolean = false,
    closes: LocalDateTime? = null,
    cover: String? = null,
) = EventPublicHtml.View(
    title = title,
    slug = "x",
    description = "Text",
    locationText = "Ort",
    onlineUrl = null,
    startsAt = LocalDateTime(2030, 11, 12, 19, 0),
    endsAt = LocalDateTime(2030, 11, 12, 21, 0),
    feeLabel = "x",
    full = full,
    registrationOpen = open,
    coverImageUrl = cover,
    hasEnded = ended,
    feeAmount = fee,
    feeCurrency = currency,
    registrationClosesAt = closes,
)

private fun branding(
    title: String = "Testverein",
    logoPath: String? = null,
) = ResolvedBranding(title = title, logoAvailable = logoPath != null, logoPath = logoPath)

private fun ld(
    view: EventPublicHtml.View,
    brand: ResolvedBranding = branding(),
    baseUrl: String = BASE,
): String =
    EventShareMeta.jsonLd(
        view = view,
        shareUrl = SHARE_URL,
        image = EventShareMeta.jsonLdImage(view = view, baseUrl = baseUrl, branding = brand),
        branding = brand,
        baseUrl = baseUrl,
        orgZone = TimeZone.of("Europe/Berlin"),
    )

private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

class EventShareMetaJsonLdTest :
    FunSpec({
        test("free event: organizer, performer and InStock offer") {
            val j = parse(ld(view = view()))
            j["organizer"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "Testverein"
            j["organizer"]!!.jsonObject["url"]!!.jsonPrimitive.content shouldBe BASE
            j["performer"] shouldBe j["organizer"]
            val o = j["offers"]!!.jsonObject
            o["price"]!!.jsonPrimitive.content shouldBe "0"
            (o["price"] as JsonPrimitive).isString shouldBe true
            o["priceCurrency"]!!.jsonPrimitive.content shouldBe "EUR"
            o["availability"]!!.jsonPrimitive.content shouldBe "https://schema.org/InStock"
            o["url"]!!.jsonPrimitive.content shouldBe SHARE_URL
            o.containsKey("validFrom") shouldBe false
            o.containsKey("validThrough") shouldBe false
        }

        test("paid event keeps price and currency; zero with scale is free") {
            val o = parse(ld(view = view(fee = BigDecimal("12.50"), currency = "CHF")))["offers"]!!.jsonObject
            o["price"]!!.jsonPrimitive.content shouldBe "12.50"
            o["priceCurrency"]!!.jsonPrimitive.content shouldBe "CHF"
            parse(ld(view = view(fee = BigDecimal("0.00"))))["offers"]!!.jsonObject["price"]!!.jsonPrimitive.content shouldBe "0"
            parse(ld(view = view(fee = BigDecimal("1E+1"))))["offers"]!!.jsonObject["price"]!!.jsonPrimitive.content shouldBe "10"
        }

        test("availability: full is SoldOut, ended or closed has no offer, deadline becomes validThrough") {
            parse(ld(view = view(full = true)))["offers"]!!.jsonObject["availability"]!!.jsonPrimitive.content shouldBe
                "https://schema.org/SoldOut"
            parse(ld(view = view(ended = true))).containsKey("offers") shouldBe false
            parse(ld(view = view(open = false))).containsKey("offers") shouldBe false
            val vt =
                parse(
                    ld(view = view(closes = LocalDateTime(2030, 11, 10, 12, 0))),
                )["offers"]!!.jsonObject["validThrough"]!!.jsonPrimitive.content
            Regex("^2030-11-10T12:00(:00)?\\+01:00$").matches(vt) shouldBe true
        }

        test("image chain: cover, raster logo, then apple-touch-icon; always absolute") {
            parse(ld(view = view(cover = "$BASE/c.png")))["image"]!!.jsonPrimitive.content shouldBe "$BASE/c.png"
            parse(ld(view = view(), brand = branding(logoPath = "/l.png")))["image"]!!.jsonPrimitive.content shouldBe
                "$BASE/api/branding/logo"
            parse(ld(view = view(), brand = branding(logoPath = "/l.svg")))["image"]!!.jsonPrimitive.content shouldBe
                "$BASE/apple-touch-icon.png"
            val trailing = parse(ld(view = view(), baseUrl = "$BASE/"))["image"]!!.jsonPrimitive.content
            trailing shouldBe "$BASE/apple-touch-icon.png"
            trailing shouldStartWith BASE
        }

        test("hostile titles and currency cannot break out of the script block") {
            val evil = "</script><script>alert(1)</script> &"
            val out = ld(view = view(title = evil, currency = "<x>"), brand = branding(title = evil))
            out shouldNotContain "<"
            out shouldNotContain ">"
            out shouldNotContain "&"
            out shouldNotContain " "
            out shouldNotContain " "
            out shouldContain "\\u003c"
            parse(out)["offers"]!!.jsonObject["priceCurrency"]!!.jsonPrimitive.content shouldBe "EUR"
        }

        test("performer is only the organization, no person data, and existing fields stay") {
            val j = parse(ld(view = view()))
            j["performer"]!!.jsonObject["@type"]!!.jsonPrimitive.content shouldBe "Organization"
            j["performer"]!!.jsonObject.keys shouldBe setOf("@type", "name", "url")
            j["@type"]!!.jsonPrimitive.content shouldBe "Event"
            j.containsKey("startDate") shouldBe true
            j.containsKey("location") shouldBe true
            j.containsKey("description") shouldBe true
        }
    })

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
    starts: LocalDateTime = LocalDateTime(2030, 11, 12, 19, 0),
    opens: LocalDateTime? = null,
) = EventPublicHtml.View(
    title = title,
    slug = "x",
    description = "Text",
    locationText = "Ort",
    onlineUrl = null,
    startsAt = starts,
    endsAt = LocalDateTime(starts.year, starts.monthNumber, starts.dayOfMonth, 23, 0),
    feeLabel = "x",
    full = full,
    registrationOpen = open,
    coverImageUrl = cover,
    hasEnded = ended,
    feeAmount = fee,
    feeCurrency = currency,
    registrationClosesAt = closes,
    registrationOpensAt = opens,
)

private fun branding(
    title: String = "Testverein",
    logoPath: String? = null,
    variants: Map<network.lapis.cloud.server.branding.LogoVariant, network.lapis.cloud.server.branding.ResolvedLogoFile> = emptyMap(),
) = ResolvedBranding(title = title, logoAvailable = logoPath != null, logoPath = logoPath, variants = variants)

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
            o.containsKey("validFrom") shouldBe false // default view carries no timestamp
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

        test("image chain with logo variants: first raster variant wins, SVG-only variants fall to the apple-touch-icon") {
            fun v(
                variant: network.lapis.cloud.server.branding.LogoVariant,
                ext: String,
            ) = variant to
                network.lapis.cloud.server.branding
                    .ResolvedLogoFile(path = "/x/${variant.slug}.$ext", extension = ext)
            val wl = network.lapis.cloud.server.branding.LogoVariant.WORDMARK_LIGHT
            val sd = network.lapis.cloud.server.branding.LogoVariant.SIGNET_DARK
            parse(
                ld(view = view(), brand = branding(variants = mapOf(v(wl, "png"), v(sd, "png")))),
            )["image"]!!.jsonPrimitive.content shouldBe
                "$BASE/api/branding/logo/wordmark-light"
            parse(
                ld(view = view(), brand = branding(variants = mapOf(v(wl, "svg"), v(sd, "webp")))),
            )["image"]!!.jsonPrimitive.content shouldBe
                "$BASE/api/branding/logo/signet-dark"
            parse(
                ld(view = view(), brand = branding(variants = mapOf(v(wl, "svg"), v(sd, "svg")))),
            )["image"]!!.jsonPrimitive.content shouldBe
                "$BASE/apple-touch-icon.png"
            // The cover still wins over every logo.
            parse(
                ld(view = view(cover = "$BASE/c.png"), brand = branding(variants = mapOf(v(wl, "png")))),
            )["image"]!!.jsonPrimitive.content shouldBe
                "$BASE/c.png"
        }

        val offsetRegex = Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d(:\\d\\d)?[+-]\\d\\d:\\d\\d$")

        fun offers(v: EventPublicHtml.View): JsonObject = parse(ld(view = v))["offers"]!!.jsonObject

        fun validFrom(v: EventPublicHtml.View): String = offers(v)["validFrom"]!!.jsonPrimitive.content

        test("validFrom: summer is +02:00, winter +01:00, matches the format") {
            val summer = validFrom(view(starts = LocalDateTime(2030, 7, 20, 19, 0), opens = LocalDateTime(2030, 7, 1, 10, 0)))
            summer shouldStartWith "2030-07-01T10:00"
            summer shouldContain "+02:00"
            offsetRegex.matches(summer) shouldBe true
            val winter = validFrom(view(opens = LocalDateTime(2030, 11, 1, 0, 0)))
            winter shouldStartWith "2030-11-01T00:00"
            winter shouldContain "+01:00"
            offsetRegex.matches(winter) shouldBe true
        }

        test("validFrom: both sides of the daylight-saving boundaries") {
            val s = LocalDateTime(2030, 12, 1, 19, 0)
            validFrom(view(starts = s, opens = LocalDateTime(2030, 3, 30, 12, 0))) shouldContain "+01:00"
            validFrom(view(starts = s, opens = LocalDateTime(2030, 4, 1, 12, 0))) shouldContain "+02:00"
            validFrom(view(starts = s, opens = LocalDateTime(2030, 10, 26, 12, 0))) shouldContain "+02:00"
            validFrom(view(starts = s, opens = LocalDateTime(2030, 10, 28, 12, 0))) shouldContain "+01:00"
        }

        test("validFrom: absent without a timestamp, other offer fields unchanged") {
            offers(view(opens = null)).containsKey("validFrom") shouldBe false
            val without = offers(view(fee = BigDecimal("5"), closes = LocalDateTime(2030, 11, 10, 12, 0)))
            val withOpens =
                offers(
                    view(fee = BigDecimal("5"), closes = LocalDateTime(2030, 11, 10, 12, 0), opens = LocalDateTime(2030, 11, 1, 8, 0)),
                )
            withOpens.containsKey("validFrom") shouldBe true
            JsonObject(withOpens.filterKeys { it != "validFrom" }) shouldBe without
        }

        test("validFrom is clamped to validThrough and startDate") {
            val closes = LocalDateTime(2030, 11, 10, 12, 0)
            val a = offers(view(closes = closes, opens = LocalDateTime(2030, 11, 11, 9, 0)))
            a["validFrom"] shouldBe a["validThrough"]
            val b = parse(ld(view = view(opens = LocalDateTime(2030, 11, 13, 9, 0))))
            b["offers"]!!.jsonObject["validFrom"] shouldBe b["startDate"]
            val c = parse(ld(view = view(closes = closes, opens = LocalDateTime(2030, 11, 1, 9, 0))))
            val o = c["offers"]!!.jsonObject
            val from = java.time.OffsetDateTime.parse(o["validFrom"]!!.jsonPrimitive.content)
            (from <= java.time.OffsetDateTime.parse(o["validThrough"]!!.jsonPrimitive.content)) shouldBe true
            (from <= java.time.OffsetDateTime.parse(c["startDate"]!!.jsonPrimitive.content)) shouldBe true
        }

        test("validFrom: ended or closed events have no offer; paid and full events still carry it") {
            val opens = LocalDateTime(2030, 11, 1, 8, 0)
            parse(ld(view = view(ended = true, opens = opens))).containsKey("offers") shouldBe false
            parse(ld(view = view(open = false, opens = opens))).containsKey("offers") shouldBe false
            offers(view(fee = BigDecimal("12.50"), opens = opens)).containsKey("validFrom") shouldBe true
            offers(view(full = true, opens = opens)).containsKey("validFrom") shouldBe true
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

package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid

private val ON = RecursionSettings(enabled = true, depth = 2, lastRunAt = null, summaryJson = null)

private fun view(
    id: String,
    origin: String = "o.beispiel.de",
    start: LocalDateTime = LocalDateTime(2026, 11, 12, 19, 0, 0),
    hops: Int = 2,
) = PartnerEventView(
    remoteServerId = Uuid.random(),
    remoteEventId = id,
    title = "T $id",
    startsAtWall = start,
    startsAtUtc = start,
    endsAtUtc = start,
    online = false,
    place = null,
    summary = null,
    recurrenceSentence = null,
    url = "https://$origin/e/$id",
    hops = hops,
    originHost = origin,
    originName = null,
    rootName = "Root",
)

class PartnerEventDisplayTest :
    FunSpec({
        fun visible(
            hops: Int = 2,
            origin: String = "o.beispiel.de",
            via: List<String> = listOf("r.beispiel.de"),
            rootActive: Boolean = true,
            settings: RecursionSettings = ON,
            patterns: List<String> = emptyList(),
        ) = PartnerEventDisplay.isVisible(
            hops = hops,
            originHost = origin,
            via = via,
            rootActive = rootActive,
            settings = settings,
            patterns = patterns,
        )

        test("visibility matrix") {
            visible() shouldBe true
            visible(hops = 1, via = emptyList(), origin = "r.beispiel.de") shouldBe true
            visible(patterns = listOf("o.beispiel.de")) shouldBe false
            visible(patterns = listOf("*.beispiel.de")) shouldBe false
            visible(patterns = listOf("r.beispiel.de")) shouldBe false
            visible(rootActive = false) shouldBe false
            visible(settings = ON.copy(enabled = false)) shouldBe false
            visible(hops = 1, via = emptyList(), settings = ON.copy(enabled = false)) shouldBe true
            visible(hops = 3, settings = ON.copy(depth = 1)) shouldBe false
            visible(hops = 3, settings = ON.copy(depth = 2)) shouldBe true
            visible(hops = 4, settings = ON.copy(depth = 2)) shouldBe false
        }

        test("at most 10 per origin and 50 in total, sorted by start, origin and id") {
            val one = (1..15).map { view(id = "a$it", origin = "one.beispiel.de", start = LocalDateTime(2026, 11, 12, 10, it, 0)) }
            PartnerEventDisplay.capAndSort(one).size shouldBe 10
            val many =
                (1..8).flatMap { o ->
                    (1..9).map { e -> view(id = "e$e", origin = "o$o.beispiel.de", start = LocalDateTime(2026, 11, 12, 10, e, 0)) }
                }
            PartnerEventDisplay.capAndSort(many).size shouldBe 50
            val sorted =
                PartnerEventDisplay.capAndSort(
                    listOf(
                        view(id = "b", origin = "z.beispiel.de"),
                        view(id = "a", origin = "z.beispiel.de"),
                        view(id = "c", origin = "a.beispiel.de"),
                        view(id = "d", origin = "a.beispiel.de", start = LocalDateTime(2026, 11, 1, 10, 0, 0)),
                    ),
                )
            sorted.map { it.remoteEventId } shouldContainExactly listOf("d", "c", "a", "b")
        }

        test("revalidatedUrl accepts only https on the origin's own host") {
            val ok = "https://o.beispiel.de/veranstaltung/x"
            PartnerEventDisplay.revalidatedUrl(url = ok, originHost = "o.beispiel.de") shouldBe ok
            PartnerEventDisplay.revalidatedUrl(url = "https://o.beispiel.de:443/x", originHost = "o.beispiel.de") shouldBe
                "https://o.beispiel.de:443/x"
            listOf(
                "http://o.beispiel.de/x",
                "https://o.beispiel.de:8443/x",
                "https://user@o.beispiel.de/x",
                "https://fremd.beispiel.de/x",
                "javascript:alert(1)",
                "https://o.beispiel.de/a b",
                "https://o.beispiel.de/a\r\nSet-Cookie: x",
                "https://o.beispiel.de/" + "a".repeat(500),
                "//o.beispiel.de/x",
            ).forEach { PartnerEventDisplay.revalidatedUrl(url = it, originHost = "o.beispiel.de").shouldBeNull() }
        }
    })

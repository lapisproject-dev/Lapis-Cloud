package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import kotlin.uuid.Uuid

private val T0 = LocalDateTime(2026, 11, 5, 10, 0, 0)
private val ON = RecursionSettings(enabled = true, depth = 2, lastRunAt = null, summaryJson = null)

private fun partner(
    host: String,
    name: String? = null,
    remoteName: String? = null,
    active: Boolean = true,
    id: Uuid = Uuid.random(),
) = PartnerRow(
    id = id,
    host = host,
    displayName = name,
    remoteServerId = null,
    remoteName = remoteName,
    active = active,
    lastFetchAt = null,
    lastStatus = PartnerFetchStatus.OK,
    lastError = null,
    eventCount = 0,
    consecutiveFailures = 0,
    nextFetchAt = T0,
    etag = null,
)

private fun disc(
    host: String,
    root: PartnerRow,
    name: String? = null,
    hops: Int = 2,
    via: List<String> = listOf(root.host),
) = DiscoveredLoginRow(host = host, remoteName = name, rootPartnerId = root.id, hops = hops, via = via)

private fun build(
    direct: List<PartnerRow> = emptyList(),
    discovered: List<DiscoveredLoginRow> = emptyList(),
    settings: RecursionSettings = ON,
    patterns: List<String> = emptyList(),
    ownHost: String? = "own.example.com",
    listHidden: Boolean = false,
) = PartnerLoginChoices.build(
    direct = direct,
    discovered = discovered,
    settings = settings,
    patterns = patterns,
    ownHost = ownHost,
    listHidden = listHidden,
)

class PartnerLoginChoicesTest :
    FunSpec({
        test("direct partners and discovered servers are listed together") {
            val a = partner(host = "a.beispiel.de", name = "Alpha")
            build(
                direct = listOf(a),
                discovered = listOf(disc(host = "b.beispiel.de", root = a, name = "Beta")),
            ).map { it.host } shouldContainExactly
                listOf("a.beispiel.de", "b.beispiel.de")
        }

        test("the direct partner wins the dedupe, among discovered rows the one with fewer hops") {
            val a = partner(host = "a.beispiel.de", name = "Alpha")
            val result =
                build(
                    direct = listOf(a),
                    discovered =
                        listOf(
                            disc(host = "a.beispiel.de", root = a, name = "Fremdname"),
                            disc(host = "c.beispiel.de", root = a, name = "Drei", hops = 3, via = listOf("a.beispiel.de", "x.beispiel.de")),
                            disc(host = "c.beispiel.de", root = a, name = "Zwei", hops = 2),
                        ),
                )
            result.map { it.name } shouldContainExactly listOf("Alpha", "Zwei")
        }

        test("own host is excluded") {
            val a = partner(host = "a.beispiel.de")
            build(
                direct = listOf(a, partner(host = "own.example.com")),
                discovered = listOf(disc(host = "own.example.com", root = a)),
            ).map { it.host } shouldContainExactly
                listOf("a.beispiel.de")
        }

        test("an inactive direct partner is missing, and so are its discovered servers") {
            val off = partner(host = "off.beispiel.de", active = false)
            val on = partner(host = "on.beispiel.de")
            build(
                direct = listOf(off, on),
                discovered = listOf(disc(host = "x.beispiel.de", root = off), disc(host = "off.beispiel.de", root = on)),
            ).map { it.host } shouldContainExactly listOf("on.beispiel.de")
        }

        test("blocked: exact origin, wildcard pattern, a host of the chain and the root host") {
            val a = partner(host = "a.beispiel.de")
            val result =
                build(
                    direct = listOf(a),
                    discovered =
                        listOf(
                            disc(host = "exact.beispiel.de", root = a),
                            disc(host = "w.sub.beispiel.org", root = a),
                            disc(host = "viahost.beispiel.de", root = a, hops = 3, via = listOf("a.beispiel.de", "mid.beispiel.de")),
                            disc(host = "ok.beispiel.de", root = a),
                        ),
                    patterns = listOf("exact.beispiel.de", "*.sub.beispiel.org", "mid.beispiel.de"),
                )
            result.map { it.host } shouldContainExactly listOf("a.beispiel.de", "ok.beispiel.de")
            build(
                direct = listOf(a),
                discovered = listOf(disc(host = "ok.beispiel.de", root = a)),
                patterns = listOf("a.beispiel.de"),
            ).shouldBeEmpty()
        }

        test("a blocked direct partner stays out even if a discovered row shows the same host") {
            val a = partner(host = "a.beispiel.de")
            val b = partner(host = "b.beispiel.de")
            build(direct = listOf(a, b), discovered = listOf(disc(host = "b.beispiel.de", root = a)), patterns = listOf("b.beispiel.de"))
                .map { it.host } shouldContainExactly listOf("a.beispiel.de")
        }

        test("recursion off, or hops beyond the depth: discovered servers vanish") {
            val a = partner(host = "a.beispiel.de")
            val rows =
                listOf(
                    disc(host = "d2.beispiel.de", root = a),
                    disc(host = "d3.beispiel.de", root = a, hops = 3, via = listOf("a.beispiel.de", "d2.beispiel.de")),
                )
            build(direct = listOf(a), discovered = rows, settings = ON.copy(enabled = false)).map { it.host } shouldContainExactly
                listOf("a.beispiel.de")
            build(direct = listOf(a), discovered = rows, settings = ON.copy(depth = 1)).map { it.host } shouldContainExactly
                listOf("a.beispiel.de", "d2.beispiel.de")
        }

        test("a discovered row without its root is dropped") {
            val a = partner(host = "a.beispiel.de")
            val ghost = partner(host = "ghost.beispiel.de")
            build(direct = listOf(a), discovered = listOf(disc(host = "x.beispiel.de", root = ghost))).map { it.host } shouldContainExactly
                listOf("a.beispiel.de")
        }

        test("a stored host that fails today's host rules is dropped") {
            val a = partner(host = "a.beispiel.de")
            val rows = listOf("127.1", "foo.local", "Gross.Beispiel.de", "ok.beispiel.de:8443").map { disc(host = it, root = a) }
            build(direct = listOf(a), discovered = rows).map { it.host } shouldContainExactly listOf("a.beispiel.de")
        }

        test("listHidden empties the list") {
            build(direct = listOf(partner(host = "a.beispiel.de")), listHidden = true).shouldBeEmpty()
        }

        test("sort: German collation, case-insensitive, ties by host, host as fallback when there is no name") {
            val a = partner(host = "1.beispiel.de", name = "Äpfel")
            val list =
                build(
                    direct =
                        listOf(
                            a,
                            partner(host = "2.beispiel.de", name = "apfel"),
                            partner(host = "3.beispiel.de", name = "Zebra"),
                            partner(host = "4.beispiel.de", name = "Öko"),
                            partner(host = "5.beispiel.de", name = "ober"),
                            partner(host = "6.beispiel.de", name = "Ärzte"),
                            partner(host = "7.beispiel.de", name = "apfel"),
                            partner(host = "m.beispiel.de"),
                            partner(host = "9.beispiel.de", name = "‮\u0000"),
                        ),
                )
            list.map { it.host } shouldContainExactly
                listOf(
                    "9.beispiel.de",
                    "2.beispiel.de",
                    "7.beispiel.de",
                    "1.beispiel.de",
                    "6.beispiel.de",
                    "m.beispiel.de",
                    "5.beispiel.de",
                    "4.beispiel.de",
                    "3.beispiel.de",
                )
            list.first().name shouldBe null
        }

        test("the remote name is the fallback of a missing display name") {
            build(direct = listOf(partner(host = "a.beispiel.de", remoteName = "Remote"))).single().name shouldBe "Remote"
        }

        test("label: long names are cut at 60 code points, the host is never shortened, emoji are never split") {
            val long = "x".repeat(61)
            val label = PartnerLoginChoice(host = "h.beispiel.de", name = long).label
            label shouldBe "${"x".repeat(60)}… (h.beispiel.de)"
            val emoji = "😀".repeat(70)
            val l2 = PartnerLoginChoice(host = "h.beispiel.de", name = emoji).label
            l2 shouldBe "${"😀".repeat(60)}… (h.beispiel.de)"
            PartnerLoginChoice(host = "h.beispiel.de", name = null).label shouldBe "h.beispiel.de"
        }

        test("hostile names are cleaned; markup stays literal text") {
            val list =
                build(
                    direct =
                        listOf(
                            partner(host = "a.beispiel.de", name = "Ev‮il\u0000\nName"),
                            partner(host = "b.beispiel.de", name = "<script>alert(1)</script>"),
                        ),
                )
            val a = list.first { it.host == "a.beispiel.de" }
            a.label shouldNotContain "‮"
            a.label shouldNotContain "\u0000"
            a.label shouldNotContain "\n"
            list.first { it.host == "b.beispiel.de" }.label shouldContain "<script>"
        }

        test("filter: hits on name and host, case-insensitive, null query lists all") {
            val all =
                build(
                    direct =
                        listOf(
                            partner(host = "a.beispiel.de", name = "Partei der Vernunft"),
                            partner(host = "zzz.beispiel.de", name = "Anders"),
                        ),
                )
            PartnerLoginChoices.filter(all = all, query = "VERNUNFT").shown.map { it.host } shouldContainExactly listOf("a.beispiel.de")
            PartnerLoginChoices.filter(all = all, query = "ZZZ").shown.map { it.host } shouldContainExactly listOf("zzz.beispiel.de")
            PartnerLoginChoices.filter(all = all, query = "nichts").shown.shouldBeEmpty()
            PartnerLoginChoices.filter(all = all, query = null).shown.size shouldBe 2
        }

        test("filter: the cap is 200, the count is honest") {
            val all = (1..250).map { PartnerLoginChoice(host = "h$it.beispiel.de", name = null) }
            val r = PartnerLoginChoices.filter(all = all, query = null)
            r.shown.size shouldBe 200
            r.totalMatches shouldBe 250
            r.capped shouldBe true
            PartnerLoginChoices.filter(all = all.take(200), query = null).capped shouldBe false
        }

        test("normalizeQuery: blank is null, long input is cut to 100 code points, control and bidi are gone, regex chars are literal") {
            PartnerLoginChoices.normalizeQuery(null) shouldBe null
            PartnerLoginChoices.normalizeQuery("   ") shouldBe null
            PartnerLoginChoices.normalizeQuery("a".repeat(5000))!!.length shouldBe 100
            PartnerLoginChoices.normalizeQuery("a‮b\u0000c") shouldBe "abc"
            PartnerLoginChoices.normalizeQuery(" .* ") shouldBe ".*"
            val all = listOf(PartnerLoginChoice(host = "abc.beispiel.de", name = "Abc"))
            PartnerLoginChoices.filter(all = all, query = ".*").shown.shouldBeEmpty()
        }

        test("canonicalChoiceOrNull: trim and lowercase, refuse blank, over-long and control input") {
            PartnerLoginChoices.canonicalChoiceOrNull("  Host.Beispiel.DE ") shouldBe "host.beispiel.de"
            PartnerLoginChoices.canonicalChoiceOrNull(null) shouldBe null
            PartnerLoginChoices.canonicalChoiceOrNull("   ") shouldBe null
            PartnerLoginChoices.canonicalChoiceOrNull("a".repeat(254)) shouldBe null
            PartnerLoginChoices.canonicalChoiceOrNull("a\u0000b.de") shouldBe null
            PartnerLoginChoices.canonicalChoiceOrNull("a b.de") shouldBe null
        }
    })

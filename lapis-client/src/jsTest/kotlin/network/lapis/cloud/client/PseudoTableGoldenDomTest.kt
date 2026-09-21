package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CrowdfundingDistributionDto
import network.lapis.cloud.shared.domain.PoliticianProfileDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianWeightSnapshotDto
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.31 (W5): the four hand-built pseudo-tables of the LTR economy screens (my bids, distribution history, weight history,
 * politician ranking) became real tables (`dataTable`). The EXPECTED values below were worked out from the OLD source (header row +
 * one `hPanel` row per entry with fixed column widths): same rows in the same order, every cell the same text, "Führend Ja/Nein" and
 * the status label unchanged; in the ranking the label that the old rows repeated in every cell is now the column header.
 *
 * What this is NOT: a characterization test pinned green against the old implementation before the migration. The old shape renders
 * `div`s, the helpers below ask for `thead`/`tbody`, and test and migration landed in one commit -- so the literals are recalculated
 * from the old source by hand, and the structure assertions (headers, `aria-label`, de-emphasis classes) are necessarily new.
 */
class PseudoTableGoldenDomTest {
    private val bids =
        listOf(
            AuctionBidDto("b1", "a1", "Vereinsbanner", 12.5.toDecimal(), LocalDateTime(2026, 9, 1, 10, 30), true, AuctionStatus.OPEN),
            AuctionBidDto("b2", "a2", "Tombola", 4.0.toDecimal(), LocalDateTime(2026, 8, 30, 9, 0), false, AuctionStatus.SETTLED),
        )

    private val distributions =
        listOf(
            CrowdfundingDistributionDto(
                "d1",
                "p1",
                "Vereinsgarten",
                LocalDate(2026, 8, 1),
                LocalDate(2026, 8, 31),
                7,
                250.5.toDecimal(),
                LocalDateTime(2026, 9, 1, 3, 15),
                "m1",
                "Erika Musterfrau",
            ),
            CrowdfundingDistributionDto(
                "d2",
                "p2",
                "Jugendtreff",
                LocalDate(2026, 7, 1),
                LocalDate(2026, 7, 31),
                3,
                80.0.toDecimal(),
                LocalDateTime(2026, 8, 1, 4, 0),
                "m2",
                "Max Beispiel",
            ),
        )

    private val snapshots =
        listOf(
            PoliticianWeightSnapshotDto(
                "s1",
                "m1",
                LocalDate(2026, 8, 1),
                10.5.toDecimal(),
                3,
                1,
                2.5.toDecimal(),
                1,
                0,
                13.0.toDecimal(),
                LocalDateTime(2026, 9, 1, 2, 0),
            ),
            PoliticianWeightSnapshotDto(
                "s2",
                "m1",
                LocalDate(2026, 7, 1),
                8.0.toDecimal(),
                2,
                2,
                1.0.toDecimal(),
                0,
                1,
                9.0.toDecimal(),
                LocalDateTime(2026, 8, 1, 2, 0),
            ),
        )

    private fun politician(
        name: String,
        member: Double,
        guest: Double,
        combined: Double,
    ) = PoliticianProfileDto(
        "p-$name",
        "m-$name",
        name,
        PoliticianProfileStatus.ACTIVE,
        null,
        LocalDateTime(2026, 1, 1, 0, 0),
        "Vorstand",
        null,
        null,
        member.toDecimal(),
        0,
        0,
        guest.toDecimal(),
        0,
        0,
        combined.toDecimal(),
    )

    private val ranking = listOf(politician("Anna", 12.5, 3.0, 15.5), politician("Bert", 9.0, 1.5, 10.5))

    /** Column texts of the header and of every body row, whichever shape the table has. */
    private fun HTMLElement.headerTexts(): List<String> {
        val th = querySelectorAll("thead th")
        return (0 until th.length).map {
            th
                .item(it)!!
                .textContent
                .orEmpty()
                .trim()
        }
    }

    private fun HTMLElement.bodyRows(): List<List<String>> {
        val rows = querySelectorAll("tbody tr")
        return (0 until rows.length).map { r ->
            val cells = (rows.item(r) as HTMLElement).querySelectorAll("td")
            (0 until cells.length).map {
                cells
                    .item(it)!!
                    .textContent
                    .orEmpty()
                    .trim()
            }
        }
    }

    @Test
    fun myBids_sameRowsSameCells() {
        withMountedRoot("golden-bids") { root, element ->
            renderMyBidsTable(root, bids, FakeNarrowViewport(narrow = false))
            assertEquals(listOf("Auktion", "Ihr Höchstgebot", "Führend", "Status", "Abgegeben"), element().headerTexts())
            assertEquals(
                listOf(
                    listOf("Vereinsbanner", "◆ 12.5 LTR", "Ja", "Offen", "2026-09-01T10:30"),
                    listOf("Tombola", "◆ 4 LTR", "Nein", "Abgeschlossen (verkauft)", "2026-08-30T09:00"),
                ),
                element().bodyRows(),
            )
        }
    }

    @Test
    fun distributions_sameRowsSameCells() {
        withMountedRoot("golden-distributions") { root, element ->
            renderDistributionsTable(root, distributions, FakeNarrowViewport(narrow = false))
            assertEquals(listOf("Projekt", "Zeitraum", "Korb", "Betrag", "Berechnet"), element().headerTexts())
            assertEquals(
                listOf(
                    listOf("Vereinsgarten", "2026-08-01 – 2026-08-31", "7", "250.5 €", "2026-09-01T03:15 von Erika Musterfrau"),
                    listOf("Jugendtreff", "2026-07-01 – 2026-07-31", "3", "80 €", "2026-08-01T04:00 von Max Beispiel"),
                ),
                element().bodyRows(),
            )
        }
    }

    @Test
    fun weightHistory_sameRowsSameCells() {
        withMountedRoot("golden-weights") { root, element ->
            renderWeightHistoryTable(root, snapshots, FakeNarrowViewport(narrow = false))
            assertEquals(listOf("Monat", "Mitglieder-Gewicht", "Gast-Gewicht", "Gesamt", "Berechnet"), element().headerTexts())
            assertEquals(
                listOf(
                    listOf("2026-08-01", "◆ 10.5 LTR", "2.5", "13", "2026-09-01T02:00"),
                    listOf("2026-07-01", "◆ 8 LTR", "1", "9", "2026-08-01T02:00"),
                ),
                element().bodyRows(),
            )
        }
    }

    @Test
    fun ranking_sameOrderSameValues() {
        withMountedRoot("golden-ranking") { root, element ->
            renderTopPoliticiansList(root, ranking, FakeNarrowViewport(narrow = false))
            assertNotNull(element().querySelector("table"), "a real table")
            // the label the old rows repeated in every cell is the column header now
            assertEquals(listOf("Rang", "Politiker", "Mitglieder", "Gäste", "Gesamt"), element().headerTexts())
            assertEquals(
                listOf(
                    listOf("1.", "Anna", "◆ 12.5 LTR", "3", "15.5"),
                    listOf("2.", "Bert", "◆ 9 LTR", "1.5", "10.5"),
                ),
                element().bodyRows(),
            )
        }
    }

    @Test
    fun everyTableHasAnAccessibleName_andTheOldDeEmphasisSurvives() {
        withMountedRoot("golden-a11y") { root, element ->
            val viewport = FakeNarrowViewport(narrow = false)
            renderMyBidsTable(root, bids, viewport)
            renderDistributionsTable(root, distributions, viewport)
            renderWeightHistoryTable(root, snapshots, viewport)
            renderTopPoliticiansList(root, ranking, viewport)
            val tables = element().querySelectorAll("table")
            assertEquals(4, tables.length)
            assertEquals(
                listOf("Meine Gebote", "Verteilungshistorie", "Gewichtsverlauf", "Top-Politiker"),
                (0 until tables.length).map { (tables.item(it) as HTMLElement).getAttribute("aria-label") },
            )
            // the old rows: timestamps muted+small, name and total bold in the ranking, total bold+small in the weight history
            assertNotNull(element().querySelector("td span.text-muted.small"), "timestamp cells stay muted")
            val bold = element().querySelectorAll("td span.fw-bold")
            assertTrue(bold.length >= 4, "ranking names and totals stay bold: ${bold.length}")
            assertNotNull(element().querySelector("td span.fw-bold.small"), "the weight-history total stays bold+small")
        }
    }

    @Test
    fun narrowScreen_cardsCarryEveryColumnValue_andTheAccessibleName() {
        withMountedRoot("golden-cards-values") { root, element ->
            val narrow = FakeNarrowViewport(narrow = true)
            renderMyBidsTable(root, bids, narrow)
            renderTopPoliticiansList(root, ranking, narrow)
            val text = element().textContent.orEmpty()
            listOf("◆ 12.5 LTR", "Offen", "Ja", "Ihr Höchstgebot", "Führend", "◆ 9 LTR", "15.5", "10.5", "Gesamt").forEach {
                assertTrue(text.contains(it), "the card list shows '$it': $text")
            }
            val lists = element().querySelectorAll(".lapis-card-list")
            assertEquals(
                listOf("Meine Gebote", "Top-Politiker"),
                (0 until lists.length).map {
                    (lists.item(it) as HTMLElement).getAttribute("aria-label")
                },
            )
        }
    }

    @Test
    fun narrowScreen_showsACardPerEntry_notATable() {
        withMountedRoot("golden-cards") { root, element ->
            val narrow = FakeNarrowViewport(narrow = true)
            renderMyBidsTable(root, bids, narrow)
            renderDistributionsTable(root, distributions, narrow)
            renderWeightHistoryTable(root, snapshots, narrow)
            renderTopPoliticiansList(root, ranking, narrow)
            assertEquals(0, element().querySelectorAll("table").length, "no table below 768 px")
            assertEquals(8, element().querySelectorAll(".lapis-data-card").length, "one card per entry")
            val text = element().textContent.orEmpty()
            listOf("Vereinsbanner", "Tombola", "Vereinsgarten", "Jugendtreff", "2026-08-01", "Anna", "Bert").forEach { name ->
                assertTrue(text.contains(name), "card list shows $name")
            }
        }
    }
}

package network.lapis.cloud.server.ai.kb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private fun page(
    text: String,
    number: Int? = null,
) = PageText(pageNumber = number, text = text)

class TextChunkerTest :
    FunSpec({
        test("a section sign heading becomes the label") {
            val chunks = TextChunker.chunk(listOf(page(text = "§ 7 Beitrag\n(1) Der Beitrag beträgt zehn Euro.")))
            chunks shouldHaveSize 1
            chunks.single().sectionLabel shouldBe "§ 7 Beitrag"
        }

        test("a Markdown heading becomes the label without its hashes") {
            TextChunker
                .chunk(
                    listOf(page(text = "## Mitgliedschaft\nJede natürliche Person kann Mitglied werden.")),
                ).single()
                .sectionLabel shouldBe
                "Mitgliedschaft"
        }

        test("Artikel and Art. headings become the label") {
            TextChunker.chunk(listOf(page(text = "Artikel 3 Zweck\nDer Verein fördert die Bildung."))).single().sectionLabel shouldBe
                "Artikel 3 Zweck"
            TextChunker
                .chunk(
                    listOf(page(text = "Art. 4 Organe\nOrgane sind die Versammlung und der Vorstand.")),
                ).single()
                .sectionLabel shouldBe
                "Art. 4 Organe"
        }

        test("a paragraph marker alone is the label, and is appended to a preceding heading") {
            TextChunker.chunk(listOf(page(text = "(2) Der Vorstand besteht aus drei Personen."))).single().sectionLabel shouldBe "(2)"
            TextChunker.chunk(listOf(page(text = "§ 5 Vorstand\n\n(2) Der Vorstand besteht aus drei Personen."))).let { chunks ->
                chunks.single().sectionLabel shouldBe "§ 5 Vorstand"
            }
        }

        test("without any heading the label is null but the page number survives as the locator") {
            val chunks = TextChunker.chunk(listOf(page(text = "Ein Fließtext ohne Überschrift, der einfach weitergeht.", number = 4)))
            chunks.single().sectionLabel shouldBe null
            chunks.single().pageNumber shouldBe 4
        }

        test("long text is split into chunks near the target size with overlap") {
            val paragraph = "wort ".repeat(100).trim() // ~499 chars
            val text = (1..8).joinToString(separator = "\n\n") { "$paragraph $it" }
            val chunks = TextChunker.chunk(listOf(page(text = text)))
            chunks.size shouldBeGreaterThan 2
            chunks.forEachIndexed { index, chunk ->
                chunk.index shouldBe index
                // target + overlap tail + separator slack
                chunk.text.length shouldBeLessThanOrEqualTo TextChunker.TARGET_CHARS + TextChunker.OVERLAP_CHARS + 2
            }
            // Overlap: the start of the next chunk is contained at the end of the previous one.
            chunks[0].text.contains(chunks[1].text.take(50)) shouldBe true
        }

        test("a single oversized paragraph is cut on word boundaries") {
            val chunks = TextChunker.chunk(listOf(page(text = "satz ".repeat(1_000))))
            chunks.size shouldBeGreaterThan 3
            chunks.forEach { it.text.length shouldBeLessThanOrEqualTo TextChunker.TARGET_CHARS + TextChunker.OVERLAP_CHARS + 2 }
        }

        test("the page number is that of the page a chunk starts on") {
            val first = "alpha ".repeat(180).trim()
            val chunks =
                TextChunker.chunk(
                    listOf(page(text = first, number = 1), page(text = "$first\n\nbeta ".plus("beta ".repeat(180)), number = 2)),
                )
            chunks.first().pageNumber shouldBe 1
            chunks.any { it.pageNumber == 2 } shouldBe true
        }

        test("control characters are removed") {
            TextChunker.chunk(listOf(page(text = "Text\u0000mit\u0007Steuerzeichen"))).single().text shouldNotContain "\u0000"
        }

        test("the number of chunks per document is capped") {
            val big = (1..2_300).joinToString(separator = "\n\n") { "block $it " + "x".repeat(1_150) }
            TextChunker.chunk(listOf(page(text = big))) shouldHaveSize TextChunker.MAX_CHUNKS_PER_DOCUMENT
        }

        test("empty input yields no chunks") {
            TextChunker.chunk(emptyList()) shouldHaveSize 0
            TextChunker.chunk(listOf(page(text = "   \n\n  "))) shouldHaveSize 0
        }
    })

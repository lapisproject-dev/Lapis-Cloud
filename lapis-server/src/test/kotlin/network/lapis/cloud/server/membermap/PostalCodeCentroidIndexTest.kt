package network.lapis.cloud.server.membermap

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val HEADER = "postal_code,place_name,lat,lon"

class PostalCodeCentroidIndexTest :
    FunSpec({
        test("bundled -> parses successfully, at least MIN_EXPECTED_ENTRIES") {
            val bundled = checkNotNull(PostalCodeCentroidIndex.loadFromClasspath())
            (bundled.size >= PostalCodeCentroidIndex.MIN_EXPECTED_ENTRIES) shouldBe true
        }

        test("bundled -> exactly 10_813 entries (pinned against the shipped CSV)") {
            val bundled = checkNotNull(PostalCodeCentroidIndex.loadFromClasspath())
            bundled.size shouldBe 10_813
        }

        test("bundled -> 38100 resolves to Braunschweig") {
            val bundled = checkNotNull(PostalCodeCentroidIndex.loadFromClasspath())
            val entry = checkNotNull(bundled.lookup("38100"))
            entry.placeName shouldBe "Braunschweig"
            entry.lat shouldBe 52.2647
            entry.lon shouldBe 10.5233
        }

        test("bundled -> 01059 resolves to a quoted, comma-containing place name") {
            val bundled = checkNotNull(PostalCodeCentroidIndex.loadFromClasspath())
            val entry = checkNotNull(bundled.lookup("01059"))
            entry.placeName shouldBe "Deutsche Telekom AG, GSUS"
        }

        test("lookup trims whitespace") {
            val bundled = checkNotNull(PostalCodeCentroidIndex.loadFromClasspath())
            checkNotNull(bundled.lookup(" 38100 "))
        }

        test("unknown postal code -> null, never throws") {
            val bundled = checkNotNull(PostalCodeCentroidIndex.loadFromClasspath())
            bundled.lookup("00000") shouldBe null
        }

        test("CRLF line endings (a trailing \\r on every line, including the header) parse identically to LF") {
            val filler = minimalFillerRows(PostalCodeCentroidIndex.MIN_EXPECTED_ENTRIES)
            val lfLines = listOf(HEADER, "38100,Braunschweig,52.2647,10.5233") + filler
            val crlfLines = lfLines.map { "$it\r" }
            val lfIndex = PostalCodeCentroidIndex.parse(lfLines.asSequence())
            val crlfIndex = PostalCodeCentroidIndex.parse(crlfLines.asSequence())
            crlfIndex.size shouldBe lfIndex.size
            checkNotNull(crlfIndex.lookup("38100")).placeName shouldBe "Braunschweig"
        }

        test("parse: missing resource -> null, never throws, logged only") {
            PostalCodeCentroidIndex.loadFromClasspath("/geodata/does-not-exist.csv") shouldBe null
        }

        test("parse: wrong header -> throws IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                PostalCodeCentroidIndex.parse(sequenceOf("wrong,header", "38100,Braunschweig,52.2647,10.5233"))
            }
        }

        test("parse: coordinate outside Germany bounds -> throws") {
            shouldThrow<IllegalArgumentException> {
                PostalCodeCentroidIndex.parse(
                    (
                        listOf(
                            HEADER,
                            "38100,Paris,48.8566,2.3522",
                        ) + minimalFillerRows(PostalCodeCentroidIndex.MIN_EXPECTED_ENTRIES)
                    ).asSequence(),
                )
            }
        }

        test("parse: duplicate postal code -> throws") {
            shouldThrow<IllegalArgumentException> {
                PostalCodeCentroidIndex.parse(
                    (
                        listOf(HEADER, "38100,Braunschweig,52.2647,10.5233", "38100,Braunschweig,52.2647,10.5233") +
                            minimalFillerRows(PostalCodeCentroidIndex.MIN_EXPECTED_ENTRIES)
                    ).asSequence(),
                )
            }
        }

        test("parse: too few rows -> throws") {
            shouldThrow<IllegalArgumentException> {
                PostalCodeCentroidIndex.parse(sequenceOf(HEADER, "38100,Braunschweig,52.2647,10.5233"))
            }
        }

        test("parse: malformed row (wrong field count) -> throws") {
            shouldThrow<IllegalArgumentException> {
                PostalCodeCentroidIndex.parse(
                    (
                        listOf(
                            HEADER,
                            "38100,Braunschweig,52.2647",
                        ) + minimalFillerRows(PostalCodeCentroidIndex.MIN_EXPECTED_ENTRIES)
                    ).asSequence(),
                )
            }
        }

        test("parse: enough distinct valid rows -> succeeds") {
            val rows = (10001..10001 + PostalCodeCentroidIndex.MIN_EXPECTED_ENTRIES).map { code -> "$code,Testort,51.0,10.0" }
            val index = PostalCodeCentroidIndex.parse((listOf(HEADER) + rows).asSequence())
            index.size shouldBe rows.size
        }
    })

/** Distinct, valid, in-bounds filler rows so a single bad row can be isolated as the ONLY failure reason in a test above MIN_EXPECTED_ENTRIES. */
private fun minimalFillerRows(min: Int): List<String> = (20001..20000 + min).map { code -> "$code,Testort,51.0,10.0" }

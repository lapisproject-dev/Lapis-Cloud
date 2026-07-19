package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * Pure-function table-driven tests for [GermanAmountInWords.format] -- no DB/PDF involved. See
 * that object's KDoc: green tests here prove internal consistency of the algorithm, not legal
 * correctness of German orthography in every edge case -- flag for human spot-check regardless.
 */
class GermanAmountInWordsTest :
    FunSpec({
        test("format renders the euro/cent parts as German cardinal words") {
            val cases =
                listOf(
                    "0.00" to "null Euro und null Cent",
                    "1.00" to "ein Euro und null Cent",
                    "2.00" to "zwei Euro und null Cent",
                    "11.00" to "elf Euro und null Cent",
                    "21.00" to "einundzwanzig Euro und null Cent",
                    "100.00" to "einhundert Euro und null Cent",
                    "101.00" to "einhunderteins Euro und null Cent",
                    "1000.00" to "eintausend Euro und null Cent",
                    "1234.56" to "eintausendzweihundertvierunddreißig Euro und sechsundfünfzig Cent",
                    "999999.99" to "neunhundertneunundneunzigtausendneunhundertneunundneunzig Euro und neunundneunzig Cent",
                )
            cases.forEach { (amount, expected) ->
                GermanAmountInWords.format(BigDecimal(amount)) shouldBe expected
            }
        }

        test("rounds to two decimal places (HALF_UP)") {
            GermanAmountInWords.format(BigDecimal("1.005")) shouldBe "ein Euro und ein Cent"
            GermanAmountInWords.format(BigDecimal("1")) shouldBe "ein Euro und null Cent"
        }

        test("digit 1 is 'ein' only as a compound-tens prefix or as the entire amount, 'eins' otherwise") {
            GermanAmountInWords.format(BigDecimal("1.00")) shouldBe "ein Euro und null Cent"
            GermanAmountInWords.format(BigDecimal("0.01")) shouldBe "null Euro und ein Cent"
            GermanAmountInWords.format(BigDecimal("31.00")) shouldBe "einunddreißig Euro und null Cent"
            GermanAmountInWords.format(BigDecimal("1001.00")) shouldBe "eintausendeins Euro und null Cent"
        }

        test("negative amounts are rendered by absolute value") {
            GermanAmountInWords.format(BigDecimal("-5.00")) shouldBe "fünf Euro und null Cent"
        }
    })

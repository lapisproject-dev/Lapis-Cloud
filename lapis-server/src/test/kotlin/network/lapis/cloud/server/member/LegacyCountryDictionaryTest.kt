package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.geo.CountryDisplayNames
import network.lapis.cloud.server.member.LegacyCountryDictionary.Resolution
import network.lapis.cloud.shared.domain.CountryCodes
import java.util.Locale

class LegacyCountryDictionaryTest :
    FunSpec({
        fun country(raw: String?) = LegacyCountryDictionary.resolveCountry(raw)

        fun nationality(raw: String?) = LegacyCountryDictionary.resolveNationality(raw)

        fun recognised(vararg codes: String) = Resolution.Recognised(codes.toList())

        test("every selectable country resolves from its own German and English name, unambiguously") {
            CountryCodes.SELECTABLE.forEach { code ->
                country(CountryDisplayNames.germanName(code)) shouldBe recognised(code)
                val english = Locale.of("", code).getDisplayCountry(Locale.ENGLISH)
                if (english.isNotBlank() && english != code) country(english) shouldBe recognised(code)
            }
        }

        test("names in the other UI languages resolve") {
            country("Polska") shouldBe recognised("PL")
            country("Allemagne") shouldBe recognised("DE")
            country("Alemania") shouldBe recognised("DE")
            country("Germania") shouldBe recognised("DE")
            country("Duitsland") shouldBe recognised("DE")
            country("Германия") shouldBe recognised("DE")
            country("Niemcy") shouldBe recognised("DE")
        }

        test("diacritics, case, transliteration and punctuation are tolerated") {
            country("  türkei ") shouldBe recognised("TR")
            country("Tuerkei") shouldBe recognised("TR")
            country("TURKEI") shouldBe recognised("TR")
            country("Österreich") shouldBe recognised("AT")
            country("Oesterreich") shouldBe recognised("AT")
            country("Bosnien-Herzegowina") shouldBe recognised("BA")
            country("Côte d'Ivoire") shouldBe recognised("CI")
        }

        test("alpha-2 and alpha-3 codes only in capitals; words that are also codes stay open") {
            country("PL") shouldBe recognised("PL")
            country("POL") shouldBe recognised("PL")
            country("DEU") shouldBe recognised("DE")
            country("USA") shouldBe recognised("US")
            country("xx") shouldBe Resolution.Unrecognised
            country("NO") shouldBe Resolution.Ambiguous
            country("IN") shouldBe Resolution.Ambiguous
            country("no") shouldBe Resolution.Unrecognised
            country("AND") shouldBe Resolution.Ambiguous
        }

        test("short forms and aliases") {
            country("BRD") shouldBe recognised("DE")
            country("Bundesrepublik Deutschland") shouldBe recognised("DE")
            country("D") shouldBe recognised("DE")
            country("UK") shouldBe recognised("GB")
            country("Holland") shouldBe recognised("NL")
            country("Vereinigte Staaten von Amerika") shouldBe recognised("US")
        }

        test("genuinely ambiguous values are never guessed") {
            listOf("Kongo", "Congo", "Korea", "Jugoslawien", "Sowjetunion", "DDR", "Europa").forEach {
                country(it) shouldBe
                    Resolution.Ambiguous
            }
            country("Kongo-Kinshasa") shouldBe recognised("CD")
            country("Kongo-Brazzaville") shouldBe recognised("CG")
        }

        test("noise and empty values stay unrecognised") {
            listOf("n/a", "-", "???", "k.A.", "   ", "", "Wakanda").forEach { country(it) shouldBe Resolution.Unrecognised }
            country(null) shouldBe Resolution.Unrecognised
        }

        test("XS and ZZ are never a country") {
            country("staatenlos") shouldBe Resolution.Unrecognised
            country("XS") shouldBe Resolution.Unrecognised
            country("ZZ") shouldBe Resolution.Unrecognised
        }

        test("nationality: adjectives with every German ending and English demonyms") {
            nationality("deutsch") shouldBe recognised("DE")
            nationality("Deutsche") shouldBe recognised("DE")
            nationality("Deutscher") shouldBe recognised("DE")
            nationality("österreichisch") shouldBe recognised("AT")
            nationality("polnisch") shouldBe recognised("PL")
            nationality("türkisch") shouldBe recognised("TR")
            nationality("Georgian") shouldBe recognised("GE")
            nationality("German") shouldBe recognised("DE")
            nationality("Deutschland") shouldBe recognised("DE")
        }

        test("nationality: lists split on comma, slash, semicolon, ampersand, und, and; sorted and deduplicated") {
            nationality("deutsch, polnisch") shouldBe recognised("DE", "PL")
            nationality("polnisch/deutsch") shouldBe recognised("DE", "PL")
            nationality("deutsch; polnisch; deutsch") shouldBe recognised("DE", "PL")
            nationality("deutsch & polnisch") shouldBe recognised("DE", "PL")
            nationality("deutsch und georgisch") shouldBe recognised("DE", "GE")
            nationality("german and polish") shouldBe recognised("DE", "PL")
        }

        test("nationality: one open part keeps the whole value open") {
            nationality("deutsch, Wakanda") shouldBe Resolution.Unrecognised
            nationality("deutsch, Kongo") shouldBe Resolution.Ambiguous
        }

        test("nationality: at most ten parts") {
            val names =
                listOf(
                    "Deutschland",
                    "Polen",
                    "Frankreich",
                    "Italien",
                    "Spanien",
                    "Portugal",
                    "Österreich",
                    "Schweiz",
                    "Belgien",
                    "Dänemark",
                    "Norwegen",
                )
            (nationality(names.take(10).joinToString(", ")) is Resolution.Recognised) shouldBe true
            nationality(names.joinToString(", ")) shouldBe Resolution.Unrecognised
        }

        test("nationality: stateless and unclear are exclusive") {
            nationality("staatenlos") shouldBe recognised("XS")
            nationality("stateless") shouldBe recognised("XS")
            nationality("ungeklärt") shouldBe recognised("ZZ")
            nationality("staatenlos, deutsch") shouldBe Resolution.Ambiguous
            nationality("ungeklärt / polnisch") shouldBe Resolution.Ambiguous
        }

        test("nationality: noise stays open") {
            listOf("n/a", "-", "", "  ", "???").forEach { nationality(it) shouldBe Resolution.Unrecognised }
            nationality(null) shouldBe Resolution.Unrecognised
        }

        test("normalize folds case, diacritics and punctuation") {
            LegacyCountryDictionary.normalize("  Côte d'Ivoire ") shouldBe "cote d ivoire"
            LegacyCountryDictionary.normalize("Großbritannien") shouldBe "grossbritannien"
        }
    })

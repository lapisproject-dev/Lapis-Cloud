package network.lapis.cloud.server.geo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.lapis.cloud.shared.domain.CountryCodes
import java.util.Locale

class CountryDisplayNamesTest :
    FunSpec({
        test("all 250 selectable codes have a German name that is neither blank nor the code itself") {
            CountryCodes.SELECTABLE.size shouldBe 250
            CountryCodes.SELECTABLE.forEach { code ->
                val name = CountryDisplayNames.germanName(code)
                name.isNotBlank() shouldBe true
                (name != code) shouldBe true
            }
        }

        test("Kosovo has its fixed name, whatever the JDK knows") {
            CountryDisplayNames.germanName("XK") shouldBe "Kosovo"
            CountryDisplayNames.germanName("xk") shouldBe "Kosovo"
        }

        test("XS and ZZ never come from Locale") {
            CountryDisplayNames.germanName("XS") shouldBe CountryDisplayNames.STATELESS_DE
            CountryDisplayNames.germanName("ZZ") shouldBe CountryDisplayNames.UNCLEAR_DE
            // the JDK would answer with the bare code for these
            Locale.of("", "XS").getDisplayCountry(Locale.GERMAN) shouldNotBe "staatenlos"
        }

        test("well-known names and an unknown code falls back to the code (never throws)") {
            CountryDisplayNames.germanName("DE") shouldBe "Deutschland"
            CountryDisplayNames.germanName("PL") shouldBe "Polen"
            CountryDisplayNames.germanName("QQ") shouldBe "QQ"
        }

        test("nationality text joins the German names in the given order") {
            CountryDisplayNames.germanNationalityText(listOf("DE", "PL")) shouldBe "Deutschland, Polen"
            CountryDisplayNames.germanNationalityText(emptyList()) shouldBe ""
        }
    })

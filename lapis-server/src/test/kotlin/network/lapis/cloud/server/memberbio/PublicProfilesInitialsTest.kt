package network.lapis.cloud.server.memberbio

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.routes.PublicProfilesHtml

/** Welle V1.9.20 -- the initials of the photo-less avatar: first LETTER of the first and last word, grapheme-aware, `null` without a letter. */
class PublicProfilesInitialsTest :
    FunSpec({
        test("two words give both initials, a hyphenated first name does not split") {
            PublicProfilesHtml.initialsOf("Anna-Lena Müller") shouldBe "AM"
            PublicProfilesHtml.initialsOf("erika mustermann") shouldBe "EM"
        }

        test("a middle name is ignored: first and last word only") {
            PublicProfilesHtml.initialsOf("Hans Peter Schmidt") shouldBe "HS"
        }

        test("a single word gives a single initial, an umlaut stays intact") {
            PublicProfilesHtml.initialsOf("Ölaf") shouldBe "Ö"
            PublicProfilesHtml.initialsOf("Madonna") shouldBe "M"
        }

        test("a combining accent is never cut off its base letter") {
            PublicProfilesHtml.initialsOf("Émile Zola") shouldBe "ÉZ"
        }

        test("digits and emoji contribute nothing -- no letter at all means the silhouette (null)") {
            PublicProfilesHtml.initialsOf("12345") shouldBe null
            PublicProfilesHtml.initialsOf("😀 😀") shouldBe null
            PublicProfilesHtml.initialsOf("   ") shouldBe null
            PublicProfilesHtml.initialsOf("") shouldBe null
        }

        test("a word that does not start with a letter is skipped, the other still counts") {
            PublicProfilesHtml.initialsOf("😀 Weber") shouldBe "W"
            PublicProfilesHtml.initialsOf("3M Müller") shouldBe "M"
        }
    })

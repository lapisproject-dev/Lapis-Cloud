package network.lapis.cloud.server.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** V1.9.342 -- `event.translation_consent` and `organization_settings.content_language` of a restored row. */
class TranslationRestoreTest :
    FunSpec({
        fun row(vararg extra: Pair<String, JsonElement>) = JsonObject(mapOf("name" to JsonPrimitive("x")) + extra)

        test("an event row without the consent column gets false, a NULL too") {
            withDefaultTranslationConsent(row())["translation_consent"] shouldBe JsonPrimitive(false)
            withDefaultTranslationConsent(row("translation_consent" to JsonNull))["translation_consent"] shouldBe JsonPrimitive(false)
        }

        test("a given consent is KEPT, true and false") {
            val yes = row("translation_consent" to JsonPrimitive(true))
            withDefaultTranslationConsent(yes) shouldBe yes
            val no = row("translation_consent" to JsonPrimitive(false))
            withDefaultTranslationConsent(no) shouldBe no
        }

        test("a consent that is not a boolean is refused with a message that names the column") {
            listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonPrimitive("yes"), JsonObject(emptyMap())).forEach { bad ->
                shouldThrow<RestoreIncompleteException> { withDefaultTranslationConsent(row("translation_consent" to bad)) }
                    .message!! shouldContain "translation_consent"
            }
        }

        test("content_language: a supported code passes, an absent one gets de, a foreign one is refused") {
            val ok = row("content_language" to JsonPrimitive("fr"))
            withValidContentLanguage(ok) shouldBe ok
            withValidContentLanguage(row())["content_language"] shouldBe JsonPrimitive("de")
            withValidContentLanguage(row("content_language" to JsonNull))["content_language"] shouldBe JsonPrimitive("de")
            listOf(JsonPrimitive("xx"), JsonPrimitive("FR"), JsonPrimitive(""), JsonPrimitive(5)).forEach { bad ->
                shouldThrow<RestoreIncompleteException> { withValidContentLanguage(row("content_language" to bad)) }
                    .message!! shouldContain "content_language"
            }
        }
    })

package network.lapis.cloud.server.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** V1.9.332 -- the `mail_language` column of a restored `organization_settings` row. */
class MailLanguageRestoreTest :
    FunSpec({
        fun row(vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>) =
            JsonObject(mapOf("name" to JsonPrimitive("x")) + extra)

        test("a supported code passes unchanged") {
            val r = row("mail_language" to JsonPrimitive("pl"))
            withValidMailLanguage(r) shouldBe r
        }

        test("an old bundle without the column gets the default de, a NULL too") {
            withValidMailLanguage(row())["mail_language"] shouldBe JsonPrimitive("de")
            withValidMailLanguage(row("mail_language" to JsonNull))["mail_language"] shouldBe JsonPrimitive("de")
        }

        test("a value outside the eight codes is refused with a message that names the column") {
            listOf(JsonPrimitive("xx"), JsonPrimitive("PL"), JsonPrimitive(""), JsonPrimitive(5), JsonObject(emptyMap())).forEach { bad ->
                shouldThrow<RestoreIncompleteException> { withValidMailLanguage(row("mail_language" to bad)) }
                    .message!! shouldContain "mail_language"
            }
        }
    })

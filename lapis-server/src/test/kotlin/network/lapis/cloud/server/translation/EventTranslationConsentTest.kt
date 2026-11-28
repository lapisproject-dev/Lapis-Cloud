package network.lapis.cloud.server.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.dsgvo.EventPersonalData
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** V1.9.342 -- the consent writer: decision trail, generation, deletion on revoke, and the DSGVO export/erasure of the decision. */
class EventTranslationConsentTest :
    FunSpec({
        val fixtures = TranslationFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterTest { fixtures.cleanup() }

        test("a new event has no consent; granting records who and when") {
            val id = fixtures.event(consent = false)
            fixtures.eventRow(id)[EventTable.translationConsent] shouldBe false
            fixtures.eventRow(id)[EventTable.translationConsentBy] shouldBe null
            val at = LocalDateTime(2031, 3, 1, 12, 0)
            fixtures.grant(eventId = id, now = at) shouldBe true
            val row = fixtures.eventRow(id)
            row[EventTable.translationConsent] shouldBe true
            row[EventTable.translationConsentBy] shouldBe fixtures.author
            row[EventTable.translationConsentAt] shouldBe at
        }

        test("granting again is a no-op and keeps the decision time") {
            val id = fixtures.event(consent = false)
            val at = LocalDateTime(2031, 3, 1, 12, 0)
            fixtures.grant(eventId = id, now = at)
            fixtures.grant(eventId = id, now = LocalDateTime(2031, 3, 2, 12, 0)) shouldBe false
            fixtures.eventRow(id)[EventTable.translationConsentAt] shouldBe at
        }

        test(
            "a revoke keeps the last decision as a trail, deletes every translation of the event and a re-grant gets a strictly later time",
        ) {
            val id = fixtures.event(consent = false)
            val first = LocalDateTime(2031, 3, 1, 12, 0)
            fixtures.grant(eventId = id, now = first)
            transaction {
                ContentTranslationStore.insertIgnoringDuplicates(
                    listOf(
                        ContentTranslationRow(
                            contentType = TranslatableContentType.EVENT,
                            contentId = id,
                            field = TranslatableField.TITLE,
                            sourceSha256 = "a".repeat(64),
                            sourceLang = "de",
                            targetLang = "en",
                            translatedText = "x",
                            modelProfile = "p",
                        ),
                    ),
                )
            }
            fixtures.rowCount(id) shouldBe 1
            fixtures.revoke(eventId = id, now = first) shouldBe true // same wall time on purpose: the stored time must still advance
            fixtures.rowCount(id) shouldBe 0
            val revoked = fixtures.eventRow(id)
            revoked[EventTable.translationConsent] shouldBe false
            revoked[EventTable.translationConsentBy] shouldBe fixtures.author
            (revoked[EventTable.translationConsentAt]!! > first) shouldBe true
            fixtures.grant(eventId = id, now = first)
            (fixtures.eventRow(id)[EventTable.translationConsentAt]!! > revoked[EventTable.translationConsentAt]!!) shouldBe true
        }

        test("an unknown event is refused") {
            shouldThrow<NotFoundException> { fixtures.grant(eventId = Uuid.random()) }
        }

        test("the member's own decisions are exported; erasure retains the row with a reason") {
            val id = fixtures.event(title = "Mein Fest", consent = true)
            val export = transaction { EventPersonalData.exportMember(fixtures.author) }
            val decisions = export["translationConsentDecisions"]!!.jsonArray
            val mine = decisions.map { it.jsonObject }.single { it["eventId"]!!.jsonPrimitive.content == id.toString() }
            mine["title"]!!.jsonPrimitive.content shouldBe "Mein Fest"
            mine["consent"]!!.jsonPrimitive.boolean shouldBe true
            mine["decidedAt"].shouldNotBeNull()

            val outcomes = transaction { EventPersonalData.eraseMember(memberId = fixtures.author, mode = ErasureMode.ANONYMIZE) }
            outcomes.map { it.table } shouldContain "event"
            val reasons = outcomes.mapNotNull { it.retentionReason }
            reasons.any { it.contains("Einwilligung") } shouldBe true
            fixtures.eventRow(id)[EventTable.translationConsentBy] shouldBe fixtures.author
        }
    })

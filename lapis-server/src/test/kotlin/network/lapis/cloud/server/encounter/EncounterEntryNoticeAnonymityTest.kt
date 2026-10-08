package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.EncounterEntryNotice
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailTemplates
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Welle V1.9.76 -- Art. 9 GDPR negative tests: the entry notice must not be able to name the person who entered. Behavioural (a real
 * entry with a unique name and address) and structural (no field or parameter that could carry a person).
 */
class EncounterEntryNoticeAnonymityTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        test(
            "the rendered mail (subject, plain text, HTML) of a real entry contains neither the name, nor the address, nor the id of the entrant",
        ) {
            val rig = EncounterRig()
            val board = fx.createMember(role = network.lapis.cloud.shared.domain.AccountRole.BOARD)
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = board, notifyMode = EncounterNotifyMode.FIRST_GUEST)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val entrant = fx.createMember(name = "Zacharias Quirinius Unverwechselbar")
            val entrantEmail = "unverwechselbar-entrant@example.org"
            transaction { MemberTable.update({ MemberTable.id eq entrant }) { it[email] = entrantEmail } }
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = entrant) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                val notice = rig.entryMailer.calls.single()
                val mail =
                    MailTemplates.encounterEntryNotice(
                        notice = notice,
                        branding =
                            MailBranding(
                                fromDisplayName = "Partei der Vernunft",
                                replyTo = null,
                                publicBaseUrl = "https://pzb.example.org",
                            ),
                    )
                listOf(mail.subject, mail.plainText, mail.html).forEach { text ->
                    text shouldNotContain "Zacharias"
                    text shouldNotContain "Unverwechselbar"
                    text shouldNotContain entrantEmail
                    text shouldNotContain entrant.toString()
                    text shouldNotContain entrant.toString().take(8)
                }
                // the recipients are the office holders, never the entrant
                notice.recipients.contains(entrantEmail) shouldBe false
            }
        }

        test("structure: EncounterEntryNotice has no field that can carry the entrant") {
            val fields = EncounterEntryNotice::class.java.declaredFields.map { it.name.lowercase() }
            fields
                .filter { it.contains("member") || it.contains("name") && it != "spacetitle" || it.contains("identity") || it == "email" }
                .shouldBeEmpty()
            // exactly the allowed vocabulary
            fields.toSet() shouldBe setOf("recipients", "spacetitle", "kind", "at", "windowend", "entries", "presentcount")
        }

        test("structure: no function of the notice state or the notifier takes a member id, an identity, an address or any String") {
            listOf("encounter/EncounterEntryNoticeState.kt", "encounter/EncounterEntryNotifier.kt").forEach { path ->
                val functions = EncounterSourceScan.functions(EncounterSourceScan.mainFile(path))
                (functions.size >= 5) shouldBe true // the scan is not vacuous
                functions.forEach { fn ->
                    val params = fn.params.lowercase()
                    listOf("member", "identity", "email", "displayname").forEach { word ->
                        (params.contains(word)) shouldBe false
                    }
                    // the only values the notice machinery accepts are ids of spaces/sessions, the mode and the time -- no free text
                    (Regex(""":\s*String\b""").containsMatchIn(fn.params)) shouldBe false
                }
            }
        }
    })

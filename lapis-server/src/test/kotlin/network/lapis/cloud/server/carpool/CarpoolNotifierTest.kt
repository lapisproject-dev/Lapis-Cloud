package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.mail.CARPOOL_NOTICE_PURPOSE
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailPurpose
import network.lapis.cloud.server.mail.MailTemplates
import network.lapis.cloud.server.mail.budget.MailLane
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.rpc.carpoolTestApplication
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.concurrent.CopyOnWriteArrayList

private class RecordingMailer : CarpoolNoticeMailer {
    data class Sent(
        val to: String,
        val kind: CarpoolNoticeKind,
        val insideTransaction: Boolean,
    )

    val sent = CopyOnWriteArrayList<Sent>()
    override val enabled: Boolean = true

    override fun send(
        to: String,
        notice: CarpoolNotice,
    ) {
        sent += Sent(to = to, kind = notice.kind, insideTransaction = TransactionManager.currentOrNull() != null)
    }
}

/**
 * V1.9.125 -- the notifications of the carpool centre: sent AFTER the commit (never inside the transaction: Exposed repeats a block on
 * `SQLException`), only to people who can actually receive a mail (ACTIVE; a FRIEND with a verified address; never a GUEST -- its address is a
 * synthetic `.invalid` one --, never an anonymised account), with a text that names nobody and carries no meeting note.
 */
class CarpoolNotifierTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        test("a request mails the ACTIVE driver after the commit; a confirmation mails neither a guest nor an unverified friend") {
            val mailer = RecordingMailer()
            carpoolTestApplication(deps = CarpoolDeps(notifier = CarpoolNotifier(mailer))) {
                val driver = data.member(label = "n-driver", email = "driver-notice@example.org")
                val guest = data.member(label = "n-guest", status = MemberStatus.GUEST, email = "guest+abc@federation.invalid")
                val unverified =
                    data.member(
                        label = "n-friend",
                        status = MemberStatus.FRIEND,
                        emailVerified = false,
                        email = "unverified-friend@example.org",
                    )
                val offer = data.posting(author = driver, seats = 3)

                val asked = call(member = guest) { requestParticipation(offer.toString()) }
                mailer.sent.map { it.to to it.kind } shouldBe listOf("driver-notice@example.org" to CarpoolNoticeKind.REQUESTED)
                call(member = driver) { confirmParticipation(asked.id) } // the notice for the guest is dropped: no deliverable address
                val askedFriend = call(member = unverified) { requestParticipation(offer.toString()) }
                call(member = driver) { confirmParticipation(askedFriend.id) } // dropped: address not verified
                mailer.sent.map { it.to }.toSet() shouldBe setOf("driver-notice@example.org")
                mailer.sent.none { it.insideTransaction } shouldBe true // never inside the transaction
            }
        }

        test("a verified friend is mailed; an anonymised account and a disabled mailer are not") {
            val mailer = RecordingMailer()
            carpoolTestApplication(deps = CarpoolDeps(notifier = CarpoolNotifier(mailer))) {
                val driver = data.member(label = "n2-driver", email = "driver2-notice@example.org")
                val friend = data.member(label = "n2-friend", status = MemberStatus.FRIEND, email = "verified-friend@example.org")
                val offer = data.posting(author = driver, seats = 3)
                val asked = call(member = friend) { requestParticipation(offer.toString()) }
                call(member = driver) { confirmParticipation(asked.id) }
                mailer.sent.map { it.to to it.kind }.toSet() shouldBe
                    setOf(
                        "driver2-notice@example.org" to CarpoolNoticeKind.REQUESTED,
                        "verified-friend@example.org" to CarpoolNoticeKind.CONFIRMED,
                    )

                val anonymised = data.member(label = "n2-anon", status = MemberStatus.FRIEND, anonymized = true, email = "anon@example.org")
                val before = mailer.sent.size
                CarpoolNotifier(mailer).dispatch(
                    listOf(
                        CarpoolNotice(
                            recipientId = anonymised,
                            kind = CarpoolNoticeKind.INVITED,
                            date = LocalDate(2026, 11, 1),
                            fromPlace = "A",
                            toPlace = "B",
                        ),
                    ),
                )
                mailer.sent.size shouldBe before
            }
            val silent = RecordingMailer()
            CarpoolNotifier(NoOpCarpoolNoticeMailer).dispatch(emptyList())
            silent.sent.shouldBeEmpty()
        }

        test("deleting an offer notifies the open and confirmed passengers once, an update of only the seats notifies nobody") {
            val mailer = RecordingMailer()
            carpoolTestApplication(deps = CarpoolDeps(notifier = CarpoolNotifier(mailer))) {
                val driver = data.member(label = "n3-driver", email = "driver3-notice@example.org")
                val a = data.member(label = "n3-a", status = MemberStatus.FRIEND, email = "n3a@example.org")
                val b = data.member(label = "n3-b", status = MemberStatus.FRIEND, email = "n3b@example.org")
                val offer = data.posting(author = driver, seats = 3)
                call(member = a) { requestParticipation(offer.toString()) }
                val second = call(member = b) { requestParticipation(offer.toString()) }
                call(member = driver) { confirmParticipation(second.id) }
                mailer.sent.clear()
                call(member = driver) { deletePosting(offer.toString()) }
                mailer.sent.map { it.to to it.kind }.toSet() shouldBe
                    setOf("n3a@example.org" to CarpoolNoticeKind.POSTING_DELETED, "n3b@example.org" to CarpoolNoticeKind.POSTING_DELETED)
            }
        }

        test("the mail names nobody, carries no meeting note, is bilingual and goes through the registered BULK purpose") {
            val branding = MailBranding(fromDisplayName = "Lapis Test", replyTo = null, publicBaseUrl = "https://lapis.example.org")
            CarpoolNoticeKind.entries.filter { it != CarpoolNoticeKind.MESSAGE }.forEach { kind ->
                val mail =
                    MailTemplates.carpoolNotice(
                        kind = kind,
                        date = LocalDate(2026, 11, 1),
                        fromPlace = "Braunschweig\r\nBcc: evil@example.org",
                        toPlace = "Hannover",
                        branding = branding,
                    )
                mail.subject shouldNotContain "\n"
                mail.subject shouldNotContain "\r"
                mail.plainText shouldContain "2026-11-01"
                mail.plainText shouldContain "https://lapis.example.org/app#/carpool"
                mail.plainText shouldContain "names nobody"
                mail.html shouldContain "Hannover"
            }
            // V1.9.139: the message mail names no date, no place, no person and none of the text.
            val message =
                MailTemplates.carpoolNotice(
                    kind = CarpoolNoticeKind.MESSAGE,
                    date = LocalDate(2026, 11, 1),
                    fromPlace = "Braunschweig-GEHEIM",
                    toPlace = "Hannover-GEHEIM",
                    branding = branding,
                )
            listOf(message.subject, message.plainText, message.html).forEach { text ->
                text shouldNotContain "2026-11-01"
                text shouldNotContain "GEHEIM"
            }
            message.plainText shouldContain "https://lapis.example.org/app#/carpool"
            message.subject shouldNotContain "\n"
            MailPurpose.isKnown(CARPOOL_NOTICE_PURPOSE) shouldBe true
            MailPurpose.policyFor(CARPOOL_NOTICE_PURPOSE).lane shouldBe MailLane.BULK
            MailPurpose.policyFor(CARPOOL_NOTICE_PURPOSE).priority shouldBe 1
        }
    })

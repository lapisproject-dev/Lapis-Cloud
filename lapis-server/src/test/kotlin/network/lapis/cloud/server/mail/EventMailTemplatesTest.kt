package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration.Companion.seconds

private const val EVIL = "<b>x</b> & \"q\"\r\nBcc: evil@example.org"

private class EventMailCapturingTransport : MailTransport {
    val received = CompletableDeferred<List<String>>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        received.complete(listOf(to, subject, plainTextBody, htmlBody))
        return MailSendOutcome.Sent
    }
}

private val allMails: Map<String, (String) -> MailTemplates.RenderedMail> =
    mapOf(
        "waitlistPay" to {
            EventMailTemplates.waitlistPromotion(
                eventTitle = "Fest",
                recipientName = "Erika",
                payNow = true,
                paymentUrl = "https://x.example/pay?t=1",
                cancelUrl = "https://x.example/c?t=2",
                ticketUrl = null,
                offerHours = 24,
                language = it,
            )
        },
        "waitlistConfirmed" to {
            EventMailTemplates.waitlistPromotion(
                eventTitle = "Fest",
                recipientName = "Erika",
                payNow = false,
                paymentUrl = null,
                cancelUrl = null,
                ticketUrl = "https://x.example/ticket/1",
                offerHours = 24,
                language = it,
            )
        },
        "registrationConfirmed" to {
            EventMailTemplates.registrationReceived(
                kind = EventRegistrationMailKind.CONFIRMED,
                eventTitle = "Fest",
                recipientName = "Erika",
                ticketUrl = "https://x.example/ticket/1",
                payUrl = null,
                cancelUrl = "https://x.example/c?t=2",
                holdMinutes = 30,
                language = it,
            )
        },
        "registrationWaitlisted" to {
            EventMailTemplates.registrationReceived(
                kind = EventRegistrationMailKind.WAITLISTED,
                eventTitle = "Fest",
                recipientName = "Erika",
                ticketUrl = null,
                payUrl = null,
                cancelUrl = "https://x.example/c?t=2",
                holdMinutes = 30,
                language = it,
            )
        },
        "registrationPayment" to {
            EventMailTemplates.registrationReceived(
                kind = EventRegistrationMailKind.PAYMENT_PENDING,
                eventTitle = "Fest",
                recipientName = "Erika",
                ticketUrl = null,
                payUrl = "https://x.example/pay?t=1",
                cancelUrl = "https://x.example/c?t=2",
                holdMinutes = 30,
                language = it,
            )
        },
        "memberNotice" to
            {
                EventMailTemplates.memberNotice(
                    eventTitle = "Fest",
                    loginUrl = "https://x.example/login",
                    eventUrl = "https://x.example/ev",
                    language = it,
                )
            },
        "duplicateNotice" to { EventMailTemplates.duplicateNotice(eventTitle = "Fest", language = it) },
        "ticket" to
            {
                EventMailTemplates.ticketAfterPayment(
                    eventTitle = "Fest",
                    recipientName = "Erika",
                    ticketUrl = "https://x.example/ticket/1",
                    language = it,
                )
            },
        "reissue" to
            {
                EventMailTemplates.ticketReissued(
                    eventTitle = "Fest",
                    recipientName = "Erika",
                    ticketUrl = "https://x.example/ticket/1",
                    language = it,
                )
            },
        "cancelled" to { EventMailTemplates.eventCancelled(eventTitle = "Fest", recipientName = "Erika", reason = "Regen", language = it) },
        "messageNotice" to
            { EventMailTemplates.registrationMessageNotice(eventTitle = "Fest", overviewUrl = "https://x.example/ov", language = it) },
    )

class EventMailTemplatesTest :
    FunSpec({
        test("every event mail renders in all eight languages without an unresolved token and keeps its links") {
            allMails.forEach { (name, render) ->
                MembershipDecisionTexts.SUPPORTED.forEach { lang ->
                    val m = render(lang)
                    listOf(m.subject, m.plainText, m.html).forEach { text ->
                        Regex("\\{[a-zA-Z]+}").containsMatchIn(text) shouldBe false
                    }
                    m.subject shouldContain "Fest"
                    m.html shouldContain "Fest"
                    Regex("https://x\\.example/[^\\s\"]+").findAll(m.plainText).forEach { link ->
                        m.html shouldContain "href=\"${link.value}\""
                    }
                    check(name.isNotEmpty())
                }
            }
        }

        test("the German wording is the wording the mails always had") {
            val pay = allMails.getValue("waitlistPay")("de")
            pay.subject shouldBe "Platz frei geworden: Fest"
            pay.plainText shouldContain "Hallo Erika,"
            pay.plainText shouldContain "innerhalb von 24 Stunden ab, sonst rückt die nächste Person nach."
            pay.plainText shouldContain "Zahlung abschließen: https://x.example/pay?t=1"
            pay.plainText shouldContain "Anmeldung stornieren: https://x.example/c?t=2"
            allMails.getValue("registrationPayment")("de").plainText shouldContain "innerhalb von 30 Minuten ab"
            allMails.getValue("ticket")("de").subject shouldBe "Zahlung bestätigt: Fest"
            allMails.getValue("reissue")("de").subject shouldBe "Neues Ticket: Fest"
            allMails.getValue("cancelled")("de").subject shouldBe "Abgesagt: Fest"
            allMails.getValue("messageNotice")("de").subject shouldBe
                "Neue Anmeldung mit Nachricht: Fest"
        }

        test("a non-German language is really that language (French and Russian, with Russian plural)") {
            val fr = allMails.getValue("registrationPayment")("fr")
            fr.subject shouldBe "Veuillez finaliser votre paiement : Fest"
            fr.plainText shouldContain "30 minutes"
            fr.plainText shouldNotContain "Hallo"
            val ru =
                EventMailTemplates.waitlistPromotion(
                    eventTitle = "Fest",
                    recipientName = "Эрика",
                    payNow = true,
                    paymentUrl = "https://x.example/p",
                    cancelUrl = "https://x.example/c",
                    ticketUrl = null,
                    offerHours = 22,
                    language = "ru",
                )
            ru.plainText shouldContain "22 часа"
            ru.plainText shouldNotContain "Hallo"
        }

        test("hoursText and minutesText use the right plural form for pl and ru") {
            listOf(1L to "1 godzina", 2L to "2 godziny", 5L to "5 godzin", 12L to "12 godzin", 22L to "22 godziny").forEach { (n, e) ->
                hoursText(n = n, language = "pl") shouldBe e
            }
            listOf(
                1L to "1 минута",
                2L to "2 минуты",
                5L to "5 минут",
                11L to "11 минут",
                21L to "21 минута",
                22L to "22 минуты",
            ).forEach { (n, e) ->
                minutesText(n = n, language = "ru") shouldBe e
            }
            minutesText(n = 1, language = "en") shouldBe "1 minute"
            minutesText(n = 30, language = "de") shouldBe "30 Minuten"
        }

        test("title, name and reason are HTML-escaped, the subject carries no line break and is capped") {
            val m =
                EventMailTemplates.eventCancelled(
                    eventTitle = EVIL + "T".repeat(300),
                    recipientName = EVIL,
                    reason = EVIL,
                    language = "de",
                )
            m.html shouldNotContain "<b>x</b>"
            m.html shouldContain "&lt;b&gt;x&lt;/b&gt; &amp;"
            m.subject shouldNotContain "\r"
            m.subject shouldNotContain "\n"
            (m.subject.length <= EventMailTemplates.SUBJECT_MAX) shouldBe true
            val reg =
                EventMailTemplates.registrationReceived(
                    kind = EventRegistrationMailKind.CONFIRMED,
                    eventTitle = EVIL,
                    recipientName = EVIL,
                    ticketUrl = null,
                    payUrl = null,
                    cancelUrl = "https://x.example/c",
                    holdMinutes = 30,
                    language = "fr",
                )
            reg.html shouldNotContain "<b>x</b>"
            reg.subject shouldNotContain "\n"
        }

        test("the notices to an unknown visitor contain no visitor data, only the title") {
            val member =
                EventMailTemplates.memberNotice(
                    eventTitle = "Fest",
                    loginUrl = "https://x.example/login",
                    eventUrl = "https://x.example/ev",
                    language = "en",
                )
            member.plainText shouldContain "Hello,"
            val dup = EventMailTemplates.duplicateNotice(eventTitle = "Fest", language = "en")
            dup.plainText shouldContain "Hello,"
            dup.plainText shouldNotContain "http"
        }

        test("the registration message notice never contains message text and keeps the privacy sentence in the language") {
            val m = EventMailTemplates.registrationMessageNotice(eventTitle = "Fest", overviewUrl = "https://x.example/ov", language = "it")
            m.plainText shouldContain "protezione dei dati"
        }

        test("unknown language codes fall back to German") {
            allMails.getValue("ticket")("xx").subject shouldBe "Zahlung bestätigt: Fest"
        }

        test("MailLanguage.instance falls back to German when the source fails or is unknown") {
            MailLanguage.instance { throw IllegalStateException("db") } shouldBe "de"
            MailLanguage.instance { "zz" } shouldBe "de"
            MailLanguage.instance { "fr" } shouldBe "fr"
        }

        test("EventMailTexts: every text has eight non-blank languages, a consistent token set and no string template") {
            EventMailTexts.all.size shouldBe 32
            EventMailTexts.all.forEach { text ->
                text.byCode().keys shouldBe MembershipDecisionTexts.SUPPORTED
                text
                    .byCode()
                    .filterValues { it.isBlank() }
                    .keys
                    .shouldBeEmpty()
                val tokens = mailTokens(text.de)
                text.byCode().forEach { (code, value) -> mailTokens(value) shouldBe tokens.also { check(code.isNotEmpty()) } }
            }
            val source = File("src/main/kotlin/network/lapis/cloud/server/mail/EventMailTexts.kt").readText()
            source.contains("\${") shouldBe false
        }

        test("every EventMailTexts field is listed in all, and (apart from an international word) differs from German") {
            val fields =
                EventMailTexts.javaClass.declaredFields
                    .filter { it.type == MailText::class.java }
                    .map { f ->
                        f.isAccessible = true
                        f.get(EventMailTexts) as MailText
                    }
            fields.size shouldBe EventMailTexts.all.size
            val offenders =
                fields.flatMap { t ->
                    t
                        .byCode()
                        .filter { (c, v) -> c != "de" && v == t.de }
                        .keys
                        .map { "${t.de}/$it" }
                }
            // "Hallo {name}," is the same word in Dutch; nothing else may equal German.
            offenders.filterNot { it == "Hallo {name},/nl" || it == "Hallo,/nl" }.shouldBeEmpty()
        }

        test("no event mail purpose builds its mail from an inline German string (tripwire)") {
            val root = "src/main/kotlin/network/lapis/cloud/server/"
            listOf(
                "events/EventWaitlist.kt",
                "events/EventRegistrationSubmission.kt",
                "events/EventRegistrationMessageNotifier.kt",
                "rpc/EventService.kt",
                "routes/PspWebhookCommon.kt",
            ).forEach { rel ->
                val src = File(root + rel).readText()
                val offenders =
                    src.lines().filter { l ->
                        val t = l.trim()
                        !t.startsWith("*") &&
                            !t.startsWith("//") &&
                            !t.startsWith("/*") &&
                            Regex("\"[^\"]*(Hallo|Ihre Anmeldung|Ihr Ticket|Anmeldung stornieren|Abgesagt)[^\"]*\"").containsMatchIn(t)
                    }
                offenders.shouldBeEmpty()
                (src.contains("EventMailTemplates")) shouldBe true
            }
        }

        test("the membership decision mail uses the instance language only when the given one is empty or unknown") {
            fun capture(
                language: String,
                instance: () -> String,
            ): List<String> {
                val transport = EventMailCapturingTransport()
                val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                try {
                    val mailer =
                        SmtpMembershipDecisionMailer(
                            dispatcher = dispatcher,
                            branding =
                                MailBranding(
                                    fromDisplayName = "Verein",
                                    replyTo = null,
                                    publicBaseUrl = "https://cloud.example.org",
                                ),
                            mailLanguage = instance,
                        )
                    mailer.sendApproved(email = "e@example.org", displayName = "Erika", language = language) shouldBe true
                    return runBlocking { withTimeout(10.seconds) { transport.received.await() } }
                } finally {
                    dispatcher.shutdown()
                }
            }
            capture("", { "fr" })[1] shouldBe MembershipDecisionTexts.forLanguage("fr").subjectApproved
            capture("zz", { "fr" })[1] shouldBe MembershipDecisionTexts.forLanguage("fr").subjectApproved
            capture("en", { "fr" })[1] shouldBe MembershipDecisionTexts.forLanguage("en").subjectApproved
            capture("", { "klingon" })[1] shouldBe MembershipDecisionTexts.forLanguage("de").subjectApproved
            capture("", { throw IllegalStateException("x") })[1] shouldBe MembershipDecisionTexts.forLanguage("de").subjectApproved
        }
    })

package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.CarpoolNotice
import network.lapis.cloud.server.carpool.CarpoolNoticeKind
import network.lapis.cloud.server.carpool.SmtpCarpoolNoticeMailer
import network.lapis.cloud.server.webhook.WebhookDeactivationNotifier
import network.lapis.cloud.server.webhook.WebhookDeliveryQueue
import network.lapis.cloud.server.webhook.WebhookEndpointStore
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import network.lapis.cloud.shared.domain.WebhookDeliveryStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private class SubjectOnlyTransport : MailTransport {
    val subjects = Channel<String>(Channel.UNLIMITED)

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        subjects.send(subject)
        return MailSendOutcome.Sent
    }

    fun next(): String = runBlocking { withTimeout(10.seconds) { subjects.receive() } }
}

/** V1.9.334 -- each Smtp*Mailer writes its mail in the instance language, read at send time; a failing source means German and loses no mail. */
class SmtpMailersLanguageTest :
    FunSpec({
        val branding = MailBranding(fromDisplayName = "Partei", replyTo = null, publicBaseUrl = "https://pzb.example.org")
        val at = LocalDateTime(2026, 10, 8, 14, 30)

        /** One call per mailer: sends one mail with [language] and returns the subject the transport saw. */
        val senders: Map<String, (MailDispatcher, () -> String) -> Unit> =
            mapOf(
                "passwordReset" to
                    {
                        d,
                        l,
                        ->
                        SmtpPasswordResetMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).send(email = "a@example.org", rawToken = "T")
                    },
                "friendVerification" to
                    {
                        d,
                        l,
                        ->
                        SmtpFriendVerificationMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).send(email = "a@example.org", rawToken = "T")
                    },
                "adminPasswordReset" to
                    {
                        d,
                        l,
                        ->
                        SmtpAdminPasswordResetNotificationMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).send(email = "a@example.org", occurredAt = at)
                    },
                "keycloakLink" to { d, l ->
                    SmtpKeycloakLinkNotificationMailer(
                        dispatcher = d,
                        branding = branding,
                        mailLanguage = l,
                    ).send(email = "a@example.org", change = KeycloakLinkChange.LINKED, occurredAt = at)
                },
                "keycloakProvisioned" to
                    {
                        d,
                        l,
                        ->
                        SmtpKeycloakProvisioningMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).sendMemberProvisioned(email = "a@example.org", newMemberName = "Max", occurredAt = at)
                    },
                "keycloakSynced" to
                    {
                        d,
                        l,
                        ->
                        SmtpKeycloakProvisioningMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).sendEmailSyncedToOldAddress(email = "a@example.org", maskedNewEmail = "m***", occurredAt = at)
                    },
                "articleReview" to { d, l ->
                    SmtpArticleReviewNotificationMailer(
                        dispatcher = d,
                        branding = branding,
                        smtpConfigured = true,
                        brandTitle = "Partei",
                        mailLanguage = l,
                    ).send(
                        ArticleReviewNotification(
                            articleId = Uuid.random(),
                            authorEmail = "a@example.org",
                            title = "Titel",
                            outcome = ArticleReviewOutcome.APPROVED,
                            reason = null,
                            publicUrl = null,
                        ),
                    )
                },
                "emailChange" to { d, l ->
                    SmtpEmailChangeMailer(
                        dispatcher = d,
                        branding = branding,
                        mailLanguage = l,
                    ).sendConfirmToNewAddress(email = "a@example.org", rawToken = "T", kind = EmailChangeKind.PROPOSAL, effectiveAt = null)
                },
                "emailWarning" to { d, l ->
                    SmtpEmailChangeMailer(
                        dispatcher = d,
                        branding = branding,
                        mailLanguage = l,
                    ).sendWarningToOldAddress(
                        email = "a@example.org",
                        rawRevokeToken = "T",
                        kind = EmailChangeKind.PROPOSAL,
                        maskedNewEmail = "m***",
                        effectiveAt = null,
                    )
                },
                "peerRequest" to
                    {
                        d,
                        l,
                        ->
                        SmtpPeerNotificationMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).sendRequestForTarget(email = "a@example.org", rawVetoToken = "T", actorName = "Admin", notBefore = at)
                    },
                "peerApproval" to { d, l ->
                    SmtpPeerNotificationMailer(dispatcher = d, branding = branding, mailLanguage = l)
                        .sendApprovalNeeded(
                            email = "a@example.org",
                            actorName = "Admin",
                            targetName = "Ziel",
                            action = PrivilegedActionKind.DEMOTE,
                            expiresAt = at,
                        )
                },
                "peerExecuted" to { d, l ->
                    SmtpPeerNotificationMailer(
                        dispatcher = d,
                        branding = branding,
                        mailLanguage = l,
                    ).sendExecutedForTarget(
                        email = "a@example.org",
                        event = PeerExecutedEvent.ROLE_CHANGED,
                        actorName = "Admin",
                        occurredAt = at,
                    )
                },
                "peerNewAdmin" to
                    {
                        d,
                        l,
                        ->
                        SmtpPeerNotificationMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).sendNewAdministrator(email = "a@example.org", newAdminName = "Neu", actorName = "Admin", occurredAt = at)
                    },
                "finTs" to
                    {
                        d,
                        l,
                        ->
                        SmtpFinTsReauthNotificationMailer(
                            dispatcher = d,
                            branding = branding,
                            mailLanguage = l,
                        ).send(recipients = listOf("a@example.org"), accountLabel = "Konto", ibanMasked = "DE12", occurredAt = at)
                    },
                "encounter" to { d, l ->
                    SmtpEncounterEntryNoticeMailer(dispatcher = d, branding = branding, smtpConfigured = true, mailLanguage = l)
                        .send(
                            EncounterEntryNotice(
                                recipients = listOf("a@example.org"),
                                spaceTitle = "Raum",
                                kind = EncounterEntryNotice.Kind.FIRST_GUEST,
                                at = at,
                                windowEnd = null,
                                entries = 1,
                                presentCount = 2,
                            ),
                        )
                },
                "carpool" to { d, l ->
                    SmtpCarpoolNoticeMailer(dispatcher = d, branding = branding, smtpConfigured = true, mailLanguage = l)
                        .send(
                            to = "a@example.org",
                            notice =
                                CarpoolNotice(
                                    recipientId = Uuid.random(),
                                    kind = CarpoolNoticeKind.CONFIRMED,
                                    date = LocalDate(2026, 11, 1),
                                    fromPlace = "A",
                                    toPlace = "B",
                                ),
                        )
                },
            )

        fun run(
            language: () -> String,
            sender: (MailDispatcher, () -> String) -> Unit,
        ): String {
            val transport = SubjectOnlyTransport()
            val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            try {
                sender(dispatcher, language)
                return transport.next()
            } finally {
                dispatcher.shutdown()
            }
        }

        senders.forEach { (name, sender) ->
            test("$name -- the instance language is used (it), the German wording is not") {
                val german = run({ "de" }, sender)
                val italian = run({ "it" }, sender)
                (italian != german) shouldBe true
                italian.shouldNotContainSlashPair()
            }

            test("$name -- a failing language source falls back to German and the mail still goes out") {
                run({ error("db down") }, sender) shouldBe run({ "de" }, sender)
            }
        }

        test("the language is read at send time: a change between two mails shows") {
            var current = "de"
            val sender = senders.getValue("passwordReset")
            run({ current }, sender) shouldContain "Passwort"
            current = "pl"
            run({ current }, sender) shouldContain "Resetowanie hasła"
        }

        test("the webhook notice reads the language ONCE for any number of recipients") {
            val transport = SubjectOnlyTransport()
            val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            try {
                val reads = AtomicInteger()
                val notifier =
                    WebhookDeactivationNotifier(
                        mailDispatcher = dispatcher,
                        branding = branding,
                        mailLanguage = {
                            reads.incrementAndGet()
                            "fr"
                        },
                    )
                val endpoint =
                    WebhookEndpointStore.EndpointRow(
                        id = Uuid.random(),
                        apiKeyId = Uuid.random(),
                        apiKeyLabel = "key",
                        url = "https://hooks.example.org/x",
                        secretSealed = "s",
                        secretPrefix = "p",
                        active = false,
                        createdAt = at,
                        createdByMemberId = Uuid.random(),
                        updatedAt = null,
                        updatedByMemberId = null,
                        deactivatedAt = at,
                        deactivatedByMemberId = null,
                        deactivationReason = WebhookDeactivationReason.entries.first(),
                    )
                val delivery =
                    WebhookDeliveryQueue.DeliveryRow(
                        id = Uuid.random(),
                        endpointId = endpoint.id,
                        eventId = Uuid.random(),
                        eventType = WebhookEventType.entries.first(),
                        entityId = Uuid.random(),
                        occurredAt = at,
                        payload = "{}",
                        status = WebhookDeliveryStatus.entries.first(),
                        attemptCount = 3,
                        nextAttemptAt = null,
                        lastAttemptAt = null,
                        lastHttpStatus = 500,
                        lastError = null,
                        createdAt = at,
                        deliveredAt = null,
                    )
                notifier.notify(
                    endpoint = endpoint,
                    delivery = delivery,
                    httpStatus = 500,
                    recipients = listOf("a@example.org", "b@example.org", "c@example.org"),
                )
                repeat(3) { transport.next() shouldBe "Webhook désactivé – Partei" }
                reads.get() shouldBe 1
            } finally {
                dispatcher.shutdown()
            }
        }
    })

private fun String.shouldNotContainSlashPair() {
    (contains(" / ")) shouldBe false
}

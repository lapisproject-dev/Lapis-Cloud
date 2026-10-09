package network.lapis.cloud.server.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties

/**
 * The real [MailTransport] -- a thin, test-seamed wrapper around Jakarta Mail's blocking
 * `Transport.send`. See [SmtpConfig] KDoc for the credential/TLS-policy source and
 * [MailDispatcher] KDoc for why this class is never called synchronously from a route handler.
 *
 * **Never `Session.getDefaultInstance`** (stolperfalle 1) -- that caches a JVM-global `Session`;
 * whichever caller constructs one first would silently "win" for the entire process. Always
 * [Session.getInstance] with an explicit [Properties] + [Authenticator] built fresh from [config].
 */
class JakartaMailTransport(
    private val config: SmtpConfig,
    /**
     * Test seam -- defaults to the real [Transport.send]. Unit tests inject a fake here and NEVER
     * open a real network connection, mirroring
     * `network.lapis.cloud.server.postal.LetterxpressPostalMailProviderTest`'s own
     * fake-responder discipline for outbound third-party calls.
     */
    private val sendMessage: (MimeMessage) -> Unit = { Transport.send(it) },
    private val sessionFactory: (SmtpConfig) -> Session = ::createSession,
) : MailTransport {
    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        // Header-injection guard BEFORE any connection attempt (stolperfalle-adjacent: this is the
        // mail-specific twin of the `\r`/`\n` check SmtpConfig.load already applies to the FROM
        // address) -- a recipient or subject containing a line break could otherwise inject
        // additional headers (extra Bcc:, altered To:) into the outgoing message.
        if (containsLineBreak(to) || containsLineBreak(subject)) {
            return MailSendOutcome.Failed(sanitizedErrorMessage = "recipient/subject contains a line break")
        }

        return withContext(Dispatchers.IO) {
            try {
                val session = sessionFactory(config)
                val message =
                    buildMessage(
                        session = session,
                        to = to,
                        subject = subject,
                        plainTextBody = plainTextBody,
                        htmlBody = htmlBody,
                    )
                // Populates the real MIME headers (Content-Type, Date, ...) from the parts/content
                // set above -- MimeMessage/MimeBodyPart.getContentType() reads ONLY the header, not
                // the underlying DataHandler, and the header stays unset until saveChanges() runs.
                // jakarta.mail.Transport.send(Message) also calls this internally, so calling it
                // explicitly here is idempotent for a real send -- but it makes [sendMessage]'s test
                // seam receive a fully-prepared message (correct Content-Type et al.) even though
                // tests replace the real Transport.send and therefore never trigger that internal call.
                message.saveChanges()
                sendMessage(message)
                MailSendOutcome.Sent
            } catch (e: Exception) {
                // Deliberately only the exception's simple class name -- see MailSendOutcome.Failed
                // KDoc "Error handling". jakarta.mail.AuthenticationFailedException in particular
                // carries the SMTP server's raw response line, which can echo back the configured
                // username; that must never reach a log, a DTO, or an exception rethrown further up.
                classify(e)
            }
        }
    }

    private fun buildMessage(
        session: Session,
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(config.fromAddress, config.fromDisplayName, "UTF-8"))
        // Reply-To is transport configuration, not a message property -- no MailTransport.send
        // interface change (V1.2.3 Design-Review). `true` = the same strict InternetAddress parse
        // SmtpConfig.load already validated this value with, so this can never throw here.
        config.replyTo?.let { message.setReplyTo(arrayOf(InternetAddress(it, true))) }
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
        message.setSubject(subject, "UTF-8")

        // multipart/alternative, text/plain FIRST then text/html LAST (stolperfalle 7) -- mail
        // clients render the LAST part they understand, so the richer HTML part must come last.
        val plainPart = MimeBodyPart()
        plainPart.setText(plainTextBody, "UTF-8")
        val htmlPart = MimeBodyPart()
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8")

        val multipart = MimeMultipart("alternative")
        multipart.addBodyPart(plainPart)
        multipart.addBodyPart(htmlPart)
        message.setContent(multipart)
        return message
    }

    private fun containsLineBreak(value: String): Boolean = value.contains('\r') || value.contains('\n')

    companion object {
        private val SMTP_CODE_PREFIX = Regex("""^\s*([245]\d\d)[ -]""")
        private const val MAX_CHAIN = 16

        /**
         * Welle V1.9.81 -- classifies a send failure into [MailSendOutcome.Failed] (see its KDoc). Walks the cause chain AND
         * `MessagingException.nextException` (a rejected recipient arrives as a `SendFailedException` whose next exception carries
         * the code). Angus is `runtimeOnly` (licence decision 2026-08-21), so the numeric reply code is read reflectively from
         * `getReturnCode()`; only if no element has one, a leading `NNN ` of a message is used -- **solely to extract the number**:
         * the message itself is never stored, logged or returned (it can echo addresses).
         */
        internal fun classify(e: Throwable): MailSendOutcome.Failed {
            val chain = throwableChain(e)
            val simpleNames = chain.map { it::class.simpleName.orEmpty() }
            val fallbackName = e::class.simpleName ?: "unknown error"
            val message = "SMTP send failed ($fallbackName)"
            if (chain.any { it is AuthenticationFailedException }) {
                return MailSendOutcome.Failed(sanitizedErrorMessage = message, kind = MailFailureKind.PERMANENT, errorClass = "AUTH")
            }
            val code = chain.firstNotNullOfOrNull { reflectReturnCode(it) } ?: chain.firstNotNullOfOrNull { codeFromMessage(it.message) }
            if (code != null && code in 400..599) {
                val errorClass = MailSendOutcome.Failed.sanitizeErrorClass("SMTP_$code")
                return if (code < 500) {
                    MailSendOutcome.Failed(
                        sanitizedErrorMessage = message,
                        kind = MailFailureKind.TRANSIENT,
                        smtpReplyCode = code,
                        errorClass = errorClass,
                    )
                } else {
                    MailSendOutcome.Failed(
                        sanitizedErrorMessage = message,
                        kind = MailFailureKind.PERMANENT,
                        smtpReplyCode = code,
                        errorClass = errorClass,
                    )
                }
            }
            val connectPhase =
                "MailConnectException" in simpleNames ||
                    chain.any { it is ConnectException || it is UnknownHostException || it is NoRouteToHostException }
            if (connectPhase) {
                // Nothing was sent: unambiguous.
                return MailSendOutcome.Failed(sanitizedErrorMessage = message, kind = MailFailureKind.TRANSIENT, errorClass = "CONNECT")
            }
            if (chain.any { it is SocketTimeoutException }) {
                return MailSendOutcome.Failed(
                    sanitizedErrorMessage = message,
                    kind = MailFailureKind.TRANSIENT,
                    deliveryUncertain = true,
                    errorClass = "TIMEOUT",
                )
            }
            return MailSendOutcome.Failed(sanitizedErrorMessage = message, kind = MailFailureKind.PERMANENT, errorClass = "UNKNOWN")
        }

        private fun throwableChain(e: Throwable): List<Throwable> {
            val seen = LinkedHashSet<Throwable>()
            val queue = ArrayDeque<Throwable>()
            queue.add(e)
            while (queue.isNotEmpty() && seen.size < MAX_CHAIN) {
                val t = queue.removeFirst()
                if (!seen.add(t)) continue
                t.cause?.let(queue::add)
                if (t is MessagingException) t.nextException?.let(queue::add)
            }
            return seen.toList()
        }

        private fun reflectReturnCode(t: Throwable): Int? =
            runCatching {
                val method = t.javaClass.getMethod("getReturnCode")
                if (method.returnType == Int::class.javaPrimitiveType) method.invoke(t) as Int else null
            }.getOrNull()?.takeIf { it in 100..599 }

        private fun codeFromMessage(message: String?): Int? =
            message
                ?.let { SMTP_CODE_PREFIX.find(it) }
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /**
         * Builds a fresh [Session] from [config] every call -- see class KDoc "Never
         * Session.getDefaultInstance". All Jakarta Mail properties are set as [String] values
         * (stolperfalle 2: `Properties.getProperty` silently ignores a non-`String` value, e.g. an
         * `Int` timeout, resulting in NO timeout at all and hanging threads).
         */
        fun createSession(config: SmtpConfig): Session {
            val props = Properties()
            props["mail.smtp.host"] = config.host
            props["mail.smtp.port"] = config.port.toString()
            props["mail.smtp.auth"] = "true"
            props["mail.smtp.from"] = config.fromAddress
            props["mail.smtp.connectiontimeout"] = config.connectTimeoutMs.toString()
            props["mail.smtp.timeout"] = config.readTimeoutMs.toString()
            props["mail.smtp.writetimeout"] = config.readTimeoutMs.toString()
            // Explicit, not relying on the library default (stolperfalle 4) -- a silent default
            // flip in a future Angus minor version would otherwise be a MITM hole.
            props["mail.smtp.ssl.checkserveridentity"] = "true"
            props["mail.smtp.ssl.protocols"] = "TLSv1.2 TLSv1.3"

            when (config.transportSecurity) {
                SmtpTransportSecurity.IMPLICIT_TLS -> {
                    // Port 465 = implicit TLS, NOT STARTTLS (stolperfalle 3) -- setting
                    // starttls.enable here instead produces a handshake failure that looks like an
                    // auth problem.
                    props["mail.smtp.ssl.enable"] = "true"
                    props["mail.smtp.starttls.enable"] = "false"
                }
                SmtpTransportSecurity.STARTTLS_REQUIRED -> {
                    props["mail.smtp.ssl.enable"] = "false"
                    props["mail.smtp.starttls.enable"] = "true"
                    // required=true, not merely opportunistic (stolperfalle 5) -- otherwise an
                    // active attacker can strip "250-STARTTLS" from the EHLO response and Jakarta
                    // Mail sends credentials in the clear.
                    props["mail.smtp.starttls.required"] = "true"
                }
            }

            val authenticator =
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication =
                        PasswordAuthentication(config.username, config.password)
                }
            return Session.getInstance(props, authenticator)
        }
    }
}

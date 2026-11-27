package network.lapis.cloud.server.mail

import network.lapis.cloud.server.mail.MailTemplates.RenderedMail

/** Which of the three registration mails is sent. */
internal enum class EventRegistrationMailKind { CONFIRMED, WAITLISTED, PAYMENT_PENDING }

/**
 * V1.9.335 -- layout of the event mails: single-language ([EventMailTexts]), without footer (like the mails always were), plain text and HTML.
 *
 * Security: every variable that can come from a guest form or from the board (event title, recipient name, cancellation reason) is
 * [htmlEscape]d in the HTML part; the `Subject:` header gets the title through [MailTemplates.sanitizeSubjectFragment] (no line breaks,
 * no control characters) and is capped at [SUBJECT_MAX]. Links are built by this server (slug + generated token) and only HTML-escaped as an
 * attribute value. The subject is built HERE (not in the callers) so no event mail ever concatenates a raw title into a header.
 */
internal object EventMailTemplates {
    const val SUBJECT_MAX = 150

    private class Link(
        val label: String,
        val url: String,
    )

    private fun render(
        language: String,
        subject: MailText,
        body: MailText,
        title: String,
        name: String? = null,
        extras: Map<String, String> = emptyMap(),
        links: List<Link> = emptyList(),
        closing: MailText? = null,
    ): RenderedMail {
        val plainValues = extras + mapOf("title" to title, "name" to (name ?: ""))
        val htmlValues = plainValues.mapValues { htmlEscape(it.value) }
        val greetingText = if (name != null) EventMailTexts.greeting else EventMailTexts.greetingAnonymous
        val subjectLine =
            fillMail(template = subject.forLanguage(language), values = mapOf("title" to MailTemplates.sanitizeSubjectFragment(title)))
                .take(SUBJECT_MAX)
        val plain =
            buildString {
                append(fillMail(template = greetingText.forLanguage(language), values = plainValues)).append("\n\n")
                append(fillMail(template = body.forLanguage(language), values = plainValues))
                links.forEach { append("\n\n").append(it.label).append(": ").append(it.url) }
                if (closing != null) append("\n\n").append(closing.forLanguage(language))
                append("\n")
            }
        val html =
            buildString {
                append("<p>").append(fillMail(template = greetingText.forLanguage(language), values = htmlValues)).append("</p>")
                append(
                    "<p>",
                ).append(fillMail(template = body.forLanguage(language), values = htmlValues).replace("\n", "<br>")).append("</p>")
                links.forEach {
                    append(
                        "<p><a href=\"",
                    ).append(htmlEscape(it.url)).append("\">").append(htmlEscape(it.label)).append("</a></p>")
                }
                if (closing != null) append("<p>").append(htmlEscape(closing.forLanguage(language))).append("</p>")
            }
        return RenderedMail(subject = subjectLine, plainText = plain, html = html)
    }

    fun waitlistPromotion(
        eventTitle: String,
        recipientName: String,
        payNow: Boolean,
        paymentUrl: String?,
        cancelUrl: String?,
        ticketUrl: String?,
        offerHours: Long,
        language: String,
    ): RenderedMail =
        if (payNow) {
            render(
                language = language,
                subject = EventMailTexts.waitlistSubject,
                body = EventMailTexts.waitlistBodyPay,
                title = eventTitle,
                name = recipientName,
                extras = mapOf("hours" to hoursText(n = offerHours, language = language)),
                links =
                    listOf(
                        Link(label = EventMailTexts.completePaymentLabel.forLanguage(language), url = requireNotNull(paymentUrl)),
                        Link(label = EventMailTexts.cancelRegistrationLabel.forLanguage(language), url = requireNotNull(cancelUrl)),
                    ),
            )
        } else {
            render(
                language = language,
                subject = EventMailTexts.waitlistSubject,
                body = EventMailTexts.waitlistBodyConfirmed,
                title = eventTitle,
                name = recipientName,
                links = listOf(Link(label = EventMailTexts.ticketLabel.forLanguage(language), url = requireNotNull(ticketUrl))),
            )
        }

    fun registrationReceived(
        kind: EventRegistrationMailKind,
        eventTitle: String,
        recipientName: String,
        ticketUrl: String?,
        payUrl: String?,
        cancelUrl: String,
        holdMinutes: Long,
        language: String,
    ): RenderedMail {
        val subject =
            when (kind) {
                EventRegistrationMailKind.CONFIRMED -> EventMailTexts.registrationSubjectConfirmed
                EventRegistrationMailKind.WAITLISTED -> EventMailTexts.registrationSubjectWaitlisted
                EventRegistrationMailKind.PAYMENT_PENDING -> EventMailTexts.registrationSubjectPayment
            }
        val body =
            when (kind) {
                EventRegistrationMailKind.CONFIRMED -> EventMailTexts.registrationBodyConfirmed
                EventRegistrationMailKind.WAITLISTED -> EventMailTexts.registrationBodyWaitlisted
                EventRegistrationMailKind.PAYMENT_PENDING -> EventMailTexts.registrationBodyPayment
            }
        val links = mutableListOf<Link>()
        if (ticketUrl != null) links += Link(label = EventMailTexts.ticketLabel.forLanguage(language), url = ticketUrl)
        if (payUrl != null) links += Link(label = EventMailTexts.payNowLabel.forLanguage(language), url = payUrl)
        links += Link(label = EventMailTexts.cancelRegistrationLabel.forLanguage(language), url = cancelUrl)
        return render(
            language = language,
            subject = subject,
            body = body,
            title = eventTitle,
            name = recipientName,
            extras = mapOf("minutes" to minutesText(n = holdMinutes, language = language)),
            links = links,
        )
    }

    /** No text of the visitor goes in (no name, no message): anyone can type any address. */
    fun memberNotice(
        eventTitle: String,
        loginUrl: String,
        eventUrl: String,
        language: String,
    ): RenderedMail =
        render(
            language = language,
            subject = EventMailTexts.memberNoticeSubject,
            body = EventMailTexts.memberNoticeBody,
            title = eventTitle,
            links =
                listOf(
                    Link(label = EventMailTexts.memberNoticeLoginLabel.forLanguage(language), url = loginUrl),
                    Link(label = EventMailTexts.memberNoticeEventLabel.forLanguage(language), url = eventUrl),
                ),
            closing = EventMailTexts.memberNoticeClosing,
        )

    fun duplicateNotice(
        eventTitle: String,
        language: String,
    ): RenderedMail =
        render(language = language, subject = EventMailTexts.duplicateSubject, body = EventMailTexts.duplicateBody, title = eventTitle)

    fun ticketAfterPayment(
        eventTitle: String,
        recipientName: String,
        ticketUrl: String,
        language: String,
    ): RenderedMail =
        render(
            language = language,
            subject = EventMailTexts.ticketSubject,
            body = EventMailTexts.ticketBody,
            title = eventTitle,
            name = recipientName,
            links = listOf(Link(label = EventMailTexts.ticketLabel.forLanguage(language), url = ticketUrl)),
        )

    fun ticketReissued(
        eventTitle: String,
        recipientName: String,
        ticketUrl: String,
        language: String,
    ): RenderedMail =
        render(
            language = language,
            subject = EventMailTexts.reissueSubject,
            body = EventMailTexts.reissueBody,
            title = eventTitle,
            name = recipientName,
            links = listOf(Link(label = EventMailTexts.ticketLabel.forLanguage(language), url = ticketUrl)),
        )

    /** [reason] is free text of the board: never translated, only escaped in the HTML part. */
    fun eventCancelled(
        eventTitle: String,
        recipientName: String,
        reason: String,
        language: String,
    ): RenderedMail =
        render(
            language = language,
            subject = EventMailTexts.cancelledSubject,
            body = EventMailTexts.cancelledBody,
            title = eventTitle,
            name = recipientName,
            extras = mapOf("reason" to reason),
        )

    /** The mail NEVER contains the message text (data minimisation). */
    fun registrationMessageNotice(
        eventTitle: String,
        overviewUrl: String,
        language: String,
    ): RenderedMail =
        render(
            language = language,
            subject = EventMailTexts.messageNoticeSubject,
            body = EventMailTexts.messageNoticeBody,
            title = eventTitle,
            links = listOf(Link(label = EventMailTexts.messageNoticeLinkLabel.forLanguage(language), url = overviewUrl)),
            closing = EventMailTexts.messageNoticeClosing,
        )
}

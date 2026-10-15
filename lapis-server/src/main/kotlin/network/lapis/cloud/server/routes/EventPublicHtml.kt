package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.html.ButtonType
import kotlinx.html.DD
import kotlinx.html.DL
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.emailInput
import kotlinx.html.figure
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.stream.createHTML
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.title
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventText
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventTicketCode

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- the server-rendered, unauthenticated public surface (see
 * `registerEventPublicRoutes` KDoc). Same rendering discipline as [EmbedDonationHtml]/[EmbedHtml]:
 * `kotlinx.html`'s ordinary, HTML-escaping text-node/attribute APIs only, no `unsafe { }`/raw-string
 * concatenation anywhere in this file, same pattern `EmbedDonationHtml`/`EmbedHtml` already
 * establish. Deliberately NO `<script>` tag anywhere -- a four-field form needs no JavaScript
 * (Duarte).
 *
 * **Occupancy is always BINARY** ("Plätze frei" / "Warteliste"), never an exact remaining count --
 * no digit of a remaining-seats count may ever appear in the rendered HTML.
 *
 * V1.9.128 "Veranstaltungsseite: Kopfbereich, Layout, ?embed=1": the page now has TWO presentations ([EventPageMode]). The standard
 * mode carries the full [PublicChrome] header (brand, icon navigation, language switcher), a wide layout and the shared footer
 * (Impressum/Datenschutz as `$baseUrl/impressum`/`/datenschutz`). `?embed=1` keeps the slim, header-less `embed-page` shape for an
 * `<iframe>` on a partner website, with site-relative footer links that leave the frame (`target="_top"`). Every family-internal link
 * and redirect goes through [EventPageContext.href] so the mode (and the language) survive the whole registration flow. The page BODY
 * is German in every UI language (the event texts are); only the chrome is translated.
 *
 * V1.9.140: the detail page reads facts -> cover image -> description -> status callout -> registration card (the form used to sit above
 * the description). The card carries `id="anmeldung"`; the "Plätze" row links to it ("Zur Anmeldung") only when registration is possible
 * AND the description is not blank (otherwise there is nothing to skip). Token and follow-up pages and every error page pass
 * `noIndex = true` to [skeleton] (`<meta name="robots" content="noindex, nofollow">`, next to the `X-Robots-Tag` header the route sets);
 * the detail page (200) never does.
 */
internal object EventPublicHtml {
    /** One event's publicly-safe view -- assembled by the route handler OUTSIDE any transaction (Slowloris rule, see `SocialPublicRoutes` KDoc). */
    data class View(
        val title: String,
        val slug: String,
        val description: String,
        val locationText: String?,
        val onlineUrl: String?,
        val startsAt: LocalDateTime,
        val endsAt: LocalDateTime,
        val feeLabel: String,
        val full: Boolean,
        val registrationOpen: Boolean,
        /** Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- additive. Absolute URL, non-null iff the event has a cover image set. See `EventCoverPolicy.coverImageUrl`. */
        val coverImageUrl: String? = null,
        /** V1.9.82 -- alt text of the cover image; falls back to the title when null. */
        val coverImageAlt: String? = null,
        /** V1.9.82 -- `true` once the event is over (`endsAt <= wallNow`): the page then says so and offers no registration form. */
        val hasEnded: Boolean = false,
        /**
         * V1.9.113 -- the public link to the online participation page of this event (`<base>/#/event-join/<slug>`), non-null only for a
         * Lapis online kind of a public, not yet ended event. It is the PAGE, never a room, a token or an external meeting link; the
         * window and the authorization are decided there.
         */
        val onlineJoinUrl: String? = null,
    )

    fun eventPage(
        ctx: EventPageContext,
        view: View,
    ): String =
        skeleton(
            ctx = ctx,
            heading = view.title,
            currentPath = "/veranstaltung/${view.slug}",
            ogImage = view.coverImageUrl,
            canonicalPath = "/veranstaltung/${view.slug}",
        ) {
            val canRegister = !view.hasEnded && view.registrationOpen
            // The jump link only makes sense when the description pushes the form down (V1.9.140).
            val showJump = canRegister && view.description.isNotBlank()
            factList {
                fact(label = "Wann") { +formatEventRange(start = view.startsAt, end = view.endsAt) }
                if (view.locationText != null || view.onlineUrl != null || view.onlineJoinUrl != null) {
                    fact(label = "Wo") {
                        whereLines(
                            locationText = view.locationText,
                            onlineUrl = view.onlineUrl,
                            onlineJoinUrl = view.onlineJoinUrl,
                            joinHint = true,
                        )
                    }
                }
                fact(label = "Teilnahme") { +view.feeLabel }
                // Occupancy stays BINARY -- never a count (see class KDoc).
                if (!view.hasEnded) {
                    fact(label = "Plätze") {
                        +(if (view.full) "Ausgebucht – Warteliste möglich" else "Plätze frei")
                        if (showJump) {
                            +" · "
                            a(href = "#anmeldung") { +"Zur Anmeldung" }
                        }
                    }
                }
            }
            if (view.coverImageUrl != null) {
                img(src = view.coverImageUrl, alt = view.coverImageAlt ?: view.title, classes = "event-cover")
            }
            div(classes = "event-description") {
                // Plain text only: one <p> per paragraph (empty line), single line breaks become <br>. Text nodes are escaped by kotlinx.html.
                for (paragraph in EventText.paragraphs(EventText.normalizeMultiline(view.description))) {
                    p {
                        paragraph.forEachIndexed { index, line ->
                            if (index > 0) br()
                            +line
                        }
                    }
                }
            }
            when {
                view.hasEnded -> callout(text = "Diese Veranstaltung hat stattgefunden.")
                !view.registrationOpen -> callout(text = "Die Anmeldung ist für diese Veranstaltung derzeit nicht möglich.")
                view.full ->
                    callout(
                        text = "Diese Veranstaltung ist ausgebucht. Sie können sich auf die Warteliste setzen.",
                        waitlist = true,
                    )
            }
            if (canRegister) registrationCard(ctx = ctx, slug = view.slug, waitlist = view.full)
        }

    private fun FlowContent.factList(block: DL.() -> Unit) {
        dl(classes = "event-facts") { block() }
    }

    private fun DL.fact(
        label: String,
        value: DD.() -> Unit,
    ) {
        dt { +label }
        dd { value() }
    }

    /** Location, the optional public online link (plain text) and the optional link to the Lapis online participation page. */
    private fun DD.whereLines(
        locationText: String?,
        onlineUrl: String?,
        onlineJoinUrl: String?,
        joinHint: Boolean,
    ) {
        var first = true

        fun separator() {
            if (!first) br()
            first = false
        }
        if (locationText != null) {
            separator()
            +locationText
        }
        // Only set by the route when the administrator ticked "show the online link publicly" AND it is an https link (F1).
        if (onlineUrl != null) {
            separator()
            +"Online: $onlineUrl"
        }
        if (onlineJoinUrl != null) {
            separator()
            a(href = onlineJoinUrl) { +"Zur Online-Teilnahme" }
            if (joinHint) +" (der Zugang öffnet kurz vor Beginn)"
        }
    }

    private fun FlowContent.callout(
        text: String,
        waitlist: Boolean = false,
    ) {
        div(classes = if (waitlist) "event-callout event-callout--waitlist" else "event-callout") {
            attributes["role"] = "status"
            +text
        }
    }

    private fun FlowContent.registrationCard(
        ctx: EventPageContext,
        slug: String,
        waitlist: Boolean,
    ) {
        section(classes = "event-card") {
            id = "anmeldung"
            attributes["aria-labelledby"] = "anmeldung-titel"
            h2 {
                id = "anmeldung-titel"
                +(if (waitlist) "Auf die Warteliste" else "Anmeldung")
            }
            form(action = ctx.href("/veranstaltung/$slug/anmeldung"), method = FormMethod.post, classes = "event-form") {
                honeypotField()
                div(classes = "event-field") {
                    label {
                        htmlFor = "guestName"
                        +"Name"
                    }
                    textInput(name = "guestName") {
                        id = "guestName"
                        required = true
                        attributes["autocomplete"] = "name"
                        attributes["maxlength"] = EventPolicy.MAX_GUEST_NAME_LENGTH.toString()
                    }
                }
                div(classes = "event-field") {
                    label {
                        htmlFor = "guestEmail"
                        +"E-Mail-Adresse"
                    }
                    emailInput(name = "guestEmail") {
                        id = "guestEmail"
                        required = true
                        attributes["autocomplete"] = "email"
                        attributes["maxlength"] = EventPolicy.MAX_GUEST_EMAIL_LENGTH.toString()
                    }
                }
                button(type = ButtonType.submit, classes = "event-submit") {
                    +(if (waitlist) "Auf die Warteliste setzen" else "Verbindlich anmelden")
                }
                p(classes = "event-hint") { +"Sie erhalten eine Bestätigung per E-Mail." }
            }
        }
    }

    private fun FORM.honeypotField() {
        // Klassischer No-JS-Bot-Schutz, siehe registerEventPublicRoutes KDoc -- ein echter Mensch
        // sieht/füllt dieses Feld nie aus. Versteckt per CSS-Klasse `.hp` (NICHT per Inline-Style: die CSP
        // `style-src 'self'` blockiert style-Attribute, das Feld wäre sonst sichtbar -- V1.9.128).
        div(classes = "hp") {
            attributes["aria-hidden"] = "true"
            label {
                htmlFor = "kommentar"
                +"Bitte freilassen"
            }
            textArea {
                id = "kommentar"
                name = "kommentar"
                attributes["tabindex"] = "-1"
                attributes["autocomplete"] = "off"
            }
        }
    }

    /** Validation failure of the registration form (400) -- never reflects the submitted values. */
    fun invalidInputPage(
        ctx: EventPageContext,
        slug: String,
    ): String =
        skeleton(ctx = ctx, heading = "Angaben unvollständig", currentPath = "/veranstaltung/$slug", noIndex = true) {
            callout(text = "Bitte geben Sie Ihren Namen und eine gültige E-Mail-Adresse an.")
            p { a(href = ctx.href("/veranstaltung/$slug")) { +"Zurück zur Anmeldung" } }
        }

    fun thanksPage(
        ctx: EventPageContext,
        slug: String,
        registrationId: String?,
    ): String =
        skeleton(ctx = ctx, heading = "Anmeldung bestätigt", currentPath = "/veranstaltung/$slug/danke", noIndex = true) {
            callout(text = "Vielen Dank für Ihre Anmeldung. Eine Bestätigung wurde per E-Mail versendet.")
            if (registrationId != null) p(classes = "event-hint") { +"Vorgang: $registrationId" }
        }

    fun waitlistPage(
        ctx: EventPageContext,
        slug: String,
    ): String =
        skeleton(ctx = ctx, heading = "Auf der Warteliste", currentPath = "/veranstaltung/$slug/warteliste", noIndex = true) {
            callout(
                text =
                    "Diese Veranstaltung ist ausgebucht. Sie stehen auf der Warteliste und werden " +
                        "benachrichtigt, sobald ein Platz frei wird.",
                waitlist = true,
            )
        }

    fun cancelledPage(
        ctx: EventPageContext,
        slug: String,
    ): String =
        skeleton(ctx = ctx, heading = "Vorgang abgebrochen", currentPath = "/veranstaltung/$slug/abgebrochen", noIndex = true) {
            callout(text = "Der Vorgang wurde abgebrochen. Es wurden keine Daten gespeichert.")
        }

    fun notFoundPage(ctx: EventPageContext): String =
        skeleton(ctx = ctx, heading = "Nicht gefunden", currentPath = "/veranstaltungen", noIndex = true) {
            callout(text = "Diese Seite existiert nicht oder ist nicht öffentlich zugänglich.")
        }

    fun tooManyRequestsPage(ctx: EventPageContext): String =
        skeleton(ctx = ctx, heading = "Zu viele Anfragen", currentPath = "/veranstaltungen", noIndex = true) {
            callout(text = "Bitte versuchen Sie es später erneut.")
        }

    fun serverErrorPage(ctx: EventPageContext): String =
        skeleton(ctx = ctx, heading = "Fehler", currentPath = "/veranstaltungen", noIndex = true) {
            callout(text = "Es ist ein Fehler aufgetreten. Bitte versuchen Sie es später erneut.")
        }

    fun malformedRequestPage(ctx: EventPageContext): String =
        skeleton(ctx = ctx, heading = "Ungültige Anfrage", currentPath = "/veranstaltungen", noIndex = true) {
            callout(text = "Die Anfrage konnte nicht verarbeitet werden.")
        }

    /**
     * Token pages (storno, zahlung, ticket) pass the DETAIL page as `currentPath` -- a bearer token or ticket code must never end up in a
     * link of the chrome (language switcher).
     */
    fun cancelConfirmPage(
        ctx: EventPageContext,
        slug: String,
        token: String,
    ): String =
        skeleton(ctx = ctx, heading = "Anmeldung stornieren", currentPath = "/veranstaltung/$slug", noIndex = true) {
            section(classes = "event-card") {
                p { +"Möchten Sie Ihre Anmeldung wirklich stornieren?" }
                form(action = ctx.href("/veranstaltung/$slug/storno"), method = FormMethod.post, classes = "event-form") {
                    hiddenInput(name = "token") { value = token }
                    button(type = ButtonType.submit, classes = "event-submit event-submit--danger") { +"Ja, stornieren" }
                }
            }
        }

    fun cancelledSuccessPage(
        ctx: EventPageContext,
        slug: String,
    ): String =
        skeleton(ctx = ctx, heading = "Anmeldung storniert", currentPath = "/veranstaltung/$slug", noIndex = true) {
            callout(text = "Ihre Anmeldung wurde storniert.")
        }

    /** Counterpart of [cancelConfirmPage] for `POST /veranstaltung/{slug}/zahlung` (Review MAJOR fix) -- same GET-renders/POST-mutates split, see `registerEventPublicRoutes` KDoc. */
    fun paymentConfirmPage(
        ctx: EventPageContext,
        slug: String,
        token: String,
    ): String =
        skeleton(ctx = ctx, heading = "Zahlung abschließen", currentPath = "/veranstaltung/$slug", noIndex = true) {
            section(classes = "event-card") {
                p { +"Bitte schließen Sie die Zahlung für Ihren Platz ab." }
                form(action = ctx.href("/veranstaltung/$slug/zahlung"), method = FormMethod.post, classes = "event-form") {
                    hiddenInput(name = "token") { value = token }
                    button(type = ButtonType.submit, classes = "event-submit") { +"Weiter zur Zahlung" }
                }
            }
        }

    /**
     * EMBED mode only: a payment service provider (Stripe Checkout) cannot be framed, so instead of a `303` inside the iframe the
     * visitor gets this page with a link that opens the checkout in the full browser window (`target="_top"`). [redirectUrl] comes
     * from the server (the PSP gateway), never from the request, and is escaped by kotlinx.html like every other attribute.
     */
    fun paymentHandoffPage(
        ctx: EventPageContext,
        slug: String,
        redirectUrl: String,
    ): String =
        skeleton(ctx = ctx, heading = "Zahlung abschließen", currentPath = "/veranstaltung/$slug", noIndex = true) {
            section(classes = "event-card") {
                p { +"Die Zahlung öffnet sich im vollen Browserfenster." }
                a(href = redirectUrl, target = "_top", classes = "event-submit") {
                    attributes["rel"] = "noopener"
                    +"Weiter zur Zahlung"
                }
            }
        }

    /**
     * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- the public ticket page's view model.
     * [canonicalCode] is the raw ticket code (canonical form, no separators) -- carried through so
     * the page's own `<img>`/PDF-download links can build the `?code=` query string; NEVER logged
     * (see `EventPublicRoutes`' own "never logs the full URI" discipline).
     *
     * **Carries NO personally identifying detail** (no name, no email, no registration id) -- the
     * strongest reading of the data-protection requirement in the wave plan: if this page's URL
     * leaks, it leaks nothing about who holds the ticket, only which event and when.
     */
    data class TicketView(
        val title: String,
        val startsAt: LocalDateTime,
        val endsAt: LocalDateTime,
        val locationText: String?,
        val onlineUrl: String?,
        val slug: String,
        val canonicalCode: String,
        /** V1.9.113 -- link to the online participation page of a Lapis online event (see [View.onlineJoinUrl]); null otherwise. */
        val onlineJoinUrl: String? = null,
    )

    /**
     * Deliberately server-rendered, not a KVision screen -- see `39-events.kuml.kts` file header
     * addendum "Why the ticket page is server-rendered, not KVision" for the full reasoning (the
     * ticket holder has no account and gets none).
     */
    fun ticketPage(
        ctx: EventPageContext,
        view: TicketView,
    ): String =
        skeleton(ctx = ctx, heading = view.title, currentPath = "/veranstaltung/${view.slug}", noIndex = true) {
            factList {
                fact(label = "Wann") { +formatEventRange(start = view.startsAt, end = view.endsAt) }
                if (view.locationText != null || view.onlineUrl != null || view.onlineJoinUrl != null) {
                    fact(label = "Wo") {
                        whereLines(
                            locationText = view.locationText,
                            onlineUrl = view.onlineUrl,
                            onlineJoinUrl = view.onlineJoinUrl,
                            joinHint = false,
                        )
                    }
                }
            }
            figure(classes = "event-qr") {
                img(
                    src = ctx.href("/veranstaltung/${view.slug}/ticket.svg?code=${view.canonicalCode}"),
                    alt = "QR-Code für den Einlass",
                ) {
                    attributes["width"] = "260"
                    attributes["height"] = "260"
                }
            }
            p(classes = "event-ticket-code") { +EventTicketCode.formatForDisplay(view.canonicalCode) }
            p { a(href = ctx.href("/veranstaltung/${view.slug}/ticket.pdf?code=${view.canonicalCode}")) { +"Als PDF herunterladen" } }
        }

    /** A valid-code-but-not-yet-ticketable status -- rendered with the TRUE reason (the caller holds a genuine code, so an honest status line is the right posture here, unlike the neutral 404 an unknown/wrong-event code gets). */
    fun ticketNotConfirmedPage(
        ctx: EventPageContext,
        slug: String,
        status: EventRegistrationStatus,
    ): String {
        val message =
            when (status) {
                EventRegistrationStatus.PENDING_PAYMENT -> "Die Zahlung für diese Anmeldung ist noch nicht abgeschlossen."
                EventRegistrationStatus.WAITLISTED -> "Diese Anmeldung steht auf der Warteliste -- es wurde noch kein Ticket ausgestellt."
                EventRegistrationStatus.CANCELLED -> "Diese Anmeldung wurde storniert."
                EventRegistrationStatus.EXPIRED -> "Diese Anmeldung ist abgelaufen."
                // CONFIRMED never reaches this function -- EventPublicRoutes renders ticketPage() instead.
                EventRegistrationStatus.CONFIRMED -> "Diese Anmeldung ist bestätigt."
            }
        return skeleton(ctx = ctx, heading = "Kein Ticket verfügbar", currentPath = "/veranstaltung/$slug", noIndex = true) {
            callout(text = message)
        }
    }

    /**
     * [PublicChrome] header + wide layout in [EventPageMode.STANDARD]; the slim `embed-page` shape in [EventPageMode.EMBED].
     * [currentPath] is the site-relative path (NO query) the language switcher points to; [canonicalPath], when non-null, adds the
     * canonical link (plus hreflang alternates in STANDARD mode). The EMBED variant is always `noindex` and canonicalises onto the
     * STANDARD URL, so it never creates duplicate content.
     */
    private fun skeleton(
        ctx: EventPageContext,
        heading: String,
        currentPath: String,
        /** Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- when non-null, rendered as `og:image` so a shared event link previews with the cover image. `kotlinx.html`'s ordinary attribute API escapes it, same as every other value this file ever writes -- see class KDoc. */
        ogImage: String? = null,
        canonicalPath: String? = null,
        /** V1.9.140 -- token/follow-up and error pages: `noindex, nofollow` (replaces, never duplicates, the EMBED `noindex`). */
        noIndex: Boolean = false,
        content: FlowContent.() -> Unit,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = if (ctx.isEmbed) "de" else ctx.lang.code
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"$heading – ${ctx.brandTitle}" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
                val robots =
                    when {
                        noIndex -> "noindex, nofollow"
                        ctx.isEmbed -> "noindex"
                        else -> null
                    }
                if (robots != null) meta(name = "robots", content = robots)
                if (ctx.isEmbed) {
                    if (canonicalPath != null) link(rel = "canonical", href = "${ctx.baseUrl}$canonicalPath")
                } else if (canonicalPath != null) {
                    link(
                        rel = "canonical",
                        href = PublicChrome.languageUrl(baseUrl = ctx.baseUrl, currentPath = canonicalPath, lang = ctx.lang),
                    )
                    with(PublicChrome) { renderHreflangAlternates(baseUrl = ctx.baseUrl, currentPath = canonicalPath) }
                }
                if (ogImage != null) {
                    meta {
                        attributes["property"] = "og:image"
                        attributes["content"] = ogImage
                    }
                }
            }
            if (ctx.isEmbed) {
                body {
                    div("embed-page event-embed") {
                        h1 { +ctx.brandTitle }
                        h2 { +heading }
                        content()
                        // Site-relative, `target="_top"`: leaves the iframe; the start page has no embed mode.
                        p { a(href = "/", target = "_top") { +"Zur Startseite" } }
                        p(classes = "section-note") {
                            a(href = "/impressum", target = "_top") { +"Impressum" }
                            +" · "
                            a(href = "/datenschutz", target = "_top") { +"Datenschutz" }
                        }
                    }
                }
            } else {
                body(classes = "has-chrome event-wide") {
                    with(PublicChrome) {
                        renderChrome(
                            lang = ctx.lang,
                            active = PublicChrome.NavTarget.EVENTS,
                            baseUrl = ctx.baseUrl,
                            branding = ctx.branding,
                            currentPath = currentPath,
                            nav = ctx.nav,
                        )
                    }
                    main {
                        attributes["id"] = "main"
                        // The page content is German in every UI language (see PublicOverviewHtml's event list).
                        div {
                            attributes["lang"] = "de"
                            h1(classes = "event-title") { +heading }
                            content()
                        }
                    }
                    with(PublicChrome) { renderPublicFooter(lang = ctx.lang, baseUrl = ctx.baseUrl, branding = ctx.branding) }
                }
            }
        }
}

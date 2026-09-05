package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.emailInput
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.submitInput
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.title
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
    )

    fun eventPage(
        brandTitle: String,
        view: View,
    ): String =
        skeleton(brandTitle = brandTitle, heading = view.title) {
            p { +view.description }
            if (view.locationText != null) p { +"Ort: ${view.locationText}" }
            if (view.onlineUrl != null) p { +"Online: ${view.onlineUrl}" }
            p { +"Beginn: ${view.startsAt}" }
            p { +"Ende: ${view.endsAt}" }
            p { +"Teilnahmegebühr: ${view.feeLabel}" }
            p { +(if (view.full) "Plätze frei: Nein (Warteliste möglich)" else "Plätze frei: Ja") }
            if (!view.registrationOpen) {
                p { +"Die Anmeldung ist für diese Veranstaltung derzeit nicht möglich." }
            } else {
                registrationForm(view.slug)
            }
        }

    private fun FlowContent.registrationForm(slug: String) {
        form(action = "/veranstaltung/$slug/anmeldung", method = FormMethod.post) {
            honeypotField()
            label {
                htmlFor = "guestName"
                +"Name"
            }
            textInput(name = "guestName") {
                attributes["id"] = "guestName"
                required = true
            }
            label {
                htmlFor = "guestEmail"
                +"E-Mail-Adresse"
            }
            emailInput(name = "guestEmail") {
                attributes["id"] = "guestEmail"
                required = true
            }
            submitInput { value = "Anmelden" }
        }
    }

    private fun FORM.honeypotField() {
        // Klassischer No-JS-Bot-Schutz, siehe registerEventPublicRoutes KDoc -- ein echter Mensch
        // sieht/füllt dieses Feld nie aus (versteckt per Inline-Style, kein JavaScript nötig).
        div {
            attributes["style"] = "position:absolute;left:-9999px"
            attributes["aria-hidden"] = "true"
            label {
                htmlFor = "kommentar"
                +"Bitte freilassen"
            }
            textArea {
                attributes["id"] = "kommentar"
                name = "kommentar"
                attributes["tabindex"] = "-1"
                attributes["autocomplete"] = "off"
            }
        }
    }

    fun thanksPage(
        brandTitle: String,
        registrationId: String?,
    ): String =
        skeleton(brandTitle = brandTitle, heading = "Anmeldung bestätigt") {
            p { +"Vielen Dank für Ihre Anmeldung. Eine Bestätigung wurde per E-Mail versendet." }
            if (registrationId != null) p { +"Vorgang: $registrationId" }
        }

    fun waitlistPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Auf der Warteliste") {
            p {
                +(
                    "Diese Veranstaltung ist ausgebucht. Sie stehen auf der Warteliste und werden " +
                        "benachrichtigt, sobald ein Platz frei wird."
                )
            }
        }

    fun cancelledPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Vorgang abgebrochen") {
            p { +"Der Vorgang wurde abgebrochen. Es wurden keine Daten gespeichert." }
        }

    fun notFoundPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Nicht gefunden") {
            p { +"Diese Seite existiert nicht oder ist nicht öffentlich zugänglich." }
        }

    fun tooManyRequestsPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Zu viele Anfragen") {
            p { +"Bitte versuchen Sie es später erneut." }
        }

    fun serverErrorPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Fehler") {
            p { +"Es ist ein Fehler aufgetreten. Bitte versuchen Sie es später erneut." }
        }

    fun malformedRequestPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Ungültige Anfrage") {
            p { +"Die Anfrage konnte nicht verarbeitet werden." }
        }

    fun cancelConfirmPage(
        brandTitle: String,
        slug: String,
        token: String,
    ): String =
        skeleton(brandTitle = brandTitle, heading = "Anmeldung stornieren") {
            p { +"Möchten Sie Ihre Anmeldung wirklich stornieren?" }
            form(action = "/veranstaltung/$slug/storno", method = FormMethod.post) {
                hiddenInput(name = "token") { value = token }
                submitInput { value = "Ja, stornieren" }
            }
        }

    fun cancelledSuccessPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Anmeldung storniert") {
            p { +"Ihre Anmeldung wurde storniert." }
        }

    /** Counterpart of [cancelConfirmPage] for `POST /veranstaltung/{slug}/zahlung` (Review MAJOR fix) -- same GET-renders/POST-mutates split, see `registerEventPublicRoutes` KDoc. */
    fun paymentConfirmPage(
        brandTitle: String,
        slug: String,
        token: String,
    ): String =
        skeleton(brandTitle = brandTitle, heading = "Zahlung abschließen") {
            p { +"Bitte schließen Sie die Zahlung für Ihren Platz ab." }
            form(action = "/veranstaltung/$slug/zahlung", method = FormMethod.post) {
                hiddenInput(name = "token") { value = token }
                submitInput { value = "Weiter zur Zahlung" }
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
    )

    /**
     * Deliberately server-rendered, not a KVision screen -- see `39-events.kuml.kts` file header
     * addendum "Why the ticket page is server-rendered, not KVision" for the full reasoning (the
     * ticket holder has no account and gets none).
     */
    fun ticketPage(
        brandTitle: String,
        view: TicketView,
    ): String =
        skeleton(brandTitle = brandTitle, heading = view.title) {
            p { +"Beginn: ${view.startsAt}" }
            p { +"Ende: ${view.endsAt}" }
            if (view.locationText != null) p { +"Ort: ${view.locationText}" }
            if (view.onlineUrl != null) p { +"Online: ${view.onlineUrl}" }
            img(src = "/veranstaltung/${view.slug}/ticket.svg?code=${view.canonicalCode}", alt = "QR-Code für den Einlass") {
                attributes["width"] = "260"
                attributes["height"] = "260"
            }
            p { +EventTicketCode.formatForDisplay(view.canonicalCode) }
            p { a(href = "/veranstaltung/${view.slug}/ticket.pdf?code=${view.canonicalCode}") { +"Als PDF herunterladen" } }
        }

    /** A valid-code-but-not-yet-ticketable status -- rendered with the TRUE reason (the caller holds a genuine code, so an honest status line is the right posture here, unlike the neutral 404 an unknown/wrong-event code gets). */
    fun ticketNotConfirmedPage(
        brandTitle: String,
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
        return skeleton(brandTitle = brandTitle, heading = "Kein Ticket verfügbar") { p { +message } }
    }

    private fun skeleton(
        brandTitle: String,
        heading: String,
        content: FlowContent.() -> Unit,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"$heading – $brandTitle" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
            }
            body {
                div("embed-page") {
                    h1 { +brandTitle }
                    h2 { +heading }
                    content()
                    p { a(href = "/") { +"Zur Startseite" } }
                }
            }
        }
}

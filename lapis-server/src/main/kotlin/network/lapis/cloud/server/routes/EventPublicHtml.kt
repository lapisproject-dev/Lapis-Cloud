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
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.submitInput
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.title

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

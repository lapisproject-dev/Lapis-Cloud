package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SepaMandateInput
import network.lapis.cloud.shared.rpc.ISepaService
import kotlin.time.Clock

/**
 * V1.2.2 SEPA-Client-UI wave -- Plan §4.1 "Mandats-Sektion im ContributionsScreen (K1 -- der
 * eigentliche Defekt)". Hooked into `ContributionsScreen.kt` between the page's own `h1` and
 * `renderOwnSummary`, one line, no other change to that file.
 *
 * K1/Rams: for a plain MEMBER whose organization has never switched SEPA on, this section renders
 * **nothing at all** -- not a placeholder, not a hidden panel, no DOM node. For TREASURER/BOARD/
 * ADMIN in the same state it shows one muted explanatory line (ADMIN additionally gets a link to
 * `/sepa-settings`; TREASURER/BOARD do not, since that route is ADMIN-only -- a link there would
 * be a guaranteed "kein Zugriff" bounce, see [ISepaService.getSepaSettings] KDoc "Role: ADMIN").
 *
 * S-16: this section owns its OWN [io.kvision.panel.VPanel] and only ever clears that, never
 * `root` itself -- `ContributionsScreen.renderOwnSummary`/`renderOrgWideContributions` keep
 * rendering below it, asynchronously, and a `root.removeAll()` here would wipe them out.
 */
fun renderSepaMandateSection(root: SimplePanel) {
    val panel = root.vPanel(spacing = 8)

    fun refresh() {
        panel.removeAll()
        AppScope.launch {
            val mandates = loadMyMandateQuietly()
            if (mandates == null) {
                if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
                    val notice = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                    notice.div(tr("SEPA-Lastschrift ist für diese Organisation nicht aktiviert.")) {
                        addCssClasses("text-muted small")
                    }
                    if (AppState.hasRole(AccountRole.ADMIN)) {
                        val settingsLink = notice.button(tr("SEPA-Konfiguration öffnen"), style = ButtonStyle.LINK)
                        settingsLink.onClick { navigateTo(Routes.SEPA_SETTINGS) }
                    }
                }
                // MEMBER (or no privileged role at all): render nothing, per K1.
                return@launch
            }

            panel.h2(tr("SEPA-Lastschriftmandat")) { addCssClass("h5") }
            if (mandates.isEmpty()) {
                panel.p(tr("Sie haben aktuell kein aktives SEPA-Lastschriftmandat hinterlegt."))
                val grantButton = panel.button(tr("Mandat erteilen"), style = ButtonStyle.PRIMARY)
                val formHost = panel.vPanel(spacing = 6)
                grantButton.onClick {
                    grantButton.hide()
                    val session = AppState.session
                    renderSepaMandateForm(
                        container = formHost,
                        onBehalf = false,
                        defaultDebtorName = session?.displayName.orEmpty(),
                        memberOptions = emptyList(),
                    ) {
                        refresh()
                    }
                }
            } else {
                val mandate = mandates.first()
                val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                statusRow.statusBadge(sepaMandateStatusLabel(mandate.status), sepaMandateStatusColor(mandate.status))
                statusRow.div(gettext("Mandatsreferenz %1", mandate.mandateReference)) { addCssClasses("small") }
                panel.div(gettext("IBAN %1", formatIbanLast4(mandate.debtorIbanLast4))) { addCssClasses("text-muted small") }
                if (!mandate.createdBySelf) {
                    panel.div(gettext("Erfasst von %1 in Ihrem Namen.", mandate.createdByDisplayName)) {
                        addCssClasses("text-muted small")
                    }
                }
                val revokeButton = panel.button(tr("Mandat widerrufen"), style = ButtonStyle.OUTLINEDANGER)
                revokeButton.onClick {
                    confirmWithReasonDialog(
                        title = tr("Mandat widerrufen"),
                        message =
                            tr(
                                "Das SEPA-Lastschriftmandat wird widerrufen. Zukünftige Beiträge können danach nicht mehr " +
                                    "per Lastschrift eingezogen werden.",
                            ),
                        reasonLabel = tr("Grund (optional)"),
                        reasonRequired = false,
                        confirmLabel = tr("Widerrufen"),
                    ) { reason ->
                        revokeButton.disabled = true
                        AppScope.launch {
                            try {
                                val result = guarded { rpcService<ISepaService>().revokeMandate(mandate.id, reason) }
                                if (result != null) {
                                    notifySuccess(tr("Mandat widerrufen."))
                                    refresh()
                                }
                            } finally {
                                revokeButton.disabled = false
                            }
                        }
                    }
                }
            }

            val prenotifications = loadMyPrenotificationsQuietly()
            if (!prenotifications.isNullOrEmpty()) {
                panel.h2(tr("Anstehende Lastschriften")) { addCssClass("h6") }
                prenotifications.forEach { prenotification ->
                    panel.div(
                        gettext(
                            "%1 am %2 von IBAN %3 (Mandatsreferenz %4, Gläubiger-ID %5)",
                            formatMoney(prenotification.amount),
                            prenotification.requestedCollectionDate,
                            formatIbanLast4(prenotification.debtorIbanLast4),
                            prenotification.mandateReference,
                            prenotification.creditorId,
                        ),
                    ) { addCssClasses("small") }
                }
            }
        }
    }
    refresh()
}

/** S-1: NEVER `guarded{}` -- a [network.lapis.cloud.shared.rpc.ConflictException] here means
 * "SEPA is disabled for this organization", the ordinary/expected case for most organizations, not
 * a toast-worthy failure that fires on every single visit to `/contributions`. */
internal suspend fun loadMyMandateQuietly() = sepaProbe { rpcService<ISepaService>().getMyMandate() }

internal suspend fun loadMyPrenotificationsQuietly() = sepaProbe { rpcService<ISepaService>().listMyPrenotifications() }

private fun todayIso(): String =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

/**
 * Plan §2.5 -- the shared mandate form for both self- and on-behalf ([onBehalf]) grants, rendered
 * inline (K9: never a modal for a data-entry form of this length). [memberOptions] is empty for
 * `onBehalf == false`.
 *
 * S-15: [Validation.formatIbanGroups] is rendered ONLY into a separate, read-only echo line below
 * the IBAN field -- never written back into the field's own value. Format-checking
 * ([Validation.looksLikeIban]/[Validation.looksLikeBic]) happens at submit time, matching every
 * other form in this client (there is no `onBlur` idiom anywhere in this codebase to piggyback on).
 */
internal fun renderSepaMandateForm(
    container: SimplePanel,
    onBehalf: Boolean,
    defaultDebtorName: String,
    memberOptions: List<Pair<String, String>>,
    onGranted: () -> Unit,
) {
    val form = container.vPanel(spacing = 6)
    val memberSelect =
        if (onBehalf) {
            form.select(options = memberOptions, value = memberOptions.firstOrNull()?.first, label = tr("Mitglied"))
        } else {
            null
        }
    val debtorNameInput = form.text(value = defaultDebtorName, label = tr("Name des Kontoinhabers"))
    val ibanInput = form.text(label = tr("IBAN"))
    val ibanEcho = form.div { addCssClasses("text-muted small font-monospace") }
    ibanInput.subscribe { value -> ibanEcho.content = Validation.formatIbanGroups(value.orEmpty()) }
    val bicInput = form.text(label = tr("BIC (optional)"))
    val signatureDateInput = form.text(value = todayIso(), label = tr("Datum der Unterschrift (JJJJ-MM-TT)"))
    val acknowledgedCheckbox =
        form.checkBox(
            label =
                tr(
                    "Ich ermächtige, per SEPA-Lastschrift fällige Beträge von meinem Konto einzuziehen, und weise " +
                        "mein Kreditinstitut an, diese Lastschriften einzulösen. Ich kann innerhalb von acht Wochen, " +
                        "beginnend mit dem Belastungsdatum, die Erstattung des belasteten Betrags verlangen.",
                ),
        )
    val errorBox =
        form.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = form.button(tr("Mandat erteilen"), style = ButtonStyle.PRIMARY)

    submitButton.onClick {
        errorBox.hide()
        val memberId = if (onBehalf) memberSelect?.value else null
        val debtorName = debtorNameInput.value.orEmpty().trim()
        val ibanRaw = ibanInput.value.orEmpty().trim()
        // Review Round 2 (2026-08-20, MAJOR): MUST be uppercased before it is sent -- unlike
        // `IbanValidator.requireValid` (which normalizes internally, see `IbanValidator.normalize`),
        // `BicValidator.isValid` (SepaService.kt grantMandate) matches its regex against the RAW
        // string with no normalization at all. [Validation.looksLikeBic] below uppercases only for
        // ITS OWN check, never writes that back into [bicRaw] -- a lowercase-typed BIC used to pass
        // this form's validation and then get rejected server-side with a bare `ConflictException`
        // that this client can only render as [SEPA_WRITE_CONFLICT_MESSAGE]'s three unrelated causes.
        val bicRaw =
            bicInput.value
                ?.trim()
                ?.uppercase()
                .orEmpty()
        val signatureDate = runCatching { LocalDate.parse(signatureDateInput.value.orEmpty().trim()) }.getOrNull()
        val acknowledged = acknowledgedCheckbox.value

        val validationError =
            when {
                onBehalf && memberId == null -> tr("Bitte ein Mitglied auswählen.")
                debtorName.isBlank() -> tr("Bitte den Namen des Kontoinhabers angeben.")
                !Validation.looksLikeIban(ibanRaw) -> tr("Die IBAN ist ungültig.")
                bicRaw.isNotBlank() && !Validation.looksLikeBic(bicRaw) -> tr("Die BIC ist ungültig.")
                signatureDate == null -> tr("Bitte ein gültiges Datum (JJJJ-MM-TT) angeben.")
                !acknowledged -> tr("Bitte das SEPA-Lastschriftmandat bestätigen.")
                else -> null
            }
        if (validationError != null) {
            errorBox.content = validationError
            errorBox.show()
            return@onClick
        }

        submitButton.disabled = true
        AppScope.launch {
            try {
                val result =
                    sepaGuarded(tr(SEPA_MANDATE_CONFLICT_MESSAGE)) {
                        rpcService<ISepaService>().grantMandate(
                            SepaMandateInput(
                                memberId = memberId,
                                debtorName = debtorName,
                                debtorIban = ibanRaw,
                                debtorBic = bicRaw.takeIf { it.isNotBlank() },
                                signatureDate = signatureDate!!,
                                mandateTextAcknowledged = acknowledged,
                            ),
                        )
                    }
                if (result != null) {
                    notifySuccess(tr("Mandat erteilt."))
                    // MINOR (Review Round 2, 2026-08-20): reset the form after every successful
                    // grant, not just in the (onBehalf == false) case where `refresh()` happens to
                    // rebuild this whole panel from scratch anyway. Without this, erfassing several
                    // Fremdmandate back-to-back left the PREVIOUS member's name/IBAN sitting in the
                    // fields -- switching only the member `select` and re-submitting would grant a
                    // formally valid mandate on the WRONG member's account, and nothing server-side
                    // catches that (the IBAN is only format-checked, never cross-checked against the
                    // named account holder).
                    memberSelect?.value = memberOptions.firstOrNull()?.first
                    debtorNameInput.value = if (onBehalf) "" else defaultDebtorName
                    ibanInput.value = ""
                    ibanEcho.content = ""
                    bicInput.value = ""
                    signatureDateInput.value = todayIso()
                    acknowledgedCheckbox.value = false
                    onGranted()
                }
            } finally {
                submitButton.disabled = false
            }
        }
    }
}

/**
 * Plan §2.4 "Standard-`conflictMessage` für Schreibaktionen".
 *
 * Review Round 2 (2026-08-20, MINOR): originally worded as a CLOSED three-way disjunction ("entweder
 * ... oder") and used verbatim at every single SEPA write call site. `SepaService` throws
 * `ConflictException` for many more reasons than those three (an already-active mandate, an invalid
 * IBAN/BIC/signature date, a past-or-today collection date, an unmet prenotification period, missing
 * creditor master data, an already-recorded return, ...) -- and since Kilua RPC never transmits the
 * server's own exception message (`AppState.guarded` KDoc), this client-side text was the ONLY thing
 * the user ever saw, positively naming three causes that usually did not apply and leaving out the
 * one that actually did. [grantMandate][SEPA_MANDATE_CONFLICT_MESSAGE],
 * [createDebitBatch][SEPA_BATCH_CREATE_CONFLICT_MESSAGE],
 * [generateBatchFile][SEPA_GENERATE_FILE_CONFLICT_MESSAGE] and
 * [recordReturn][SEPA_RECORD_RETURN_CONFLICT_MESSAGE] now get their own, enumerated message instead
 * -- this constant remains the fallback for every other write call site (previewDebitBatch,
 * notifyBatch, markBatchSubmitted, cancelBatch, settleBatch), whose OWN conflict causes are close
 * enough to "gate not satisfied, or the run/mandate's status changed underneath you" that a shared,
 * now deliberately OPEN-ended text ("häufige Gründe sind") is accurate rather than misleading.
 */
internal const val SEPA_WRITE_CONFLICT_MESSAGE =
    "SEPA-Lastschrift-Aktion war nicht erfolgreich (Konflikt) -- häufige Gründe sind, dass die Funktion nicht " +
        "aktiviert ist, der aktuelle Rechtshinweis noch nicht erneut bestätigt wurde, zu viele Anfragen in kurzer " +
        "Zeit gestellt wurden, oder sich der Status von Lauf/Mandat zwischenzeitlich geändert hat. Ein " +
        "Administrator prüft das ggf. unter SEPA-Konfiguration."

/** Shared gate-reason clause reused by every specific conflict message below -- mirrors
 * `SepaService.requireSepaUsable`'s three causes (Plan §1). */
private const val SEPA_GATE_CONFLICT_HINT =
    "die Funktion ist nicht aktiviert, der aktuelle Rechtshinweis wurde nicht erneut bestätigt, oder es wurden " +
        "zu viele Anfragen in kurzer Zeit gestellt"

/** `grantMandate`'s own conflict causes (`SepaService.kt:339-372`), see [SEPA_WRITE_CONFLICT_MESSAGE] KDoc. */
internal const val SEPA_MANDATE_CONFLICT_MESSAGE =
    "Das Mandat konnte nicht erteilt werden -- mögliche Gründe: für dieses Mitglied besteht bereits ein aktives " +
        "Mandat, die IBAN ist ungültig oder liegt außerhalb des SEPA-Raums, der Name des Kontoinhabers ist leer, " +
        "die BIC hat kein gültiges Format, das Unterschriftsdatum liegt in der Zukunft oder mehr als 12 Monate " +
        "zurück, oder " + SEPA_GATE_CONFLICT_HINT + "."

/** `createDebitBatch`'s own conflict causes (`SepaService.kt:552-560`), see [SEPA_WRITE_CONFLICT_MESSAGE] KDoc.
 * Deliberately NOT reused for `previewDebitBatch` -- that call has none of these, only the gate. */
internal const val SEPA_BATCH_CREATE_CONFLICT_MESSAGE =
    "Der Lauf konnte nicht angelegt werden -- mögliche Gründe: das Einzugsdatum liegt nicht in der Zukunft, es " +
        "wurde kein fälliger Beitrag mit aktivem SEPA-Mandat gefunden, der Lauf hätte mehr Positionen als " +
        "zulässig, oder " + SEPA_GATE_CONFLICT_HINT + "."

/** `generateBatchFile`'s own conflict causes (`SepaService.kt:904-1010,1060`), see
 * [SEPA_WRITE_CONFLICT_MESSAGE] KDoc. */
internal const val SEPA_GENERATE_FILE_CONFLICT_MESSAGE =
    "Die Datei konnte nicht erzeugt werden -- mögliche Gründe: die Vorabankündigungsfrist ist für das " +
        "Einzugsdatum dieses Laufs nicht gewahrt, die Gläubiger-Stammdaten der Organisation (Gläubiger-ID, Name, " +
        "IBAN oder BIC in den Organisationseinstellungen) fehlen oder sind ungültig, alle Positionen des Laufs " +
        "wurden zwischenzeitlich storniert, der Lauf wurde bereits von einem gleichzeitigen Versuch verarbeitet, " +
        "oder " + SEPA_GATE_CONFLICT_HINT + "."

/** `recordReturn`'s own conflict causes (`SepaService.kt:1500-1519,1533`), see
 * [SEPA_WRITE_CONFLICT_MESSAGE] KDoc. */
internal const val SEPA_RECORD_RETURN_CONFLICT_MESSAGE =
    "Die Rücklastschrift konnte nicht erfasst werden -- mögliche Gründe: das Rückgabedatum liegt in der Zukunft, " +
        "die Rücklastschriftgebühr ist ungültig (nicht positiv oder mehr als 2 Nachkommastellen), für diese " +
        "Position ist bereits ein Rückläufer erfasst, die Position befindet sich nicht mehr im dafür zulässigen " +
        "Status, oder " + SEPA_GATE_CONFLICT_HINT + "."

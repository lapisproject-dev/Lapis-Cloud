package network.lapis.cloud.client

import io.kvision.form.text.password
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAccessPreflightDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.IMemberService

/*
 * Welle V1.4.9 "Admin-Passwort-Reset" -- der vierte Aktionsknopf der Mitgliederverwaltung, ein
 * eigener Dialog (nicht Teil von [openMemberEditorDialog]s Sektionen, siehe dort und
 * [renderMemberRosterRow]s KDoc): zwei unabhängige Wege, ein Konto wieder erreichbar zu machen --
 * Weg 1 (rot, ein sofortiger, folgenreicher Zugriffs-Akt: setzt ein Passwort direkt und beendet
 * alle Sitzungen) und Weg 2 (neutral, folgenlos bis das Mitglied selbst reagiert: verschickt
 * denselben Reset-Link wie die Selbstbedienung). Die beiden bleiben bewusst UNTERSCHIEDLICH
 * gefährlich UND unterschiedlich gestaltet -- siehe resetMailBlockReason KDoc.
 */

/** Mirrors `network.lapis.cloud.server.security.TemporaryPasswordGenerator.ALPHABET` -- loser Spiegel, der Server bleibt Autorität. */
const val PASSWORD_ALPHABET: String = "abcdefghjkmnpqrstuvwxyz23456789"
const val PASSWORD_GROUP_COUNT: Int = 4
const val PASSWORD_GROUP_SIZE: Int = 4

/**
 * ADMIN, Fremdzeile, nicht anonymisiert, hat ein Konto, nicht DECEASED -- spiegelt
 * `MemberService.setTemporaryPasswordForMember`s/`.sendPasswordResetMailToMember`s eigene Gates
 * (das strengere der beiden Wege gewinnt: DECEASED sperrt nur Weg 1, aber ein Knopf, der zu einem
 * Dialog führt, in dem eine der beiden Zonen sofort deaktiviert wäre, ist schlechter als gar kein
 * Knopf -- siehe [resetMailBlockReason], das die feinere LOGIN_BLOCKED-Sperre separat traegt).
 */
fun canResetPasswordOf(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean = passwordResetBlockReason(callerRole, callerMemberId, row) == null

/**
 * `null` = Knopf aktiv; sonst der Text, der als `title` am DEAKTIVIERTEN Knopf steht -- kein
 * grauer Knopf ohne Erklärung (Forstall).
 */
fun passwordResetBlockReason(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): String? =
    when {
        row.anonymized -> tr("DSGVO-gelöscht")
        row.id == callerMemberId -> tr("Eigenes Passwort: über „Passwort ändern“ im eigenen Konto.")
        row.role == null -> tr("Kein Login-Konto — zuerst „Konto anlegen“ im Bearbeiten-Dialog.")
        row.status == MemberStatus.DECEASED -> tr("Verstorben — zuerst den Status korrigieren.")
        callerRole != AccountRole.ADMIN -> tr("Nur Administratoren können einen Zugang zurücksetzen.")
        else -> null
    }

/** Live-Konsequenz über dem roten Knopf. 0/1/n grammatikalisch korrekt. */
fun temporaryPasswordConsequence(activeSessionCount: Int): String =
    when (activeSessionCount) {
        0 -> tr("Es gibt aktuell keine aktive Sitzung zu beenden.")
        1 -> tr("Eine aktive Sitzung wird sofort beendet.")
        else -> gettext("%1 aktive Sitzungen werden sofort beendet.", activeSessionCount)
    }

/**
 * `null` = Weg 2 anbietbar; sonst der Hinweistext unter dem deaktivierten Knopf. **Bewusste
 * Asymmetrie zu Weg 1**: nicht konfiguriertes SMTP deaktiviert NUR diese Zone (Zone 1 bleibt voll
 * bedienbar -- das temporäre Passwort braucht keine E-Mail), und ein `LOGIN_BLOCKED`-Status
 * deaktiviert NUR Weg 2 (ein Reset-Link an ein gesperrtes Konto wäre wirkungslos; Weg 1 ist selbst
 * für DONOR/WITHDRAWN/REJECTED weiterhin sinnvoll -- ein Betreiber kann das Konto trotzdem
 * vorbereiten).
 */
fun resetMailBlockReason(
    mailDelivery: MailDeliveryState,
    status: MemberStatus,
): String? =
    when {
        mailDelivery == MailDeliveryState.NOT_CONFIGURED ->
            tr(
                "SMTP ist auf dieser Installation nicht konfiguriert (LAPIS_SMTP_*) — es kann keine " +
                    "E-Mail versendet werden. Nutzen Sie das temporäre Passwort oben.",
            )
        status in MemberStatusSets.LOGIN_BLOCKED ->
            tr("Für diesen Status ist der Login gesperrt — ein Reset-Link wäre wirkungslos.")
        else -> null
    }

/**
 * Reine Formatierung: [indices] sind Positionen in [PASSWORD_ALPHABET]. Spiegelt
 * `network.lapis.cloud.server.security.TemporaryPasswordGenerator` (4 Gruppen à 4, "-"-getrennt).
 */
fun formatDictatablePassword(indices: IntArray): String =
    indices
        .map { PASSWORD_ALPHABET[it] }
        .chunked(PASSWORD_GROUP_SIZE)
        .joinToString(separator = "-") { it.joinToString(separator = "") }

/**
 * `window.crypto.getRandomValues` mit Rejection-Sampling gegen den Modulo-Bias (256 % 31 == 8).
 * `null`, wenn `crypto` fehlt -- dann bleibt das Feld leer und der Server erzeugt
 * (`newPassword = null` gesendet). NIEMALS `kotlin.random.Random`: Kotlin/JS' `Random.Default` ist
 * ein aus `Math.random()` geseedeter XorWow-PRNG, nicht kryptografisch.
 */
fun generateDictatablePasswordOrNull(): String? {
    val crypto: dynamic = window.asDynamic().crypto
    if (crypto == null || crypto == undefined) return null
    val alphabetSize = PASSWORD_ALPHABET.length
    // Werte >= diese Schwelle werden verworfen -- 256 - (256 % 31) = 248, damit jeder der 31
    // Buchstaben exakt gleich viele der [0,248)-Rohwerte abbekommt.
    val rejectAtOrAbove = 256 - (256 % alphabetSize)
    val indices =
        IntArray(PASSWORD_GROUP_COUNT * PASSWORD_GROUP_SIZE) {
            var raw: Int
            do {
                val buffer = js("new Uint8Array(1)")
                crypto.getRandomValues(buffer)
                raw = (buffer[0] as Int)
            } while (raw >= rejectAtOrAbove)
            raw % alphabetSize
        }
    return formatDictatablePassword(indices)
}

/**
 * Öffnet den Dialog. **Nicht** aus [openMemberEditorDialog] erreichbar -- kein Modal-im-Modal.
 * [row] muss [canResetPasswordOf] erfüllen, das prüft der aufrufende Knopf bereits (siehe
 * [renderMemberRosterRow]).
 */
fun openMemberPasswordResetDialog(
    row: MemberAdminRowDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = gettext("Zugang zurücksetzen — %1", row.displayName))
    // Eigener Inhalts-Container statt direkt auf `modal` -- die Quittung ersetzt NUR diesen
    // Container (per removeAll()), der Footer-Knopf "Schließen" (modal.addButton, ganz unten)
    // bleibt davon unberührt.
    val body = modal.div()

    // ── Zone 1: Temporäres Passwort (rot, oben) ──
    val passwordRow = body.hPanel(spacing = 6)
    val passwordInput = passwordRow.password(label = tr("Temporäres Passwort"))
    passwordInput.value = generateDictatablePasswordOrNull()
    val regenerateButton = passwordRow.button("", icon = "fas fa-rotate", style = ButtonStyle.OUTLINESECONDARY)
    regenerateButton.title = tr("Neu erzeugen")
    regenerateButton.onClick { passwordInput.value = generateDictatablePasswordOrNull() }
    val reasonInput = body.textArea(rows = 2, label = tr("Begründung (3-1000 Zeichen)"))
    val consequenceBox = body.div { addCssClasses("alert alert-danger") }
    consequenceBox.content = tr("Sitzungszahl wird geladen …")
    val tempPasswordError =
        body.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val setPasswordButton = body.button(tr("Passwort setzen und alle Sitzungen beenden"), style = ButtonStyle.DANGER)
    // Deaktiviert bis der Preflight geladen ist -- kein Klick auf ungeklärte Fakten.
    setPasswordButton.disabled = true

    // ── Zone 2: Reset-E-Mail (neutral, unten, durch einen dezenten Rahmen getrennt) ──
    body.div { addCssClasses("mt-3 pt-3 border-top") }
    body.div(
        tr(
            "Alternativ kann eine E-Mail mit einem Reset-Link verschickt werden. Das Mitglied " +
                "vergibt sein Passwort dann selbst; bis dahin ändert sich nichts am Konto.",
        ),
    ) { addCssClasses("text-muted small") }
    val resetMailWarning =
        body.div {
            addCssClasses("alert alert-secondary")
            hide()
        }
    val resetMailButton = body.button(tr("Reset-E-Mail senden"), style = ButtonStyle.OUTLINESECONDARY)
    resetMailButton.disabled = true

    modal.addButton(Button(tr("Schließen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.show()

    AppScope.launch {
        val preflight: MemberAccessPreflightDto? =
            memberAdminGuarded { rpcService<IMemberService>().getMemberAccessPreflight(row.id) }
        if (preflight == null) return@launch

        consequenceBox.content = temporaryPasswordConsequence(preflight.activeSessionCount)
        setPasswordButton.disabled = false

        val mailBlock = resetMailBlockReason(preflight.mailDelivery, row.status)
        if (mailBlock != null) {
            resetMailButton.disabled = true
            resetMailWarning.content = mailBlock
            resetMailWarning.show()
        } else {
            resetMailButton.disabled = false
        }
    }

    setPasswordButton.onClick {
        tempPasswordError.hide()
        val reason = reasonInput.value.orEmpty().trim()
        if (reason.length < 3 || reason.length > 1000) {
            tempPasswordError.content = tr("Bitte eine Begründung (3-1000 Zeichen) angeben.")
            tempPasswordError.show()
            return@onClick
        }
        val chosenPassword = passwordInput.value.orEmpty()
        // Ein LEERES Feld ist eine bewusste Wahl -- "Server soll erzeugen" (siehe der `ifBlank {
        // null }` unten) -- und KEIN zu kurzes Passwort. Review-Fund (MAJOR): `passwordHint` gegen
        // `""` liefert immer "Mindestens 12 Zeichen." (Validation.PASSWORD_MIN_LENGTH), was den
        // Server-generiert-Pfad unerreichbar machte -- sowohl für einen Betreiber, der das Feld
        // absichtlich leert, als auch für jeden Browser/Kontext ohne `window.crypto`
        // (generateDictatablePasswordOrNull liefert dort `null`, das Feld bleibt leer). Die Prüfung
        // greift deshalb nur, wenn tatsächlich ein Klartext-Passwort gewählt wurde.
        if (chosenPassword.isNotBlank()) {
            val passwordHint = Validation.passwordHint(chosenPassword, row.email)
            if (passwordHint != null) {
                tempPasswordError.content = passwordHint
                tempPasswordError.show()
                return@onClick
            }
        }
        setPasswordButton.disabled = true
        AppScope.launch {
            val result =
                memberAdminGuarded {
                    rpcService<IMemberService>().setTemporaryPasswordForMember(
                        memberId = row.id,
                        // Der Server generiert nur, wenn hier wirklich `null` ankommt -- das Feld ist
                        // beim Öffnen bereits vorbefüllt, ein Klartext-Passwort geht also i. d. R.
                        // durch, es sei denn `window.crypto` fehlte (dann blieb das Feld leer).
                        newPassword = chosenPassword.ifBlank { null },
                        reason = reason,
                    )
                }
            setPasswordButton.disabled = false
            if (result != null) {
                onChanged()
                renderTemporaryPasswordReceipt(
                    body = body,
                    // Review-Fund (MAJOR): der Server echot ein Passwort nur zurück, wenn ER es
                    // erzeugt hat (`newPassword == null` gesendet, siehe MemberService.kt
                    // `generatedPassword = if (newPassword == null) ... else null`). Im
                    // Normalfall -- das Feld bleibt beim vorbefüllten, diktierbaren Passwort --
                    // ist `result.generatedPassword` also IMMER `null`, obwohl genau dieses
                    // Passwort gerade gesetzt wurde. Der Client kennt das gewählte Passwort
                    // bereits selbst (`chosenPassword`); als Fallback verwendet, zeigt die
                    // Quittung IMMER das Passwort, das jetzt tatsächlich gilt -- serverseitig
                    // erzeugt oder client-seitig diktiert -- statt in den meisten Fällen leer zu
                    // bleiben und den einzigen Anzeige-Moment ungenutzt zu lassen.
                    generatedPassword = result.generatedPassword ?: chosenPassword.ifBlank { null },
                    revokedSessionCount = result.revokedSessionCount,
                    memberNotified = result.memberNotified,
                )
            }
        }
    }

    resetMailButton.onClick {
        resetMailButton.disabled = true
        AppScope.launch {
            val result = memberAdminGuarded { rpcService<IMemberService>().sendPasswordResetMailToMember(row.id) }
            resetMailButton.disabled = false
            if (result != null) {
                when (result.delivery) {
                    MailDeliveryState.HANDED_TO_SMTP -> notifySuccess(tr("Reset-E-Mail wurde versendet."))
                    MailDeliveryState.NOT_CONFIGURED ->
                        notifyError(tr("SMTP ist nicht konfiguriert — es konnte keine E-Mail versendet werden."))
                    MailDeliveryState.RATE_LIMITED ->
                        notifyError(tr("Versandlimit erreicht — bitte später erneut versuchen."))
                }
            }
        }
    }
}

/**
 * Ersetzt [body] durch die Quittung -- das Passwort wird NIE erneut angezeigt, dieser Moment ist
 * der einzige. Kein `modal.hide()` hier: Bootstraps ~150ms-Fade würde den einmalig sichtbaren Wert
 * unwiederbringlich verschwinden lassen, bevor ihn jemand notieren/kopieren kann -- genau deshalb
 * ist dieser Dialog kein Abschnitt von [openMemberEditorDialog], siehe dessen "Kein modal.hide()
 * nach dem Setzen"-Stolperfalle.
 */
private fun renderTemporaryPasswordReceipt(
    body: SimplePanel,
    generatedPassword: String?,
    revokedSessionCount: Int,
    memberNotified: MailDeliveryState,
) {
    body.removeAll()
    if (generatedPassword != null) {
        body.div(generatedPassword) {
            addCssClasses("border rounded p-2 fs-5 font-monospace")
        }
        val copyLabel = tr("Kopieren")
        val copiedLabel = tr("Kopiert")
        lateinit var copyButton: Button
        copyButton =
            body.button(copyLabel, style = ButtonStyle.SECONDARY) {
                onClick {
                    copyPasswordToClipboard(generatedPassword) {
                        copyButton.text = copiedLabel
                        AppScope.launch {
                            delay(2000)
                            copyButton.text = copyLabel
                        }
                    }
                }
            }
    }
    body.div(tr("Wird nicht erneut angezeigt.")) { addCssClasses("fw-bold mt-2") }
    val notificationLine =
        when (memberNotified) {
            MailDeliveryState.HANDED_TO_SMTP -> tr("Das Mitglied wurde per E-Mail informiert.")
            MailDeliveryState.NOT_CONFIGURED -> tr("Kein SMTP konfiguriert — das Mitglied wurde nicht benachrichtigt.")
            MailDeliveryState.RATE_LIMITED -> tr("Benachrichtigung übersprungen (Versandlimit).")
        }
    body.div {
        addCssClasses("text-muted small mt-2")
        span(gettext("%1 Sitzung(en) beendet.", revokedSessionCount))
        span(" $notificationLine")
    }
}

/**
 * D6-Muster (siehe `EmbedIntegrationScreen.copyToClipboard`): kein Toast, kein Dialog, keine
 * Animation -- nur der Label-Wechsel des Aufrufers. Bei fehlender/blockierter Clipboard-API wird
 * [onCopied] trotzdem NICHT aufgerufen (ehrliches Fehlschlagen statt eines irreführenden "Kopiert").
 */
private fun copyPasswordToClipboard(
    text: String,
    onCopied: () -> Unit,
) {
    val clipboard: dynamic = window.navigator.asDynamic().clipboard
    if (clipboard == null || clipboard == undefined) return
    val promise: dynamic = clipboard.writeText(text)
    promise.then({ onCopied() }, {})
}

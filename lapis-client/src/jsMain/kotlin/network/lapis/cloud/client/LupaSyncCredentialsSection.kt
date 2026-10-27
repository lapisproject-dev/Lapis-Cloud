package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Autocomplete
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.LupaBbCredentialsInput
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto
import network.lapis.cloud.shared.rpc.ILupaBbSyncService

/**
 * V1.9.226 -- the credentials block of the sync page: user, password and API key of LupaBB. The three fields are NEVER pre-filled (the server
 * does not even have a way to send them back), carry `autocomplete="off"` (password managers are asked to leave them alone) and are emptied
 * as soon as the form was sent. "Prüfen und speichern" makes ONE request to LupaBB and stores the values only if that worked.
 */
internal fun renderLupaSyncCredentials(
    root: Container,
    initial: LupaBbSyncStatusDto,
    onStatus: (LupaBbSyncStatusDto) -> Unit,
) {
    val host = root.vPanel(spacing = 8)
    var editing = false

    lateinit var paint: (LupaBbSyncStatusDto) -> Unit

    fun paintForm(status: LupaBbSyncStatusDto) {
        val form = host.lapisForm()
        val user =
            form.textField(label = tr("Benutzername"), required = true, autocomplete = Autocomplete.OFF, init = {
                it.setAttribute("spellcheck", "false")
            })
        val password =
            form.passwordField(label = tr("Passwort"), required = true, suppressManagers = true, reveal = true)
        val apiKey = form.passwordField(label = tr("API-Schlüssel"), required = true, suppressManagers = true)
        val save = newActionButton(ActionIcon.SAVE, tr("Prüfen und speichern"), ButtonStyle.PRIMARY)
        val cancel =
            if (status.credentialsSet) {
                newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY).also {
                    it.onClick {
                        editing = false
                        paint(status)
                    }
                }
            } else {
                null
            }
        form.buttons(primary = save, cancel = cancel)
        save.disabled = !status.encryptionAvailable
        save.onClick {
            form.submit(save) {
                val input = LupaBbCredentialsInput(username = user.value.trim(), password = password.value, apiKey = apiKey.value.trim())
                val result = guarded { rpcService<ILupaBbSyncService>().verifyAndSaveCredentials(input) }
                // emptied whatever the result: a typed secret does not stay in the page
                user.reset()
                password.reset()
                apiKey.reset()
                if (result != null) {
                    notifySuccess(tr("Die Zugangsdaten wurden geprüft und gespeichert."))
                    editing = false
                    onStatus(result)
                }
            }
        }
    }

    paint = { status ->
        host.removeAll()
        host.h2(tr("Zugangsdaten")) { addCssClass("h5") }
        if (!status.encryptionAvailable) {
            host.div(
                tr("Auf diesem Server ist kein Geheimnis-Schlüssel eingerichtet. Zugangsdaten können deshalb nicht gespeichert werden."),
            ) {
                addCssClasses("alert alert-warning mb-0")
                setAttribute("role", "note")
            }
        }
        if (status.credentialsSet && !editing) {
            host.div {
                addCssClass("text-muted")
                span(tr("Zugangsdaten sind hinterlegt."))
                status.setAt?.let {
                    span(" ")
                    span(gettext("Gespeichert am %1.", formatSystemDateTime(it)))
                }
            }
            status.setByName?.let { name ->
                host.div {
                    addCssClasses("text-muted small")
                    span(tr("Gespeichert von"))
                    span(": ")
                    span(sanitizeUntrustedI18nText(name))
                }
            }
            val row = host.lapisToolbar()
            row.actionButton(ActionIcon.EDIT, tr("Zugangsdaten ersetzen")) {
                onClick {
                    editing = true
                    paint(status)
                }
            }
            row.actionButton(ActionIcon.DELETE, tr("Zugangsdaten entfernen"), style = ButtonStyle.OUTLINEDANGER) {
                onClick {
                    confirmDialog(
                        title = tr("Zugangsdaten entfernen?"),
                        message = tr("Danach ist kein Abgleich mehr möglich, bis Sie neue Zugangsdaten eingeben."),
                        confirmLabel = tr("Entfernen"),
                        confirmIcon = ActionIcon.DELETE,
                    ) {
                        AppScope.launch {
                            val result = guarded { rpcService<ILupaBbSyncService>().removeCredentials() }
                            if (result != null) {
                                notifySuccess(tr("Die Zugangsdaten wurden entfernt."))
                                onStatus(result)
                            }
                        }
                    }
                }
            }
        } else {
            host.div(
                tr(
                    "Benutzername, Passwort und API-Schlüssel erhalten Sie von der Verwaltung von LupaBB. " +
                        "Sie werden verschlüsselt gespeichert " +
                        "und nie wieder angezeigt.",
                ),
            ) { addCssClasses("text-muted") }
            paintForm(status)
        }
    }
    paint(initial)
}

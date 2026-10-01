package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.McpAccessStateDto
import network.lapis.cloud.shared.domain.McpConnectionDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMcpAccessService
import network.lapis.cloud.shared.rpc.McpFeatureDisabledException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/** The RPC surface [McpAccessCard] needs -- an interface so DOM tests can drive the card without a server. Exceptions propagate. */
internal interface McpAccessRpc {
    suspend fun getState(): McpAccessStateDto

    suspend fun setAllowed(allowed: Boolean): McpAccessStateDto

    suspend fun revoke(tokenId: String): McpAccessStateDto
}

internal fun liveMcpAccessRpc(): McpAccessRpc =
    object : McpAccessRpc {
        override suspend fun getState() = rpcService<IMcpAccessService>().getMcpAccessState()

        override suspend fun setAllowed(allowed: Boolean) = rpcService<IMcpAccessService>().setMcpAccessAllowed(allowed)

        override suspend fun revoke(tokenId: String) = rpcService<IMcpAccessService>().revokeConnection(tokenId)
    }

/** Injectable confirmation dialog (the tests drive it without a modal); same arguments as [confirmDialog]. */
internal fun interface McpConfirm {
    fun show(
        title: String,
        message: String,
        confirmLabel: String,
        extraLines: List<String>,
        dangerNote: String?,
        onConfirm: () -> Unit,
    )
}

internal val liveMcpConfirm =
    McpConfirm { title, message, confirmLabel, extraLines, dangerNote, onConfirm ->
        confirmDialog(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            confirmStyle = ButtonStyle.DANGER,
            extraLines = extraLines,
            dangerNote = dangerNote,
            focusCancel = true,
            onConfirm = onConfirm,
        )
    }

/** What a read of the access state means for the card. */
internal sealed interface McpLoadOutcome {
    data class Ok(
        val dto: McpAccessStateDto,
    ) : McpLoadOutcome

    /** MCP is off for this server, or this account may not use it: the card does not exist, silently. */
    data object Hidden : McpLoadOutcome

    data object Failed : McpLoadOutcome
}

/**
 * Reads the state and classifies the result. Deliberately NOT `guarded {}`: its toast for "feature disabled" / "forbidden" would
 * announce an error for what is simply "this card does not exist for you". Nothing is logged and no exception text is ever shown.
 */
internal suspend fun loadMcpAccess(rpc: McpAccessRpc): McpLoadOutcome =
    try {
        val dto = rpc.getState()
        if (dto.featureEnabled) McpLoadOutcome.Ok(dto) else McpLoadOutcome.Hidden
    } catch (e: CancellationException) {
        throw e
    } catch (e: McpFeatureDisabledException) {
        McpLoadOutcome.Hidden
    } catch (e: ForbiddenException) {
        McpLoadOutcome.Hidden
    } catch (e: UnauthenticatedException) {
        sessionExpired()
        McpLoadOutcome.Hidden
    } catch (e: Throwable) {
        McpLoadOutcome.Failed
    }

/**
 * Welle V1.9.29 "KI-Zugang" -- the member's own card on "Meine Daten": the switch "KI-Agenten dürfen sich mit meinem Konto verbinden"
 * and the list of the agent connections they granted, each with a "Widerrufen" button.
 *
 * **The server state is the only truth.** Nothing is flipped or removed optimistically: every change renders from the DTO the server
 * answered with, and a failed write re-reads the state. Switching OFF (and revoking) asks for a confirmation first, switching ON
 * does not. While a write runs every control is locked.
 *
 * The connection name is chosen by the agent's user and therefore untrusted: it only ever reaches the screen through [untrustedDiv]
 * (the marker-stripping helper) and never appears in a dialog, attribute or toast. The `tokenId` lives only in the click closure of its
 * row: not in the DOM, not in a title, not in storage. Server exception text is never shown.
 */
internal class McpAccessCard(
    parent: Container,
    private val rpc: McpAccessRpc,
    private val confirm: McpConfirm = liveMcpConfirm,
    private val toast: (String) -> Unit = { notifyError(it) },
) {
    internal val root: SimplePanel = parent.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }

    init {
        root.h2(className = "h5 mb-0") {
            icon("fas fa-plug").apply {
                addCssClass("me-2")
                setAttribute("aria-hidden", "true")
            }
            span(tr("KI-Zugang"))
        }
        root.div(
            tr(
                "KI-Agenten, zum Beispiel Claude Desktop, können sich mit Ihrem Konto verbinden und Ihre eigenen Daten lesen. " +
                    "Daten anderer Personen bleiben ihnen verschlossen.",
            ),
        ) { addCssClasses("text-muted small") }
        root.div(
            tr(
                "Wenn Ihre Organisation es erlaubt, können Agenten außerdem Entwürfe anlegen. " +
                    "Veröffentlicht wird erst, wenn Sie einen Entwurf selbst freigeben.",
            ),
        ) { addCssClasses("text-muted small") }
    }

    internal val reloadHost: SimplePanel = root.simplePanel()
    private val body: SimplePanel = root.vPanel(spacing = 8)

    internal val accessSwitch: CheckBox =
        CheckBox(value = false, label = tr("KI-Agenten dürfen sich mit meinem Konto verbinden")).also {
            it.addCssClass("form-switch")
            body.add(it)
        }
    internal val stateLine: Div = body.div("") { addCssClasses("text-muted small") }
    internal val connectionList: SimplePanel = body.vPanel(spacing = 4)
    internal val statusLine: Div = body.div("") { addCssClasses("text-muted small") }

    private val revokeButtons = mutableListOf<Button>()
    private var state: McpAccessStateDto? = null
    private var inFlight = false
    private var syncingSwitch = false

    init {
        statusLine.setAttribute("role", "status")
        statusLine.setAttribute("aria-live", "polite")
        statusLine.hide()
        buildHowTo()
        accessSwitch.subscribe { checked ->
            // KVision notifies subscribers ALSO for programmatic `value =` changes -- a notification that merely echoes the server state we
            // just rendered must never be mistaken for a click. A real click always DIFFERS from the server state.
            val server = state?.accessAllowed ?: return@subscribe
            if (syncingSwitch || checked == server) return@subscribe
            onSwitchToggled(checked)
        }
        root.hide()
    }

    /** The collapsible "So verbinden Sie einen Agenten" block: a toggle button with `aria-expanded`, three honest sentences. */
    private fun buildHowTo() {
        val toggle = body.button(tr("So verbinden Sie einen Agenten"), style = ButtonStyle.LINK) { addCssClasses("btn-sm p-0 text-start") }
        toggle.setAttribute("aria-expanded", "false")
        val howTo = body.vPanel(spacing = 4)
        howTo.div(
            tr(
                "Tragen Sie in Ihrem KI-Agenten die Adresse dieser Plattform als MCP-Server ein; die genaue Adresse nennt Ihnen Ihre Organisation.",
            ),
        ) { addCssClasses("small") }
        howTo.div(
            tr(
                "Beim ersten Verbinden melden Sie sich hier an und geben der Verbindung selbst einen Namen; erst nach Ihrem Erlauben erhält der Agent Zugriff.",
            ),
        ) { addCssClasses("small") }
        howTo.div(
            tr("Der Agent sieht nur Ihre eigenen Daten, und Sie können jede Verbindung hier jederzeit widerrufen."),
        ) { addCssClasses("small") }
        howTo.hide()
        var expanded = false
        toggle.onClick {
            expanded = !expanded
            howTo.visible = expanded
            toggle.setAttribute("aria-expanded", expanded.toString())
        }
    }

    /** Reads the state from the server and renders it (or hides / shows the error state). */
    suspend fun reload() {
        when (val outcome = loadMcpAccess(rpc)) {
            is McpLoadOutcome.Ok -> render(outcome.dto)
            McpLoadOutcome.Hidden -> hide()
            McpLoadOutcome.Failed -> showFailed()
        }
    }

    private fun hide() {
        state = null
        root.hide()
    }

    private fun showFailed() {
        state = null
        root.show()
        body.hide()
        reloadHost.removeAll()
        reloadHost.dataErrorState { AppScope.launch { reload() } }
    }

    /** DIE einzige Stelle, die den Kartenzustand aus einem Server-DTO ableitet. */
    internal fun render(dto: McpAccessStateDto) {
        if (!dto.featureEnabled) {
            hide()
            return
        }
        state = dto
        root.show()
        reloadHost.removeAll()
        body.show()
        syncingSwitch = true
        try {
            accessSwitch.value = dto.accessAllowed
        } finally {
            syncingSwitch = false
        }
        stateLine.content =
            when {
                !dto.accessAllowed -> tr("Ausgeschaltet. Kein KI-Agent kann sich mit Ihrem Konto verbinden.")
                dto.connections.isEmpty() -> tr("Eingeschaltet. Derzeit ist kein Agent verbunden.")
                else -> gettext("Eingeschaltet. Verbundene Agenten: %1.", dto.connections.size)
            }
        connectionList.removeAll()
        revokeButtons.clear()
        dto.connections.forEach { addRow(it) }
        connectionList.visible = dto.connections.isNotEmpty()
        applyEnabled()
    }

    private fun addRow(connection: McpConnectionDto) {
        val row =
            connectionList.div {
                addCssClasses(
                    "border rounded p-2 d-flex flex-wrap justify-content-between align-items-center gap-2",
                )
            }
        val info = row.div()
        val shownLabel = sanitizeUntrustedI18nText(connection.connectionLabel).trim()
        if (shownLabel.isEmpty()) {
            info.div(tr("Ohne Namen")) { addCssClasses("fw-bold text-break") }
        } else {
            info.untrustedDiv(shownLabel, className = "fw-bold text-break")
        }
        info.div(gettext("Erteilt am %1", formatDateTime(connection.grantedAt))) { addCssClasses("text-muted small") }
        info.div(
            connection.lastUsedAt?.let { gettext("Zuletzt benutzt am %1", formatDateTime(it)) } ?: tr("Noch nie benutzt"),
        ) { addCssClasses("text-muted small") }
        val revoke = Button(tr("Widerrufen"), icon = "fas fa-unlink", style = ButtonStyle.OUTLINEDANGER)
        row.add(revoke)
        revokeButtons += revoke
        val tokenId = connection.tokenId
        revoke.onClick { requestRevoke(tokenId, revoke) }
    }

    private fun applyEnabled() {
        accessSwitch.disabled = inFlight
        revokeButtons.forEach { it.disabled = inFlight }
    }

    private fun resetSwitchToServerState() {
        val server = state?.accessAllowed ?: return
        syncingSwitch = true
        try {
            accessSwitch.value = server
        } finally {
            syncingSwitch = false
        }
    }

    private fun onSwitchToggled(checked: Boolean) {
        if (inFlight) {
            resetSwitchToServerState()
            return
        }
        if (checked) {
            launchWrite(button = null, enabling = true) { rpc.setAllowed(true) }
        } else {
            // Never flip optimistically: show the server state (on) again first, switch off only after the dialog.
            resetSwitchToServerState()
            requestDisable()
        }
    }

    private fun requestDisable() {
        val connected = state?.connections?.isNotEmpty() == true
        val lines =
            listOf(
                if (connected) tr("Alle bestehenden Verbindungen werden sofort getrennt.") else tr("Derzeit ist kein Agent verbunden."),
                tr("Bereits erstellte Entwürfe bleiben erhalten."),
            )
        confirm.show(
            tr("KI-Zugang ausschalten"),
            tr("Möchten Sie den KI-Zugang wirklich ausschalten?"),
            tr("Ausschalten"),
            lines,
            if (connected) tr("Laufende Agentensitzungen enden sofort.") else null,
        ) { launchWrite(button = null, enabling = false) { rpc.setAllowed(false) } }
    }

    private fun requestRevoke(
        tokenId: String,
        button: Button,
    ) {
        if (inFlight) return
        confirm.show(
            tr("Verbindung widerrufen"),
            tr("Dieser Agent verliert sofort den Zugriff auf Ihr Konto."),
            tr("Widerrufen"),
            emptyList(),
            null,
        ) { launchWrite(button = button, enabling = false) { rpc.revoke(tokenId) } }
    }

    /**
     * The single write path. [inFlight] is taken SYNCHRONOUSLY (a second click or a second `onConfirm` before the coroutine has even
     * started is a no-op); the clicked [button], if any, is locked by [runGuardedAction].
     */
    private fun launchWrite(
        button: Button?,
        enabling: Boolean,
        call: suspend () -> McpAccessStateDto,
    ) {
        if (inFlight || button?.disabled == true) return
        inFlight = true
        runGuardedAction(button) {
            try {
                write(enabling = enabling, call = call)
            } finally {
                inFlight = false
                applyEnabled()
            }
        }
        applyEnabled()
    }

    /** Calls the server and renders ONLY its answer; every failure becomes a fixed sentence (never the server's text) plus a re-read. */
    private suspend fun write(
        enabling: Boolean,
        call: suspend () -> McpAccessStateDto,
    ) {
        statusLine.content = tr("Wird gespeichert…")
        statusLine.show()
        try {
            render(call())
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpFeatureDisabledException) {
            hide()
        } catch (e: UnauthenticatedException) {
            sessionExpired()
        } catch (e: ForbiddenException) {
            // Only switching ON can be turned away by the rate limit; anywhere else this is just "could not save".
            toast(
                if (enabling) {
                    tr(
                        "Bitte versuchen Sie es in einigen Minuten erneut.",
                    )
                } else {
                    tr("Die Änderung konnte nicht gespeichert werden.")
                },
            )
            reload()
        } catch (e: Throwable) {
            toast(tr("Die Änderung konnte nicht gespeichert werden."))
            reload()
        } finally {
            statusLine.hide()
        }
    }
}

/**
 * Mounts the card into [container] -- only for an organization member of a server whose MCP layer is operational. Anyone else gets no
 * request and no card (the server would turn a non-member away with a plain "forbidden", which must not become a toast).
 */
internal fun renderMcpAccessSection(
    container: SimplePanel,
    rpc: McpAccessRpc = liveMcpAccessRpc(),
) {
    val session = AppState.session ?: return
    if (!session.mcpEnabled || session.status !in MemberStatusSets.ORGANIZATION_MEMBER) return
    val card = McpAccessCard(parent = container, rpc = rpc)
    AppScope.launch { card.reload() }
}

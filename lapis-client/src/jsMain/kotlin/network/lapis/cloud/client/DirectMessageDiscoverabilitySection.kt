package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.html.ButtonStyle
import io.kvision.html.H2
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.DirectMessageBlockDto
import network.lapis.cloud.shared.rpc.IDirectMessageService

/** The RPC surface of the "Auffindbarkeit" section -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface DirectMessageDiscoverabilityRpc {
    suspend fun getVisibility(): Boolean

    suspend fun setVisibility(visible: Boolean)

    suspend fun listBlocks(): List<DirectMessageBlockDto>

    suspend fun unblock(blockId: String)
}

internal fun liveDirectMessageDiscoverabilityRpc(): DirectMessageDiscoverabilityRpc =
    object : DirectMessageDiscoverabilityRpc {
        override suspend fun getVisibility() = rpcService<IDirectMessageService>().getRecipientSearchVisibility()

        override suspend fun setVisibility(visible: Boolean) = rpcService<IDirectMessageService>().setRecipientSearchVisibility(visible)

        override suspend fun listBlocks() = rpcService<IDirectMessageService>().listMessageBlocks()

        override suspend fun unblock(blockId: String) = rpcService<IDirectMessageService>().unblockMessageSender(blockId)
    }

/**
 * V1.9.237 -- end of the Nachrichten page: "Auffindbarkeit" (whether the person shows up in the name search for new messages; saved at once,
 * rolled back with an error toast if the server refuses) and the list of the people the viewer blocked, each with "Aufheben". A blocked person
 * appears by name only where a conversation exists, otherwise as "Mitglied" -- the server never hands out a name for blocks from the finder.
 */
internal fun renderDirectMessageDiscoverabilitySection(
    root: SimplePanel,
    rpc: DirectMessageDiscoverabilityRpc = liveDirectMessageDiscoverabilityRpc(),
) {
    root.add(H2(content = tr("Auffindbarkeit"), className = "h5 mt-3"))
    root.div(
        tr(
            "Wenn Sie das ausschalten, erscheinen Sie nicht in der Namenssuche für neue Nachrichten. Wer Ihre Kennung kennt, kann Ihnen weiterhin schreiben.",
        ),
        className = "text-muted small",
    )
    root
        .dataSection<Boolean>(
            isEmpty = { false },
            load = { guarded { rpc.getVisibility() } },
            render = { panel, visible ->
                val form = panel.lapisForm()
                val field = form.checkField(label = tr("In der Empfängersuche für neue Nachrichten erscheinen"), value = visible)
                var current = visible
                var rollingBack = false
                field.subscribe { raw ->
                    if (rollingBack) return@subscribe
                    val wanted = raw.toBoolean()
                    if (wanted == current) return@subscribe
                    val previous = current
                    current = wanted
                    AppScope.launch {
                        val saved = guarded { rpc.setVisibility(wanted) }
                        if (saved == null) {
                            current = previous
                            rollingBack = true
                            (field.control as? CheckBox)?.value = previous
                            rollingBack = false
                        }
                    }
                }
            },
        ).reload()
    root.add(H2(content = tr("Blockierte Personen"), className = "h5 mt-3"))
    lateinit var blocks: DataSection
    blocks =
        root.dataSection<List<DirectMessageBlockDto>>(
            emptyText = tr("Sie haben niemanden blockiert."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpc.listBlocks() } },
            render = { panel, list ->
                list.forEach { block ->
                    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center border-bottom py-1") }
                    row.span(block.displayName.takeIf { it.isNotBlank() }?.let(::sanitizeUntrustedI18nText) ?: tr("Mitglied"))
                    row.span(formatSystemDateTime(block.createdAt), className = "text-muted small ms-auto")
                    val lift = newActionButton(ActionIcon.CANCEL, tr("Aufheben"), ButtonStyle.OUTLINESECONDARY, small = true)
                    lift.onClick {
                        AppScope.launch {
                            if (guarded { rpc.unblock(block.blockId) } != null) {
                                notifySuccess(tr("Die Sperre wurde aufgehoben."))
                                blocks.reload()
                            }
                        }
                    }
                    row.add(lift)
                }
            },
        )
    blocks.reload()
}

package network.lapis.cloud.client

import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.LupaBbApplyResultDto
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewPageDto
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto
import network.lapis.cloud.shared.rpc.ILupaBbSyncService

/**
 * V1.9.226 "LupaBB-Abgleich" -- `/members/lupabb-sync`, ADMIN only (the server gates every call). A manual, one-way comparison of the
 * members with the data of LupaBB (the former member administration): credentials, then a preview, then "Übernehmen". Reached from the button
 * "Mit LupaBB abgleichen" on the member administration; there is no sidebar entry of its own.
 *
 * The screen owns the RPC reads (the credentials block is a `dataSection`: one loading text, one error with "Erneut versuchen"); the preview
 * block gets its four calls through [LupaSyncApi].
 */
fun renderLupaSyncScreen(container: SimplePanel) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.overviewBackButton(Routes.MEMBERS, tr("Zur Mitgliederverwaltung"))
    root.pageHeader(
        tr("LupaBB-Abgleich"),
        subtitle = tr("Mitglieder-Stammdaten aus LupaBB in die Mitgliederverwaltung übernehmen. Ein Lauf wird von Hand gestartet."),
    )
    var status: LupaBbSyncStatusDto? = null
    lateinit var credentials: DataSection
    lateinit var preview: LupaSyncPreview
    credentials =
        root.dataSection<LupaBbSyncStatusDto>(
            isEmpty = { false },
            load = { guarded { rpcService<ILupaBbSyncService>().getStatus() } },
            onSettled = { loaded ->
                status = loaded
                preview.paint()
            },
            render = { panel, loaded -> renderLupaSyncCredentials(panel, loaded) { credentials.reload() } },
        )
    preview =
        LupaSyncPreview(
            root = root,
            api = LiveLupaSyncApi,
            statusProvider = { status },
            onApplied = { credentials.reload() },
        )
    credentials.reload()
}

/** The real calls of the preview block. */
private object LiveLupaSyncApi : LupaSyncApi {
    override suspend fun loadPreview(): LupaBbPreviewSummaryDto = rpcService<ILupaBbSyncService>().loadPreview()

    override suspend fun previewPage(
        token: String,
        filter: LupaBbPreviewFilter,
        offset: Int,
        limit: Int,
    ): LupaBbPreviewPageDto = rpcService<ILupaBbSyncService>().getPreviewPage(token, filter, offset, limit)

    override suspend fun apply(token: String): LupaBbApplyResultDto = rpcService<ILupaBbSyncService>().applyPreview(token)

    override suspend fun link(
        token: String,
        personNumber: Long,
        memberId: String,
    ): LupaBbPreviewPageDto = rpcService<ILupaBbSyncService>().linkMemberToLupaBb(token, personNumber, memberId)
}

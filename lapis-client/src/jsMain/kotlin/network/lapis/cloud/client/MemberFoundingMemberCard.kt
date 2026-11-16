package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.FoundingMemberRules
import network.lapis.cloud.shared.domain.FoundingMemberVisibility
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OwnFoundingMemberDto
import network.lapis.cloud.shared.rpc.FoundingMemberConsentOutdatedException
import network.lapis.cloud.shared.rpc.IMemberService

/** The RPC surface of the card -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface FoundingMemberRpc {
    suspend fun getCurrentMember(): MemberDto

    suspend fun setVisibility(
        visibility: FoundingMemberVisibility,
        consentTextVersion: String?,
    ): OwnFoundingMemberDto
}

internal fun liveFoundingMemberRpc(): FoundingMemberRpc =
    object : FoundingMemberRpc {
        override suspend fun getCurrentMember() = rpcService<IMemberService>().getCurrentMember()

        override suspend fun setVisibility(
            visibility: FoundingMemberVisibility,
            consentTextVersion: String?,
        ) = rpcService<IMemberService>().setOwnFoundingMemberVisibility(visibility, consentTextVersion)
    }

/**
 * V154 -- "Meine Daten": the member's own consent to show the founding-member label publicly. Rendered only for a member the board has marked.
 * The consent wording is [FoundingMemberRules.CONSENT_TEXT], shown VERBATIM and untranslated on purpose: it is the versioned text the server
 * records ([FoundingMemberRules.CONSENT_TEXT_VERSION]); `FoundingMemberRulesTest` pins it. The switch is never flipped optimistically: the
 * server answer decides, and a failed call puts it back.
 */
internal class MemberFoundingMemberCard(
    private val parent: SimplePanel,
    private val rpc: FoundingMemberRpc,
    private val onChanged: () -> Unit,
    private val toastError: (String) -> Unit = { notifyError(it) },
    private val toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    internal lateinit var publicSwitch: CheckBox
        private set

    fun render(dto: MemberDto) {
        if (!dto.foundingMember) return
        val root = parent.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
        root.h2(tr("Gründungsmitglied")) { addCssClass("h5") }
        root.div(tr("Der Vorstand hat Sie als Gründungsmitglied gekennzeichnet. Die Kennzeichnung steht auf Ihrem Mitgliedsausweis.")) {
            addCssClasses("text-muted small")
        }
        val consentText = FoundingMemberRules.CONSENT_TEXT
        root.div(consentText) { addCssClasses("small") }
        val switch = CheckBox(value = dto.foundingMemberPublic, label = tr("Als Gründungsmitglied öffentlich zeigen"))
        publicSwitch = switch
        root.add(switch)
        switch.subscribe { checked ->
            // The host section is re-rendered from the server after every call, so a notification that merely echoes the server state is ignored.
            if (checked == dto.foundingMemberPublic) return@subscribe
            switch.disabled = true
            runGuardedAction(button = null) {
                try {
                    val visibility = if (checked) FoundingMemberVisibility.PUBLIC else FoundingMemberVisibility.PRIVATE
                    val version = if (checked) FoundingMemberRules.CONSENT_TEXT_VERSION else null
                    rpc.setVisibility(visibility, version)
                    toastSuccess(if (checked) tr("Ihre Einwilligung wurde gespeichert.") else tr("Ihre Einwilligung wurde widerrufen."))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FoundingMemberConsentOutdatedException) {
                    toastError(tr("Der Hinweistext wurde aktualisiert. Bitte erneut bestätigen."))
                } catch (e: Throwable) {
                    guarded<Unit> { throw e }
                } finally {
                    onChanged()
                }
            }
        }
    }
}

/** Mounts the card on "Meine Daten" (not for a guest session). Renders nothing for a member who is not marked. */
internal fun renderMemberFoundingMemberSection(
    container: SimplePanel,
    rpc: FoundingMemberRpc = liveFoundingMemberRpc(),
) {
    val session = AppState.session ?: return
    if (session.isGuest) return
    lateinit var section: DataSection
    section =
        container.dataSection<MemberDto>(
            isEmpty = { false },
            load = { guarded { rpc.getCurrentMember() } },
            render = { panel, dto -> MemberFoundingMemberCard(panel, rpc, onChanged = { section.reload() }).render(dto) },
        )
    section.reload()
}

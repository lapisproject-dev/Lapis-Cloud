package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextDraftLookupDto
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextVersionDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto

/**
 * V1.9.134 -- administration of the registration texts (membership agreement, FRIEND terms). Every method requires the ADMIN role. A
 * published version is immutable; a change is a new version. The public fetch stays on [IRegistrationService].
 */
@RpcService
interface IAgreementTextAdminService {
    suspend fun getOverview(): AgreementTextOverviewDto

    suspend fun getDraft(kind: AgreementTextKind): AgreementTextDraftLookupDto

    /** Creates or overwrites the draft of `input.kind`. */
    suspend fun saveDraft(input: AgreementTextDraftInput): AgreementTextDraftDto

    /**
     * Publishes the stored draft as a new version. [expectedDraftSha256] must equal the draft's hash, so exactly the text the admin
     * previewed is published (optimistic lock against a second admin).
     */
    suspend fun publishDraft(
        kind: AgreementTextKind,
        expectedDraftSha256: String,
    ): AgreementTextVersionSummaryDto

    suspend fun listVersions(kind: AgreementTextKind): List<AgreementTextVersionSummaryDto>

    /** The text of a published version (proof of what a member saw); the label of the built-in placeholder returns the built-in text. */
    suspend fun getVersion(
        kind: AgreementTextKind,
        versionLabel: String,
    ): AgreementTextVersionDto

    /**
     * V1.9.196 -- the sanitized HTML the server renders from a Markdown [body] (live preview of the editor). ADMIN-only, rate limited, stores
     * and logs nothing. The client never renders Markdown itself.
     */
    suspend fun previewAgreementText(
        kind: AgreementTextKind,
        body: String,
    ): String
}

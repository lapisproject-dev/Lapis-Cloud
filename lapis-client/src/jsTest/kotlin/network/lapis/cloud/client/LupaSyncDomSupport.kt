package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LupaBbCandidateDto
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbCredentialsInput
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbFieldDiffDto
import network.lapis.cloud.shared.domain.LupaBbLinkTargetDto
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewPageDto
import network.lapis.cloud.shared.domain.LupaBbPreviewRowDto
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbReasonDto
import network.lapis.cloud.shared.domain.LupaBbRowActionResultDto
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ILupaBbSyncService

/** The fixtures and the RPC stub of the LupaBB sync DOM tests (V1.9.226). */
internal object LupaSyncDomSupport {
    fun session(role: AccountRole = AccountRole.ADMIN) =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    val noCredentials = LupaBbSyncStatusDto(credentialsSet = false)
    val withCredentials =
        LupaBbSyncStatusDto(credentialsSet = true, setAt = LocalDateTime(2026, 10, 1, 9, 30), setByName = "Dana Keller")

    val counts =
        mapOf(
            LupaBbCategory.NEW to 1,
            LupaBbCategory.CHANGED to 1,
            LupaBbCategory.UNCHANGED to 3,
            LupaBbCategory.AMBIGUOUS to 1,
            LupaBbCategory.SKIPPED to 0,
            LupaBbCategory.ERROR to 0,
            LupaBbCategory.MISSING to 0,
        )

    fun summary(statusChanges: Int = 0) =
        LupaBbPreviewSummaryDto(
            token = "token-1",
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            counts = counts,
            newCount = 1,
            changedCount = 1,
            statusChangeCount = statusChanges,
            severeStatusChangeCount = statusChanges,
        )

    val rows =
        listOf(
            LupaBbPreviewRowDto(
                personNumber = 4711,
                name = "Erika Muster",
                email = "erika@example.org",
                category = LupaBbCategory.AMBIGUOUS,
                reasons = listOf(LupaBbReasonDto(LupaBbRowReasonCode.NAME_MISMATCH)),
                candidates = listOf(LupaBbCandidateDto("m-1", "Erika Mustermann", "em@example.org")),
            ),
            LupaBbPreviewRowDto(
                personNumber = 4712,
                name = "Max Neu",
                email = "max@example.org",
                category = LupaBbCategory.CHANGED,
                diffs = listOf(LupaBbFieldDiffDto(LupaBbField.CITY, before = "Alt", after = "Braunschweig")),
            ),
        )

    /** A held-back row of a BOARD member: address and status are in [LupaBbPreviewRowDto.protectedDiffs], the member can be opened. */
    val protectedRows =
        listOf(
            LupaBbPreviewRowDto(
                personNumber = 4801,
                name = "Bruno Vorstand",
                email = "bruno@example.org",
                category = LupaBbCategory.CHANGED,
                memberId = "member-4801",
                reasons = listOf(LupaBbReasonDto(LupaBbRowReasonCode.ADMIN_ROLE_MANUAL)),
                protectedDiffs =
                    listOf(
                        LupaBbFieldDiffDto(LupaBbField.STREET, before = "Alt", after = "Neu 1"),
                        LupaBbFieldDiffDto(LupaBbField.CITY, before = "Alt", after = "Braunschweig"),
                        LupaBbFieldDiffDto(LupaBbField.STATUS, before = MemberStatus.ACTIVE.name, after = MemberStatus.WITHDRAWN.name),
                    ),
            ),
            LupaBbPreviewRowDto(
                personNumber = 4802,
                name = "Dana Keller",
                email = "dana@example.org",
                category = LupaBbCategory.ERROR,
                memberId = "admin-1",
                isOwnRecord = true,
                reasons = listOf(LupaBbReasonDto(LupaBbRowReasonCode.ADMIN_ROLE_MANUAL)),
                protectedDiffs = listOf(LupaBbFieldDiffDto(LupaBbField.STREET, before = "Alt", after = "Neu 2")),
                protectedBlocker = LupaBbRowReasonCode.OWN_RECORD,
            ),
            LupaBbPreviewRowDto(
                personNumber = 4803,
                name = "Admin Zwei",
                email = "zwei@example.org",
                category = LupaBbCategory.CHANGED,
                memberId = "admin-2",
                reasons = listOf(LupaBbReasonDto(LupaBbRowReasonCode.ADMIN_ROLE_MANUAL)),
                protectedDiffs =
                    listOf(
                        LupaBbFieldDiffDto(LupaBbField.STATUS, before = MemberStatus.ACTIVE.name, after = MemberStatus.WITHDRAWN.name),
                    ),
                protectedBlocker = LupaBbRowReasonCode.PEER_APPROVAL_REQUIRED,
            ),
        )

    /** V1.9.303 -- rows of `EMAIL_BELONGS_TO_OTHER`: with the holder named (and a likely double entry), without a likely double entry, anonymised holder. */
    val conflictRows =
        listOf(
            LupaBbPreviewRowDto(
                personNumber = 9_000_000_070,
                name = "Erika Doppel",
                email = "doppel@example.org",
                category = LupaBbCategory.AMBIGUOUS,
                reasons = listOf(LupaBbReasonDto(LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER)),
                conflictMemberId = "member-71",
                conflictMemberName = "Dr. Erika Doppel",
                conflictPersonNumber = 9_000_000_071,
                conflictLikelyDuplicate = true,
            ),
            LupaBbPreviewRowDto(
                personNumber = 9_000_000_072,
                name = "Anna Anders",
                email = "anders@example.org",
                category = LupaBbCategory.AMBIGUOUS,
                reasons = listOf(LupaBbReasonDto(LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER)),
                conflictMemberId = "member-73",
                conflictMemberName = "Jemand Anderes",
                conflictPersonNumber = 9_000_000_073,
            ),
            LupaBbPreviewRowDto(
                personNumber = 9_000_000_074,
                name = "Gelöscht Person",
                email = "weg@example.org",
                category = LupaBbCategory.AMBIGUOUS,
                reasons = listOf(LupaBbReasonDto(LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER)),
            ),
        )

    class Routes(
        val status: String,
        val verify: String,
        val remove: String,
        val load: String,
        val page: String,
        val apply: String,
        val link: String,
        val applyProtected: String,
        val search: String,
        val skip: String,
    )

    suspend fun routes() =
        Routes(
            status = routeOf { rpcService<ILupaBbSyncService>().getStatus() },
            verify = routeOf { rpcService<ILupaBbSyncService>().verifyAndSaveCredentials(LupaBbCredentialsInput("u", "p", "k")) },
            remove = routeOf { rpcService<ILupaBbSyncService>().removeCredentials() },
            load = routeOf { rpcService<ILupaBbSyncService>().loadPreview() },
            page = routeOf { rpcService<ILupaBbSyncService>().getPreviewPage("t", LupaBbPreviewFilter.ALL, 0, 1) },
            apply = routeOf { rpcService<ILupaBbSyncService>().applyPreview("t") },
            link = routeOf { rpcService<ILupaBbSyncService>().linkMemberToLupaBb("t", 1, "m") },
            applyProtected = routeOf { rpcService<ILupaBbSyncService>().applyProtectedRow("t", 1) },
            search = routeOf { rpcService<ILupaBbSyncService>().searchLinkTargets("t", 1, "abc") },
            skip = routeOf { rpcService<ILupaBbSyncService>().skipRow("t", 1) },
        )

    fun answer(
        routes: Routes,
        request: RecordedRequest,
        status: LupaBbSyncStatusDto,
        summary: LupaBbPreviewSummaryDto = summary(),
        pageRows: List<LupaBbPreviewRowDto> = rows,
        targets: List<LupaBbLinkTargetDto> = emptyList(),
    ): StubResponse =
        when {
            !request.isRpc -> StubResponse()
            request.rpcRoute == routes.status -> request.answerWith(jsonOf(LupaBbSyncStatusDto.serializer(), status))
            request.rpcRoute == routes.verify -> request.answerWith(jsonOf(LupaBbSyncStatusDto.serializer(), withCredentials))
            request.rpcRoute == routes.remove -> request.answerWith(jsonOf(LupaBbSyncStatusDto.serializer(), noCredentials))
            request.rpcRoute == routes.load -> request.answerWith(jsonOf(LupaBbPreviewSummaryDto.serializer(), summary))
            request.rpcRoute == routes.page ->
                request.answerWith(jsonOf(LupaBbPreviewPageDto.serializer(), LupaBbPreviewPageDto(pageRows.size, 0, pageRows, counts)))
            request.rpcRoute == routes.search ->
                request.answerWith(jsonOf(ListSerializer(LupaBbLinkTargetDto.serializer()), targets))
            request.rpcRoute == routes.applyProtected ->
                request.answerWith(
                    jsonOf(
                        LupaBbRowActionResultDto.serializer(),
                        LupaBbRowActionResultDto(applied = true, page = LupaBbPreviewPageDto(pageRows.size, 0, pageRows, counts)),
                    ),
                )
            else -> request.answerWith("null")
        }
}

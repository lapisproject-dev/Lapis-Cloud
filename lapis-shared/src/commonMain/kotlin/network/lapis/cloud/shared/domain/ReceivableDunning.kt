package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- a SECOND, fully independent dunning domain
 * for `OpenItemDirection.RECEIVABLE` items. Deliberately its own file, its own tables
 * (`receivable_dunning_level`/`receivable_dunning_notice`), its own service/poller -- see
 * `network.lapis.cloud.server.rpc.ReceivableDunningService`/
 * `network.lapis.cloud.server.openitem.dunning.ReceivableDunningPoller` KDoc for why the
 * pre-existing member-contribution dunning domain (`Dunning.kt`/`DunningService`/`DunningPoller`,
 * V1.2.7) is NOT extended/polymorphised for this. No PDF, no postal dispatch, no compliance
 * disclaimer in this wave -- see `docs/architecture/open-items.adoc` "Deferred/out of scope".
 */
@Serializable
data class ReceivableDunningLevelDto(
    val id: String,
    val levelNumber: Int,
    val name: String,
    val graceDays: Int,
    val responseDays: Int,
    val feeAmount: Decimal? = null,
    val active: Boolean = true,
)

@Serializable
data class ReceivableDunningLevelInput(
    val levelNumber: Int,
    val name: String,
    val graceDays: Int,
    val responseDays: Int,
    val feeAmount: Decimal? = null,
    val active: Boolean = true,
)

@Serializable
data class ReceivableDunningNoticeDto(
    val id: String,
    val openItemId: String,
    val receivableDunningLevelId: String,
    val cycleNumber: Int,
    val levelNumber: Int,
    val levelName: String,
    val feeAmount: Decimal? = null,
    val amountDue: Decimal,
    val status: ReceivableDunningNoticeStatus,
    val issuedAt: LocalDateTime,
    val respondBy: LocalDate,
    val documentId: String? = null,
    val feeJournalEntryId: String? = null,
    val createdByMemberId: String? = null,
    val cancelledAt: LocalDateTime? = null,
    val cancellationReason: String? = null,
)

@Serializable
data class ReceivableDunningSettingsDto(
    val receivableDunningEnabled: Boolean = false,
    val pollerEnabled: Boolean = false,
    val activeLevelCount: Int = 0,
)

/** Structured payload for an [AuditEntityType.RECEIVABLE_DUNNING_NOTICE] audit entry. */
@Serializable
data class ReceivableDunningNoticeSnapshot(
    val noticeId: String,
    val openItemId: String,
    val levelNumber: Int,
    val status: ReceivableDunningNoticeStatus,
    val feeJournalEntryId: String? = null,
)

/**
 * Reserved for a future `ReceivableDunningLevel` audit trail -- **not currently written anywhere**.
 * Level CRUD is not audited in this wave, same "no build-ahead-of-need" restraint
 * [AuditEntityType.PAYMENT_TRANSACTION]'s own KDoc documents for its own case: there is no
 * dedicated [AuditEntityType] literal for level configuration changes, only for notices
 * ([AuditEntityType.RECEIVABLE_DUNNING_NOTICE]). A future wave can add one if that turns out to be
 * needed.
 */
@Serializable
data class ReceivableDunningLevelSnapshot(
    val levelId: String,
    val levelNumber: Int,
    val name: String,
    val active: Boolean,
)

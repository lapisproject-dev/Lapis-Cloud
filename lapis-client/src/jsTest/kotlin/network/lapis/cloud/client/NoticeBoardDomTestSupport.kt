package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.domain.NoticePageDto
import network.lapis.cloud.shared.domain.NoticeStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement

internal fun noticeSession(
    status: MemberStatus = MemberStatus.ACTIVE,
    role: AccountRole = AccountRole.MEMBER,
) = SessionInfoDto(
    memberId = "member-1",
    displayName = "Dana Keller",
    role = role,
    status = status,
    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
)

internal fun noticeDto(
    id: String,
    title: String = "Titel-$id",
    category: NoticeCategory = NoticeCategory.OFFER,
    mine: Boolean = false,
    weight: String = "0.00",
    pinned: Boolean = false,
    canReply: Boolean = true,
    canModerate: Boolean = false,
    status: NoticeStatus = NoticeStatus.ACTIVE,
    daysLeft: Int = 10,
    body: String = "Text-$id",
    categoryLocked: Boolean = false,
) = NoticeDto(
    id = id,
    category = category,
    title = title,
    body = body,
    authorDisplayName = if (mine) "Dana Keller" else "Vera Vorstand",
    isMine = mine,
    status = status,
    createdAt = LocalDateTime(2026, 11, 1, 10, 0),
    updatedAt = null,
    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
    expiresOn = LocalDate(2098, 12, 31),
    daysLeft = daysLeft,
    minExpiresOn = LocalDate(2026, 11, 10),
    maxExpiresOn = LocalDate(2027, 1, 30),
    pinnedAt = if (pinned) LocalDateTime(2026, 11, 2, 10, 0) else null,
    weightLtr = weight.toDouble(),
    canEdit = mine && status == NoticeStatus.ACTIVE,
    canWithdraw = mine && status == NoticeStatus.ACTIVE,
    canBoost = status == NoticeStatus.ACTIVE,
    canReply = canReply && !mine && status == NoticeStatus.ACTIVE,
    canModerate = canModerate && status == NoticeStatus.ACTIVE,
    categoryLocked = categoryLocked,
)

internal fun noticePage(
    items: List<NoticeDto> = emptyList(),
    pinned: List<NoticeDto> = emptyList(),
    total: Int = items.size,
) = NoticePageDto(
    pinned = pinned,
    items = items,
    total = total,
    offset = 0,
    myActiveCount = 0,
    defaultExpiresOn = LocalDate(2026, 12, 10),
    minExpiresOn = LocalDate(2026, 11, 10),
    maxExpiresOn = LocalDate(2027, 2, 7),
)

internal fun balanceDto(amount: String) = LtrLedgerBalanceDto(memberId = "member-1", freeBalanceLtr = amount.toDouble())

internal fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

internal fun HTMLElement.buttonLike(text: String): HTMLElement? = allOf("button").firstOrNull { it.textContent.orEmpty().trim() == text }

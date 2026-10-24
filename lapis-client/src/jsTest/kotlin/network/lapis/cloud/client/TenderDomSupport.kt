package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidDto
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto
import network.lapis.cloud.shared.rpc.ITenderService

/** Fixtures of the V1.9.202 tender screen tests: sessions and DTOs with sensible defaults, one change per test. */
internal fun tenderSession(
    role: AccountRole = AccountRole.MEMBER,
    status: MemberStatus = MemberStatus.ACTIVE,
) = SessionInfoDto(
    memberId = "caller-1",
    displayName = "Vera Vorstand",
    role = role,
    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
    status = status,
)

internal fun tenderService(): ITenderService = rpcService()

internal fun tenderSummary(
    id: String = "t1",
    title: String = "Website neu aufsetzen",
    status: TenderStatus = TenderStatus.PUBLISHED,
    count: Int? = null,
    mine: TenderBidStatus? = null,
    visibility: TenderBidVisibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
    criterion: TenderAwardCriterion = TenderAwardCriterion.LOWEST_PRICE,
) = TenderSummaryDto(
    id = id,
    title = title,
    status = status,
    deadline = LocalDateTime(2099, 11, 3, 11, 0),
    awardCriterion = criterion,
    currency = TenderCurrency.LTR,
    bidVisibility = visibility,
    bindingMotionId = "motion-1",
    visibleBidCount = count,
    myBidStatus = mine,
)

internal fun tenderBid(
    id: String,
    name: String,
    price: Double,
    mine: Boolean = false,
    awardable: Boolean = false,
    status: TenderBidStatus = TenderBidStatus.SUBMITTED,
    text: String = "Ich mache das.",
) = TenderBidDto(
    id = id,
    tenderId = "t1",
    bidderDisplayName = name,
    price = price.toDecimal(),
    currency = TenderCurrency.LTR,
    text = text,
    status = status,
    submittedAt = LocalDateTime(2099, 10, 30, 10, 0),
    priceSetAt = LocalDateTime(2099, 10, 30, 10, 0),
    isMine = mine,
    awardable = awardable,
)

internal fun tenderDetail(
    summary: TenderSummaryDto = tenderSummary(),
    block: TenderDetailDto.() -> TenderDetailDto = { this },
): TenderDetailDto =
    TenderDetailDto(
        tender = summary,
        description = "Die Webseite wird neu aufgesetzt.",
        expectedResult = "Eine lauffaehige Webseite.",
        bindingMotionTitle = "Haushalt 2099",
    ).block()

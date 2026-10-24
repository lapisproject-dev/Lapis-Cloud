package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.TenderService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderDraftUpdateInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.RateLimitedException
import kotlin.time.Duration.Companion.minutes

/** V1.9.202 -- a Markdown description is rendered by the SERVER to sanitized HTML; the client never renders Markdown itself. */
class TenderDescriptionFormatTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("MARKDOWN is stored and shipped as sanitized HTML; PLAIN ships no HTML at all") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    val evil = "**fett** <script>alert(1)</script> [Klick](javascript:alert(1)) ![x](http://evil.example/p.png)"
                    val created =
                        tender(member = board) {
                            createTender(
                                createInput(
                                    binding = binding,
                                ).copy(description = evil, descriptionFormat = TenderDescriptionFormat.MARKDOWN),
                            )
                        }
                    created.descriptionFormat shouldBe TenderDescriptionFormat.MARKDOWN
                    val html = created.descriptionHtml!!
                    html.contains("<strong>fett</strong>") shouldBe true
                    html.contains("<script") shouldBe false
                    html.contains("javascript:") shouldBe false
                    html.contains("<img") shouldBe false
                    created.description shouldBe evil
                    val plain = tender(member = board) { createTender(createInput(binding = binding)) }
                    plain.descriptionFormat shouldBe TenderDescriptionFormat.PLAIN
                    plain.descriptionHtml shouldBe null
                }
            }
        }

        test("a published tender shows the rendered HTML to a friend as well; a draft keeps its format when updated") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
                    val binding = fx.approvedMotion()
                    val draft =
                        tender(member = board) {
                            createTender(
                                createInput(
                                    binding = binding,
                                ).copy(description = "*a*\r\nb", descriptionFormat = TenderDescriptionFormat.MARKDOWN),
                            )
                        }
                    draft.description shouldBe "*a*\nb" // normalised like the preview, before the length check
                    val updated =
                        tender(member = board) {
                            updateTenderDraft(
                                tenderId = draft.tender.id,
                                input =
                                    TenderDraftUpdateInput(
                                        bindingMotionId = binding.toString(),
                                        title = "Neu",
                                        description = "# Titel",
                                        expectedResult = "x",
                                        deadline = TENDER_DEADLINE,
                                        awardCriterion = draft.tender.awardCriterion,
                                        descriptionFormat = TenderDescriptionFormat.MARKDOWN,
                                    ),
                            )
                        }
                    updated.descriptionHtml!!.contains("<h2") shouldBe true // headings are shifted
                    tender(member = board) { publishTender(draft.tender.id) }
                    tender(member = friend) { getTender(draft.tender.id) }.descriptionHtml!!.contains("<h2") shouldBe true
                }
            }
        }

        test("a description that cannot be rendered is never stored or published") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val nested = ">".repeat(9_900)
                    val refused =
                        tenderAttempt(member = board) {
                            createTender(
                                createInput(
                                    binding = fx.approvedMotion(),
                                ).copy(description = nested, descriptionFormat = TenderDescriptionFormat.MARKDOWN),
                            )
                        }.exceptionOrNull()
                    refused.shouldBeInstanceOf<ConflictException>()
                    // as PLAIN the same text is just text
                    tenderAttempt(
                        member = board,
                    ) { createTender(createInput(binding = fx.approvedMotion()).copy(description = nested)) }.isSuccess shouldBe
                        true
                }
            }
        }

        test("the preview: board only, at most 10000 characters, rate-limited, the same pipeline") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val limiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes)

                    suspend fun preview(text: String) =
                        attempt(member = board) { TenderService(call = it, previewRateLimiter = limiter).previewTenderDescription(text) }
                    preview(text = "**x**").getOrThrow().contains("<strong>x</strong>") shouldBe true
                    preview(text = "x".repeat(10_001)).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                    preview(text = ">".repeat(9_900)).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                    preview(text = "again").exceptionOrNull().shouldBeInstanceOf<RateLimitedException>()
                    // roles
                    listOf(
                        fx.member(label = "plain"),
                        fx.member(label = "treasurer", role = AccountRole.TREASURER),
                        fx.member(label = "friend", status = MemberStatus.FRIEND),
                    ).forEach {
                        tenderAttempt(
                            member = it,
                        ) { previewTenderDescription("x") }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                }
            }
        }
    })

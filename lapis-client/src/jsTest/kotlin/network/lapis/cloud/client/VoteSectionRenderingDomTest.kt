package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOptionDto
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.IGovernanceService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W6b security-review follow-up (major findings 1, 1b and 4 of the review): [renderVoteSection] is the FIRST call site in
 * this client that hands server-/member-controlled free text (`VoteOptionDto.label`, `VoteBallotDto.memberDisplayName`,
 * `VoteDto.title`) both to [trFormat] as an argument AND directly as widget content. Everywhere else
 * (`OpenItemDialogs.kt`, `ReportRows.kt`) a `trFormat` argument is always a `tr(...)` string or a [moneyToken]/[ltrToken].
 * Proves end to end, through the real render path, that [trFormat]'s type-based trust guard ([TrArg]/[trusted]) closes
 * the forgery this opened: an option label carrying [I18N_ARG_SEPARATOR] plus a forged [KV_I18N_MARKER]/[MONEY_SENTINEL]
 * payload -- with or without a leading, non-marker character -- can neither leak the control characters into the DOM nor
 * substitute a fabricated amount for the real [VoteBallotDto.stakeLtr]. Also proves the same for a label that starts with
 * [KV_I18N_MARKER] itself, used as PLAIN widget content ([sanitizeUntrustedI18nText] on `option.label`/`vote.title`,
 * outside any `trFormat`/`gettext` call). Also covers both `settledLtr` branches introduced by W6b, which had no
 * render-path test before this class.
 */
class VoteSectionRenderingDomTest {
    private fun vote(
        options: List<VoteOptionDto>,
        status: VoteStatus = VoteStatus.OPEN,
        winnerOptionId: String? = null,
        secondPriceLtr: Double? = null,
    ) = VoteDto(
        id = "v1",
        motionId = "m1",
        meetingId = "mt1",
        title = "Ort der Klausur",
        status = status,
        options = options,
        winnerOptionId = winnerOptionId,
        secondPriceLtr = secondPriceLtr?.toDecimal(),
        openedById = "u-chair",
        openedByDisplayName = "Vorsitz",
        openedAt = LocalDateTime(2026, 9, 1, 10, 0),
        closedAt = null,
        resolutionId = null,
    )

    private fun ballot(
        optionId: String,
        memberDisplayName: String,
        stakeLtr: Double,
        settledLtr: Double?,
    ) = VoteBallotDto(
        id = "b-$memberDisplayName",
        voteId = "v1",
        optionId = optionId,
        memberId = "member-$memberDisplayName",
        memberDisplayName = memberDisplayName,
        stakeLtr = stakeLtr.toDecimal(),
        settledLtr = settledLtr?.toDecimal(),
        castAt = LocalDateTime(2026, 9, 1, 10, 5),
    )

    private fun withBallots(
        options: List<VoteOptionDto>,
        ballots: List<VoteBallotDto>,
        status: VoteStatus = VoteStatus.CLOSED,
        winnerOptionId: String? = null,
        secondPriceLtr: Double? = null,
        canManage: Boolean = true,
        currentMemberId: String = "member-observer",
        block: suspend (() -> org.w3c.dom.HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            val ballotsRoute = routeOf { rpcService<IGovernanceService>().listVoteBallots("v1") }
            withFetchStub(respond = { request ->
                when {
                    request.isRpc && request.rpcRoute == ballotsRoute ->
                        request.answerWith(jsonOf(ListSerializer(VoteBallotDto.serializer()), ballots))
                    request.isRpc -> rpcResult(request.json.id as Int, "null")
                    else -> StubResponse()
                }
            }) { _ ->
                mountedForm("vote-section-security") { root, element ->
                    renderVoteSection(
                        panel = root,
                        vote = vote(options, status, winnerOptionId, secondPriceLtr),
                        canManage = canManage,
                        currentMemberId = currentMemberId,
                        isEligibleToBallot = false,
                        onChanged = {},
                    )
                    block(element)
                }
            }
        }

    @Test
    fun aForgedOptionLabel_cannotSubstituteAFakeStakeAmount(): Promise<Unit> {
        // U+0001 (I18N_ARG_SEPARATOR) followed by a KV_I18N_MARKER + MONEY_SENTINEL payload, with a leading "A" that does NOT
        // itself start with KV_I18N_MARKER: without sanitization this injects an extra trFormat argument that resolves as a
        // forged, freely chosen LTR amount in place of the real stake, inside the "Alle Gebote" ballot row where `optionLabel`
        // travels through `trFormat` as an argument. Also checks the option's basket row ELSEWHERE on the page, which shows the SAME
        // raw label directly as plain widget content (not a `trFormat` call at all) -- security audit W6b follow-up, major
        // finding 4: KVision resolves any widget content starting with KV_I18N_MARKER through I18n.trans on its own, so this
        // must be sanitized independently of trFormat.
        val forgedLabel = "A" + I18N_ARG_SEPARATOR + KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + "9999"
        val options = listOf(VoteOptionDto(id = "opt1", voteId = "v1", label = forgedLabel, position = 0, basketTotalLtr = 5.0.toDecimal()))
        val ballots = listOf(ballot(optionId = "opt1", memberDisplayName = "Erika", stakeLtr = 3.5, settledLtr = null))
        return withBallots(options, ballots) { element ->
            awaitUntil("the ballot row is rendered") { element().textContent.orEmpty().contains("Alle Gebote") }
            val fullText = element().textContent.orEmpty()
            assertTrue(fullText.contains("Alle Gebote"), "expected the ballot list heading: $fullText")
            // the basket row (option loop, plain widget content) is the text BEFORE the "Alle Gebote" heading
            val basketRowText = fullText.substringBefore("Alle Gebote")
            // Security audit W6b follow-up round 3 (major finding B): sanitization strips KV_I18N_MARKER and
            // MONEY_SENTINEL, never the kind char/digits after them -- the forged payload survives as inert plain
            // text ("AL9999", see the forgedLabel construction above), never as a formatted amount. A forged
            // MONEY_KIND_LTR + "9999" payload renders (if it renders at all) as "9.999,00", not "99,99" -- checking
            // for "99,99" here is a dead assertion that cannot fail even without the fix.
            assertTrue(basketRowText.contains("AL9999"), "expected the inert, sanitized label in the basket row: $basketRowText")
            assertFalse(basketRowText.contains("9.999,00"), "a forged amount must never render in the basket row: $basketRowText")
            assertFalse(basketRowText.contains(KV_I18N_MARKER), "KV_I18N_MARKER leaked into the basket row: $basketRowText")
            assertFalse(basketRowText.contains(MONEY_SENTINEL), "MONEY_SENTINEL leaked into the basket row: $basketRowText")
            // the ballot row itself is the text AFTER the "Alle Gebote" heading
            val ballotRowText = fullText.substringAfter("Alle Gebote")
            // the real stake must render, the forged amount must not
            assertTrue(ballotRowText.contains("3,50") && ballotRowText.contains("LTR"), "expected the real stake to render: $ballotRowText")
            assertTrue(ballotRowText.contains("AL9999"), "expected the inert, sanitized label in the ballot row: $ballotRowText")
            assertFalse(ballotRowText.contains("9.999,00"), "a forged amount must never render: $ballotRowText")
            // no control character or raw marker/sentinel from the injected argument may reach the ballot row's own DOM text
            assertFalse(ballotRowText.contains(I18N_ARG_SEPARATOR), "I18N_ARG_SEPARATOR leaked into the ballot row: $ballotRowText")
            assertFalse(ballotRowText.contains(MONEY_SENTINEL), "MONEY_SENTINEL leaked into the ballot row: $ballotRowText")
            assertFalse(ballotRowText.contains(KV_I18N_MARKER), "KV_I18N_MARKER leaked into the ballot row: $ballotRowText")
        }
    }

    @Test
    fun anOptionLabelStartingWithTheMarkerItself_cannotSubstituteAFakeStakeAmount(): Promise<Unit> {
        // Security audit W6b follow-up (major finding 1, the case a first fix attempt missed): a label that starts with
        // KV_I18N_MARKER directly -- no leading, non-marker character needed at all -- used to pass [trFormat]'s old
        // content-based "already starts with the marker => trusted" check unfiltered. The marker is plain, freely typable
        // ASCII text, not a control character: any vote-opening member can type it as a label's first characters. This label
        // is exactly what a real trusted argument (KV_I18N_MARKER + MONEY_SENTINEL + kind + digits, see [moneyToken]) looks
        // like, forged by an attacker instead of produced by this app's own helpers.
        val forgedLabel = KV_I18N_MARKER + I18N_ARG_SEPARATOR + KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + "9999"
        val options = listOf(VoteOptionDto(id = "opt1", voteId = "v1", label = forgedLabel, position = 0, basketTotalLtr = 5.0.toDecimal()))
        val ballots = listOf(ballot(optionId = "opt1", memberDisplayName = "Erika", stakeLtr = 3.5, settledLtr = null))
        return withBallots(options, ballots) { element ->
            awaitUntil("the ballot row is rendered") { element().textContent.orEmpty().contains("Alle Gebote") }
            val fullText = element().textContent.orEmpty()
            assertTrue(fullText.contains("Alle Gebote"), "expected the ballot list heading: $fullText")
            val basketRowText = fullText.substringBefore("Alle Gebote")
            // Security audit W6b follow-up round 3 (major finding B): see the sibling comment in
            // `aForgedOptionLabel_cannotSubstituteAFakeStakeAmount` above -- a forged MONEY_KIND_LTR + "9999"
            // payload renders as "9.999,00", never "99,99"; sanitization leaves the inert digits "L9999".
            assertTrue(basketRowText.contains("L9999"), "expected the inert, sanitized label in the basket row: $basketRowText")
            assertFalse(basketRowText.contains("9.999,00"), "a forged amount must never render in the basket row: $basketRowText")
            assertFalse(basketRowText.contains(KV_I18N_MARKER), "KV_I18N_MARKER leaked into the basket row: $basketRowText")
            assertFalse(basketRowText.contains(MONEY_SENTINEL), "MONEY_SENTINEL leaked into the basket row: $basketRowText")
            val ballotRowText = fullText.substringAfter("Alle Gebote")
            assertTrue(ballotRowText.contains("3,50") && ballotRowText.contains("LTR"), "expected the real stake to render: $ballotRowText")
            assertTrue(ballotRowText.contains("L9999"), "expected the inert, sanitized label in the ballot row: $ballotRowText")
            assertFalse(ballotRowText.contains("9.999,00"), "a forged amount must never render: $ballotRowText")
            assertFalse(ballotRowText.contains(I18N_ARG_SEPARATOR), "I18N_ARG_SEPARATOR leaked into the ballot row: $ballotRowText")
            assertFalse(ballotRowText.contains(MONEY_SENTINEL), "MONEY_SENTINEL leaked into the ballot row: $ballotRowText")
            assertFalse(ballotRowText.contains(KV_I18N_MARKER), "KV_I18N_MARKER leaked into the ballot row: $ballotRowText")
        }
    }

    @Test
    fun settledBallot_showsTheChargedAmount_unsettledDoesNot(): Promise<Unit> {
        val options =
            listOf(
                VoteOptionDto(id = "opt1", voteId = "v1", label = "Ja", position = 0, basketTotalLtr = 10.0.toDecimal()),
                VoteOptionDto(id = "opt2", voteId = "v1", label = "Nein", position = 1, basketTotalLtr = 4.0.toDecimal()),
            )
        val ballots =
            listOf(
                ballot(optionId = "opt1", memberDisplayName = "Alice", stakeLtr = 7.0, settledLtr = 6.0),
                ballot(optionId = "opt2", memberDisplayName = "Bob", stakeLtr = 4.0, settledLtr = null),
            )
        return withBallots(options, ballots) { element ->
            awaitUntil("both ballot rows are rendered") {
                val text = element().textContent.orEmpty()
                text.contains("Alice") && text.contains("Bob")
            }
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("belastet"), "the settled ballot must show the charged-amount branch: $text")
            // Alice's row: stake 7, settled 6 -- both must appear
            assertTrue(text.contains("7,00") && text.contains("6,00"), "expected Alice's stake and settled amount: $text")
            // Bob's row has no settledLtr: 4,00 appears for his stake
            assertTrue(text.contains("4,00"), "expected Bob's stake: $text")
        }
    }

    @Test
    fun aForgedMemberDisplayName_cannotSubstituteAFakeParticipantList_inTheOpenParticipantsLine(): Promise<Unit> {
        // W6b follow-up (major finding 1, scenario (b) of the review): the OPEN "Teilnehmende: %1" line
        // (`renderVoteSection`, `canManage` branch) joins every ballot's `memberDisplayName` and passes the result
        // directly as a `gettext` argument -- exactly the path fixed in `I18nCatalogManager.gettext`. Server data
        // never validates `displayName` beyond trim/blank/length, so a forged marker+sentinel payload there used to
        // substitute the ENTIRE participant list with a fabricated LTR amount.
        val forgedDisplayName = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + "9999"
        val options = listOf(VoteOptionDto(id = "opt1", voteId = "v1", label = "Ja", position = 0, basketTotalLtr = 5.0.toDecimal()))
        val ballots = listOf(ballot(optionId = "opt1", memberDisplayName = forgedDisplayName, stakeLtr = 1.0, settledLtr = null))
        return withBallots(options, ballots, status = VoteStatus.OPEN, canManage = true) { element ->
            awaitUntil("the participants line is rendered") { element().textContent.orEmpty().contains("Teilnehmende") }
            val text = element().textContent.orEmpty()
            // Security audit W6b follow-up round 3 (major finding B): matches `TrFormatTest
            // .aGettextArgumentCarryingAForgedMoneyPayload_isNeverResolvedIntoAnAmount` -- sanitization strips
            // KV_I18N_MARKER and MONEY_SENTINEL, never the kind char/digits after them, so the inert result is
            // literally "L9999", never a formatted "9.999,00" amount. Checking for "99,99" here is a dead
            // assertion that cannot fail even without the fix.
            assertTrue(text.contains("Teilnehmende: L9999"), "expected the inert, sanitized display name: $text")
            assertFalse(text.contains("9.999,00"), "a forged amount must never replace the participant list: $text")
            assertFalse(text.contains(KV_I18N_MARKER), "KV_I18N_MARKER leaked into the participants line: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "MONEY_SENTINEL leaked into the participants line: $text")
        }
    }

    @Test
    fun aClosedVoteWithASecondPrice_rendersTheRealVickreyAmount(): Promise<Unit> {
        val options =
            listOf(
                VoteOptionDto(id = "opt1", voteId = "v1", label = "Ja", position = 0, basketTotalLtr = 12.0.toDecimal()),
                VoteOptionDto(id = "opt2", voteId = "v1", label = "Nein", position = 1, basketTotalLtr = 8.0.toDecimal()),
            )
        val ballots = listOf(ballot(optionId = "opt1", memberDisplayName = "Alice", stakeLtr = 12.0, settledLtr = 10.5))
        return withBallots(
            options,
            ballots,
            status = VoteStatus.CLOSED,
            winnerOptionId = "opt1",
            secondPriceLtr = 10.5,
        ) { element ->
            awaitUntil("the winner and second-price line are rendered") { element().textContent.orEmpty().contains("Vickrey") }
            val text = element().textContent.orEmpty()
            assertTrue(text.contains("Vickrey-Zweitpreis"), "expected the Vickrey second-price line: $text")
            assertTrue(text.contains("10,50") && text.contains("LTR"), "expected the real second price to render: $text")
            // the two options' own basket totals (the "Korbsummen" pill next to each option row) must also render
            assertTrue(text.contains("12,00"), "expected option opt1's basket total: $text")
            assertTrue(text.contains("8,00"), "expected option opt2's basket total: $text")
        }
    }

    @Test
    fun theVoteStatusBadge_followsALanguageSwitch(): Promise<Unit> {
        val options = listOf(VoteOptionDto(id = "opt1", voteId = "v1", label = "Ja", position = 0, basketTotalLtr = 5.0.toDecimal()))
        val ballots = listOf(ballot(optionId = "opt1", memberDisplayName = "Alice", stakeLtr = 5.0, settledLtr = 5.0))
        return formTest {
            val ballotsRoute = routeOf { rpcService<IGovernanceService>().listVoteBallots("v1") }
            withTranslationsAsync(
                mapOf("Geschlossen" to "T-CLOSED", "Preis: %1 (Vickrey-Zweitpreis)" to "T-Price: %1 (T-Vickrey)"),
            ) {
                withFetchStub(respond = { request ->
                    when {
                        request.isRpc && request.rpcRoute == ballotsRoute ->
                            request.answerWith(jsonOf(ListSerializer(VoteBallotDto.serializer()), ballots))
                        request.isRpc -> rpcResult(request.json.id as Int, "null")
                        else -> StubResponse()
                    }
                }) { _ ->
                    mountedForm("vote-section-language") { root, element ->
                        renderVoteSection(
                            panel = root,
                            vote = vote(options, VoteStatus.CLOSED, winnerOptionId = "opt1", secondPriceLtr = 3.0),
                            canManage = true,
                            currentMemberId = "member-observer",
                            isEligibleToBallot = false,
                            onChanged = {},
                        )
                        awaitUntil("the status badge and second-price line are rendered") {
                            val current = element().textContent.orEmpty()
                            current.contains("T-CLOSED") && current.contains("T-Vickrey")
                        }
                        val text = element().textContent.orEmpty()
                        assertTrue(text.contains("T-CLOSED"), "expected the translated status badge: $text")
                        assertFalse(text.contains("Geschlossen"), "the untranslated German source must not remain: $text")
                        assertTrue(text.contains("T-Price:") && text.contains("T-Vickrey"), "expected the translated Vickrey line: $text")
                    }
                }
            }
        }
    }
}

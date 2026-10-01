package network.lapis.cloud.client

import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.CandidacyDto
import network.lapis.cloud.shared.domain.CandidacyInput
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.ElectionBallotCastResultDto
import network.lapis.cloud.shared.domain.ElectionBallotDto
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionBoardMemberDto
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionParticipationDto
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.ReceiptVerificationDto
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLElement

/*
 * V1.9.22 -- the shared harness of the elections DOM tests: the route table of every service method the elections screens call
 * (learned from the compiler-checked method references, see `routeOf`) and a mutable "world" that answers them.
 */

internal const val CONFLICT_EXCEPTION = "network.lapis.cloud.shared.rpc.ConflictException"
internal const val FORBIDDEN_EXCEPTION = "network.lapis.cloud.shared.rpc.ForbiddenException"

/** A 27-character Base64url receipt code, like the server hands out (20 random bytes, unpadded). */
internal const val TEST_RECEIPT = "Abc123_-Abc123_-Abc123_-Abc"

internal class ElectionRoutes(
    val getElection: String,
    val getParticipation: String,
    val listBoard: String,
    val listCandidacies: String,
    val getMotion: String,
    val listCommitteeMembers: String,
    val listCommittees: String,
    val listBallots: String,
    val getResult: String,
    val cast: String,
    val verify: String,
    val approve: String,
    val tally: String,
    val appoint: String,
    val withdraw: String,
    val submitCandidacy: String,
    val openVoting: String,
    val closeVoting: String,
    val abort: String,
    val release: String,
    val listMembers: String,
    val listElections: String,
    val openElection: String,
)

internal suspend fun electionRoutes(): ElectionRoutes =
    ElectionRoutes(
        getElection = routeOf { rpcService<IElectionService>().getElection("x") },
        getParticipation = routeOf { rpcService<IElectionService>().getElectionParticipation("x") },
        listBoard = routeOf { rpcService<IElectionService>().listElectionBoard("x") },
        listCandidacies = routeOf { rpcService<IElectionService>().listCandidacies("x") },
        getMotion = routeOf { rpcService<IGovernanceService>().getMotion("x") },
        listCommitteeMembers = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("x", true) },
        listCommittees = routeOf { rpcService<IGovernanceService>().listCommittees(false) },
        listBallots = routeOf { rpcService<IElectionService>().listElectionBallots("x") },
        getResult = routeOf { rpcService<IElectionService>().getElectionResult("x") },
        cast = routeOf { rpcService<IElectionService>().castElectionBallot(ElectionBallotInput("x")) },
        verify = routeOf { rpcService<IElectionService>().verifyReceipt("x", "y") },
        approve = routeOf { rpcService<IElectionService>().approveTally("x") },
        tally = routeOf { rpcService<IElectionService>().tally("x") },
        appoint = routeOf { rpcService<IElectionService>().appointElectionBoard("x", emptyList()) },
        withdraw = routeOf { rpcService<IElectionService>().withdrawCandidacy("x") },
        submitCandidacy = routeOf { rpcService<IElectionService>().submitCandidacy("x", CandidacyInput()) },
        openVoting = routeOf { rpcService<IElectionService>().openVoting("x") },
        closeVoting = routeOf { rpcService<IElectionService>().closeVoting("x") },
        abort = routeOf { rpcService<IElectionService>().abortElection("x") },
        release = routeOf { rpcService<IElectionService>().releaseCandidateList("x") },
        listMembers = routeOf { rpcService<IMemberService>().listMembers() },
        listElections = routeOf { rpcService<IElectionService>().listElections() },
        openElection =
            routeOf {
                rpcService<IElectionService>().openElection(ElectionOpenInput(motionId = "x", electionType = ElectionType.YES_NO))
            },
    )

/** What the stubbed server currently holds, and how it answers the write calls. Mutable so a test can change the world between reloads. */
internal class ElectionWorld(
    var election: ElectionDto,
    var participation: ElectionParticipationDto = participation(),
    var board: List<ElectionBoardMemberDto> = emptyList(),
    var candidacies: List<CandidacyDto> = emptyList(),
    var motion: MotionDto = motionDto(),
    var roster: List<CommitteeMembershipDto> = emptyList(),
    var committees: List<CommitteeDto> = emptyList(),
    var targetRoster: List<CommitteeMembershipDto> = emptyList(),
    var ballots: List<ElectionBallotDto> = emptyList(),
    var result: ElectionResultDto? = null,
    var members: List<MemberSummaryDto> = emptyList(),
    var elections: List<ElectionDto> = emptyList(),
    /** `route -> fqcn` of an exception to throw for a write route (everything else answers `null`/the world). */
    val failures: MutableMap<String, String> = mutableMapOf(),
    var castResult: ElectionBallotCastResultDto = ElectionBallotCastResultDto(id = "b1", castAt = ELECTION_AT, receiptCode = TEST_RECEIPT),
    var verification: ReceiptVerificationDto = ReceiptVerificationDto(found = true, optionLabel = null),
    var castDelayMs: Int = 0,
    var castNetworkError: Boolean = false,
    var opened: ElectionDto? = null,
    /** Called with the route of EVERY RPC request, before it is answered (a test uses it to change the world on a write). */
    var onRoute: (String) -> Unit = {},
)

internal fun ElectionWorld.respond(routes: ElectionRoutes): (RecordedRequest) -> StubResponse =
    { request ->
        if (!request.isRpc) {
            StubResponse()
        } else {
            val route = request.rpcRoute
            onRoute(route)
            val failure = failures[route]
            when {
                route == routes.cast && castNetworkError -> StubResponse(networkError = true)
                failure != null -> serviceExceptionResult(request.json.id as Int, failure)
                else -> {
                    val json =
                        when (route) {
                            routes.getElection -> jsonOf(ElectionDto.serializer(), election)
                            routes.getParticipation -> jsonOf(ElectionParticipationDto.serializer(), participation)
                            routes.listBoard -> jsonOf(ListSerializer(ElectionBoardMemberDto.serializer()), board)
                            routes.listCandidacies -> jsonOf(ListSerializer(CandidacyDto.serializer()), candidacies)
                            routes.getMotion -> jsonOf(MotionDto.serializer(), motion)
                            routes.listCommitteeMembers ->
                                jsonOf(
                                    ListSerializer(CommitteeMembershipDto.serializer()),
                                    if (request.rpcParam(0) as String == election.targetCommitteeId) targetRoster else roster,
                                )
                            routes.listCommittees -> jsonOf(ListSerializer(CommitteeDto.serializer()), committees)
                            routes.listBallots -> jsonOf(ListSerializer(ElectionBallotDto.serializer()), ballots)
                            routes.getResult -> jsonOf(ElectionResultDto.serializer(), checkNotNull(result) { "no result in the world" })
                            routes.cast -> jsonOf(ElectionBallotCastResultDto.serializer(), castResult)
                            routes.verify -> jsonOf(ReceiptVerificationDto.serializer(), verification)
                            routes.listMembers -> jsonOf(ListSerializer(MemberSummaryDto.serializer()), members)
                            routes.listElections -> jsonOf(ListSerializer(ElectionDto.serializer()), elections)
                            routes.openElection -> jsonOf(ElectionDto.serializer(), opened ?: election)
                            routes.approve, routes.openVoting, routes.closeVoting, routes.abort, routes.release ->
                                jsonOf(ElectionDto.serializer(), election)
                            routes.appoint -> jsonOf(ListSerializer(ElectionBoardMemberDto.serializer()), board)
                            routes.tally ->
                                jsonOf(
                                    ElectionResultDto.serializer(),
                                    result ?: ElectionResultDto("e1", emptyList(), true, null, emptyMap()),
                                )
                            routes.withdraw, routes.submitCandidacy -> jsonOf(CandidacyDto.serializer(), candidacy("k1", "m-1", "X"))
                            else -> "null"
                        }
                    if (route == routes.cast && castDelayMs > 0) {
                        StubResponse(text = rpcResult(request.json.id as Int, json).text, delayMs = castDelayMs)
                    } else {
                        request.answerWith(json)
                    }
                }
            }
        }
    }

/** The visible text of [element] with all whitespace collapsed to single spaces. */
internal fun HTMLElement.flatText(): String = textContent.orEmpty().replace(Regex("\\s+"), " ").trim()

/** `true` when a button named exactly [text] exists. */
internal fun HTMLElement.hasButton(text: String): Boolean = allOf("button").any { it.textContent?.trim() == text }

/** `true` when the button named [text] carries the `disabled` attribute. */
internal fun HTMLElement.isButtonDisabled(text: String): Boolean = buttonNamed(text).hasAttribute("disabled")

/** Runs [block] with `console.log/info/warn/error/debug` counted instead of printed; [block] receives the live count. Restores the console afterwards. */
internal suspend fun <T> withConsoleSpy(block: suspend (() -> Int) -> T): T {
    val console =
        kotlinx.browser.window
            .asDynamic()
            .console
    val names = listOf("log", "info", "warn", "error", "debug")
    val originals = names.associateWith { console[it] }
    var count = 0
    names.forEach { name -> console[name] = { _: dynamic -> count++ } }
    try {
        return block { count }
    } finally {
        names.forEach { name -> console[name] = originals.getValue(name) }
    }
}

/** Fails when the test receipt code (or any part of it) sits in `localStorage` or `sessionStorage`. */
internal fun assertNoStoredCode() {
    val window = kotlinx.browser.window
    listOf(window.localStorage, window.sessionStorage).forEach { storage ->
        for (index in 0 until storage.length) {
            val key = storage.key(index).orEmpty()
            val value = storage.getItem(key).orEmpty()
            kotlin.test.assertFalse(
                key.contains(TEST_RECEIPT) || value.contains(TEST_RECEIPT),
                "the receipt code must never be stored: $key",
            )
        }
    }
}

/**
 * Counts the `beforeunload` listeners that are registered on `window` while [block] runs. A test must NEVER dispatch a real
 * `beforeunload` event: Karma's own `window.onbeforeunload` treats it as "a full page reload" and aborts the whole run.
 */
internal suspend fun <T> withBeforeUnloadSpy(block: suspend (() -> Int) -> T): T {
    val w = kotlinx.browser.window.asDynamic()
    val originalAdd = w.addEventListener
    val originalRemove = w.removeEventListener
    val active: dynamic = js("[]")
    w.addEventListener = { type: dynamic, listener: dynamic, options: dynamic ->
        if (type == "beforeunload" && !(active.includes(listener) as Boolean)) active.push(listener)
        originalAdd.call(kotlinx.browser.window, type, listener, options)
    }
    w.removeEventListener = { type: dynamic, listener: dynamic, options: dynamic ->
        if (type == "beforeunload") {
            val index = active.indexOf(listener) as Int
            if (index >= 0) active.splice(index, 1)
        }
        originalRemove.call(kotlinx.browser.window, type, listener, options)
    }
    try {
        return block { active.length as Int }
    } finally {
        w.addEventListener = originalAdd
        w.removeEventListener = originalRemove
        // a leaked guard would make Karma ask before leaving the page
        for (index in 0 until (active.length as Int)) originalRemove.call(kotlinx.browser.window, "beforeunload", active[index])
    }
}

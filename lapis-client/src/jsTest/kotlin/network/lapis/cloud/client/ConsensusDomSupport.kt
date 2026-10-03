package network.lapis.cloud.client

import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotCastResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusParticipationDto
import network.lapis.cloud.shared.domain.SystemicConsensusReceiptVerificationDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.28 -- the shared harness of the consensus DOM tests: the route table of every service method the consensus screens call (learned from
 * compiler-checked method references, see `routeOf`) and a mutable "world" that answers them.
 */

internal class ConsensusRoutes(
    val get: String,
    val getParticipation: String,
    val getResult: String,
    val getMotion: String,
    val list: String,
    val listParticipations: String,
    val listBallots: String,
    val cast: String,
    val verify: String,
    val freeze: String,
    val close: String,
    val evaluate: String,
    val reopen: String,
    val abort: String,
    val addOption: String,
    val removeOption: String,
    val setRationale: String,
    val open: String,
)

internal suspend fun consensusRoutes(): ConsensusRoutes =
    ConsensusRoutes(
        get = routeOf { rpcService<ISystemicConsensusService>().getSystemicConsensus("x") },
        getParticipation = routeOf { rpcService<ISystemicConsensusService>().getSystemicConsensusParticipation("x") },
        getResult = routeOf { rpcService<ISystemicConsensusService>().getSystemicConsensusResult("x") },
        getMotion = routeOf { rpcService<IGovernanceService>().getMotion("x") },
        list = routeOf { rpcService<ISystemicConsensusService>().listSystemicConsensuses() },
        listParticipations = routeOf { rpcService<ISystemicConsensusService>().listSystemicConsensusParticipations(emptyList()) },
        listBallots = routeOf { rpcService<ISystemicConsensusService>().listResistanceBallots("x") },
        cast = routeOf { rpcService<ISystemicConsensusService>().castResistanceBallot(SystemicConsensusBallotInput("x", emptyMap())) },
        verify = routeOf { rpcService<ISystemicConsensusService>().verifySystemicConsensusReceipt("x", "y") },
        freeze = routeOf { rpcService<ISystemicConsensusService>().freezeOptions("x") },
        close = routeOf { rpcService<ISystemicConsensusService>().closeRating("x") },
        evaluate = routeOf { rpcService<ISystemicConsensusService>().evaluate("x") },
        reopen = routeOf { rpcService<ISystemicConsensusService>().reopenRating("x") },
        abort = routeOf { rpcService<ISystemicConsensusService>().abortSystemicConsensus("x") },
        addOption = routeOf { rpcService<ISystemicConsensusService>().addOption("x", SystemicConsensusOptionInput("y")) },
        removeOption = routeOf { rpcService<ISystemicConsensusService>().removeOption("x") },
        setRationale = routeOf { rpcService<ISystemicConsensusService>().setOptionRationale("x", null) },
        open = routeOf { rpcService<ISystemicConsensusService>().openSystemicConsensus(SystemicConsensusOpenInput("x")) },
    )

/** What the stubbed server currently holds, and how it answers the write calls. Mutable so a test can change the world between reloads. */
internal class ConsensusWorld(
    var consensus: SystemicConsensusDto,
    var participation: SystemicConsensusParticipationDto = skParticipation(),
    var motion: MotionDto = motionDto(),
    var result: SystemicConsensusResultDto? = null,
    var all: List<SystemicConsensusDto> = emptyList(),
    var participations: List<SystemicConsensusParticipationDto> = emptyList(),
    var ballots: List<SystemicConsensusBallotDto> = emptyList(),
    /** `route -> fqcn` of an exception to throw for a route. */
    val failures: MutableMap<String, String> = mutableMapOf(),
    var castResult: SystemicConsensusBallotCastResultDto =
        SystemicConsensusBallotCastResultDto(id = "b1", castAt = SK_AT, receiptCode = TEST_RECEIPT),
    var verification: SystemicConsensusReceiptVerificationDto =
        SystemicConsensusReceiptVerificationDto(found = true, round = 1, countedInCurrentResult = false, resistances = null),
    var castDelayMs: Int = 0,
    var castNetworkError: Boolean = false,
    var opened: SystemicConsensusDto? = null,
    var onRoute: (String) -> Unit = {},
)

internal fun ConsensusWorld.respond(routes: ConsensusRoutes): (RecordedRequest) -> StubResponse =
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
                            routes.get -> jsonOf(SystemicConsensusDto.serializer(), consensus)
                            routes.getParticipation -> jsonOf(SystemicConsensusParticipationDto.serializer(), participation)
                            routes.getResult ->
                                jsonOf(
                                    SystemicConsensusResultDto.serializer(),
                                    checkNotNull(result) { "no result in the world" },
                                )
                            routes.getMotion -> jsonOf(MotionDto.serializer(), motion)
                            routes.list -> jsonOf(ListSerializer(SystemicConsensusDto.serializer()), all)
                            routes.listParticipations ->
                                jsonOf(ListSerializer(SystemicConsensusParticipationDto.serializer()), participations)
                            routes.listBallots -> jsonOf(ListSerializer(SystemicConsensusBallotDto.serializer()), ballots)
                            routes.cast -> jsonOf(SystemicConsensusBallotCastResultDto.serializer(), castResult)
                            routes.verify -> jsonOf(SystemicConsensusReceiptVerificationDto.serializer(), verification)
                            routes.open -> jsonOf(SystemicConsensusDto.serializer(), opened ?: consensus)
                            routes.freeze, routes.close, routes.reopen, routes.abort, routes.removeOption ->
                                jsonOf(SystemicConsensusDto.serializer(), consensus)
                            routes.addOption ->
                                jsonOf(SystemicConsensusOptionDto.serializer(), skOption("o-new", "Neu", 9))
                            routes.setRationale -> jsonOf(SystemicConsensusOptionDto.serializer(), skOption("o-a", "Option A", 1))
                            routes.evaluate ->
                                jsonOf(SystemicConsensusResultDto.serializer(), result ?: skResult())
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

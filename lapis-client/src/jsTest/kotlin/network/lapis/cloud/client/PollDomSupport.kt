package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IPollService

/*
 * V1.9.31 -- the shared harness of the poll DOM tests: the route table of every `IPollService` method (learned from compiler-checked
 * method references, see `routeOf`) and a mutable "world" that answers them.
 */

internal class PollRoutes(
    val create: String,
    val close: String,
    val abort: String,
    val get: String,
    val list: String,
    val result: String,
    val participation: String,
    val participations: String,
    val cast: String,
    val canCreate: String,
)

internal suspend fun pollRoutes(): PollRoutes =
    PollRoutes(
        create = routeOf { rpcService<IPollService>().createPoll(PollCreateInput("q", null, emptyList())) },
        close = routeOf { rpcService<IPollService>().closePoll("x") },
        abort = routeOf { rpcService<IPollService>().abortPoll("x") },
        get = routeOf { rpcService<IPollService>().getPoll("x") },
        list = routeOf { rpcService<IPollService>().listPolls() },
        result = routeOf { rpcService<IPollService>().getPollResult("x") },
        participation = routeOf { rpcService<IPollService>().getPollParticipation("x") },
        participations = routeOf { rpcService<IPollService>().listPollParticipations(emptyList()) },
        cast = routeOf { rpcService<IPollService>().castPollResponse(PollResponseInput("x", "y")) },
        canCreate = routeOf { rpcService<IPollService>().canCreatePolls() },
    )

internal fun pollSession(status: MemberStatus = MemberStatus.ACTIVE) =
    SessionInfoDto(
        memberId = "m-1",
        displayName = "Mitglied",
        role = AccountRole.MEMBER,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        status = status,
    )

/** What the stubbed server currently holds, and how it answers. Mutable so a test can change the world between reloads. */
internal class PollWorld(
    var poll: PollDto = pollDto(),
    var participation: PollParticipationDto = pollParticipation(),
    var result: PollResultDto? = null,
    /** All polls the list can return; the list answers by the requested status, offset and limit. */
    var all: List<PollDto> = emptyList(),
    var participations: List<PollParticipationDto> = emptyList(),
    var canCreate: Boolean = false,
    /** `route -> fqcn` of an exception to throw for a route. */
    val failures: MutableMap<String, String> = mutableMapOf(),
    var created: PollDto? = null,
    var castDelayMs: Int = 0,
    var castNetworkError: Boolean = false,
    var createDelayMs: Int = 0,
    var onRoute: (String) -> Unit = {},
)

internal fun PollWorld.respond(routes: PollRoutes): (RecordedRequest) -> StubResponse =
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
                            routes.get -> jsonOf(PollDto.serializer(), poll)
                            routes.participation -> jsonOf(PollParticipationDto.serializer(), participation)
                            routes.result -> jsonOf(PollResultDto.serializer(), checkNotNull(result) { "no result in the world" })
                            routes.list -> jsonOf(ListSerializer(PollDto.serializer()), listPage(request))
                            routes.participations -> jsonOf(ListSerializer(PollParticipationDto.serializer()), participations)
                            routes.cast ->
                                jsonOf(
                                    PollParticipationDto.serializer(),
                                    participation.copy(hasResponded = true, canRespond = false),
                                )
                            routes.create -> jsonOf(PollDto.serializer(), created ?: poll)
                            routes.close, routes.abort -> jsonOf(PollDto.serializer(), poll)
                            routes.canCreate -> jsonOf(Boolean.serializer(), canCreate)
                            else -> "null"
                        }
                    val delay =
                        when (route) {
                            routes.cast -> castDelayMs
                            routes.create -> createDelayMs
                            else -> 0
                        }
                    if (delay > 0) {
                        StubResponse(text = rpcResult(request.json.id as Int, json).text, delayMs = delay)
                    } else {
                        request.answerWith(json)
                    }
                }
            }
        }
    }

private fun PollWorld.listPage(request: RecordedRequest): List<PollDto> {
    val status = request.rpcParam(0) as? String
    val limit = request.rpcParam(1) as Int
    val offset = request.rpcParam(2) as Int
    return all.filter { status == null || it.status.name == status }.drop(offset).take(limit)
}

internal fun pollListOf(
    count: Int,
    status: PollStatus = PollStatus.OPEN,
): List<PollDto> = (1..count).map { pollDto(id = "p$it", status = status, question = "Frage Nummer $it") }

package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderBlockDto
import network.lapis.cloud.shared.domain.MemberFinderConnectionDto
import network.lapis.cloud.shared.domain.MemberFinderConsentTextDto
import network.lapis.cloud.shared.domain.MemberFinderIncomingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderResponseDto
import network.lapis.cloud.shared.domain.MemberFinderStateDto
import network.lapis.cloud.shared.rpc.IMemberFinderService

/** The ids and names of the member finder fixtures. The ids are random-looking UUIDs on purpose (the only ids that may appear in the DOM). */
internal object FinderIds {
    const val INCOMING = "aaaaaaaa-0000-4000-8000-000000000001"
    const val OUTGOING = "bbbbbbbb-0000-4000-8000-000000000002"
    const val CONNECTION = "cccccccc-0000-4000-8000-000000000003"
    const val BLOCK = "dddddddd-0000-4000-8000-000000000004"
    const val OTHER_PERSON = "Anna Beispiel"
    const val NOTE = "Ich wohne in der Nähe vom Bahnhof."
}

internal val finderDay: LocalDate = LocalDate(2026, 10, 16)

internal fun finderState(
    offered: Boolean = true,
    eligible: Boolean = true,
    optedIn: Boolean = true,
    consentOutdated: Boolean = false,
    interest: MemberFinderInterest? = MemberFinderInterest.BOTH,
    band: MemberFinderBand? = MemberFinderBand.B10_19,
    pendingOutgoing: Int = 0,
    nextOptInAllowedAt: kotlinx.datetime.LocalDateTime? = null,
) = MemberFinderStateDto(
    offered = offered,
    eligible = eligible,
    optedIn = optedIn,
    consentOutdated = consentOutdated,
    interest = if (optedIn) interest else null,
    regionLabel = if (optedIn && eligible) "38" else null,
    band = if (optedIn) band else null,
    bandComputedOn = if (optedIn) finderDay else null,
    nextOptInAllowedAt = nextOptInAllowedAt,
    pendingOutgoing = pendingOutgoing,
)

internal fun finderConsent(version: String = "v1") =
    MemberFinderConsentTextDto(versionLabel = version, body = "Einwilligung $version.", sha256 = "sha-$version")

internal fun incoming(
    note: String? = FinderIds.NOTE,
    purpose: MemberFinderPurpose = MemberFinderPurpose.GROUP_FOUNDING,
) = MemberFinderIncomingRequestDto(
    requestId = FinderIds.INCOMING,
    purpose = purpose,
    receivedOn = finderDay,
    expiresOn = LocalDate(2026, 11, 15),
    note = note,
)

internal fun outgoing(state: MemberFinderOutgoingState = MemberFinderOutgoingState.PENDING) =
    MemberFinderOutgoingRequestDto(
        requestId = FinderIds.OUTGOING,
        purpose = MemberFinderPurpose.MEETUP,
        sentOn = finderDay,
        expiresOn = LocalDate(2026, 11, 15),
        state = state,
    )

internal fun connection() =
    MemberFinderConnectionDto(
        connectionId = FinderIds.CONNECTION,
        otherDisplayName = FinderIds.OTHER_PERSON,
        purpose = MemberFinderPurpose.GROUP_FOUNDING,
        connectedOn = finderDay,
        endsOn = LocalDate(2027, 1, 14),
    )

internal fun finderBlock() = MemberFinderBlockDto(blockId = FinderIds.BLOCK, createdOn = finderDay)

/** The routes of the service methods, found by calling each once (the index inside the route is not stable, see [routeOf]). */
internal class FinderRoutes(
    val getState: String,
    val getConsentText: String,
    val optIn: String,
    val optOut: String,
    val sendRequest: String,
    val listOutgoing: String,
    val withdraw: String,
    val listIncoming: String,
    val respond: String,
    val listConnections: String,
    val contact: String,
    val blockConnection: String,
    val listBlocks: String,
    val unblock: String,
    val boardOverview: String,
)

internal suspend fun finderRoutes(): FinderRoutes =
    FinderRoutes(
        getState = routeOf { rpcService<IMemberFinderService>().getState() },
        getConsentText = routeOf { rpcService<IMemberFinderService>().getConsentText() },
        optIn = routeOf { rpcService<IMemberFinderService>().optIn("v1", "sha-v1", MemberFinderInterest.MEETUP, true) },
        optOut = routeOf { rpcService<IMemberFinderService>().optOut() },
        sendRequest = routeOf { rpcService<IMemberFinderService>().sendRequest(MemberFinderPurpose.MEETUP, null) },
        listOutgoing = routeOf { rpcService<IMemberFinderService>().listOutgoingRequests() },
        withdraw = routeOf { rpcService<IMemberFinderService>().withdrawRequest("x") },
        listIncoming = routeOf { rpcService<IMemberFinderService>().listIncomingRequests() },
        respond = routeOf { rpcService<IMemberFinderService>().respondToRequest("x", true, false) },
        listConnections = routeOf { rpcService<IMemberFinderService>().listConnections() },
        contact = routeOf { rpcService<IMemberFinderService>().contactConnection("x", "y") },
        blockConnection = routeOf { rpcService<IMemberFinderService>().blockConnection("x") },
        listBlocks = routeOf { rpcService<IMemberFinderService>().listBlocks() },
        unblock = routeOf { rpcService<IMemberFinderService>().unblock("x") },
        boardOverview = routeOf { rpcService<IMemberFinderService>().getBoardOverview() },
    )

/** A mutable fake of the server side of the finder: tests change the fields and the stub answers from them. */
internal class FinderServer(
    val routes: FinderRoutes,
) {
    var state: MemberFinderStateDto = finderState()
    var consent: MemberFinderConsentTextDto = finderConsent()
    var incomingList: List<MemberFinderIncomingRequestDto> = emptyList()
    var outgoingList: List<MemberFinderOutgoingRequestDto> = emptyList()
    var connectionList: List<MemberFinderConnectionDto> = emptyList()
    var blockList: List<MemberFinderBlockDto> = emptyList()

    /** When set, the first `optIn` is refused as outdated and the consent text switches to this version. */
    var outdatedOnceThen: MemberFinderConsentTextDto? = null

    /** The type name (fully qualified) of an exception that `sendRequest` throws; `null` answers [sendAnswer]. */
    var sendFails: String? = null
    var sendAnswer: MemberFinderOutgoingRequestDto = outgoing()
    var respondAnswer: MemberFinderResponseDto = MemberFinderResponseDto(connection = null)

    private val prefix = "network.lapis.cloud.shared.rpc."

    fun respond(request: RecordedRequest): StubResponse {
        if (!request.isRpc) return StubResponse()
        val route = request.rpcRoute
        val id = request.json.id as Int
        return when (route) {
            routes.getState -> request.answerWith(jsonOf(MemberFinderStateDto.serializer(), state))
            routes.getConsentText -> request.answerWith(jsonOf(MemberFinderConsentTextDto.serializer(), consent))
            routes.optIn -> {
                val next = outdatedOnceThen
                if (next != null) {
                    outdatedOnceThen = null
                    consent = next
                    serviceExceptionResult(id, prefix + "MemberFinderConsentOutdatedException")
                } else {
                    state = finderState(interest = MemberFinderInterest.valueOf(request.rpcParam(2) as String))
                    request.answerWith(jsonOf(MemberFinderStateDto.serializer(), state))
                }
            }
            routes.optOut -> {
                state = finderState(optedIn = false)
                request.answerWith(jsonOf(MemberFinderStateDto.serializer(), state))
            }
            routes.sendRequest -> {
                val failure = sendFails
                if (failure !=
                    null
                ) {
                    serviceExceptionResult(id, prefix + failure)
                } else {
                    request.answerWith(jsonOf(MemberFinderOutgoingRequestDto.serializer(), sendAnswer))
                }
            }
            routes.listOutgoing -> request.answerWith(jsonOf(ListSerializer(MemberFinderOutgoingRequestDto.serializer()), outgoingList))
            routes.listIncoming -> request.answerWith(jsonOf(ListSerializer(MemberFinderIncomingRequestDto.serializer()), incomingList))
            routes.listConnections -> request.answerWith(jsonOf(ListSerializer(MemberFinderConnectionDto.serializer()), connectionList))
            routes.listBlocks -> request.answerWith(jsonOf(ListSerializer(MemberFinderBlockDto.serializer()), blockList))
            routes.respond -> {
                incomingList = emptyList()
                request.answerWith(jsonOf(MemberFinderResponseDto.serializer(), respondAnswer))
            }
            routes.withdraw -> {
                outgoingList = emptyList()
                request.answerWith("{}")
            }
            routes.unblock -> {
                blockList = emptyList()
                request.answerWith("{}")
            }
            routes.blockConnection -> {
                connectionList = emptyList()
                blockList = listOf(finderBlock())
                request.answerWith("{}")
            }
            routes.contact ->
                request.answerWith(
                    jsonOf(
                        DirectMessageDto.serializer(),
                        // Only the shape matters: both ids of the conversation are in the answer, the screen has to drop it.
                        directMessageFixture(),
                    ),
                )
            else -> StubResponse()
        }
    }
}

private fun directMessageFixture() =
    DirectMessageDto(
        id = "eeeeeeee-0000-4000-8000-000000000005",
        senderId = "ffffffff-0000-4000-8000-000000000006",
        senderDisplayName = "Ich",
        recipientId = "99999999-0000-4000-8000-000000000007",
        recipientDisplayName = FinderIds.OTHER_PERSON,
        body = "Hallo",
        sentAt = kotlinx.datetime.LocalDateTime(2026, 10, 16, 12, 0),
        readAt = null,
    )

package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.membermap.AddressGroup
import network.lapis.cloud.server.membermap.PmtilesBasemap
import network.lapis.cloud.server.membermap.PmtilesProbe
import network.lapis.cloud.server.membermap.PostalCodeCentroidIndex
import network.lapis.cloud.server.membermap.aggregateMemberMap
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BoardMemberMapResponse
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.IBoardMemberMapService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}
private val MEMBER_MAP_READ_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.9.5 "Vorstands-Karte" (second attempt) -- see [IBoardMemberMapService] KDoc. BOARD/ADMIN
 * only, no self-service variant.
 *
 * **Privacy is enforced by the QUERY SHAPE, not by post-filtering a richer result.** The `selectAll`
 * below reads ONLY [MemberTable.postalCode] and [MemberTable.country] for every eligible row -- no
 * `id`, `displayName`, `email`, `city`, `dateOfBirth` or `joinedAt` column is ever part of the
 * `SELECT`, let alone held in memory afterward. The two columns are folded into per-(postalCode,
 * country) counts in Kotlin (`groupingBy { }.eachCount()`) rather than a database-level `GROUP BY`
 * -- there is no richer row surviving this function at any point for a later mistake to leak; the
 * counting step and the privacy boundary are the same line. [network.lapis.cloud.server.membermap
 * .aggregateMemberMap] then does the actual DE/foreign/mapped/unresolvable classification, see that
 * function's own KDoc. `BoardMemberMapRpcWireTest` asserts the raw RPC response body directly for
 * the absence of any identifying field.
 */
class BoardMemberMapService(
    private val call: ApplicationCall,
    private val basemap: PmtilesBasemap,
    private val centroids: PostalCodeCentroidIndex?,
) : IBoardMemberMapService {
    override suspend fun getMemberMap(): BoardMemberMapResponse {
        val current = resolveCurrentMember(call)
        current.requireRole(*MEMBER_MAP_READ_ROLES)

        val groups =
            transaction {
                MemberTable
                    .select(MemberTable.postalCode, MemberTable.country)
                    .where {
                        (MemberTable.status inList MemberStatusSets.MEMBER_MAP_ELIGIBLE) and
                            MemberTable.anonymizedAt.isNull()
                    }.map { row -> row[MemberTable.postalCode] to row[MemberTable.country] }
                    .groupingBy { it }
                    .eachCount()
                    .map { (key, count) -> AddressGroup(postalCode = key.first, country = key.second, count = count) }
            }

        val tilesAvailable = basemap.probe() is PmtilesProbe.Available
        val response = aggregateMemberMap(groups = groups, centroids = centroids, tilesAvailable = tilesAvailable)

        // Five aggregate numbers only -- never a postal-code list, see class KDoc.
        logger.info {
            "Vorstands-Karte abgerufen von ${current.memberId} (${current.role}): total=${response.total}, " +
                "mapped=${response.mappedTotal}, unresolvable=${response.unresolvableGermanPostalCode}, " +
                "noPostalCode=${response.noPostalCode}, foreign=${response.foreign}"
        }
        return response
    }
}

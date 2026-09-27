package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.ConferenceBackgroundImageTable
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ConferenceBackgroundImageDto
import network.lapis.cloud.shared.rpc.IConferenceBackgroundService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen". See
 * `network.lapis.cloud.server.routes.registerConferenceBackgroundRoutes` KDoc for why the byte
 * payload itself travels over dedicated routes, not RPC -- this service is the read-only metadata
 * companion.
 *
 * **Self-healing (F2 defense-in-depth)**: if the ADMIN whole-organization backup were ever
 * restored on an instance, `conference_background_image` rows would NOT come back (see
 * `OrganizationSchemaCatalog.EXCLUDED_TABLES` -- deliberately excluded), so this situation should
 * not normally arise. As a defensive backstop regardless (e.g. a file lost to an operator mistake,
 * a botched migration), any row whose main file is missing on disk is filtered out of the result
 * AND deleted in the same transaction -- a metadata row with no file behind it would otherwise
 * silently occupy one of [network.lapis.cloud.shared.domain.ConferenceBackgroundRules.MAX_PER_MEMBER]
 * slots and its thumbnail would 404 forever. The row's THUMBNAIL file (if it still exists -- only
 * the main file was confirmed missing) is deleted too (Review-Befund, MINOR): once the row is gone,
 * nothing references that file any more, not even the DSGVO erasure path, which works from the rows.
 */
class ConferenceBackgroundService(
    private val call: ApplicationCall,
    private val storageRoot: File,
) : IConferenceBackgroundService {
    override suspend fun listMine(): List<ConferenceBackgroundImageDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            val rows =
                ConferenceBackgroundImageTable
                    .selectAll()
                    .where { ConferenceBackgroundImageTable.memberId eq current.memberId }
                    .orderBy(ConferenceBackgroundImageTable.createdAt to SortOrder.ASC)
                    .toList()

            val missingRows =
                rows.filterNot { row -> storageRoot.resolve(row[ConferenceBackgroundImageTable.storageKey]).exists() }
            val missingIds = missingRows.map { it[ConferenceBackgroundImageTable.id] }
            if (missingIds.isNotEmpty()) {
                logger.warn { "ConferenceBackgroundService.listMine: self-healing ${missingIds.size} row(s) with a missing file" }
                missingRows.forEach { row ->
                    runCatching {
                        storageRoot.resolve(row[ConferenceBackgroundImageTable.thumbStorageKey]).delete()
                    }.onFailure { e ->
                        logger.warn(e) {
                            "ConferenceBackgroundService.listMine: failed to delete orphaned thumbnail file (DB row is authoritative)"
                        }
                    }
                }
                ConferenceBackgroundImageTable.deleteWhere { ConferenceBackgroundImageTable.id inList missingIds }
            }

            rows
                .filterNot { it[ConferenceBackgroundImageTable.id] in missingIds }
                .map { row ->
                    ConferenceBackgroundImageDto(
                        id = row[ConferenceBackgroundImageTable.id].toString(),
                        width = row[ConferenceBackgroundImageTable.width],
                        height = row[ConferenceBackgroundImageTable.height],
                    )
                }
        }
    }
}

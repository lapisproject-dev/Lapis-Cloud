package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.EventImportPreviewDto
import network.lapis.cloud.shared.domain.EventImportResultDto

/**
 * Welle V1.9.82 "Veranstaltungs-Feed, Archiv und Import" -- admin import of PAST events from a JSON payload (see
 * `docs/api/event-import.adoc`). Deliberately its OWN RPC interface, not more methods on [IEventService]: the request path gets a
 * dedicated body-size guard before deserialization, and [IEventService] does not grow further.
 *
 * Role: BOARD/ADMIN for every method. Only events that already ended can be imported; imported events are created as `PUBLISHED` +
 * `PUBLIC` and their slug must not exist yet (an existing slug is skipped, never updated).
 */
@RpcService
interface IEventImportService {
    /** Dry run: parses and validates [json], writes nothing. */
    suspend fun previewEventImport(json: String): EventImportPreviewDto

    /**
     * Writes all valid entries in ONE transaction (all or nothing). [payloadSha256] must be the hash returned by the preview for the
     * very same [json] string, otherwise a `ConflictException` is thrown and nothing is written.
     */
    suspend fun commitEventImport(
        json: String,
        payloadSha256: String,
    ): EventImportResultDto
}

package network.lapis.cloud.server.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.rpc.RateLimitedException
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.263 -- rate-limited read of the document volume's free space. One instance lives in
 * `Application.module`; `DocumentService` is rebuilt per call, so the limiter must be shared.
 */
class DocumentStorageSpaceReader(
    private val root: File?,
    private val rateLimiter: FederationInboxRateLimiter,
    private val usable: (File) -> Long = StorageSpaceProbe.usable,
    private val total: (File) -> Long = StorageSpaceProbe.total,
) {
    /** The caller MUST have checked ADMIN before. Throws [RateLimitedException]. */
    suspend fun read(memberId: Uuid): StorageSpaceDto {
        if (!rateLimiter.checkAndRecord("member:$memberId")) throw RateLimitedException()
        return withContext(Dispatchers.IO) { StorageSpaceProbe.measure(root = root, usable = usable, total = total) }
    }

    companion object {
        const val MAX_CALLS = 12

        /** No root configured -> UNKNOWN (still rate limited). Default for tests constructing `DocumentService(call = call)`. */
        val UNCONFIGURED =
            DocumentStorageSpaceReader(
                root = null,
                rateLimiter = FederationInboxRateLimiter(maxRequests = MAX_CALLS, window = 1.minutes),
            )
    }
}

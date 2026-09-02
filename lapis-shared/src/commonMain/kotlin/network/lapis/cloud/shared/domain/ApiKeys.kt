package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.3.1 "API-Fundament, lesend" -- one issued API key, as returned by
 * [network.lapis.cloud.shared.rpc.IApiKeyService.listApiKeys]. Never carries the raw key or its
 * hash -- see `network.lapis.cloud.server.security.ApiKeyStore.ApiKeyRow` KDoc.
 */
@Serializable
data class ApiKeyDto(
    val id: String,
    val label: String,
    val keyPrefix: String,
    val createdAt: LocalDateTime,
    val createdByMemberId: String,
    val expiresAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
    /** 5-Minuten-Genauigkeit -- see `ApiKeyStore.touchLastUsed` KDoc. */
    val lastUsedAt: LocalDateTime?,
)

/**
 * Result of [network.lapis.cloud.shared.rpc.IApiKeyService.issueApiKey]/`.reissueApiKey` -- the
 * ONLY response that ever carries [rawKey] in the clear. It is never retrievable again afterwards
 * (see `ApiKeyStore` KDoc "Only a hash of the raw key is ever stored") -- the client UI is
 * responsible for showing it to the operator exactly once (see `ApiKeysScreen.kt`'s persistent-card
 * pattern, Design-Team decision #8: no re-reveal modal, "Neu ausstellen" instead).
 */
@Serializable
data class ApiKeyIssueResultDto(
    val apiKey: ApiKeyDto,
    val rawKey: String,
)

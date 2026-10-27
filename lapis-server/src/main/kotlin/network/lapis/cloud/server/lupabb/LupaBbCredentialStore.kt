package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.LupaBbConfigEvent
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

internal class StoredLupaBbCredentials(
    val credentials: LupaBbCredentials,
    val transport: ApiKeyTransport,
)

internal class LupaBbConfigInfo(
    val setAt: LocalDateTime,
    val setBy: Uuid,
    val transport: ApiKeyTransport,
)

/**
 * V1.9.226 -- the ONLY reader and writer of `lupabb_sync_config` (apart from [LupaBbRunGuard], which owns the guard columns of the same row).
 * The credentials are sealed with [SecretBox] (AES-256-GCM, AAD [AAD]) as the JSON `{u, p, k}`; the ciphertext never leaves this object.
 * Every function runs inside the caller's transaction.
 */
internal object LupaBbCredentialStore {
    const val CONFIG_ID = 1

    /** The `entityId` of every `LUPABB_SYNC_CONFIG` audit entry (the audit log needs a UUID). */
    val CONFIG_ENTITY_ID: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000122")
    const val AAD = "lupabb_sync_config:1:credentials:v1"

    @Serializable
    private class Payload(
        val u: String,
        val p: String,
        val k: String,
    )

    fun info(): LupaBbConfigInfo? =
        LupaBbSyncConfigTable
            .selectAll()
            .where { LupaBbSyncConfigTable.id eq CONFIG_ID }
            .singleOrNull()
            ?.let {
                LupaBbConfigInfo(
                    setAt = it[LupaBbSyncConfigTable.setAt],
                    setBy = it[LupaBbSyncConfigTable.setBy],
                    transport = ApiKeyTransport.valueOf(it[LupaBbSyncConfigTable.apiKeyTransport]),
                )
            }

    /** `null` when nothing is stored; throws [SecretBoxException] when the ciphertext cannot be opened (another key, tampering). */
    fun load(box: SecretBox): StoredLupaBbCredentials? {
        val row = LupaBbSyncConfigTable.selectAll().where { LupaBbSyncConfigTable.id eq CONFIG_ID }.singleOrNull() ?: return null
        val plain = box.open(sealed = row[LupaBbSyncConfigTable.credentialsCiphertext], aad = AAD)
        val payload =
            try {
                Json.decodeFromString(Payload.serializer(), plain)
            } catch (_: Exception) {
                throw SecretBoxException("The stored LupaBB credentials are unreadable")
            }
        return StoredLupaBbCredentials(
            credentials = LupaBbCredentials(username = payload.u, password = payload.p, apiKey = payload.k),
            transport = ApiKeyTransport.valueOf(row[LupaBbSyncConfigTable.apiKeyTransport]),
        )
    }

    /** Stores (inserts or replaces) the credentials; the guard columns of an existing row stay untouched. */
    fun save(
        box: SecretBox,
        credentials: LupaBbCredentials,
        transport: ApiKeyTransport,
        actor: Uuid,
        now: LocalDateTime,
    ): LupaBbConfigEvent {
        val sealed =
            box.seal(
                plaintext =
                    Json.encodeToString(
                        Payload.serializer(),
                        Payload(u = credentials.username, p = credentials.password, k = credentials.apiKey),
                    ),
                aad = AAD,
            )

        fun replace() =
            LupaBbSyncConfigTable.update({ LupaBbSyncConfigTable.id eq CONFIG_ID }) {
                it[credentialsCiphertext] = sealed
                it[apiKeyTransport] = transport.name
                it[setAt] = now
                it[setBy] = actor
            }
        if (replace() > 0) return LupaBbConfigEvent.REPLACED
        try {
            withSavepoint(name = "lupabb_config_insert") {
                LupaBbSyncConfigTable.insert {
                    it[id] = CONFIG_ID
                    it[credentialsCiphertext] = sealed
                    it[apiKeyTransport] = transport.name
                    it[setAt] = now
                    it[setBy] = actor
                }
            }
        } catch (e: Exception) {
            // two ADMINs saved at the same time while no row existed: the other one won the insert, this one replaces it
            if (!e.isUniqueViolation()) throw e
            replace()
            return LupaBbConfigEvent.REPLACED
        }
        return LupaBbConfigEvent.SET
    }

    /** Removes the row unless a run holds it (a stale claim counts as free). Returns `true` when a row was removed. */
    fun delete(staleBefore: LocalDateTime): Boolean =
        LupaBbSyncConfigTable.deleteWhere {
            (id eq CONFIG_ID) and (runningSince.isNull() or (runningSince less staleBefore))
        } > 0
}

package network.lapis.cloud.server.security

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** V1.9.215 -- test helpers: session rows with explicit times. The far-future "now" keeps real sessions of other specs out of the numbers. */
internal object OnlineSessionCountsSupport {
    /** All real sessions of other specs expire long before this. */
    val FAR_NOW: LocalDateTime = LocalDateTime.parse("2200-01-01T00:00:00")

    fun LocalDateTime.minus(d: Duration): LocalDateTime =
        (toInstant(TimeZone.UTC) - d).toLocalDateTime(TimeZone.UTC).truncatedToDbPrecision()

    fun LocalDateTime.plus(d: Duration): LocalDateTime =
        (toInstant(TimeZone.UTC) + d).toLocalDateTime(TimeZone.UTC).truncatedToDbPrecision()

    private val sessionIds = mutableListOf<Uuid>()

    @Suppress("LongParameterList")
    fun session(
        memberId: Uuid,
        now: LocalDateTime = FAR_NOW,
        expiresAt: LocalDateTime = now.plus(4.hours),
        lastUsedAt: LocalDateTime? = null,
        revokedAt: LocalDateTime? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            SessionTable.insert {
                it[SessionTable.id] = id
                it[tokenHash] = SessionTokens.hash(Uuid.random().toString())
                it[SessionTable.memberId] = memberId
                it[createdAt] = now.minus(1.hours)
                it[SessionTable.expiresAt] = expiresAt
                it[SessionTable.lastUsedAt] = lastUsedAt
                it[SessionTable.revokedAt] = revokedAt
            }
        }
        sessionIds += id
        return id
    }

    /** Deletes only the sessions created here (never `deleteAll`: other specs in the JVM own sessions too). */
    fun cleanUp() {
        if (sessionIds.isEmpty()) return
        transaction { SessionTable.deleteWhere { SessionTable.id inList sessionIds } }
        sessionIds.clear()
    }
}

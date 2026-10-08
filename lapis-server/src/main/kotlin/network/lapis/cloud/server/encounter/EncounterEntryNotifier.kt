package network.lapis.cloud.server.encounter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.EncounterEntryNotice
import network.lapis.cloud.server.mail.EncounterEntryNoticeMailer
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs name exception classes only -- never an id, a title, an address or a number.
private val logger = KotlinLogging.logger {}

/** At most this many office holders are informed (equals the maximum number of role assignments of a space). */
private const val MAX_NOTICE_RECIPIENTS = 20

/**
 * Welle V1.9.76 -- the anonymous entry notice: when a person WITHOUT an office enters an encounter space, the office holders of that
 * space who are NOT in the room at that moment get one e-mail that names nobody (room, time, number of newcomers, number present).
 *
 * - **FIRST_GUEST**: one mail per opening of the room (the first newcomer). **EVERY_GUEST**: newcomers are counted into five-minute
 *   slots and summarised, at most one mail per space per five minutes (see [EncounterEntryNoticeState]).
 * - **Best effort, after the commit**: [EncounterSpaceService.enterSpace][network.lapis.cloud.server.rpc.EncounterSpaceService.enterSpace]
 *   calls [onGuestEntered] only after the entry transaction has committed; this class never throws, so a mail problem can never block
 *   or undo an entry.
 * - **Presence** needs no new storage: the recipients are the office holders without a `conference_participation` row in the session
 *   (that table is the transient live presence, deleted on leave/close/by the poller).
 * - **No mail inside a transaction**: the database reads run in their own short read transactions, [EncounterEntryNoticeMailer.send]
 *   (which only enqueues) is always called outside.
 * - **Nothing personal is written anywhere**: no audit entry, no table, no log line with an address or id.
 *
 * Module-scoped singleton (the service is built per request): see `Application.module`.
 */
class EncounterEntryNotifier(
    private val state: EncounterEntryNoticeState,
    private val mailer: EncounterEntryNoticeMailer,
    private val flushIntervalSeconds: Long = 30,
) {
    private var job: Job? = null

    /** What [resolveNotice] reads: everything the mail needs, and nothing about the entrant. */
    internal data class ResolvedNotice(
        val title: String,
        val recipients: List<String>,
        val presentCount: Int,
    )

    /** Idempotent. Flushes due EVERY_GUEST windows every [flushIntervalSeconds] (also runs when LiveKit/the poller are disabled). */
    fun start() {
        if (job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    delay(flushIntervalSeconds.seconds)
                    flushDue()
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Called AFTER the entry transaction committed, for a person WITHOUT an office who was newly admitted (not a reconnect). Never throws.
     * With the mail not configured it returns before any state or database access.
     */
    fun onGuestEntered(
        spaceId: Uuid,
        sessionRoomId: Uuid,
        mode: EncounterNotifyMode,
    ) {
        try {
            if (mode == EncounterNotifyMode.NONE || !mailer.enabled) return
            when (mode) {
                EncounterNotifyMode.FIRST_GUEST -> {
                    if (!state.claimFirstGuest(sessionRoomId = sessionRoomId, spaceId = spaceId)) {
                        if (state.isFull()) logger.warn { "encounter entry notice: state full, notice skipped" }
                        return
                    }
                    sendFirstGuest(spaceId = spaceId, sessionRoomId = sessionRoomId)
                }
                EncounterNotifyMode.EVERY_GUEST -> {
                    flushDue()
                    if (!state.countEntry(sessionRoomId = sessionRoomId, spaceId = spaceId, now = ServerClock.nowInstant())) {
                        logger.warn { "encounter entry notice: state full, notice skipped" }
                    }
                }
                EncounterNotifyMode.NONE -> Unit
            }
        } catch (e: Exception) {
            logger.warn { "encounter entry notice failed (${e::class.simpleName})" }
        }
    }

    /** Sends every EVERY_GUEST window that is due (its slot has ended and the space has not sent for five minutes). Never throws. */
    fun flushDue() {
        try {
            if (!mailer.enabled) return
            val now = ServerClock.nowInstant()
            state.drainDue(now).forEach { window ->
                try {
                    sendWindow(window = window, now = now)
                } catch (e: Exception) {
                    logger.warn { "encounter entry notice failed (${e::class.simpleName})" }
                }
            }
        } catch (e: Exception) {
            logger.warn { "encounter entry notice failed (${e::class.simpleName})" }
        }
    }

    /** Session closed (manually, by the poller, or at the end of its life): pending windows and the marker are dropped without a mail. */
    fun clearSession(sessionRoomId: Uuid) = state.clearSession(sessionRoomId)

    /** The notify mode of a space changed: marker and pending windows are dropped. */
    fun clearSpace(spaceId: Uuid) = state.clearSpace(spaceId)

    private fun sendFirstGuest(
        spaceId: Uuid,
        sessionRoomId: Uuid,
    ) {
        val resolved =
            transaction { resolveNotice(spaceId = spaceId, sessionRoomId = sessionRoomId, expected = EncounterNotifyMode.FIRST_GUEST) }
                ?: return
        val now = ServerClock.nowInstant()
        val at = flooredToMinute(now.toLocalDateTime(OrganizationTimeZone.current()))
        mailer.send(
            EncounterEntryNotice(
                recipients = resolved.recipients,
                spaceTitle = resolved.title,
                kind = EncounterEntryNotice.Kind.FIRST_GUEST,
                at = at,
                windowEnd = null,
                entries = 1,
                presentCount = resolved.presentCount,
            ),
        )
        state.markSent(spaceId = spaceId, at = now)
    }

    private fun sendWindow(
        window: EncounterEntryNoticeState.DueWindow,
        now: Instant,
    ) {
        val resolved =
            transaction {
                resolveNotice(spaceId = window.spaceId, sessionRoomId = window.sessionRoomId, expected = EncounterNotifyMode.EVERY_GUEST)
            } ?: return
        val zone: TimeZone = OrganizationTimeZone.current()
        mailer.send(
            EncounterEntryNotice(
                recipients = resolved.recipients,
                spaceTitle = resolved.title,
                kind = EncounterEntryNotice.Kind.WINDOW,
                at = window.windowStart.toLocalDateTime(zone),
                windowEnd = window.windowEnd.toLocalDateTime(zone),
                entries = window.count,
                presentCount = resolved.presentCount,
            ),
        )
        state.markSent(spaceId = window.spaceId, at = now)
    }

    /**
     * Reads, inside the caller's transaction, what the mail needs -- or `null` if there is nothing to send: the space is archived or no
     * longer has the [expected] mode, the session ended, or nobody would receive it.
     */
    internal fun resolveNotice(
        spaceId: Uuid,
        sessionRoomId: Uuid,
        expected: EncounterNotifyMode,
    ): ResolvedNotice? {
        val space = EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq spaceId }.singleOrNull() ?: return null
        if (space[EncounterSpaceTable.archivedAt] != null || space[EncounterSpaceTable.notifyMode] != expected.name) return null
        val sessionOpen =
            ConferenceRoomTable
                .selectAll()
                .where { (ConferenceRoomTable.id eq sessionRoomId) and ConferenceRoomTable.endedAt.isNull() }
                .any()
        if (!sessionOpen) return null
        val recipients = noticeRecipients(spaceId = spaceId, sessionRoomId = sessionRoomId)
        if (recipients.isEmpty()) return null
        return ResolvedNotice(
            title = space[EncounterSpaceTable.title],
            recipients = recipients,
            presentCount = EncounterSessions.presentCount(sessionRoomId),
        )
    }

    /**
     * The e-mail addresses of the ACTIVE PULPIT/STEWARD office holders of [spaceId] who are not currently present in [sessionRoomId]
     * (no `conference_participation` row), lower-cased, deduplicated, at most [MAX_NOTICE_RECIPIENTS]. Anonymised addresses (`.invalid`)
     * are dropped. Must run INSIDE the caller's open `transaction {}`.
     */
    internal fun noticeRecipients(
        spaceId: Uuid,
        sessionRoomId: Uuid,
    ): List<String> {
        val present: Set<Uuid> =
            ConferenceParticipationTable
                .selectAll()
                .where { ConferenceParticipationTable.roomId eq sessionRoomId }
                .map { it[ConferenceParticipationTable.memberId] }
                .toSet()
        return (EncounterSpaceRoleTable innerJoin MemberTable)
            .selectAll()
            .where {
                (EncounterSpaceRoleTable.spaceId eq spaceId) and
                    (MemberTable.status eq MemberStatus.ACTIVE) and
                    (EncounterSpaceRoleTable.role inList OFFICE_ROLES)
            }.filter { it[MemberTable.id] !in present }
            .map { it[MemberTable.email].trim().lowercase() }
            .filter { it.isNotEmpty() && !it.endsWith(".invalid") }
            .distinct()
            .sorted()
            .take(MAX_NOTICE_RECIPIENTS)
    }

    private fun flooredToMinute(at: LocalDateTime): LocalDateTime = LocalDateTime(date = at.date, time = LocalTime(at.hour, at.minute))

    private companion object {
        val OFFICE_ROLES = listOf(EncounterSpaceRole.PULPIT.name, EncounterSpaceRole.STEWARD.name)
    }
}

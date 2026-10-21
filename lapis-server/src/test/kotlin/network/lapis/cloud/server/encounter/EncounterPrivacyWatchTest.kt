package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.agreementtexts.EncounterAssemblyConsentDefault
import network.lapis.cloud.server.agreementtexts.EncounterChurchConsentDefault
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.mail.FakeEncounterEntryNoticeMailer
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val CONSENT_ASSEMBLY =
    EncounterConsentInput(consentVersion = EncounterAssemblyConsentDefault.VERSION, consentSha256 = EncounterAssemblyConsentDefault.SHA256)

private val CONSENT =
    EncounterConsentInput(consentVersion = EncounterChurchConsentDefault.VERSION, consentSha256 = EncounterChurchConsentDefault.SHA256)

/**
 * Welle V1.9.61 -- the data-protection WATCH of the encounter spaces. Each test names one promise made to a person who takes part in
 * a church service (Art. 9 GDPR: that can reveal religious belief) and checks it against the REAL database after a realistic flow:
 * nothing about who attended may remain once they left or the session ended, and nothing about them may enter the audit log.
 */
class EncounterPrivacyWatchTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun sessionRows(spaceId: Uuid): Long =
            transaction {
                val rooms =
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.encounterSpaceId eq spaceId
                        }.map { it[ConferenceRoomTable.id] }
                ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.roomId inList rooms }.count()
            }

        test("after closeSpace no participation row and no legacy consent row remains for ANY session of the space; left_at is never set") {
            encounterApp {
                val rig = EncounterRig()
                val steward = fx.createMember()
                val guest = fx.createMember(status = MemberStatus.GUEST)
                val members = List(3) { fx.createMember() }
                val space = fx.createSpace(createdBy = fx.createMember(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                members.forEach { m ->
                    rig.asMember(client = client, member = m) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                }
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }.getOrThrow()
                // somebody leaves early: the row is gone at once, there is no "left at"
                rig.asMember(client = client, member = members[0]) { it.leaveSpace(space.toString()) }.getOrThrow()
                transaction {
                    ConferenceParticipationTable
                        .selectAll()
                        .where { ConferenceParticipationTable.roomId eq room }
                        .all { it[ConferenceParticipationTable.leftAt] == null }
                } shouldBe true
                fx.participationCount(room) shouldBe 3L

                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                sessionRows(space) shouldBe 0L
                transaction {
                    ConferenceGuestConsentAcknowledgmentTable
                        .selectAll()
                        .where { ConferenceGuestConsentAcknowledgmentTable.roomId eq room }
                        .count()
                } shouldBe 0L
                // a second session and its end: same promise
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(
                        client = client,
                        member = members[1],
                    ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .getOrThrow()
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                sessionRows(space) shouldBe 0L
            }
        }

        test(
            "the audit log gets ONLY configuration and open/close entries -- nothing about entering, leaving, removing, silencing, and no congregation member id anywhere",
        ) {
            encounterApp {
                val rig = EncounterRig()
                val steward = fx.createMember()
                val board = fx.createMember(role = network.lapis.cloud.shared.domain.AccountRole.BOARD)
                val congregation = List(4) { fx.createMember() }
                val guest = fx.createMember(status = MemberStatus.FRIEND)
                val space = fx.createSpace(createdBy = board, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val entriesAfterOpen = auditEntriesOf(space).size
                val roomName = fx.livekitName(fx.openSessionRoom(space)!!)

                congregation.forEach { m ->
                    rig
                        .asMember(client = client, member = m) {
                            it.enterSpace(spaceId = space.toString(), consent = null)
                        }.getOrThrow()
                }
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = CONSENT) }.getOrThrow()
                rig.liveKit.connect(room = roomName, identity = congregation[0].toString())
                rig.asMember(client = client, member = congregation[1]) { it.listPresent(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = steward) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = congregation[0].toString())
                    }.getOrThrow()
                rig
                    .asMember(client = client, member = steward) {
                        it.silenceInSpace(spaceId = space.toString(), memberId = congregation[1].toString())
                    }.getOrThrow()
                rig.asMember(client = client, member = congregation[2]) { it.leaveSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = guest) { it.leaveSpace(space.toString()) }.getOrThrow()

                // the presence flow added NOT ONE audit entry
                auditEntriesOf(space).size shouldBe entriesAfterOpen

                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                val ids = (congregation + guest).map { it.toString() }
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId eq space }
                        .forEach { row ->
                            val text =
                                listOfNotNull(
                                    row[AuditLogEntryTable.beforeSnapshot],
                                    row[AuditLogEntryTable.afterSnapshot],
                                ).joinToString()
                            ids.none { text.contains(it) } shouldBe true
                            (row[AuditLogEntryTable.actorMemberId] in (congregation + guest)) shouldBe false
                        }
                    // nor does any audit entry anywhere mention one of them as snapshot content
                    ids.forEach { id ->
                        AuditLogEntryTable.selectAll().where { AuditLogEntryTable.afterSnapshot like "%$id%" }.count() shouldBe 0L
                    }
                }
                // the only entries are open and close, with the steward as the actor
                auditEntriesOf(space).map { it.second } shouldBe listOf("""{"state":"OPEN"}""", """{"state":"CLOSED","reason":"MANUAL"}""")
                auditEntriesOf(space).map { it.third }.toSet() shouldBe setOf(steward)
            }
        }

        test("the consent proof has no room, no space and no time of day: one row per (member, version) with a DATE") {
            encounterApp {
                val rig = EncounterRig()
                val steward = fx.createMember()
                val guest = fx.createMember(status = MemberStatus.GUEST)
                val spaceA = fx.createSpace(createdBy = fx.createMember(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                val spaceB = fx.createSpace(createdBy = fx.createMember(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                listOf(spaceA, spaceB).forEach { fx.setRole(spaceId = it, memberId = steward, role = EncounterSpaceRole.STEWARD) }
                listOf(spaceA, spaceB).forEach { s ->
                    rig.asMember(client = client, member = steward) { it.openSpace(s.toString()) }.getOrThrow()
                }
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = spaceA.toString(), consent = CONSENT) }.getOrThrow()
                // the consent given for A also covers B (same version): nothing new is stored, B reveals nothing either
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = spaceB.toString(), consent = null) }.getOrThrow()
                val columns =
                    transaction {
                        val names = mutableSetOf<String>()
                        exec(
                            "SELECT column_name FROM information_schema.columns WHERE table_name = 'encounter_consent_acknowledgment'",
                        ) { rs ->
                            while (rs.next()) names += rs.getString(1)
                        }
                        names
                    }
                columns shouldBe setOf("member_id", "consent_version", "consent_sha256", "acknowledged_on")
                transaction {
                    network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
                        .selectAll()
                        .where { network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable.memberId eq guest }
                        .count()
                } shouldBe 1L
            }
        }

        test(
            "V1.9.148: the same promises hold for an ASSEMBLY room -- one date-only proof row per (member, version), nothing about the room",
        ) {
            encounterApp {
                val rig = EncounterRig()
                val steward = fx.createMember()
                val guest = fx.createMember(status = MemberStatus.GUEST)
                val space =
                    fx.createSpace(
                        createdBy = fx.createMember(),
                        guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS,
                        profile = network.lapis.cloud.shared.domain.EncounterProfile.ASSEMBLY,
                    )
                fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                rig
                    .asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = CONSENT_ASSEMBLY) }
                    .getOrThrow()
                fx.participationCount(room) shouldBe 1L
                transaction {
                    network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
                        .selectAll()
                        .where { network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable.memberId eq guest }
                        .map { it[network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable.consentVersion] }
                } shouldBe listOf(EncounterAssemblyConsentDefault.VERSION)
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                sessionRows(space) shouldBe 0L
                // closing the room leaves only the date-only proof
                transaction {
                    network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
                        .selectAll()
                        .where { network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable.memberId eq guest }
                        .count()
                } shouldBe 1L
            }
        }

        test("the poller start-up sweep removes orphaned presence rows of ENDED sessions (e.g. left behind by a crash)") {
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            val ended =
                fx.insertSession(
                    spaceId = space,
                    openedBy = steward,
                    endedAt =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime(),
                )
            fx.insertParticipation(roomId = ended, memberId = member)
            fx.insertParticipation(roomId = ended, memberId = steward)
            // a legacy consent row (which must never exist) is swept too
            transaction {
                ConferenceGuestConsentAcknowledgmentTable.insert {
                    it[ConferenceGuestConsentAcknowledgmentTable.id] = Uuid.random()
                    it[ConferenceGuestConsentAcknowledgmentTable.memberId] = member
                    it[ConferenceGuestConsentAcknowledgmentTable.roomId] = ended
                    it[acknowledgedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[consentVersion] = "v"
                    it[consentSha256] = "0".repeat(64)
                    it[homeserverUrl] = null
                    it[organizationName] = "Org"
                }
            }
            // an ORDINARY ended room keeps its history (the sweep is for encounter sessions only)
            val ordinary = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = ordinary
                    it[title] = "Alt"
                    it[description] = ""
                    it[livekitRoomName] = "lc-$ordinary"
                    it[createdByMemberId] = steward
                    it[createdAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[endedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[maxParticipants] = 25
                }
            }
            fx.insertParticipation(roomId = ordinary, memberId = member)
            val poller =
                EncounterSpacePoller(
                    liveKitAdminClient = FakeEncounterLiveKit(),
                    moderationState = EncounterModerationState(),
                    blessingState = EncounterBlessingState(),
                    bellState = EncounterBellState(),
                    seatState = EncounterSeatState(),
                    mapState = EncounterMapState(),
                    tableState = EncounterTableState(),
                    floorState = EncounterFloorState(),
                    entryNotifier = EncounterEntryNotifier(state = EncounterEntryNoticeState(), mailer = FakeEncounterEntryNoticeMailer()),
                    liveKitEnabled = false,
                )
            runBlocking { poller.tick() }
            fx.participationCount(ended) shouldBe 0L
            transaction {
                ConferenceGuestConsentAcknowledgmentTable
                    .selectAll()
                    .where { ConferenceGuestConsentAcknowledgmentTable.roomId eq ended }
                    .count()
            } shouldBe 0L
            fx.participationCount(ordinary) shouldBe 1L
        }

        // ── Welle V1.9.76: the anonymous entry notice leaves no trace ─────

        test(
            "V1.9.76: an entry that sends the notice leaves no audit entry and no row about the entrant, and no new column names a person",
        ) {
            encounterApp {
                val rig = EncounterRig()
                val steward = fx.createMember()
                val guest = fx.createMember()
                val space =
                    fx.createSpace(
                        createdBy = fx.createMember(),
                        notifyMode = network.lapis.cloud.shared.domain.EncounterNotifyMode.FIRST_GUEST,
                    )
                fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val room = fx.openSessionRoom(space)!!
                val auditBefore = fx.auditCount()
                rig.asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                rig.entryMailer.calls.size shouldBe 1
                fx.auditCount() shouldBe auditBefore
                // the only lasting row about the entrant is the transient presence row, deleted on leave
                rig.asMember(client = client, member = guest) { it.leaveSpace(space.toString()) }.getOrThrow()
                fx.participationCount(room) shouldBe 0L
                // the room configuration got no column that references a person (V1.9.80 added three room-setting columns)
                network.lapis.cloud.server.db.generated.EncounterSpaceTable.columns
                    .map { it.name }
                    .toSet() shouldBe
                    setOf(
                        "id",
                        "title",
                        "description",
                        "theme_key",
                        "mode",
                        "profile",
                        "reaction_set",
                        "notify_mode",
                        // V1.9.80: three more columns, all of them room configuration (never a person)
                        "tables_enabled",
                        "table_count",
                        "table_seats",
                        // V1.9.152: the fixed room template (what the ROOM looks like, identical for everybody present; never a person)
                        "room_layout",
                        "guest_policy",
                        // V1.9.143: one more room setting (whether the pulpit of the room may be recorded), a flag of the room and never a person
                        "pulpit_recording_allowed",
                        // V1.9.166: one more room setting (whether the room has a grid map), a flag of the room and never a person or a position
                        "map_enabled",
                        "max_participants",
                        "closed_notice",
                        "created_at",
                        "created_by_member_id",
                        "updated_at",
                        "archived_at",
                    )
            }
        }

        test("V1.9.80: the table paths log neither a member, an identity, a table nor a room name (even when LiveKit fails)") {
            val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender =
                ch.qos.logback.core.read
                    .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
            appender.start()
            root.addAppender(appender)
            try {
                val w = fx.tableWorld()
                encounterApp {
                    openAndEnter(w = w, w.steward, w.a, w.b)
                    w.rig.liveKit.failAll = true // every rotation fails -> the warning paths run
                    w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                    w.rig.asMember(client = client, member = w.b) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 1) }.getOrThrow()
                    w.rig
                        .asMember(
                            client = client,
                            member = w.steward,
                        ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                        .getOrThrow()
                    w.rig
                        .asMember(
                            client = client,
                            member = w.steward,
                        ) { it.sendToPlenum(spaceId = w.spaceId, memberId = w.a.toString()) }
                        .getOrThrow()
                    w.rig.asMember(client = client, member = w.b) { it.leaveTable(w.spaceId) }.getOrThrow()
                    EncounterTableReconciler(
                        liveKitAdminClient = w.rig.liveKit,
                        tableState = w.rig.tableState,
                        liveKitEnabled = true,
                    ).tick()
                    val text = appender.list.joinToString("\n") { it.formattedMessage }
                    listOf(w.a, w.b, w.steward).forEach { text.contains(it.toString()) shouldBe false }
                    text.contains(ENCOUNTER_TABLE_ROOM_PREFIX) shouldBe false
                    text.contains(w.space.toString()) shouldBe false
                    (appender.list.count { it.formattedMessage.contains("encounter tables") } > 0) shouldBe true // the scan is not vacuous
                }
            } finally {
                root.detachAppender(appender)
            }
        }

        // ── Welle V1.9.166: the grid map keeps no position anywhere but in memory ─────────────────

        /** Row counts of every table of the schema, by name (the scan of "nothing new was written"). */
        fun rowCounts(): Map<String, Long> =
            transaction {
                val tables = mutableListOf<String>()
                exec("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'") { rs ->
                    while (rs.next()) tables += rs.getString(1)
                }
                tables.associateWith { name ->
                    var count = 0L
                    exec("SELECT COUNT(*) FROM \"$name\"") { rs -> if (rs.next()) count = rs.getLong(1) }
                    count
                }
            }

        /** Every value of every table, as text. */
        fun allValues(): List<String> =
            transaction {
                val tables = mutableListOf<String>()
                exec("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'") { rs ->
                    while (rs.next()) tables += rs.getString(1)
                }
                val values = mutableListOf<String>()
                tables.forEach { name ->
                    exec("SELECT * FROM \"$name\"") { rs ->
                        val columns = rs.metaData.columnCount
                        while (rs.next()) for (c in 1..columns) rs.getString(c)?.let { values += it }
                    }
                }
                values
            }

        test(
            "V1.9.166: after a realistic map session no table holds a position, a facing or a path; the audit log holds the switches only; the logs name nothing",
        ) {
            val w = fx.tableWorld(mapEnabled = true)
            val tableCountsBefore = rowCounts()
            val logs =
                captureLogs {
                    encounterApp {
                        openAndEnter(w = w, w.steward, w.a, w.b)
                        val a = w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                        w.rig.asMember(client = client, member = w.b) { it.mapEnter(w.spaceId) }.getOrThrow()
                        repeat(20) { n ->
                            w.rig.mapClock += 1.seconds
                            val dir = if (n % 2 == 0) EncounterMapFacing.WEST else EncounterMapFacing.EAST
                            w.rig.asMember(client = client, member = w.a) { it.mapStep(spaceId = w.spaceId, direction = dir) }.getOrThrow()
                        }
                        w.rig.mapClock += 10.seconds
                        w.rig
                            .asMember(
                                client = client,
                                member = w.a,
                            ) { it.mapTeleport(spaceId = w.spaceId, anchor = EncounterMapAnchor.CENTER) }
                            .getOrThrow()
                        w.rig.asMember(client = client, member = w.steward) { it.mapSnapshot(w.spaceId) }.getOrThrow()
                        w.rig.liveKit.failSendData = true
                        // a failing delivery runs the warning path
                        EncounterMapBroadcaster(liveKit = w.rig.liveKit, state = w.rig.mapState, liveKitEnabled = true).tick()
                        w.rig.asMember(client = client, member = w.a) { it.selectSeat(spaceId = w.spaceId, seat = 2) }.getOrThrow()
                        w.rig.asMember(client = client, member = w.b) { it.mapLeave(w.spaceId) }.getOrThrow()
                        w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.spaceId) }.getOrThrow()
                        (a.onMap) shouldBe true
                    }
                }
            val tableCountsAfter = rowCounts()
            // only the session rows (and the audit entries of open / close) may differ; no table was added to by the map
            val changed = tableCountsAfter.filter { (name, count) -> tableCountsBefore[name] != count }.keys.map { it.lowercase() }
            val allowed = setOf("conference_room", "conference_participation", "audit_log_entry", "audit_log_chain", "audit_chain_head")
            changed.filterNot { it in allowed || it.startsWith("audit") || it.startsWith("conference_") } shouldBe emptyList()
            // no cell anywhere is a facing, a coordinate packet or a path
            val values = allValues()
            values.none { it in setOf("NORTH", "EAST", "SOUTH", "WEST") } shouldBe true
            values.none { it.contains("\"c\":[") || it.contains("lapis-encounter-map") } shouldBe true
            // the audit log of the room: configuration and open / close only
            val audit =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId eq w.space }
                        .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                }
            audit.joinToString("\n").let { text ->
                listOf(w.a, w.b).forEach { member -> text.contains(member.toString()) shouldBe false }
                text.contains("facing") shouldBe false
            }
            // the logs name no member, no space, no room, no name
            val text = logs.joinToString("\n")
            listOf(w.a, w.b, w.steward, w.space).forEach { text.contains(it.toString()) shouldBe false }
            text.contains("\"c\":[") shouldBe false
            (logs.any { it.contains("encounter map") }) shouldBe true // the warning path really ran
        }

        test("V1.9.166: a server restart (a new state) forgets every position; the new epoch tells the clients") {
            val first = EncounterMapState()
            val space = Uuid.random()
            val room = Uuid.random()
            val member = Uuid.random()
            first.enter(
                spaceId = space,
                sessionRoomId = room,
                livekitRoom = "lk",
                layout = network.lapis.cloud.shared.domain.EncounterRoomLayout.HALL,
                memberId = member,
                initials = "AB",
            )
            first.snapshot(sessionRoomId = room, memberId = member).self.onMap shouldBe true
            val restarted = EncounterMapState()
            restarted.snapshot(sessionRoomId = room, memberId = member).self.onMap shouldBe false
            restarted.snapshot(sessionRoomId = room, memberId = member).positions shouldBe emptyList()
            (restarted.epoch == first.epoch) shouldBe false
        }
    })

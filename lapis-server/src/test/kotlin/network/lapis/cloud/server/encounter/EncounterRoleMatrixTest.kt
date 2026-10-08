package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.rpc.EncounterSpaceService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private enum class Outcome { OK, FORBIDDEN, CONFLICT, NOT_FOUND, BAD_REQUEST }

private fun Result<*>.outcome(): Outcome =
    when (val e = exceptionOrNull()) {
        null -> Outcome.OK
        is ForbiddenException -> Outcome.FORBIDDEN
        is ConflictException -> Outcome.CONFLICT
        is NotFoundException -> Outcome.NOT_FOUND
        is BadRequestException -> Outcome.BAD_REQUEST
        else -> throw IllegalStateException("unexpected failure $e", e)
    }

/** The kinds of caller of the access matrix. */
private enum class Caller(
    val status: MemberStatus,
    val account: AccountRole = AccountRole.MEMBER,
    val office: EncounterSpaceRole? = null,
) {
    ADMIN(MemberStatus.ACTIVE, AccountRole.ADMIN),
    BOARD(MemberStatus.ACTIVE, AccountRole.BOARD),
    PULPIT(MemberStatus.ACTIVE, office = EncounterSpaceRole.PULPIT),
    STEWARD(MemberStatus.ACTIVE, office = EncounterSpaceRole.STEWARD),
    WITHDRAWN_PULPIT(MemberStatus.WITHDRAWN, office = EncounterSpaceRole.PULPIT),
    ACTIVE_NO_OFFICE(MemberStatus.ACTIVE),
    FRIEND(MemberStatus.FRIEND),
    GUEST(MemberStatus.GUEST),
    APPLICATION(MemberStatus.APPLICATION),
    DONOR(MemberStatus.DONOR),
    ;

    val eligible: Boolean get() = status in setOf(MemberStatus.ACTIVE, MemberStatus.GUEST, MemberStatus.FRIEND)
    val privileged: Boolean get() = account == AccountRole.ADMIN || account == AccountRole.BOARD
    val moderator: Boolean get() = eligible && (privileged || (office != null && status == MemberStatus.ACTIVE))
    val canPublish: Boolean get() = eligible && office != null && status == MemberStatus.ACTIVE
}

private class Case(
    val name: String,
    val expected: (Caller) -> Outcome,
    val run: suspend (EncounterSpaceService, Ctx) -> Any?,
)

private class Ctx(
    val space: Uuid,
    val scratchSpace: Uuid,
    val victim: Uuid,
    val callerId: Uuid,
)

private val CONSENT_INPUT =
    EncounterConsentInput(consentVersion = EncounterConsentDisclaimer.VERSION, consentSha256 = EncounterConsentDisclaimer.SHA256)

/**
 * Welle V1.9.61 -- the access matrix of [EncounterSpaceService] pinned as a data table: every kind of caller against every method,
 * with the expected outcome and, for entering, whether the token may publish. Every combination runs against a FRESH space with an
 * open session (inserted directly), so no combination can influence another. The expectations are written from the access matrix in
 * `IEncounterSpaceService`'s KDoc, NOT derived from the implementation.
 */
class EncounterRoleMatrixTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        val cases =
            listOf(
                Case(name = "listSpaces", expected = { if (it.eligible) Outcome.OK else Outcome.FORBIDDEN }) { s, _ -> s.listSpaces() },
                Case(
                    name = "getSpace",
                    expected = { if (it.eligible) Outcome.OK else Outcome.FORBIDDEN },
                ) { s, c -> s.getSpace(c.space.toString()) },
                Case(name = "getEntryInfo", expected = {
                    if (it.eligible) Outcome.OK else Outcome.FORBIDDEN
                }) { s, c -> s.getEntryInfo(c.space.toString()) },
                Case(name = "createSpace", expected = {
                    if (it.privileged) Outcome.OK else Outcome.FORBIDDEN
                }) { s, _ -> s.createSpace(EncounterSpaceInput(title = "Matrix", profile = EncounterProfile.ASSEMBLY)) },
                Case(name = "updateSpace", expected = { if (it.privileged) Outcome.OK else Outcome.FORBIDDEN }) { s, c ->
                    s.updateSpace(
                        spaceId = c.space.toString(),
                        input = EncounterSpaceInput(title = "Neu", guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS),
                    )
                },
                Case(name = "createSpace (notifyMode)", expected = { if (it.privileged) Outcome.OK else Outcome.FORBIDDEN }) { s, _ ->
                    s.createSpace(EncounterSpaceInput(title = "Matrix", notifyMode = EncounterNotifyMode.EVERY_GUEST))
                },
                Case(name = "updateSpace (notifyMode)", expected = { if (it.privileged) Outcome.OK else Outcome.FORBIDDEN }) { s, c ->
                    s.updateSpace(
                        spaceId = c.space.toString(),
                        input =
                            EncounterSpaceInput(
                                title = "Neu",
                                guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS,
                                notifyMode = EncounterNotifyMode.FIRST_GUEST,
                            ),
                    )
                },
                Case(
                    name = "archiveSpace (open session)",
                    expected = { if (it.privileged) Outcome.CONFLICT else Outcome.FORBIDDEN },
                ) { s, c ->
                    s.archiveSpace(c.space.toString())
                },
                Case(name = "listSpaceRoles", expected = {
                    if (it.privileged) Outcome.OK else Outcome.FORBIDDEN
                }) { s, c -> s.listSpaceRoles(c.scratchSpace.toString()) },
                Case(name = "setSpaceRoles", expected = { if (it.privileged) Outcome.OK else Outcome.FORBIDDEN }) { s, c ->
                    s.setSpaceRoles(spaceId = c.scratchSpace.toString(), assignments = emptyList())
                },
                Case(name = "openSpace (already open)", expected = {
                    if (it.moderator) Outcome.OK else Outcome.FORBIDDEN
                }) { s, c -> s.openSpace(c.space.toString()) },
                Case(
                    name = "closeSpace",
                    expected = { if (it.moderator) Outcome.OK else Outcome.FORBIDDEN },
                ) { s, c -> s.closeSpace(c.space.toString()) },
                Case(name = "enterSpace", expected = {
                    if (it.eligible) Outcome.OK else Outcome.FORBIDDEN
                }) { s, c -> s.enterSpace(spaceId = c.space.toString(), consent = CONSENT_INPUT) },
                Case(name = "leaveSpace", expected = { Outcome.OK }) { s, c -> s.leaveSpace(c.space.toString()) },
                // listPresent while NOT present: nobody sees the roster from outside -- not even BOARD/ADMIN
                Case(name = "listPresent (not present)", expected = { Outcome.FORBIDDEN }) { s, c -> s.listPresent(c.space.toString()) },
                Case(
                    name = "removeFromSpace (a congregation member)",
                    expected = { if (it.moderator) Outcome.OK else Outcome.FORBIDDEN },
                ) { s, c ->
                    s.removeFromSpace(spaceId = c.space.toString(), memberId = c.victim.toString())
                },
                Case(
                    name = "silenceInSpace (a congregation member)",
                    expected = { if (it.moderator) Outcome.OK else Outcome.FORBIDDEN },
                ) { s, c ->
                    s.silenceInSpace(spaceId = c.space.toString(), memberId = c.victim.toString())
                },
            )

        test("access matrix: every kind of caller against every method") {
            encounterApp {
                val failures = mutableListOf<String>()
                cases.forEach { case ->
                    Caller.entries.forEach { kind ->
                        val rig = EncounterRig()
                        val boardCreator = fx.createMember(role = AccountRole.BOARD)
                        val space = fx.createSpace(createdBy = boardCreator, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                        val scratch = fx.createSpace(createdBy = boardCreator)
                        val callerId = fx.createMember(status = kind.status, role = kind.account)
                        kind.office?.let { fx.setRole(spaceId = space, memberId = callerId, role = it) }
                        fx.insertSession(spaceId = space, openedBy = boardCreator)
                        val victim = fx.createMember()
                        fx.insertParticipation(roomId = fx.openSessionRoom(space)!!, memberId = victim)
                        val actual =
                            rig
                                .asMember(client = client, member = callerId) {
                                    case.run(it, Ctx(space = space, scratchSpace = scratch, victim = victim, callerId = callerId))
                                }.outcome()
                        val expected = case.expected(kind)
                        if (actual != expected) failures += "${case.name} as $kind: expected $expected but was $actual"
                    }
                }
                failures shouldBe emptyList()
            }
        }

        test("V1.9.76: the notify mode is BOARD/ADMIN only -- everybody else is refused and the stored mode stays unchanged") {
            encounterApp {
                listOf(Caller.PULPIT, Caller.STEWARD, Caller.ACTIVE_NO_OFFICE, Caller.GUEST, Caller.FRIEND).forEach { kind ->
                    val rig = EncounterRig()
                    val boardCreator = fx.createMember(role = AccountRole.BOARD)
                    val space = fx.createSpace(createdBy = boardCreator, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                    val callerId = fx.createMember(status = kind.status, role = kind.account)
                    kind.office?.let { fx.setRole(spaceId = space, memberId = callerId, role = it) }
                    val input =
                        EncounterSpaceInput(
                            title = "Neu",
                            guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS,
                            notifyMode = EncounterNotifyMode.EVERY_GUEST,
                        )
                    rig
                        .asMember(client = client, member = callerId) { it.updateSpace(spaceId = space.toString(), input = input) }
                        .failure<ForbiddenException>()
                    rig.asMember(client = client, member = callerId) { it.createSpace(input) }.failure<ForbiddenException>()
                    val stored =
                        transaction {
                            EncounterSpaceTable
                                .selectAll()
                                .where {
                                    EncounterSpaceTable.id eq space
                                }.single()[EncounterSpaceTable.notifyMode]
                        }
                    stored shouldBe "NONE"
                }
            }
        }

        test("access matrix: listPresent for a caller who IS present -- eligible callers see the roster, the rest are refused") {
            encounterApp {
                val failures = mutableListOf<String>()
                Caller.entries.forEach { kind ->
                    val rig = EncounterRig()
                    val boardCreator = fx.createMember(role = AccountRole.BOARD)
                    val space = fx.createSpace(createdBy = boardCreator, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                    val callerId = fx.createMember(status = kind.status, role = kind.account)
                    kind.office?.let { fx.setRole(spaceId = space, memberId = callerId, role = it) }
                    val room = fx.insertSession(spaceId = space, openedBy = boardCreator)
                    fx.insertParticipation(roomId = room, memberId = callerId)
                    val actual = rig.asMember(client = client, member = callerId) { it.listPresent(space.toString()) }.outcome()
                    val expected = if (kind.eligible) Outcome.OK else Outcome.FORBIDDEN
                    if (actual != expected) failures += "listPresent (present) as $kind: expected $expected but was $actual"
                }
                failures shouldBe emptyList()
            }
        }

        test("access matrix: what the entry token may do -- only an ACTIVE office holder may publish, BOARD/ADMIN listen only") {
            encounterApp {
                val failures = mutableListOf<String>()
                Caller.entries.filter { it.eligible }.forEach { kind ->
                    val rig = EncounterRig()
                    val boardCreator = fx.createMember(role = AccountRole.BOARD)
                    val space = fx.createSpace(createdBy = boardCreator, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
                    val callerId = fx.createMember(status = kind.status, role = kind.account)
                    kind.office?.let { fx.setRole(spaceId = space, memberId = callerId, role = it) }
                    fx.insertSession(spaceId = space, openedBy = boardCreator)
                    val entry =
                        rig
                            .asMember(client = client, member = callerId) {
                                it.enterSpace(spaceId = space.toString(), consent = CONSENT_INPUT)
                            }.getOrThrow()
                    if (entry.canPublish != kind.canPublish) failures += "canPublish as $kind: expected ${kind.canPublish}"
                    if (videoGrantOf(entry.join.token)["canPublish"] != kind.canPublish) failures += "token canPublish as $kind"
                    if (videoGrantOf(entry.join.token)["canPublishData"] != true) failures += "token canPublishData as $kind"
                }
                failures shouldBe emptyList()
            }
        }

        test("a steward may not act against another office holder or BOARD/ADMIN; an ordinary member may not act at all") {
            encounterApp {
                val rig = EncounterRig()
                val boardCreator = fx.createMember(role = AccountRole.BOARD)
                val space = fx.createSpace(createdBy = boardCreator)
                val steward = fx.createMember()
                val pulpit = fx.createMember()
                val admin = fx.createMember(role = AccountRole.ADMIN)
                val plain = fx.createMember()
                fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
                fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
                fx.insertSession(spaceId = space, openedBy = boardCreator)
                val protectedTargets = listOf(pulpit, admin, boardCreator)
                protectedTargets.forEach { target ->
                    rig
                        .asMember(client = client, member = steward) {
                            it.removeFromSpace(spaceId = space.toString(), memberId = target.toString())
                        }.outcome() shouldBe
                        Outcome.FORBIDDEN
                    rig
                        .asMember(client = client, member = steward) {
                            it.silenceInSpace(spaceId = space.toString(), memberId = target.toString())
                        }.outcome() shouldBe
                        Outcome.FORBIDDEN
                    rig
                        .asMember(client = client, member = plain) {
                            it.removeFromSpace(spaceId = space.toString(), memberId = target.toString())
                        }.outcome() shouldBe
                        Outcome.FORBIDDEN
                }
                // BOARD/ADMIN may act against an office holder
                rig
                    .asMember(client = client, member = boardCreator) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = steward.toString())
                    }.outcome() shouldBe
                    Outcome.OK
                rig
                    .asMember(client = client, member = admin) {
                        it.silenceInSpace(spaceId = space.toString(), memberId = pulpit.toString())
                    }.outcome() shouldBe
                    Outcome.OK
                // nobody moderates himself
                rig
                    .asMember(client = client, member = admin) {
                        it.removeFromSpace(spaceId = space.toString(), memberId = admin.toString())
                    }.outcome() shouldBe
                    Outcome.CONFLICT
                // an unknown role assignment value cannot even be expressed: the enum is closed
                EncounterSpaceRoleAssignmentInput(memberId = plain.toString(), role = EncounterSpaceRole.PULPIT).role shouldBe
                    EncounterSpaceRole.PULPIT
            }
        }
    })

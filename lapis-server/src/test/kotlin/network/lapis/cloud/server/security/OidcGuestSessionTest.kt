package network.lapis.cloud.server.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V0.8.2 OIDC-Gastzugang-Federation -- the guest-identity/session model (see
 * [OidcGuestMemberStore] KDoc for the "guest = real Member row, status=GAST" design decision).
 * The direct regression test against this wave's hard, non-negotiable requirement: "voting is
 * never a scope" -- exercised here against the REAL, existing
 * [network.lapis.cloud.server.rpc.requireActiveMembership] function, not a mock.
 */
class OidcGuestSessionTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList createdMemberIds }
                SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        test("resolveOrCreateGuestMember creates exactly one Member(status=GAST)+Account row on first visit") {
            val issuer = "https://home-${Uuid.random()}.example"
            val subject = "guest-subject-${Uuid.random()}"
            val claims =
                OidcGuestClaims(
                    issuer = issuer,
                    subject = subject,
                    name = "Visiting Guest",
                    picture = null,
                    preferredUsername = "guest1",
                    homeserverUrl = issuer,
                    membershipStatus = "AKTIV",
                )
            val memberId = OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims, grantedScope = "openid profile_basic")
            createdMemberIds += memberId

            transaction {
                val memberRow = MemberTable.selectAll().where { MemberTable.id eq memberId }.single()
                memberRow[MemberTable.status] shouldBe MemberStatus.GAST
                memberRow[MemberTable.displayName] shouldBe "Visiting Guest"

                val accountRow = AccountTable.selectAll().where { AccountTable.memberId eq memberId }.single()
                accountRow[AccountTable.role] shouldBe AccountRole.MEMBER
                accountRow[AccountTable.oidcIssuer] shouldBe issuer
                accountRow[AccountTable.oidcSubject] shouldBe subject

                val profileRow = OidcGuestProfileTable.selectAll().where { OidcGuestProfileTable.memberId eq memberId }.single()
                profileRow[OidcGuestProfileTable.homeserverUrl] shouldBe issuer
                profileRow[OidcGuestProfileTable.membershipStatus] shouldBe "AKTIV"
            }
        }

        test("resolveOrCreateGuestMember reuses the SAME Member row on a second visit from the same (iss, sub)") {
            val issuer = "https://home-${Uuid.random()}.example"
            val subject = "guest-subject-${Uuid.random()}"
            val claims1 =
                OidcGuestClaims(
                    issuer = issuer,
                    subject = subject,
                    name = "Guest Name 1",
                    picture = null,
                    preferredUsername = "guest2",
                    homeserverUrl = issuer,
                    membershipStatus = null,
                )
            val claims2 =
                OidcGuestClaims(
                    issuer = issuer,
                    subject = subject,
                    name = "Guest Name 2 (updated profile)",
                    picture = null,
                    preferredUsername = "guest2",
                    homeserverUrl = issuer,
                    membershipStatus = "GAST",
                )

            val memberId1 = OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims1, grantedScope = "openid")
            createdMemberIds += memberId1
            val memberId2 = OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims2, grantedScope = "openid profile_basic")

            memberId2 shouldBe memberId1

            val memberCount =
                transaction {
                    (AccountTable innerJoin MemberTable)
                        .selectAll()
                        .where { (AccountTable.oidcIssuer eq issuer) and (AccountTable.oidcSubject eq subject) }
                        .count()
                }
            memberCount shouldBe 1L

            // Profile data is refreshed on repeat visits.
            transaction {
                val profileRow = OidcGuestProfileTable.selectAll().where { OidcGuestProfileTable.memberId eq memberId1 }.single()
                profileRow[OidcGuestProfileTable.grantedScope] shouldBe "openid profile_basic"
                profileRow[OidcGuestProfileTable.membershipStatus] shouldBe "GAST"
            }
        }

        test("synthetic email is deterministic for the same (iss, sub) and different for a different (iss, sub) pair") {
            val issuer = "https://home-${Uuid.random()}.example"
            val subjectA = "subject-a-${Uuid.random()}"
            val subjectB = "subject-b-${Uuid.random()}"

            val emailA1 = OidcGuestMemberStore.syntheticEmail(issuer = issuer, subject = subjectA)
            val emailA2 = OidcGuestMemberStore.syntheticEmail(issuer = issuer, subject = subjectA)
            val emailB = OidcGuestMemberStore.syntheticEmail(issuer = issuer, subject = subjectB)

            emailA1 shouldBe emailA2
            emailA1 shouldNotBe emailB
            emailA1.endsWith("@federation.invalid") shouldBe true
        }

        test(
            "a guest session (CurrentMember.isGuest) fails requireActiveMembership -- the direct 'voting is never a scope' regression guard",
        ) {
            val issuer = "https://home-${Uuid.random()}.example"
            val subject = "guest-subject-${Uuid.random()}"
            val claims =
                OidcGuestClaims(
                    issuer = issuer,
                    subject = subject,
                    name = "Voting Test Guest",
                    picture = null,
                    preferredUsername = null,
                    homeserverUrl = issuer,
                    membershipStatus = null,
                )
            val memberId = OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims, grantedScope = "openid")
            createdMemberIds += memberId

            val session = SessionStore.createSession(memberId)
            val resolved = SessionStore.resolve(session.rawToken)
            requireNotNull(resolved)
            resolved.isGuest shouldBe true

            shouldThrow<ForbiddenException> {
                transaction {
                    network.lapis.cloud.server.rpc
                        .requireActiveMembership(memberId = memberId)
                }
            }
        }

        test("CurrentMember.isGuest is false for a resolved REAL local member session") {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Real Member, Not A Guest"
                    it[email] = "real-member-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = kotlinx.datetime.LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id

            val session = SessionStore.createSession(id)
            val resolved = SessionStore.resolve(session.rawToken)
            requireNotNull(resolved)
            resolved.isGuest shouldBe false
        }
    })

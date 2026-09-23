package network.lapis.cloud.server.federation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ISSUER = "https://homeserver.example.org"

/**
 * Review finding F2 (round 3 of V1.7.1b Keycloak/OIDC review): no test previously proved
 * [GuestIdentityBoundToNonGuestMemberException] is actually thrown for a non-GUEST
 * `(issuer, subject)` match -- see [OidcGuestMemberStore.resolveOrCreateGuestMember]'s "review
 * finding N5 fix (round 2)" comment. Store-level coverage only (no HTTP layer) -- the route-level
 * "caught + 401 + audit row" half of N5 lives in `OidcRoutes.kt`'s RP callback handler; a
 * route-level test for that would need a mocked home-server token/JWKS endpoint, which no existing
 * test in this codebase sets up for the RP callback flow (`OidcRoutesTest.kt` only covers
 * `/rp/login`'s pre-fetch SSRF guard, never the full callback) -- out of scope to build fresh for
 * this fix round.
 */
class OidcGuestMemberStoreTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createNonGuestMemberWithOidcIdentity(
            issuer: String,
            subject: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "OIDC-Guest-Store Testmitglied"
                    it[email] = "oidc-guest-store-${Uuid.random()}@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[oidcIssuer] = issuer
                    it[oidcSubject] = subject
                }
            }
            createdMemberIds += id
            return id
        }

        test(
            "resolveOrCreateGuestMember throws GuestIdentityBoundToNonGuestMemberException when " +
                "(issuer, subject) is already bound to a non-GUEST member",
        ) {
            val subject = "subject-${Uuid.random()}"
            val memberId = createNonGuestMemberWithOidcIdentity(issuer = ISSUER, subject = subject, status = MemberStatus.ACTIVE)

            val claims =
                OidcGuestClaims(
                    issuer = ISSUER,
                    subject = subject,
                    name = "Some Guest",
                    picture = null,
                    preferredUsername = null,
                    homeserverUrl = ISSUER,
                    membershipStatus = null,
                )

            shouldThrow<GuestIdentityBoundToNonGuestMemberException> {
                OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims, grantedScope = "openid")
            }

            // Never mutated as a side effect of the rejected attempt.
            val row = transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single() }
            row[MemberTable.status] shouldBe MemberStatus.ACTIVE
        }

        test(
            "resolveOrCreateGuestMember does NOT throw for a matching (issuer, subject) whose member IS a GUEST",
        ) {
            val subject = "subject-${Uuid.random()}"
            val memberId = createNonGuestMemberWithOidcIdentity(issuer = ISSUER, subject = subject, status = MemberStatus.GUEST)

            val claims =
                OidcGuestClaims(
                    issuer = ISSUER,
                    subject = subject,
                    name = "Some Guest",
                    picture = null,
                    preferredUsername = null,
                    homeserverUrl = ISSUER,
                    membershipStatus = null,
                )

            val resolvedMemberId = OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims, grantedScope = "openid")

            resolvedMemberId shouldBe memberId
        }
    })

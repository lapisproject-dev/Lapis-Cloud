package network.lapis.cloud.server.keycloak

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ISSUER = "https://keycloak.example.org/realms/lapis"

/**
 * V1.7.1b "Keycloak als externe Benutzerverwaltung -- Server-Kern" -- exercises
 * [KeycloakAccountLinker] against a real (H2) transaction, no HTTP layer. Covers every one of the
 * six decision-table rows from the vault spec ("Keycloak Externe Benutzerverwaltung.md" decision 5).
 */
class KeycloakAccountLinkerTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    KeycloakAccountLinkTable.deleteWhere { KeycloakAccountLinkTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Keycloak-Linker Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun existingLink(
            memberId: Uuid,
            issuer: String = ISSUER,
            subject: String = "subject-${Uuid.random()}",
        ): String {
            val now = DbClock.nowLocalDateTime()
            transaction {
                KeycloakAccountLinkTable.insert {
                    it[id] = Uuid.random()
                    it[KeycloakAccountLinkTable.memberId] = memberId
                    it[keycloakIssuer] = issuer
                    it[keycloakSubject] = subject
                    it[linkedAt] = now
                    it[linkedBy] = null
                    it[lastLoginAt] = now
                }
            }
            return subject
        }

        // ── Case 1: existing link is authoritative ─────────────────────────────────────

        test("an existing link resolves by (issuer, subject), ignoring a changed email") {
            val memberId = createMember(email = "linker-case1-${Uuid.random()}@example.org")
            val subject = existingLink(memberId)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = subject,
                    email = "a-totally-different-email-${Uuid.random()}@example.org",
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Linked).memberId shouldBe memberId
        }

        test("resolving an existing link updates lastLoginAt") {
            val memberId = createMember(email = "linker-case1b-${Uuid.random()}@example.org")
            val subject = existingLink(memberId)

            KeycloakAccountLinker.linkOrResolve(
                issuer = ISSUER,
                subject = subject,
                email = "irrelevant-${Uuid.random()}@example.org",
                emailVerified = true,
                requireVerifiedEmail = true,
            )

            val row =
                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq memberId }.single()
                }
            (row[KeycloakAccountLinkTable.lastLoginAt] != null) shouldBe true
        }

        // ── Case 2: no link, unverified email, requireVerifiedEmail=true -> reject ─────

        test("no link + unverified email + requireVerifiedEmail=true is rejected before any email lookup") {
            val email = "linker-case2-${Uuid.random()}@example.org"
            createMember(email = email)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = email,
                    emailVerified = false,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.EMAIL_NOT_VERIFIED
        }

        test("no link + unverified email + requireVerifiedEmail=false still auto-links") {
            val email = "linker-case2b-${Uuid.random()}@example.org"
            val memberId = createMember(email = email)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = email,
                    emailVerified = false,
                    requireVerifiedEmail = false,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Linked).memberId shouldBe memberId
        }

        // ── Security-audit fix (MINOR 3a): requireVerifiedEmail=false must NOT open a takeover
        // path for ESCALATED_ROLES members (BOARD/TREASURER/ADMIN) ─────────────────────────────

        test(
            "no link + unverified email + requireVerifiedEmail=false + matched member is ADMIN -> " +
                "still rejected as EMAIL_NOT_VERIFIED (escalated role always requires a verified email)",
        ) {
            val email = "linker-escalated-${Uuid.random()}@example.org"
            createMember(email = email, role = AccountRole.ADMIN)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = email,
                    emailVerified = false,
                    requireVerifiedEmail = false,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.EMAIL_NOT_VERIFIED
        }

        test(
            "no link + VERIFIED email + requireVerifiedEmail=false + matched member is BOARD -> auto-links normally",
        ) {
            val email = "linker-escalated-verified-${Uuid.random()}@example.org"
            val memberId = createMember(email = email, role = AccountRole.BOARD)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = email,
                    emailVerified = true,
                    requireVerifiedEmail = false,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Linked).memberId shouldBe memberId
        }

        // ── Case 3: no link, exactly one email match, not yet linked -> auto-link ──────

        test("no link + exactly one matching, unlinked member -> auto-links with linkedBy=NULL, wasNewLink=true") {
            val email = "linker-case3-${Uuid.random()}@example.org"
            val memberId = createMember(email = email)
            val subject = "subject-${Uuid.random()}"

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = subject,
                    email = email,
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            val linked = outcome as KeycloakAccountLinker.LinkOutcome.Linked
            linked.memberId shouldBe memberId
            // Review finding N3 fix: distinguishes a brand-new auto-link (this case) from Case 1's
            // resolve-of-an-existing-link -- see LinkOutcome.Linked.wasNewLink KDoc.
            linked.wasNewLink shouldBe true

            val row =
                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq memberId }.single()
                }
            row[KeycloakAccountLinkTable.keycloakSubject] shouldBe subject
            row[KeycloakAccountLinkTable.linkedBy] shouldBe null
        }

        test("email match is case-insensitive") {
            val email = "Linker-Case3B-${Uuid.random()}@example.org"
            val memberId = createMember(email = email)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = email.uppercase(),
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Linked).memberId shouldBe memberId
        }

        // ── Review finding N3 fix: AMBIGUOUS_EMAIL_MATCH (review finding 6) ─────────────

        test("two members whose emails differ only in case -> rejected as AMBIGUOUS_EMAIL_MATCH, neither linked") {
            val base = "linker-ambiguous-${Uuid.random()}"
            val lowerEmail = "$base@example.org"
            val upperEmail = "${base.uppercase()}@EXAMPLE.ORG"
            val memberIdLower = createMember(email = lowerEmail)
            val memberIdUpper = createMember(email = upperEmail)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = lowerEmail.lowercase(),
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.AMBIGUOUS_EMAIL_MATCH

            val linkCount =
                transaction {
                    KeycloakAccountLinkTable
                        .selectAll()
                        .where {
                            KeycloakAccountLinkTable.memberId inList
                                listOf(memberIdLower, memberIdUpper)
                        }.count()
                }
            linkCount shouldBe 0L
        }

        // ── Review finding N4 fix: only a genuine unique-violation (SQLSTATE 23505) maps to
        // CONFLICTING_LINK -- any OTHER SQL failure during the insert must propagate instead ─────

        test("an overlong keycloak_subject is rejected client-side by Exposed BEFORE the DB is ever touched") {
            // This test does NOT exercise N4's ExposedSQLException-mapping catch block at all --
            // it only proves that Exposed validates VARCHAR length client-side. `keycloak_subject`
            // is VARCHAR(255) (`V45__keycloak_login.sql`); a value over that length throws
            // `IllegalArgumentException` before any SQL is ever sent, so it never reaches
            // `linkOrResolve`'s insert/catch, let alone `mapLinkInsertFailure`. (Round 3 review
            // finding F1: an earlier version of this comment incorrectly claimed this test proved
            // N4's mapping was correctly narrow -- it proves nothing about that mapping. The actual
            // regression coverage for `mapLinkInsertFailure` lives in the
            // "mapLinkInsertFailure(...)" tests below, which exercise real `ExposedSQLException`
            // instances directly.)
            val email = "linker-sqlstate-${Uuid.random()}@example.org"
            createMember(email = email)
            val overlongSubject = "s".repeat(300)

            shouldThrow<IllegalArgumentException> {
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = overlongSubject,
                    email = email,
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            }
        }

        // ── Review finding F1 fix (round 3): direct unit tests for `mapLinkInsertFailure`,
        // the extracted exception-to-outcome mapping the insert catch above delegates to. These
        // construct real `ExposedSQLException`s with real `java.sql.SQLException` SQLSTATE codes
        // instead of relying on an indirect trigger, so they actually distinguish "N4's mapping
        // is correct" from "N4's mapping is a no-op" -- unlike the overlong-subject test above,
        // which never reaches this code at all. ─────────────────────────────────────────────

        test("mapLinkInsertFailure: a genuine unique-violation (SQLSTATE 23505) maps to CONFLICTING_LINK") {
            val cause =
                transaction {
                    ExposedSQLException(
                        java.sql.SQLException("duplicate key value violates unique constraint", "23505"),
                        emptyList(),
                        this,
                    )
                }

            val outcome = KeycloakAccountLinker.mapLinkInsertFailure(cause)

            outcome.reason shouldBe KeycloakAccountLinker.RejectionReason.CONFLICTING_LINK
        }

        test("mapLinkInsertFailure: a foreign-key violation (SQLSTATE 23503) is NOT mapped -- it propagates") {
            val cause =
                transaction {
                    ExposedSQLException(
                        java.sql.SQLException("violates foreign key constraint", "23503"),
                        emptyList(),
                        this,
                    )
                }

            val thrown = shouldThrow<ExposedSQLException> { KeycloakAccountLinker.mapLinkInsertFailure(cause) }
            thrown shouldBe cause
        }

        test("mapLinkInsertFailure: a non-SQL Throwable is rethrown untouched") {
            val cause = IllegalStateException("connection pool exhausted")

            val thrown = shouldThrow<IllegalStateException> { KeycloakAccountLinker.mapLinkInsertFailure(cause) }
            thrown shouldBe cause
        }

        // ── Case 4: no link, no email match -> reject, NEVER create a member ───────────

        test("no link + no matching member -> rejected, and no member is ever created") {
            val email = "linker-case4-${Uuid.random()}@example.org"
            val memberCountBefore = transaction { MemberTable.selectAll().count() }

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = email,
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.NO_MATCHING_MEMBER

            val memberCountAfter = transaction { MemberTable.selectAll().count() }
            memberCountAfter shouldBe memberCountBefore
        }

        // ── Case 5: email matches a member already linked to a DIFFERENT subject -> reject ─

        test("email matches a member already linked to a different subject -> rejected as CONFLICTING_LINK, never re-linked") {
            val email = "linker-case5-${Uuid.random()}@example.org"
            val memberId = createMember(email = email)
            val originalSubject = existingLink(memberId, subject = "original-subject-${Uuid.random()}")

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "a-completely-different-subject-${Uuid.random()}",
                    email = email,
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.CONFLICTING_LINK

            val row =
                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq memberId }.single()
                }
            row[KeycloakAccountLinkTable.keycloakSubject] shouldBe originalSubject
        }

        // ── Case 6: matched member's status blocks login -> reject ─────────────────────

        test("no link + matched member is WITHDRAWN -> rejected as MEMBER_LOGIN_BLOCKED") {
            val email = "linker-case6-${Uuid.random()}@example.org"
            createMember(email = email, status = MemberStatus.WITHDRAWN)

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = "subject-${Uuid.random()}",
                    email = email,
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.MEMBER_LOGIN_BLOCKED
        }

        test("an existing link whose member is now WITHDRAWN is rejected on resolve, not just on first link") {
            val memberId = createMember(email = "linker-case6b-${Uuid.random()}@example.org")
            val subject = existingLink(memberId)
            transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[status] = MemberStatus.WITHDRAWN } }

            val outcome =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = ISSUER,
                    subject = subject,
                    email = "irrelevant-${Uuid.random()}@example.org",
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (outcome as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.MEMBER_LOGIN_BLOCKED
        }
    })

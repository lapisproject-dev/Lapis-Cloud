package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberNumberSequenceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.shared.domain.MemberCardCode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- direct unit coverage of [MemberCardStore]: issuance,
 * idempotency, rotation (old code revoked, new code valid), unknown/revoked resolving to the same
 * outcome shape, and that the raw code never appears verbatim in [MemberCardCodeTable].
 */
class MemberCardStoreDbTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val now = LocalDateTime(2037, 1, 1, 12, 0)
        val later = LocalDateTime(2037, 1, 1, 13, 0)

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    MemberCardCodeTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
                // Every member in this spec joins in 2037 (see `now`/`later` above and
                // `createMember`'s hardcoded joinedAt) -- issuing a code now also allocates a
                // member_number (MemberCardStore.issue calls MemberNumberAllocator.ensureFor),
                // which touches the shared per-year member_number_sequence row for 2037. Clean it
                // up the same way MemberNumberAllocatorDbTest cleans up its own touchedYears, so a
                // later exact-sequence assertion for 2037 does not inherit a pre-incremented
                // counter from this spec's run.
                MemberNumberSequenceTable.deleteWhere { allocationYear eq 2037 }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MemberCardStoreDbTest Mitglied"
                    it[email] = "membercardstore-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2037, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        test("issueNewCode issues a fresh, canonical, resolvable code") {
            val memberId = createMember()
            val raw = transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }

            MemberCardCode.canonicalize(raw) shouldBe raw

            val resolution = transaction { MemberCardStore.resolve(raw) }
            resolution.shouldBeInstanceOf<MemberCardResolution.Valid>()
        }

        test("the raw code never appears verbatim in member_card_code -- only its SHA-256 hash") {
            val memberId = createMember()
            val raw = transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }
            val storedHash =
                transaction {
                    MemberCardCodeTable
                        .selectAll()
                        .where { MemberCardCodeTable.memberId eq memberId }
                        .single()[MemberCardCodeTable.codeHash]
                }
            storedHash shouldBe sha256Hex(raw.toByteArray(Charsets.US_ASCII))
            (storedHash == raw) shouldBe false
        }

        test("revokeAndReissue invalidates the old code and issues a valid new one") {
            val memberId = createMember()
            val oldRaw = transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }
            val newRaw = transaction { MemberCardStore.revokeAndReissue(memberId = memberId, now = later) }

            (oldRaw == newRaw) shouldBe false

            transaction { MemberCardStore.resolve(oldRaw) } shouldBe MemberCardResolution.Revoked
            transaction { MemberCardStore.resolve(newRaw) }.shouldBeInstanceOf<MemberCardResolution.Valid>()
        }

        test("unknown code resolves to Unknown") {
            val unknownRaw = MemberCardPolicy.newRawCode()
            transaction { MemberCardStore.resolve(unknownRaw) } shouldBe MemberCardResolution.Unknown
        }

        test("resolve for a Valid code carries the member's display name, member number and status") {
            val memberId = createMember()
            val raw = transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }
            val resolution = transaction { MemberCardStore.resolve(raw) } as MemberCardResolution.Valid

            resolution.displayName shouldBe "MemberCardStoreDbTest Mitglied"
            resolution.status shouldBe MemberStatus.ACTIVE
            resolution.memberNumber.startsWith("M-2037-") shouldBe true
        }

        test("issueNewCode allocates member_number at issuance -- resolve is read-only and never allocates it") {
            val memberId = createMember()
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.memberNumber]
            } shouldBe null

            val raw = transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }

            // The number must already be persisted right after issuance -- BEFORE resolve() is
            // ever called -- proving allocation happens on the issuance path, not lazily from the
            // unauthenticated /ausweis read path.
            val numberAfterIssue =
                transaction {
                    MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.memberNumber]
                }
            (numberAfterIssue != null && numberAfterIssue.startsWith("M-2037-")) shouldBe true

            val resolution = transaction { MemberCardStore.resolve(raw) } as MemberCardResolution.Valid
            resolution.memberNumber shouldBe numberAfterIssue
        }
    })

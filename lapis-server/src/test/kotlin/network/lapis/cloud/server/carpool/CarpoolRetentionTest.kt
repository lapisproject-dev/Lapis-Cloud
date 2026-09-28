package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- [CarpoolRetention.deleteDueRows] boundary coverage, 1:1
 * `network.lapis.cloud.server.social.PostDraftRetentionTest`s Muster: rein datengetrieben über
 * einen fest übergebenen `now`, Zeilen direkt per Insert erzeugt.
 */
class CarpoolRetentionTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val fixedNow = LocalDateTime(2028, 1, 1, 0, 0, 0)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                CarpoolPostingTable.deleteWhere { authorMemberId inList createdMemberIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Carpool Retention Testmitglied"
                    it[email] = "carpool-retention-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertPosting(
            authorMemberId: Uuid,
            departureDate: LocalDate,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                CarpoolPostingTable.insert {
                    it[CarpoolPostingTable.id] = id
                    it[CarpoolPostingTable.authorMemberId] = authorMemberId
                    it[type] = CarpoolPostingType.REQUEST
                    it[fromPlace] = "Braunschweig"
                    it[toPlace] = "Hannover"
                    it[CarpoolPostingTable.departureDate] = departureDate
                    it[departureTime] = null
                    it[seatsOffered] = null
                    it[notes] = null
                    it[createdAt] = fixedNow
                    it[updatedAt] = fixedNow
                }
            }
            return id
        }

        fun stillExists(id: Uuid): Boolean =
            transaction {
                CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq id }.count() >
                    0L
            }

        test("departure_date exactly now - 7 days is kept; now - 8 days is deleted") {
            val memberId = newMember()
            val kept =
                insertPosting(
                    authorMemberId = memberId,
                    departureDate = fixedNow.date.minus(DatePeriod(days = CarpoolRetention.RETENTION_DAYS_AFTER_DEPARTURE)),
                )
            val deleted =
                insertPosting(
                    authorMemberId = memberId,
                    departureDate =
                        fixedNow.date.minus(
                            DatePeriod(
                                days =
                                    CarpoolRetention.RETENTION_DAYS_AFTER_DEPARTURE + 1,
                            ),
                        ),
                )

            CarpoolRetention.deleteDueRows(fixedNow)

            stillExists(kept) shouldBe true
            stillExists(deleted) shouldBe false
        }

        test("a future or today posting is never deleted") {
            val memberId = newMember()
            val today = insertPosting(authorMemberId = memberId, departureDate = fixedNow.date)
            val future = insertPosting(authorMemberId = memberId, departureDate = fixedNow.date.plus(DatePeriod(days = 30)))

            CarpoolRetention.deleteDueRows(fixedNow)

            stillExists(today) shouldBe true
            stillExists(future) shouldBe true
        }

        test("deleteDueRows returns the count of deleted rows") {
            val memberId = newMember()
            insertPosting(authorMemberId = memberId, departureDate = fixedNow.date.minus(DatePeriod(days = 20)))
            insertPosting(authorMemberId = memberId, departureDate = fixedNow.date.minus(DatePeriod(days = 21)))
            insertPosting(authorMemberId = memberId, departureDate = fixedNow.date) // not due

            val deleted = CarpoolRetention.deleteDueRows(fixedNow)

            deleted shouldBe 2
        }
    })

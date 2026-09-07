package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.MemberTable
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

/**
 * Welle V1.4.4.5 "Mitgliederlebenszyklus: Sterbefall-Workflow" --
 * `V24__member_date_of_death.sql`'s `chk_member_date_of_death_requires_status` CHECK actually
 * fires against the real migrated H2 schema. Same "CHECK-Sonde" pattern
 * [MemberFamilyConstraintTest] already establishes.
 */
class MemberDateOfDeathConstraintTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun newMember(
            status: MemberStatus = MemberStatus.ACTIVE,
            dateOfDeath: LocalDate? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Sterbedatum Testmitglied"
                    it[email] = "date-of-death-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[MemberTable.dateOfDeath] = dateOfDeath
                }
            }
            createdMemberIds += id
            return id
        }

        fun probeInsert(sql: String): Throwable? = runCatching { transaction { exec(sql) } }.exceptionOrNull()

        test("chk_member_date_of_death_requires_status rejects an INSERT with a date_of_death on a non-DECEASED status") {
            val id = Uuid.random()
            val sql =
                "INSERT INTO member (id, display_name, email, status, joined_at, date_of_death) " +
                    "VALUES ('$id', 'Constraint Probe', 'dod-insert-$id@example.org', 'ACTIVE', DATE '2020-01-01', DATE '2026-01-01')"
            val exception = probeInsert(sql)
            createdMemberIds += id // best-effort cleanup even though the insert should have failed
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_date_of_death_requires_status", ignoreCase = true) shouldBe true
        }

        test("chk_member_date_of_death_requires_status rejects an UPDATE that sets date_of_death on a non-DECEASED row") {
            val id = newMember(status = MemberStatus.ACTIVE)
            val exception =
                runCatching {
                    transaction {
                        MemberTable.update({ MemberTable.id eq id }) {
                            it[dateOfDeath] = LocalDate(2026, 1, 1)
                        }
                    }
                }.exceptionOrNull()
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_date_of_death_requires_status", ignoreCase = true) shouldBe true
        }

        test("a date_of_death together with status DECEASED is accepted") {
            val id = newMember(status = MemberStatus.DECEASED, dateOfDeath = LocalDate(2026, 1, 1))
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.dateOfDeath]
            } shouldBe LocalDate(2026, 1, 1)
        }

        test("date_of_death IS NULL together with status DECEASED is accepted") {
            val id = newMember(status = MemberStatus.DECEASED, dateOfDeath = null)
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.dateOfDeath]
            } shouldBe null
        }

        /**
         * Regression fuer die im Datei-Header (bzw. `MemberService.updateMemberStatus`) beschriebene
         * Stolperfalle: ein Statuswechsel WEG von DECEASED, der `date_of_death` NICHT im selben
         * UPDATE nullt, verletzt den Constraint. Belegt, warum `MemberService.updateMemberStatus`
         * beides zwingend im selben `update {}`-Block erledigen muss.
         */
        test("regression: leaving DECEASED without clearing date_of_death violates the constraint") {
            val id = newMember(status = MemberStatus.DECEASED, dateOfDeath = LocalDate(2026, 1, 1))
            val exception =
                runCatching {
                    transaction {
                        MemberTable.update({ MemberTable.id eq id }) {
                            it[status] = MemberStatus.ACTIVE
                            // date_of_death deliberately NOT cleared here -- this is the bug this test guards against.
                        }
                    }
                }.exceptionOrNull()
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_date_of_death_requires_status", ignoreCase = true) shouldBe true
        }

        test("leaving DECEASED while clearing date_of_death in the same update succeeds") {
            val id = newMember(status = MemberStatus.DECEASED, dateOfDeath = LocalDate(2026, 1, 1))
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[status] = MemberStatus.ACTIVE
                    it[dateOfDeath] = null
                }
            }
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.dateOfDeath]
            } shouldBe null
        }
    })

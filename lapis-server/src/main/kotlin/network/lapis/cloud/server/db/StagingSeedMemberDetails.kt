package network.lapis.cloud.server.db

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.generated.MemberTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Postal codes and birth dates of the fictitious seed members (V1.9.296), so that the member map, the birthday list and the "upcoming majority"
 * list of a staging or demo instance show something. The initial seed sets neither column, and the top-up may not insert members.
 *
 * This is the ONE part that updates existing `member` rows, and only in the narrowest way: per column and per planned member one UPDATE whose
 * WHERE clause requires the fixed seed id, the staging e-mail domain, `anonymized_at IS NULL` and the column to be NULL (the postal code also
 * blank). A value a person has set, or that an earlier run wrote, is never replaced (compare-and-set in SQL, no read-then-write). Status, country,
 * country code, e-mail and display name are never touched. No row is locked: a plain UPDATE of two non-key columns needs no member row lock.
 *
 * The postal codes are real city postal codes that exist in the bundled geodata; a city postal code is not personal data and no street is set.
 * The birth dates are relative to the seed clock (class D), so the planned birthdays lie in the next 14 days; one dependent is a minor who
 * turns 18 in about three weeks. They age on a staging instance that is not reset (documented in the deploy README).
 */
internal object StagingSeedMemberDetails : StagingSeedPart {
    override val name: String = "member-details"

    /** The age of a planned birth date, relative to the seed day: [daysFromToday] is the day of the next birthday (negative: already past this year). */
    data class BirthOffset(
        val yearsAgo: Int,
        val daysFromToday: Int,
    )

    /** One planned member; [member] is the seed member number. A null field means "leave that column alone". */
    data class PlannedDetails(
        val member: Int,
        val postalCode: String?,
        val birthOffset: BirthOffset?,
    )

    /** Append-only. Members 9 and 40 are planned with a birth date only; the 15 members outside the plan (among them 9 and 40) have no postal code, so the map's "no postal code" count shows. */
    val plan: List<PlannedDetails> =
        listOf(
            PlannedDetails(member = 1, postalCode = "38100", birthOffset = BirthOffset(yearsAgo = 45, daysFromToday = -150)),
            PlannedDetails(member = 2, postalCode = "38100", birthOffset = BirthOffset(yearsAgo = 35, daysFromToday = 3)),
            PlannedDetails(member = 3, postalCode = "10115", birthOffset = BirthOffset(yearsAgo = 40, daysFromToday = -90)),
            PlannedDetails(member = 4, postalCode = "38100", birthOffset = BirthOffset(yearsAgo = 27, daysFromToday = -250)),
            PlannedDetails(member = 5, postalCode = "20095", birthOffset = BirthOffset(yearsAgo = 42, daysFromToday = 9)),
            PlannedDetails(member = 6, postalCode = "80331", birthOffset = null),
            PlannedDetails(member = 7, postalCode = "50667", birthOffset = null),
            PlannedDetails(member = 8, postalCode = "38100", birthOffset = BirthOffset(yearsAgo = 51, daysFromToday = 12)),
            PlannedDetails(member = 9, postalCode = null, birthOffset = BirthOffset(yearsAgo = 47, daysFromToday = -60)),
            PlannedDetails(member = 10, postalCode = "10115", birthOffset = BirthOffset(yearsAgo = 44, daysFromToday = -300)),
            PlannedDetails(member = 19, postalCode = "04109", birthOffset = null),
            PlannedDetails(member = 20, postalCode = "01067", birthOffset = null),
            PlannedDetails(member = 21, postalCode = "60311", birthOffset = BirthOffset(yearsAgo = 29, daysFromToday = 6)),
            PlannedDetails(member = 23, postalCode = "70173", birthOffset = null),
            PlannedDetails(member = 24, postalCode = "30159", birthOffset = null),
            PlannedDetails(member = 27, postalCode = "38100", birthOffset = null),
            PlannedDetails(member = 28, postalCode = "20095", birthOffset = BirthOffset(yearsAgo = 38, daysFromToday = -120)),
            PlannedDetails(member = 29, postalCode = "80331", birthOffset = null),
            PlannedDetails(member = 30, postalCode = "50667", birthOffset = null),
            PlannedDetails(member = 32, postalCode = "04109", birthOffset = null),
            PlannedDetails(member = 33, postalCode = "60311", birthOffset = null),
            PlannedDetails(member = 34, postalCode = "38100", birthOffset = null),
            PlannedDetails(member = 35, postalCode = "10115", birthOffset = null),
            PlannedDetails(member = 37, postalCode = "70173", birthOffset = null),
            PlannedDetails(member = 38, postalCode = "30159", birthOffset = null),
            PlannedDetails(member = 39, postalCode = "01067", birthOffset = BirthOffset(yearsAgo = 36, daysFromToday = -200)),
            // The family dependent who turns 18 in about three weeks (see StagingSeedFamilies).
            PlannedDetails(
                member = 40,
                postalCode = null,
                birthOffset = BirthOffset(yearsAgo = MINOR_AGE, daysFromToday = MINOR_DAYS_TO_MAJORITY),
            ),
        )

    /** The planned birth date: the next birthday falls on `today + daysFromToday`. Pure; never 29 February. */
    fun dateOfBirth(
        clock: SeedClock,
        offset: BirthOffset,
    ): LocalDate {
        val date = clock.today.plus(offset.daysFromToday, DateTimeUnit.DAY).minus(offset.yearsAgo, DateTimeUnit.YEAR)
        return if (date.month == Month.FEBRUARY && date.day == 29) date.minus(1, DateTimeUnit.DAY) else date
    }

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        var inserted = 0
        var skipped = 0

        fun count(updated: Int) {
            if (updated > 0) inserted += updated else skipped++
        }
        plan.forEach { details ->
            val id = SeedIds.member(details.member)
            val seedMember =
                (MemberTable.id eq id) and
                    MemberTable.anonymizedAt.isNull() and
                    (MemberTable.email like "%@${StagingSeedData.EMAIL_DOMAIN}")
            details.postalCode?.let { plz ->
                count(
                    MemberTable.update({ seedMember and (MemberTable.postalCode.isNull() or (MemberTable.postalCode eq "")) }) {
                        it[postalCode] = plz
                    },
                )
            }
            details.birthOffset?.let { offset ->
                val birth = dateOfBirth(clock = clock, offset = offset)
                count(MemberTable.update({ seedMember and MemberTable.dateOfBirth.isNull() }) { it[dateOfBirth] = birth })
            }
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }

    internal const val MINOR_AGE: Int = 18
    internal const val MINOR_DAYS_TO_MAJORITY: Int = 20
}

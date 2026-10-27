package network.lapis.cloud.server.partg

import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException

/**
 * V1.9.225 -- which report years are valid: `2000 < year <= current year of the organisation`. The lower bound 2001 keeps the prior year
 * (>= 2000) inside `chk_partg_sheet_year`; a year in the future is refused.
 */
internal object PartgYearRules {
    const val MIN_YEAR = 2001

    fun maxYear(): Int = ServerClock.todayIn(OrganizationTimeZone.current()).year

    fun requireValid(year: Int): Int {
        if (year < MIN_YEAR || year > maxYear()) throw PartyReportInvalidInputException("Year $year is outside $MIN_YEAR..${maxYear()}")
        return year
    }
}

package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * V1.9.38 -- the zone conversion of `OrganizationTime.kt` against the REAL zone data of `@js-joda/timezone` (Karma runs in a real
 * Chrome, so a missing module shows up here as a throwing `TimeZone.of`, not only in production). Every expectation names its zone
 * explicitly, so the result is independent of the zone of the machine that runs the tests.
 */
class OrganizationTimeTest {
    @AfterTest
    fun resetZone() {
        OrganizationTime.zoneId = DEFAULT_ORGANIZATION_ZONE_ID
    }

    @Test
    fun zoneData_isLoaded_soANamedRegionResolvesInTheBrowser() {
        ensureZoneData()
        assertEquals("Europe/Berlin", TimeZone.of("Europe/Berlin").id)
        assertEquals("Asia/Tbilisi", TimeZone.of("Asia/Tbilisi").id)
    }

    @Test
    fun summer_utcBecomesMesz() {
        assertEquals(LocalDateTime(2026, 10, 2, 3, 48), toOrganizationZone(LocalDateTime(2026, 10, 2, 1, 48), "Europe/Berlin"))
        assertEquals(LocalDateTime(2026, 7, 1, 14, 0), toOrganizationZone(LocalDateTime(2026, 7, 1, 12, 0), "Europe/Berlin"))
    }

    @Test
    fun winter_utcBecomesMez() {
        assertEquals(LocalDateTime(2026, 12, 1, 13, 0), toOrganizationZone(LocalDateTime(2026, 12, 1, 12, 0), "Europe/Berlin"))
    }

    @Test
    fun springForward_2026_03_29() {
        assertEquals(LocalDateTime(2026, 3, 29, 1, 59), toOrganizationZone(LocalDateTime(2026, 3, 29, 0, 59), "Europe/Berlin"))
        assertEquals(LocalDateTime(2026, 3, 29, 3, 0), toOrganizationZone(LocalDateTime(2026, 3, 29, 1, 0), "Europe/Berlin"))
    }

    @Test
    fun fallBack_2026_10_25_sameWallClockTwice() {
        assertEquals(LocalDateTime(2026, 10, 25, 2, 30), toOrganizationZone(LocalDateTime(2026, 10, 25, 0, 30), "Europe/Berlin"))
        assertEquals(LocalDateTime(2026, 10, 25, 2, 30), toOrganizationZone(LocalDateTime(2026, 10, 25, 1, 30), "Europe/Berlin"))
    }

    @Test
    fun midnight_theDateRollsOver() {
        OrganizationTime.zoneId = "Europe/Berlin"
        val utc = LocalDateTime(2026, 9, 30, 22, 30)
        assertEquals(LocalDateTime(2026, 10, 1, 0, 30), toOrganizationZone(utc))
        assertEquals(LocalDate(2026, 10, 1), systemDate(utc))
    }

    @Test
    fun zoneAbbreviation_centralEuropeanFamily_mezAndMesz() {
        assertEquals("MESZ", zoneAbbreviation("Europe/Berlin", Instant.parse("2026-07-01T12:00:00Z")))
        assertEquals("MEZ", zoneAbbreviation("Europe/Berlin", Instant.parse("2026-12-01T12:00:00Z")))
        assertEquals("MESZ", zoneAbbreviation("Europe/Vienna", Instant.parse("2026-07-01T12:00:00Z")))
    }

    @Test
    fun zoneAbbreviation_otherZones_useTheNeutralOffset() {
        val at = Instant.parse("2026-07-01T12:00:00Z")
        assertEquals("UTC+4", zoneAbbreviation("Asia/Tbilisi", at))
        assertEquals("UTC+5:30", zoneAbbreviation("Asia/Kolkata", at))
        assertEquals("UTC-3", zoneAbbreviation("America/Sao_Paulo", at))
        assertEquals("UTC", zoneAbbreviation("UTC", at))
    }

    @Test
    fun invalidZoneId_fallsBackToUtc_andNeverThrows() {
        assertEquals(LocalDateTime(2026, 7, 1, 12, 0), toOrganizationZone(LocalDateTime(2026, 7, 1, 12, 0), "Not/AZone"))
        assertEquals("UTC", zoneAbbreviation("Not/AZone", Instant.parse("2026-07-01T12:00:00Z")))
        assertEquals(TimeZone.UTC, resolveZone("<script>"))
    }

    @Test
    fun formatSystemDateTimeWithZone_carriesTheAbbreviation() {
        OrganizationTime.zoneId = "Europe/Berlin"
        assertEquals("02.10.2026,${NBSP}03:48${NBSP}MESZ", formatSystemDateTimeWithZone(LocalDateTime(2026, 10, 2, 1, 48)))
        OrganizationTime.zoneId = "Asia/Tbilisi"
        assertEquals("02.10.2026,${NBSP}05:48${NBSP}UTC+4", formatSystemDateTimeWithZone(LocalDateTime(2026, 10, 2, 1, 48)))
    }

    @Test
    fun formatSystemDateTime_plainFormat_usesTheOrganizationZone() {
        OrganizationTime.zoneId = "Europe/Berlin"
        assertEquals("02.10.2026,${NBSP}03:48", formatSystemDateTime(LocalDateTime(2026, 10, 2, 1, 48)))
        assertEquals("02.10.2026,${NBSP}03:48:09", formatSystemTimestamp(LocalDateTime(2026, 10, 2, 1, 48, 9)))
    }

    @Test
    fun isoUtcTitle_isTheUnconvertedUtcInstant() {
        assertEquals("2026-10-02T01:48:00Z", isoUtcTitle(LocalDateTime(2026, 10, 2, 1, 48)))
    }

    @Test
    fun classB_wallClock_isShownAsStored_theOrganizationZoneNeverShiftsIt() {
        OrganizationTime.zoneId = "Asia/Tbilisi"
        // `DateTime.kt` unchanged: a typed-in 20:00 stays 20:00 whatever the zone setting is.
        assertEquals("01.07.2026,${NBSP}20:00", formatDateTime(LocalDateTime(2026, 7, 1, 20, 0)))
    }

    @Test
    fun v19137_dstDays_theConvertedWallClockIsFormattedInTheLocaleForm() {
        val de = TEMPORAL_LOCALES.getValue("de")
        val us = TEMPORAL_LOCALES.getValue("en-US")

        fun shown(
            locale: TemporalLocale,
            utc: LocalDateTime,
        ) = formatDateTimeIn(locale, toOrganizationZone(utc, "Europe/Berlin"))
        // 29 March 2026, spring forward: there is no 02:xx on the wall clock
        assertEquals("29.03.2026,${NBSP}01:59", shown(de, LocalDateTime(2026, 3, 29, 0, 59)))
        assertEquals("29.03.2026,${NBSP}03:00", shown(de, LocalDateTime(2026, 3, 29, 1, 0)))
        assertEquals("03/29/2026,${NBSP}3:00${NBSP}AM", shown(us, LocalDateTime(2026, 3, 29, 1, 0)))
        // 25 October 2026, fall back: the wall clock 02:30 occurs twice, both stamps are shown as 02:30
        assertEquals("25.10.2026,${NBSP}02:30", shown(de, LocalDateTime(2026, 10, 25, 0, 30)))
        assertEquals("25.10.2026,${NBSP}02:30", shown(de, LocalDateTime(2026, 10, 25, 1, 30)))
        assertEquals("10/25/2026,${NBSP}2:30${NBSP}AM", shown(us, LocalDateTime(2026, 10, 25, 1, 30)))
        // the conversion itself is untouched by the formatting change: the same UTC instants as before
        assertEquals(LocalDateTime(2026, 3, 29, 3, 0), toOrganizationZone(LocalDateTime(2026, 3, 29, 1, 0), "Europe/Berlin"))
        assertEquals(LocalDateTime(2026, 10, 25, 2, 30), toOrganizationZone(LocalDateTime(2026, 10, 25, 1, 30), "Europe/Berlin"))
        // a class-B wall clock 02:30 on the spring-forward day is displayed exactly as typed (never shifted to 03:30)
        assertEquals("29.03.2026,${NBSP}02:30", formatDateTimeIn(de, LocalDateTime(2026, 3, 29, 2, 30)))
    }

    @Test
    fun organizationNow_isAWallClockOfTheOrganizationZone() {
        OrganizationTime.zoneId = "Pacific/Kiritimati" // UTC+14: far from every test machine's zone
        val kiritimati = organizationNow()
        OrganizationTime.zoneId = "Pacific/Pago_Pago" // UTC-11
        val pago = organizationNow()
        // The two clocks are read a few milliseconds apart; the hour difference between UTC+14 and UTC-11 is 25 (+-1 at a boundary).
        val hours = kiritimati.toEpochHours() - pago.toEpochHours()
        assertEquals(true, hours in 24..26, "got $hours")
    }

    private fun LocalDateTime.toEpochHours(): Long = (date.toEpochDays() * 24L) + hour
}

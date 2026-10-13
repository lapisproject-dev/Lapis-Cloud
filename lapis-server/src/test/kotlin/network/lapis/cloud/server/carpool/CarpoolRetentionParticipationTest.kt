package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.MemberStatus

/** V1.9.125 -- the retention poller removes the participations together with the due posting, and only those. */
class CarpoolRetentionParticipationTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        test("a due posting takes its participations with it; a current posting keeps its own") {
            val driver = data.member(label = "ret-driver")
            val guest = data.member(label = "ret-guest", status = MemberStatus.GUEST)
            val due = data.posting(author = driver, daysAhead = -(CarpoolRetention.RETENTION_DAYS_AFTER_DEPARTURE + 3))
            val current = data.posting(author = driver, daysAhead = 4)
            data.participation(postingId = due, personId = guest, status = CarpoolParticipationStatus.CONFIRMED)
            data.participation(postingId = current, personId = guest, status = CarpoolParticipationStatus.OPEN)
            val now = LocalDateTime(data.today().year, data.today().month, data.today().day, 12, 0)

            CarpoolRetention.deleteDueRows(now)

            data.participationRows(due).size shouldBe 0
            data.participationRows(current).size shouldBe 1
        }
    })

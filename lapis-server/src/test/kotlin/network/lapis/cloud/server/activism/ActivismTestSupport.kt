package network.lapis.cloud.server.activism

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismMaterialDocumentTable
import network.lapis.cloud.server.db.generated.ActivismNoticeTextTable
import network.lapis.cloud.server.db.generated.ActivismReportTable
import network.lapis.cloud.server.db.generated.ActivismResponsibleTable
import network.lapis.cloud.server.db.generated.ActivismSignalTable
import network.lapis.cloud.server.db.generated.ActivismTaskTable
import network.lapis.cloud.server.db.generated.ActivismTextBlockTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ActivismService
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.ServiceCaller
import network.lapis.cloud.shared.domain.ActivismActionInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

internal val ACTIVISM_NOW = LocalDateTime(2026, 11, 5, 10, 0, 0)

/** Clears every activism table (children first) and resets the two settings columns. */
internal fun wipeActivism() {
    transaction {
        ActivismSignalTable.deleteAll()
        ActivismReportTable.deleteAll()
        ActivismContributionTable.deleteAll()
        ActivismMaterialDocumentTable.deleteAll()
        ActivismTextBlockTable.deleteAll()
        ActivismTaskTable.deleteAll()
        ActivismResponsibleTable.deleteAll()
        ActivismActionTable.deleteAll()
        ActivismNoticeTextTable.deleteAll()
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[activismReviewRequired] = false
            it[activismNoticeCountry] = "DE"
        }
    }
}

/** A mutable clock for the 24-hour tests. */
internal class TestClock(
    var now: LocalDateTime = ACTIVISM_NOW,
) {
    operator fun invoke(): LocalDateTime = now
}

internal fun activismInput(
    title: String = "Plakatieren Innenstadt",
    kind: network.lapis.cloud.shared.domain.ActivismKind = network.lapis.cloud.shared.domain.ActivismKind.POSTERING,
    progress: Boolean = true,
    signal: Boolean = false,
    appointment: Boolean = false,
    goal: Int? = 500,
    confirm: Boolean = false,
) = ActivismActionInput(
    title = title,
    kind = kind,
    formProgress = progress,
    formSignal = signal,
    formAppointment = appointment,
    goalQuantity = goal,
    unit = "Plakate",
    confirmContributions = confirm,
)

internal suspend fun <T> ServiceCaller.asMember(
    who: Uuid,
    limiter: ActivismRateLimiter = ActivismRateLimiter(),
    clock: TestClock = TestClock(),
    action: suspend (ActivismService) -> T,
): Result<T> = call(caller = who) { action(ActivismService(call = it, limiter = limiter, clock = clock::invoke)) }

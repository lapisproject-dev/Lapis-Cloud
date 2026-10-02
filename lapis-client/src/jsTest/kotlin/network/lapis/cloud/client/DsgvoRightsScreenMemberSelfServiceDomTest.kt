package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.9.33 -- the mounting contract of [renderMemberAddressSection] and [renderMemberCardRevokeSection], which `DsgvoRightsScreen`
 * calls right after the KI-Zugang card: both are appended to the container they are given, once.
 */
class DsgvoRightsScreenMemberSelfServiceDomTest {
    private val session =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    @Test
    fun bothCards_appearInMeineDaten_afterWhatIsAlreadyThere(): Promise<Unit> =
        formTest {
            AppState.setSession(session)
            val addressRpc =
                object : MemberAddressRpc {
                    var reads = 0

                    override suspend fun getCurrentMember(): network.lapis.cloud.shared.domain.MemberDto {
                        reads++
                        return network.lapis.cloud.shared.domain.MemberDto(
                            id = "member-1",
                            displayName = "Dana Keller",
                            email = "dana@example.org",
                            status = MemberStatus.ACTIVE,
                            joinedAt = LocalDate(2026, 1, 1),
                            role = AccountRole.MEMBER,
                        )
                    }

                    override suspend fun updateAddress(
                        memberId: String,
                        street: String?,
                        postalCode: String?,
                        city: String?,
                        country: String?,
                    ) = getCurrentMember()

                    override suspend fun updateBeneficialOwnerData(
                        memberId: String,
                        dateOfBirth: LocalDate?,
                        nationality: String?,
                    ) = getCurrentMember()
                }
            mountedForm("dsgvo-self-service") { root, element ->
                root.div("Vorheriger Abschnitt")
                renderMemberAddressSection(root, addressRpc)
                renderMemberCardRevokeSection(
                    root,
                    object : MemberCardRevokeRpc {
                        override suspend fun revoke(memberId: String) = error("not called")
                    },
                )
                awaitUntil("cards rendered") { element().textContent.orEmpty().contains("Anschrift speichern") }
                val text = element().textContent.orEmpty()
                assertTrue(text.indexOf("Vorheriger Abschnitt") < text.indexOf("Anschrift und Angaben nach Geldwäschegesetz"))
                assertTrue(text.indexOf("Anschrift und Angaben nach Geldwäschegesetz") < text.indexOf("Mitgliedsausweis"))
                delay(40)
                assertEquals(1, addressRpc.reads)
            }
        }
}

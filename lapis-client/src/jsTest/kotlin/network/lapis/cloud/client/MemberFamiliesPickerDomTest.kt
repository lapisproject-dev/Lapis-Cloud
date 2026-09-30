package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.IMemberService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `MemberFamiliesScreen.memberPicker` stays a plain `Select` fed by a server-side search (limit 20, see
 * `ClientPersonSelectTripwireTest`'s ledger). It used to build its options WITHOUT `untrustedOptions`: a display name carrying the
 * KVision i18n marker rendered through `Widget.translate` -- the same leak class as the carpool card date line. The composed
 * `gettext("%1 (%2)", name, email)` label is now sanitised like every other person option.
 */
class MemberFamiliesPickerDomTest {
    @Test
    fun pickerOptions_neverCarryTheI18nMarker_evenForAForgedDisplayName(): Promise<Unit> =
        formTest {
            val list = routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 20)) }
            val page =
                MemberAdminPageDto(
                    rows =
                        listOf(
                            MemberAdminRowDto(
                                id = "m1",
                                displayName = "${KV_I18N_MARKER}Anna",
                                email = "anna@example.org",
                                status = MemberStatus.ACTIVE,
                                role = null,
                                joinedAt = LocalDate(2024, 1, 1),
                            ),
                        ),
                    totalCount = 1,
                    statusCounts = emptyMap(),
                    limit = 20,
                    offset = 0,
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(jsonOf(MemberAdminPageDto.serializer(), page))
                        else -> request.answerWith("null")
                    }
                },
            ) { _ ->
                mountedForm("families-picker-marker") { root, element ->
                    root.memberPicker("Zahler")
                    awaitUntil("the options load", timeoutMs = 1500) { element().querySelector("select option") != null }
                    val text =
                        element()
                            .allOf("select option")
                            .first()
                            .textContent
                            .orEmpty()
                            .trim()
                    assertFalse(text.contains(KV_I18N_MARKER), "the marker must not leak into the option text: $text")
                    assertEquals("Anna (anna@example.org)", text)
                }
            }
        }
}

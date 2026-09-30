package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMemberPhotoService
import network.lapis.cloud.shared.rpc.IMemberPublicProfileService
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun row(
    id: String,
    name: String,
    hasPhoto: Boolean,
    hasPublicBio: Boolean,
): MemberAdminRowDto =
    MemberAdminRowDto(
        id = id,
        displayName = name,
        email = "$id@example.org",
        status = MemberStatus.ACTIVE,
        role = AccountRole.MEMBER,
        joinedAt = LocalDate(2020, 1, 1),
        hasPhoto = hasPhoto,
        hasPublicBio = hasPublicBio,
    )

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the two moderation buttons of the roster ("Foto entfernen",
 * "Kurzvorstellung entfernen"): offered only to BOARD/ADMIN, only for a row whose DTO reports the
 * presence, behind a confirmation dialog, and followed by an in-place reload of the roster.
 */
class MemberAdminModerationButtonsDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest(block)

    private fun session(role: AccountRole): SessionInfoDto =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    private fun pageJson(vararg rows: MemberAdminRowDto): String =
        jsonOf(
            MemberAdminPageDto.serializer(),
            MemberAdminPageDto(
                rows = rows.toList(),
                totalCount = rows.size,
                statusCounts = mapOf(MemberStatus.ACTIVE to rows.size),
                limit = 25,
                offset = 0,
            ),
        )

    private fun HTMLElement.labelled(label: String): List<HTMLElement> = allOf("button").filter { it.getAttribute("aria-label") == label }

    @Test
    fun boardSeesTheButtonsOnlyOnRowsThatHaveAPhotoOrABio_andRemovalNeedsAConfirmation(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.BOARD))
            val listRoute =
                routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 25)) }
            val photoRoute = routeOf { rpcService<IMemberPhotoService>().moderationRemovePhoto("x") }
            val bioRoute = routeOf { rpcService<IMemberPublicProfileService>().moderationRemoveBio("x") }
            val page =
                pageJson(
                    row("m-both", "Berta Beides", hasPhoto = true, hasPublicBio = true),
                    row("m-none", "Nina Nichts", hasPhoto = false, hasPublicBio = false),
                )
            withFetchStub(respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, page)
                    // Unit-returning RPC: kotlinx.serialization encodes Unit as an empty object.
                    request.rpcRoute == photoRoute || request.rpcRoute == bioRoute -> rpcResult(request.json.id as Int, "{}")
                    else -> rpcResult(request.json.id as Int, "[]")
                }
            }) { recorded ->
                mountedForm("roster-moderation-board") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().textContent.orEmpty().contains("Berta Beides") }
                    val photoButtons = element().labelled("Foto entfernen")
                    val bioButtons = element().labelled("Kurzvorstellung entfernen")
                    assertTrue(photoButtons.isNotEmpty(), "a photo button for the row that has a photo")
                    assertEquals(photoButtons.size, bioButtons.size, "both buttons come from the same single row")
                    photoButtons.first().click()
                    delay(80)
                    assertTrue(recorded.none { it.isRpc && it.rpcRoute == photoRoute }, "nothing removed before the confirmation")
                    val dialog = lastOpenModal()
                    assertTrue(
                        dialog.textContent.orEmpty().contains("Berta Beides"),
                        "the dialog names the member, never shows the picture",
                    )
                    val listCallsBefore = recorded.toRoute(listRoute).size
                    dialog.buttonNamed("Entfernen").click()
                    awaitUntil("the removal RPC") { recorded.any { it.isRpc && it.rpcRoute == photoRoute } }
                    assertEquals("m-both", recorded.singleCall(photoRoute).rpcParam(0) as String)
                    awaitUntil("the roster is reloaded in place") { recorded.toRoute(listRoute).size > listCallsBefore }
                }
            }
        }

    @Test
    fun theBioButtonUsesItsOwnRpc(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.ADMIN))
            val listRoute =
                routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 25)) }
            val bioRoute = routeOf { rpcService<IMemberPublicProfileService>().moderationRemoveBio("x") }
            val page = pageJson(row("m-bio", "Bio Bruno", hasPhoto = false, hasPublicBio = true))
            withFetchStub(respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, page)
                    else -> rpcResult(request.json.id as Int, "[]")
                }
            }) { recorded ->
                mountedForm("roster-moderation-bio") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().textContent.orEmpty().contains("Bio Bruno") }
                    assertTrue(element().labelled("Foto entfernen").isEmpty(), "no photo, no photo button")
                    element().labelled("Kurzvorstellung entfernen").first().click()
                    delay(80)
                    assertTrue(lastOpenModal().textContent.orEmpty().contains("Bio Bruno"))
                    lastOpenModal().buttonNamed("Entfernen").click()
                    awaitUntil("the bio removal RPC") { recorded.any { it.isRpc && it.rpcRoute == bioRoute } }
                    assertEquals("m-bio", recorded.singleCall(bioRoute).rpcParam(0) as String)
                }
            }
        }

    @Test
    fun aTreasurerNeverSeesTheButtons_evenIfTheRowClaimsAPhoto(): Promise<Unit> =
        test {
            AppState.setSession(session(AccountRole.TREASURER))
            val listRoute =
                routeOf { rpcService<IMemberService>().listMembersForAdministration(MemberAdminQuery(search = null, limit = 25)) }
            val page = pageJson(row("m-x", "Kasse Karl", hasPhoto = true, hasPublicBio = true))
            withFetchStub(respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, page)
                    else -> rpcResult(request.json.id as Int, "[]")
                }
            }) {
                mountedForm("roster-moderation-treasurer") { root, element ->
                    renderMemberAdministrationScreen(root)
                    awaitUntil("the roster rows") { element().textContent.orEmpty().contains("Kasse Karl") }
                    assertTrue(element().labelled("Foto entfernen").isEmpty())
                    assertTrue(element().labelled("Kurzvorstellung entfernen").isEmpty())
                }
            }
        }
}

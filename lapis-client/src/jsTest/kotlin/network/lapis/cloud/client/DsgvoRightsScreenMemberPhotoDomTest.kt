package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- "Mein Foto" is the FIRST section of "Meine Daten" (before "Auskunft"), and
 * [confirmDialog]'s new `confirmStyle` parameter keeps `DANGER` as the default while allowing `PRIMARY`.
 */
class DsgvoRightsScreenMemberPhotoDomTest {
    private val session =
        SessionInfoDto(
            memberId = "photo-member-1",
            displayName = "Foto-Testperson",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    @Test
    fun meinFoto_isTheFirstSection_beforeAuskunft(): Promise<Unit> =
        formTest {
            AppState.setSession(session)
            mountedForm("dsgvo-member-photo-first") { root, element ->
                renderDsgvoRightsScreen(root)
                val headings = element().allOf("h2")
                assertEquals(
                    "Mein Foto",
                    headings
                        .first()
                        .textContent
                        .orEmpty()
                        .trim(),
                    "the photo section opens the screen",
                )
                assertEquals("Auskunft", headings[1].textContent.orEmpty().trim(), "and 'Auskunft' follows directly")
            }
        }

    @Test
    fun confirmDialog_keepsDangerByDefault_andAcceptsPrimary(): Promise<Unit> =
        formTest {
            mountedForm("confirm-dialog-style") { _, _ ->
                confirmDialog(title = "Löschen", message = "Sicher?", confirmLabel = "Ja, löschen", onConfirm = {})
                val danger = lastOpenModal().buttonNamed("Ja, löschen")
                assertTrue(danger.classList.contains("btn-danger"), "unchanged default for every destructive caller")
                closeOpenModals(timeoutMs = 500)

                confirmDialog(
                    title = "Foto veröffentlichen",
                    message = "Text",
                    confirmLabel = "Veröffentlichen",
                    confirmStyle = ButtonStyle.PRIMARY,
                    onConfirm = {},
                )
                val primary = lastOpenModal().buttonNamed("Veröffentlichen")
                assertTrue(primary.classList.contains("btn-primary"))
                assertTrue(!primary.classList.contains("btn-danger"))
            }
        }
}

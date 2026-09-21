package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.BoardMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.TransparenzregisterReminderDto
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.4.31 audit fix M4: the six German sentences of the board-membership screen (header note, appointment caption, honesty banner,
 * resolve button, "Erledigt"/"Offen", "Bestätigt von ... am ...") used to bypass `tr()`/`gettext()`, so no language but German ever
 * saw them. Proven here by installing a catalog that maps every one of them to a visibly different text and reading the REAL screen.
 */
class BoardMembershipTranslationDomTest {
    private fun reminder(resolved: Boolean) =
        TransparenzregisterReminderDto(
            id = if (resolved) "r2" else "r1",
            triggeredAt = LocalDateTime(2026, 1, 1, 0, 0),
            memberId = "member-alice",
            memberDisplayName = "Alice",
            committeeRole = CommitteeRole.CHAIR,
            changeType = BoardChangeType.JOINED,
            resolved = resolved,
            resolvedAt = if (resolved) LocalDateTime(2026, 1, 2, 0, 0) else null,
            resolvedById = if (resolved) "member-bob" else null,
            resolvedByDisplayName = if (resolved) "Bob" else null,
        )

    private val translations =
        mapOf(
            BOARD_MEMBERSHIP_HEADER_NOTE to "T-HEADER",
            MANUAL_APPOINTMENT_CAPTION to "T-MANUAL",
            TRANSPARENZREGISTER_REMINDER_HONESTY_BANNER to "T-BANNER",
            RESOLVE_REMINDER_BUTTON_LABEL to "T-RESOLVE",
            BOARD_COMMITTEE_CROSS_LINK_CAPTION to "T-CROSSLINK",
            "Erledigt" to "T-DONE",
            "Offen" to "T-OPEN",
            "Bestätigt von %1 am %2" to "T-CONFIRMED %1 / %2",
        )

    @Test
    fun everySentenceOfTheScreenGoesThroughTheCatalog(): Promise<Unit> =
        formTest {
            val remindersRoute = routeOf { rpcService<IBoardMembershipService>().listTransparenzregisterReminders(true) }
            val boardRoute = routeOf { rpcService<IBoardMembershipService>().listCurrentBoard() }
            val board =
                BoardMembershipDto(
                    id = "b1",
                    memberId = "member-alice",
                    memberDisplayName = "Alice",
                    committeeRole = CommitteeRole.CHAIR,
                    startedAt = LocalDate(2026, 1, 1),
                    endedAt = null,
                )
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == remindersRoute ->
                        request.answerWith(
                            jsonOf(ListSerializer(TransparenzregisterReminderDto.serializer()), listOf(reminder(false), reminder(true))),
                        )
                    request.rpcRoute == boardRoute ->
                        request.answerWith(jsonOf(ListSerializer(BoardMembershipDto.serializer()), listOf(board)))
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withTranslationsAsync(translations) {
                withFetchStub(respond) { _ ->
                    mountedForm("m4-board") { root, element ->
                        renderBoardMembershipScreen(root)
                        awaitUntil("reminders and the roster are rendered") {
                            val text = element().textContent.orEmpty()
                            text.contains("T-RESOLVE") && text.contains("T-CROSSLINK")
                        }
                        val text = element().textContent.orEmpty()
                        listOf(
                            "T-HEADER",
                            "T-MANUAL",
                            "T-BANNER",
                            "T-RESOLVE",
                            "T-CROSSLINK",
                            "T-DONE",
                            "T-OPEN",
                            "T-CONFIRMED Bob",
                        ).forEach {
                            assertTrue(text.contains(it), "not translated: '$it' -- the screen shows: $text")
                        }
                        // ... and none of the German sources is left on screen
                        listOf(
                            "automatisch aus Wahlen",
                            "Wahlfunktion",
                            "kann die tatsächliche Meldung",
                            "Ich habe das Register aktualisiert",
                            "Sitz im Gremium",
                            "Bestätigt von",
                        ).forEach { assertTrue(!text.contains(it), "German source still on screen: '$it'") }
                    }
                }
            }
        }

    @Test
    fun theLabelHelpersTranslate_andTheCaptionKeepsItsArguments() {
        withTranslations(translations) {
            assertEquals("T-DONE", reminderResolutionLabel(true))
            assertEquals("T-OPEN", reminderResolutionLabel(false))
            assertEquals("T-CONFIRMED Bob / 2026-01-02T00:00", resolvedCaption(reminder(true)))
        }
    }
}

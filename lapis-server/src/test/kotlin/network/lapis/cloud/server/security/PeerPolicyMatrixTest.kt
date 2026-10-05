package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerDenyReason
import kotlin.uuid.Uuid

/**
 * Welle V1.9.57 -- the COMPLETE cross product of the peer-protection matrix: actor {MEMBER, TREASURER, BOARD, ADMIN} x
 * target {no account, MEMBER, TREASURER, BOARD, ADMIN, SELF} x all [PeerAction]s x eligible approvers {0, 1, 2} x mail
 * {configured, not}. The expectations are DATA (one row per action, one cell per actor/target), not logic -- and the test
 * pins that the table has a cell for EVERY combination, so a new [PeerAction] or role cannot slip in unclassified.
 *
 * Cell codes: F = Deny(NOT_PERMITTED), S = Deny(SELF_TARGET), T = Deny(TARGET_IS_ADMIN), P = Deny(PROTECTED_TARGET),
 * A = Allow, An = Allow + notify target, Ao = Allow + notify all other admins, M = Mask, R = approval-type (see
 * [expectedApproval]).
 */
class PeerPolicyMatrixTest :
    FunSpec({
        // Column order of every row: NONE, MEMBER, TREASURER, BOARD, ADMIN, SELF
        val table: Map<PeerAction, Map<AccountRole, String>> =
            mapOf(
                PeerAction.READ_PROTECTED_DATA to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F A",
                        AccountRole.TREASURER to "F F F F F A",
                        AccountRole.BOARD to "A A A A M A",
                        AccountRole.ADMIN to "A A A A A A",
                    ),
                PeerAction.WRITE_PROTECTED_DATA to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F A",
                        AccountRole.TREASURER to "F F F F F A",
                        AccountRole.BOARD to "A A A A P A",
                        AccountRole.ADMIN to "A A A A An A",
                    ),
                PeerAction.ERASE to
                    mapOf(
                        AccountRole.MEMBER to "F F F F T A",
                        AccountRole.TREASURER to "F F F F T A",
                        AccountRole.BOARD to "F F F F T A",
                        AccountRole.ADMIN to "A A A A T T",
                    ),
                PeerAction.LINK_IDENTITY to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F F",
                        AccountRole.TREASURER to "F F F F F F",
                        AccountRole.BOARD to "F F F F F F",
                        AccountRole.ADMIN to "A A A A T A",
                    ),
                PeerAction.EMAIL_OVERRIDE to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F F",
                        AccountRole.TREASURER to "F F F F F F",
                        AccountRole.BOARD to "F F F F F F",
                        AccountRole.ADMIN to "A A A A T S",
                    ),
                PeerAction.TEMP_PASSWORD to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F F",
                        AccountRole.TREASURER to "F F F F F F",
                        AccountRole.BOARD to "F F F F F F",
                        AccountRole.ADMIN to "An An An An R S",
                    ),
                PeerAction.RESET_MAIL to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F F",
                        AccountRole.TREASURER to "F F F F F F",
                        AccountRole.BOARD to "F F F F F F",
                        AccountRole.ADMIN to "A A A A An S",
                    ),
                PeerAction.DEMOTE to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F F",
                        AccountRole.TREASURER to "F F F F F F",
                        AccountRole.BOARD to "F F F F F F",
                        AccountRole.ADMIN to "A A A A R S",
                    ),
                PeerAction.PROMOTE_TO_ADMIN to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F F",
                        AccountRole.TREASURER to "F F F F F F",
                        AccountRole.BOARD to "F F F F F F",
                        AccountRole.ADMIN to "Ao Ao Ao Ao Ao S",
                    ),
                PeerAction.SUSPEND to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F S",
                        AccountRole.TREASURER to "F F F F F S",
                        AccountRole.BOARD to "A A F F F S",
                        AccountRole.ADMIN to "A A A A R S",
                    ),
                PeerAction.NON_BLOCKING_STATUS to
                    mapOf(
                        AccountRole.MEMBER to "F F F F F S",
                        AccountRole.TREASURER to "F F F F F S",
                        AccountRole.BOARD to "A A F F F S",
                        AccountRole.ADMIN to "A A A A An S",
                    ),
            )
        val targets = listOf("NONE", "MEMBER", "TREASURER", "BOARD", "ADMIN", "SELF")

        fun expectedApproval(
            action: PeerAction,
            eligible: Int,
            mail: Boolean,
        ): PeerDecision =
            when {
                action == PeerAction.TEMP_PASSWORD && !mail -> PeerDecision.Deny(PeerDenyReason.MAIL_UNAVAILABLE)
                eligible == 0 -> PeerDecision.Deny(PeerDenyReason.NO_SECOND_ADMIN)
                else -> PeerDecision.RequiresApproval(eligibleApprovers = eligible)
            }

        fun expected(
            code: String,
            action: PeerAction,
            eligible: Int,
            mail: Boolean,
        ): PeerDecision =
            when (code) {
                "F" -> PeerDecision.Deny(PeerDenyReason.NOT_PERMITTED)
                "S" -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                "T" -> PeerDecision.Deny(PeerDenyReason.TARGET_IS_ADMIN)
                "P" -> PeerDecision.Deny(PeerDenyReason.PROTECTED_TARGET)
                "A" -> PeerDecision.Allow()
                "An" -> PeerDecision.Allow(notifyTarget = true)
                "Ao" -> PeerDecision.Allow(notifyOtherAdmins = true)
                "M" -> PeerDecision.Mask
                "R" -> expectedApproval(action, eligible, mail)
                else -> error("unknown cell code $code")
            }

        test("the expectation table has a cell for EVERY action x actor x target") {
            PeerAction.entries.toSet() shouldBe table.keys
            table.forEach { (action, rows) ->
                rows.keys shouldBe setOf(AccountRole.MEMBER, AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
                rows.forEach { (role, row) ->
                    withClue(clue = "$action / $role") { row.split(" ").size shouldBe targets.size }
                }
            }
        }

        test("PeerPolicy.decide matches the table for the full cross product") {
            var checked = 0
            table.forEach { (action, rows) ->
                rows.forEach { (actorRole, row) ->
                    val cells = row.split(" ")
                    targets.forEachIndexed { index, targetKind ->
                        val actorId = Uuid.random()
                        val self = targetKind == "SELF"
                        val targetId = if (self) actorId else Uuid.random()
                        val targetRole =
                            when (targetKind) {
                                "NONE" -> null
                                "SELF" -> actorRole
                                else -> AccountRole.valueOf(targetKind)
                            }
                        for (eligible in 0..2) {
                            for (mail in listOf(true, false)) {
                                val actual =
                                    PeerPolicy.decide(
                                        actorRole = actorRole,
                                        actorId = actorId,
                                        targetRole = targetRole,
                                        targetId = targetId,
                                        action = action,
                                        eligibleApprovers = eligible,
                                        mailConfigured = mail,
                                    )
                                withClue(clue = "$actorRole -> $targetKind $action eligible=$eligible mail=$mail") {
                                    actual shouldBe expected(cells[index], action, eligible, mail)
                                }
                                checked++
                            }
                        }
                    }
                }
            }
            checked shouldBe PeerAction.entries.size * 4 * targets.size * 3 * 2
        }

        test("a non-ADMIN never gets an approval path and never touches an ADMIN target destructively") {
            val destructive =
                setOf(PeerAction.TEMP_PASSWORD, PeerAction.DEMOTE, PeerAction.SUSPEND, PeerAction.LINK_IDENTITY, PeerAction.EMAIL_OVERRIDE)
            for (role in listOf(AccountRole.MEMBER, AccountRole.TREASURER, AccountRole.BOARD)) {
                for (action in destructive) {
                    val d =
                        PeerPolicy.decide(
                            actorRole = role,
                            actorId = Uuid.random(),
                            targetRole = AccountRole.ADMIN,
                            targetId = Uuid.random(),
                            action = action,
                            eligibleApprovers = 5,
                            mailConfigured = true,
                        )
                    withClue(clue = "$role $action") { (d is PeerDecision.Deny) shouldBe true }
                }
            }
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }

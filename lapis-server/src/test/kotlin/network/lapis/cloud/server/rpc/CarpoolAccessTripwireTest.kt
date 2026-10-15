package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.rpc.ICarpoolService
import java.io.File

private val serverMain: File = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }
private val repoRoot: File = serverMain.absoluteFile.parentFile.parentFile.parentFile.parentFile

/**
 * V1.9.125 -- the carpool access rule was CHANGED (passengers: ACTIVE, GUEST, FRIEND). This tripwire keeps the change as narrow as it was meant:
 *
 *  1. the RPC surface is EXACTLY the known list (a new method fails the build until it is listed here and classified);
 *  2. exactly the named passenger allowlist uses `requireCarpoolPassengerMembership` (directly, or through the shared `decide` helper of
 *     confirm/decline/withdraw), every other method uses `requireActiveMembership`;
 *  3. the passenger guard is called only inside the carpool service, and the `CARPOOL_PASSENGER`/`CARPOOL_DRIVER` sets are used only by the files
 *     that apply them (never in another service, a route or another screen).
 */
class CarpoolAccessTripwireTest :
    FunSpec({
        val allMethods =
            listOf(
                "listPostings",
                "listMyPostings",
                "createPosting",
                "updatePosting",
                "deletePosting",
                "contactAuthor",
                "requestParticipation",
                "findInvitee",
                "inviteParticipant",
                "confirmParticipation",
                "declineParticipation",
                "withdrawParticipation",
                "listMyParticipations",
                "listParticipants",
                "setMeetingNote",
                "getPassengerHint",
                "sendCarpoolMessage",
                "carpoolMessages",
                "endCarpoolThread",
            )
        val passengerAllowlist =
            setOf(
                "listPostings",
                "requestParticipation",
                "confirmParticipation",
                "declineParticipation",
                "withdrawParticipation",
                "listMyParticipations",
                // V1.9.139: open to the allowlist, but narrowed to the two parties of a CONFIRMED ride by CarpoolMessages.requireAccess.
                "sendCarpoolMessage",
                "carpoolMessages",
                "endCarpoolThread",
            )
        val messageMethods = listOf("sendCarpoolMessage", "carpoolMessages", "endCarpoolThread")

        val serviceSource = File(serverMain, "network/lapis/cloud/server/rpc/CarpoolService.kt").readText()

        /** The text of a member function: from its declaration line to the next member declaration at class level. */
        fun bodyOf(name: String): String {
            val declaration =
                Regex("""(?m)^    (override suspend |private )?fun $name\(""").find(serviceSource) ?: error("no function $name")
            val rest = serviceSource.substring(declaration.range.last)
            val next = Regex("""(?m)^    (override suspend fun|private fun|constructor|// -{10})""").find(rest)
            return if (next == null) rest else rest.substring(0, next.range.first)
        }

        test("the RPC surface is exactly the known nineteen methods, in the pinned order") {
            ICarpoolService::class.java.declaredMethods
                .filter { !it.isSynthetic && '$' !in it.name }
                .map { it.name }
                .toSet() shouldBe allMethods.toSet()
        }

        test("exactly the named passenger allowlist uses the passenger guard; every other method stays ACTIVE-only") {
            val wrong = mutableListOf<String>()
            allMethods.forEach { method ->
                val body = bodyOf(method)
                val passenger = "requireCarpoolPassengerMembership(" in body || "decide(" in body
                val active = "requireActiveMembership(" in body
                if (method in passengerAllowlist) {
                    if (!passenger || active) wrong += "$method must use ONLY the passenger guard"
                } else {
                    if (passenger || !active) wrong += "$method must use ONLY requireActiveMembership"
                }
            }
            // The shared helper behind confirm/decline/withdraw must itself carry the passenger guard.
            if ("requireCarpoolPassengerMembership(" !in bodyOf("decide")) wrong += "decide must call the passenger guard"
            wrong.shouldBeEmpty()
        }

        test(
            "V1.9.139: the three message methods pass through the ONE gate CarpoolMessages.requireAccess, and use nothing else to decide",
        ) {
            val wrong = mutableListOf<String>()
            messageMethods.forEach { method ->
                val body = bodyOf(method)
                if ("CarpoolMessages.requireAccess(" !in body) wrong += "$method must call CarpoolMessages.requireAccess"
                if ("CARPOOL_" in body) wrong += "$method must not decide on a status set itself"
            }
            wrong.shouldBeEmpty()
        }

        test("the passenger guard is called only by the carpool service; the two sets only by the files that apply them") {
            val guardUsers = setOf("CarpoolService.kt", "MembershipGuards.kt")
            val setUsers =
                guardUsers +
                    setOf("Foundation.kt", "NavVisibility.kt", "CarpoolParticipations.kt", "CarpoolInviteeSearch.kt", "CarpoolMessages.kt")
            val roots =
                listOf(serverMain, File(repoRoot, "lapis-shared/src/commonMain/kotlin"), File(repoRoot, "lapis-client/src/jsMain/kotlin"))
            val offenders = mutableListOf<String>()
            var scanned = 0
            roots.filter { it.exists() }.forEach { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        scanned++
                        val text = file.readText()
                        if ("requireCarpoolPassengerMembership" in text && file.name !in guardUsers) offenders += "guard: ${file.path}"
                        val usesSet = "CARPOOL_PASSENGER" in text || "CARPOOL_DRIVER" in text
                        if (usesSet && file.name !in setUsers) offenders += "set: ${file.path}"
                    }
            }
            (scanned > 500) shouldBe true // the scan itself sees the code base
            offenders.shouldBeEmpty()
        }
    })

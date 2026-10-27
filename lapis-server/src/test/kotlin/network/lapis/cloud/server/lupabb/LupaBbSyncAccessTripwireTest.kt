package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.rpc.ILupaBbSyncService

/**
 * V1.9.226 -- the sync can end memberships, so EVERY method of its RPC service starts with the ADMIN gate. A new method fails the build until it
 * does. Also pinned: nothing in the package deletes a member, creates an account or sends a mail, and the e-mail address is written once only
 * (the insert of a NEW member).
 */
class LupaBbSyncAccessTripwireTest :
    FunSpec({
        val methods =
            setOf(
                "getStatus",
                "verifyAndSaveCredentials",
                "removeCredentials",
                "loadPreview",
                "getPreviewPage",
                "applyPreview",
                "linkMemberToLupaBb",
            )
        val lupabbFiles = SourceScan.mainFiles().filter { "/lupabb/" in it.path }

        test("the RPC surface is exactly the seven known methods") {
            ILupaBbSyncService::class.java.declaredMethods
                .filter { !it.isSynthetic && '$' !in it.name }
                .map { it.name }
                .toSet() shouldBe methods
        }

        test("every RPC method of the service starts with resolveCurrentMember(call) and the ADMIN gate") {
            val code = SourceScan.blank(SourceScan.mainFiles().single { it.name == "LupaBbSyncService.kt" }.readText())
            val headers = Regex("""override suspend fun (\w+)\([^)]*\)\s*:\s*[\w<>?, ]+\s*\{""").findAll(code).toList()
            headers.map { it.groupValues[1] }.toSet() shouldBe methods
            val offenders =
                headers.filterNot { m ->
                    val body = code.substring(m.range.last + 1).trimStart()
                    val first = body.lineSequence().first().trim()
                    val second =
                        body
                            .lineSequence()
                            .drop(1)
                            .first()
                            .trim()
                    (first == "resolveCurrentMember(call).requireRole(AccountRole.ADMIN)") ||
                        (first == "val current = resolveCurrentMember(call)" && second == "current.requireRole(AccountRole.ADMIN)")
                }
            offenders.map { it.groupValues[1] }.shouldBeEmpty()
        }

        test("the package never deletes a member, creates an account or touches the mail pipeline") {
            val forbidden =
                listOf(
                    Regex("""\bMemberTable\s*\.\s*(deleteWhere|deleteAll|deleteIgnoreWhere)\b"""),
                    Regex("""\bAccountTable\s*\.\s*(insert|update|upsert|batchInsert|deleteWhere|deleteAll)\b"""),
                    Regex("""\bMailOutbox\w*|\bMailDispatcher\b|\bMailTransport\b|\bPeerNotifier\b|\bpeerNotifier\b"""),
                    Regex("""\bwebhook\w*Publisher\b""", RegexOption.IGNORE_CASE),
                )
            val offenders =
                lupabbFiles.flatMap { file ->
                    val code = SourceScan.blank(file.readText())
                    forbidden.filter { it.containsMatchIn(code) }.map { "${file.name}: ${it.pattern}" }
                }
            offenders.shouldBeEmpty()
            // the only mail related import is the address syntax check
            lupabbFiles
                .flatMap { f -> f.readLines().filter { it.startsWith("import network.lapis.cloud.server.mail.") }.map { f.name to it } }
                .map { it.second } shouldBe listOf("import network.lapis.cloud.server.mail.isValidMailboxAddress")
        }

        test("the e-mail address of a member is assigned once in the package: the insert of a NEW member") {
            val assignments =
                lupabbFiles.sumOf { Regex("""\[\s*(MemberTable\.)?email\s*]\s*=""").findAll(SourceScan.blank(it.readText())).count() }
            assignments shouldBe 1
            val applier = SourceScan.blank(lupabbFiles.single { it.name == "LupaBbApplier.kt" }.readText())
            val insertAt = applier.indexOf("private fun insertMember")
            val updateAt = applier.indexOf("private fun updateMember")
            val assignAt = Regex("""\[\s*(MemberTable\.)?email\s*]\s*=""").find(applier)!!.range.first
            (assignAt in insertAt until updateAt) shouldBe true
        }

        test("the legacy member.country text is never written") {
            lupabbFiles
                .filter { Regex("""\[\s*(MemberTable\.)?country\s*]\s*=""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("the status of a member is changed only by the central path, with the LUPABB_SYNC source") {
            val applier = SourceScan.blank(lupabbFiles.single { it.name == "LupaBbApplier.kt" }.readText())
            Regex("""MemberRoleStatusMutations\.applyStatusChangeLocked\(""").findAll(applier).count() shouldBe 1
            applier.contains("historySource = MemberStatusHistorySource.LUPABB_SYNC") shouldBe true
            applier.contains("MemberStatusHistorySource.LUPABB_SYNC") shouldBe true
        }
    })

package network.lapis.cloud.server.activism

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.rpc.IActivismService
import java.io.File

/**
 * V1.9.246 -- structural guarantees of the activism module that a later edit must not lose without a failing build.
 */
class ActivismTripwireTest :
    FunSpec({
        val activismFiles = SourceScan.mainFiles().filter { "/server/activism/" in it.path }
        val serviceFile = SourceScan.mainFiles().single { it.name == "ActivismService.kt" }
        val allowedTableUsers = setOf("ActivismPersonalData.kt", "OrganizationSchemaCatalog.kt")
        val serviceCode = SourceScan.blank(serviceFile.readText())

        fun String.insideTransactions(): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            Regex("""\btransaction\s*\{""").findAll(this).forEach { m ->
                var depth = 1
                var i = m.range.last + 1
                while (i < length && depth > 0) {
                    if (this[i] == '{') depth++
                    if (this[i] == '}') depth--
                    i++
                }
                ranges += m.range.first until i
            }
            return ranges
        }

        test("only the activism store (and the DSGVO contributor and the backup catalog) touch the activism tables") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { "/db/generated/" !in it.path }
                    .filter { it.name !in allowedTableUsers && it.name != "ActivismStore.kt" }
                    .filter { Regex("""\bActivism\w+Table\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
            // the service reads titles of actions for the report list: it is the ONE other reader, via the two typed tables below
            offenders.toSet() shouldBe setOf("ActivismService.kt")
            val serviceTables = Regex("""\bActivism\w+Table\b""").findAll(serviceCode).map { it.value }.toSet()
            withClue("the service must not use the signal table or write any table") {
                serviceTables.contains("ActivismSignalTable") shouldBe false
                Regex(
                    """\bActivism\w+Table\s*\.\s*(insert|update|deleteWhere|deleteAll|upsert|batchInsert)\b""",
                ).containsMatchIn(serviceCode) shouldBe
                    false
            }
        }

        test("only ActivismStore.kt and the DSGVO contributor use the signal table; no function returns the persons behind a signal") {
            SourceScan
                .mainFiles()
                .filter { "/db/generated/" !in it.path }
                .filter { Regex("""\bActivismSignalTable\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet() shouldBe setOf("ActivismStore.kt", "ActivismPersonalData.kt")
            val store = SourceScan.blank(File(activismFiles.single { it.name == "ActivismStore.kt" }.path).readText())
            val signalPart = store.substring(store.indexOf("internal object ActivismSignalStore"))
            Regex("""fun (\w+)\(""").findAll(signalPart).map { it.groupValues[1] }.toSet() shouldBe
                setOf("hasSignal", "countOf", "set", "clear", "signalsOfMember", "eraseMember")
            signalPart.contains("List<Uuid>") shouldBe true // only signalsOfMember (action ids)
            Regex("""fun \w+\([^)]*\)\s*:\s*(List|Set|Map)<""").findAll(signalPart).count() shouldBe 1
        }

        test("the signal is never audited and the report creation is not audited") {
            val code = serviceCode
            val setSignal = code.substringAfter("override suspend fun setSignal(").substringBefore("override suspend fun reportAction(")
            setSignal.contains("audit(") shouldBe false
            val reportAction = code.substringAfter("override suspend fun reportAction(").substringBefore("override suspend fun listReports")
            reportAction.contains("audit(") shouldBe false
        }

        test("the rate limiter is never called inside a transaction block") {
            val ranges = serviceCode.insideTransactions()
            val calls = Regex("""\blimiter\.|\bplanningRate\(|\bparticipationRate\(""").findAll(serviceCode).map { it.range.first }.toList()
            calls.filter { pos -> ranges.any { pos in it } }.shouldBeEmpty()
            // the helpers themselves are the only place that calls the limiter outside the methods
            (calls.size > 10) shouldBe true
        }

        test("the module has no LTR reference and no content detection") {
            val files = activismFiles + serviceFile + SourceScan.mainFiles().single { it.name == "ActivismPersonalData.kt" }
            files
                .filter { Regex("""Ltr|LTR|Libertaler""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
            // no Regex and no word list in the package: text is plain text and is never judged by its meaning
            activismFiles
                .filter {
                    Regex(
                        """Regex\(|\.toRegex\(|blocklist|badWords|forbiddenWords|profanity""",
                        RegexOption.IGNORE_CASE,
                    ).containsMatchIn(SourceScan.blank(it.readText()))
                }.map { it.name }
                .shouldBeEmpty()
        }

        test(
            "the RPC surface is the known set of methods and each one starts with the participant gate (or a manager gate that includes it)",
        ) {
            val methods =
                IActivismService::class.java.declaredMethods
                    .filter { !it.isSynthetic && '$' !in it.name }
                    .map { it.name }
                    .toSet()
            methods.size shouldBe 37
            val headers = Regex("""override suspend fun (\w+)\([^)]*\)\s*(?::\s*[\w<>?, ]+)?\s*\{""").findAll(serviceCode).toList()
            headers.map { it.groupValues[1] }.toSet() shouldBe methods
            val offenders =
                headers.filterNot { m ->
                    serviceCode.substring(m.range.last + 1).trimStart().startsWith("val viewer = participant()")
                }
            offenders.map { it.groupValues[1] }.shouldBeEmpty()
        }

        test("rights are decided only by ActivismAccessPolicy: the service names no account role") {
            serviceCode.contains("AccountRole") shouldBe false
            val users =
                SourceScan
                    .mainFiles()
                    .filter { Regex("""ActivismAccessPolicy""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            users shouldBe setOf("ActivismAccessPolicy.kt", "ActivismService.kt")
        }

        test("the activism service is registered LAST in Application.kt, after the partner servers") {
            val code = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/Application.kt").readText())
            val registrations = Regex("""registerService\(""").findAll(code).map { it.range.first }.toList()
            registrations.last() shouldBe code.indexOf("registerService(IActivismService::class)")
        }

        test("MemberStatusSets.ACTIVISM_PARTICIPANT is used only by the activism files") {
            SourceScan
                .mainFiles()
                .filter { SourceScan.blank(it.readText()).contains("ACTIVISM_PARTICIPANT") }
                .map { it.name }
                .toSet() shouldBe setOf("ActivismAccessPolicy.kt", "ActivismStore.kt")
        }

        test("no log line of the activism module names a title, a reason, a note, an area or a name") {
            (activismFiles + serviceFile)
                .flatMap { file ->
                    SourceScan
                        .blank(file.readText())
                        .lines()
                        .filter { it.contains("logger.") }
                        .filter { Regex("""title|reason|note|area|name|text|body""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                        .map { file.name }
                }.shouldBeEmpty()
        }
    })

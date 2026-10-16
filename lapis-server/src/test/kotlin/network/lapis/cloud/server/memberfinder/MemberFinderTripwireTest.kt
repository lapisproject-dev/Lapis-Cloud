package network.lapis.cloud.server.memberfinder

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.domain.MemberFinderBlockDto
import network.lapis.cloud.shared.domain.MemberFinderBoardOverviewDto
import network.lapis.cloud.shared.domain.MemberFinderConnectionDto
import network.lapis.cloud.shared.domain.MemberFinderConsentTextDto
import network.lapis.cloud.shared.domain.MemberFinderIncomingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderOutgoingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderRegionBandDto
import network.lapis.cloud.shared.domain.MemberFinderResponseDto
import network.lapis.cloud.shared.domain.MemberFinderStateDto
import java.io.File

/**
 * V1.9.144 tripwires of the member finder (Art. 9 GDPR): what the API can ever return, what is ever logged or audited, what is ever stored, who may touch
 * the tables, and that every method goes through the one gate. A tripwire fails loudly when the next change tries to weaken one of them.
 */
class MemberFinderTripwireTest :
    FunSpec({
        val serverMain = SourceScan.mainRoot()
        val sharedRoot =
            File(
                "../lapis-shared/src/commonMain/kotlin",
            ).let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin") }
        val serviceFile = File(serverMain, "network/lapis/cloud/server/rpc/MemberFinderService.kt")
        val personalDataFile = File(serverMain, "network/lapis/cloud/server/dsgvo/MemberFinderPersonalData.kt")
        val finderDir = File(serverMain, "network/lapis/cloud/server/memberfinder")
        val interfaceFile = File(sharedRoot, "network/lapis/cloud/shared/rpc/IMemberFinderService.kt")

        // ── (a) the DTO allowlist ──────────────────────────────────────────────────────────────────────────────────────────────────────

        // The explicit list of every type the service may return. A method with a return type outside this list fails the test.
        val dtoSerializers: Map<String, KSerializer<*>> =
            mapOf(
                "MemberFinderStateDto" to serializer<MemberFinderStateDto>(),
                "MemberFinderConsentTextDto" to serializer<MemberFinderConsentTextDto>(),
                "MemberFinderOutgoingRequestDto" to serializer<MemberFinderOutgoingRequestDto>(),
                "MemberFinderIncomingRequestDto" to serializer<MemberFinderIncomingRequestDto>(),
                "MemberFinderConnectionDto" to serializer<MemberFinderConnectionDto>(),
                "MemberFinderBlockDto" to serializer<MemberFinderBlockDto>(),
                "MemberFinderBoardOverviewDto" to serializer<MemberFinderBoardOverviewDto>(),
                "MemberFinderRegionBandDto" to serializer<MemberFinderRegionBandDto>(),
                "MemberFinderResponseDto" to serializer<MemberFinderResponseDto>(),
            )

        // `DirectMessageDto` is the answer of `contactConnection`: it exists only AFTER both sides agreed and carries the message the caller just
        // sent (the other side is known to the caller by then). It is the one deliberate exemption.
        val exempt = setOf("DirectMessageDto", "Unit")
        val allowedIdNames = setOf("requestId", "connectionId", "blockId")
        val forbidden =
            Regex(
                """(?i)((member|from|to|sender|recipient|target|other|blocker|blocked)_?id|email|postal|street|city|birth|regionKey)""",
            )

        fun names(descriptor: SerialDescriptor): List<String> =
            (0 until descriptor.elementsCount).flatMap { i ->
                listOf(descriptor.getElementName(i)) + names(descriptor.getElementDescriptor(i))
            }

        fun returnTypes(): List<String> {
            val code = SourceScan.blank(interfaceFile.readText())
            return Regex("""suspend fun \w+\(([^)]*)\)\s*(?::\s*([A-Za-z]+(?:<([A-Za-z]+)>)?))?""")
                .findAll(code)
                .map { m -> m.groupValues[3].ifEmpty { m.groupValues[2] }.ifEmpty { "Unit" } }
                .toList()
        }

        test(
            "(a) every type the service returns is on the explicit list; no element of any of them can name a member, an address or a region",
        ) {
            val types = returnTypes()
            types.size shouldBe 15
            types.filter { it !in dtoSerializers && it !in exempt }.shouldBeEmpty()
            for ((dto, ser) in dtoSerializers) {
                val offenders = names(ser.descriptor).filter { it !in allowedIdNames && forbidden.containsMatchIn(it) }
                withClue(dto) { offenders.shouldBeEmpty() }
            }
        }

        test("(a) the DTO files contain no field that could carry a foreign id (a source-level second look)") {
            val code = SourceScan.blank(File(sharedRoot, "network/lapis/cloud/shared/domain/MemberFinder.kt").readText())
            Regex("""val\s+(\w+)\s*:""")
                .findAll(code)
                .map { it.groupValues[1] }
                .filter {
                    it !in allowedIdNames &&
                        forbidden.containsMatchIn(it)
                }.toList()
                .shouldBeEmpty()
        }

        // ── (c) logs, audit and mail ───────────────────────────────────────────────────────────────────────────────────────────────────

        test(
            "(c) no log line of the finder names a note, a region, a name or a member id; no audit recorder, println or mail in the finder files",
        ) {
            val files = finderDir.listFiles { f -> f.extension == "kt" }!!.toList() + serviceFile + personalDataFile
            val offenders = mutableListOf<String>()
            for (file in files) {
                val raw = file.readText()
                val code = SourceScan.blank(raw)
                if (code.contains("AuditLogRecorder")) offenders += "${file.name}: AuditLogRecorder"
                if (Regex("""\bprintln\(|System\.(out|err)""").containsMatchIn(code)) offenders += "${file.name}: println"
                if (Regex("""(?i)mailer|MailOutbox|sendMail""").containsMatchIn(code)) offenders += "${file.name}: mail"
                // the logger lines are string templates; look at the RAW text of every line that logs
                raw.lines().filter { Regex("""\blogger\.(trace|debug|info|warn|error)""").containsMatchIn(it) }.forEach { line ->
                    if (Regex(
                            """(?i)\b(note|region|displayName|memberId|fromMember|toMember|name)\b|\$\{?(it|row|member|current)""",
                        ).containsMatchIn(line)
                    ) {
                        offenders += "${file.name}: $line"
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test(
            "(c) the switch service writes the audit entry with the version and the hash only: no count, no member in the snapshot builder",
        ) {
            val code = File(serverMain, "network/lapis/cloud/server/rpc/OrganizationSettingsService.kt").readText()
            val builder = code.substringAfter("private fun finderSnapshot(")
            builder.contains("count", ignoreCase = true) shouldBe false
            builder.contains("memberId", ignoreCase = true) shouldBe false
        }

        test("(c) no mail template mentions the finder (the note never goes into an email)") {
            val offenders =
                serverMain
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.path.contains("/mail/") }
                    .filter { it.readText().contains("MemberFinder") }
                    .map { it.name }
                    .toList()
            offenders.shouldBeEmpty()
        }

        // ── (d) nothing stored ─────────────────────────────────────────────────────────────────────────────────────────────────────────

        test("(d) no column named region, postal code or city on a person table; region_key exists only on the aggregate") {
            val generated = File(serverMain, "network/lapis/cloud/server/db/generated")
            val personTables = listOf("MemberFinderConsentEventTable.kt", "MemberFinderRequestTable.kt", "MemberFinderBlockTable.kt")
            for (name in personTables) {
                val code = File(generated, name).readText()
                Regex("""(?i)"[a-z_]*(region|postal|city)[a-z_]*"""").containsMatchIn(code) shouldBe false
            }
            File(generated, "MemberFinderRegionBandTable.kt").readText().contains("\"region_key\"") shouldBe true
            // the aggregate has no member column at all
            Regex(""""([a-z_]+)"""")
                .findAll(
                    File(
                        generated,
                        "MemberFinderRegionBandTable.kt",
                    ).readText().lines().filter { it.contains("public val") }.joinToString("\n"),
                ).map { it.groupValues[1] }
                .toSet() shouldBe setOf("region_key", "band", "computed_at")
            // and the model and migration say the same
            val model =
                File(File(serverMain.parentFile, "kuml"), "72-member-finder.kuml.kts")
                    .readLines()
                    .filterNot { it.trim().startsWith("//") }
                    .joinToString("\n")
            val perEntity = model.split("classOf(name = ")
            perEntity
                .filter {
                    it.startsWith("\"MemberFinderConsentEvent\"") ||
                        it.startsWith("\"MemberFinderRequest\"") ||
                        it.startsWith("\"MemberFinderBlock\"")
                }.forEach { chunk ->
                    // judge the COLUMN names the entity declares (the chunk also ends with the `val` name of the next entity)
                    Regex(""""columnName" to "([a-z_]+)"""")
                        .findAll(chunk)
                        .map { it.groupValues[1] }
                        .filter { Regex("(region|postal|city)").containsMatchIn(it) }
                        .toList()
                        .shouldBeEmpty()
                }
        }

        // ── (e/f) one gate ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

        test("(e/f) every method of the service calls the one gate before anything else") {
            val code = SourceScan.blank(serviceFile.readText())
            val bodies = code.split("override suspend fun ").drop(1)
            bodies.size shouldBe 15
            for (body in bodies) {
                val name = body.substringBefore("(")
                val head = body.substringAfter("{").trimStart()
                withClue(name) { head.startsWith("val current = requireFinderMember()") shouldBe true }
            }
        }

        // ── tables and ids ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

        test("(+) only the finder package, the GDPR contributor and the generated objects touch the four tables") {
            val tables =
                listOf("MemberFinderConsentEventTable", "MemberFinderRequestTable", "MemberFinderBlockTable", "MemberFinderRegionBandTable")
            val allowedDir = finderDir.canonicalPath
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { f ->
                        !f.canonicalPath.startsWith(allowedDir) &&
                            f.canonicalPath != personalDataFile.canonicalPath &&
                            !f.path.contains("/db/generated/")
                    }.filter { f -> SourceScan.blank(f.readText()).let { code -> tables.any { code.contains(it) } } }
                    .map { it.name }
            offenders.shouldBeEmpty()
        }

        test("(+) request ids are random v4 UUIDs only: no v7 anywhere in the finder, and only the request store inserts requests") {
            val files = finderDir.listFiles { f -> f.extension == "kt" }!!.toList() + serviceFile + personalDataFile
            files.filter { SourceScan.blank(it.readText()).contains("generateV7") }.map { it.name }.shouldBeEmpty()
            val inserting =
                files.filter { SourceScan.blank(it.readText()).contains("MemberFinderRequestTable.insert") }.map { it.name }
            inserting shouldBe listOf("MemberFinderRequestStore.kt")
        }
    })

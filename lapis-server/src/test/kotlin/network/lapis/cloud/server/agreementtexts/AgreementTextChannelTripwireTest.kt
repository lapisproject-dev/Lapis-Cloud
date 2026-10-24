package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.196 -- where the text of an agreement leaves the system. Today it reaches people ONLY through the RPC DTOs (registration, member finder,
 * encounter and event notices, pulpit dialog, back-office), all of which resolve it through [AgreementTextResolver]. Mail, PDF, the legal pages,
 * MCP and the public REST routes carry no agreement text -- and must not start to without a conscious decision, because a Markdown text would
 * then have to be rendered (or stripped) for that channel as well. These scans pin the current state:
 *
 *  1. no file under `mail/`, `pdf/`, `legal/`, `mcp/`, `routes/` mentions the resolver, the store, the Markdown renderer or the two tables;
 *  2. [AgreementMarkdown] is used only by the services that build the DTOs and by the back-office service;
 *  3. the `body_format` column is read and written only in the store (it never changes after the insert), and `previewAgreementText` neither logs
 *     nor stores anything.
 */
class AgreementTextChannelTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val needles =
            Regex(
                """\b(AgreementTextResolver|AgreementTextStore|AgreementMarkdown|AgreementTextVersionTable|AgreementTextDraftTable|CurrentAgreementText)\b""",
            )

        test("the scan sees the sources (not vacuous)") {
            (files.size > 100) shouldBe true
            (files.any { it.path.contains("/mail/") }) shouldBe true
            (files.any { it.path.contains("/pdf/") }) shouldBe true
            (files.any { it.path.contains("/mcp/") }) shouldBe true
            (files.any { it.path.contains("/routes/") }) shouldBe true
        }

        test("no mail, PDF, legal-page, MCP or public route source references an agreement text") {
            val offenders =
                files
                    .filter { file -> listOf("/mail/", "/pdf/", "/legal/", "/mcp/", "/routes/").any { file.path.contains(it) } }
                    .filter { needles.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.path.substringAfter("/server/") }
            offenders.shouldBeEmpty()
        }

        test("the Markdown renderer of agreement texts is used only where a DTO or the preview is built") {
            val users =
                files
                    .filter {
                        it.name != "AgreementMarkdown.kt" &&
                            Regex("""\bAgreementMarkdown\b""").containsMatchIn(SourceScan.blank(it.readText()))
                    }.map { it.name }
                    .toSet()
            users shouldBe
                setOf(
                    "AgreementTextAdminService.kt",
                    "RegistrationService.kt",
                    "MemberFinderService.kt",
                    "EncounterConsentDisclaimer.kt",
                    "EncounterSpaceService.kt",
                    "EventCaptureRequirement.kt",
                    "PulpitRecordingService.kt",
                    // V1.9.199: the travel-expense contact hint (Markdown) is rendered into the rates DTO and the ADMIN preview
                    "TravelExpenseLineRules.kt",
                    // V1.9.202: the Markdown description of a tender is rendered into the tender DTO, checked at save, and previewed for the board
                    "TenderService.kt",
                    "TenderDtos.kt",
                )
        }

        test("body_format is touched only by the store: no other file names the column object") {
            val users =
                files
                    .filter { it.path.contains("/server/") && !it.path.contains("/db/generated/") }
                    .filter { Regex("""\bbodyFormat\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            users shouldBe setOf("AgreementTextStore.kt")
        }

        test("previewAgreementText neither logs nor stores") {
            val service = files.single { it.name == "AgreementTextAdminService.kt" }.readText()
            val preview =
                SourceScan
                    .blank(
                        service,
                    ).substringAfter("override suspend fun previewAgreementText(")
                    .substringBefore("private fun lockDraftForPublish")
            Regex("""\blogger\b|\bAgreementTextStore\b|\btransaction\b|\bupsert|\binsert""").containsMatchIn(preview) shouldBe false
        }
    })

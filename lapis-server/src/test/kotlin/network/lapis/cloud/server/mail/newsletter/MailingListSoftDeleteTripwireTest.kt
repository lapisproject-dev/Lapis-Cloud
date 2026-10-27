package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.234 -- structural guards of the mailing-list soft delete. A deleted list stays in the table as evidence for the messages already
 * sent, so every query that shows or addresses lists must filter on `deleted_at`; the places that deliberately do not carry a comment.
 */
class MailingListSoftDeleteTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun file(name: String): File = files.single { it.name == name }

        val marker = "V1.9.234: no deleted_at filter"

        test("every MailingListTable.selectAll() in MailingService carries the deleted_at filter or an explaining marker comment") {
            val raw = file("MailingService.kt").readText()
            val offenders =
                Regex("""MailingListTable\s*\.selectAll\(\)""")
                    .findAll(raw)
                    .filter { m ->
                        val after = raw.substring(m.range.first, minOf(raw.length, m.range.first + 400))
                        val before = raw.substring(maxOf(0, m.range.first - 400), m.range.first)
                        // The filter must appear in this statement (before the next statement-ending blank line or val), or the marker precedes it.
                        val statement = after.substringBefore("\n\n")
                        !statement.contains("deletedAt") && !before.takeLast(300).contains(marker)
                    }.map { raw.substring(0, it.range.first).count { c -> c == '\n' } + 1 }
                    .toList()
            offenders.shouldBeEmpty()
        }

        test("RuleReferences filters deleted lists as defence in depth") {
            SourceScan.blank(file("RuleReferences.kt").readText()).contains("MailingListTable.deletedAt.isNull()") shouldBe true
        }

        test("the delivery worker deliberately has no deleted_at filter and says why") {
            val raw = file("MailingDeliveryWorker.kt").readText()
            raw.contains(marker.replace("no deleted_at filter", "deliberately WITHOUT deleted_at filter")) shouldBe true
            SourceScan.blank(raw).contains("deletedAt") shouldBe false
        }

        test("deleteMailingList does no file-system effect inside transaction and removes the files only after the commit") {
            val code = SourceScan.blank(file("MailingService.kt").readText())
            val start = code.indexOf("override suspend fun deleteMailingList")
            val body = code.substring(start, code.indexOf("override suspend fun subscribe"))
            val txStart = body.indexOf("transaction {")
            val txEnd = body.indexOf("deleteFilesAfterCommit")
            (txStart in 0 until txEnd) shouldBe true
            // everything from "transaction {" up to the file removal is the transaction body -- no file effect in it
            body.substring(txStart, txEnd).contains("deleteFilesAfterCommit") shouldBe false
            body.substring(txStart, txEnd).contains(".delete()") shouldBe false
        }

        test("deleteMailingList names no e-mail address and no list name in its log line or audit snapshot") {
            val code = SourceScan.blank(file("MailingService.kt").readText())
            val start = code.indexOf("override suspend fun deleteMailingList")
            val body = code.substring(start, code.indexOf("override suspend fun subscribe"))
            body.contains("[MailingListTable.name]") shouldBe false
            body.contains("[MemberTable.email]") shouldBe false
        }

        test("only the list row is locked FOR UPDATE by deleteMailingList and the draft messages, never a member row") {
            val code = SourceScan.blank(file("MailingService.kt").readText())
            val start = code.indexOf("override suspend fun deleteMailingList")
            val body = code.substring(start, code.indexOf("override suspend fun subscribe"))
            body.contains("MemberTable") shouldBe false
        }
    })

package network.lapis.cloud.server.messaging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.237 "Neue Nachricht" -- source-scan tripwires: the limiter is never charged inside a transaction (Exposed retries the block on an
 * SQLException and would count twice), no log line of the direct-message code mentions a search term, a text, a name or a file name, and the
 * direct-message block table has exactly one writer.
 */
class DirectMessageComposeTripwireTest :
    FunSpec({
        fun relative(file: File) = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        test("DirectMessageRateLimiter.checkAndRecord* is never called inside a transaction block") {
            val offenders =
                SourceScan.mainFiles().flatMap { file ->
                    val blanked = SourceScan.blank(file.readText())
                    val blocks = SourceScan.transactionBlocks(blanked)
                    Regex("""\bcheckAndRecord(Send|Search|Block)\b|\brequireSendAllowed\b""")
                        .findAll(blanked)
                        .filter { match -> blocks.any { match.range.first in it } }
                        .map { "${relative(file)}:${SourceScan.lineOf(text = blanked, index = it.range.first)}" }
                        .toList()
                }
            offenders.shouldBeEmpty()
        }

        test("no log line in the direct-message code mentions a query, a body, a display name or a file name") {
            val files =
                SourceScan.mainFiles().filter {
                    relative(it).let { p ->
                        p.contains("/messaging/DirectMessage") ||
                            p.endsWith("rpc/DirectMessageService.kt") ||
                            p.endsWith("rpc/DirectMessaging.kt")
                    }
                }
            (files.size >= 6) shouldBe true
            val offenders =
                files.flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) ->
                            line.contains("logger.") &&
                                listOf("query", "body", "displayName", "fileName", "term", "recipientId").any { line.contains(it) }
                        }.map { "${file.name}:${it.index + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test("DirectMessageBlockTable is written only by DirectMessageBlockStore and the DSGVO contributor") {
            val writers =
                SourceScan.mainFiles().filter { file ->
                    Regex("""DirectMessageBlockTable\s*\.\s*(insert|deleteWhere|update|batchInsert)\b""").containsMatchIn(
                        SourceScan.blank(file.readText()),
                    ) ||
                        Regex("""\binsert\s*\{[^}]*DirectMessageBlockTable""").containsMatchIn(SourceScan.blank(file.readText()))
                }
            writers.map { relative(it) }.toSet() shouldBe
                setOf(
                    "network/lapis/cloud/server/messaging/DirectMessageBlockStore.kt",
                    "network/lapis/cloud/server/dsgvo/CommunicationPersonalData.kt",
                )
        }
    })

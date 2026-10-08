package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.61 -- source tripwires for the Art. 9 GDPR promise of the encounter spaces ("no lasting trace of who attended"):
 *
 * 1. Entering, leaving, listing the present, removing, silencing -- and the helpers they use -- never write the audit log
 *    (the log is hash-chained and cannot be erased, so an entry "member X was there" would be permanent).
 * 2. No log line in the encounter code names a member id, an identity, a display name or a room id.
 * 3. The legacy conference consent table and `left_at` are never written for an encounter session.
 * 4. The consent proof is written inside a savepoint and only a unique violation counts as "already there".
 */
class EncounterPrivacyTripwireTest :
    FunSpec({
        val serviceFile = EncounterSourceScan.mainFile("rpc/EncounterSpaceService.kt")
        val encounterFiles =
            (
                File(
                    SourceScan.mainRoot(),
                    "network/lapis/cloud/server/encounter",
                ).listFiles()!!.filter { it.extension == "kt" } + serviceFile
            )

        test("the presence paths never write the audit log") {
            val fns = EncounterSourceScan.functions(serviceFile).associateBy { it.name }
            listOf(
                "enterSpace",
                "leaveSpace",
                "listPresent",
                "removeFromSpace",
                "silenceInSpace",
                "moderate",
                "prepareEntry",
                "admitInTx",
                "recordConsent",
            ).forEach { name ->
                val fn = fns[name] ?: error("function $name not found -- the tripwire must not run empty")
                (fn.body.contains("AuditLogRecorder")) shouldBe false
            }
            // ... and the lifecycle/config paths DO (the scan finds what it should)
            fns.getValue("openSpace").body shouldContain "AuditLogRecorder"
            fns.getValue("closeSpace").body shouldContain "AuditLogRecorder"
        }

        test("the poller audits only the automatic close, with no actor") {
            val poller =
                EncounterSourceScan
                    .functions(
                        EncounterSourceScan.mainFile("encounter/EncounterSpacePoller.kt"),
                    ).associateBy { it.name }
            poller.getValue("closeSession").body shouldContain "actorMemberId = null"
            poller.filterKeys { it != "closeSession" }.values.none { it.body.contains("AuditLogRecorder") } shouldBe true
        }

        test("no log line in the encounter code names a member, an identity, a display name or a room id") {
            val offenders = mutableListOf<String>()
            val forbidden =
                Regex("""memberId|identity|displayName|roomId|spaceId|\$\{?(member|room|space|identity|name)""", RegexOption.IGNORE_CASE)
            encounterFiles.forEach { f ->
                f.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (code.contains("logger.") && forbidden.containsMatchIn(code.substringAfter("logger."))) {
                        offenders += "${f.name}:${index + 1}: ${line.trim()}"
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test("the encounter code never writes the legacy guest-consent table and never sets left_at (no trace is kept)") {
            encounterFiles.forEach { f ->
                val code = SourceScan.blank(f.readText())
                Regex("""ConferenceGuestConsentAcknowledgmentTable\s*\.\s*insert""").containsMatchIn(code) shouldBe false
                // reading it for deletion is fine; an UPDATE of left_at is not
                Regex("""leftAt\]\s*=\s*(?!null)\S""").containsMatchIn(code) shouldBe false
                code.contains("closeOpenParticipationsFor") shouldBe false
            }
        }

        test("the consent proof is inserted in a savepoint and only a unique violation is swallowed") {
            val fn = EncounterSourceScan.functions(serviceFile).single { it.name == "recordConsent" }
            fn.body shouldContain "withSavepoint"
            fn.body shouldContain "isUniqueViolation()"
            fn.body shouldContain "throw e"
        }

        test("the poller makes every LiveKit call outside a transaction (no suspend call inside a transaction lambda)") {
            val raw = SourceScan.blank(EncounterSourceScan.mainFile("encounter/EncounterSpacePoller.kt").readText())
            // a transaction { ... } block never mentions the LiveKit client
            Regex("""transaction\s*\{""").findAll(raw).forEach { m ->
                var depth = 0
                var end = m.range.last
                for (i in m.range.last until raw.length) {
                    if (raw[i] == '{') depth++
                    if (raw[i] == '}') {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
                raw.substring(m.range.first, end + 1).contains("liveKitAdminClient") shouldBe false
            }
        }

        // ── Welle V1.9.76: the anonymous entry notice ─────────────────────

        val notifierFile = EncounterSourceScan.mainFile("encounter/EncounterEntryNotifier.kt")
        val stateFile = EncounterSourceScan.mainFile("encounter/EncounterEntryNoticeState.kt")
        val noticeMailerFile = EncounterSourceScan.mainFile("mail/SmtpEncounterEntryNoticeMailer.kt")

        test("the entry-notice paths never write the audit log (an audit entry would be a permanent record that somebody entered)") {
            val fns = EncounterSourceScan.functions(notifierFile).associateBy { it.name }
            listOf("onGuestEntered", "flushDue", "sendFirstGuest", "sendWindow", "resolveNotice", "noticeRecipients").forEach { name ->
                val fn = fns[name] ?: error("function $name not found -- the tripwire must not run empty")
                fn.body.contains("AuditLogRecorder") shouldBe false
            }
            listOf(notifierFile, stateFile, noticeMailerFile).forEach { f ->
                SourceScan.blank(f.readText()).contains("AuditLogRecorder") shouldBe false
            }
            // the service calls the hook outside the audit-writing helpers
            EncounterSourceScan
                .functions(serviceFile)
                .single { it.name == "admitInTx" }
                .body
                .contains("entryNotifier") shouldBe false
        }

        test("no log line of the entry-notice files mentions an address, a recipient, a title or a count") {
            val offenders = mutableListOf<String>()
            val forbidden = Regex("""email|recipient|title|\bcount\b|address|\bentries\b""", RegexOption.IGNORE_CASE)
            listOf(notifierFile, stateFile, noticeMailerFile).forEach { f ->
                f.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (code.contains("logger.") && forbidden.containsMatchIn(code.substringAfter("logger."))) {
                        offenders += "${f.name}:${index + 1}: ${line.trim()}"
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test("the notice mailer enqueues every mail with logRecipient = false (the dispatcher must not log even a masked address)") {
            val code = SourceScan.blank(noticeMailerFile.readText())
            code.contains("dispatcher.enqueue(") shouldBe true
            code.contains("logRecipient = false") shouldBe true
            code.contains("logger") shouldBe false
        }

        test("transaction rule 2: the notifier never sends a mail inside a transaction lambda") {
            val raw = SourceScan.blank(notifierFile.readText())
            var checked = 0
            Regex("""transaction\s*\{""").findAll(raw).forEach { m ->
                var depth = 0
                var end = m.range.last
                for (i in m.range.last until raw.length) {
                    if (raw[i] == '{') depth++
                    if (raw[i] == '}') {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
                val block = raw.substring(m.range.first, end + 1)
                block.contains("mailer.") shouldBe false
                block.contains(".send(") shouldBe false
                checked++
            }
            (checked >= 2) shouldBe true // the scan is not vacuous: the two read transactions were found
            // the service calls the notifier after the entry transaction, wrapped so that it can never break the entry
            val enter = EncounterSourceScan.functions(serviceFile).single { it.name == "enterSpace" }.body
            enter.contains("entryNotifier.onGuestEntered") shouldBe true
            (enter.indexOf("entryNotifier.onGuestEntered") > enter.indexOf("admitInTx")) shouldBe true
        }

        test("the notifier class stores no person: its state is keyed by session/space ids only") {
            val code = SourceScan.blank(stateFile.readText())
            Regex("""memberId|identity|email|displayName""", RegexOption.IGNORE_CASE).containsMatchIn(code) shouldBe false
        }
    })

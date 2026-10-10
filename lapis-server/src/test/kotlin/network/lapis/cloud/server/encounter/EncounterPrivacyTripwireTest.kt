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
                // V1.9.79: seat selection and the shared presence-list helpers
                "selectSeat",
                "presentViewInTx",
                "presentDtos",
                "removeFromSpace",
                "silenceInSpace",
                "moderate",
                "prepareEntry",
                "admitInTx",
                "recordConsent",
                // V1.9.95: the blessing
                "blessSpace",
                "liturgyTargetInTx",
                // V1.9.96: the bell
                "ringBell",
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

        // ── Welle V1.9.79: the in-memory seat plan ────────────────────────

        test("V1.9.79: the seat state is memory only -- no transaction, no table, no logger, no audit") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("encounter/EncounterSeatState.kt").readText())
            listOf("transaction", "Table", "logger", "KotlinLogging", "AuditLogRecorder", "println").forEach { forbidden ->
                code.contains(forbidden) shouldBe false
            }
        }

        test(
            "V1.9.79/V1.9.80: no migration and no generated schema file knows a seat or a person at a seat; V79 is the only table migration",
        ) {
            val migrations =
                File(SourceScan.mainRoot().parentFile, "resources/db/migration").listFiles()!!.filter { it.extension == "sql" }
            (migrations.size > 70) shouldBe true // the scan is not vacuous
            // V1.9.80: V79 is the ONE new encounter migration; it adds exactly the three room-setting columns (with CHECKs) and nothing about a person
            val v79 = migrations.filter { it.name.startsWith("V79__") }.single()
            v79.name shouldBe "V79__encounter_space_tables.sql"
            val v79Code = v79.readLines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
            Regex("""ADD COLUMN IF NOT EXISTS (\w+)""").findAll(v79Code).map { it.groupValues[1] }.toList() shouldBe
                listOf("tables_enabled", "table_count", "table_seats")
            Regex("""\b(member|person|identity|seat|participant)\b""", RegexOption.IGNORE_CASE).containsMatchIn(v79Code) shouldBe false
            // (V80 is the mail-pipeline migration of V1.9.81 -- no further ENCOUNTER migration exists)
            migrations.none { it.name.startsWith("V80__") && it.name.contains("encounter", ignoreCase = true) } shouldBe true
            // (V18 events legitimately has event seats; only the encounter migrations are in question)
            val encounterMigrations = migrations.filter { it.name.contains("encounter", ignoreCase = true) }
            (encounterMigrations.size >= 4) shouldBe true
            encounterMigrations.none { Regex("""\bseat\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.readText()) } shouldBe true
            val generated = File(SourceScan.mainRoot(), "network/lapis/cloud/server/db/generated")
            (generated.exists()) shouldBe true
            generated.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name.startsWith("Encounter") }.none {
                Regex("""\bseat\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.readText())
            } shouldBe true
        }

        // ── Welle V1.9.80: the in-memory table plan ───────────────────────

        test("V1.9.80: the table state is memory only -- no transaction, no table, no logger, no audit") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("encounter/EncounterTableState.kt").readText())
            listOf("transaction", "Table(", "logger", "KotlinLogging", "AuditLogRecorder", "println", "suspend").forEach { forbidden ->
                code.contains(forbidden) shouldBe false
            }
        }

        test("V1.9.80: the table paths never write the audit log and never log") {
            val fns = EncounterSourceScan.functions(serviceFile).associateBy { it.name }
            listOf(
                "joinTable",
                "leaveTable",
                "tableToken",
                "listTables",
                "quietTable",
                "sendToPlenum",
                "applyRotations",
                "mintTableToken",
                "moderationSessionInTx",
            ).forEach { name ->
                val fn = fns[name] ?: error("function $name not found -- the tripwire must not run empty")
                fn.body.contains("AuditLogRecorder") shouldBe false
                fn.body.contains("logger") shouldBe false
            }
        }

        test("V1.9.80: the table paths never touch the conference participation/room tables' write side or the egress client") {
            val tableFiles =
                listOf(
                    "encounter/EncounterTableState.kt",
                    "encounter/EncounterTableRooms.kt",
                    "encounter/EncounterTableReconciler.kt",
                ).map { SourceScan.blank(EncounterSourceScan.mainFile(it).readText()) }
            tableFiles.forEach { code ->
                listOf(
                    "ConferenceRoomTable",
                    "ConferenceParticipationTable",
                    "LiveKitEgressClient",
                    "AuditLogRecorder",
                    "transaction",
                ).forEach {
                    code.contains(it) shouldBe false
                }
            }
            val fns = EncounterSourceScan.functions(serviceFile).associateBy { it.name }
            listOf("joinTable", "leaveTable", "tableToken", "listTables", "quietTable", "sendToPlenum", "mintTableToken").forEach { name ->
                fns.getValue(name).body.contains("LiveKitEgressClient") shouldBe false
                fns.getValue(name).body.contains("Insert") shouldBe false
            }
        }

        test(
            "V1.9.80: joinTable and leaveTable throttle with requireSeatRate (ServiceBusyException) before any database access; tableToken has its own limiter",
        ) {
            val fns = EncounterSourceScan.functions(serviceFile).associateBy { it.name }
            listOf("joinTable", "leaveTable").forEach { name ->
                val body = fns.getValue(name).body
                body shouldContain "requireSeatRate(limiter = tableRateLimiter"
                body.contains("requireWithinRate") shouldBe false
                (body.indexOf("requireSeatRate") < body.indexOf("transaction")) shouldBe true
            }
            val token = fns.getValue("tableToken").body
            token shouldContain "requireSeatRate(limiter = tableTokenRateLimiter"
            (token.indexOf("requireSeatRate") < token.indexOf("transaction")) shouldBe true
            // there is no member id parameter: nobody can put another person at a table
            fns.getValue("joinTable").params.contains("memberId") shouldBe false
            // no LiveKit call and no token minting inside a transaction of the table paths
            listOf("joinTable", "leaveTable", "tableToken", "listTables", "quietTable", "sendToPlenum").forEach { name ->
                val raw = SourceScan.blank(fns.getValue(name).body)
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
                    listOf("applyRotations", "liveKitAdminClient", "mintTableToken", "LiveKitAccessToken").forEach { forbidden ->
                        block.contains(forbidden) shouldBe false
                    }
                }
            }
        }

        test("V1.9.79: selectSeat throttles with its own helper and ServiceBusyException, before any database access") {
            val fn = EncounterSourceScan.functions(serviceFile).single { it.name == "selectSeat" }
            fn.body shouldContain "requireSeatRate"
            fn.body.contains("requireWithinRate") shouldBe false
            fn.body.contains("logger") shouldBe false
            (fn.body.indexOf("requireSeatRate") < fn.body.indexOf("transaction")) shouldBe true
            // there is no member id parameter: nobody can put another person on a seat
            fn.params.contains("memberId") shouldBe false
            EncounterSourceScan.functions(serviceFile).single { it.name == "requireSeatRate" }.body shouldContain "ServiceBusyException"
        }

        // ── Welle V1.9.95: the blessing ───────────────────────────────────

        test("V1.9.95: the blessing path writes nothing (no insert/update/delete/upsert, no audit) and logs exactly one fixed DEBUG line") {
            val fns = EncounterSourceScan.functions(serviceFile).associateBy { it.name }
            listOf("blessSpace", "liturgyTargetInTx").forEach { name ->
                val body = SourceScan.blank(fns.getValue(name).body)
                Regex("""\b(insert|update|deleteWhere|upsert|batchInsert|AuditLogRecorder)\b""").containsMatchIn(body) shouldBe false
            }
            val bless = fns.getValue("blessSpace").body + fns.getValue("liturgyTargetInTx").body
            Regex("""logger\.""").findAll(bless).count() shouldBe 1
            bless shouldContain """logger.debug { "blessing send failed" }"""
            // the external call stays outside the transaction lambda
            val raw = SourceScan.blank(fns.getValue("blessSpace").body)
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
                raw.substring(m.range.first, end + 1).contains("sendData") shouldBe false
            }
        }

        test("V1.9.95: the blessing state is memory only and names no person") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("encounter/EncounterBlessingState.kt").readText())
            listOf(
                "memberId",
                "identity",
                "Logger",
                "logger",
                "KotlinLogging",
                "transaction",
                "Table",
                "AuditLogRecorder",
                "println",
            ).forEach {
                code.contains(it) shouldBe false
            }
            (encounterFiles.any { it.name == "EncounterBlessingState.kt" }) shouldBe true // inside the log-line scan above
        }

        // ── Welle V1.9.96: the bell ───────────────────────────────────────

        test("V1.9.96: the bell path writes nothing (no insert/update/delete/upsert, no audit) and logs exactly one fixed DEBUG line") {
            val fns = EncounterSourceScan.functions(serviceFile).associateBy { it.name }
            listOf("ringBell", "liturgyTargetInTx").forEach { name ->
                val body = SourceScan.blank(fns.getValue(name).body)
                Regex("""\b(insert|update|deleteWhere|upsert|batchInsert|AuditLogRecorder)\b""").containsMatchIn(body) shouldBe false
            }
            val bell = fns.getValue("ringBell").body + fns.getValue("liturgyTargetInTx").body
            Regex("""logger\.""").findAll(bell).count() shouldBe 1
            bell shouldContain """logger.debug { "bell send failed" }"""
            val raw = SourceScan.blank(fns.getValue("ringBell").body)
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
                raw.substring(m.range.first, end + 1).contains("sendData") shouldBe false
            }
        }

        test("V1.9.96: the bell state is memory only and names no person") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("encounter/EncounterBellState.kt").readText())
            listOf(
                "memberId",
                "identity",
                "Logger",
                "logger",
                "KotlinLogging",
                "transaction",
                "Table",
                "AuditLogRecorder",
                "println",
            ).forEach {
                code.contains(it) shouldBe false
            }
            (encounterFiles.any { it.name == "EncounterBellState.kt" }) shouldBe true
        }

        test("V1.9.96: the blessing and the bell share ONE rights helper and check neither profile nor role themselves") {
            val fns = EncounterSourceScan.functions(serviceFile).associateBy { it.name }
            listOf("blessSpace", "ringBell").forEach { name ->
                val body = fns.getValue(name).body
                body shouldContain "liturgyTargetInTx("
                body.contains("profileOf") shouldBe false
                body.contains("roleOf") shouldBe false
            }
            // rights come BEFORE the throttle, so an unauthorised caller cannot probe the throttle state
            val ring = fns.getValue("ringBell").body
            (ring.indexOf("liturgyTargetInTx(") < ring.indexOf("bellState.tryAcquire")) shouldBe true
        }

        test("V1.9.96: the bell topic literal exists exactly once in the shared module and its payload fits the limit") {
            val shared = java.io.File("../lapis-shared/src").takeIf { it.exists() } ?: java.io.File("lapis-shared/src")
            val hits =
                shared
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .sumOf { Regex(""""lapis-encounter-bell"""").findAll(it.readText()).count() }
            hits shouldBe 1
            (
                network.lapis.cloud.shared.domain.ENCOUNTER_BELL_PAYLOAD.length <=
                    network.lapis.cloud.shared.domain.ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES
            ) shouldBe
                true
            (
                network.lapis.cloud.shared.domain.ENCOUNTER_BELL_PAYLOAD.length <=
                    network.lapis.cloud.shared.domain.ENCOUNTER_BELL_MAX_PAYLOAD_BYTES
            ) shouldBe
                true
        }
    })

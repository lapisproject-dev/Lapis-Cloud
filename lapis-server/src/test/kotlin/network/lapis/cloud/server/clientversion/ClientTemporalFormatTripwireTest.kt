package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * W7 "Zeitstempel-Formatierung app-weit": rules that keep the ONE date/time formatter (`DateTime.kt`) the only one --
 * the temporal sibling of [ClientMoneyFormatTripwireTest] (M1-M6 for `Money.kt`). Client sources are read as text
 * (`jsTest` under Karma has no file system, so every tripwire lives here); comment lines are exempt, and every rule has a
 * positive and a negative example so a broken regex cannot go silent. Gradle runs server tests with `lapis-server` as the
 * working directory.
 *
 * Review-Befund 2026-09-24: `DateTime.kt`'s own file KDoc claimed a `T6` rule of this exact class name already enforced
 * "Formatting therefore never touches `toInstant`, `TimeZone` or `Clock`" -- this class did not exist at all. What is now
 * T4 below is that rule, made real; T1-T3 close the same kind of gap for the other claims that same KDoc makes.
 *
 * V1.4.32 W7 Teilwelle B adds T5: `DateTime.kt`'s file KDoc documented three routes by which a raw ISO string still
 * reaches the DOM despite T1-T4 -- (a) `field.toString()` outside the field-list grep's blind spot for machine-readable/
 * sort-key contexts, (b) `gettext("... %1", field)` with no `.toString()` in the source at all (`gettext`'s own
 * `args[index - 1]?.toString()` stringifies it), (b-lambda) the same route behind a `field?.let { ... it ... }` that
 * renames the field to `it` (or a named parameter) before the call, and (c) a `"${field}"` string-template
 * interpolation. T5a/b/b-lambda/c below are the enforcement of all four, mirroring T3's own field-list mechanics but
 * deriving the field list itself from `lapis-shared` at test-run time (a hand-maintained list, like M5's
 * `DECIMAL_FIELDS`, goes stale the moment a new persisted temporal field is added without a matching test edit).
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val SHARED_SOURCES =
    File("../lapis-shared/src")
        .let { if (it.exists()) it else File("lapis-shared/src") }

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun codeLines(file: File): List<String> = file.readLines().filterNot { isCommentLine(it) }

/** T1: `Intl.DateTimeFormat`/`toLocaleDateString`/`toLocaleTimeString` depend on the browser's ICU version (flaky golden
 * tests) and this app only ever needs numeric components in a fixed per-language order -- see `DateTime.kt`'s file KDoc
 * "Why no Intl.DateTimeFormat / toLocaleDateString". */
private val FORBIDDEN_DATE_API = Regex("""Intl\.DateTimeFormat|toLocaleDateString\(|toLocaleTimeString\(""")

/** T2: a temporal token concatenated into another string leaks the i18n marker (mirrors `ClientMoneyFormatTripwireTest`'s
 * M3 for the five money/LTR kinds). */
private val TEMPORAL_TOKEN_CALL = Regex("""\b(?:dateToken|dayMonthToken|dateTimeToken|timestampToken|timeToken)\(""")
private val TOKEN_CONCATENATION = Regex(""""\s*\+|\+\s*"|\$\{|\$\w|gettext\(""")

/** T3: no screen re-derives `DateTime.kt`'s own zero-padding of a day/month/hour/minute/second/year component instead of
 * calling its `internal` `pad2`/`pad4` (Review-Befund 2026-09-24: three independent
 * hand-rolled `.toString().padStart(...)` reimplementations existed side by side with `DateTime.kt`'s own, in
 * `PriceHistoryChartData.kt`, `ConferenceScreen.kt` (two sites) and `ConferenceStreamDestinationsScreen.kt` -- plus a
 * fourth, differently-shaped one, `AuctionScreen.kt`'s private `Int.pad2()`, all closed in the same review-fix pass this
 * tripwire was added in). A duration (`conferenceRecordingDurationLabel`'s `minutes`/`secs` from an elapsed second count,
 * not a `LocalDateTime` component) never matches -- the property name right before the dot must be the exact singular
 * `day`/`hour`/`minute`/`second`, not a locally chosen variable name. */
private val HAND_ROLLED_PADDING =
    Regex(
        """\.(?:day|hour|minute|second)\.toString\(\)\.padStart\(2,\s*'0'\)|""" +
            """\.month\.number\.toString\(\)\.padStart\(2,\s*'0'\)|""" +
            """\.year\.toString\(\)\.padStart\(4,\s*'0'\)""",
    )

/** T4: `DateTime.kt`'s own `format*`/`*Components` functions never touch `toInstant`, `TimeZone` or `Clock` -- the load-
 * bearing claim of that file's own KDoc (see this class' own KDoc above). Every persisted temporal field is a "naked"
 * server-local `LocalDate`/`LocalDateTime` with no zone information to convert FROM; re-deriving a zone here would
 * silently disagree with what the server actually stored. Scanned against `DateTime.kt` alone -- other client files
 * legitimately touch these three (e.g. `PriceHistoryChartData.kt`'s UTC-anchored gap-distance epoch millis,
 * `ConferenceScreen.kt`'s `Clock.System.now()` for "right now", `AuctionScreen.kt`'s "price fetched at" timestamp --
 * none of them a re-interpretation of a STORED value for display). */
private val ZONE_OR_INSTANT_API = Regex("""\btoInstant\(|\bTimeZone\b|\bClock\b""")

// ---- T5: field list derived from lapis-shared at test-run time ----------------------------------------------------

/** Every `val`/`var` name declared with type `LocalDate`/`LocalDateTime` (optionally nullable, optionally
 * `kotlinx.datetime`-qualified) anywhere in `lapis-shared`. Derived, not hand-maintained, so the rule cannot go stale
 * the way `ClientMoneyFormatTripwireTest`'s `DECIMAL_FIELDS` (hand-listed) can -- a new persisted temporal field is
 * covered automatically the next time this test runs. */
private val TEMPORAL_FIELD_DECL = Regex("""va[lr]\s+(\w+)\s*:\s*(?:kotlinx\.datetime\.)?LocalDate(?:Time)?\??""")

private val TEMPORAL_FIELDS: Set<String> =
    SHARED_SOURCES
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { f -> TEMPORAL_FIELD_DECL.findAll(f.readText()).map { it.groupValues[1] } }
        .toSet()

/** Sorted longest-first before joining into an alternation -- defensive against any future field name that is a
 * prefix of another (backtracking would still find the right one, but this keeps the intent explicit and matches
 * the `find -regex` convention of this environment). */
private val TEMPORAL_FIELD_ALTERNATION = TEMPORAL_FIELDS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

/** T5a (route 1): `x.field.toString()` / `x.field?.toString()` -- mirrors M5's `RAW_DECIMAL_TOSTRING`. */
private val RAW_TEMPORAL_TOSTRING = Regex("""(?:\w+\??\.)+(?:$TEMPORAL_FIELD_ALTERNATION)\??\.toString\(\)""")

/** Excludes the legitimate machine-readable/sort-key uses of a raw `.toString()` on a temporal field -- a field
 * prefill (`setValue(`/`value = `, mirrors M5's own exclusion) or a comparator/sort key
 * (`compareBy`/`thenBy`/`sortedBy`/`sortedWith`/`sortBy` -- `DateTime.kt`'s own KDoc names ISO as the correct sort
 * key, `LedgerScreen.kt`'s `entryDate` comparator being the canonical example). Neither is a display bug. */
private val MACHINE_OR_SORT_CONTEXT = Regex("""setValue\(|\bvalue\s*=|compareBy|thenBy|sortedBy|sortedWith|sortBy""")

/** T5b (route 2, direct): a qualified access as a top-level `gettext` argument -- requires at least one `.` before
 * the field name, so a bare local `date`/`from`/`to` (which could be anything) never matches on its own. */
private val QUALIFIED_TEMPORAL_FIELD = Regex("""^(?:\w+\??\.)+(?:$TEMPORAL_FIELD_ALTERNATION)$""")

/** T5b-lambda (route 2, behind a rename): `field?.let { [param ->] ... }` -- the opener alone; whether the body
 * actually passes `param` (or `it`, unnamed) into a `gettext(...)` call is checked separately in [lambdaSitesInText]. */
private val LET_LAMBDA_OPENER =
    Regex("""(?:\w+\??\.)+(?:$TEMPORAL_FIELD_ALTERNATION)\??\.let\s*\{\s*(?:(\w+)\s*->)?""")

/** T5c (route 3): `${x.field}` string-template interpolation, no wrapping `format*`/`*Token` call. */
private val TEMPLATE_TEMPORAL_FIELD = Regex("""\$\{(?:\w+\??\.)+(?:$TEMPORAL_FIELD_ALTERNATION)\??\}""")

private fun rawTemporalToStringSites(file: File): List<String> =
    codeLines(file)
        .filter { RAW_TEMPORAL_TOSTRING.containsMatchIn(it) && !MACHINE_OR_SORT_CONTEXT.containsMatchIn(it) }
        .map { it.trim() }

private fun rawTemporalGettextArgSites(file: File): List<String> =
    gettextArguments(codeLines(file).joinToString("\n")).flatMap { args ->
        args.filter { QUALIFIED_TEMPORAL_FIELD.matches(it) }
    }

/** Extracts the `{ ... }` body starting right after an opening brace at [bodyStart] (brace/string-balanced, the same
 * technique as [gettextArguments]'s parenthesis walk) -- returns the body text and the index just past the matching
 * closing `}`. */
private fun extractBraceBody(
    text: String,
    bodyStart: Int,
): Pair<String, Int> {
    var i = bodyStart
    var depth = 1
    var inString = false
    val body = StringBuilder()
    while (i < text.length && depth > 0) {
        val c = text[i]
        when {
            inString -> {
                body.append(c)
                if (c == '\\' && i + 1 < text.length) {
                    i++
                    body.append(text[i])
                } else if (c == '"') {
                    inString = false
                }
            }
            c == '"' -> {
                inString = true
                body.append(c)
            }
            c == '{' -> {
                depth++
                body.append(c)
            }
            c == '}' -> {
                depth--
                if (depth > 0) body.append(c)
            }
            else -> body.append(c)
        }
        i++
    }
    return body.toString() to i
}

/** The [LET_LAMBDA_OPENER] sites in [text] whose lambda body passes the (possibly renamed) parameter into a
 * `gettext(...)` call as a top-level argument -- the actual T5b-lambda finding, not just the opener. */
private fun lambdaSitesInText(text: String): List<String> {
    val hits = mutableListOf<String>()
    for (m in LET_LAMBDA_OPENER.findAll(text)) {
        val param = m.groupValues[1].ifEmpty { "it" }
        val (body, _) = extractBraceBody(text = text, bodyStart = m.range.last + 1)
        if (gettextArguments(body).any { args -> param in args }) hits += m.value.trim()
    }
    return hits
}

private fun rawTemporalLambdaSites(file: File): List<String> = lambdaSitesInText(file.readText())

private fun templateTemporalSites(file: File): List<String> =
    codeLines(file).filter { TEMPLATE_TEMPORAL_FIELD.containsMatchIn(it) }.map { it.trim() }

/** Same-name field in an unrelated non-temporal context, confirmed false-positive, with a reason -- each entry must
 * still match at test time (an outdated entry breaks the build, same discipline as the ledgers below). The Teilwelle
 * B red-proof run spot-checked every short/generic temporal field name (`asOf`, `since`, `until`, `date`, `from`,
 * `to`, `endsAt`, `paidAt`, `readAt`, `sentAt`, `leftAt`, `castAt`) against every `lapis-shared` declaration site of
 * that name and found no non-temporal collision reaching a T5 finding -- so this set starts empty. */
private val T5_FALSE_POSITIVES: Set<Pair<String, String>> = emptySet()

/**
 * File name -> finding count. Only ever lowered -- an increase means a NEW raw-ISO display site was introduced and
 * the ledger tests below will fail, exactly like M4/M6's `MotionsScreen.kt` ledgers. Measured by this Teilwelle B's
 * own red-proof run (T5's tests failing against an empty ledger, per this class' own commit history); every entry
 * still open after this wave is also carried into `docs/architecture/ui-ux-guideline.adoc`'s W7 section and
 * `CHANGELOG.md`.
 */
private val T5A_LEDGER: Map<String, Int> =
    mapOf(
        "ApiKeysScreen.kt" to 1,
        "BankAccountsScreen.kt" to 4,
        "BankStatementImportScreen.kt" to 5,
        "DocumentsScreen.kt" to 1,
        "EventCheckInScreen.kt" to 2,
        "EventVolunteerShiftsScreen.kt" to 1,
        "EventsScreen.kt" to 1,
        "LedgerScreen.kt" to 1,
        "LtrLedgerScreen.kt" to 1,
        "MemberAdministrationScreen.kt" to 1,
        "MemberFamiliesScreen.kt" to 2,
        "MemberHonorsScreen.kt" to 1,
        "MyVolunteerShiftsScreen.kt" to 1,
        "PaymentTransactionsScreen.kt" to 1,
        "PoliticianScreen.kt" to 1,
        "PriceOracleScreen.kt" to 1,
        // GoBD golden-test pinning (ReportGoldenTest) -- stays open, not part of any Teilwelle B commit, see
        // ui-ux-guideline.adoc "Not done in this wave" and DateTime.kt's file KDoc.
        "ReportRows.kt" to 3,
        "SepaMandatesScreen.kt" to 1,
        "VolunteerAllowanceApprovalsScreen.kt" to 1,
        "VolunteerAllowanceScreen.kt" to 1,
    )

/**
 * Commit 3 of this wave (Route 2 Group A) closed AuditLogScreen.kt, DunningCasesScreen.kt,
 * SepaBatchesScreen.kt, OpenItemsScreen.kt, DsgvoRightsScreen.kt and MemberFinancialHistoryScreen.kt
 * entirely, and reduced DsgvoComplianceScreen.kt from 2 to 1 (`incident.authorityNotificationDeadline`
 * stays open -- only `incident.discoveredAt` and the `agreement.reviewDueDate`/`incident.authorityNotifiedAt`
 * lambdas were in this commit's scope).
 *
 * Commit 4 of this wave (Route 2 Group B) closed AuctionScreen.kt, BackupScreen.kt,
 * BoardMembershipScreen.kt, CommitteesScreen.kt, CommunicationScreen.kt (both its T5b and its
 * T5b-λ finding), ContributionReliefLabels.kt, ContributionsScreen.kt, CrowdfundingScreen.kt,
 * FinancialReportsScreen.kt, LedgerScreen.kt, MeetingsScreen.kt, MotionsScreen.kt,
 * PriceOracleScreen.kt, SocialModerationScreen.kt, TravelExpenseApprovalsScreen.kt and
 * TravelExpenseScreen.kt entirely.
 *
 * Commit 5 of this wave (Route 2 Group C, the rest) closed every remaining T5b/T5b-λ finding:
 * ConferenceScreen.kt, ContributionReliefQueueScreen.kt (both its T5b and its T5b-λ finding),
 * DashboardScreen.kt, DsgvoComplianceScreen.kt (its second, Group-A-leftover finding),
 * DunningSettingsScreen.kt, MemberAdministrationScreen.kt, PaymentGatewaySettingsScreen.kt,
 * PoliticianScreen.kt, PostalMailScreen.kt, SepaMandateSection.kt, SepaSettingsScreen.kt,
 * SocialNetworkScreen.kt and BankStatementImportScreen.kt (its T5b-λ finding only -- its five
 * T5a findings stay in T5A_LEDGER, out of route 2/3 scope). T5B_LEDGER and T5B_LAMBDA_LEDGER are
 * therefore empty from this commit on -- routes 2 and 3 are now a fully enforced invariant
 * app-wide, only route 1 (T5A_LEDGER) still has open, optional entries (Commit 6, ReportRows.kt
 * pinned).
 */
private val T5B_LEDGER: Map<String, Int> = emptyMap()

private val T5B_LAMBDA_LEDGER: Map<String, Int> = emptyMap()

/** Closed in Commit 2 of this wave (all 6 route-3 sites converted to a `format*`/`*Span`/`*Column` call) -- see
 * `CHANGELOG.md` and `ui-ux-guideline.adoc`'s W7 section for the file-by-file list. May only ever stay empty or
 * shrink further (there is nothing left to shrink from). */
private val T5C_LEDGER: Map<String, Int> = emptyMap()

class ClientTemporalFormatTripwireTest :
    FunSpec({
        val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val others = files.filter { it.name != "DateTime.kt" }
        val dateTimeKt = files.first { it.name == "DateTime.kt" }

        test("the scan sees the client sources") {
            (files.size > 100) shouldBe true
            files.any { it.name == "DateTime.kt" } shouldBe true
        }

        test("T1: no Intl.DateTimeFormat / toLocaleDateString / toLocaleTimeString in the client, and the detector sees one") {
            files.flatMap { f ->
                codeLines(f).filter { FORBIDDEN_DATE_API.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
            } shouldBe
                emptyList()
            FORBIDDEN_DATE_API.containsMatchIn("""x.asDynamic().toLocaleDateString("de-DE")""") shouldBe true
            FORBIDDEN_DATE_API.containsMatchIn("""Intl.DateTimeFormat("de").format(x)""") shouldBe true
            FORBIDDEN_DATE_API.containsMatchIn("""formatDate(x)""") shouldBe false
        }

        test("T2: a temporal token is never concatenated into a string nor passed to gettext, and the detector sees both") {
            val findings =
                files.flatMap { f ->
                    codeLines(f)
                        .filter { TEMPORAL_TOKEN_CALL.containsMatchIn(it) && TOKEN_CONCATENATION.containsMatchIn(it) }
                        .map { "${f.name}: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            TOKEN_CONCATENATION.containsMatchIn(""""Termin: " + dateToken(x)""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""gettext("Termin: %1", dateTimeToken(x))""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""val s = "${'$'}{timestampToken(x)} extra"""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""trFormat(tr("Termin: %1"), dateToken(x))""") shouldBe false
            TOKEN_CONCATENATION.containsMatchIn("""span(dateTimeToken(at)) {""") shouldBe false
        }

        test("T3: no screen re-derives DateTime.kt's own zero-padding of a date/time component, and the detector sees one") {
            val findings =
                others.flatMap { f ->
                    codeLines(f).filter { HAND_ROLLED_PADDING.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            HAND_ROLLED_PADDING.containsMatchIn("""val h = startedAt.hour.toString().padStart(2, '0')""") shouldBe true
            HAND_ROLLED_PADDING.containsMatchIn("""val y = dateTime.year.toString().padStart(4, '0')""") shouldBe true
            HAND_ROLLED_PADDING.containsMatchIn("""val m = now.month.number.toString().padStart(2, '0')""") shouldBe true
            HAND_ROLLED_PADDING.containsMatchIn("""val h = pad2(startedAt.hour)""") shouldBe false
            // an elapsed-duration component (a local Long, not a LocalDateTime property) must not false-positive
            HAND_ROLLED_PADDING.containsMatchIn(""""${'$'}hours:${'$'}{minutes.toString().padStart(2, '0')}"""") shouldBe false
        }

        test("T4: DateTime.kt's own formatting never touches toInstant / TimeZone / Clock, and the detector sees one") {
            val findings = codeLines(dateTimeKt).filter { ZONE_OR_INSTANT_API.containsMatchIn(it) }.map { it.trim() }
            findings shouldBe emptyList()
            ZONE_OR_INSTANT_API.containsMatchIn("""at.toInstant(TimeZone.currentSystemDefault())""") shouldBe true
            ZONE_OR_INSTANT_API.containsMatchIn("""val now = Clock.System.now()""") shouldBe true
            ZONE_OR_INSTANT_API.containsMatchIn("""formatDateComponents(year, month, day, locale)""") shouldBe false
        }

        test("T5 field list derives from lapis-shared and is not stale") {
            (TEMPORAL_FIELDS.size >= 140) shouldBe true
            ("executedAt" in TEMPORAL_FIELDS) shouldBe true
            ("startsAt" in TEMPORAL_FIELDS) shouldBe true
        }

        test("T5a: a raw x.field.toString() is caught, a formatted call and a machine-readable/sort-key use are not") {
            RAW_TEMPORAL_TOSTRING.containsMatchIn("""div(event.startsAt.toString())""") shouldBe true
            RAW_TEMPORAL_TOSTRING.containsMatchIn("""div(event.startsAt?.toString())""") shouldBe true
            RAW_TEMPORAL_TOSTRING.containsMatchIn("""div(formatDateTime(event.startsAt))""") shouldBe false
            MACHINE_OR_SORT_CONTEXT.containsMatchIn("""value = contact.consentGivenAt?.toString()""") shouldBe true
            MACHINE_OR_SORT_CONTEXT.containsMatchIn("""compareBy { it.entryDate.toString() }""") shouldBe true
            MACHINE_OR_SORT_CONTEXT.containsMatchIn("""div(event.startsAt.toString())""") shouldBe false
            val findings = others.associate { it.name to rawTemporalToStringSites(it).size }.filterValues { it > 0 }
            findings shouldBe T5A_LEDGER
        }

        test("T5b: a qualified field is a top-level gettext argument, a bare local name and a formatted call are not") {
            QUALIFIED_TEMPORAL_FIELD.matches("request.executedAt") shouldBe true
            QUALIFIED_TEMPORAL_FIELD.matches("it.executedAt") shouldBe true
            // a bare local can be anything (StatuteQaScreen-style false positive risk, mirrors M6's "amount" exclusion) --
            // T5b therefore only ever matches a QUALIFIED access, never a bare name on its own.
            QUALIFIED_TEMPORAL_FIELD.matches("executedAt") shouldBe false
            QUALIFIED_TEMPORAL_FIELD.matches("formatDateTime(request.executedAt)") shouldBe false
            gettextArguments("""gettext("Ausgeführt am %1", request.executedAt)""") shouldBe listOf(listOf("request.executedAt"))
            // the multi-line form a per-line regex would miss
            gettextArguments(
                "gettext(\n \"Entdeckt am: %1 · %2\",\n  incident.discoveredAt,\n  incident.affectedDataCategories,\n)",
            ) shouldBe listOf(listOf("incident.discoveredAt", "incident.affectedDataCategories"))
            val findings = others.associate { it.name to rawTemporalGettextArgSites(it).size }.filterValues { it > 0 }
            findings shouldBe T5B_LEDGER
        }

        test("T5b-lambda: field?.let { ... gettext(..., param) ... } is caught, a formatted lambda is not") {
            lambdaSitesInText("""val s = request.executedAt?.let { gettext("Ausgeführt am %1", it) }""").size shouldBe 1
            lambdaSitesInText("""val s = request.executedAt?.let { formatDateTime(it) }""") shouldBe emptyList()
            lambdaSitesInText(
                """val s = request.executedAt?.let { at -> gettext("Ausgeführt am %1", formatDateTime(at)) }""",
            ) shouldBe emptyList()
            lambdaSitesInText("""val s = request.executedAt?.let { at -> gettext("Ausgeführt am %1", at) }""").size shouldBe 1
            val findings = others.associate { it.name to rawTemporalLambdaSites(it).size }.filterValues { it > 0 }
            findings shouldBe T5B_LAMBDA_LEDGER
        }

        test("T5c: a template interpolation of a temporal field is caught, a formatted one is not") {
            TEMPLATE_TEMPORAL_FIELD.containsMatchIn("""div("${'$'}{event.startsAt}")""") shouldBe true
            TEMPLATE_TEMPORAL_FIELD.containsMatchIn(
                """div("${'$'}{event.startsAt?.let { formatDateTime(it) }}")""",
            ) shouldBe false
            TEMPLATE_TEMPORAL_FIELD.containsMatchIn("""div(formatDateTime(event.startsAt))""") shouldBe false
            val findings = others.associate { it.name to templateTemporalSites(it).size }.filterValues { it > 0 }
            findings shouldBe T5C_LEDGER
        }

        test("T5 false-positive entries still apply") {
            T5_FALSE_POSITIVES.forEach { (fileName, snippet) ->
                val file = others.first { it.name == fileName }
                (file.readText().contains(snippet)) shouldBe true
            }
        }
    })

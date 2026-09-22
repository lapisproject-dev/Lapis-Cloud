package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Security audit W6b follow-up round 3 (major finding A): [sanitizeUntrustedI18nText]'s own KDoc
 * (`I18nCatalogManager.kt`) says KVision's `Widget` resolves ANY widget content that starts with
 * `KV_I18N_MARKER` through `I18n.trans` on render -- so EVERY server-/member-controlled field handed to a
 * `div`/`span`/`p`/`h1..h6` call as plain widget content must go through [sanitizeUntrustedI18nText] first,
 * unconditionally, or a forged marker + money-sentinel payload renders as a fabricated, freely chosen amount.
 * Before this test, the rule was enforced at exactly two call sites in the whole client
 * (`grep -rn sanitizeUntrustedI18nText lapis-client/src/jsMain` -- `MotionsScreen.kt` only) and nothing caught
 * a new screen skipping it.
 *
 * Round 3 left [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] at 58 (fixing every one in one pass was judged too large a
 * change to land and verify safely in one round). Round 4 (`network.lapis.cloud.client.UntrustedText`) closed the
 * engstelle instead of repeating the same manual `sanitizeUntrustedI18nText(...)` wrap 58 more times: five thin
 * helpers (`untrustedDiv`/`untrustedSpan`/`untrustedP`/`untrustedHeading`/`untrustedCardTitle`) each sanitize
 * unconditionally, and every one of the 58 round-3 findings was migrated onto one of them, bringing the ledger to
 * [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] = 1. The single remaining entry, `ApiKeysScreen.kt` (`result.rawKey`), is
 * a deliberate, documented exception (see the inline comment at that call site): it is the actual secret value
 * shown to the operator exactly once, and silently stripping bytes from it would risk a corrupted key being
 * copied -- sanitizing it is the wrong fix, not a missed one.
 *
 * This remains a LEDGER test, not a zero-tolerance gate, so a genuinely new, justified exception can still be
 * added by raising [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] together with an inline comment at the call site (as
 * with `rawKey`) -- but the constant may otherwise only go DOWN as call sites are migrated onto the helpers
 * above (or wrapped in [sanitizeUntrustedI18nText] directly). A PR that adds a NEW unsanitized call site, or
 * reverts a fixed one, fails this test.
 *
 * The detector is necessarily a heuristic (regex over source text, like [ClientMoneyFormatTripwireTest]): it
 * only catches a DIRECT `receiver.call(dotted.field.access)` argument, not one first assigned to a local `val`
 * or wrapped in an intermediate helper -- false negatives are possible, false positives (a genuinely trusted
 * dotted constant, e.g. an enum member) are handled by excluding them from the ledger count explicitly if they
 * ever show up, not by weakening the regex. Known gap, not closed by this round: a local `val` holding an
 * untrusted DTO field before it reaches a widget call (`val label = donor.displayName; row.div(label)`) is
 * invisible to this regex -- see the "Known gaps" note in `docs/architecture/ui-ux-guideline.adoc`, section
 * "Untrusted widget text (security rounds 1-4)".
 *
 * Round 5 (money-forgery hardening follow-up, major finding): the construction-time shape above -- `receiver.div(x)`
 * -- is only HALF of how untrusted text reaches `Widget`/`Template.content`. The other half is a plain ASSIGNMENT
 * to an already-constructed widget, `widget.content = dtoField.field`, which hits the exact same render-time
 * resolution path (see [sanitizeUntrustedI18nText]'s KDoc on `I18nCatalogManager.kt`) but has no call-with-parens
 * shape for [RAW_DOTTED_WIDGET_TEXT_CALL] to match. [RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT] is a second, independent
 * detector + ledger for exactly that shape; [network.lapis.cloud.client.untrustedContent] is its sanitizing helper
 * (analogous to `untrustedDiv`/etc. for the construction-time shape).
 */
private val WIDGET_TEXT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun isWidgetTextCommentLine(line: String): Boolean =
    line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/**
 * `receiver.div(...)`/`span(...)`/`p(...)`/`h1(...)`..`h6(...)`/`link(...)` whose FIRST argument is a raw dotted
 * field access (`foo.bar`, `foo.bar.baz`) -- a server-/member-/DTO-controlled value handed straight to KVision as
 * widget content, with no sanitizing wrapper of any kind. A bare single-word local (no dot) is excluded on
 * purpose: that shape is far too common for trusted locals (`tr(...)` results, loop indices, etc.) to serve as a
 * signal.
 *
 * Security audit W6b, round 7 (major finding 1): `link` was missing from this list even though [io.kvision.html.Link]
 * (built via KVision's `link(...)` DSL function) renders its `label` through the exact same
 * `Widget.translate`/`I18n.trans` sink as `Tag.content` -- `container.link(document.title, url = ..., dataNavigo = ...)`
 * (`DocumentsScreen.kt`) and `receiptRow.link(receipt.originalFilename, url = ..., target = ...)`
 * (`TravelExpenseScreen.kt`/`TravelExpenseApprovalsScreen.kt`) passed a raw dotted field as `link`'s first
 * argument, invisibly, because the detector only looked for `div|span|p|h1..h6`. `link` takes several
 * positional-capable parameters (`label`, `url`, `icon`, ...), so the pattern below only requires the raw dotted
 * field IMMEDIATELY after the opening paren -- it does not need to be the call's only argument, unlike the
 * single-argument shape the other tags use.
 */
private val RAW_DOTTED_WIDGET_TEXT_CALL =
    Regex(
        """\.(?:div|span|p|h1|h2|h3|h4|h5|h6)\(\s*[a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)+\s*\)""" +
            """|\.link\(\s*[a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)+\s*[,)]""",
    )

/**
 * The ASSIGNMENT counterpart to [RAW_DOTTED_WIDGET_TEXT_CALL] (round 5): `receiverWidget.content = dtoField.field`
 * -- an already-constructed widget's `content` (see `io.kvision.html.Template`) overwritten with a raw dotted
 * field access, no sanitizing wrapper. Requires the receiver-dot before `content` on purpose (`widgetVar.content =`,
 * not a bare `content = ...` inside a builder-lambda's own property scope) -- that receiver-qualified shape is what
 * the round-5 finding actually reported and is what [network.lapis.cloud.client.untrustedContent] targets; a bare
 * in-lambda `content = ...` assignment is a related but distinct shape (harder to distinguish reliably from a
 * generic reusable function's `content` parameter default without risking false positives on genuinely trusted
 * `tr(...)`-result call sites) and is a documented, not-yet-automated known gap -- see the "Known gaps" note in
 * `docs/architecture/ui-ux-guideline.adoc`.
 */
private val RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT =
    Regex("""\w\.content\s*=\s*[a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)+""")

/**
 * Round 8 (major finding, follow-up to round 7): `Select`/`SimpleSelect`'s `options: List<StringPair>?` is the
 * third sink [RAW_DOTTED_WIDGET_TEXT_CALL]/[RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT] never covered -- an option's label
 * (rendered as `<option>` text through the exact same `Widget.translate`/`I18n.trans` path) is built almost
 * exclusively in this codebase via the `it.<idField> to it.<labelField>` pair shape (`it.id to it.displayName`,
 * `it.id to it.name`, `it.id to it.title`, `it.id to it.label`, ...) inside a `.map { ... }` feeding `options =`/
 * `.options =`. [RAW_OPTIONS_LABEL_MAP] catches that shape directly, independent of the specific field names, since
 * unlike [RAW_DOTTED_WIDGET_TEXT_CALL] it does not need to anchor on a fixed receiver-call name.
 * [network.lapis.cloud.client.untrustedOptions] is the sanitizing helper (analogous to `untrustedDiv`/etc.):
 * sanitizes only the label half of every pair, leaves the id/value half untouched.
 *
 * The trailing `(?!\()` (with a POSSESSIVE `\w*+`, not the default backtracking `\w*` -- Java's regex engine
 * supports possessive quantifiers, and only a possessive one actually blocks backtracking into a shorter match)
 * excludes `it.name to it.someLabelFn()`: a method call already returning a translated (`tr(...)`/`gettext(...)`)
 * label, not a raw field access -- e.g. `CateringScreen.kt`'s `it.name to it.cateringOrderStatusLabel()`. Without
 * the possessive quantifier, `\w*` backtracks one character short of the `(` and still reports a match.
 */
private val RAW_OPTIONS_LABEL_MAP =
    Regex("""\bit\.[a-zA-Z_]\w*\s+to\s+it\.[a-zA-Z_]\w*+(?!\()""")

/**
 * Any of these wrapping the same line means the argument is already resolved/sanitized -- not a violation.
 * Round 4 added the five [network.lapis.cloud.client]`.untrusted*` helpers: each sanitizes its `text` argument
 * unconditionally, so a call site that has been migrated onto one of them is safe even though it no longer
 * matches [RAW_DOTTED_WIDGET_TEXT_CALL] in the first place (an extra `untrusted` prefix sits between the
 * receiver and the opening paren). Listed anyway, explicitly, so the exclusion is documented rather than
 * implicit -- and so the "detector recognizes the new helpers as already-safe" test below has something
 * concrete to assert against. Round 5 added `untrustedContent(` for the assignment shape.
 */
private val ALREADY_SAFE =
    Regex(
        """sanitizeUntrustedI18nText\(|\btr\(|\bgettext\(|\btrFormat\(|""" +
            """\buntrustedDiv\(|\buntrustedSpan\(|\buntrustedP\(|\buntrustedHeading\(|\buntrustedCardTitle\(|""" +
            """\buntrustedContent\(|\buntrustedLink\(|\buntrustedOptions\(""",
    )

private fun rawUnsanitizedWidgetTextCalls(file: File): List<String> =
    file
        .readLines()
        .filterNot { isWidgetTextCommentLine(it) }
        .filter { RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(it) && !ALREADY_SAFE.containsMatchIn(it) }
        .map { it.trim() }

private fun rawUnsanitizedWidgetTextAssignments(file: File): List<String> =
    file
        .readLines()
        .filterNot { isWidgetTextCommentLine(it) }
        .filter { RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(it) && !ALREADY_SAFE.containsMatchIn(it) }
        .map { it.trim() }

private fun rawUnsanitizedOptionsLabelMaps(file: File): List<String> =
    file
        .readLines()
        .filterNot { isWidgetTextCommentLine(it) }
        .filter { RAW_OPTIONS_LABEL_MAP.containsMatchIn(it) && !ALREADY_SAFE.containsMatchIn(it) }
        .map { it.trim() }

/**
 * Ledger, W6b round 4: only ever lowered as call sites are migrated onto the `untrusted*` helpers (or wrapped in
 * [sanitizeUntrustedI18nText] directly) -- with the single documented exception of `ApiKeysScreen.kt`'s
 * `result.rawKey` (see the class KDoc above). Recount with `rawUnsanitizedWidgetTextCalls` over every `.kt` file
 * under `lapis-client/src/jsMain/kotlin` after fixing a batch -- never raise this constant to make a new
 * violation pass without an inline justification comment at the call site, matching `rawKey`'s.
 */
private const val KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS = 1

/**
 * Ledger, W6b round 5 (assignment shape, [RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT]): four of the five round-5 findings
 * (`BankAccountsScreen.kt` `outcome.bankPrompt`, `AccountingExportScreen.kt` `disclaimer.text`/`requestedProvider.displayName`,
 * `ConferenceScreen.kt` `d.text`) were migrated onto [network.lapis.cloud.client.untrustedContent] and stay there.
 *
 * Round 6 review (major finding, regression) reverted the fifth, `EventsScreen.kt` `result.message`: it is a
 * deliberate, documented exception, not a missed migration. `result.message` is always constructed via `tr(...)`/
 * `gettext(...)` in `EventFormValidation.kt` -- it already carries the `KV_I18N_MARKER` prefix that
 * [sanitizeUntrustedI18nText] strips as part of neutralizing a forged marker. Routing it through
 * [network.lapis.cloud.client.untrustedContent] (as round 5 mistakenly did) strips that legitimate marker too and
 * silently breaks translation resolution: every non-German locale would render the raw German msgid instead of
 * the translated validation message. See the inline comment at the `EventsScreen.kt` call site and the "Known
 * gaps" note in `docs/architecture/ui-ux-guideline.adoc`. This brings the ledger back up to 1 -- analogous to
 * `ApiKeysScreen.kt`'s `result.rawKey` exception in [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] above. Recount with
 * `rawUnsanitizedWidgetTextAssignments` after fixing a batch; never raise further without an inline justification
 * comment at the call site.
 */
private const val KNOWN_UNSANITIZED_WIDGET_TEXT_ASSIGNMENTS = 1

/**
 * Ledger, W6b round 8 ([RAW_OPTIONS_LABEL_MAP]). Two documented exceptions, both an enum's own `.name` mapped to
 * itself (`it.name to it.name`) -- a compile-time-fixed identifier, never a server-/member-controlled field, so
 * there is nothing to sanitize: `DocumentsScreen.kt` (`DocumentsAuthzUi.allowedCreateLevels(role)`, a
 * `DocumentAccessLevel` enum) and `MemberAdministrationScreen.kt` (`selectableRolesFor(callerRole)`, an
 * `AccountRole` enum). Every other `it.<field> to it.<field>` pair found in the initial full-codebase sweep (21+
 * call sites, see [network.lapis.cloud.client.untrustedOptions] KDoc) was migrated onto that helper. Recount with
 * `rawUnsanitizedOptionsLabelMaps` after fixing a batch; never raise further without an inline justification
 * comment at the call site, matching the two exceptions above.
 */
private const val KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS = 2

class ClientUntrustedWidgetTextTripwireTest :
    FunSpec({
        val files = WIDGET_TEXT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("the scan sees the client sources") {
            (files.size > 100) shouldBe true
            files.any { it.name == "MotionsScreen.kt" } shouldBe true
        }

        test("the detector recognizes a raw dotted field access handed to div/span/p/h1..h6, and a sanitized one") {
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""row.div(amendment.title) { addCssClasses("flex-grow-1") }""") shouldBe true
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""nameArea.span(row.displayName) { addCssClasses("fw-bold") }""") shouldBe true
            // wrapped in sanitizeUntrustedI18nText(...), the div( is no longer directly followed by a bare dotted
            // identifier chain (an extra "(" sits in between), so the raw detector itself does not match here --
            // that is exactly why the line-level ALREADY_SAFE check exists as a second, line-scoped signal (below).
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""row.div(sanitizeUntrustedI18nText(amendment.title))""") shouldBe false
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""panel.h2(tr("Vote")) { addCssClass("h5") }""") shouldBe false
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""panel.div(gettext("Quorum: %1%", committee.quorumPercent))""") shouldBe false
            ALREADY_SAFE.containsMatchIn(
                """row.div(sanitizeUntrustedI18nText(amendment.title)) { addCssClasses("flex-grow-1") }""",
            ) shouldBe
                true
        }

        test("the detector recognizes the round-4 untrusted* helpers as already-safe") {
            ALREADY_SAFE.containsMatchIn(
                """headerRow.untrustedCardTitle(order.description)""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """row.untrustedDiv(mandate.memberDisplayName, className = "flex-grow-1")""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """container.untrustedSpan(donor.displayName, className = "fw-bold")""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """panel.untrustedP(motion.rationale, className = "mb-0")""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """panel.untrustedHeading(provider.displayName, 2, className = "h5")""",
            ) shouldBe true
        }

        test("round 7: the detector recognizes a raw link(...) call, and untrustedLink(...) as already-safe") {
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(
                """container.link(document.title, url = "javascript:void(0)", dataNavigo = false)""",
            ) shouldBe true
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(
                """receiptRow.link(receipt.originalFilename, url = TravelExpenseHttp.receiptDownloadUrl(receipt.id), target = "_blank")""",
            ) shouldBe true
            // a link(...) call whose first argument is a trusted tr(...) result (a static action label, not a
            // server-/member-controlled field) must not be flagged.
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(
                """actions.link(tr("Herunterladen"), url = DocumentHttp.downloadUrl(document.id, version.id))""",
            ) shouldBe false
            ALREADY_SAFE.containsMatchIn(
                """container.untrustedLink(document.title, url = "javascript:void(0)", dataNavigo = false)""",
            ) shouldBe true
        }

        test("the detector still fires on the lambda-cell shape (container.span(row.field) inside a DataColumn cell)") {
            rawUnsanitizedWidgetTextCalls(
                File.createTempFile("dataColumnCell", ".kt").apply {
                    writeText("""cell = { container, entry -> container.span(entry.description) { addCssClass("fw-bold") } },""")
                    deleteOnExit()
                },
            ).size shouldBe 1
        }

        test("the count of raw, unsanitized DTO-field widget text calls only ever goes down from the W6b round 4 ledger") {
            val findings = files.flatMap { f -> rawUnsanitizedWidgetTextCalls(f).map { "${f.name}: $it" } }
            (findings.size <= KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS) shouldBe true
            // Non-vacuity guard: the ledger being small must not mean the detector stopped detecting anything --
            // it must still recognize a deliberately reintroduced violation (never actually added to source).
            (findings.size >= 1) shouldBe true
            findings.any { it.startsWith("ApiKeysScreen.kt:") && it.contains("result.rawKey") } shouldBe true
            // Regression guards: round 3's MotionsScreen.kt fix and round 4's closed sites must never reappear.
            findings.none { it.startsWith("MotionsScreen.kt:") && it.contains("amendment.title") } shouldBe true
            findings.none { it.contains("post.authorDisplayName") } shouldBe true
            findings.none { it.contains("report.purpose") } shouldBe true
            findings.none { it.contains("entry.description") } shouldBe true
            // Regression guards, round 7: the three raw link(...) findings (major finding 1) must never reappear.
            findings.none { it.startsWith("DocumentsScreen.kt:") && it.contains("document.title") } shouldBe true
            findings.none { it.contains("receipt.originalFilename") } shouldBe true
        }

        test("the assignment detector recognizes widget.content = dotted.field, and a sanitized/helper one") {
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """tanPromptLabel.content = outcome.bankPrompt""",
            ) shouldBe true
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """scrollBox.content = d.text""",
            ) shouldBe true
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """untrustedContent(tanPromptLabel, outcome.bankPrompt)""",
            ) shouldBe false
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """tanExpiryLabel.content = gettext("Gültig bis %1 Uhr.", outcome.expiresAt.toString())""",
            ) shouldBe false
            ALREADY_SAFE.containsMatchIn(
                """untrustedContent(tanPromptLabel, outcome.bankPrompt)""",
            ) shouldBe true
        }

        test("the count of raw, unsanitized DTO-field widget text ASSIGNMENTS only ever goes down from the W6b round 5 ledger") {
            val findings = files.flatMap { f -> rawUnsanitizedWidgetTextAssignments(f).map { "${f.name}: $it" } }
            (findings.size <= KNOWN_UNSANITIZED_WIDGET_TEXT_ASSIGNMENTS) shouldBe true
            // Documented exception (round 6): EventsScreen.kt's result.message is a trusted tr()/gettext() result,
            // not untrusted DTO content -- see the KNOWN_UNSANITIZED_WIDGET_TEXT_ASSIGNMENTS KDoc above.
            findings.any { it.startsWith("EventsScreen.kt:") && it.contains("result.message") } shouldBe true
            // Regression guards: the other four round-5 findings must never reappear as a raw assignment.
            findings.none { it.contains("outcome.bankPrompt") } shouldBe true
            findings.none { it.contains("requestedProvider.displayName") } shouldBe true
            findings.none { it.startsWith("AccountingExportScreen.kt:") && it.contains("disclaimer.text") } shouldBe true
            findings.none { it.startsWith("ConferenceScreen.kt:") && it.contains("d.text") } shouldBe true
            // Non-vacuity guard: the detector must still recognize a violation shape when one is deliberately present.
            (
                rawUnsanitizedWidgetTextAssignments(
                    File.createTempFile("assignmentShape", ".kt").apply {
                        writeText("""label.content = donor.displayName""")
                        deleteOnExit()
                    },
                ).size
            ) shouldBe 1
        }

        test("round 8: the detector recognizes a raw it.field to it.field options map, and untrustedOptions(...) as already-safe") {
            RAW_OPTIONS_LABEL_MAP.containsMatchIn(
                """panel.select(options = recipientCandidates.map { it.id to it.displayName }, label = tr("Empfänger"))""",
            ) shouldBe true
            RAW_OPTIONS_LABEL_MAP.containsMatchIn(
                """row.select(options = currentBreakoutRooms.map { it.id to it.label }, value = currentAssignmentId)""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """panel.select(options = untrustedOptions(recipientCandidates.map { it.id to it.displayName }), label = tr("Empfänger"))""",
            ) shouldBe true
        }

        test("the count of raw, unsanitized DTO-field options label maps only ever goes down from the W6b round 8 ledger") {
            val findings = files.flatMap { f -> rawUnsanitizedOptionsLabelMaps(f).map { "${f.name}: $it" } }
            (findings.size <= KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS) shouldBe true
            // Documented exceptions (round 8): both are an enum's own `.name` mapped to itself, not untrusted DTO
            // content -- see the KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS KDoc above.
            findings.any { it.startsWith("DocumentsScreen.kt:") && it.contains("it.name to it.name") } shouldBe true
            findings.any { it.startsWith("MemberAdministrationScreen.kt:") && it.contains("it.name to it.name") } shouldBe true
            // Regression guards: the round-8 findings fixed via untrustedOptions(...) must never reappear raw.
            findings.none { it.startsWith("LtrLedgerScreen.kt:") && it.contains("it.id to it.displayName") } shouldBe true
            findings.none { it.startsWith("MotionsScreen.kt:") && it.contains("it.id to it.label") } shouldBe true
            // Non-vacuity guard: the detector must still recognize a violation shape when one is deliberately present.
            (
                rawUnsanitizedOptionsLabelMaps(
                    File.createTempFile("optionsLabelMapShape", ".kt").apply {
                        writeText("""val options = candidates.map { it.id to it.displayName }""")
                        deleteOnExit()
                    },
                ).size
            ) shouldBe 1
        }
    })

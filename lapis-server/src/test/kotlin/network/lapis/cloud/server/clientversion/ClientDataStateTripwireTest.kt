package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * R34 ratchet (W5, V1.4.31): a screen that READS from the server shows its load as one of the shared states (loading text, error with
 * "Erneut versuchen", empty text, content) -- `dataSection`, or the building blocks `dataSectionViews`/`dataErrorState`. A static scan
 * cannot prove a screen does that; what it can do is count the read calls of every client file that uses NONE of those building
 * blocks, and let that number only go down. The list of files below is the ledger of what is still to migrate.
 *
 * A "read call" is `rpcService<..>().<name>(` where the name starts with list/get/find/load/search/read/fetch/current/my/status/count/
 * summary/preview/resolve/history. The scan is a floor, not a ceiling: a load through a helper or an unusual verb is not seen. It is also
 * FILE-granular (audit V1.4.31): a file with ONE state block counts as migrated even if it reads elsewhere without one -- so "0 reads outside a
 * state region" for a migrated file means "has a state block", not "every read of it is inside one". Counting per read would need the
 * region boundaries, which a text scan cannot see.
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val READ_CALL =
    Regex(
        """rpcService<\w+>\(\)\s*\.\s*(?:list|get|find|load|search|read|fetch|current|my|status|count|summary|preview|resolve|history)\w*\(""",
    )

private val STATE_BLOCK = Regex("""\b(?:dataSection|dataSectionViews|dataErrorState)\b""")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** Read calls per file, for files that use no state block at all. */
private fun readsOutsideStateRegions(files: List<File>): Map<String, Int> =
    files
        .associate { file ->
            val code = file.readLines().filterNot { isCommentLine(it) }.joinToString("\n")
            file.name to if (STATE_BLOCK.containsMatchIn(code)) 0 else READ_CALL.findAll(code).count()
        }.filterValues { it > 0 }

/**
 * Measured after W5's migration (PostalMail, EventRooms, Catering, MyVolunteerShifts, the LTR balance strip). Only ever lowered,
 * with TWO documented exceptions: Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" adds ONE more fire-and-forget sidebar-badge
 * read in `Sidebar.kt` (`listSubmittedArticles().size`, the same `runCatching`/`AppScope.launch`/no-state-block shape
 * `reliefLink`/`travelExpenseLink`/the volunteer-allowance counter already establish there) -- a background badge count has no
 * sensible `dataSection`/`dataErrorState` presentation (there is no visible "loading"/"error" state for a sidebar number), so this
 * is not migration debt, it is the same accepted shape as its three siblings, one more time. 147 -> 148.
 * Welle V1.9.12 "Mitfahrerzentrale" turns `CommunicationScreen.kt`'s `renderInbox` from a bare `unreadCount()` (not matched by
 * [READ_CALL] at all -- "unreadCount" starts with neither "count" nor any other listed prefix) into a real message list via
 * `listInbox()` (which IS matched -- starts with "list"), one more read call in a file this wave does not otherwise migrate onto
 * `dataSection`. 148 -> 149.
 * Follow-up wave "Wiederkehrende Veranstaltungen: Client/iCal/i18n" adds ONE more, independently of the above (both landed on
 * separate branches from the same 148 baseline, merged together here): `EventSeriesEditor.kt`'s debounced `previewSeries(`
 * live-preview call (400ms after every keystroke/selection change in the recurrence editor) has the same "no sensible
 * loading/error state" shape as the accepted badge reads above -- the live sentence it feeds simply stays blank until the next
 * successful response, exactly like a badge count staying at its last value. 149 -> 150.
 * Welle V1.9.15 "SuperMailer" adds ONE more, in the new `MailingHtmlEditor.kt` (a new file, hence 44 -> 45 files too): the editor's
 * "Vorschau" action calls `previewMailingHtml(` on demand from a button inside `runGuardedAction`. Like the debounced series preview
 * above it is a user-triggered, stateless computation with no list to show a loading/empty/error state for -- a failure already
 * toasts through `guarded`, and the preview box simply stays as it was. 150 -> 151.
 * Welle V1.9.25 "Abstimmen im Konferenzraum" adds TWO, both in the new `ConferenceVotePanel.kt` (a new file, hence 45 -> 46 files too): the
 * 5-second `getRoomVotingState(` poll and the on-demand `getElection(` load behind "Zur Wahlkabine". Neither has a sensible `dataSection`:
 * the poll runs in the background and deliberately shows NO error (a failed poll leaves the list as it was and, after three misses in a
 * row, a calm "Status wird aktualisiert" line replaces the toast a `dataErrorState` would be); the booth load is a one-shot action whose
 * failure is a plain inline note and whose success replaces the whole overview. Same accepted shape as the badge reads above.
 * 151 -> 153.
 * Welle V1.9.82 "Veranstaltungs-Feed, Archiv und Import" adds ONE more, in the new `EventImportScreen.kt` (a new file, hence 46 -> 47 files
 * too): the "Vorschau prüfen" action calls `previewEventImport(` on demand. Same accepted shape as `MailingHtmlEditor.kt`'s `previewMailingHtml(`
 * above: a user-triggered, stateless dry run with no list to load, so no loading/empty/error state to present -- a failure already toasts
 * through `guarded` and the screen simply stays in the editor. (The method name starts with "preview", which [READ_CALL] counts as a read.)
 * 153 -> 154.
 * Welle V1.9.101 "Mitgliederversammlung bereinigen" REMOVES two member-directory reads from `MeetingsScreen.kt` (`listMembers()` of the creation form
 * and of the meeting detail; `MotionsScreen.kt` swaps its `listMembers()` for `getCurrentMember()`, net zero) and adds FOUR accepted ones: in
 * `CommitteesScreen.kt` the stateless number N (`countGeneralAssemblyEligible(`, deliberately WITHOUT an error state: a failed count only drops the
 * figure from the sentence) and the roster read of the new assembly view (`listCommitteeMembers(`, the same call the old roster made, now once
 * more for the assembly's own view), in `MeetingsScreen.kt` the same N for the invitation (`countGeneralAssemblyEligible(`, net the file went
 * 7 -> 6), and in the new `RemoteMemberPicker.kt` (a new file, hence 47 -> 48 files) the person search itself (`searchCommitteeCandidates(`: a
 * debounced, user-triggered dropdown whose empty/failed states live inside the list, a failure toasts through `guarded`). 154 -> 156.
 * Welle V1.9.102 "Gremien, Welle B" adds ONE more in `CommitteesScreen.kt`: the notice band's `getCommitteeAutoEndNotice(` read. It is a
 * supplementary hint above the committee list, not the screen's content: a failed load toasts through `guarded` and the band simply stays
 * hidden, so there is no loading/empty/error state to show (an empty notice is deliberately invisible). Same accepted shape as the badge
 * reads above. 156 -> 157.
 * Welle V1.9.105 "Artikel-Import" adds ONE more, in the new `ArticleImportScreen.kt` (a new file, hence 48 -> 49 files): the "Vorschau prüfen"
 * action calls `previewArticleImport(` on demand -- the same accepted shape as `EventImportScreen.kt`'s `previewEventImport(` above (a
 * user-triggered, stateless dry run: a failure toasts through `guarded` and the screen stays in the editor). 157 -> 158.
 * Welle V1.9.108 review fix adds ONE more, in `ConferenceScreen.kt` (no new file): after a FAILED moderation policy call the panel re-reads the
 * room (`getRoom(`) so it shows the stored state, not a local guess -- a one-off recovery read inside an existing action, failures toast
 * through `guarded` and the panel keeps its state. 158 -> 159.
 * Welle V1.9.111 "Dynamische Mailinglisten" adds SIX in three new files plus one existing one (hence 49 -> 52 files): the rule builder's live count (`MailingRuleBuilder.kt`,
 * `previewDynamicRecipients(`, a debounced, stateless dry run whose failure shows its own "Vorschau nicht verfügbar" text -- the accepted shape
 * of `previewEventImport(` above) and the two option loads that fill the builder's committee and tier checkboxes (`listCommittees(`,
 * `listMembershipTiers(`) in `MailingListCreateForm.kt` and `DynamicMailingListRows.kt` (the edit form). A failed option load toasts through
 * `guarded` and the builder simply offers no entries ("Es gibt noch keine aktiven Gremien."): there is no content region to put a loading/error state
 * into. The sixth is `CommunicationScreen.kt`'s re-read of `listMailingLists(` after a saved rule, to rebuild the detail from the stored state (an action
 * follow-up, no screen load). 159 -> 165.
 * The lower bound keeps the scanner honest: a broken regex that suddenly finds much less fails the second assertion.
 */
private const val READ_OUTSIDE_STATE_REGION_MAX = 165
private const val FILES_OUTSIDE_STATE_REGION_MAX = 52

/**
 * A `when` branch that returns a raw German label (`X -> "Geplant"`) shows German in every language: a label goes through
 * `gettext`/`tr`. Measured before W5: 33 such branches (committee types, meeting/resolution/motion/vote statuses). Now 0.
 */
private val RAW_GERMAN_BRANCH = Regex("""^\s*[\w.]+\s*->\s*"[A-ZÄÖÜ]""")

private fun rawGermanBranchFindings(files: List<File>): List<String> =
    files.flatMap { file ->
        file
            .readLines()
            .filterNot { isCommentLine(it) }
            .filter { RAW_GERMAN_BRANCH.containsMatchIn(it) }
            .map { "${file.name}: ${it.trim()}" }
    }

class ClientDataStateTripwireTest :
    FunSpec({
        val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("R34 ratchet: read calls in files without any state block only ever go down, and the scanner is not vacuous") {
            val findings = readsOutsideStateRegions(files)
            val total = findings.values.sum()
            (total <= READ_OUTSIDE_STATE_REGION_MAX) shouldBe true
            (total >= READ_OUTSIDE_STATE_REGION_MAX - 5) shouldBe true
            (findings.size <= FILES_OUTSIDE_STATE_REGION_MAX) shouldBe true
            (findings.size >= FILES_OUTSIDE_STATE_REGION_MAX - 3) shouldBe true
        }

        test("no when branch returns a raw German label (must go through gettext/tr), and the detector sees one") {
            rawGermanBranchFindings(files) shouldBe emptyList()
            RAW_GERMAN_BRANCH.containsMatchIn("        MeetingStatus.PLANNED -> \"Geplant\"") shouldBe true
            RAW_GERMAN_BRANCH.containsMatchIn("        MeetingStatus.PLANNED -> gettext(\"Geplant\")") shouldBe false
            RAW_GERMAN_BRANCH.containsMatchIn("        Kind.LIVE -> \"rtmp://example\"") shouldBe false
        }

        test("the migrated W5 screens use a state block, the detector sees a read call and a state block, and skips comments") {
            listOf("PostalMailScreen.kt", "EventRoomsScreen.kt", "CateringScreen.kt", "MyVolunteerShiftsScreen.kt", "LtrLedgerScreen.kt")
                .forEach { name -> (name in readsOutsideStateRegions(files)) shouldBe false }
            READ_CALL.containsMatchIn("    guarded { rpcService<IEventService>().listEvents(q) }") shouldBe true
            READ_CALL.containsMatchIn("    rpcService<IEventService>()\n        .getEvent(id)") shouldBe true
            READ_CALL.containsMatchIn("    rpcService<IEventService>().createEvent(input)") shouldBe false
            STATE_BLOCK.containsMatchIn("    root.dataSection<List<X>>(") shouldBe true
            isCommentLine("    // rpcService<IEventService>().listEvents(q)") shouldBe true
        }
    })

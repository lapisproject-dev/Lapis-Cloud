package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbNoticeCode
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.domain.VotingRightSource
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * V1.9.226 -- the comparison of a [LupaBbDataset] with the members. READ ONLY: one transaction, no lock, nothing written.
 *
 * Matching, in this order (the first rule that applies wins):
 *  1. the person number is on a member: that member (anonymised members are skipped);
 *  2. the person number is on the erasure suppression list: skipped (the person was erased under Art. 17 GDPR);
 *  2b. a not yet linked member whose CSV-import reference (`external_reference`) is this person number: that member (several: ambiguous);
 *  3. exactly one member with the same e-mail address AND the same name, no person number yet: the record is linked to it;
 *  4. any other member with the same e-mail or the same name and no person number: ambiguous, an ADMIN decides (at most five candidates);
 *  5. nobody: a new member (only with a valid e-mail address and, if the chapter duty is on, a chapter).
 *
 * The e-mail address of an existing member is never part of the comparison result (it is the sign-in identity); a difference only shows as a
 * notice. A member with a management role (BOARD, TREASURER, ADMIN) and the acting ADMIN are never changed beyond the person number link, the
 * "missing" hint and the LupaBB side-table profile; the core fields and the status of such a record are reconciled by hand (reason
 * `ADMIN_ROLE_MANUAL`; a record that has nothing left to write lands in the error class).
 *
 * V1.9.303 -- the title is its own field: the display name is computed WITHOUT it. One tolerance: a Lapis display name that equals
 * "<LupaBB title> <computed name>" counts as unchanged, see [isLegacyTitledDisplayName].
 */
private val blankRun = Regex("""\s+""")

private fun normalizeBlanks(value: String): String = value.trim().replace(blankRun, " ")

/**
 * V1.9.303 -- the ONE tolerance of the display-name comparison. Earlier syncs (and hand edits) stored "<title> <name>" as display name; since
 * the title is its own field (V151) the sync computes the display name WITHOUT the title. A Lapis display name that equals
 * "<LupaBB title> <computed name>" after blank normalisation (trim, runs of whitespace -> one blank) counts as UNCHANGED: no difference,
 * no rewrite. Case-sensitive; only against the LupaBB title (never member.title); never when the LupaBB title is empty. A changed title
 * therefore shows two rows (display name AND title) on purpose, so a letter never reads "Prof. Dr. Dr. X".
 */
internal fun isLegacyTitledDisplayName(
    lapisDisplayName: String,
    lupaTitle: String?,
    lupaDisplayName: String,
): Boolean {
    val t = lupaTitle?.let(::normalizeBlanks).orEmpty()
    if (t.isEmpty()) return false
    return normalizeBlanks(lapisDisplayName) == normalizeBlanks("$t $lupaDisplayName")
}

private val nonWordRegex = Regex("""[^\p{L}\p{N}]+""")
private val titleStrippedTokens =
    setOf("dr", "prof", "dipl", "ing", "mag", "med", "hc", "jr", "sr", "von", "zu", "van", "de", "der", "mba", "phd", "bsc", "msc")

private fun nameTokens(value: String): List<String> = value.lowercase(Locale.ROOT).split(nonWordRegex).filter { it.isNotEmpty() }

/** V1.9.303 -- "probably the same person twice in LupaBB": sorted name tokens without title tokens (both titles + stripped tokens) equal and not empty. */
internal fun isLikelyDuplicate(
    memberName: String,
    memberTitle: String?,
    recordName: String,
    recordTitle: String?,
): Boolean {
    val drop = nameTokens(memberTitle.orEmpty()).toSet() + nameTokens(recordTitle.orEmpty()) + titleStrippedTokens
    val a = nameTokens(memberName).filterNot { it in drop }.sorted()
    val b = nameTokens(recordName).filterNot { it in drop }.sorted()
    return a.isNotEmpty() && a == b
}

internal object LupaBbPlanner {
    const val MAX_CANDIDATES = 5

    /** What may still be written for a member with a management role (and for the acting ADMIN): no core field, no status, no chapter. */
    private val PROTECTED_SAFE_FIELDS =
        setOf(
            LupaBbField.PERSON_NUMBER,
            LupaBbField.MISSING_FLAG,
            LupaBbField.GIVEN_NAME,
            LupaBbField.FAMILY_NAME,
            LupaBbField.TITLE,
            LupaBbField.NAME_AFFIX,
            LupaBbField.COMPANY,
            LupaBbField.PERSON_TYPE,
            LupaBbField.ADDRESS_SUPPLEMENT,
            LupaBbField.PO_BOX,
            LupaBbField.PHONE,
            LupaBbField.MOBILE,
        )
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    private val strippedNameTokens =
        setOf("dr", "prof", "dipl", "ing", "mag", "med", "hc", "jr", "sr", "von", "zu", "van", "de", "der", "mba", "phd", "bsc", "msc")

    @Suppress("LongParameterList")
    fun plan(
        dataset: LupaBbDataset,
        actor: Uuid,
        secretBox: SecretBox?,
        chapterEnforced: Boolean,
        today: LocalDate,
    ): LupaBbPlan =
        transaction {
            val members = LupaBbMemberLoader.loadAll()
            // an empty answer while members are linked is a broken answer, never "everybody left LupaBB"
            if (dataset.parsed.isEmpty() && members.any { it.personNumber != null }) {
                throw LupaBbFetchException(LupaBbErrorCode.UNEXPECTED_RESPONSE)
            }
            val chapters =
                RegionalChapterTable.selectAll().associate {
                    normalizeLabel(it[RegionalChapterTable.name]) to
                        (it[RegionalChapterTable.id] to it[RegionalChapterTable.name])
                }
            val chapterNames = chapters.values.associate { it.first to it.second }
            val suppressed = if (secretBox == null) emptySet() else LupaBbSuppression.allHmacs()
            val index = MemberIndex(members)
            val rows = ArrayList<PlanRow>(dataset.parsed.size)
            val context =
                Context(
                    actor = actor,
                    secretBox = secretBox,
                    suppressed = suppressed,
                    chapterEnforced = chapterEnforced,
                    chapters = chapters,
                    chapterNames = chapterNames,
                    associationsSupplied = dataset.regionalAssociationsSupplied,
                    today = today,
                )
            for (parsed in dataset.parsed) {
                rows +=
                    when (parsed) {
                        is ParsedRecord.Rejected ->
                            PlanRow(
                                personNumber = parsed.personNumber,
                                name = parsed.name,
                                email = parsed.email,
                                category = parsed.category,
                                reasons = listOf(PlanReason(code = parsed.reason, param = parsed.param)),
                            )
                        is ParsedRecord.Valid -> planRecord(r = parsed.record, index = index, ctx = context)
                    }
            }
            val present =
                dataset.parsed
                    .map { it.personNumber }
                    .filter { it > 0 }
                    .toSet()
            for (m in members) {
                val pn = m.personNumber ?: continue
                if (pn in present || m.anonymized || m.missingSince != null) continue
                rows +=
                    PlanRow(
                        personNumber = pn,
                        name = m.displayName,
                        email = m.email,
                        category = LupaBbCategory.MISSING,
                        memberId = m.id,
                        diffs = listOf(FieldDiff(field = LupaBbField.MISSING_FLAG, before = null, after = today.toString())),
                        reasons = listOf(PlanReason(code = LupaBbRowReasonCode.MISSING_IN_LUPABB)),
                        fingerprint = m.fingerprint(),
                    )
            }
            val notices = if (dataset.regionalAssociationsSupplied) emptyList() else listOf(LupaBbNoticeCode.CHAPTER_NOT_SUPPLIED)
            LupaBbPlan(rows = rows, notices = notices, dataSha256 = dataset.dataSha256)
        }

    private class Context(
        val actor: Uuid,
        val secretBox: SecretBox?,
        val suppressed: Set<String>,
        val chapterEnforced: Boolean,
        val chapters: Map<String, Pair<Uuid, String>>,
        val chapterNames: Map<Uuid, String>,
        val associationsSupplied: Boolean,
        val today: LocalDate,
    ) {
        val anyChapter: Boolean get() = chapters.isNotEmpty()
    }

    private class MemberIndex(
        members: List<LapisMember>,
    ) {
        val byNumber: Map<Long, LapisMember> = members.filter { it.personNumber != null }.associateBy { it.personNumber!! }
        val byEmail: Map<String, LapisMember> = members.associateBy { it.email.trim().lowercase() }

        /** Not yet linked members whose CSV-import reference is a number: for members of the LupaBB export that IS the LupaBB person number. */
        val byReference: Map<Long, List<LapisMember>> =
            members
                .filter { it.personNumber == null && !it.anonymized }
                .mapNotNull { m -> LupaBbSuppression.referenceNumber(m.externalReference)?.let { it to m } }
                .groupBy({ it.first }, { it.second })
        private val strippedNames = HashMap<String, MutableList<LapisMember>>()
        private val fullNames = HashMap<String, MutableList<LapisMember>>()

        init {
            for (m in members) {
                if (m.anonymized || m.personNumber != null) continue
                val tokens = tokens(m.displayName)
                fullNames.getOrPut(tokens.sorted().joinToString(" ")) { ArrayList() } += m
                strippedNames.getOrPut(tokens.filterNot { it in strippedNameTokens }.sorted().joinToString(" ")) { ArrayList() } += m
            }
        }

        fun byName(record: LupaBbRecord): List<LapisMember> {
            val keys = LinkedHashSet<List<LapisMember>>()
            fullNames[tokens(record.displayName).sorted().joinToString(" ")]?.let { keys += it }
            val core = tokens(listOfNotNull(record.givenName, record.familyName).joinToString(" "))
            if (core.isNotEmpty()) strippedNames[core.filterNot { it in strippedNameTokens }.sorted().joinToString(" ")]?.let { keys += it }
            record.title?.takeIf { it.isNotBlank() }?.let { t ->
                fullNames[tokens("$t ${record.displayName}").sorted().joinToString(" ")]?.let { keys += it }
            }
            if (record.personType == "LEGAL" && record.company != null) {
                fullNames[tokens(record.company).sorted().joinToString(" ")]?.let { keys += it }
            }
            return keys.flatten().distinctBy { it.id }
        }
    }

    private fun tokens(value: String): List<String> = value.lowercase(Locale.ROOT).split(nonWord).filter { it.isNotEmpty() }

    private fun normalizeLabel(value: String): String = tokens(value).joinToString(" ")

    private fun planRecord(
        r: LupaBbRecord,
        index: MemberIndex,
        ctx: Context,
    ): PlanRow {
        fun base(
            category: LupaBbCategory,
            vararg reasons: PlanReason,
            candidates: List<LapisMember> = emptyList(),
        ) = PlanRow(
            personNumber = r.personNumber,
            name = r.displayName,
            email = r.email,
            category = category,
            reasons = reasons.toList(),
            candidates = candidates.take(MAX_CANDIDATES).map { PlanCandidate(memberId = it.id, name = it.displayName, email = it.email) },
        )
        if (ctx.secretBox != null &&
            ctx.secretBox.let { LupaBbSuppression.hmac(secretBox = it, personNumber = r.personNumber) } in ctx.suppressed
        ) {
            return base(LupaBbCategory.SKIPPED, PlanReason(code = LupaBbRowReasonCode.ERASED_IN_LAPIS))
        }
        val byNumber = index.byNumber[r.personNumber]
        if (byNumber != null) {
            return if (byNumber.anonymized) {
                base(
                    LupaBbCategory.SKIPPED,
                    PlanReason(code = LupaBbRowReasonCode.ANONYMIZED),
                )
            } else {
                existing(r = r, m = byNumber, ctx = ctx)
            }
        }
        val byReference = index.byReference[r.personNumber].orEmpty()
        if (byReference.isNotEmpty()) {
            // the import stored the LupaBB person number as reference: a certain match (never a new member), a clash is for an ADMIN to decide
            return if (byReference.size == 1) {
                existing(r = r, m = byReference.single(), ctx = ctx)
            } else {
                base(
                    LupaBbCategory.AMBIGUOUS,
                    PlanReason(code = LupaBbRowReasonCode.MULTIPLE_CANDIDATES),
                    candidates = byReference,
                )
            }
        }
        val emailMember = r.email?.let { index.byEmail[it] }
        val nameMembers = index.byName(r)
        return when {
            emailMember != null && emailMember.anonymized ->
                base(
                    LupaBbCategory.AMBIGUOUS,
                    PlanReason(code = LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER),
                )
            // linked to another person already: no candidate to offer (the link would be refused)
            emailMember != null && emailMember.personNumber != null ->
                base(
                    LupaBbCategory.AMBIGUOUS,
                    PlanReason(code = LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER),
                ).copy(
                    conflictMemberId = emailMember.id,
                    conflictMemberName = emailMember.displayName,
                    conflictPersonNumber = emailMember.personNumber,
                    conflictLikelyDuplicate =
                        isLikelyDuplicate(
                            memberName = emailMember.displayName,
                            memberTitle = emailMember.title,
                            recordName = r.displayName,
                            recordTitle = r.title,
                        ),
                )
            emailMember != null && nameMembers.any { it.id == emailMember.id } ->
                if (nameMembers.size == 1) {
                    existing(r = r, m = emailMember, ctx = ctx)
                } else {
                    base(
                        LupaBbCategory.AMBIGUOUS,
                        PlanReason(code = LupaBbRowReasonCode.MULTIPLE_CANDIDATES),
                        candidates = listOf(emailMember) + nameMembers.filter { it.id != emailMember.id },
                    )
                }
            emailMember != null ->
                base(
                    LupaBbCategory.AMBIGUOUS,
                    PlanReason(code = LupaBbRowReasonCode.NAME_MISMATCH),
                    candidates = listOf(emailMember) + nameMembers,
                )
            nameMembers.size > 1 ->
                base(LupaBbCategory.AMBIGUOUS, PlanReason(code = LupaBbRowReasonCode.MULTIPLE_CANDIDATES), candidates = nameMembers)
            nameMembers.size == 1 ->
                base(
                    LupaBbCategory.AMBIGUOUS,
                    PlanReason(
                        code =
                            if (r.email ==
                                null
                            ) {
                                (r.emailIssue ?: LupaBbRowReasonCode.NO_EMAIL)
                            } else {
                                LupaBbRowReasonCode.EMAIL_DIFFERS
                            },
                    ),
                    candidates = nameMembers,
                )
            r.email == null ->
                base(
                    if (r.emailIssue == LupaBbRowReasonCode.INVALID_EMAIL) LupaBbCategory.ERROR else LupaBbCategory.SKIPPED,
                    PlanReason(code = r.emailIssue ?: LupaBbRowReasonCode.NO_EMAIL),
                )
            else -> newMember(r = r, ctx = ctx)
        }
    }

    private fun newMember(
        r: LupaBbRecord,
        ctx: Context,
    ): PlanRow {
        val chapter = chapterFor(r = r, ctx = ctx)
        val reasons = ArrayList<PlanReason>()
        if (chapter.notFound != null) reasons += PlanReason(code = LupaBbRowReasonCode.CHAPTER_NOT_FOUND, param = chapter.notFound)
        val chapterId = chapter.id?.takeIf { r.status in RegionalChapterRules.ASSIGNABLE_STATUSES }
        val category =
            if (r.status == MemberStatus.ACTIVE && ctx.chapterEnforced && ctx.anyChapter && chapterId == null) {
                reasons += PlanReason(code = LupaBbRowReasonCode.CHAPTER_REQUIRED)
                LupaBbCategory.ERROR
            } else {
                LupaBbCategory.NEW
            }
        val empty = emptyMemberFor(r)
        return PlanRow(
            personNumber = r.personNumber,
            name = r.displayName,
            email = r.email,
            category = category,
            record = r,
            diffs = diffs(r = r, m = empty, ctx = ctx, chapterId = chapterId),
            reasons = reasons,
            chapterId = chapterId,
        )
    }

    private fun emptyMemberFor(r: LupaBbRecord) =
        LapisMember(
            id = Uuid.NIL,
            email = r.email.orEmpty(),
            displayName = "",
            status = r.status,
            street = null,
            postalCode = null,
            city = null,
            countryCode = null,
            dateOfBirth = null,
            regionalChapterId = null,
            personNumber = null,
            missingSince = null,
            anonymized = false,
            role = null,
            profile = null,
        )

    private class ChapterMatch(
        val id: Uuid?,
        val notFound: String?,
    )

    private fun chapterFor(
        r: LupaBbRecord,
        ctx: Context,
    ): ChapterMatch {
        val label = r.regionalAssociationLabel ?: return ChapterMatch(id = null, notFound = null)
        val match = ctx.chapters[normalizeLabel(label)]
        return if (match == null) ChapterMatch(id = null, notFound = label.take(80)) else ChapterMatch(id = match.first, notFound = null)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun existing(
        r: LupaBbRecord,
        m: LapisMember,
        ctx: Context,
    ): PlanRow {
        val reasons = ArrayList<PlanReason>()
        val chapter = chapterFor(r = r, ctx = ctx)
        if (chapter.notFound != null) reasons += PlanReason(code = LupaBbRowReasonCode.CHAPTER_NOT_FOUND, param = chapter.notFound)
        val chapterId = chapter.id?.takeIf { m.regionalChapterId == null && r.status in RegionalChapterRules.ASSIGNABLE_STATUSES }
        var diffs = diffs(r = r, m = m, ctx = ctx, chapterId = chapterId)
        var rowChapterId = chapterId
        var protectedDiffs: List<FieldDiff> = emptyList()
        var protectedChapterId: Uuid? = null
        var protectedBlocker: LupaBbRowReasonCode? = null
        if (r.country == LupaBbCountry.Unresolved) reasons += PlanReason(code = LupaBbRowReasonCode.COUNTRY_AMBIGUOUS)
        if (r.email != null && r.email != m.email.trim().lowercase()) reasons += PlanReason(code = LupaBbRowReasonCode.EMAIL_DIFFERS)
        if (m.missingSince != null) reasons += PlanReason(code = LupaBbRowReasonCode.REAPPEARED)
        // V1.9.298 -- a voting right set by hand is never overwritten: an info line, NOT a field difference and NOT an error
        if (votingRightWritable(r = r, m = m) && m.votingRightSource == VotingRightSource.MANUAL && m.votingRight != r.votingRight) {
            reasons += PlanReason(code = LupaBbRowReasonCode.VOTING_RIGHT_MANUAL, param = "${m.votingRight}>${r.votingRight}")
        }
        var category = if (diffs.isEmpty()) LupaBbCategory.UNCHANGED else LupaBbCategory.CHANGED
        val statusChanged = r.status != m.status
        val protectedMember = m.role != null && m.role in ESCALATED_ROLES || m.id == ctx.actor
        if (protectedMember) {
            // the safe subset (person number link, "missing" flag, LupaBB side-table profile) is still written; the core fields, the status and
            // the chapter go to manual handling
            val safe = diffs.filter { it.field in PROTECTED_SAFE_FIELDS }
            val manual = diffs.size - safe.size
            if (manual > 0) reasons += PlanReason(code = LupaBbRowReasonCode.ADMIN_ROLE_MANUAL)
            if (manual > 0) {
                // everything a manual take-over would write (the role is never a field, a MANUAL voting right is not in the list)
                protectedDiffs = diffs
                protectedChapterId = chapterId
                protectedBlocker =
                    when {
                        m.id == ctx.actor -> LupaBbRowReasonCode.OWN_RECORD
                        statusChanged &&
                            !LupaBbStatusRules.syncAllows(
                                from = m.status,
                                to = r.status,
                                memberPersonNumber = m.personNumber,
                                recordPersonNumber = r.personNumber,
                            ) -> {
                            reasons +=
                                PlanReason(code = LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED, param = "${m.status}>${r.status}")
                            LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
                        }
                        statusChanged && r.status == MemberStatus.DONOR && m.role != null && m.role in ESCALATED_ROLES ->
                            LupaBbRowReasonCode.ROLE_BLOCKS_STATUS
                        statusChanged && m.role == AccountRole.ADMIN && r.status in MemberStatusSets.LOGIN_BLOCKED ->
                            LupaBbRowReasonCode.PEER_APPROVAL_REQUIRED
                        statusChanged &&
                            r.status == MemberStatus.ACTIVE &&
                            ctx.chapterEnforced &&
                            ctx.anyChapter &&
                            (m.regionalChapterId ?: chapterId) == null -> LupaBbRowReasonCode.CHAPTER_REQUIRED
                        else -> null
                    }
            }
            category =
                when {
                    safe.isNotEmpty() -> LupaBbCategory.CHANGED
                    manual > 0 -> LupaBbCategory.ERROR
                    else -> LupaBbCategory.UNCHANGED
                }
            if (category == LupaBbCategory.CHANGED) {
                diffs = safe
                rowChapterId = null
            }
        } else if (statusChanged) {
            if (!LupaBbStatusRules.syncAllows(
                    from = m.status,
                    to = r.status,
                    memberPersonNumber = m.personNumber,
                    recordPersonNumber = r.personNumber,
                )
            ) {
                reasons += PlanReason(code = LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED, param = "${m.status}>${r.status}")
                category = LupaBbCategory.ERROR
            } else {
                if (m.status == MemberStatus.DECEASED) reasons += PlanReason(code = LupaBbRowReasonCode.LEAVES_DECEASED)
                val effectiveChapter = m.regionalChapterId ?: chapterId
                if (r.status == MemberStatus.ACTIVE && ctx.chapterEnforced && ctx.anyChapter && effectiveChapter == null) {
                    reasons += PlanReason(code = LupaBbRowReasonCode.CHAPTER_REQUIRED)
                    category = LupaBbCategory.ERROR
                }
            }
        }
        return PlanRow(
            personNumber = r.personNumber,
            name = r.displayName,
            email = r.email,
            category = category,
            memberId = m.id,
            record = r,
            diffs = diffs,
            statusFrom = m.status,
            reasons = reasons,
            fingerprint = m.fingerprint(),
            chapterId = rowChapterId,
            protectedDiffs = protectedDiffs,
            protectedChapterId = protectedChapterId,
            isOwnRecord = m.id == ctx.actor,
            protectedBlocker = protectedBlocker,
        )
    }

    /** V1.9.298 -- whether the sync may look at the voting right of [m] at all (the record carries a value; not anonymised, not deceased). */
    private fun votingRightWritable(
        r: LupaBbRecord,
        m: LapisMember,
    ): Boolean = r.votingRight != null && !m.anonymized && m.status != MemberStatus.DECEASED

    /** The fields in which LupaBB differs from [m]. The e-mail address is NOT a field. Two-way: an empty LupaBB value clears the Lapis value. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun diffs(
        r: LupaBbRecord,
        m: LapisMember,
        ctx: Context,
        chapterId: Uuid?,
    ): List<FieldDiff> {
        val out = ArrayList<FieldDiff>()
        val p = m.profile

        fun add(
            field: LupaBbField,
            before: String?,
            after: String?,
        ) {
            val b = before?.trim()?.takeIf { it.isNotEmpty() }
            val a = after?.trim()?.takeIf { it.isNotEmpty() }
            if (b != a) out += FieldDiff(field = field, before = b, after = a)
        }
        if (m.personNumber != r.personNumber) add(LupaBbField.PERSON_NUMBER, m.personNumber?.toString(), r.personNumber.toString())
        val legacy = isLegacyTitledDisplayName(lapisDisplayName = m.displayName, lupaTitle = r.title, lupaDisplayName = r.displayName)
        val displayAfter = if (legacy) m.displayName else r.displayName
        add(LupaBbField.DISPLAY_NAME, m.displayName, displayAfter)
        add(LupaBbField.GIVEN_NAME, p?.givenName, r.givenName)
        add(LupaBbField.FAMILY_NAME, p?.familyName, r.familyName)
        add(LupaBbField.TITLE, m.title, r.title)
        add(LupaBbField.NAME_AFFIX, p?.nameAffix, r.nameAffix)
        add(LupaBbField.COMPANY, p?.company, r.company)
        add(LupaBbField.PERSON_TYPE, p?.personType, r.personType)
        add(LupaBbField.STREET, m.street, r.street)
        add(LupaBbField.ADDRESS_SUPPLEMENT, p?.addressSupplement, r.addressSupplement)
        add(LupaBbField.PO_BOX, p?.poBox, r.poBox)
        add(LupaBbField.POSTAL_CODE, m.postalCode, r.postalCode)
        add(LupaBbField.CITY, m.city, r.city)
        when (val c = r.country) {
            LupaBbCountry.Empty -> add(LupaBbField.COUNTRY, m.countryCode, null)
            is LupaBbCountry.Code -> add(LupaBbField.COUNTRY, m.countryCode, c.code)
            LupaBbCountry.Unresolved -> Unit
        }
        add(LupaBbField.DATE_OF_BIRTH, m.dateOfBirth?.toString(), r.dateOfBirth?.toString())
        add(LupaBbField.PHONE, p?.phone, r.phone)
        add(LupaBbField.MOBILE, p?.mobile, r.mobile)
        if (chapterId != null) add(LupaBbField.REGIONAL_CHAPTER, null, ctx.chapterNames[chapterId])
        if (r.status != m.status) add(LupaBbField.STATUS, m.status.name, r.status.name)
        // V1.9.298 -- the voting right, only when the label says something, never on an anonymised or deceased member, and never over a value an
        // administrator set by hand (that case is only a notice, see [existing]).
        if (votingRightWritable(r = r, m = m) && m.votingRightSource != VotingRightSource.MANUAL) {
            add(LupaBbField.VOTING_RIGHT, m.votingRight.toString(), r.votingRight.toString())
        }
        if (m.missingSince != null) add(LupaBbField.MISSING_FLAG, m.missingSince.toString(), null)
        return out
    }
}

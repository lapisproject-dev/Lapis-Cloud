package network.lapis.cloud.server.db

import network.lapis.cloud.server.activism.ActivismActionValues
import network.lapis.cloud.server.activism.ActivismSignalStore
import network.lapis.cloud.server.activism.ActivismStore
import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.uuid.Uuid

/**
 * Sample actions of the activism module (V1.9.296): one in each of the five states that need no settings change -- planned (info stand with
 * signals), running (postering with confirmed, open and rejected contributions), completed (signature collection with a result), idea (door to door,
 * visible only to its responsible person and to managers) and withdrawn (visible only to managers). AWAITING_REVIEW is left out: it exists only
 * while `activismReviewRequired` is on, which is a settings change. No reports and no material (material needs a document file).
 *
 * Written ONLY through `ActivismStore` / `ActivismSignalStore`, the one writer of the module's tables (`ActivismTripwireTest` forbids the table
 * objects anywhere else); the participant check therefore uses the general organization-member status set, which holds the same statuses. An action
 * is written as a whole: its id is missing and every responsible person, contributor and signalling member is an active organization member;
 * otherwise that action is skipped alone. The signal is a bare "I would take part" of a fictitious person and is never audited. No audit entry is
 * written for the actions (the live service audits status changes; the seed writes the result of those, like the other parts).
 *
 * The fixed ids cover the action, its tasks, text blocks and contributions; responsible rows and signal rows get random ids (the store API is
 * pinned), so a test compares their COUNTS per action.
 */
internal object StagingSeedActivism : StagingSeedPart {
    override val name: String = "activism"

    data class PlannedTask(
        val slot: Int,
        val text: String,
        val assignee: Int?,
        val dueInDays: Int?,
        val doneDaysAgo: Int?,
    )

    data class PlannedContribution(
        val slot: Int,
        val member: Int,
        val quantity: Int,
        val area: String?,
        val status: ActivismContributionStatus,
        val reportedDaysAgo: Int,
        val rejectReason: String? = null,
    )

    data class PlannedResult(
        val participants: Int,
        val quantity: Int,
        val text: String,
        val daysAgo: Int,
    )

    data class PlannedAction(
        val index: Int,
        val title: String,
        val kind: ActivismKind,
        val status: ActivismStatus,
        val description: String,
        val location: String?,
        /** Wall-clock start, in days from today at [startHour]; null for an idea without a date. */
        val startsInDays: Int?,
        val startHour: Int?,
        val endsInDays: Int?,
        val endHour: Int?,
        val formAppointment: Boolean,
        val formProgress: Boolean,
        val formSignal: Boolean,
        val goalQuantity: Int?,
        val unit: String?,
        val confirmContributions: Boolean,
        val responsibles: List<Int>,
        val tasks: List<PlannedTask>,
        val textBlocks: List<Pair<String, String>>,
        val contributions: List<PlannedContribution>,
        val signals: List<Int>,
        val result: PlannedResult?,
        val withdrawnReason: String?,
        val createdDaysAgo: Int,
    ) {
        val id: Uuid get() = SeedIds.activism(index)

        fun taskId(slot: Int): Uuid = SeedIds.activism(0x2000 + index * 0x10 + slot)

        fun textBlockId(slot: Int): Uuid = SeedIds.activism(0x3000 + index * 0x10 + slot)

        fun contributionId(slot: Int): Uuid = SeedIds.activism(0x4000 + index * 0x10 + slot)

        /** Every seed member number the action involves. */
        val members: Set<Int>
            get() = (responsibles + signals + contributions.map { it.member } + tasks.mapNotNull { it.assignee }).toSet()
    }

    /** Append-only: the index is the id. */
    val actions: List<PlannedAction> =
        listOf(
            PlannedAction(
                index = 1,
                title = "Infostand auf dem Marktplatz (Demodaten)",
                kind = ActivismKind.INFO_STAND,
                status = ActivismStatus.PLANNED,
                description = "Wir informieren Passanten am Infostand ueber unsere Arbeit (Demodaten, fiktive Aktion).",
                location = "Marktplatz Musterstadt (fiktiv)",
                startsInDays = 10,
                startHour = 10,
                endsInDays = 10,
                endHour = 14,
                formAppointment = true,
                formProgress = false,
                formSignal = true,
                goalQuantity = null,
                unit = null,
                confirmContributions = false,
                responsibles = listOf(4, 5),
                tasks =
                    listOf(
                        PlannedTask(slot = 1, text = "Tisch und Schirm besorgen (Demodaten)", assignee = 4, dueInDays = 8, doneDaysAgo = 3),
                        PlannedTask(slot = 2, text = "Flyer abholen (Demodaten)", assignee = 5, dueInDays = 9, doneDaysAgo = null),
                        PlannedTask(
                            slot = 3,
                            text = "Helfer fuer den Aufbau finden (Demodaten)",
                            assignee = null,
                            dueInDays = null,
                            doneDaysAgo = null,
                        ),
                    ),
                textBlocks =
                    listOf(
                        "Hinweise zum Aufbau (Demodaten)" to
                            "Treffpunkt eine halbe Stunde vor Beginn am Marktplatz Musterstadt. Alle Angaben sind erfundene Demodaten.",
                    ),
                contributions = emptyList(),
                signals = listOf(6, 7, 8, 10, 19, 20),
                result = null,
                withdrawnReason = null,
                createdDaysAgo = 14,
            ),
            PlannedAction(
                index = 2,
                title = "Plakatieren in Musterstadt (Demodaten)",
                kind = ActivismKind.POSTERING,
                status = ActivismStatus.RUNNING,
                description = "Wir haengen Plakate im Stadtgebiet auf (Demodaten, fiktive Aktion).",
                location = "Stadtgebiet Musterstadt (fiktiv)",
                startsInDays = -5,
                startHour = 9,
                endsInDays = 9,
                endHour = 18,
                formAppointment = false,
                formProgress = true,
                formSignal = false,
                goalQuantity = 200,
                unit = "Plakate",
                confirmContributions = true,
                responsibles = listOf(6),
                tasks =
                    listOf(
                        PlannedTask(slot = 1, text = "Plakate drucken lassen (Demodaten)", assignee = 6, dueInDays = null, doneDaysAgo = 6),
                        PlannedTask(slot = 2, text = "Routen verteilen (Demodaten)", assignee = null, dueInDays = 2, doneDaysAgo = null),
                    ),
                textBlocks = emptyList(),
                contributions =
                    listOf(
                        PlannedContribution(
                            slot = 1,
                            member = 19,
                            quantity = 30,
                            area = "Nordstadt (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 4,
                        ),
                        PlannedContribution(
                            slot = 2,
                            member = 20,
                            quantity = 25,
                            area = "Nordstadt (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 4,
                        ),
                        PlannedContribution(
                            slot = 3,
                            member = 21,
                            quantity = 40,
                            area = "Altstadt (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 3,
                        ),
                        PlannedContribution(
                            slot = 4,
                            member = 23,
                            quantity = 20,
                            area = "Suedstadt (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 2,
                        ),
                        PlannedContribution(
                            slot = 5,
                            member = 24,
                            quantity = 15,
                            area = "Altstadt (fiktiv)",
                            status = ActivismContributionStatus.REPORTED,
                            reportedDaysAgo = 1,
                        ),
                        PlannedContribution(
                            slot = 6,
                            member = 27,
                            quantity = 10,
                            area = "Suedstadt (fiktiv)",
                            status = ActivismContributionStatus.REPORTED,
                            reportedDaysAgo = 0,
                        ),
                        PlannedContribution(
                            slot = 7,
                            member = 28,
                            quantity = 50,
                            area = null,
                            status = ActivismContributionStatus.REJECTED,
                            reportedDaysAgo = 3,
                            rejectReason = "Demodaten: doppelt gemeldet.",
                        ),
                    ),
                signals = emptyList(),
                result = null,
                withdrawnReason = null,
                createdDaysAgo = 12,
            ),
            PlannedAction(
                index = 3,
                title = "Unterschriftensammlung (Demodaten)",
                kind = ActivismKind.SIGNATURES,
                status = ActivismStatus.COMPLETED,
                description = "Wir sammeln Unterschriften fuer ein fiktives Buergerbegehren (Demodaten, fiktive Aktion).",
                location = "Fussgaengerzone Musterstadt (fiktiv)",
                startsInDays = -30,
                startHour = 10,
                endsInDays = -16,
                endHour = 18,
                formAppointment = false,
                formProgress = true,
                formSignal = false,
                goalQuantity = 500,
                unit = "Unterschriften",
                confirmContributions = true,
                responsibles = listOf(5),
                tasks = emptyList(),
                textBlocks = emptyList(),
                contributions =
                    listOf(
                        PlannedContribution(
                            slot = 1,
                            member = 29,
                            quantity = 120,
                            area = "Fussgaengerzone (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 25,
                        ),
                        PlannedContribution(
                            slot = 2,
                            member = 30,
                            quantity = 140,
                            area = "Fussgaengerzone (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 22,
                        ),
                        PlannedContribution(
                            slot = 3,
                            member = 32,
                            quantity = 110,
                            area = "Bahnhof (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 20,
                        ),
                        PlannedContribution(
                            slot = 4,
                            member = 33,
                            quantity = 90,
                            area = "Bahnhof (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 18,
                        ),
                        PlannedContribution(
                            slot = 5,
                            member = 34,
                            quantity = 74,
                            area = "Marktplatz (fiktiv)",
                            status = ActivismContributionStatus.CONFIRMED,
                            reportedDaysAgo = 17,
                        ),
                    ),
                signals = emptyList(),
                result =
                    PlannedResult(
                        participants = 8,
                        quantity = 534,
                        text = "Das Ziel wurde erreicht. Alle Angaben sind erfundene Demodaten.",
                        daysAgo = 15,
                    ),
                withdrawnReason = null,
                createdDaysAgo = 45,
            ),
            PlannedAction(
                index = 4,
                title = "Hausbesuche in der Nordstadt (Demodaten)",
                kind = ActivismKind.DOOR_TO_DOOR,
                status = ActivismStatus.IDEA,
                description = "Eine Idee, die noch niemand eingeplant hat (Demodaten, fiktive Aktion).",
                location = null,
                startsInDays = null,
                startHour = null,
                endsInDays = null,
                endHour = null,
                formAppointment = false,
                formProgress = false,
                formSignal = false,
                goalQuantity = null,
                unit = null,
                confirmContributions = false,
                responsibles = listOf(7),
                tasks = emptyList(),
                textBlocks = emptyList(),
                contributions = emptyList(),
                signals = emptyList(),
                result = null,
                withdrawnReason = null,
                createdDaysAgo = 3,
            ),
            PlannedAction(
                index = 5,
                title = "Online-Aktionstag (Demodaten)",
                kind = ActivismKind.ONLINE,
                status = ActivismStatus.WITHDRAWN,
                description = "Ein gemeinsamer Aktionstag im Netz, der wieder abgesagt wurde (Demodaten, fiktive Aktion).",
                location = null,
                startsInDays = 20,
                startHour = 18,
                endsInDays = 20,
                endHour = 20,
                formAppointment = true,
                formProgress = false,
                formSignal = false,
                goalQuantity = null,
                unit = null,
                confirmContributions = false,
                responsibles = listOf(8),
                tasks = emptyList(),
                textBlocks = emptyList(),
                contributions = emptyList(),
                signals = emptyList(),
                result = null,
                withdrawnReason = "Demodaten: Termin entfaellt.",
                createdDaysAgo = 20,
            ),
        )

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        var inserted = 0
        var skipped = 0
        val statuses = statusesOf(actions.flatMap { it.members } + ADMIN)
        actions.forEach { action ->
            val eligible =
                statuses[ADMIN] in MemberStatusSets.ORGANIZATION_MEMBER &&
                    action.members.all { statuses[it] in MemberStatusSets.ORGANIZATION_MEMBER }
            if (eligible && ActivismStore.getAction(id = action.id) == null) {
                inserted += write(action = action, clock = clock)
            } else {
                skipped++
            }
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }

    /** Order inside one action matters: the task and text block positions are computed from the rows already there. */
    private fun write(
        action: PlannedAction,
        clock: SeedClock,
    ): Int {
        val created = clock.utcAt(daysAgo = action.createdDaysAgo)
        ActivismStore.insertAction(
            id = action.id,
            values =
                ActivismActionValues(
                    title = action.title,
                    description = action.description,
                    goalText = null,
                    location = action.location,
                    startsAt = action.startsInDays?.let { clock.wallAt(daysFromToday = it, hour = requireNotNull(action.startHour)) },
                    endsAt = action.endsInDays?.let { clock.wallAt(daysFromToday = it, hour = requireNotNull(action.endHour)) },
                    kind = action.kind,
                    formAppointment = action.formAppointment,
                    formProgress = action.formProgress,
                    formSignal = action.formSignal,
                    goalQuantity = action.goalQuantity,
                    unit = action.unit,
                    confirmContributions = action.confirmContributions,
                    ownNotice = null,
                ),
            createdBy = SeedIds.member(action.responsibles.first()),
            now = created,
        )
        ActivismStore.replaceResponsibles(actionId = action.id, memberIds = action.responsibles.map { SeedIds.member(it) }.toSet())
        if (action.status != ActivismStatus.IDEA) {
            // approvedBy stays null: the review is off. One call per action (every call raises the revision).
            // A completed action changes status at the moment its result is recorded, so updated_at never precedes the status change.
            val statusAt = action.result?.let { clock.utcAt(daysAgo = it.daysAgo) } ?: clock.now
            ActivismStore.setStatus(id = action.id, status = action.status, now = statusAt, withdrawnReason = action.withdrawnReason)
        }
        action.tasks.forEach { task ->
            ActivismStore.insertTask(
                actionId = action.id,
                text = task.text,
                assignee = task.assignee?.let { SeedIds.member(it) },
                dueOn = task.dueInDays?.let { clock.dayAgo(-it) },
                id = action.taskId(task.slot),
            )
            task.doneDaysAgo?.let {
                ActivismStore.setTaskDone(
                    id = action.taskId(task.slot),
                    done = true,
                    by = SeedIds.member(task.assignee ?: action.responsibles.first()),
                    now = clock.utcAt(daysAgo = it),
                )
            }
        }
        action.textBlocks.forEachIndexed { i, (title, body) ->
            ActivismStore.insertTextBlock(actionId = action.id, title = title, body = body, id = action.textBlockId(i + 1))
        }
        action.contributions.forEach { contribution ->
            val reported = clock.utcAt(daysAgo = contribution.reportedDaysAgo, hour = 11)
            ActivismStore.insertContribution(
                actionId = action.id,
                memberId = SeedIds.member(contribution.member),
                quantity = contribution.quantity,
                area = contribution.area,
                note = null,
                now = reported,
                id = action.contributionId(contribution.slot),
            )
            if (contribution.status != ActivismContributionStatus.REPORTED) {
                ActivismStore.decideContribution(
                    id = action.contributionId(contribution.slot),
                    status = contribution.status,
                    reason = contribution.rejectReason,
                    by = SeedIds.member(ADMIN),
                    now = clock.utcAt(daysAgo = contribution.reportedDaysAgo, hour = 17),
                )
            }
        }
        action.signals.forEach { ActivismSignalStore.set(actionId = action.id, memberId = SeedIds.member(it)) }
        action.result?.let {
            ActivismStore.setResult(
                id = action.id,
                participants = it.participants,
                quantity = it.quantity,
                text = it.text,
                now = clock.utcAt(daysAgo = it.daysAgo),
            )
        }
        return 1 + action.responsibles.size + action.tasks.size + action.textBlocks.size + action.contributions.size + action.signals.size
    }

    private const val ADMIN = 1
}

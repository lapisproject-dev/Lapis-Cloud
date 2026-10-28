package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MyProtocolTaskDto
import network.lapis.cloud.shared.rpc.IMeetingProtocolService

/** V1.9.240 -- the RPCs of "my tasks" behind one seam (a test replaces them). */
internal class MyProtocolTasksRpc(
    val list: suspend () -> List<MyProtocolTaskDto> = { rpcService<IMeetingProtocolService>().getMyProtocolTasks() },
    val setDone: suspend (taskKey: String, done: Boolean) -> MyProtocolTaskDto = { key, done ->
        rpcService<IMeetingProtocolService>().setMyProtocolTaskDone(key, done)
    },
)

/**
 * V1.9.240 -- "Meine Aufgaben aus Protokollen": the tasks of the signed-in person from the newest CONFIRMED version of each Protokoll. Shown only
 * when there are any (a person without tasks sees nothing). The task text is foreign data and shown as text only. A tick marks the task done
 * (kept outside the immutable Protokoll); an icon-less text button reopens it.
 */
internal fun renderMyProtocolTasks(
    parent: Container,
    rpc: MyProtocolTasksRpc = MyProtocolTasksRpc(),
): Div {
    val host = Div(className = "my-protocol-tasks")
    parent.add(host)
    host.hide()

    fun paint(tasks: List<MyProtocolTaskDto>) {
        host.removeAll()
        if (tasks.isEmpty()) {
            host.hide()
            return
        }
        host.show()
        host.h2(tr("Meine Aufgaben aus Protokollen")) { addCssClass("h5") }
        val box = host.vPanel(spacing = 4)
        val table = box.standardTable(emptyList())
        listOf(tr("Aufgabe"), tr("Sitzung"), tr("Fällig bis"), tr("Status"), tr("Aktion")).forEach {
            table.addHeaderCell(HeaderCell(content = it, scope = Scope.COL))
        }
        tasks.forEach { task ->
            table.row {
                textCell(task.description)
                textCell("${task.committeeName}, ${formatDate(task.meetingDate)}")
                textCell(task.dueDate?.let { formatDate(it) } ?: "–")
                cell {
                    when {
                        task.done -> statusBadge(gettext("Erledigt"), "success")
                        task.overdue -> statusBadge(gettext("überfällig"), "danger")
                        else -> statusBadge(gettext("offen"), "secondary")
                    }
                }
                cell {
                    val label = if (task.done) tr("Wieder öffnen") else tr("Erledigt")
                    val button =
                        tableActionGroup().actionButton(
                            if (task.done) ActionIcon.UNDO else ActionIcon.APPROVE,
                            label,
                            style = ButtonStyle.OUTLINESECONDARY,
                            small = true,
                        )
                    button.onClick {
                        runGuardedAction(button) {
                            val saved = guarded { rpc.setDone(task.taskKey, !task.done) } ?: return@runGuardedAction
                            paint(tasks.map { if (it.taskKey == saved.taskKey) saved else it })
                        }
                    }
                }
            }
        }
    }

    AppScope.launch {
        val tasks =
            try {
                rpc.list()
            } catch (_: Exception) {
                emptyList()
            }
        paint(tasks)
    }
    return host
}

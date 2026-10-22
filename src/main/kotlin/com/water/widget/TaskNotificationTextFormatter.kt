package com.water.widget

import com.water.widget.ui.TaskLogFormatter

data class TaskNotificationText(
    val title: String,
    val text: String
)

/** 只负责把结构化任务状态压缩成通知栏可读的单行/双通道摘要。 */
object TaskNotificationTextFormatter {
    fun format(state: TaskRunState): TaskNotificationText {
        if (!state.running) {
            return when (state.result) {
                TaskRunResult.COMPLETED -> TaskNotificationText(
                    title = "积分任务已完成",
                    text = "本次已确认增加 ${state.totalGained} 分"
                )
                TaskRunResult.PARTIAL -> TaskNotificationText(
                    title = "积分任务已结束 · 部分未完成",
                    text = if (state.totalGained > 0) "本次已确认增加 ${state.totalGained} 分，可在运行记录中查看详情"
                        else "暂未确认有积分增加，可在运行记录中查看详情"
                )
                TaskRunResult.CANCELLED -> TaskNotificationText(
                    title = "积分任务已停止",
                    text = if (state.totalGained > 0) "停止前已确认增加 ${state.totalGained} 分"
                        else "停止前暂未确认有积分增加"
                )
                TaskRunResult.FAILED -> TaskNotificationText(
                    title = "积分任务运行失败",
                    text = state.logs.lastOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?.let(TaskLogFormatter::readableText)
                        ?: if (state.totalGained > 0) "本次已确认增加 ${state.totalGained} 分"
                            else "暂未确认有积分增加"
                )
                TaskRunResult.NONE -> TaskNotificationText(
                    title = "积分任务",
                    text = "等待开始"
                )
            }
        }

        val active = state.lanes.filter {
            it.phase != TaskLanePhase.COMPLETED && it.phase != TaskLanePhase.FAILED
        }
        val detail = when {
            active.isEmpty() -> "正在收尾"
            active.size == 1 -> laneText(active.first())
            else -> {
                val shown = active.take(2).joinToString("；") { laneText(it) }
                if (active.size > 2) "$shown；还有其他账号正在处理" else shown
            }
        }
        return TaskNotificationText(
            title = if (state.totalGained > 0) "积分任务运行中 · 已确认增加 ${state.totalGained} 分"
                else "积分任务运行中 · 暂未确认有积分增加",
            text = detail
        )
    }

    private fun laneText(lane: TaskLaneState): String {
        val account = lane.accountLabel.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()
        return "$account${TaskLogFormatter.readableText(lane.currentTask)}"
    }
}

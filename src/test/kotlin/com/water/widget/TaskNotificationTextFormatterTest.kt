package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskNotificationTextFormatterTest {
    @Test fun runningWithoutVerifiedCreditDoesNotClaimZeroEarned() {
        assertEquals("积分任务运行中 · 暂未确认有积分增加",
            TaskNotificationTextFormatter.format(TaskRunState(running = true)).title)
    }

    @Test fun partialRunWithoutVerifiedCreditDoesNotClaimZeroEarned() {
        assertEquals("暂未确认有积分增加，可在运行记录中查看详情",
            TaskNotificationTextFormatter.format(TaskRunState(result = TaskRunResult.PARTIAL)).text)
    }
    @Test
    fun formatsSingleLaneWithCurrentAccountAndTask() {
        val state = TaskRunState(
            running = true,
            totalGained = 15,
            lanes = listOf(
                TaskLaneState(
                    laneId = 1,
                    laneCount = 1,
                    accountIndex = 1,
                    accountCount = 2,
                    accountLabel = "测试账号",
                    currentTask = "[APP] 每日浏览（2/3）",
                    phase = TaskLanePhase.RUNNING_MISSION,
                    gained = 15
                )
            )
        )

        assertEquals(
            TaskNotificationText(
                title = "积分任务运行中 · 已确认增加 15 分",
                text = "测试账号 · [APP] 每日浏览（2/3）"
            ),
            TaskNotificationTextFormatter.format(state)
        )
    }

    @Test
    fun formatsTwoConcurrentLanesWithoutLosingEitherCurrentTask() {
        val state = TaskRunState(
            running = true,
            totalGained = 10,
            lanes = listOf(
                TaskLaneState(
                    laneId = 1,
                    laneCount = 2,
                    accountCount = 1,
                    accountLabel = "账号甲",
                    currentTask = "每日签到",
                    phase = TaskLanePhase.SIGNING_IN
                ),
                TaskLaneState(
                    laneId = 2,
                    laneCount = 2,
                    accountCount = 1,
                    accountLabel = "账号乙",
                    currentTask = "[支付宝] 浏览商品",
                    phase = TaskLanePhase.RUNNING_MISSION
                )
            )
        )

        assertEquals(
            "账号甲 · 每日签到；账号乙 · [支付宝] 浏览商品",
            TaskNotificationTextFormatter.format(state).text
        )
    }

    @Test
    fun formatsCompletedTotal() {
        val state = TaskRunReducer.finish(
            TaskRunState(running = true, totalGained = 35)
        )

        assertEquals(
            TaskNotificationText("积分任务已完成", "本次已确认增加 35 分"),
            TaskNotificationTextFormatter.format(state)
        )
    }

    @Test
    fun formatsPartialCompletionWithoutClaimingFullSuccess() {
        val state = TaskRunReducer.finish(
            TaskRunState(running = true, totalGained = 20),
            partial = true
        )

        assertEquals(
            TaskNotificationText(
                "积分任务已结束 · 部分未完成",
                "本次已确认增加 20 分，可在运行记录中查看详情"
            ),
            TaskNotificationTextFormatter.format(state)
        )
    }

    @Test
    fun formatsFailureWithActionableReason() {
        val state = TaskRunReducer.fail(
            TaskRunState(totalGained = 5),
            "上一次任务请求仍在收尾，请约半分钟后重试。"
        )

        assertEquals(
            TaskNotificationText(
                "积分任务运行失败",
                "上一次任务请求仍在收尾，请约半分钟后重试。"
            ),
            TaskNotificationTextFormatter.format(state)
        )
    }
}

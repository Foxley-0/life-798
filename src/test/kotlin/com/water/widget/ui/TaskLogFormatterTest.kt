package com.water.widget.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskLogFormatterTest {
    @Test fun technicalMessagesHavePlainLanguageWithoutChangingConfirmedMeaning() {
        assertEquals("正在确认积分到账", TaskLogFormatter.readableText("核验累计积分变化"))
        assertEquals("签到请求成功，等待确认到账", TaskLogFormatter.readableText("签到请求成功，到账待核验"))
        assertEquals("暂时无法确认到账，保留此前已确认的积分",
            TaskLogFormatter.readableText("到账核验失败，保留此前已核验积分"))
    }

    @Test
    fun hidesInternalSchedulingDetails() {
        assertNull(
            TaskLogFormatter.format(
                "共 2 个账号，分为 1 个安全通道并发执行；共享账号标识、uid 或 Token 的账号将串行执行。"
            )
        )
        assertNull(TaskLogFormatter.format("[测试账号] 每日签到: 未签到，优先执行"))
    }

    @Test
    fun convertsAccountHeaderToReadableProgress() {
        assertEquals(
            "正在处理：测试账号",
            TaskLogFormatter.format(
                "---------- [测试账号] 通道 1/2，账号 1/2 ----------"
            )
        )
    }

    @Test
    fun keepsMissionResultsAndFinalSummaries() {
        assertNull(TaskLogFormatter.format("[1/8] [APP] 浏览任务 +10分"))
        assertEquals(
            "[测试账号] [APP] 浏览任务 +10分",
            TaskLogFormatter.format("[测试账号] [APP] 浏览任务：成功 +10分")
        )
        assertEquals("✅ 已提交 +10分", TaskLogFormatter.format("✅ 已提交 +10分"))
        assertEquals(
            "[测试账号] 完成 · 获得 80 分",
            TaskLogFormatter.format("[测试账号] 本账号预计获得 80 分")
        )
        assertEquals(
            "全部完成 · 本次获得 80 分",
            TaskLogFormatter.format("===== 全部完成，本次预计获得 80 分 =====")
        )
        assertEquals(
            "全部完成 · 本次获得 80 分",
            TaskLogFormatter.format("===== 全部完成，本次获得 80 分 =====")
        )
        assertEquals(
            "任务结束 · 部分未完成 · 本次获得 30 分",
            TaskLogFormatter.format("===== 任务结束，部分未完成，本次获得 30 分 =====")
        )
    }

    @Test
    fun keepsSameTaskInDifferentAccountsSeparate() {
        val first = TaskLogFormatter.append(emptyList(), "[账号甲] 签到成功 +5分")
        val second = TaskLogFormatter.append(first, "[账号乙] 签到成功 +5分")

        assertEquals(
            listOf("[账号甲] 签到成功 +5分", "[账号乙] 签到成功 +5分"),
            second
        )
    }

    @Test
    fun aggregatesRoundsOnlyWithinTheSameAccount() {
        var logs = emptyList<String>()
        logs = TaskLogFormatter.append(logs, "[账号甲] [APP] 浏览任务 (1/2)：成功 +10分")
        logs = TaskLogFormatter.append(logs, "[账号甲] [APP] 浏览任务 (2/2)：成功 +10分")
        logs = TaskLogFormatter.append(logs, "[账号乙] [APP] 浏览任务 (1/2)：成功 +10分")
        logs = TaskLogFormatter.append(logs, "[账号乙] [支付宝] 浏览任务：成功 +10分")

        assertEquals(
            listOf(
                "[账号甲] [APP] 浏览任务 +20分（10分 × 2）",
                "[账号乙] [APP] 浏览任务 +10分",
                "[账号乙] [支付宝] 浏览任务 +10分"
            ),
            logs
        )
    }

    @Test fun interleavedLogsAreGroupedWithoutGuessingTheLastActiveAccount() {
        val groups = TaskLogFormatter.groupByAccount(listOf(
            "开始运行全部账号", "正在处理：账号甲", "正在处理：账号乙",
            "[账号甲] 签到请求成功，到账待核验", "[账号乙] 每日签到: 今日已签到",
            "[账号甲] 本账号已核验增加 5 分", "任务结束 · 部分未完成"
        ))
        assertEquals(listOf(null, "账号乙", "账号甲"), groups.map { it.account })
        assertEquals(listOf("任务结束 · 部分未完成", "开始运行全部账号"), groups[0].lines)
        val a = groups.single { it.account == "账号甲" }
        assertEquals(listOf("本账号已核验增加 5 分", "签到请求成功，到账待核验", "开始处理"), a.lines)
        assertEquals(listOf("每日签到: 今日已签到", "开始处理"), groups.single { it.account == "账号乙" }.lines)
    }

    @Test fun persistentRateLimitIsIncludedInFailureFiltering() {
        org.junit.Assert.assertTrue(TaskLogFormatter.isFailure("观看视频重试仍受限，跳过本任务剩余次数"))
        org.junit.Assert.assertFalse(TaskLogFormatter.isFailure("请求过于频繁，60秒后重试一次"))
    }
}

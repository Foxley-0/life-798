package com.water.widget

import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTaskScheduleTest {
    @Test fun beforeTimeRunsTodayAndAtOrAfterTimeRunsTomorrow() {
        val now = ZonedDateTime.parse("2026-09-27T08:00:00+08:00[Asia/Hong_Kong]")
        val nine = LocalTime.of(9, 0)
        assertEquals(now.withHour(9), nextAutoTaskTime(now, nine))
        assertEquals(now.plusDays(1).withHour(9), nextAutoTaskTime(now.withHour(9), nine))
        assertEquals(now.plusDays(1).withHour(9), nextAutoTaskTime(now.withHour(10), nine))
    }

    @Test fun midnightRollsIntoNextMonth() {
        val now = ZonedDateTime.parse("2026-09-30T23:59:00+08:00[Asia/Hong_Kong]")
        assertEquals("2026-10-01T00:00+08:00[Asia/Hong_Kong]",
            nextAutoTaskTime(now, LocalTime.MIDNIGHT).toString())
    }

    @Test fun nextDayKeepsLocalTimeAcrossDaylightSaving() {
        val now = ZonedDateTime.parse("2026-03-07T10:00:00-05:00[America/New_York]")
        assertEquals("2026-03-08T09:00-04:00[America/New_York]",
            nextAutoTaskTime(now, LocalTime.of(9, 0)).toString())
    }
}

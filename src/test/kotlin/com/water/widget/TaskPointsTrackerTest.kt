package com.water.widget

import org.junit.Assert.*
import org.junit.Test

class TaskPointsTrackerTest {
    @Test fun repeatedAndFinalVerificationOnlyAddNewCredit() {
        val points = TaskPointsTracker(100)
        assertEquals(7, points.record(107))
        assertEquals(0, points.record(107))
        assertEquals(3, points.record(110))
        assertEquals(0, points.record(110))
        assertEquals(10, points.total)
    }

    @Test fun failedOrStaleReadPreservesVerifiedCredit() {
        val points = TaskPointsTracker(100)
        assertEquals(7, points.record(107))
        assertNull(points.record(null))
        assertNull(points.record(105))
        assertEquals(7, points.total)
        assertEquals(3, points.record(110))
    }

    @Test fun missingBaselineDoesNotInventCredit() {
        val points = TaskPointsTracker(null)
        assertNull(points.record(120))
        assertEquals(0, points.total)
    }
}

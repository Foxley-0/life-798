package com.water.widget

import org.junit.Assert.*
import org.junit.Test

class WaterControlStateTest {
    @Test fun onlyMatchingSessionCanBeStartedOrCleared() {
        WaterControlState.begin(10, "account", "device")
        try {
            assertTrue(WaterControlState.state.value!!.starting)
            WaterControlState.started(9)
            assertTrue(WaterControlState.state.value!!.starting)
            WaterControlState.started(10)
            assertFalse(WaterControlState.state.value!!.starting)
            WaterControlState.clear(9)
            assertEquals("device", WaterControlState.state.value!!.deviceId)
            WaterControlState.clear(10)
            assertNull(WaterControlState.state.value)
        } finally { WaterControlState.clear(10) }
    }
}

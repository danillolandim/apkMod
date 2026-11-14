package com.seupainel.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    private val gb = 1024L * 1024 * 1024

    @Test fun `boxes with up to 2 GB are treated as low memory`() {
        assertTrue(DeviceProfile.isLow(lowRamFlag = false, totalRamBytes = 1 * gb))
        // 2 GB nominais aparecem como ~1,9 GB.
        assertTrue(DeviceProfile.isLow(lowRamFlag = false, totalRamBytes = (1.9 * gb).toLong()))
        assertFalse(DeviceProfile.isLow(lowRamFlag = false, totalRamBytes = (2.8 * gb).toLong()))
        assertFalse(DeviceProfile.isLow(lowRamFlag = false, totalRamBytes = 4 * gb))
    }

    @Test fun `the system flag wins and an unknown size alone does not count`() {
        assertTrue(DeviceProfile.isLow(lowRamFlag = true, totalRamBytes = 4 * gb))
        assertFalse(DeviceProfile.isLow(lowRamFlag = false, totalRamBytes = 0))
    }

    @Test fun `low memory limits are never larger than the full ones`() {
        val low = DeviceProfile.limits(true)
        val full = DeviceProfile.limits(false)
        assertTrue(low.low && !full.low)
        assertTrue(low.imageMemoryPercent < full.imageMemoryPercent)
        assertTrue(low.posterDiskBytes < full.posterDiskBytes)
        assertTrue(low.liveMaxBufferMs < full.liveMaxBufferMs && low.liveBackBufferMs < full.liveBackBufferMs)
        assertTrue(low.vodMaxBufferMs < full.vodMaxBufferMs)
        assertTrue(low.liveMinBufferMs <= low.liveMaxBufferMs && low.vodMinBufferMs <= low.vodMaxBufferMs)
        assertFalse(low.prewarmNextVideo)
    }
}

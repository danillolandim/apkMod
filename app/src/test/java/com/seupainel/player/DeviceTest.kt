package com.seupainel.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {

    private fun tv(mode: Boolean = false, leanback: Boolean = false, feature: Boolean = false, touch: Boolean = true) =
        Device.classify(televisionMode = mode, leanback = leanback, televisionFeature = feature, touchscreen = touch)

    @Test fun `a phone has a touchscreen and no television signal`() {
        assertFalse(tv())
    }

    @Test fun `any television signal makes it a tv`() {
        assertTrue(tv(mode = true))
        assertTrue(tv(leanback = true))
        assertTrue(tv(feature = true))
    }

    @Test fun `a device without touchscreen is never treated as a phone`() {
        assertTrue(tv(touch = false))
    }

    @Test fun `a tv that also reports a touchscreen stays a tv`() {
        assertTrue(tv(mode = true, leanback = true, touch = true))
    }
}

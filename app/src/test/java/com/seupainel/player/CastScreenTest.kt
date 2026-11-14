package com.seupainel.player

import com.seupainel.player.CastScreen.IntentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastScreenTest {

    private val cast = IntentSpec("android.settings.CAST_SETTINGS")
    private val display = IntentSpec("android.settings.WIFI_DISPLAY_SETTINGS")
    private val samsung = IntentSpec("com.samsung.android.smartmirroring.CAST_SETTINGS")
    private val wireless = IntentSpec("android.settings.WIRELESS_SETTINGS")

    @Test fun `tries the cast panel first and the wireless settings last`() {
        val list = CastScreen.candidates()
        assertEquals(listOf(cast, display, samsung, wireless), list)
        assertEquals(list.size, list.toSet().size)
    }

    @Test fun `uses the first screen the device can open`() {
        assertEquals(cast, CastScreen.firstAvailable(CastScreen.candidates()) { true })
        assertEquals(samsung, CastScreen.firstAvailable(CastScreen.candidates()) { it == samsung || it == wireless })
        assertEquals(wireless, CastScreen.firstAvailable(CastScreen.candidates()) { it == wireless })
    }

    @Test fun `no screen available gives null so the app can explain the manual way`() {
        assertNull(CastScreen.firstAvailable(CastScreen.candidates()) { false })
        assertNull(CastScreen.firstAvailable(emptyList()) { true })
    }

    @Test fun `stops asking after the first one that opens`() {
        val asked = mutableListOf<IntentSpec>()
        CastScreen.firstAvailable(CastScreen.candidates()) { asked += it; it == display }
        assertEquals(listOf(cast, display), asked)
    }

    @Test fun `every candidate has its own action`() {
        assertTrue(CastScreen.candidates().all { it.action.isNotBlank() })
    }
}

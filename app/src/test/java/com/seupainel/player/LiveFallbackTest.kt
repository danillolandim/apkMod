package com.seupainel.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFallbackTest {

    @Test fun `goes down one quality at a time`() {
        assertEquals(1, LiveFallback.next(count = 3, current = 0, failed = setOf(0)))
        assertEquals(2, LiveFallback.next(count = 3, current = 1, failed = setOf(0, 1)))
    }

    @Test fun `with nothing below tries one above that has not failed`() {
        assertEquals(0, LiveFallback.next(count = 3, current = 2, failed = setOf(2)))
        assertEquals(1, LiveFallback.next(count = 3, current = 2, failed = setOf(0, 2)))
    }

    @Test fun `gives up when every quality failed or there is only one`() {
        assertNull(LiveFallback.next(count = 3, current = 2, failed = setOf(0, 1, 2)))
        assertNull(LiveFallback.next(count = 1, current = 0, failed = setOf(0)))
        assertNull(LiveFallback.next(count = 0, current = 0, failed = emptySet()))
    }

    @Test fun `remembers the quality that worked for a while`() {
        val memo = LiveFallback.Memo(ttlMs = 1_000)
        memo.put("canal", 2, now = 100)
        assertEquals(2, memo.get("canal", count = 3, now = 600))
        assertNull(memo.get("outro", count = 3, now = 600))
        assertNull(memo.get("canal", count = 3, now = 1_200))
        assertNull(memo.get("canal", count = 3, now = 1_300))   // vencida some de vez
    }

    @Test fun `memory is dropped when the channel no longer has that quality or on request`() {
        val memo = LiveFallback.Memo(ttlMs = 1_000)
        memo.put("canal", 2, now = 0)
        assertNull(memo.get("canal", count = 2, now = 10))
        memo.put("canal", 1, now = 0)
        memo.forget("canal")
        assertNull(memo.get("canal", count = 3, now = 10))
        memo.put("", 1, now = 0)
        assertNull(memo.get("", count = 3, now = 10))
    }

    private fun watch() = LiveFallback.StallWatch(timeoutMs = 8_000, calmMs = 30_000, maxReconnects = 3)

    @Test fun `a channel that keeps moving is left alone`() {
        val w = watch().apply { reset(0) }
        for (s in 1..60) assertNull(w.tick(position = s * 1_000L, now = s * 1_000L, hasOtherQuality = true))
    }

    @Test fun `first stall reconnects and a second one right after goes down in quality`() {
        val w = watch().apply { reset(0) }
        assertNull(w.tick(5_000, now = 1_000, hasOtherQuality = true))
        assertNull(w.tick(5_000, now = 8_000, hasOtherQuality = true))
        assertEquals(LiveFallback.StallAction.RECONNECT, w.tick(5_000, now = 9_000, hasOtherQuality = true))
        assertNull(w.tick(5_000, now = 16_000, hasOtherQuality = true))
        assertEquals(LiveFallback.StallAction.NEXT_QUALITY, w.tick(5_000, now = 17_000, hasOtherQuality = true))
    }

    @Test fun `without another quality it reconnects a few times and then gives up`() {
        val w = watch().apply { reset(0) }
        w.tick(0, now = 0, hasOtherQuality = false)
        assertEquals(LiveFallback.StallAction.RECONNECT, w.tick(0, now = 8_000, hasOtherQuality = false))
        assertEquals(LiveFallback.StallAction.RECONNECT, w.tick(0, now = 16_000, hasOtherQuality = false))
        assertEquals(LiveFallback.StallAction.RECONNECT, w.tick(0, now = 24_000, hasOtherQuality = false))
        assertEquals(LiveFallback.StallAction.GIVE_UP, w.tick(0, now = 32_000, hasOtherQuality = false))
    }

    @Test fun `after playing calmly a new stall is treated as the first again`() {
        val w = watch().apply { reset(0) }
        w.tick(0, now = 0, hasOtherQuality = true)
        assertEquals(LiveFallback.StallAction.RECONNECT, w.tick(0, now = 8_000, hasOtherQuality = true))
        // Voltou e tocou 40 s sem travar.
        for (s in 9..48) assertNull(w.tick(s * 1_000L, now = s * 1_000L, hasOtherQuality = true))
        assertEquals(LiveFallback.StallAction.RECONNECT, w.tick(48_000, now = 56_000, hasOtherQuality = true))
    }

    @Test fun `paused time does not count as a stall`() {
        val w = watch().apply { reset(0) }
        w.tick(0, now = 0, hasOtherQuality = true)
        w.touch(60_000)
        assertNull(w.tick(0, now = 61_000, hasOtherQuality = true))
    }
}

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
}

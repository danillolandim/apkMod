package com.seupainel.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    @Test fun `version is a positive number, anything else is not a version`() {
        assertEquals(112, UpdateLogic.parseVersion(" 112\n"))
        assertNull(UpdateLogic.parseVersion("<html>Not Found</html>"))
        assertNull(UpdateLogic.parseVersion(""))
        assertNull(UpdateLogic.parseVersion(null))
        assertNull(UpdateLogic.parseVersion("0"))
    }

    @Test fun `prompts only for a newer version`() {
        assertTrue(UpdateLogic.shouldPrompt(remote = 112, local = 111, skipped = 0, manual = false))
        assertFalse(UpdateLogic.shouldPrompt(remote = 111, local = 111, skipped = 0, manual = false))
        assertFalse(UpdateLogic.shouldPrompt(remote = 110, local = 111, skipped = 0, manual = true))
    }

    @Test fun `skipped version is quiet on the automatic check until a newer one comes out`() {
        assertFalse(UpdateLogic.shouldPrompt(remote = 112, local = 111, skipped = 112, manual = false))
        assertTrue(UpdateLogic.shouldPrompt(remote = 113, local = 111, skipped = 112, manual = false))
        assertTrue(UpdateLogic.shouldPrompt(remote = 112, local = 111, skipped = 112, manual = true))
    }

    @Test fun `automatic check runs at first and then only after the gap`() {
        assertTrue(UpdateLogic.dueForAutoCheck(now = 5_000, last = 0))
        assertFalse(UpdateLogic.dueForAutoCheck(now = 60_000, last = 5_000))
        assertTrue(UpdateLogic.dueForAutoCheck(now = 5_000 + UpdateLogic.AUTO_GAP_MS, last = 5_000))
        assertTrue(UpdateLogic.dueForAutoCheck(now = 1_000, last = 5_000))
    }

    @Test fun `whats new is only the current version section`() {
        assertEquals("- A\n- B", UpdateLogic.whatsNew("# Novidades\n\n## Esta versão\n- A\n- B\n\n## Antes\n- C"))
        assertNull(UpdateLogic.whatsNew("# Novidades\n- solto"))
        assertNull(UpdateLogic.whatsNew(null))
    }

    @Test fun `download progress and completeness`() {
        assertEquals(50, UpdateLogic.percent(5, 10))
        assertNull(UpdateLogic.percent(5, -1))
        assertTrue(UpdateLogic.complete(10, 10))
        assertTrue(UpdateLogic.complete(10, -1))
        assertFalse(UpdateLogic.complete(7, 10))
        assertFalse(UpdateLogic.complete(0, -1))
    }
}

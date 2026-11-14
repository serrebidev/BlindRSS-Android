package org.blindrss.android

import org.blindrss.android.playback.parseJumpToMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekParseTest {
    @Test fun parsesAllForms() {
        assertEquals(90_000L, parseJumpToMs("90"))
        assertEquals(90_000L, parseJumpToMs(" 90 "))
        assertEquals(83_000L, parseJumpToMs("1:23"))
        assertEquals(5_025_000L, parseJumpToMs("1:23:45"))
        assertEquals(0L, parseJumpToMs("0"))
    }

    @Test fun rejectsGarbage() {
        assertNull(parseJumpToMs(""))
        assertNull(parseJumpToMs(null))
        assertNull(parseJumpToMs("abc"))
        assertNull(parseJumpToMs("1:2:3:4"))
        assertNull(parseJumpToMs("-5"))
        assertNull(parseJumpToMs("1::2"))
    }
}

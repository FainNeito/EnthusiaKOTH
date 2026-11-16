package net.badgersmc.ek.infrastructure.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MemoTest {
    @Test fun `a value is reused until it is older than its lifetime, per key`() {
        var now = 1_000L
        var loads = 0
        val memo = Memo<String, Int>(15_000) { now }
        fun read(key: String) = memo.get(key) { ++loads }
        assertEquals(1, read("a")); assertEquals(1, read("a"))
        assertEquals(2, read("b"))
        now += 14_999; assertEquals(1, read("a"))
        now += 1; assertEquals(3, read("a"))
        // A clock that steps back never serves a value from the future.
        now -= 60_000; assertEquals(4, read("a"))
        assertEquals(4, loads)
    }
}

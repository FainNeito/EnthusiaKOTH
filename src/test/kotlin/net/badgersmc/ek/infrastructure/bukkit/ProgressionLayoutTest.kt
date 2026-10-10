package net.badgersmc.ek.infrastructure.bukkit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
class ProgressionLayoutTest {
 @Test fun productionGridPreservesControlsAcrossPages() {
  assertEquals(21,ProgressionLayout.slots.size)
  assertEquals(19,ProgressionLayout.slots.first())
  assertEquals(43,ProgressionLayout.slots.last())
  assertEquals(1,ProgressionLayout.page(99,22))
  assertEquals(0,ProgressionLayout.page(-1,0))
  assertFalse(49 in ProgressionLayout.slots)
  assertEquals(mapOf(19 to "claim21",20 to "claim22"),ProgressionLayout.actions(mapOf(20 to "previous",21 to "claim21",22 to "claim22"),1,23))
 }
}

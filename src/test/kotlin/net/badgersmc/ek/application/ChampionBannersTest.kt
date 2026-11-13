package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class ChampionBannersTest {
    @TempDir lateinit var dir: File

    @Test fun `a design keeps a wall banner on the wall and a standing banner standing`() {
        val design = BannerDesign("red", emptyList())
        assertEquals("RED_WALL_BANNER", design.material(wall = true))
        assertEquals("RED_BANNER", design.material(wall = false))
        assertTrue(BannerDesign.isBanner("RED_WALL_BANNER") && BannerDesign.isWallBanner("RED_WALL_BANNER"))
        assertTrue(BannerDesign.isBanner("WHITE_BANNER") && !BannerDesign.isWallBanner("WHITE_BANNER"))
        assertFalse(BannerDesign.isBanner("RED_WOOL"))
    }

    @Test fun `an unknown base colour gives no banner rather than a broken block`() {
        assertNull(BannerDesign("GOLD", emptyList()).material(wall = true))
        assertNull(BannerDesign("", emptyList()).material(wall = false))
    }

    @Test fun `layers are normalised, unknown dyes dropped and capped at the vanilla limit`() {
        val layers = listOf(BannerDesign.Layer("STRIPE_TOP", "black"), BannerDesign.Layer("CROSS", "NOT_A_DYE"),
            BannerDesign.Layer(" ", "RED")) + List(20) { BannerDesign.Layer("BORDER", "YELLOW") }
        val usable = BannerDesign("WHITE", layers).usableLayers()
        assertEquals(BannerDesign.Layer("stripe_top", "BLACK"), usable.first())
        assertTrue(usable.none { it.type == "cross" || it.type.isBlank() })
        assertEquals(BannerDesign.MAX_LAYERS, usable.size)
    }

    @Test fun `champions survive a reload and a new winner replaces the old one`() {
        val file = File(dir, "arena-champions.tsv")
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        ChampionStore(file).apply {
            set("conquest", ArenaChampion(first, "Enthusiast", 1L))
            set("capture", ArenaChampion(first, "Tab\tName", 2L))
        }
        val reloaded = ChampionStore(file)
        assertEquals(ArenaChampion(first, "Enthusiast", 1L), reloaded.get("conquest"))
        assertEquals("Tab Name", reloaded.get("capture")?.name)
        reloaded.set("conquest", ArenaChampion(second, "Rivals", 3L))
        assertEquals(second, ChampionStore(file).get("conquest")?.guildId)
        assertEquals(first, ChampionStore(file).get("capture")?.guildId)
        assertFalse(File(dir, "arena-champions.tsv.tmp").exists())
    }

    @Test fun `a damaged line is skipped without losing the others`() {
        val file = File(dir, "arena-champions.tsv")
        val guild = UUID.randomUUID()
        file.writeText("conquest\tnot-a-uuid\t1\tBad\ncapture\t$guild\t5\tGood\nshort\n")
        val store = ChampionStore(file)
        assertNull(store.get("conquest"))
        assertEquals(ArenaChampion(guild, "Good", 5L), store.get("capture"))
        assertTrue(store.remove("capture"))
        assertFalse(store.remove("capture"))
        assertTrue(ChampionStore(file).all().isEmpty())
    }
}

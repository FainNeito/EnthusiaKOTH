package net.badgersmc.ek.infrastructure.bukkit

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupDependencyMetadataTest {
    @Test fun `Tags menu link does not force a Tags Advancements KOTH load cycle`() {
        val descriptor = Files.readString(Path.of("src/main/resources/paper-plugin.yml"))
        val tags = descriptor.substringAfter("    EnthusiaTags:").substringBefore("    LumaGuilds:")
        assertTrue(tags.contains("load: OMIT"), "The runtime rewards link must not force Tags to load before KOTH")
        assertTrue(tags.contains("required: false"))
        assertTrue(tags.contains("join-classpath: true"), "Preserve optional companion class access")
        assertTrue(descriptor.substringAfter("    EnthusiaAdvancements:").substringBefore("    EnthusiaLoreItems:").contains("load: AFTER"))
    }
}

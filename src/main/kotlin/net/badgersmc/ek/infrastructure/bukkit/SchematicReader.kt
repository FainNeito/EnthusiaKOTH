package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.PlacedBlock
import org.bukkit.Bukkit
import java.io.File

/**
 * Reads WorldEdit schematics from WorldEdit's own schematics folder. Only this class touches the WorldEdit API, so a
 * server without WorldEdit can still load KOTH; importing then simply reports that WorldEdit is missing.
 */
object SchematicReader {
    sealed interface Read {
        /** Every block of the schematic's box, relative to the spot the builder stood on when they copied it. */
        data class Blocks(val blocks: List<PlacedBlock>) : Read
        data object NoWorldEdit : Read
        data object NotFound : Read
        data class TooLarge(val volume: Long) : Read
        data class Unreadable(val reason: String) : Read
    }

    private val FILE_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")

    /** WorldEdit's schematics folder, where builders save with //schem save. */
    fun folder(): File? = Bukkit.getPluginManager().getPlugin("WorldEdit")?.let { File(it.dataFolder, "schematics") }

    /** Schematic file names in the folder, for tab completion. */
    fun names(): List<String> = folder()?.listFiles { file -> file.isFile && (file.name.endsWith(".schem") || file.name.endsWith(".schematic")) }
        .orEmpty().map { it.name }.sorted()

    /** Finds [name] in the schematics folder, with or without its extension. Names can't leave that folder. */
    fun resolve(name: String): File? {
        val root = folder() ?: return null
        if (!FILE_NAME.matches(name) || name.startsWith(".")) return null
        return listOf(name, "$name.schem", "$name.schematic").map { File(root, it) }
            .firstOrNull { it.isFile && it.canonicalFile.parentFile == root.canonicalFile }
    }

    fun read(name: String, maxVolume: Long): Read {
        if (Bukkit.getPluginManager().getPlugin("WorldEdit")?.isEnabled != true) return Read.NoWorldEdit
        val file = resolve(name) ?: return Read.NotFound
        return try {
            readFile(file, maxVolume)
        } catch (_: LinkageError) {
            Read.NoWorldEdit
        } catch (error: Exception) {
            Read.Unreadable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun readFile(file: File, maxVolume: Long): Read {
        val format = com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats.findByFile(file)
            ?: return Read.Unreadable("not a schematic WorldEdit recognises")
        val clipboard = file.inputStream().buffered().use { input -> format.getReader(input).use { it.read() } }
        val region = clipboard.region
        val min = region.minimumPoint
        val max = region.maximumPoint
        val volume = (max.x() - min.x() + 1L) * (max.y() - min.y() + 1L) * (max.z() - min.z() + 1L)
        if (volume > maxVolume) return Read.TooLarge(volume)
        val origin = clipboard.origin
        val blocks = ArrayList<PlacedBlock>(volume.toInt())
        for (position in region) {
            val data = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(clipboard.getBlock(position))
            blocks += PlacedBlock(position.x() - origin.x(), position.y() - origin.y(), position.z() - origin.z(), data.asString)
        }
        return Read.Blocks(blocks)
    }
}

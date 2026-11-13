package net.badgersmc.ek.application

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One block an arena changes when it builds itself: what stands there normally and what the built form puts there. */
data class BuildBlock(val x: Int, val y: Int, val z: Int, val base: String, val built: String)

/** The blocks an arena adds when a KOTH starts, in the world they belong to. */
data class ArenaBuild(val arenaId: String, val world: String, val blocks: List<BuildBlock>) {
    companion object {
        /**
         * The order the arena builds itself in: bottom layer first, and within a layer from the capture point outward,
         * so it rises out of the ground around the point. Teardown runs the same list backwards.
         */
        fun buildOrder(blocks: List<BuildBlock>, centerX: Double, centerZ: Double): List<BuildBlock> =
            blocks.sortedWith(compareBy<BuildBlock> { it.y }
                .thenBy { (it.x + 0.5 - centerX).let { dx -> dx * dx } + (it.z + 0.5 - centerZ).let { dz -> dz * dz } }
                .thenBy { it.x }.thenBy { it.z })

        /** Splits [count] blocks over [ticks] so every tick places a share and the last one finishes on time. */
        fun batches(count: Int, ticks: Int): List<Int> {
            if (count <= 0) return emptyList()
            val safeTicks = ticks.coerceIn(1, count)
            return (0 until safeTicks).map { tick -> count * (tick + 1) / safeTicks - count * tick / safeTicks }
        }
    }
}

/** A snapshot of an arena's region in its base form, kept so the built form can be recorded as a difference. */
data class BaseSnapshot(
    val world: String,
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
    val palette: List<String>,
    /** Palette index per block, x fastest, then z, then y. */
    val blocks: IntArray,
) {
    val sizeX: Int get() = maxX - minX + 1
    val sizeZ: Int get() = maxZ - minZ + 1

    fun at(x: Int, y: Int, z: Int): String = palette[blocks[index(x, y, z)]]

    fun index(x: Int, y: Int, z: Int): Int = ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX)

    /** The blocks that differ in [current], which reports the block data now standing at a position. */
    fun diff(arenaId: String, current: (Int, Int, Int) -> String): ArenaBuild {
        val changed = mutableListOf<BuildBlock>()
        for (y in minY..maxY) for (z in minZ..maxZ) for (x in minX..maxX) {
            val base = at(x, y, z)
            val now = current(x, y, z)
            if (now != base) changed += BuildBlock(x, y, z, base, now)
        }
        return ArenaBuild(arenaId, world, changed)
    }

    override fun equals(other: Any?): Boolean = other is BaseSnapshot && world == other.world && minX == other.minX &&
        minY == other.minY && minZ == other.minZ && maxX == other.maxX && maxY == other.maxY && maxZ == other.maxZ &&
        palette == other.palette && blocks.contentEquals(other.blocks)

    override fun hashCode(): Int = (world.hashCode() * 31 + palette.hashCode()) * 31 + blocks.contentHashCode()
}

/**
 * Files under `arena-builds/`: `<arena>.base.dat` (the base snapshot), `<arena>.build.dat` (the recorded build) and
 * `active/<arena>.dat`, a journal written before an arena builds itself and removed once it is back in its base form.
 * A journal found at startup means the server stopped mid-event, and its base blocks are put back.
 */
class ArenaBuildStore(private val folder: File) {
    private val active = File(folder, "active")

    private fun baseFile(id: String) = File(folder, "$id.base.dat")
    private fun buildFile(id: String) = File(folder, "$id.build.dat")
    private fun journalFile(id: String) = File(active, "$id.dat")

    fun hasBase(id: String) = baseFile(id).isFile
    fun hasBuild(id: String) = buildFile(id).isFile

    fun saveBase(id: String, snapshot: BaseSnapshot) = write(baseFile(id)) { out ->
        out.writeUTF(snapshot.world)
        listOf(snapshot.minX, snapshot.minY, snapshot.minZ, snapshot.maxX, snapshot.maxY, snapshot.maxZ).forEach(out::writeInt)
        out.writeInt(snapshot.palette.size); snapshot.palette.forEach(out::writeUTF)
        out.writeInt(snapshot.blocks.size); snapshot.blocks.forEach(out::writeInt)
    }

    fun loadBase(id: String): BaseSnapshot? = read(baseFile(id)) { input ->
        val world = input.readUTF()
        val bounds = IntArray(6) { input.readInt() }
        val palette = List(input.readInt()) { input.readUTF() }
        val blocks = IntArray(input.readInt()) { input.readInt() }
        BaseSnapshot(world, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], palette, blocks)
    }

    fun saveBuild(build: ArenaBuild) = writeBuild(buildFile(build.arenaId), build)
    fun loadBuild(id: String): ArenaBuild? = readBuild(buildFile(id))

    fun startJournal(build: ArenaBuild) = writeBuild(journalFile(build.arenaId), build)
    fun endJournal(id: String) { journalFile(id).delete() }
    fun journals(): List<ArenaBuild> = active.listFiles { file -> file.name.endsWith(".dat") }.orEmpty().sortedBy { it.name }.mapNotNull(::readBuild)

    fun clear(id: String) { baseFile(id).delete(); buildFile(id).delete() }

    private fun writeBuild(file: File, build: ArenaBuild) = write(file) { out ->
        out.writeUTF(build.arenaId); out.writeUTF(build.world)
        val palette = (build.blocks.map { it.base } + build.blocks.map { it.built }).distinct()
        val lookup = palette.withIndex().associate { it.value to it.index }
        out.writeInt(palette.size); palette.forEach(out::writeUTF)
        out.writeInt(build.blocks.size)
        build.blocks.forEach { block ->
            out.writeInt(block.x); out.writeInt(block.y); out.writeInt(block.z)
            out.writeInt(lookup.getValue(block.base)); out.writeInt(lookup.getValue(block.built))
        }
    }

    private fun readBuild(file: File): ArenaBuild? = read(file) { input ->
        val arena = input.readUTF(); val world = input.readUTF()
        val palette = List(input.readInt()) { input.readUTF() }
        val blocks = List(input.readInt()) {
            BuildBlock(input.readInt(), input.readInt(), input.readInt(), palette[input.readInt()], palette[input.readInt()])
        }
        ArenaBuild(arena, world, blocks)
    }

    /** Writes to a temporary file first and moves it into place, so a crash never leaves half a file behind. */
    private fun write(file: File, body: (DataOutputStream) -> Unit) {
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(GZIPOutputStream(temp.outputStream().buffered())).use { out -> out.writeInt(FORMAT); body(out) }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun <T> read(file: File, body: (DataInputStream) -> T): T? {
        if (!file.isFile) return null
        return DataInputStream(GZIPInputStream(file.inputStream().buffered())).use { input ->
            check(input.readInt() == FORMAT) { "Unknown arena build format in ${file.name}" }
            body(input)
        }
    }

    private companion object { const val FORMAT = 1 }
}

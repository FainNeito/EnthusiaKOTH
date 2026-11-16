package net.badgersmc.ek.application

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One block an arena changes when it builds itself: what stands there normally and what the built form puts there. */
data class BuildBlock(val x: Int, val y: Int, val z: Int, val base: String, val built: String)

/** The blocks an arena adds when a KOTH starts, in the world they belong to. */
data class ArenaBuild(val arenaId: String, val world: String, val blocks: List<BuildBlock>) {
    companion object {
        /**
         * Changed blocks whose base form holds contents, such as a chest's items, a sign's text or a banner's pattern.
         * Only block states are recorded, so building over one and putting the base back would lose those contents.
         */
        fun blocksWithContents(blocks: List<BuildBlock>, holdsContents: (String) -> Boolean): List<BuildBlock> =
            blocks.filter { it.base.substringBefore('[') != it.built.substringBefore('[') && holdsContents(it.base) }

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

    fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ

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
 * Files under `arena-builds/`:
 * - `<arena>.base.dat`, the base snapshot.
 * - `layouts/<arena>/<layout>.dat`, each named design the arena can build, and `layouts/<arena>/state.properties`,
 *   which layout is active and how the arena rotates between them.
 * - `active/<arena>.dat`, a journal written before an arena builds itself and removed once it is back in its base form.
 *   A journal found at startup means the server stopped mid-event, and its base blocks are put back.
 *
 * A build recorded before layouts existed, `<arena>.build.dat`, becomes the arena's `default` layout.
 */
class ArenaBuildStore(private val folder: File, private val today: () -> LocalDate = { LocalDate.now() }) {
    private val active = File(folder, "active")

    private fun baseFile(id: String) = File(folder, "$id.base.dat")
    private fun legacyBuildFile(id: String) = File(folder, "$id.build.dat")
    private fun journalFile(id: String) = File(active, "$id.dat")
    private fun layoutDir(id: String) = File(File(folder, "layouts"), id)
    private fun layoutFile(id: String, name: String) = File(layoutDir(id), "$name.dat")
    private fun stateFile(id: String) = File(layoutDir(id), "state.properties")

    init {
        // Each single build from before layouts becomes that arena's default layout, active and on a fixed rotation.
        folder.listFiles { file -> file.name.endsWith(".build.dat") }.orEmpty().forEach { file ->
            val id = file.name.removeSuffix(".build.dat")
            if (layouts(id).isEmpty()) {
                layoutDir(id).mkdirs()
                java.nio.file.Files.move(file.toPath(), layoutFile(id, ArenaLayouts.DEFAULT).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                if (storedActive(id) == null) setActive(id, ArenaLayouts.DEFAULT)
            }
        }
    }

    fun hasBase(id: String) = baseFile(id).isFile
    fun hasBuild(id: String) = activeLayout(id) != null

    // Layouts --------------------------------------------------------------------------------------------------------

    fun layouts(id: String): List<String> = layoutDir(id).listFiles { file -> file.name.endsWith(".dat") }.orEmpty()
        .map { it.name.removeSuffix(".dat") }.filter(ArenaLayouts::validName).sorted()

    fun hasLayout(id: String, name: String) = ArenaLayouts.validName(name) && layoutFile(id, name).isFile

    fun loadLayout(id: String, name: String): ArenaBuild? = if (hasLayout(id, name)) readBuild(layoutFile(id, name)) else null

    /** Saves [build] as the layout [name]. The first layout an arena gets becomes its active one. */
    fun saveLayout(build: ArenaBuild, name: String) {
        require(ArenaLayouts.validName(name)) { "invalid layout name '$name'" }
        writeBuild(layoutFile(build.arenaId, name), build)
        if (storedActive(build.arenaId)?.takeIf { hasLayout(build.arenaId, it) } == null) setActive(build.arenaId, name)
    }

    fun renameLayout(id: String, from: String, to: String) {
        require(hasLayout(id, from) && ArenaLayouts.validName(to) && !hasLayout(id, to)) { "cannot rename '$from' to '$to'" }
        java.nio.file.Files.move(layoutFile(id, from).toPath(), layoutFile(id, to).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        if (storedActive(id) == from) setActive(id, to)
    }

    fun deleteLayout(id: String, name: String) { if (hasLayout(id, name)) layoutFile(id, name).delete() }

    fun rotation(id: String): LayoutRotation = LayoutRotation.parse(state(id).getProperty("rotation")) ?: LayoutRotation.FIXED
    fun setRotation(id: String, rotation: LayoutRotation) = updateState(id) { it.setProperty("rotation", rotation.id) }

    fun storedActive(id: String): String? = state(id).getProperty("active")?.takeIf(ArenaLayouts::validName)
    fun setActive(id: String, name: String) = updateState(id) { it.setProperty("active", name) }

    /** The layout the arena builds now: this week's on a weekly rotation, otherwise the stored choice. */
    fun activeLayout(id: String): String? {
        val names = layouts(id)
        if (names.isEmpty()) return null
        if (rotation(id) == LayoutRotation.WEEKLY) return ArenaLayouts.weekly(names, today())
        return storedActive(id)?.takeIf { it in names } ?: names.first()
    }

    /** Called as a KOTH starts there: an arena on a random rotation picks its next layout. */
    fun advanceForKoth(id: String, random: kotlin.random.Random = kotlin.random.Random.Default) {
        if (rotation(id) != LayoutRotation.RANDOM) return
        ArenaLayouts.random(layouts(id), storedActive(id), random)?.let { setActive(id, it) }
    }

    private fun state(id: String): java.util.Properties = java.util.Properties().also { props ->
        stateFile(id).takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use(props::load)
    }

    private fun updateState(id: String, change: (java.util.Properties) -> Unit) {
        val props = state(id).also(change)
        val file = stateFile(id)
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writer(Charsets.UTF_8).use { props.store(it, "KOTH layouts of arena '$id'") }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

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

    /** Saves [build] over the arena's active layout, or as its default layout when it has none yet. */
    fun saveBuild(build: ArenaBuild) = saveLayout(build, activeLayout(build.arenaId) ?: ArenaLayouts.DEFAULT)
    /** The active layout's build. */
    fun loadBuild(id: String): ArenaBuild? = activeLayout(id)?.let { loadLayout(id, it) }

    fun startJournal(build: ArenaBuild) = writeBuild(journalFile(build.arenaId), build)
    fun endJournal(id: String) { journalFile(id).delete() }
    fun journals(): List<ArenaBuild> = active.listFiles { file -> file.name.endsWith(".dat") }.orEmpty().sortedBy { it.name }.mapNotNull(::readBuild)

    /** Forgets the arena's base form and every one of its layouts. */
    fun clear(id: String) { baseFile(id).delete(); legacyBuildFile(id).delete(); layoutDir(id).deleteRecursively() }

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

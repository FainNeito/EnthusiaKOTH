package net.badgersmc.ek.domain

/** A presentation label; never an arena identifier or executable markup. */
object ArenaName {
    private val colors = Regex("(?i)(?:[&§]#[0-9a-f]{6}|[&§]x(?:[&§][0-9a-f]){6}|[&§][0-9a-fk-or])")
    private val graphemes = Regex("\\X")

    fun plain(raw: String): String = colors.replace(raw, "")
    fun legacy(raw: String): String = colors.replace(raw) { it.value.replace('&', '§') }

    fun parse(raw: String?): String? {
        if (raw == null) return null
        require(raw.length <= 1024 && raw.none { it.isISOControl() || it in "<>" }) {
            "Arena name must be single-line text without markup"
        }
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val visible = plain(value)
        require(visible.isNotBlank() && '§' !in visible && !Regex("(?i)&[#x]").containsMatchIn(visible)) {
            "Arena name contains incomplete color codes or no visible text"
        }
        val clusters = graphemes.findAll(visible).map { it.value }.toList()
        require(clusters.size <= 64 && clusters.all(::safeCluster)) {
            "Arena name must contain at most 64 visible characters without hidden controls"
        }
        return value
    }

    private fun safeCluster(cluster: String): Boolean {
        val points = cluster.codePoints().toArray()
        val emoji = points.any { it in 0x1F000..0x1FAFF || it in 0x2600..0x27BF }
        return points.withIndex().all { (index, point) ->
            when {
                point in 0xD800..0xDFFF -> false
                Character.getType(point) != Character.FORMAT.toInt() -> true
                point == 0x200D -> emoji && index > 0 && index < points.lastIndex
                point in 0xE0020..0xE007F -> points.first() == 0x1F3F4 && points.last() == 0xE007F &&
                    points.drop(1).dropLast(1).all { it in 0xE0020..0xE007E }
                else -> false
            }
        }
    }
    fun resolve(id: String, raw: String?): String = parse(raw) ?: id
}

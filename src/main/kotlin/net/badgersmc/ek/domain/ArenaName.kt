package net.badgersmc.ek.domain

/** Presentation only: persistent IDs never derive from names. */
object ArenaName {
    fun parse(raw: String?): String? {
        if (raw == null) return null
        require(raw.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "Arena name must be single-line visible text" }
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        require(value.length <= 64 && value.none { it in "<>§" } && !Regex("(?i)&[0-9a-fk-orx#]").containsMatchIn(value)) { "Arena name must be plain text, at most 64 characters" }
        return value
    }
    fun resolve(id: String, raw: String?): String = parse(raw) ?: id
}

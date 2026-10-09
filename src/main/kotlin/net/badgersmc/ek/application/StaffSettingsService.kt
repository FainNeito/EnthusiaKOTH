package net.badgersmc.ek.application

import java.time.LocalDate
import java.time.ZoneId

data class StaffSettingsSnapshot(val revision: String, val values: Map<String, Any?>)
class StaffSettingsDraft(val revision: String, val arenaId: String?, val values: MutableMap<String, Any?>) {
    val changes = linkedMapOf<String, Any?>()
    fun set(path: String, value: Any?) { values[path] = value; changes[path] = value }
}
interface StaffSettingsStore {
    fun read(): StaffSettingsSnapshot
    fun write(changes: Map<String, Any?>, revision: String)
}

class StaffSettingsService(private val store: StaffSettingsStore, private val busy: () -> Boolean) {
    fun begin(id: String?): StaffSettingsDraft {
        val snapshot = store.read()
        if (id != null && snapshot.values["arenas.$id.family"] == null) throw IllegalArgumentException("arena")
        return StaffSettingsDraft(snapshot.revision, id, snapshot.values.toMutableMap())
    }
    fun save(draft: StaffSettingsDraft) {
        if (busy()) throw IllegalStateException("busy")
        draft.changes.forEach { (path, value) -> validate(path, value) }
        store.write(draft.changes, draft.revision)
    }
    fun times(raw: String): List<String> = if (raw.trim() == "-") emptyList() else raw.split(',').map {
        val time = ScheduleService.parseScheduleTime(it) ?: throw IllegalArgumentException("times")
        "%02d:%02d".format(time.hour, time.minute)
    }.distinct().sorted().also { require(it.size <= 96) { "times" } }
    fun commands(raw: String): List<String> = if (raw.trim() == "-") emptyList() else raw.split(";").map { it.trim() }.also {
        require(it.size <= 45) { "commands" }; it.forEach(::command)
    }
    fun command(value: String) { require(value.isNotBlank() && value.length <= 512 && value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "commands" }
        if (value.startsWith("bank", true)) {
            val parts = value.split(Regex("\\s+")); val amount = parts.getOrNull(1)?.toDoubleOrNull()
            require(parts.size == 2 && amount != null && amount.isFinite() && amount > 0) { "money" }
        }
    }
    fun money(raw: String): Double = (raw.toDoubleOrNull() ?: throw IllegalArgumentException("money")).also {
        require(it.isFinite() && it >= 0 && it <= 1e12) { "money" }
    }
    fun validate(path: String, value: Any?) {
        when {
            path == "general.timezone" -> ZoneId.of(value as String)
            path == "leaderboards.season-start" -> if (value != "") LocalDate.parse(value as String)
            path == "events.max-concurrent" -> require(value is Int && value in 1..16)
            path == "display.bossbar-title" -> require(value is String && value.length <= 512 && value.none { it == '\n' || it == '\r' })
            path == "display.bossbar-color" -> require(value in listOf("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE"))
            path == "display.bossbar-overlay" -> require(value in listOf("PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20"))
            path in setOf("schedule.enabled", "display.zone-border", "display.bossbar", "display.actionbar", "display.hologram", "display.scoreboard") -> require(value is Boolean)
            path == "schedule.times" || Regex("arenas\\.[^.]+\\.schedule").matches(path) -> { require(value is List<*>); value.forEach { require(ScheduleService.parseScheduleTime(it as String) != null) } }
            Regex("arenas\\.[^.]+\\.rewards").matches(path) -> { require(value is List<*>); value.forEach { command(it as String) } }
            Regex("arenas\\.[^.]+\\.chanced-rewards").matches(path) -> {
                require(value is List<*>); value.forEach { val row = it as Map<*, *>; command(row["command"] as String); val chance = row["chance"] as Double; require(chance.isFinite() && chance in 0.0..100.0) }
            }
            Regex("arenas\\.[^.]+\\.reward-family").matches(path) -> require(value is String && Regex("editor_[a-f0-9_]+").matches(value))
            Regex("rewards\\.editor_[a-f0-9_]+\\.(solo|guild)-vault-money").matches(path) -> money(value.toString())
            else -> throw IllegalArgumentException("unsupported")
        }
    }
}

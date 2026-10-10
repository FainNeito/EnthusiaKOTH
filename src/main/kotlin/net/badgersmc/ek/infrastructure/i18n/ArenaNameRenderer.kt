package net.badgersmc.ek.infrastructure.i18n

import net.badgersmc.ek.domain.ArenaName
import net.badgersmc.ek.toComponent
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.UUID

private val arenaLegacy = LegacyComponentSerializer.builder().character('§').hexColors()
    .useUnusualXRepeatedCharacterHexFormat().build()

/** Neutral root confines name styling to its children, preserving surrounding template styles. */
fun String.arenaComponent(): Component = Component.empty().append(arenaLegacy.deserialize(ArenaName.legacy(this)))

fun String.arenaLegacyText(): String = arenaLegacy.serialize(arenaComponent())

fun String.withArenaName(name: String): Component = toComponent().replaceText {
    it.matchLiteral("{KOTH}").replacement(name.arenaComponent())
}

/** Nexus v2.1.1 stringifies placeholder values. Insert components after its normal template pass. */
fun LangService.arenaMsg(key: String, vararg values: Pair<String, Any?>): Component {
    val replacements = mutableListOf<Pair<String, Component>>()
    val placeholders = values.map { (name, value) ->
        if (value is Component) {
            val marker = "ekoth_${UUID.randomUUID()}"
            replacements += marker to value
            name to marker
        } else name to value
    }
    return replacements.fold(msg(key, *placeholders.toTypedArray())) { message, (marker, value) ->
        message.replaceText { it.matchLiteral(marker).replacement(value) }
    }
}

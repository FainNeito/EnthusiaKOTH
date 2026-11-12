package net.badgersmc.ek.infrastructure.i18n

import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * Registers `<error_prefix>`, the label on error lines, beside the built-in `<prefix>`.
 * It reads the `error-prefix` key on every use, so editing the lang file and reloading applies.
 */
fun LangService.registerKothTags() {
    registerGlobalResolver("error_prefix", TagResolver.resolver("error_prefix") { _, _ ->
        Tag.selfClosingInserting(MiniMessage.miniMessage().deserialize(raw("error-prefix")))
    })
}

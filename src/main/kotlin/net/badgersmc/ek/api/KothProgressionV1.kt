package net.badgersmc.ek.api

import java.util.UUID

/** Read-only challenge projection. Null means unavailable; values are 0..100. */
interface KothProgressionV1 {
    fun progress(player: UUID): Map<String, Int>?
}

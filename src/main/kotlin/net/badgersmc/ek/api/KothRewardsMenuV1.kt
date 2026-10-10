package net.badgersmc.ek.api

import java.util.UUID

/** Navigation only. Opens the online caller's own menu on the server thread; never grants rewards. */
interface KothRewardsMenuV1 {
    /** Accepted pages: home, challenges, claims, results. False means unavailable or denied. */
    fun open(player: UUID, page: String): Boolean
}

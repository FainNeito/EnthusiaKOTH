package net.badgersmc.ek.infrastructure.bukkit
/** Presentation only; action indices still identify provider-owned database records. */
internal object ProgressionLayout {
 val slots=listOf(19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43)
 fun <T> actions(all:Map<Int,T>,page:Int,count:Int):Map<Int,T> = (0 until minOf(21,(count-page*21).coerceAtLeast(0))).mapNotNull { offset -> all[page*21+offset]?.let { slots[offset] to it } }.toMap()
 fun pages(count:Int)=maxOf(1,(count+20)/21)
 fun page(requested:Int,count:Int)=requested.coerceIn(0,pages(count)-1)
}

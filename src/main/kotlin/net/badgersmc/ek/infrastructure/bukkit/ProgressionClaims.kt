package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.PlayerEconomy
import net.badgersmc.ek.application.ProgressionClaim
import net.badgersmc.ek.infrastructure.persistence.SqlProgressionStore
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus
import org.enthusia.tags.TagService
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Idempotent providers retry original IDs. Non-idempotent providers reserve REVIEW before dispatch. */
class ProgressionClaims(private val plugin: JavaPlugin, private val store: SqlProgressionStore, private val economy: PlayerEconomy) {
    init { store.recoverIdempotentClaims() }
    fun loreAvailable(): Boolean = runCatching { Bukkit.getServicesManager().load(LoreItemsServiceV1::class.java) != null }.getOrDefault(false)
    fun tagsAvailable(): Boolean = runCatching { Bukkit.getServicesManager().load(TagService::class.java) != null }.getOrDefault(false)
    fun redeem(player: UUID, claimId: String): String {
        val claim = store.claims(player).firstOrNull { it.id == claimId } ?: return "Claim unavailable."
        if (claim.status != "PENDING") return "Claim status: ${claim.status}."
        return when (claim.kind) {
            "MONEY" -> {
                if (Bukkit.getPlayer(player) == null) return "Reconnect to claim currency."
                if (!store.transition(claim.id,player,"PENDING","REVIEW","Reserved before external payout; do not automatically replay")) return "Claim already reserved."
                if (runCatching { economy.deposit(player,claim.amount / 100.0) }.getOrDefault(false)) {
                    store.transition(claim.id,player,"REVIEW","PAID"); "Currency paid."
                } else "Payout uncertain or rejected; staff reconciliation required."
            }
            "LORE" -> queueLore(claim)
            "TAG" -> queueTag(claim)
            "CHOICE" -> "Choose a package in /ekoth claims."
            else -> "Staff-managed claim."
        }
    }
    private fun queueLore(c: ProgressionClaim): String {
        val service = runCatching { Bukkit.getServicesManager().load(LoreItemsServiceV1::class.java) }.getOrNull() ?: return "LoreItems unavailable; claim retained."
        if (!store.transition(c.id,c.player,"PENDING","SENDING")) return "Claim already reserved."
        try {
            service.queueDelivery(c.value,c.player,c.id).toCompletableFuture().orTimeout(10,TimeUnit.SECONDS).whenComplete { result,error ->
                finish(c, error == null && result != null && result.externalOperationId() == c.id &&
                    result.status() in setOf(LoreDeliveryStatus.ACCEPTED_QUEUED,LoreDeliveryStatus.ALREADY_ACCEPTED),
                    "QUEUED", result?.detail() ?: "Provider timeout; retry original operation id")
            }
        } catch (error: Throwable) { finish(c,false,"QUEUED",error.message ?: "Provider failure") }
        return "Delivery requested; queue acceptance is not physical delivery."
    }
    private fun queueTag(c: ProgressionClaim): String {
        val service = runCatching { Bukkit.getServicesManager().load(TagService::class.java) }.getOrNull() ?: return "Tags unavailable; claim retained."
        if (!store.transition(c.id,c.player,"PENDING","SENDING")) return "Claim already reserved."
        try {
            service.grantTagPersisted(c.player,c.value).orTimeout(10,TimeUnit.SECONDS).whenComplete { accepted,error ->
                finish(c,error == null && accepted == true,"GRANTED","Tag grant must persist")
            }
        } catch(error:Throwable) { finish(c,false,"GRANTED",error.message ?: "Provider failure") }
        return "Tag grant requested."
    }
    private fun finish(c:ProgressionClaim,accepted:Boolean,status:String,detail:String) {
        if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin,Runnable {
            runCatching { store.transition(c.id,c.player,"SENDING",if(accepted) status else "PENDING",detail) }
                .onFailure { plugin.logger.severe("Claim ${c.id} persistence failed: ${it.message}") }
        })
    }
    fun dispatchGuildClaims() {
        store.pendingGuildClaims().forEach { c ->
            if(store.transition(c.id,c.player,"PENDING","REVIEW","Guild XP dispatch reserved; reconcile any uncertain result")) {
                if(runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(),c.value) }.getOrDefault(false))
                    store.transition(c.id,c.player,"REVIEW","DISPATCHED","Command accepted; XP amount requires companion verification")
            }
        }
    }
}

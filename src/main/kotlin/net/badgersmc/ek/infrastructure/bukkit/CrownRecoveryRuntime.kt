package net.badgersmc.ek.infrastructure.bukkit

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import net.badgersmc.ek.application.*
import net.badgersmc.ek.domain.*
import net.badgersmc.ek.infrastructure.persistence.*
import net.enthusia.loreitems.api.v1.CrownRecoveryEventsV1
import net.enthusia.loreitems.api.v1.CrownReclamationV1
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

/** Main-thread runtime adapter. Disabled until explicit policy, occurrences and provider settings exist. */
class CrownRecoveryRuntime(private val plugin:JavaPlugin,source:DataSource,private val events:SqlCrownEventStore):AutoCloseable {
    private val ledger=SqlCrownActivityStore(source).also{it.init()}
    private val bindings=SqlCrownRecoveryEvents(source).also{it.init()}
    private val holds=SqlCrownRecoveryHold(source).also{it.init()}
    private val busy=AtomicBoolean()
    @Volatile private var closed=false
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor { action -> Thread(action,"koth-crown-observer").apply{isDaemon=true} }
    private val task=plugin.server.scheduler.runTaskTimer(plugin,Runnable{tick()},100L,1200L)
    private fun settings()=plugin.config.getConfigurationSection("crown-recovery")
    private fun enabled()=!closed && plugin.isEnabled && settings()?.getBoolean("enabled")==true
    private fun provider()=try{plugin.server.servicesManager.load(CrownRecoveryEventsV1::class.java)}catch(_:NoClassDefFoundError){null}
    private fun main(action:()->Unit){if(!closed && plugin.isEnabled)plugin.server.scheduler.runTask(plugin,Runnable{if(!closed)action()})}
    private fun report(error:Throwable){plugin.logger.log(java.util.logging.Level.WARNING,"Crown recovery held for review",error)}
    private fun policy():CrownInactivityPolicy {
        val s=settings() ?: return CrownInactivityPolicy()
        val activity=s.getStringList("activity-occurrences");val recovery=s.getStringList("recovery-occurrences")
        require(activity.intersect(recovery.toSet()).isEmpty())
        require(plugin.config.getString("crown-quest.first-final-occurrence") !in activity+recovery)
        return CrownInactivityPolicy(s.getBoolean("enabled"),s.getString("revision").orEmpty(),
            s.getLong("inactivity-seconds").takeIf{it>0}?.let(Duration::ofSeconds),
            s.getInt("minimum-opportunities").takeIf{it>0}).also{it.validate()}
    }
    fun complete(match:UUID){if(enabled()) {bindings.finish(match,events);tick()}}
    fun cancelled(match:UUID){runCatching{bindings.cancel(match);bindings.retireEmpty()}.onFailure(::report)}
    fun retireEmptyBindings(){bindings.retireEmpty()}
    fun canStart(id:String):Boolean=events.read(id)?.purpose!=CrownEventPurpose.RECOVERY_CROWN_FINAL || bindings.active().any{it.occurrence==id}
    fun announce(id:String,arena:String,at:Instant,solo:Boolean,send:(String,List<Pair<String,String>>)->Unit) {
        if(!enabled() || !solo)return
        val s=settings()!!;val frozen=runCatching{capture()}.getOrElse{report(it);return};val p=frozen.policy
        val lead=s.getLong("minimum-announcement-seconds");if(lead<=0 || Instant.now().isAfter(at.minusSeconds(lead)))return
        fun announce(purpose:CrownEventPurpose,binding:RecoveryEventBinding?=null) {
            if(!enabled() || !runCatching{capture()==frozen}.getOrDefault(false) || Instant.now().isAfter(at.minusSeconds(lead)))return
            val slot=CrownOccurrence(id,arena,at,purpose,p.revision)
            if(events.plan(slot)==CrownOccurrenceWrite.CONFLICT || events.read(id)!=slot)return
            if(binding!=null && !bindings.assign(binding))return
            if(purpose==CrownEventPurpose.HOLDER_CHALLENGE) send("crown.announce-challenge",listOf("arena" to arena))
            else send("crown.announce-recovery",listOf("arena" to arena,"command" to "/ekoth crown accept-binding $id ${p.revision}"))
            bindings.announced(id,p.revision,Instant.now())
        }
        if(id in s.getStringList("activity-occurrences")){announce(CrownEventPurpose.HOLDER_CHALLENGE);return}
        if(id !in s.getStringList("recovery-occurrences"))return
        val requireQuest=s.get("require-quest") as? Boolean ?: return
        if(requireQuest && plugin.config.getConfigurationSection("crown-quest")?.getBoolean("enabled")!=true)return
        provider()?.current()?.whenComplete { status,error -> main {
            if(error!=null){report(error);return@main}
            val current=status?.orElse(null) ?: return@main
            if(current.deliveryEnabled() && current.held() && !current.transferPending() && current.recoveryOperation()!=null)announce(CrownEventPurpose.RECOVERY_CROWN_FINAL,
                RecoveryEventBinding(id,current.recoveryOperation(),CrownTenure(current.instance(),current.holder(),current.generation())))
        } }
    }
    private data class ObservationSettings(val policy:CrownInactivityPolicy,val lead:Long,val requiresQuest:Boolean?,
        val requirements:CrownQuestRequirements?,val questRevision:String,val recoveryOccurrences:List<String>,val activityOccurrences:List<String>)
    private fun capture():ObservationSettings {
        val p=policy();val s=settings()!!;val quest=plugin.config.getConfigurationSection("crown-quest")
        val requirements=quest?.takeIf{it.getBoolean("enabled")}?.let{CrownQuestRequirements(true,it.getInt("wins"),it.getLong("scoring-seconds"),it.getInt("distinct-days"))}
        val lead=s.getLong("minimum-announcement-seconds");require(lead>0)
        return ObservationSettings(p,lead,s.get("require-quest") as? Boolean,requirements,
            questRevision(quest?.getString("revision"),p.revision),s.getStringList("recovery-occurrences").toList(),s.getStringList("activity-occurrences").toList())
    }
    internal companion object {
        /** The shipped blank quest revision falls back to the recovery revision instead of failing every winner read. */
        fun questRevision(configured:String?,recovery:String):String=configured?.takeIf{it.isNotBlank()} ?: recovery
    }
    private fun dispatch(settings:ObservationSettings,action:()->Unit)=main {
        if(enabled() && runCatching{capture()==settings}.getOrDefault(false))action()
    }
    /** Set once the queue exists; evaluates evidence and runs the cancellation CAS under the queue lock. */
    @Volatile var missedOccurrenceGate:((CrownOccurrence)->Pair<MissedOccurrenceDecision,Boolean>)?=null
    private val heldMissed=mutableSetOf<String>()
    /** Never-enqueued special slots are cancelled only on MISSED; retireEmpty replays the binding release. */
    private fun reconcileMissed() {
        val gate=missedOccurrenceGate ?: return
        var cursor:String?=null
        do {
            val page=events.listOpenPage(cursor,200)
            page.occurrences.filter{it.state==CrownOccurrenceState.PLANNED &&
                (it.purpose==CrownEventPurpose.RECOVERY_CROWN_FINAL || it.purpose==CrownEventPurpose.HOLDER_CHALLENGE)}.forEach { slot ->
                val (decision,cancelled)=gate(slot)
                if(cancelled)plugin.logger.info("Crown occurrence ${slot.occurrenceId} was never queued; cancelled without award")
                else if(decision==MissedOccurrenceDecision.HOLD && heldMissed.add(slot.occurrenceId))
                    plugin.logger.warning("Crown occurrence ${slot.occurrenceId} is held for review: schedule/queue evidence is incomplete or inconsistent")
            }
            cursor=page.nextCursor
        } while(cursor!=null)
        bindings.retireEmpty()
    }
    fun tick() {
        if(!enabled())return
        runCatching{reconcileMissed()}.onFailure(::report)
        if(!busy.compareAndSet(false,true))return
        val native=provider();if(native==null){busy.set(false);return}
        try {
            val settings=capture()
            val reclamation=plugin.server.servicesManager.load(CrownReclamationV1::class.java)
            native.current().whenComplete{result,error ->
                if(error!=null){busy.set(false);report(error);return@whenComplete}
                try {worker.execute {
                    try {
                        if(closed)return@execute
                        val current=result?.orElse(null) ?: return@execute
                        val p=settings.policy;val now=Instant.now()
                        val tenure=CrownTenure(current.instance(),current.holder(),current.generation())
                        bindings.reconcileResults();bindings.observeSuccessor(tenure)
                        if(current.recoveryOperation()==null && !current.transferPending()) {
                            holds.observeSuccessor(tenure);ledger.observe(tenure,p.revision,now)
                            val before=ledger.read(tenure) ?: return@execute
                            val complete=bindings.observe(tenure,p.revision,before.observedFrom,now,settings.lead,ledger)
                            if(current.deliveryEnabled() && settings.requiresQuest!=null && settings.recoveryOccurrences.isNotEmpty() &&
                                CrownInactivityAssessment.assess(p,ledger.read(tenure)!!,now,complete)==CrownActivityDecision.INACTIVITY_REVIEW) {
                                val old=holds.read()
                                val request=if(old==null)RecoveryRequest(UUID.nameUUIDFromBytes("crown-inactivity:${tenure.instance}:${tenure.generation}:${p.revision}".toByteArray()),
                                    tenure.instance,tenure.holder,tenure.generation,now)
                                    else RecoveryRequest(old.operationId,old.instanceId,old.holderId,old.generation,old.requestedAt)
                                if(request.instanceId==tenure.instance && request.holderId==tenure.holder && request.generation==tenure.generation &&
                                    holds.reserve(request)!=RecoveryReservation.CONFLICT) dispatch(settings) {
                                    reclamation?.reserve(request.operationId,tenure.instance,tenure.holder,tenure.generation)
                                        ?.whenComplete{_,failure->if(failure!=null)report(failure)}
                                }
                            }
                        }
                        bindings.retireEmpty()
                        bindings.active().forEach { binding ->
                            val winner=bindings.winner(binding,p.revision,settings.requirements,settings.requiresQuest,settings.lead,settings.questRevision)
                                ?: return@forEach
                            dispatch(settings) {
                                native.deliver(binding.recovery,binding.tenure.instance,binding.tenure.holder,binding.tenure.generation,winner.match,winner.winner)
                                    .whenComplete{response,failure->if(failure!=null)report(failure) else if(response==CrownRecoveryEventsV1.Result.CONFLICT)
                                        plugin.logger.warning("Recovery Crown event ${binding.occurrence} has a custody conflict")}
                            }
                        }
                    }catch(failure:Exception){report(failure)}finally{busy.set(false)}
                }}catch(failure:Exception){busy.set(false);if(!closed)report(failure)}
            }
        }catch(failure:Exception){busy.set(false);report(failure)}
    }
    override fun close(){closed=true;task.cancel();worker.shutdownNow()}
}

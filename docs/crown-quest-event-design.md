# Crown quest and special KOTH: proposed design

Status: design plus unwired prototypes, not enabled. Base: KOTH main
`8e6e47400ccbde0868a276bfcdff25a34aebf251`. All examples and time periods
below are candidates for TEST, not production settings. The existing
`sovereign_crown` progression rule is not replaced or activated by this file.

## Intent and player experience

One physical Crown of Sorrow is first earned by the first qualifying Solo KOTH
player, then can pass through eligible player-versus-player combat. If it is
recovered because its holder has been inactive, the same Crown becomes the
prize in a special public Solo KOTH. No second physical Crown may be minted as
an automatic fallback. The user approved this exception to the earlier
PvP-only transfer plan on 2026-11-04; implementation and activation still
require proof of safe custody and recovery.

The player's `/rewards` view should show progress, event eligibility and a
clear explanation that the first successful award immediately equips the Crown
and consumes the existing helmet. Before entering the final stage, require an
explicit acknowledgement of that irreversible consequence. Acknowledgement
does not itself mint or equip anything. The eventual qualifying final victory,
not a later safe-zone claim, triggers the guarded exchange.

## Quest proposal

Progress uses independently configurable targets and is disabled until TEST.
Only accepted, durable, public Solo KOTH results in approved arenas may count;
private/admin events, Guild KOTH, arbitrary commands, external advancement
grants and cancelled/no-winner events never award progress. Backfilling old
events is disabled unless explicitly designed and accepted. Default threshold
values remain unset pending calibration.

- Win eligible Solo KOTHs on multiple distinct UTC days.
- Accumulate active scoring time across multiple eligible events.
- Defeat eligible opposing players in the event area, with distinct opponent
  and repeat-pair limits. Opponent identities do not prove independent humans.
- After the preceding requirements, opt into a scheduled Crown final. Announce
  the upcoming final and its eligible contenders in Discord and globally in
  game with advance notice; do not reveal private base coordinates.
- The first eligible winner of a contested final is the first-award candidate.
  Other players can contest the event but cannot receive the Crown without
  satisfying the quest. A noneligible winner, cancellation, no winner, failed
  contest or technical ambiguity ends that occurrence without an award.

Counts and eligibility are frozen from authoritative event records, not
client-provided state. Event ID plus player ID forms an idempotency key;
duplicate callbacks, restart, admin replay, out-of-order delivery, arena rename,
or cancelling an event cannot duplicate credit. A credit record must retain the
matching rule revision and event evidence for staff review. If eligibility or
result evidence is unavailable or contradictory, hold the award for review;
do not convert uncertainty into success. A loss need not reset long-term
progress; the earlier proposed three-consecutive-win gate is not adopted.

## Special Crown KOTH and activity

The special event is an explicitly marked **public SOLO** scheduled occurrence,
distinct from ordinary scheduled KOTH and from its rewards. Ordinary KOTH
continues using the current MaceGuard warzone combat rotation. Schedule special
events at different hours across time zones, publicly preview future dates,
handle DST gaps/overlaps explicitly, and use durable occurrence IDs and claims
so a restart or manual retry cannot create two copies of an event. Do not treat
the existing daily HH:mm scheduler as proof that weekly rotation, Crown event
types or recovery are already implemented.

Proposed retention rule for review: the wearer meaningfully participates in
one eligible special event in a rolling 30-day window. Participation means
actual, non-AFK involvement while the event is active; mere entry, respawn,
teleport, idling or an uncontested alt is insufficient. Multiple scheduled
opportunities must be announced at varying times; configurable reminders begin
well before the deadline. Cancelled, inaccessible, failed or suppressed events
must not count as an opportunity. If too few eligible opportunities occurred,
extend or suspend the deadline; do not punish missing one event. How many
opportunities, participation evidence, outage handling, deadline extension and
notifications are unresolved TEST decisions, not runtime defaults.

An overdue holder moves to a review/recovery hold: never declare the Crown
unowned merely because the owner is offline, their inventory is unavailable,
or a query times out. A provider-supported fenced custody exchange must prove
the old Crown cannot remain or later reappear before the same logical Crown is
offered in a recovery event. If it cannot prove that, leave the Crown held and
show staff a reconciliation issue. Never recreate it from the quest pool or
remove the player's helmet on an uncertain result. If no one wins a recovery
event, retain custody and schedule another; do not issue a replacement.

## Anti-abuse, safety and player communication

Opposition, account age, playtime, repeat-pair and other configurable numerical
anti-abuse thresholds start disabled until TEST per user direction. Disabled
thresholds must not be interpreted as evidence of genuine competition or as
production readiness: reward/Crown activation remains fail-closed until an
accepted protection policy is enabled. Merely requiring another account or
presence on the hill is not reliable proof of adversarial play. Preserve review
and audit records, cap announcement spam, and resolve ties deterministically
using committed event order, with an atomic global first-award reservation.

Do not publish exact or approximate player/base locations, expose offline NPCs,
or add a guild XP multiplier as a hidden side effect. These were discussion
ideas, not approved mechanics. Reveal only player identity, event time and the
public arena as needed for an announced final. Ordinary PvE death retains the
Crown; PvP transfer, killer attribution, binding, helmet replacement, reconnect
grace, combat logging and provider recovery each require their own verified
contract before activation. This design does not implement those capabilities.

## SPEAR: requirements and verification plan

- CROWN-Q01: Only durable qualifying public Solo results credit a challenge,
  at most once per player per event, and preserve auditable evidence.
- CROWN-Q02: The final requires explicit warning acknowledgement, announced
  event identity and a contested eligible win; no other path issues a Crown.
- CROWN-E01: Event occurrences survive restart without duplicates and retain
  their original arena, mode, event type, timestamp and policy revision.
- CROWN-E02: A rolling activity deadline uses genuine eligible opportunities,
  handles outages and never treats mere presence as demonstrated opposition.
- CROWN-R01: One physical Crown exists at most once. Issuance, PvP exchange,
  offline recovery and any helmet consumption need fenced, durable provider and
  native evidence; ambiguous operations retain a hold until reconciliation.
- CROWN-N01: Final announcements are global in game and configurable for Discord;
  no position information beyond a configured public arena is exposed.

Prove: start with deterministic tests for duplicate/out-of-order events,
non-solo/private/no-winner exclusions, simultaneous finalists, contested versus
staged opposition, daily scheduling/DST, missed/cancelled events and outage
windows. Include crash/restart at every reservation, publication, custody,
acknowledgement and finalization boundary; independent provider/native proofs
must demonstrate sole-original custody, definite nonissuance and fencing.
Engine/architecture: retain domain policy in application/domain and persistence,
Paper, Discord, MaceGuard and companion integrations in adapters. Refine:
exercise `/rewards` and all holder/contender states in responsive preview and
controlled TEST with consenting accounts; verify Java and Bedrock rendering,
separate TEST economy and exact provider artifacts. EARS/state helper tooling
was not found in the canonical main tree; this requirement/evidence record is a
manual substitute, not a claim that any automated helper or gameplay test ran.

## Open product decisions

1. How the same recovered Crown is safely held and transferred without a
   second physical issuance, including an offline original and server crashes.
2. Quest metrics and values, announcement lead time, eligibility for recovery
   events, and how contenders with equal committed results are ordered.
3. Rotating schedule and what counts as a fair number of opportunities;
   whether the 30-day example, warnings and outage grace are acceptable.
4. Participation and anti-collusion evidence and enforcement, including how
   allied groups, repeat pairs and low-population periods are treated.
5. Precise attribution, custody and no-duplicate semantics for PvP, logout,
   destruction, admin action, server outages and an offline overdue holder.
6. Exact protected companion API versions and provider/native ownership for
   physical issuance, durable query, fencing and original-helmet finalization.

## Isolated policy prototype, 2026-11-04

`CrownEventPolicy` classifies public solo match evidence as rejected, held,
quest progress or a first/recovery-final candidate. It requires verified
durable results, an accepted protection policy, verified opposition and, for
either final, a consequence acknowledgement. This pure result is not a payout,
an inventory mutation, a persistence guarantee or evidence that opposition was
genuine. `CrownRecoveryPolicy` holds expired/inactive cases unless fenced
custody and proof that the original cannot reappear are both positively
established. The policy has no listeners, configuration, scheduler, database,
webhooks, provider adapters or live integration; no threshold is enabled.

Focused isolated verification: Gradle 8.10.2, project JDK 21, offline
`test --tests net.badgersmc.ek.domain.CrownEventPolicyTest --no-daemon` passed
five tests for eligibility exclusions, uncertainty, first versus recovery
candidate, and fenced custody. Initial Java 25 and Java 22 invocations failed
because the required JDK 21 was not selected. A first JDK 21 run compiled while
the test sources were being updated and failed on an outdated compiled type;
the subsequent cleanly sequenced run passed. These tests do not establish
durable progress, atomic reservations, fairness, payment, native inventory
behavior, provider contracts, scheduling, UI, restart recovery or in-game
acceptance. EARS/state helpers remain absent.

## Rotating schedule and activity prototype

`CrownSchedule` previews a configurable weekly weekday with a repeating list of
local times and a stable anchor. No default times or active scheduler were added.
It skips nonexistent local times during the spring DST gap and uses the earlier
offset exactly once during a fall overlap. Preview intervals are half-open and
bounded to 370 days. These previews do not provide persistent occurrence IDs,
durable queueing, server event starts or recovery.

`CrownActivityWindow` classifies evidence as participation, an open window,
insufficient opportunities, inactivity review or an uncertainty hold. It
deduplicates identical occurrence IDs, holds conflicting records and incomplete
or unknown evidence, and excludes cancelled/ineligible events. Only verified
eligible participation can satisfy the window. Its output cannot remove a
Crown: a separate provider custody/fencing protocol remains mandatory. Exact
event rules, anti-abuse thresholds, required opportunity counts, event times
and deadlines are not configured or enabled.

Offline focused Gradle tests on the JDK 21 project toolchain passed all three
prototype suites: event eligibility, weekly rotation and DST, and activity
assessment. This is isolated unit evidence only; no scheduling persistence,
notifications, TEST/client checks, provider contract or gameplay integration
is established.

## Persistent event and recovery boundary prototype

`SqlCrownEventStore` stores immutable special-event metadata, with an atomic
planned-to-started compare-and-set and a unique match ID. Completion and
cancellation require the same started match and are terminal. Exact schedule
replays are idempotent; conflicting metadata cannot overwrite a slot. It is
not connected to the production scheduler or queue and does not itself start an
event or prove the outcome. Durable queue handoff, reclaim policy and atomic
progress attribution remain outstanding.

`SqlCrownRecoveryHold` durably reserves one singleton Crown recovery operation.
The isolated recovery hold now binds the operation to the Crown instance,
holder and nonnegative custody generation. Changed identities or generations
conflict on retry; an older table missing those fields is refused and left for
explicit reconciliation. This is an experimental schema, not a production
migration or proof of physical custody.

The unwired `CrownRecoveryCoordinator` commits that hold before consulting its
capability adapter. `LegacyLoreCrownCustody` reports LoreItems V1 unsupported:
it deliberately calls neither `queueDelivery` nor a definition query and has
no release or transfer operation. A provider error remains held. These are
conservative integration boundaries, not proof of item custody, provider
fencing, live compatibility, or a functional re-award. The existing legacy
progression claim route remains present and must be retired/gated and
reconciled before any Crown rollout. No data migration, inventory exchange,
native player mutation, TEST use, threshold activation or provider write was
performed.

Focused offline tests on JDK 21 passed the Crown domain, SQLite persistence,
and legacy-provider boundary suites. They exercise restart, duplicate and
conflicting schedule requests, simultaneous starts/recovery holds, terminal
states, provider errors, and confirmation that V1 delivery is never called.
They do not prove durable handoff across the running scheduler, actual provider
capabilities, crash-safe helmet consumption, cross-server uniqueness or client
acceptance. Those remain release blockers.

## Quest credit ledger prototype

`SqlCrownQuestCredits` accepts only an event ID and explicitly enabled,
revisioned rules with a cutover instant. It reads the existing KOTH match,
protected reward audit, decision snapshot and eligible winner contribution in
one write-locked SQLite transaction. It accepts only an approved, protected,
contested public Solo winner with matching stored evidence, an accepted arena,
and enabled contest/readiness/hold policy. Defaults do not enable this path or
set anti-abuse thresholds. The event ID is unique; exact retries are idempotent,
while changed rule revisions conflict rather than silently rewriting history.
Totals are scoped to the requested rule revision and exclude records whose
underlying audit or decision is no longer approved. Revision migration and
backfill are intentionally not implemented. The ledger cannot issue a reward.

Offline focused tests on JDK 21 passed 25 Crown tests plus 12 existing
progression persistence tests. They cover concurrency, restart, rejected or
held results, unprotected rules, lack of contest, UTC cutover, revoked results,
replay and revision conflict. This is not evidence of active contest detection,
live fairness against colluding players, persistent scheduler handoff, a
provider fence, `/rewards` integration, physical custody, or TEST acceptance.
No production or TEST state was changed, and the new ledger remains unwired.

## Bounded replay and refreshed provider API inspection

`SqlCrownQuestCredits.reconcilePage` scans approved protected Solo records in
stable `(completion timestamp,event UUID)` order, with a bounded page size and
an optional cursor. Replays call the existing idempotent eligibility check; they
never issue a Crown. A full later rescan is required to catch a previously held
match approved behind an old cursor. A different rule revision is surfaced as
a conflict rather than silently relabeling old credit. No background worker or
completion listener invokes this method yet; a production integration must
persist appropriate policy revisions, provide bounded retry/backoff and avoid
running database work on the server thread.

On 2026-11-04, fresh remote readback of LoreItems canonical `origin/main`
`86999c032eaf658d6e66223d73aedf58cbd6cee2` showed only the public V1 service
interface under the relevant API path. Fork `main`
`d9e15ae6aca0d1383848e6bd82f6d233ea828ecb` also exposes V1 queue delivery and
no public Crown custody/fencing service in that API tree. This is source
inspection, not installed-artifact or native custody proof. V1 queue acceptance
still cannot establish physical equipment, exclusive custody or safe recovery.
At the time of that inspection, the legacy Crown claim path was ungated. No
provider repository worktree, TEST or runtime state was changed during this
check.

Offline JDK 21 checks passed 27 Crown tests and 12 existing progression
persistence tests after the replay addition, including tied timestamps,
restart, a held result approved behind a previous cursor, and revision
conflicts. A complete cursor cycle must be repeated after a null `next`; it is
not an indefinitely advancing watermark. No deployed integration or provider
test is implied.

## Legacy issuer guard

The candidate now prevents the historical `sovereign_crown` challenge route
from reserving an exclusive physical owner or creating a physical relic claim;
its tag path remains unchanged. Existing claims whose IDs match
`challenge:sovereign_crown:*:relic` are held before V1 provider dispatch or
claim transition. This narrowly blocks the known legacy route only. Arbitrary
item commands, alternate configured definitions, pre-existing provider queues,
historical ownership rows and other issuance paths have not been reconciled;
the guard is not proof of global uniqueness. No schema, provider or production
data were migrated and no Crown issuance path was enabled.

Focused JDK 21 tests passed after adding replay, legacy-claim classification,
existing-claim dispatch blocking and progression preservation. This remains
unmerged source; the replay, scheduler, `/rewards`, special-event and safe
physical recovery flows are not wired into the live plugin.

The full project-local offline JDK 21 `gradlew test --no-daemon` run passed:
414 tests, zero failures/errors, two skipped. The run does not include real
provider API contract tasks, server/client checks or physical item recovery.

## Restart reconciliation page

The event journal can now return bounded pages of planned and started
occurrences in immutable ID order, so a future restart worker can discover
unfinished events without loading the entire journal. Terminal events are
excluded. A cursor is only a position in one scan, not proof that earlier IDs
cannot appear later; reconciliation must repeat complete scans and safely
recheck current state before any action. This method does not schedule,
resume, complete, or award anything, and is not connected to a worker.
The focused `CrownPersistenceTest` suite passed offline on JDK 21, including
restart, pagination, terminal filtering and a newly added earlier ID.

The user selected LoreItems as the Crown provider. Its currently inspected V1
queue remains insufficient for custody, physical equipment or recovery. The
provider repository has active, unresolved physical tracking and reconciliation
work; the journal above is not a substitute for that evidence. No provider
capability, native inventory exchange, TEST deployment or runtime activation is
claimed by this change.

## Final eligibility prototype

`CrownFinalEligibilityPolicy` combines the existing event evidence policy and
quest thresholds into a candidate decision. It is disabled by default, requires
positive explicitly supplied thresholds, and holds recovery finals until the
question of whether the quest applies to recovery is configured. This is a pure
unwired calculation: before use, a caller must atomically establish that the
persisted occurrence, result and revisioned quest totals all refer to the same
winner and current approved evidence. Neither a candidate nor its input values
authorize custody, delivery, or helmet consumption. Thresholds remain unset for
the server pending TEST. Focused `CrownFinalEligibilityTest` passed on JDK 21.

The candidate policy also exposes a matching check for occurrence match ID,
result match ID, winning player, progress owner and required quest revision.
These identifiers remain caller-supplied; the policy is not connected to a
transactional reader, event scheduler, or provider, and does not establish that
the supplied identifiers or the evidence are trustworthy. Any mismatch holds.

### Durable final observation

`SqlCrownFinalObservation` reads a completed special occurrence, its matching
approved public Solo result, saved snapshot and that winner's quest totals in
one database transaction. It refuses missing, mismatched, rejected or held
records and counts only credits before the scheduled final time. Revoked
credits disappear through the existing status joins. The result is an
observation, not proof of actual opposition, final acknowledgement, provider
custody or entitlement; it is not wired to issue items or start events. The
scheduled-time boundary is conservative for delayed starts. A runtime caller
must run database work off the server thread and revalidate prior to any future
action.

The final candidate screen also rejects non-scheduled match origins and match
timestamps before the occurrence's scheduled instant. This is not proof of
advance announcement or of the actual event start time; those remain separate
runtime acceptance requirements.

Final observations additionally recheck the saved protected-match policy,
contest evidence, opposing side, relation stability and winning contribution
against the recorded player row. This rejects an approved label when those
saved facts fail validation. It cannot independently establish that the
underlying players were not colluding, and it does not authorize an award.

`SqlCrownFinalPreflight` composes that read-only observation and configured
quest thresholds with the existing provider capability check. It stops on
disabled configuration, missing evidence, incomplete quest or undefined
recovery policy, and reports the V1 provider as unsupported. It cannot return
a success or authorize any physical action; it is not scheduled or wired to a
command. A future provider must establish final-result integrity, exclusive
custody, durable helmet exchange and recovery before this boundary can advance.

## First Crown award and provider admission

KOTH now has a durable first-award intent bound to a completed scheduled solo final, a protected winning match, qualifying prior quest progress, and the quest revision. The final completion method requires the expected match ID. The singleton transaction rolls back on rejection and checks historical Crown owners/claims. A repeated eligible callback returns the same match as the stable operation ID. Progress or match revocation prevents a fresh dispatch decision. This intent alone cannot create or equip an item.

LoreItems now checks the existing Crown definition and instance, provider conflicts and the holder's confirmed player-inventory location within the transaction that reserves custody and records the old helmet. A conflicting candidate leaves no reservation, audit or helmet evidence. Physical identity is rechecked by the Paper exchange path. This is admission for an existing item only.

Full local Gradle suites and shadow JARs built. KOTH 433 passed / 2 skipped; LoreItems 602 passed / 9 skipped. KOTH regression exercised racing finals, replay from a new store, legacy ownership, wrong match, incomplete or revoked credit. LoreItems regression exercised absent registration, a different holder, conflicting operations, clean staging, timeouts and changed source metadata. No Paper end-to-end award was run for this change.

Still needed: the normal KOTH match/scheduling path must call the dedicated final handler, bind the final acknowledgment, and deliver its request to LoreItems. LoreItems must support safe initial issuance without requiring the winner to already possess the Crown. Rehearse those paths together with retry and restart before shared TEST. All edits are isolated and unmerged. Entity initialization failed; shared resources were not used.
The normal protected match completion now records ordinary eligible quest progress and routes a match already bound to a first final to the singleton award transaction. The winner must have accepted that specific event and policy revision through /koth crown accept-helmet-loss before it started. The consent check applies to both initial preparation and retries. Configuration is disabled pending TEST calibration.

Local full KOTH suite: 433 passed, 2 skipped; shadowJar built. The regression includes another player's consent, wrong revision, late consent, two racing finals and replay. See protected-completion.log. No project-local EARS/state helper found. Entity initialization returned URLError. Only isolated work was performed.

Remaining: publish and bind planned finals through the schedule/activation flow, pass the award intent to a supported LoreItems initial issuance API, and verify real first issuance plus crash/rejoin on isolated Paper before shared TEST. No provider or shared operations performed.
The scheduler can designate a specific scheduled solo occurrence for the first final using crown-quest.first-final-occurrence. Staff can read today's occurrence IDs in /ekoth schedule. The normal advance warning plans the final and sends its helmet-loss consent command. A missed warning leaves the slot ordinary. Quest remains disabled by default.

Queue activation binds arena, scheduled instant, solo mode and durable match UUID before starting. A restart with the same activation UUID may resume the binding. Transient storage failures retain the UUID for retry. A conflicting or terminal queue entry is removed to allow later matches through. SQLite reopen, competing identity, terminal replay and real queue code were tested locally. See scheduled-final.log for the Gradle test and shadowJar run. No live Paper first-award scenario ran.

LoreItems initial issuance, physical swap after that issuance, and interrupted end-to-end recovery still require integration and verification. Entity initialize returned URLError before ownership could be read. Changes are local; shared TEST and clients were not used.

Native reclamation bridge: CrownRecoveryCoordinator.requestReclamation requires established inactivity, opportunity and expiry evidence before the local hold. NativeLoreCrownCustody invokes the exact LoreItems reservation identity. Successful/replayed reservations return HELD_RESERVED; event-ready physical custody remains unsupported. Scheduled inactivity observation and activation are pending. KOTH 439 tests total, 2 skipped, zero failures/errors; shadowJar passed. Provider registered-service evidence: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/recovery-matrix-202702/RESULTS.md. No shared TEST or deployment.

Provider update: CrownReclamationV1 reservation may initiate guarded online removal. Its response remains reservation-only; the KOTH coordinator continues to return HELD_RESERVED. Provider V17 physical reclamation/restart evidence: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/reclamation-physical-213328/RESULTS.md. KOTH still has no event-ready custody receipt or recovery delivery path.

## Durable inactivity assessment (2026-11-05)

Requirement: an activity window SHALL begin at the first durable observation of an exact Crown instance/holder/generation; restarts and logins SHALL NOT renew it. Independently verified meaningful special-event participation SHALL renew the window. Repeated event callbacks SHALL NOT inflate opportunity counts. Conflicting evidence, time regression, policy/holder mismatch and incomplete coverage SHALL hold for review. Thresholds SHALL remain disabled/unset until configured.

CrownInactivityPolicy and SqlCrownActivityStore implement this inert evidence boundary. SQLite persists the tenure, policy revision, observation interval and immutable per-occurrence evidence. CrownInactivityAssessment evaluates renewal and expiry using CrownActivityWindow. Four integration tests verify restart persistence, deduplication, meaningful participation, rejected wrong-holder writes, conflicting evidence, policy changes, clock regression and missing coverage.

Build: 443 tests total, 2 skipped, zero failures/errors; shadowJar passed. Local unmerged KOTH JAR SHA256 17AC045083F3ACA4BC04FFDEAA50CB4082EAD0952196DDFDA1F3456FCD6D7E3B. The ledger is not wired to an automatic scheduler or player commands. Inputs still require the protected event/announcement/participation adapter; a caller-supplied evidence row is not authoritative eligibility. No automatic confiscation is enabled.

Provider progress: opt-in offline removal and login fencing passed local Paper restart/failure scenarios. An escrow HELD state still does not authorize a recovery final: KOTH needs a generation-bound provider status/delivery contract, one durable event-to-custody assignment, protected result verification and a single-recipient grant with restart recovery. Missing winner/cancelled events must leave the same original held. These remain implementation tasks, not verified behavior. Evidence: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/OFFLINE-AND-ACTIVITY-RESULTS.md.

Source review/failure closure: strict config-key acceptance fixed; final-artifact online reclamation and connected PvP fallback regression passed. Durable activity evidence remains inert pending protected automatic observation wiring. Source review is local/self-review, not independent PR approval. Review delivery and full failure matrix remain pending.

## Automatic observations and recovery-event delivery (2026-11-05 continuation)

Spec: announced, completed, protected solo holder challenges SHALL be the source of inactivity opportunities and participation. Missing, conflicting or unapproved evidence SHALL hold. Grace SHALL continue until sufficient opportunities exist. A recovery final SHALL bind one exact HELD original and generation to one protected winner with pre-start binding consent. A normal restart SHALL resume an interrupted recipient grant without issuing a second original. Recovery recipients retain their existing helmet; initial sacrifice consent remains separate.

Engine/architecture: CrownRecoveryRuntime now collects protected observations on a dedicated worker and rechecks a frozen configuration before native actions. SqlCrownRecoveryEvents persists announcement, occurrence, match and winner binding; cancelled/no-winner events release only their unawarded binding. LoreItems CrownRecoveryEventsV1 and V18 persist the native single-recipient grant, reusing the existing saved-inventory exchange/recovery path. No mint fallback is used. New configurations remain disabled/unset.

Review: fixed nested connection borrowing, worker/main-thread separation, late-opportunity grace, old-generation local hold retirement, configuration-change fencing, and former-holder login fencing while an incoming recovery grant is pending. Self-review only; independent PR review remains outstanding.

Proof: KOTH 448 tests total, 2 skipped, zero failures/errors; LoreItems 623 total, 9 skipped, zero failures/errors. Both shadow JAR builds passed. Native Paper recovery delivered the existing generation-2 original back to its former holder as generation 3 while preserving their diamond helmet. An injected custody-commit failure left one pending recipient operation; after removing the fixture fault and restarting, normal join recovery committed that same operation and announced ownership once. This was fault injection plus normal restart, not a hard-kill proof. Automatic tenure observation was persisted by the running KOTH plugin. Full scheduled recovery rehearsal results are recorded separately in RECOVERY-EVENT-RESULTS.md.

Limits: the complete automatic inactivity-to-offline-removal-to-event cycle still needs a combined rehearsal, including cancelled/missed schedules, disabled/reloaded settings, shutdown races, recipient disconnect/full inventory and hard interruption points. Existing targeted checks are not full matrix closure. Current-main reconciliation, independent review/CI and coordinated TEST/client acceptance remain. No shared TEST/client or production operations occurred. Local Entity remains unavailable (DriverError); no task versions or ownership acknowledgments were invented. Project-local EARS/state helper unavailable; this is the bounded SPEAR requirement/implementation/evidence record.

Scheduled runtime result: protected solo recovery final delivered the same original, generation 2 -> 3, preserving the recipient helmet. Final-artifact restart kept the same custody/grant, retired its event binding and produced zero duplicate announcements. Full bounded evidence: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/RECOVERY-EVENT-RESULTS.md. Combined automatic expiry/removal/event cycle and integrated failure matrix remain before TEST readiness.

2026-11-06: durable invalid-queue discard marker and idempotent exact Crown occurrence cancellation are implemented locally; queue marker persists before cancellation and is compacted only after acknowledgment. Full KOTH test 458 total, 2 skipped, zero failures/errors. See C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/RECOVERY-EVENT-RESULTS.md. Paper interruption, old-version rollback compatibility, never-enqueued missed occurrences, source reconciliation and independent review remain pending. Defaults remain disabled.

Rollback safety: the current main-branch queue reader does not recognize DISCARDED and drops unknown rows. Do not downgrade to that version with outstanding discard markers; keep the new version available to reconcile them, preserve backups and verify queue and event/binding state first. A tested recovery procedure for markers that cannot be drained remains a TEST prerequisite. See RECOVERY-EVENT-RESULTS.md for source evidence.

## Never-enqueued recovery occurrence: evidence gate (2026-11-06)

Requirement: a missed recovery occurrence SHALL remain bound until the scheduler can prove it is neither queued nor recoverably claimed, and an atomic PLANNED-to-CANCELLED transition prevents a delayed start. A cutoff time alone SHALL NOT be sufficient. Unknown, unreadable, malformed, partially persisted, or inconsistent queue/schedule evidence SHALL hold.

Source finding: FileOperationalStateStore.load() returns an empty queue on read failure and silently skips malformed rows; ensureScheduleLoaded() logs read failure and loadClaim() silently ignores malformed claims. Therefore an empty in-memory queue or null start claim is not currently proof of absence. ScheduleService uses a 10-minute catch-up interval, but setLastEvaluation may update memory without persisting and also runs while the schedule is disabled. These are insufficient as an authoritative missed-event receipt.

Implementation boundary: before automatic retirement, add a verified schedule/queue health and durable watermark contract covering missing and malformed files, failed/partial atomic writes, unflushed cursor, disabled scheduler, clock regression and restart; perform the cancellation CAS only with a stable scheduler/queue snapshot and preserve a start claim/queue entry if either exists. Replay any cancellation-to-binding-retirement gap. A delayed or resumed activation must win over or be rejected by the same durable occurrence CAS. Do not change unrelated queue semantics or interpret failed reads as clean state.

Verification required: reproduce each I/O ambiguity, older-format compatibility, pending/committed/removed claims, delayed queue activation and both sides of cancellation/activation race; then isolated Paper hard-interruption across evidence publication, cancellation and retirement, plus full source review and CI. No automatic cancellation was enabled based on this analysis.

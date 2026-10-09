# KOTH reward anti-abuse (SPEAR)

User approved October 8: alliance-aware opposition, meaningful hill activity,
locked match identities, a fixed event reward budget, abuse auditing and
account-age/playtime/repeated-opponent controls. Combat follows MaceGuard's
warzone rotation; no independent KOTH combat rotation will be introduced.

Canonical main fetched at 1a3c75f; isolated branch extends PR #4 at 995b7e3.
Requirements and evidence are tracked manually because no local EARS/state
helpers exist. TEST/client acceptance remains deferred; no merge/deployment.

- ABUSE-001: THE SYSTEM SHALL count the connected alliance group as one side,
  including indirect allies, and SHALL fail closed when relation data is unknown.
- ABUSE-002: WHEN a player changes guild during a match THE SYSTEM SHALL exclude
  that player from protected scoring/rewards. Guild identities SHALL be captured
  at activation, including offline members. Alliance removal SHALL NOT split a
  side during that match; newly added alliances SHALL merge sides conservatively.
- ABUSE-003: THE SYSTEM SHALL require a distinct enemy side to accumulate a
  configured amount of uncontested scoring or simultaneous opposing hill activity.
  Brief entry, allied presence and ineligible accounts SHALL NOT qualify.
- ABUSE-004: THE SYSTEM SHALL apply configured first-seen age and played-time
  eligibility, and SHALL NOT classify players by shared IP addresses.
- ABUSE-005: THE SYSTEM SHALL bound recipient command executions per event;
  contributor/all-online expansion SHALL NOT increase the fixed command budget.
  Only the winning guild receives guild money/win credit; allies do not share it.
- ABUSE-006: THE SYSTEM SHALL durably reserve qualifying reward outcomes before
  payouts, limit repeated rewarded opponents across restart and concurrent events,
  and record identifiers/activity/rejection reasons for staff auditing.
- ABUSE-007: THE SYSTEM SHALL retain existing behavior when protection is disabled
  and private tests SHALL NOT generate reward-history or suspicion records.

Baseline evidence: guild IDs are independently counted, presence alone meets
participation, and recipient placeholders execute for every eligible online
account. Existing 265 tests are regression evidence, not a historical red run.
User selected **keep thresholds disabled until TEST**. `reward-protection.enabled`
defaults false; proposed 30 seconds, 7 days, 120 played minutes, two rewarded wins
per opposing side per 24 hours remain editable values, not activated policy.
Safeguards cannot prove human identity or detect all staged opposition. A durable
reservation is at-most-once, not an exactly-once command execution transaction;
crashes after reservation may require manual payout reconciliation.

## Engine / architecture / refine

Pure application policy captures the complete offline/online membership roster
at activation. Alliance edges are reconciled on scoring ticks and completion,
and only merge; unavailable data permanently disqualifies that protected match.
Guild changes observed by those checks invalidate the account for that match.
This is polling, not a physical ban on editing guilds, and does not claim to
detect every transient between-tick change. SOLO scoring remains individual, but
allied guild accounts still share one opposition/history side. Allies continue
to contest each other under the existing scoring rules; cooperative alliance
capture remains deferred. Only one winning identity receives credit/rewards.

Meaningful opposition is eligible hill co-presence with the eventual winner or
uncontested opposing score, measured in scoring seconds. It cannot prove that
players actually tried to fight; staged opposition is mitigated, not eliminated.
Eligibility uses server first-seen time and Paper PLAY_ONE_MINUTE ticks (20 ticks
per second), not Mojang account creation or an external playtime database.

Protected `{CONTRIBUTORS}` and legacy `{ALL_ONLINE}` commands use eligible winning
contributors, ordered by scoring time and stable UUID tie-break. One configurable
recipient-command execution budget is shared across all fixed/chance rows. A
failed dispatch consumes its slot; no automatic retry. This bounds expansion,
not the internals of arbitrary administrator-supplied commands or selectors.
Bank/fixed commands retain their configured single execution and money amount.
No item/money amounts, reward package choices or guild XP rates are invented.

SQLite acquires its writer lock before checking/referencing opponent history and
commits an event UUID reservation before win credit or payouts. Audit records
contain match IDs, sides, activity, excluded UUIDs, relation-change flags and
decisions; no IP addresses. `accepted=1` means reserved, not confirmed paid.
Rejected/duplicate outcomes do not pay. Staff can inspect `koth_reward_audit` and
`koth_reward_opponents` in the existing statistics database; no public audit menu.

MaceGuard main e18093c was inspected: its item listener obtains active rotation
through a live supplier. `combat.follow-warzone-rotation=true` makes KOTH's own
item/cooldown policy permissive, so MaceGuard enforces its current policy without
a competing KOTH rotation. Arenas must be inside MaceGuard's configured scope;
missing/disabled/out-of-scope MaceGuard does not magically gain enforcement.
Existing legacy arena rule values are retained for explicit opt-out. TEST must
verify region coverage and a rotation change mid-match before activation.

Companion source: LumaGuilds main a15b244e lacked relation lookup. Its separate
read-only API PR adds `GuildLookup.getActiveAllianceGraph()` with default null for
unknown capability, retains the old constructor, and injects RelationService.
KOTH catches old-runtime linkage errors and never treats unknown as unrelated.
Both companion source and runtime/pins must be verified before enabling this gate.

Verification includes pure alliance/identity/eligibility boundaries, actual
SQLite restart and two-store reservation contention, engine payout count,
allied opposition rejection, durable-write failure, paid-start-before-withdrawal
and old API compatibility. Full local checks and artifact evidence accompany
delivery; hosted checks and deferred TEST/client acceptance remain separate.

Local verification: final clean `./gradlew.bat clean test build --no-daemon`
passed **282 tests** on JDK 21/Paper 1.21.11. No failures/errors/skips.
Local unmerged JAR `EnthusiaKOTH-0.3.0-SNAPSHOT-all.jar` SHA-256:
`2f7da8d362711ca8c45c7fbce72f8826b7b3be0f982f51b565e67099f8c2f08b`.
Inspection found zero duplicate entries and zero bundled server API classes.
LumaGuilds' full 1540-test suite passed on the initial base. Its main was rewritten
while work was underway; the companion change is rebased onto e11fb16e and
clean verification on that base is a separate delivery gate.

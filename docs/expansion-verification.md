# KOTH expansion requirements and evidence

Authorized October 8: implement all eight additions in the feature comparison.
Canonical main fetched at `1a3c75f`; isolated branch extends setup PR #3 at
`efa88bc`. No repository EARS/state helpers exist; SPEAR tracking is manual.

## Spec

- EXP-001: Staff SHALL edit daily arena and rotating schedules, scheduling enabled
  state and IANA timezone through staged menus, and preview actual next occurrences.
  Existing rotation, DST and durable claim rules SHALL be retained.
- EXP-002: Staff SHALL edit fixed/chance commands and per-arena solo/guild money
  through explicit Save and preview recipient semantics without executing payouts.
  Shared legacy reward families SHALL NOT be modified by a single-arena edit.
- EXP-003: Staff SHALL inspect disabled/enabled arena rules/rewards/schedule and
  teleport to a checked safe objective surface using normal cancellable teleport.
- EXP-004: Staff SHALL edit bossbar/actionbar/border and optional hologram/sidebar
  settings. Sidebars SHALL yield to existing sidebar owners and restore only their
  own state. Capture displays SHALL retain spawn/warzone/market and opt-out scope.
- EXP-005: Daily, Monday-based weekly and configurable season standings SHALL use
  timestamped wins from this release onward; lifetime/legacy stats SHALL remain.
  Window boundaries SHALL be inclusive start/exclusive end in configured timezone.
- EXP-006: SCORE SHALL accumulate one point per uncontested scoring second until
  event expiry; leaving/contesting SHALL pause accumulated points. A positive unique
  leader SHALL win, ties/zero SHALL have no winner. Existing fairness/rewards apply.
- EXP-007: A documented public Bukkit lifecycle API SHALL publish immutable,
  synchronous start/control/completion/cancellation snapshots. Observer failures
  SHALL NOT prevent settlement/cleanup; hooks SHALL NOT be cancellable financial gates.
- EXP-008: Configurable event capacity (default one) SHALL permit distinct,
  nonoverlapping public arenas concurrently. Private tests SHALL remain exclusive.
  Death/restrictions/bars/borders/reminders/Discord throttles/rewards/cleanup SHALL
  be event-scoped. Paid starts SHALL reject capacity/conflicts before withdrawal.
  Durable FIFO queue recovery SHALL preserve existing occurrence identity/claims.

Save is atomic with revision checks, and refuses active events or queued starts.
No new reward amounts, season dates, production capacity or display opt-ins are
selected for the server. Local tests and responsive simulation are separate from
hosted checks, PR review, merge and deferred TEST/client acceptance.

## Prove

Source baseline: one activeEvent, one bossbar/border, no timestamped wins, SCORE,
lifecycle API or schedule/reward/display menus. Existing 223-test setup branch is
the starting evidence; no historical red/green evidence is invented.

## Engine / architecture / refine

Implemented all EXP-001 through EXP-008. Draft/settings validation is in the
application layer; YAML, Bukkit menus, lifecycle events, displays, WorldGuard
bounds and SQLite history remain infrastructure adapters. Settings saves are
revision checked and atomic; per-arena money edits allocate a fresh reward family.
Concurrent events use independent UUIDs and cleanup. Unknown WorldGuard bounds
fail closed; bounding boxes are conservative for polygons. Private tests remain
globally exclusive. Paid conflicts are rejected before withdrawal.

Manual review corrected reentrant cancellation during completion, cancellation
inside STARTED observers, and shutdown recovery that could otherwise hide an
earlier pending refund. Focused regressions cover these paths. Queue fault
injection and existing payment/reward tests remain intact.

`./gradlew.bat clean test build --no-daemon` passed on JDK 21 with the declared
Paper 1.21.11 API: **265 tests, zero failures/errors/skips**. Coverage includes
atomic/stale/disk settings writes, menu ownership and permissions, actual SQLite
restart/idempotency, timezone boundaries, SCORE pause/expiry/ties, queue identity,
capacity/overlap/private isolation, second-arena death/pearl restrictions,
independent display cleanup, observer failures and refund recovery.

Local unmerged test artifact: `EnthusiaKOTH-0.3.0-SNAPSHOT-all.jar`, SHA-256
`ac9fd37d97c26e0924892787e993cd3ccd1d6df5a09711ba6662c0b0b3095f55`.
JAR inspection found no duplicate entries or bundled Bukkit/Paper/LumaGuilds/Vault
API classes. No additional runtime profiles are declared. Existing Gradle and
WorldEdit getter deprecation warnings remain.

[Interactive preview](https://enthusia-koth-setup-preview.awareyak.chatgpt.site)
is an owner-private, sign-in-required simulation, not a connected server. Hosting
confirmed deployment of source `a41dae368262101ed7bfb0f3c34dffba4aee87eb`.
Browser checks at 320 and 390 pixel mobile widths verified no horizontal overflow,
44-pixel minimum controls, schedule preview, blocked saves, reward previews,
SCORE contest/expiry and independent stop behavior. The authenticated hosted
flow and a physical phone were not tested; the hosted sign-in boundary was checked.

Limitations: legacy wins have no dates and appear only in lifetime totals; no
historic dates are invented. Active scoring is transient, as in the baseline.
COMPLETED hooks report the result, not successful financial settlement. Sidebar
coexistence, holograms, teleport cancellation and all gameplay need deferred
Paper TEST/client acceptance. New hologram/sidebar options default off and event
capacity defaults to one. No production rewards, season date, uploads or restarts
are authorized by this implementation. Exact-head hosted CI/review is recorded
on the delivery PR separately from these local results.

# EnthusiaKOTH

EnthusiaKOTH is Enthusia's King of the Hill framework. It supports solo or guild-based KOTHs, manual/player starts, GUI starts, flare items, scheduled starts, protected KOTH regions, configurable combat-item restrictions, rewards, statistics, leaderboards, and staff/private-test tooling.

## Current live deployment status

The refreshed Enthusia SMP server snapshot currently has:

- global lock state: **UNLOCKED**
- scheduled KOTHs: **disabled**
- Discord webhook announcements: **disabled**
- manual basic/advanced costs: **0**
- capture arena: **disabled**
- moving arena: **disabled**
- conquest arena: **disabled**

That means the plugin is present/configured, but **none of the production KOTH arena families are currently enabled in the live snapshot**. Future wiki text should not advertise KOTH as presently runnable unless a later server snapshot shows an enabled arena.

The repository's bundled default config is newer than the live snapshot and enables some example/default arenas. Do not use bundled defaults to infer current live availability.

## Player commands

The main command is `/ekoth`.

Ordinary player-facing subcommands implemented by the plugin include:

- `/ekoth gui` — open the KOTH selection/status GUI.
- `/ekoth schedule` — show scheduled KOTH times when scheduling is enabled.
- `/ekoth top [page]` — show KOTH wins leaderboard pages.
- `/ekoth stats [player]` — show KOTH win statistics.
- `/ekoth notifications` — toggle public KOTH messages, warnings and passive displays; saved in player data. `/ekoth messages` is an alias.
- `/ekoth start <arena> [basic|advanced] [solo|guild]` — request a manual KOTH start when the arena/start mode is available.

Staff-only surfaces include stopping/cancelling KOTHs, flare distribution, reload/status/lock controls, and private-test start/join/cancel flows.

## Solo and guild modes

A KOTH can run in either:

- **Solo mode** — an individual player is the capturing/winning identity.
- **Guild mode** — capture/win ownership is associated with a LumaGuilds guild.

LumaGuilds is a required runtime dependency. The plugin resolves guild membership through its guild integration rather than maintaining a separate guild system.

## Participation and risk configuration

Config version 8 adds opt-in start/participation gates under `fairness`.
`starter-cooldown-seconds` covers player command, GUI and flare starts across
arenas. Admin/console starts bypass it. Cooldowns persist in
`starter-cooldowns.dat` and survive reload/restart. Reservations are saved before
payment; ordinary failed starts release them. A crash or failed release may leave
a conservative cooldown until expiry. Corrupt state blocks configured cooldown
starts rather than resetting limits; preserve the file for operator repair.

`minimum-online-teams` counts distinct online nonspectator, alive/valid accounts
for solo events and guilds for guild events before player starts.
`minimum-participating-teams` independently requires enough distinct eligible
teams to visit the hill during scoring ticks before any win/reward is granted,
including scheduled/admin events. Both defaults are 0 (disabled). These are not
unique-human checks and cannot identify alts or prove actual PvP occurred.

Use `{CONTRIBUTORS}` in arena fixed/chance commands for individual reward
recipients, for example `give {CONTRIBUTORS} diamond 1`. A player must have scored
on the winning team for at least `contributor-minimum-percent` (default 10) of
that team's scoring ticks, be online, and still represent that team at payout.
Each scoring tick gives every eligible capper one second; multiple simultaneous
cappers do not divide each other's credit. Contested/non-scoring ticks do not
count. Credit is cumulative over the event even if capture progress resets or
decays, and cannot transfer between guilds. Chance is rolled per command, then
the selected command is applied to its eligible recipients. Offline payouts are
not queued. Legacy `{ALL_ONLINE}` retains its behavior: online guild members in
guild mode, all online server players in solo mode. It is not a contributor filter.

Set `arenas.<id>.keep-inventory: true` for a lower-risk arena. Experience is also
preserved by default (`keep-experience: true`). The rule applies only while the
event is ACTIVE and to authorized private participants/public players dying inside
that event's named WorldGuard region, or its capture/protected geometry if no named
region is configured. It does not extend to deaths elsewhere or delayed starts.
Set `reward-family` to a separate entry under `rewards` for lower money payouts;
arena fixed/chance commands must also be configured for that variant. No automatic
discount is assumed. These features require staged Paper/client acceptance before
production activation; the earlier live snapshot above is not a current readiness
claim. See [the priority checklist](docs/tasks.md).

## Start origins

The implementation recognizes several event origins:

- player command,
- KOTH GUI,
- flare item,
- admin command,
- scheduled start,
- private test.

A global lock state controls which of these are allowed:

- `UNLOCKED` — normal start paths allowed.
- `MANUAL_LOCKED` — only scheduled, private-test and admin starts are allowed.
- `ALL_LOCKED` — no KOTH starts are allowed.

Manual starts can have separate **basic** and **advanced** Vault economy costs. The current live values are both 0, but arena availability is currently disabled.

## KOTH flares

The plugin supports special KOTH flare items. A valid flare can be used to start its configured KOTH when:

- flare use is enabled,
- the player has permission,
- start locks allow it,
- the target arena/start request is otherwise valid.

The bundled current implementation uses a custom named redstone-torch item by default, but exact live item text should be taken from the deployed config/language files.

## Arena families

### Capture KOTH

A standard capture-zone KOTH. Players/guilds fight for control of a fixed circular objective.

Important configurable behavior includes:

- capture radius,
- overall KOTH duration,
- required uninterrupted/accumulated capture time,
- what happens to capture progress after the capper leaves (`RESET`, `DECAY`, or `PAUSE`),
- capture-progress decay rate,
- whether multiple cappers contest the point,
- solo vs guild ownership,
- fixed and chance-based completion rewards.

The bundled config's example capture arena uses a 5-block capture radius, 15-minute event duration and 120-second capture requirement, but the current live arena is disabled.

### Moving KOTH

A moving-objective variant. The objective moves through the configured arena/path rather than remaining at one permanent capture point.

Relevant configuration includes the moving objective's square/path size, movement speed, duration, restrictions and rewards.

### Conquest KOTH

A conquest-style mode that uses multi-player/guild pressure and configurable capture-speed bonuses. More participants can contribute to faster capture according to configured scaling.

The implementation preserves per-player-count capture-speed bonuses and supports the same broader reward/restriction infrastructure as other arena families.

## Capture behavior and contesting

KOTH runtime tracks the current capper, progress, event state and objective position. Capture ownership can be contested according to arena rules. The plugin also exposes a progress bar and periodic reminders when configured.

The event state model supports scheduled, queued, starting, active, ending, completed and cancelled states so starts/cancellations/recovery do not have to be treated as a single transient command.

## Combat-item rules

Each KOTH family can configure whether these are allowed and, where supported, their cooldowns:

- elytra,
- mace,
- spear,
- ender pearls,
- wind charges.

Maces support a configurable mace policy rather than only a simple boolean. The current live snapshot has all listed items allowed and all configured cooldowns at 0 for each family, but the arenas themselves are disabled.

## Region protection

Each KOTH arena can bind directly to a named WorldGuard region with `worldguard-region`. When a binding is present, the WorldGuard region geometry is authoritative for permanent KOTH arena protection; the legacy `protected-region` cuboid remains as a backward-compatible fallback for arenas that are not yet bound.

The capture objective is independent of the arena boundary. `center` selects the objective center and `radius` is a true horizontal circular radius.

Staff can create and configure arena geometry and basic rules entirely in game:

```text
/ekoth setup
```

Choose **Create arena**, type its name privately in chat, then use the editor.
**Arena boundary** gives a tagged selection wand: left-click the first block,
right-click the opposite corner. Select the intended vertical extent, or click
**Extend native arena to full world height** after selection. Alternatively,
**Choose existing WorldGuard region** binds a region without changing its flags.
Stand at the desired hill and click **Capture center**; adjust radius, event limit,
capture time, leave behavior and inventory/XP toggles. **Preview geometry** sends
private particles for 30 seconds (static circle and native cuboid only; no named
WorldGuard outline or moving-path animation). Use `/ekoth setup` to return.

**Save arena** validates and atomically persists before applying. Nothing changes
until Save. Closing keeps the draft; Cancel, `/ekoth setup cancel`, logout or plugin
disable discard it. Saves are blocked while any event or queued start exists and
reject stale drafts after another configuration change. New arenas are disabled,
unscheduled and have an isolated zero-money reward family with no reward commands.
Rewards, schedules and advanced family rules still require deliberate configuration.

Shortcuts: `/ekoth arena create <name> [capture|moving|conquest]`,
`/ekoth setup <name>`, `/ekoth editor <name>` and `/ekoth wand`.
All setup actions require `enthusiakoth.admin`. Existing setup commands remain:

```text
//wand
# select the arena, then:
/rg define koth

/ekoth arena region capture koth
/ekoth arena center capture
/ekoth arena enable capture
/ekoth test start capture solo self quick
```

`/ekoth arena region` resolves the WorldGuard region in the staff member's current world, and `/ekoth arena center` stores the center at the middle of the block under the player's X/Z position. Region names are tab-completed from WorldGuard.

The plugin also provides an explicit staff bypass permission for intentional maintenance. The display system can render KOTH objective/zone-border visuals so players can identify the active objective area.

## Rewards and economy safety

The plugin supports:

- Vault-backed player-paid starts,
- solo completion rewards,
- guild-vault completion rewards,
- arena command-style rewards,
- chance-based rewards.

Start payments are tracked with payment receipts/journaling and refund-recovery logic so a failed/cancelled start does not rely on a fragile one-shot economy transaction.

The current live snapshot has KOTH reward money values at 0 and no enabled arenas.

## Statistics and leaderboards

KOTH wins are stored in SQLite-backed statistics storage. `/ekoth top` pages through ranked wins and `/ekoth stats` exposes individual win totals. The code also contains migration support for older YAML statistics.

PlaceholderAPI integration exposes KOTH state/stat information for other server displays.

## Scheduling

The scheduler supports:

- a configured timezone,
- configured daily occurrence times,
- pre-start warnings,
- queued/event-safe lifecycle handling,
- Discord notifications when enabled.

The current live snapshot uses `America/New_York` and contains example times at 00:00, 08:00 and 16:00, but `schedule.enabled` is false, so those times are **not currently active event times**.

## Private testing

Staff can run isolated private KOTH tests with configurable quick-match and quick-capture durations. Private tests have their own permission/access model and are allowed separately from ordinary player-facing starts.

## Discord integration

Optional Discord webhook support can announce/pre-warn/live-update KOTH events. It is currently disabled in the live SMP snapshot.

## Configuration source of truth

For implementation behavior, use:

- `src/main/kotlin/` for runtime behavior,
- `src/main/resources/config.yml` for current bundled/default config structure,
- `src/main/resources/lang/en_US.yml` for player-facing language,
- `docs/config-audit.md` for which configuration keys are actually consumed.

For **current Enthusia SMP availability and values**, use the latest `enthusia-server-state` snapshot instead of repository defaults.

Public start and winner announcements are global and respect `/ekoth notifications`. Capture entry/leave/countdown/reminders, boss bars and progress action bars are limited to players currently inside WorldGuard regions from `notifications.capture-regions` (default `spawn`, `warzone`, `market`) in the event world. Missing regions or an empty list suppress these local updates; no global fallback. Private test messages remain participant-only.

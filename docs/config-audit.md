# Configuration audit

`config.yml` contains runtime behavior and numeric/item definitions. Player-facing text is configured only in `lang/en_US.yml`; duplicate message templates were removed from `config.yml` because the Kotlin implementation never consumed them.

| Key | Runtime owner | Status |
|---|---|---|
| `config-version` | `ConfigLoader` | Validated and warns on mismatch |
| `fairness.contributor-minimum-percent` | `ScoringParticipation` / reward commands | Default 10; applies only to `{CONTRIBUTORS}` |
| `fairness.starter-cooldown-seconds` | `StartService` / durable cooldown store | Default 0; covers player command, GUI and flare starts |
| `fairness.minimum-online-teams` | `StartService` / guild adapter | Default 0; counts distinct eligible online accounts in solo mode or guilds in guild mode before player start |
| `fairness.minimum-participating-teams` | completion policy | Default 0; withholds rewards and win credit unless enough teams visited the hill |
| `arenas.*.keep-inventory` | death listener | Default false; active-event arena/participant scope |
| `arenas.*.keep-experience` | death listener | Default true; takes effect only with keep-inventory |
| `arenas.*.reward-family` | completion money rewards | Optional independent `rewards.<name>` selection; no fallback when an explicit name is missing |
| `general.timezone` | `ConfigLoader` / `ScheduleService` | Parsed once with a guarded `America/New_York` fallback |
| `locks.state` | `ServiceModule` / start services | Loaded, persisted, consumed |
| `manual-start.enabled` | `StartService` | Consumed |
| `manual-start.basic-cost` | `StartService` | Consumed as decimal Vault currency |
| `manual-start.advanced-cost` | `StartService` | Consumed as decimal Vault currency |
| `manual-start.delay-seconds` | `StartService` / `KothService` | Consumed |
| `private-testing.*` | private-test application flow | Consumed; objective particles are rendered by the display lifecycle in PR F |
| `schedule.enabled` | `ScheduleService` / placeholders | Consumed |
| `schedule.pre-start-warning-seconds` | `ScheduleService` | Consumed |
| `schedule.times` | `ScheduleService` | Consumed as legacy rotating occurrences |
| `flares.enabled` | `StartService` | Consumed before item use |
| `flares.item.*` | `FlareService` | Consumed |
| `progress-bar.enabled/length/character` | `KothService` | Consumed; text template lives in language file |
| `reminders.enabled/interval-seconds` | `KothService` | Consumed; text template lives in language file |
| `discord.*` | Discord and scheduling integrations | Consumed; HTTP lifecycle is repaired in PR F |
| `notifications.capture-regions` | WorldGuard audience adapter / KOTH messages and displays | Region IDs in event world; defaults spawn/warzone/market; empty or missing regions do not broadcast captures globally |
| `display.zone-border` | display lifecycle | Consumed |
| `rules.defaults.*` | restriction service | Consumed |
| `arenas.*` | `ConfigLoader`, scheduling, capture, rewards | Consumed |
| `arenas.*.worldguard-region` | `WorldGuardRegionService` / arena protection | Optional named WorldGuard region; when set, WorldGuard geometry is authoritative for arena protection |
| `arenas.*.capture-speed-bonuses` | Conquest capture logic | Consumed and preserved |
| `rewards.*` | completion reward logic | Consumed |

Removed misleading keys:

- `messages.*` from `config.yml`
- `flares.messages.*`
- `progress-bar.format`
- `reminders.format`
- `storage.stats-file` (legacy migration intentionally detects `stats.yml`)

Those values were either duplicated by the language file or never read by production code.

## Expansion keys

| Key | Runtime owner | Behavior |
|---|---|---|
| `events.max-concurrent` | `KothService`, `EventConcurrency` | Public capacity, default 1; editor validates 1 through 16 |
| `leaderboards.season-start` | command/window resolver | ISO local date; blank/invalid/future disables season view |
| `display.bossbar`, `display.actionbar` | `DisplayService`, `KothService` | Enabled by default; retain regional/opt-out audience |
| `display.bossbar-color/overlay/title` | `DisplayService` | Bukkit styles and optional MiniMessage template |
| `display.hologram/sidebar` | `DisplayService` | Optional, default off; audience and sidebar ownership guards |
| `rules.defaults.score` | `ConfigLoader`, restrictions | Optional SCORE rules, permissive if missing |

Staff editors write existing `schedule.*`, `arenas.*.schedule.times`, arena fixed/
chance commands and `arenas.*.money-reward-family`. Money writes allocate a new
`rewards.<editor-family>` with both solo and guild amounts; shared families are
not edited. No cron model or historic timestamp migration is introduced.

## Reward protection

`reward-protection.*` is loaded by ConfigLoader and frozen per protected match.
`enabled` defaults false until TEST. Opposition seconds are positive, account-age
days/playtime minutes nonnegative, recipient command budget 0 through 100, repeat
window at least one hour and repeat wins nonnegative (zero disables only that
limit). Identity/alliance checks remain active whenever protection is enabled.
Legacy `fairness.minimum-online-teams` becomes a count of eligible sides under
protection; paid/flare starts require two eligible sides before payment/start.
`combat.follow-warzone-rotation` selects permissive KOTH item rules versus retained
family rules; actual MaceGuard enforcement is its own region/runtime responsibility.

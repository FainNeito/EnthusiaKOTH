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

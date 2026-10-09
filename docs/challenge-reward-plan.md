# Challenge and reward configuration preparation

## SPEAR and preparation boundary

Prepared from canonical KOTH `d055211` on 2026-10-09. Server testing is deferred.
CFG-001: The candidate SHALL remain disabled with zero event budgets, empty packages/XP and blank tag/template keys until TEST acceptance.
CFG-002: The plan SHALL preserve stable challenge IDs and document actual ledger, companion and lifetime ownership behavior without inventing installed definitions.

Spec: existing PROG-001..008 and accepted 10% contributor/lifetime policy. Prove/engine: documentation and an inactive candidate only; no gameplay changes or historical red/green claims. Arch: inspected ProgressionSettings, ProgressionPolicy, SqlProgressionStore and Advancements' ekoth tree. No domain/platform or companion API changes. Project-local EARS/state helpers are absent; this manual requirement/task/evidence record applies. Refine: candidate validation recorded below; hosted checks and server acceptance are separate.

## Prepared candidate and draft challenges

[configuration/progression-test.yml](configuration/progression-test.yml) is a complete KOTH progression.yml candidate, copied from current defaults. It retains existing draft thresholds and no payout-capable IDs. Source defaults are unchanged. During approved TEST preparation, back up and merge reviewed keys into existing progression.yml; do not replace other config or databases. Tags and LoreItems definitions belong to their own providers.

| Stable ID / display | Wins | Opposing sides | Arenas | UTC days | Scoring time | Proposed tag | Proposed relic definition |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| contender / Contender | 5 | 3 | 2 | 3 | 1,800s (30m) | koth_contender | none |
| veteran / Warzone Veteran | 25 | 10 | 3 | 14 | 10,800s (3h) | koth_veteran | none |
| sovereign_blade / Sovereign Blade | 100 | 25 | 4 | 30 | 43,200s (12h) | koth_sovereign_blade | koth_sovereign_blade |
| sovereign_crown / Sovereign Crown | 150 | 35 | 4 | 45 | 64,800s (18h) | koth_sovereign_crown | koth_sovereign_crown |

All five metrics apply per player. Days are distinct UTC dates with accepted eligible wins, not consecutive days or the scheduling timezone. Opponents are conservatively merged side identities, not unique humans. Arenas are distinct stored IDs: do not rename/recreate areas to inflate variety. Relic drafts need four accepted arenas. Display parents do not add prerequisites to KOTH's five-metric rules. Full renderer descriptions must change with thresholds; the pilot derives descriptions from KOTH. Install only one renderer profile.

Only durably accepted public protected wins with unchanged relations and eligible contributors count. Draft participation is 10% of winning-side scoring PLUS 120 personal scoring seconds and 120 seconds of eligible opposition. The personal floor can exclude short-event contributors; calibrate capture/duration settings before choosing it. Scheduled/player/GUI/flare sources can qualify. Private/admin tests, lifecycle events, advancement grants, cosmetic kills and retired AxKoth capture tokens cannot supply credit.

Protection carries inactive draft values of 30 opposition seconds, 7 account-age days, 120 playtime minutes and 2 rewarded wins against the same side per 24 hours. The stricter progression opposition floor also applies when enabled. Roster age and starter/team fairness gates remain zero/disabled. These are draft values, not active restrictions or proof that undisclosed allied alts can be detected.

## Reward mapping and lifetime ownership

The tag/definition names above are proposals, left blank in runtime YAML. Inspect actual Tags/LoreItems registries for collisions and supported metadata before assigning keys. EA owns display only; its eKOTH rewards remain suppressed. No provider command or signer UUID is invented here.

Each qualifier can earn each challenge's tag once, including Blade/Crown tags. Only one person EVER receives each physical relic. Blank exclusive templates prevent that challenge award/reservation. Populate only after template acceptance. Lifetime identity is the immutable challenge ID, not definition name. Never rename IDs, delete ownership rows or import/restore TEST data to issue another relic. First qualifying transaction reserves ownership; scoring time then UUID orders contributors within a match. Item loss, seasons and reload do not reset ownership.

Sword draft: netherite; Sharpness V, Sweeping Edge III, Looting III, Fire Aspect II, Knockback II, Unbreaking III, Mending. Lore: "Forged for the sovereign of Enthusia's contested hills." FainNeito must genuinely sign/confirm the held item and attach Signature's player_kills tracker before saving through LoreItems. Preserve real Signature metadata/managed lore. Kills never grant advancement progress. Exact enchantments/balance remain TEST decisions.

Helmet draft: netherite; Protection IV, Respiration III, Aqua Affinity, Unbreaking III, Mending. Thorns III awaits balance acceptance. Lore: "One crown. One sovereign." Use a real supported model/texture and inspect Java/Bedrock before saving the held definition. No texture/model ID is invented or installed. See [exclusive-rewards.md](exclusive-rewards.md).

## Event budgets and later activation

Currency pool stays 0 cents, item pool 0 units, packages empty, guild XP command empty. Choose economic values after calibration. One event pool splits equally across qualifiers with deterministic integer remainders. Each package consumes the same event budgets; differing item values still need review. Relics/tags are separate challenge claims. Verify the real XP command target/amount/failure behavior before configuring it.

Clear every arena fixed/chanced payout and zero every money family, including custom/keep-inventory variants, before activation preparation. Enabled progression replaces legacy payouts, but DISABLED progression does not disable legacy commands. Preserve real geometry and disable arenas/manual starts/flares/schedules during preparation; use network safety fragments. Capture notices stay spawn/warzone/market; start/winner global. MaceGuard's current warzone rotation controls combat. Keep-inventory variants preserve inventory AND XP and need separately selected lower rewards.

Use isolated TEST KOTH/provider storage and disposable templates. Private/admin matches intentionally cannot prove earned progression: use controlled PUBLIC protected TEST matches with consenting guilds. Record real balances, operation IDs, queued versus physical delivery, simultaneous ownership, restart/retry and uncertain payout handling. Retry requires provider evidence. Follow [test-acceptance.md](test-acceptance.md) and network release preparation. Production activation remains separate.

## Verification evidence

Strict SnakeYAML 2.2 parsing with duplicate keys rejected passed. Parsed candidate equals the canonical resource exactly: stable four challenge IDs/metrics, progression disabled, zero budgets, empty packages/XP and blank tags/exclusive templates. Reviewed all keys against ProgressionSettings and ProgressionPolicy; compared IDs/metrics with the pinned Advancements ekoth tree. Whitespace validation passed. This is configuration/documentation evidence; no new gameplay tests or full build are claimed. Hosted PR verification is separate.

No server files, provider registries, ownership rows, GUI layouts, source defaults or Advancements tree changed.

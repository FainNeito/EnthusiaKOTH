# Display and hologram placeholders

Base: fetched canonical main d96ba685ed81da83474fdf221e68322068fad02a, isolated codex/koth-display-placeholders. No project EARS/state helpers exist; SPEAR evidence is manual.

- PAPI-001: When a display requests current, next or arena-specific names, the system shall expose the configured label with legacy/RGB formatting and emojis, terminating its style with a reset; existing arena-ID placeholders shall retain their values.
- PAPI-002: When a capture-family event is visible, the system shall expose the current controller's recorded capture percentage (0..100), remaining capture duration and remaining whole seconds rounded up, independently of the event timer. No controller shall yield zero progress and the full target duration; contention/pause shall not advance the placeholder clock.
- PAPI-003: When a mode has no capture target (Score, Moving, Conquest), capture fields shall return `N/A`; missing/private-invisible events shall return `Not Active` for duration and `0` for numeric capture fields, without exposing private event data.
- PAPI-004: When arena IDs share prefixes or contain underscores, arena-specific placeholders shall resolve the longest matching ID case-insensitively. Concurrent events shall use their own controller/score/target.

Names use legacy section codes, including expanded RGB, with a trailing reset. The consuming display must support PlaceholderAPI and legacy/RGB rendering. Emoji glyphs depend on the client/resource pack. No server upload/restart is part of this task.

| Placeholder | Meaning |
| --- | --- |
| `%enthusiakoth_current_name%` | Formatted current arena label; `None` when inactive |
| `%enthusiakoth_next_name%` | Formatted next scheduled arena label; `None` without a schedule |
| `%enthusiakoth_<id>_name%` | Formatted configured arena label, even when inactive |
| `%enthusiakoth_current_capture_progress%` | Current controller's capture percentage, one decimal, no percent sign |
| `%enthusiakoth_current_capture_timeleft%` | Remaining capture duration, e.g. `1m 30s` |
| `%enthusiakoth_current_capture_secondsleft%` | Remaining capture seconds, rounded up |
| `%enthusiakoth_<id>_capture_progress%` | Arena-specific capture percentage |
| `%enthusiakoth_<id>_capture_timeleft%` | Arena-specific remaining capture duration |
| `%enthusiakoth_<id>_capture_secondsleft%` | Arena-specific remaining capture seconds |

Existing `current_koth`, `next_koth`, `<id>_timeleft`, wins and leaderboard placeholders keep their meanings. Capture progress reads earned score and does not predict pauses, contesting, decay or future activity. Arena IDs, schedules and stored statistics remain unchanged.

Example (arena ID `capture`):
```text
%enthusiakoth_capture_name%
Capturing: %enthusiakoth_capture_capper%
Capture: %enthusiakoth_capture_capture_progress%%
To capture: %enthusiakoth_capture_capture_timeleft%
Event ends: %enthusiakoth_capture_timeleft%
```

## SPEAR evidence

- Spec/prove: requirements recorded against fetched canonical main. A new name-placeholder assertion failed against the old resolver before implementation (one failure in six tests); no historical red/green evidence is invented.
- Engine: the PlaceholderAPI adapter supplies formatted event/configured names and recorded controller score/target. The pure resolver formats bounded percentage and ceiling-rounded remaining seconds. No Bukkit calls or live clock extrapolation are added to capture calculations.
- Arch: existing IDs, scheduling, stats, rewards and mode engines are unchanged. Private participant guards precede snapshot creation. Arena state suppliers remain authoritative when they hide an event; concurrent arena IDs resolve independently, including underscore/prefix IDs. PlaceholderAPI 2.11.6 is added only to the test runtime to exercise the actual expansion adapter, and remains compile-only for deployment.
- Refine: focused placeholder tests passed. Canonical Java 21/Paper 1.21.11 `clean test shadowJar --no-daemon` passed with 382 passing tests, two existing provider-only skips, no failures/errors (384 total). Tests include expanded RGB/bold/emoji/reset output, playerless public/private requests, owner/outsider guards, controller loss, partial score rounding/clamping, unsupported modes, concurrent/prefix resolution and existing placeholders. Manual review found no remaining issue in changed paths. No independent review or server/client acceptance is claimed; hosted checks/source delivery are tracked on the PR.
- [Interactive simulation](https://enthusia-koth-setup-preview.awareyak.chatgpt.site/placeholders.html) published from clean pushed source `437e37b4d3e395e870877c304a5d1d06c47736fa`. Formatted labels, pause, score-mode and private-view states verified in the owner browser session. The site preserves owner-only access and requires sign-in. Responsive CSS and 48px controls are included; current browser viewport override is ineffective, so this turn does not claim a new mobile-device acceptance check. Native display rendering and actual mobile sign-in remain unverified.
- TEST gate: install the merged build, parse current/next/arena placeholders through the intended display plugin, check styled suffixes, Java/Bedrock glyphs, contested pauses, reset/decay, concurrent arenas and private/playerless visibility. No server upload, restart or production activation performed.

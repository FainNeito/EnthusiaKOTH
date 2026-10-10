# Arena display names (NAME-001..004)

Base: canonical main `23dba1b`. SPEAR is recorded here; this repository has no project-local EARS/state helpers.

- NAME-001: When an arena has a display name, player-facing chat, displays and arena menus shall show that name. Missing or blank names shall fall back to the existing ID.
- NAME-002: When staff change a name through Area > Arena name, the editor shall retain it as an unsaved draft until Save. Cancel, timeout, stale replies and revoked permission shall not apply input to another draft.
- NAME-003: When saved, the name shall persist atomically alongside existing settings without changing IDs, schedules, reward-command substitutions, statistics, challenges or active-display ownership.
- NAME-004: Names shall be plain, single-line text, trimmed and at most 64 characters. Invalid names shall be rejected without changing the draft. Duplicate display names shall remain separate arenas.

Acceptance: local policy/persistence/display/controller checks and source review; interactive preview is schematic. TEST installation, native client rendering and production activation remain separate gates.

## Setup

Open `/ekoth setup <id>` → Area → Arena name. Enter a private chat reply, then Save. `cancel` keeps the previous name; `-` restores the ID. Invalid input returns to the editor with the draft intact. Review shows the name and internal ID.

YAML alternative: `arenas.capture.display-name: "Crimson Summit"`. Missing/blank names use the ID. Invalid configured names fail with their exact configuration path. Formatting codes, tags, control characters and invisible format characters are rejected. The editor reserves `cancel` and `-` as input actions.

## SPEAR evidence

- Spec: NAME-001..004 above; existing arena keys and progression identity are authoritative.
- Prove: the new loader regression compiled against an added nullable config field and failed before loader support (`expected Crimson Summit, actual null`). This is new behavioral evidence, not a claim about historical tests.
- Engine: persisted optional display names, pure validation, private draft-bound chat prompt, Area field and consistent chat/display/menu/flare/Discord presentation.
- Arch: platform menus and YAML remain adapters. Names never own bars/holograms/sidebars. Lifecycle/rewards menu/progression API sources, command targets, flare PDC, statistics, physical arena identity, `{KOTH}` reward commands and PlaceholderAPI ID outputs retain their contracts. Notification audiences are unchanged.
- Refine: clean Java 21 / Paper 1.21.11 build passed; after refinement the complete suite reports **342 passed, 2 provider-only skips, zero failures**. Added 13 cases cover trim/fallback, invalid input, disk save/clear, unrelated keys, runtime zone IDs, duplicate labels/rename cleanup, hidden controls and private input success/cancel/clear/error/permission/stale/timeout boundaries. Existing atomic failure and stale-write tests pass.
- Manual source review found no outstanding issue in the changed paths. No independent reviewer claim; companion runtime API suites were not rerun because their exported contracts and adapters are unchanged.
- Local unmerged test artifact: `EnthusiaKOTH-0.3.0-SNAPSHOT-all.jar`; SHA-256 `4be3640cc7da56bba35603ab7e901dcb59afa485ac520d5204eb1e08eb14844a`. Not uploaded or activated.
- Interactive [preview](https://enthusia-koth-setup-preview.awareyak.chatgpt.site/cleanup.html): name editing, cancel, save gate and named chat/bossbar examples checked at 390×844; no horizontal overflow and button targets at least 48px. Published private version uses preview source `1bdda2a96b821220f699887a8f149baae2da7fcf`. Owner sign-in required; actual mobile authentication and native Minecraft rendering remain unverified.
- TEST: set/clear/save/reopen/restart a name, verify global starts/winners, regional solo/guild capture labels, duplicate labels, normal permission boundaries and Java/Bedrock menus. No server installation, restart or acceptance performed.

Hosted checks and merged source are recorded by the PR. Network pin/release and server activation remain distinct gates.

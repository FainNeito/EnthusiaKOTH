# Discord start role

Base: fetched canonical main 1366c53df7bd5369bcfe69776578bf20dca1eb38; isolated branch codex/koth-start-role-ping.

- HOOK-006: When a public KOTH starts with a configured start role, the system shall put its role mention in message content and allow only that role to notify members. Pre-start, live edits, winners, cancellations and no-winner messages shall not mention it.
- HOOK-007: When start-role-id is absent or blank, the system shall preserve existing mention suppression. Invalid nonblank values shall fail configuration loading with the setting path. Private KOTH shall continue to send no webhook.

SPEAR tooling is absent; requirements, task and proof are maintained here. Baseline inspection confirms all mentions suppressed and no role setting. Local tests use fake transport, never real Discord. No server restart, plugin upload or test post is part of this change.

Configuration: `discord.start-role-id: ""` (disabled by default). Set a quoted Discord role ID for a start-only ping. Discord role mentionability and member notification settings still apply; real Discord acceptance is a TEST gate.

Proof: new regression initially failed test compilation because configuration/service role support did not exist. After implementation, both focused tests passed; full Java 21/Paper 1.21.11 test and shadowJar passed (373 cases, 371 passed, two provider-only skips). Manual review verified explicit role allowlisting, no player-controlled message content, caller-thread configuration capture, blank compatibility and unchanged private-event routing. No companion API changed. Role setting saved and read back on TEST; updated plugin installation and real Discord delivery remain pending.

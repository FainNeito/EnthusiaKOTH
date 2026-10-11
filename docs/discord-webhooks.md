# Discord webhook lifecycle

Base: canonical main 385a8b44a389e53c2c51bd55a9ea84d4fe406864, fetched 2026-10-10; isolated codex/koth-webhook-lifecycle. SPEAR requirements/evidence are maintained manually; no repository EARS or state helpers exist.

- HOOK-001: When a public event starts, updates or ends, the system shall render configurable enabled/title/description/color/fields/timestamp embeds using plain arena/player/guild labels and a documented placeholder set. Missing keys shall retain compatible defaults; invalid values shall fail with a configuration path.
- HOOK-002: When a public event is cancelled or completes without a winner, the system shall enqueue the corresponding terminal announcement without executing additional rewards. Private events shall send no webhook.
- HOOK-003: When live status is enabled, the system shall create at most one live message per event and webhook destination, then PATCH that message. Concurrent events shall use separate UUID ownership, regardless of duplicate names. Terminal results shall finalize that message and remove queued stale live updates.
- HOOK-004: When Discord rate limits or rejects delivery, the system shall bound the queue and retries, prioritize lifecycle messages, honor retry delays, redact transport errors, avoid retrying ambiguous message creation, and prevent old-message edits at a changed webhook destination. A deleted live message shall not cause repeated recreation.
- HOOK-005: When the plugin shuts down, the system shall cancel pending I/O and clear transient state. Shutdown cancellation delivery is best effort; no durable Discord outbox or restart reconciliation is promised.

Acceptance: meaningful policy/transport/event integration/configuration tests, full Java 21/Paper 1.21.11 build, source review, hosted checks and responsive interactive embed preview. No real Discord post, server upload, restart or activation is authorized by this implementation request.

## Prove

Source baseline has fixed embed text/colors, no cancellation/no-winner transport calls, no response message ID or PATCH support, and globally coalesces all live updates. Existing tests establish bounded delivery/retry behavior; they are not historical proof of the new features.

## Evidence

- Engine: added template configuration/defaults and a pure bounded JSON renderer; Bukkit/HTTP/config parsing remain infrastructure adapters. KothService routes public terminal outcomes with UUID ownership without changing scores, payouts, refunds or private-event boundaries.
- Architecture: Discord name resolution happens on the caller/server thread, while HTTP is serialized on a dedicated worker. The bounded delivery queue coalesces per event, terminal state supersedes pending live payloads, IDs stay bound to their webhook destination and shutdown cancels pending work. No companion-plugin API changed, so provider-only suites were not rerun.
- Refine: initial focused run found one malformed-config exception mismatch (40 tests, one failure); corrected to a path-specific IllegalArgumentException. Clean Java 21/Paper 1.21.11 `clean test shadowJar` passed, then final `test shadowJar` after strict scalar validation, explicit start UUIDs and additional private/rate-limit cases passed: **369 passed, two provider-only skips, zero failures/errors (371 total)**. Existing WorldGuard/Gradle deprecation notices remain.
- HTTP fixtures use only a local loopback server. They verify create `wait=true`, returned top-level ID rather than author ID, PATCH, preserved thread destination, UTF-8 emojis and body-only Retry-After. Dispatcher/config/application checks cover coalescing, separate event IDs with duplicate names, terminal races, deleted/uncertain messages, destination changes, template disabling, escaped one-pass placeholders, aggregate limits and public/private terminal calls.
- Manual source review completed for the changed delivery/config/event/template paths and lifecycle safeguards; no independent review or real Discord/server acceptance is claimed. Hosted exact-head checks/source delivery are recorded on the PR.
- Updated [private interactive preview](https://enthusia-koth-setup-preview.awareyak.chatgpt.site/discord.html) published from pushed source `5262f353bcbf0286ca26e4597ad58c10b0e92833`. At 390x844, custom wording/hex color, live edits, two arenas, cancellation and no-winner flows were verified: no horizontal overflow and buttons at least 48px. Native private deployment succeeded and the owner browser opened it. Actual phone sign-in, Discord Markdown layout and emoji glyphs remain unverified/simulated.
- Local **unmerged** test artifact version 0.3.0-SNAPSHOT, SHA-256 `4ebfd0cf99474702b3b46558c44d87bfe75b7305244c44426eae063d0618b78b`. No server upload, restart, activation or real webhook send occurred.

## Configuration

Use `discord.embeds.pre-start`, `start`, `live`, `winner`, `cancelled` and `no-winner` in config.yml. Each supports `enabled`, `title`, `description`, quoted six-digit hex `color`, `timestamp`, and `fields` (name/value/inline). Omitted settings use built-in defaults; `fields: []` removes fields. Discord delivery remains disabled by default and needs the existing `discord.webhook-url` plus `discord.enabled: true`.

```yaml
discord:
  embeds:
    live:
      title: "🏆 {arena}"
      description: "{capper} · {status} · {time_left} remaining"
      color: "#FFAA00"
      fields: []
    cancelled:
      title: "🛑 {arena} stopped"
      description: "{reason}"
```

Placeholders: `{arena}`, `{location}`, `{capper}`, `{winner}`, `{time_left}`, `{status}`, `{reason}`, `{minutes}`, `{event_id}`. Only values applicable to that message type are populated; other values use `—`/`Nobody`. Pre-start warnings have no event UUID yet. Names are plain text with emojis; templates may use Discord Markdown. Placeholder values are inserted once, and cannot introduce new substitutions. Only the optional configured start role may be mentioned on public starts; mentions are suppressed on other creations and edits. Expanded text is bounded to Discord's title/description/field/6000-character aggregate limits.

The first live update creates one message with server confirmation (`wait=true`); subsequent updates edit its ID. On a win, cancellation or no-winner result, an existing live message becomes the terminal embed. A successful final edit suppresses the separate result post. If there is no editable live message or the final edit fails permanently or exhausts retries, the enabled result announcement is posted as a fallback. Disabling that announcement still finalizes existing live status without a fallback post; disabling live prevents new live updates. Each event owns its UUID, including arenas with identical display names. The update interval remains `discord.live-update-seconds` (60 by default). A configured thread query is preserved. Start announcements remain separate, with the optional start-only role mention.

Deleted/rejected/ambiguous live creations stop updates for that event rather than posting replacements. Known rate limits retry; edits and announcements retain bounded temporary-failure retries. Terminal events discard stale queued live updates, preserving rate-limit delays. Changing webhook destinations does not move or edit an old message at the new webhook. Transport error details are redacted.

Message IDs and queues are transient; no durable outbox or restart reconciliation is added. Plugin shutdown cancels pending I/O, so shutdown cancellation announcements/final edits are best effort. Reload cancels running events; apply configuration between events. Queue saturation or persistent Discord failures can still drop delivery. No Discord post proves reward settlement or anti-abuse qualification.

Protocol reference: [Discord webhook execute/edit documentation](https://docs.discord.com/developers/resources/webhook), inspected for wait responses, edits, thread queries and explicit allowed_mentions.

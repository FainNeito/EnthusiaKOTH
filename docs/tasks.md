# KOTH priority checklist

This is the source work checklist derived from the October 8 Discord audit.
Implementation and local evidence do not establish deployed/client acceptance.
Requirements and verification: [priorities-verification.md](priorities-verification.md).

- [ ] BANK-001: review and merge independent [guild-bank repair PR #1](https://github.com/FainNeito/EnthusiaKOTH/pull/1); verify companion runtime and real transaction.
- [x] KOTH-101: persistent public notification toggle, preserving private-test messages and command replies.
- [x] KOTH-106: global start/winner announcements; capture updates/displays restricted to configured spawn/warzone/market regions.
- [x] KOTH-102: team-bound contribution accounting and `{CONTRIBUTORS}` recipients; 10% default.
- [x] KOTH-103: configurable durable starter cooldown and online-team gate before payment/flare consumption; disabled by default.
- [x] KOTH-104: arena-scoped active-event inventory/XP preservation; separately selectable money reward family.
- [x] KOTH-105: optional minimum hill-participating teams before wins/rewards; disabled by default.
- [x] Manual review of bank and priority PRs; REV-001/REV-002 fixed with local regressions.
- [ ] Exact-head hosted build checks and independent review; merge through the normal process.
- [ ] Configure risk-specific commands/money, gates, permissions, schedules and arenas; review old live config migration.
- [ ] Stage two-team Paper/client checks: capture/contest/death/respawn, drops/XP, notifications/rejoin, guild change, exact balances, restart/payment failures and companion restrictions.
- [ ] Verify merged source, owning network pin/build and live acceptance before activation.

Later proposals remain undecided: allied guild wins, reward selection, guild XP and
recognition adapters, combat-profile rotation and selective web reset. No guild XP
command/API, economy discount, loot table or alliance policy is assumed here.

## Guided setup (AxKoth reference)

- [x] Inspect supplied AxKoth 2.27.0 setup bytecode/configuration and compare feature opportunities.
- [x] Define SETUP-001 through SETUP-006; implement staged arena creation/editor/wand/preview and atomic storage.
- [x] Complete focused/full local checks and manual code review; 223 tests, artifact hash in evidence.
- [ ] Hosted checks and source PR review/merge.
- [ ] TEST/client: naming privacy, selection height, existing WG binding, particles, inventory controls, save failures, restart and legacy gameplay acceptance.

See [arena setup evidence](arena-setup-verification.md) and
[AxKoth comparison](axkoth-comparison.md). This request does not deploy or merge.

## Approved AxKoth-inspired expansion

- [x] EXP-001: staged schedules editor and actual upcoming-occurrence preview.
- [x] EXP-002: staged fixed/chance/money reward editor and payout preview.
- [x] EXP-003: arena information and safe, cancellable staff teleport.
- [x] EXP-004: display editor and optional hologram/sidebar with ownership guards.
- [x] EXP-005: timestamped daily, weekly and season standings.
- [x] EXP-006: SCORE accumulation, pause, expiry and tie behavior.
- [x] EXP-007: immutable informational Bukkit lifecycle API.
- [x] EXP-008: bounded simultaneous public events and isolated cleanup/listeners.
- [x] Local review, 265-test clean build, JAR inspection and responsive simulation.
- [ ] Exact-head hosted checks and PR review/merge (stacked on setup PR #3).
- [ ] Deferred TEST/client acceptance and clean merged-source deployment gates.

Details and limits: [expansion-verification.md](expansion-verification.md).
Score and concurrency are now approved by the expansion request; alliance policy,
database replacement and arbitrary leaderboard resets remain undecided.

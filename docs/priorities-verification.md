# KOTH participation and fairness priorities

Base: fetched canonical main `f80adebb10f5be991abe20de41e505ebceb3d5a5`.
Existing bank repair remains independently reviewable in PR #1; this work does
not merge or duplicate it. No production operations are authorized.

## Spec and tasks

- KOTH-101: WHEN a player disables KOTH notifications THE SYSTEM SHALL persist
  that preference in player data and suppress public KOTH chat, warnings and
  passive displays; command replies and private-test messages remain available.
- KOTH-102: WHEN a team gains score THE SYSTEM SHALL record each eligible hill
  player's scoring time against that team, including the winning tick. WHEN a
  reward command uses `{CONTRIBUTORS}` THE SYSTEM SHALL target only online winning
  team members meeting the configured percentage of that team's scoring time.
  Legacy placeholders retain their semantics. Contribution is not human/alt proof.
- KOTH-103: WHEN configured cooldown or minimum-team gates reject a player start
  THE SYSTEM SHALL reject before economy withdrawal or flare consumption. A
  successful player start SHALL persist its cooldown across reload/restart;
  administrative starts bypass it. Failed starts SHALL release reservations.
- KOTH-104: WHEN an active keep-inventory event participant dies inside its arena
  THE SYSTEM SHALL preserve inventory and, when configured, experience without
  duplicate drops. Starting/finished events, other worlds, outside locations and
  nonparticipants SHALL retain normal death behavior.
- KOTH-105: WHEN configured minimum opposing-team participation is unmet THE
  SYSTEM SHALL withhold win credit and rewards. Existing defaults remain compatible.

User confirmed: 10% scoring-time contribution threshold, inventory and XP
preservation, configurable starter/opponent controls disabled by default.
Policy defaults are configurable and conservative: new start/payout gates disabled,
keep-inventory disabled, legacy rewards unchanged; the new contributor placeholder
requires positive scoring participation even with a zero-percent threshold.
Keep-inventory arenas use their own configured rewards; no invented economy rate.
No pricing/discount or production gate values were selected.

## SPEAR state

Spec recorded before engine edits. No local EARS validator or SPEAR state helper
exists; requirement/task/evidence tracking is manual. Three new contribution tests
failed behaviorally against the initial no-op ledger, then passed with accounting.
No historical red/green claim is made for other new features. The first baseline
attempt collided with an overlapping build's Kotlin cache; the subsequent clean
run compiled and ran tests. Two existing queue tests expected global broadcast;
they now assert actual observer delivery and retain exactly-once recovery coverage.
The expanded regression suite passed before final clean artifact verification.
Architecture: domain accounting and application policies; Bukkit/PDC, region and
file persistence remain infrastructure adapters. Review queue/recovery, final-tick
rewards, payment rollback and WorldGuard/private-event boundaries before delivery.

## Delivery gates

- [ ] Focused behavioral regressions and full Java 21 clean build.
- [ ] Runtime API/shaded artifact contracts and configuration documentation.
- [ ] Exact-head hosted checks and actionable review findings.
- [ ] Reviewable PR, unmerged; no production readiness claim.
- [ ] Separate staged Paper/client acceptance, especially death drops/XP,
      guild money and cross-plugin protection. Not performed by local tests.

## Local verification

Java 21 / Paper API 1.21.11: `gradlew.bat clean test build --no-daemon`
passed 177 tests, zero failures/errors/skips. Regression evidence includes the
winning contribution tick, inactive/outside/private death boundaries, XP policy,
durable cooldown reload/corruption/write failure, refund/release behavior and
notification persistence. Queue tests still assert exactly-once announcements to
an actual observer. Public event messages remain visible to the server console.
Shaded artifact inspection found no duplicate ZIP entries or bundled Bukkit,
Paper, Vault or LumaGuilds API classes. Version is now `0.3.0-SNAPSHOT`, config 8;
metadata/artifact rebuild and combined bank-PR verification follow below.

Manual architecture review: new ledger is domain-only, start guards precede
withdrawals and flare consumption, cooldown storage is atomic and fails closed,
and death rules use active-event/private-participant and named-region boundaries.
Contribution data follows existing transient event scores; crash recovery does
not persist partial scoring ledgers. A no-contest completion still settles the
start fee because the event ran; withholding rewards does not refund that fee.

Bank PR #1 remains open at `65ed709c8b7800300ca5955d079cdeb2d13ce6c2`.
Its local evidence (167 passing tests) was inspected alongside the dispatch and
failure guards. GitHub returned zero exact-head workflow runs; no hosted pass
is claimed. FainNeito and upstream wsg138 main both resolved to the base above.

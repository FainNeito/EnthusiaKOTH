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

- [x] Focused behavioral regressions and full Java 21 clean build.
- [x] Compile-time API/shaded artifact contracts and configuration documentation; live runtime acceptance remains pending.
- [ ] Exact-head hosted checks and actionable review findings.
- [x] Reviewable [PR #2](https://github.com/FainNeito/EnthusiaKOTH/pull/2), unmerged; no production readiness claim.
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

## Final artifact and combined verification

Feature implementation commit: `417ff734ad01aa9d8e5455179abc56d9ea559207`.
The versioned `test build` rerun passed all 177 tests. Unmerged local test artifact:
`EnthusiaKOTH-0.3.0-SNAPSHOT-all.jar`, SHA-256
`c113337aa736e87808eb08a7f978365ab0684025528de6faf1554c6b63764f6f`.

An isolated temporary merge of that feature commit plus bank PR head
`65ed709c8b7800300ca5955d079cdeb2d13ce6c2` applied without conflicts.
Combined source tree: `c70171a9143d7457e76c6a34bc73090615d974cf`.
Java 21 `clean test build` passed 186 tests, zero failures/errors/skips.
Combined unmerged local test JAR SHA-256:
`ab1114519ab7f26361ce41f3cf34414ae113d9869524d0ea2a1e4387c00474cc`.
This local combined tree is not a canonical merge, a network build/pin update or
a production artifact. Bank regressions prove dispatch/failure handling, not a
real live guild-bank transaction. Feature and bank PRs remain independently open.

GitHub returned no workflow runs for feature implementation head either. No hosted
pass or automated review approval is claimed. The GitHub connector refused PR
creation; the signed-in browser successfully created PR #2. No source merge,
server upload, activation or restart occurred.

## Notification audience correction

KOTH-106: Public start and winner announcements SHALL reach opted-in players globally. Capture entry/leave/countdown/reminders and passive capture displays SHALL reach only opted-in players currently inside configured spawn, warzone or market WorldGuard regions in the event world. Private tests SHALL remain participant-only. Missing regions SHALL NOT fall back to global capture messages. Region IDs default to spawn, warzone and market and remain configurable.

Current main was fetched and remains f80adeb; this continues PR #2. Project-local SPEAR tooling remains absent.

Notification correction evidence: two new routing regressions failed against the old global capture routing, then passed. Java 21 clean test build passed 180 tests. Final test build passed 182 tests with zero failures/errors/skips after the stale public boss-bar rejoin fix. Coverage includes global start/winner, local capture entry/countdown/leave, current audience changes, public rejoin filtering and private rejoin preservation. WorldGuard lookup remains an infrastructure adapter and missing/wrong-world regions fail closed. Real WorldGuard/Paper/client acceptance remains pending.

Updated unmerged local 0.3.0-SNAPSHOT test artifact SHA-256: `5594afb9cb0c2e017706d6e4ca3d6ab717d219bb90b70000cd04651d3368353a`. Earlier 186-test combined bank verification applies to the earlier source tree recorded above; this follow-up changes notification routing and has not been staged or deployed.

KOTH-106 market extension: user requested Market in the local capture audience. Default config and missing-key fallback include `market`; explicitly configured lists remain authoritative. This is a region-list extension with unchanged routing engine; no new behavioral red/green claim or project-local SPEAR tooling pass.

Market extension validation: Java 21 `test build` passed 182 tests with zero failures/errors/skips. The updated simulation routes Market capture notifications, suppresses outside capture updates and retains global winner announcements. Updated unmerged local test JAR SHA-256: `fc824ed98c1d7c68c728b6cb2586197f21ff8ddc993d6de8933b85386242a2d8`. Production and real WorldGuard/client acceptance remain pending.

## Manual code review follow-up

Review scope: PR #1 at 65ed709c8b7800300ca5955d079cdeb2d13ce6c2 and PR #2 at 4805ec6acddc759244792c2e1a2dc8fd8a4e1173 against fetched main f80adeb. User defers TEST-server work.

REV-001 (P2): One contributor reward dispatch exception aborts the recipient loop and denies remaining eligible contributors. WHEN one recipient's reward command throws THE SYSTEM SHALL log that failure, continue other eligible recipients, and avoid retrying the ambiguous failed operation.
REV-002 (P2): Cooldown reservation currently precedes permissions, disabled-feature, lock and active-event validation, causing synchronous writes for ineligible starts and avoidable cooldown consumption if reservation release fails. WHEN a start fails these preflight checks THE SYSTEM SHALL reject without reading/writing cooldown state or checking optional opponent counts. Payment/start failure after valid preflight still releases the durable reservation conservatively.

Bank API review: fetched BadgersMC/enthusia-network main 559bfabc2187ab796a3be889f032383a8041f819 pins LumaGuilds a15b244e8a294bf18e6dedf722462edf9faa40ae. Inspected real systemBankDeposit/Withdraw delegation and Int amount bound at that pin. The older local submodule checkout is not the current network source. No runtime transaction is claimed.

Review fixes verified: three focused regressions failed behaviorally before the fixes. Contributor dispatch failure is isolated per recipient without retry; preflight now runs before optional opponent counts and durable cooldown reservations for paid/GUI and flare requests. Permissions, locks and active-event precedence remain explicit. Java 21 clean test build passed 185 tests, zero failures/errors/skips. Feature unmerged local test JAR SHA-256: `4fec8eec90911cbb4ff716e620324c2bbf5e965f45974524d8f53ef01fd8ef2f`. No GUI or visual behavior changed in this review follow-up.

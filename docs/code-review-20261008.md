# KOTH manual code review

Reviewed PR #1 at `65ed709c8b7800300ca5955d079cdeb2d13ce6c2` and PR #2 at `4805ec6acddc759244792c2e1a2dc8fd8a4e1173` against current main `f80adebb10f5be991abe20de41e505ebceb3d5a5`. Fix commit: `1635aa0fcd379c1d0c640998b361ada38cd32775`.

## Findings fixed

- **REV-001 / P2: one contributor exception skipped subsequent rewards.** In `KothService.executeRewardCommand`, dispatch failures were caught outside the whole contributor loop. A command throwing for the first eligible player prevented the remaining eligible players receiving that command. Each recipient dispatch now has an independent failure boundary; it logs and continues without retrying an ambiguous operation. Regression proves the failed command runs once and the next eligible recipient receives it.
- **REV-002 / P2: ineligible starts reserved cooldown state.** Player start guards ran before source permissions, feature flags, lock and active-event checks. Busy/unauthorized requests could perform two synchronous disk writes; a release failure could unnecessarily retain a cooldown. Paid/GUI and flare preflight now precedes fairness guards. Invalid starts never touch cooldown storage. Valid starts still reserve before charging or consuming flares, and failed starts retain existing release/refund safeguards. Regressions cover busy event with a failing release and permission rejection across player command, GUI and flare paths.

## Review coverage and verification

Reviewed team scoring/threshold boundaries and final-tick accounting; winning-guild revalidation; legacy reward expansion; start sources, permissions, feature flags, locks, payment journaling and rollback; cooldown read/write/corruption handling; active/private keep-inventory/XP boundaries; notification opt-out, current WorldGuard region audience, global start/winner delivery and rejoin handling; configuration defaults and shaded API boundaries.

Three new regressions failed behaviorally before fixes. Java 21/Paper API 1.21.11 `clean test build` passed **185 tests** with zero failures/errors/skips after fixes.

A separate local temporary merge with bank PR #1 passed **194 tests**, zero failures/errors/skips. Combined tree: `310c0e8e1aaed87ff9be9d2c40a5e33dfab78fe1`. Combined unmerged local test JAR SHA-256: `ecc0c329b68b1259272015736056afdcaf763d5504b2316ac596948bf7bba0ce`. ZIP inspection found no duplicate entries or bundled Bukkit/Paper/Vault/LumaGuilds API classes. The temporary merge was aborted after preserving evidence; neither source PR was merged.

No additional code finding identified in bank PR #1. Its direct system-bank dispatch, exact positive integral Int-bound conversion and fail-closed old-provider handling match the real LumaGuilds API and implementation at `a15b244e8a294bf18e6dedf722462edf9faa40ae`. Fetched network main `559bfabc2187ab796a3be889f032383a8041f819` pins that companion commit. This source/API check does not prove a running server uses that version or prove an actual guild transaction.

## Remaining acceptance limits

This is a manual agent review, not independent approval or a hosted build pass. CodeRabbit reports automatic review skipped. Exact-head hosted build checks and independent review remain separate. Project-local EARS/state helpers are absent; SPEAR requirement, red/green and evidence tracking is manual.

Per user instruction, TEST-server validation is deferred. Real WorldGuard geometry, death-event interactions/drops/XP, live guild bank balances and cross-plugin/client behavior remain unverified. No server upload, activation, restart or production change occurred. Merge and canonical network build/pin verification remain release gates.

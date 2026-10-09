# KOTH delivery checklist

Reconciled 2026-10-09 against fetched canonical main `d0552119f3a5ea01fdb094189c9a32df290e6081` and GitHub merged PR records. Checked items establish source delivery only. Server/client acceptance is separate. Earlier verification documents retain historical branch/test observations.

## Completed source work

- [x] BANK-001: system guild-bank dispatch and conservative transactions; [PR #1](https://github.com/FainNeito/EnthusiaKOTH/pull/1) merged. Real balances remain a TEST gate.
- [x] KOTH-101..106: notification toggle, global start/winner, regional spawn/warzone/market captures, 10% contributors, durable cooldown/team gates and inventory/XP preservation; [PR #2](https://github.com/FainNeito/EnthusiaKOTH/pull/2) merged.
- [x] SETUP-001..006: disabled drafts, editor/wand/WorldGuard selection, private preview and atomic save; [PR #3](https://github.com/FainNeito/EnthusiaKOTH/pull/3) merged.
- [x] EXP-001..008: schedule/reward/display editors, information/teleport, window standings, SCORE, lifecycle API and bounded concurrent events; [PR #4](https://github.com/FainNeito/EnthusiaKOTH/pull/4) merged.
- [x] ABUSE-001..007: alliance accounting, frozen membership, opposition/account gates, bounded recipients, durable repeated opponents and audit; [PR #5](https://github.com/FainNeito/EnthusiaKOTH/pull/5) merged.
- [x] Combat follows MaceGuard's current warzone rotation; actual region coverage remains a TEST gate.
- [x] PROG-001..008: verified challenges, fixed event pool, immutable packages, durable claims, lifetime relics, audit/readiness/reconciliation and companion projection; [PR #6](https://github.com/FainNeito/EnthusiaKOTH/pull/6) merged.
- [x] Actual Guilds artifact contract suite exists: 16 provider cases and 296 ordinary passing cases (one provider-only skip) recorded in delivery evidence.
- [x] Source manual reviews and hosted build gates completed for merged features. CodeRabbit skipped automatic review; this is not independent approval.
- [x] Alliance API [LumaGuilds #220](https://github.com/BadgersMC/LumaGuilds/pull/220) and projection [Advancements #21](https://github.com/BadgersMC/EnthusiaAdvancements/pull/21) merged.
- [x] Advancements [#22](https://github.com/BadgersMC/EnthusiaAdvancements/pull/22) retires AxKoth. Old capture tokens cannot authorize verified progress/rewards.
- [x] Prepare inactive challenge/reward candidate and provider mapping: [challenge-reward-plan.md](challenge-reward-plan.md), [progression-test.yml](configuration/progression-test.yml). Values remain drafts until TEST; no installed tags/templates claimed.

## Remaining integration and configuration

- [ ] Merge owning [network PR #171](https://github.com/BadgersMC/enthusia-network/pull/171) after current-head checks; account lacks merge access. Public Build 37882730858 passed at 843cfbb; documentation-only 651ec31 rerun 37884305013 also passed. Trusted private Display-inclusive build remains a release gate.
- [ ] Reconcile old server config by key. Preserve real arenas/assets and active Advancements pilot; disable TEST Discord guild-role writes and legacy payouts before loading candidates.
- [ ] Accept arena identities/geometry and attainable challenge requirements; draft relics require four distinct arenas.
- [ ] Calibrate opposition, age/playtime, repeated opponents, scoring floor and optional roster/start gates on TEST. Protection/progression remain disabled until then.
- [ ] Select event currency/item budgets and balanced package definitions; verify actual guild XP command/amount.
- [ ] Register/check tags, prepare genuine FainNeito signed/tracked sword and helmet LoreItems templates, and accept Java/Bedrock helmet visuals.
- [ ] Recheck canonical source, network pins, clean artifact versions/hashes and runtime APIs before activation.

## Deferred TEST and player acceptance

- [ ] Run [test-acceptance.md](test-acceptance.md): independent/allied teams, brief/uncontested opposition, roster/relation edits, alternate aliases and repeated wins.
- [ ] Verify contribution boundaries, fixed aggregate payouts, package ownership, simultaneous relic reservations and no private/admin/advancement bypass.
- [ ] Verify real bank/currency/XP balances, queue versus delivery, offline/full inventory, restart/disk failures and ambiguous payout reconciliation.
- [ ] Verify death/drops/XP, notifications/rejoin, region/rotation coverage, concurrency, schedules and Java/Bedrock menus/items.
- [ ] Record isolated TEST storage, backups and rollback; never import TEST ownership or claims into production.
- [ ] Obtain production activation authorization separately; source merge/build is not live acceptance.

## Scope decisions

Alliance anti-abuse, selectable packages and guild recognition are approved and implemented; production values remain pending. Cooperative allied capture as a gameplay mode, database replacement and arbitrary leaderboard/web resets are not implemented or approved by this checklist. Kill tracker counts remain cosmetic. Historical AxKoth comparisons remain reference material, not runtime dependencies.

Evidence: priorities-verification.md, arena-setup-verification.md, expansion-verification.md, anti-abuse-verification.md, companion-api-verification.md, progression-verification.md, exclusive-rewards.md.

## Match integrity source work (INT-001..007)

- [x] Implement contest evidence, bounded win-trading reports, audited durable reward/challenge holds, UTC event currency/item caps and stable physical arena identities.
- [x] Implement opt-in start readiness and private own-result/claim/challenge breakdown. New enforcement disabled/zero until TEST.
- [x] Local/full/provider verification and manual source review; [PR #8](https://github.com/FainNeito/EnthusiaKOTH/pull/8) Build 37891506702 passed at source head f6430df. Final documentation head checks must pass before merge; see match-integrity-verification.md.
- [ ] Add a read-only LoreItems definition query before enabling enforced readiness with item definitions; current unsupported capability blocks starts rather than fabricating verification.
- [ ] TEST calibration: contest boundaries, holds/restart/review permissions, budgets across allied aliases/UTC rollover, renamed/copied/displaced hills, delayed preflight failure/refunds and Java/Bedrock result menus. No server testing performed here.
- [ ] Update network PR #171 to the merged integrity source and verify the owning combined build; maintainer merge/trusted private Display build remain release gates.

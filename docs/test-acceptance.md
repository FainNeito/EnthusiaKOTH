# KOTH TEST acceptance

User authorized review, dependency-order merges and TEST preparation on October 8.
Production remains read-only. TEST is Bloom SMP Test Server `5d109214`, observed
running Paper 26.2 build 128 with zero connected players. Existing TEST KOTH is
0.1.0; schedules and Discord delivery are disabled and guild money is zero.
Existing capture/moving coordinates are configuration examples, not accepted
arena geometry. Do not start a rewarded event before a player checks the areas.

## Source and runtime gates

- Merge setup, expansion, anti-abuse and progression source in dependency order.
  Preserve current-main changes and inspect exact-head hosted results/findings.
- Merge the reviewed LumaGuilds alliance API and Advancements companion changes.
  Build from their canonical merged commits and update the owning network pins.
- Record versions, commits and SHA-256 for every candidate; preserve prior TEST
  jars/configuration outside the active plugin directory. Install only one
  Advancements profile. TEST currently has the display-only pilot profile.
- Inspect TEST Guilds, economy, Tags and LoreItems storage before activation;
  shared production databases or Discord writers must not be exercised by tests.
  KOTH challenge/claim data must use a TEST-only database. Never copy TEST
  ownership, claims or match history into production.
- Keep reward protection/progression off and template/tag IDs blank until provider
  loading and readiness pass. Verify loaded versions in a fresh startup log.
  A successful startup does not establish multiplayer/client acceptance.

## Required scenarios

Use consenting players in at least three independent guilds plus an allied guild.
Record event UUID, source, arena, participant scoring, accepted/rejected audit,
claim operation IDs and provider balances/deliveries for each run.

| Case | Expected result |
| --- | --- |
| Uncontested win, brief opposing entry, allied/indirectly allied opposition | No verified progression or protected reward if configured opposition fails |
| Guild leave/rejoin and alliance add/remove/revert between ticks | Immediate exclusion; relation changes cannot recover challenge credit |
| Account age/playtime/roster gates, including unknown provider/history | No invented eligibility; unknown required evidence fails closed |
| Same opposing side and alternating guild aliases across restart | Durable repeated-opponent rules and conservative side identity remain effective |
| Multiple winning contributors, including below 10 percent | Only qualifying contributors; summed money/items stay within one event pool |
| Package double-click, stale menu, foreign menu owner and reconnect | One immutable server-owned package choice; no extra claims |
| Simultaneous completion of an exclusive challenge on separate matches | One durable recipient for each reward, independently |
| Full inventory, offline delivery, timeout/retry and restart | LoreItems retains the original operation ID; acceptance is queueing, not delivery |
| Economy/XP exception or crash after dispatch reservation | REVIEW persists; no automatic non-idempotent replay |
| Private/admin event, synthetic lifecycle event, ordinary advancement trigger/admin grant | No authoritative challenge rewards or duplicate advancement payout |
| MaceGuard rotation changes during a match | Current warzone rotation governs combat throughout; verify region coverage |
| Notification observers in spawn, warzone, market and outside them | Start/winner global; capture updates restricted to the three configured regions |
| Keep-inventory death, XP, disconnect/rejoin, Java and Bedrock menus | Actual player/client evidence required; no unit-test substitution |

## Draft challenge and reward preparation

The existing five-metric challenges are drafts, still disabled: Contender
5 wins/3 opposing sides/2 arenas/3 days/1,800 scoring seconds; Veteran
25/10/3/14/10,800; Sovereign Blade 100/25/4/30/43,200; Sovereign Crown
150/35/4/45/64,800. All five criteria and accepted-match eligibility must hold.
Calibrate against real contested TEST matches before choosing production values.
Use disposable TEST definitions and separate TEST ownership for reward trials.

Earned tag candidates are `koth_contender`, `koth_veteran`, `koth_sovereign_blade`
and `koth_sovereign_crown`; register/verify actual EnthusiaTags definitions before
assigning keys. These names are proposals, not installed or granted tags.
Choose one balanced money/item budget for each event; alternate package item
definitions must undergo value review. Guild XP remains empty until an actual
supported command and its observed amount are verified on isolated TEST data.

Prepare the compatible maximum-enchantment sword and helmet described in
`exclusive-rewards.md`. FainNeito must hold the sword, genuinely sign and confirm
it, and attach the player-kill tracker before its held snapshot becomes a LoreItems
definition. Cosmetic kill counts never authorize advancements. The physical
helmet model/texture and Java/Bedrock acceptance require the actual resource pack.
Do not substitute fake signer metadata or claim artwork is installed from a draft.

## Acceptance record

Local source evidence: 296 passing KOTH cases plus one explicit provider-only skip;
16 actual-provider cases pass against the refreshed LumaGuilds artifact. Guilds
clean verification discovered 1,608 cases, five skips, zero failures/errors.
Full Advancements local renderer tests pass after the polling quality refinement.
Hosted results, merged commits and canonical build hashes are recorded in the
delivery bundle. Multiplayer, genuine signature, model rendering and real
provider delivery remain pending until they are observed on TEST.

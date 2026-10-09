# AxKoth reference and EnthusiaKOTH opportunities

Reference artifact: user-supplied AxKoth **2.27.0** JAR, SHA-256
`e4598c2146bbeeea2ad0306eec7df91a96a41afc4131b6f4c95cdad76568c869`.
Compared with EnthusiaKOTH merged main `1a3c75f` and this setup branch.
Static inspection of bundled configurations/messages, class inventory and JVM
bytecode for Create/Wand/WandListeners/Editor. No third-party code, artwork or
libraries copied. Static evidence establishes available surfaces, not measured
performance, successful runtime behavior or licensing permission to copy code.
Official feature overview: https://docs.artillex-studios.com/axkoth.html.

## Setup experience inspected

AxKoth provides a tagged wand, left/right corner selection, same-world/missing
selection validation, named creation, redefine, teleport/info/list commands and
mode-specific inventory editors. Its bundled editor notice says reload is needed
to apply edits. Its selected region is the capture zone itself. Enthusia needs
both a protected arena boundary and a smaller circular objective; conflating
these would change existing combat/death semantics.

This implementation adopts the interaction pattern: a staff arena list, private
chat naming, disabled creation, tagged blaze-rod boundary wand, native boundary
or existing WorldGuard region picker, stand-here center, numeric/toggle editor,
private geometry preview, readiness feedback and explicit atomic Save. Native
boundary height can be extended to full world height with one labeled action.
Preview draws a static center circle and native cuboid; it does not animate a
moving path or render a named WorldGuard polygon. Save refuses while an event or
queued start exists so configuration reload cannot interrupt a match.

## Initial comparison before the expansion request

| AxKoth surface evidenced in supplied JAR | Enthusia status | Benefit / recommendation |
|---|---|---|
| Wand, create/redefine, mode editor | Existing commands required YAML IDs; added guided drafts here | Highest setup value; implementation in this PR |
| Scheduler editors, Cron entries | Existing daily/rotating/per-arena scheduling; no staff scheduler editor | Next UX slice: show upcoming occurrences and timezone; retain existing schedule model |
| Reward item/command editor | Existing fixed/chance commands and Vault/guild-bank rewards, contributor recipients | Later separate reward preview/editor; keep 10% contribution and financial safeguards; do not copy Ax winner policy |
| Information and teleport commands | Existing status/objective placeholders; private particle preview added | Add richer arena info and optional staff teleport later; safety/teleport integration needed |
| CAPTURE and SCORE modes | Capture, moving and conquest implemented | Score is a distinct proposed mode, not equivalent to conquest; needs separate scoring/winner specification |
| Multiple simultaneous KOTH instances | Single active event plus durable queue | Larger architecture change: arena/event-specific listeners, displays, persistence and settlement; defer |
| Bossbar, hologram and scoreboard editors | Existing bossbar/actionbar and objective marker/border lifecycle | Optional display editor useful; scoreboard needs coexistence with current server scoreboard |
| Broadcast/display distance controls | Region audience is already spawn/warzone/market, global start/winner, opt-out | Keep approved regional semantics; no replacement with distance broadcasts |
| Arbitrary leaderboard time windows | Existing persistent wins and leaderboards | Seasonal/daily views useful; require retained event timestamps and reset policy |
| Start/end/capture lifecycle commands | Completion reward commands present | Useful integration hooks; prefer bounded, documented API events before arbitrary repeated commands |
| Public API event classes | No equivalent public event package observed | Good future integration surface for quests/holidays; needs stable payload and cancellation contracts |
| Team integration package | LumaGuilds-specific ownership already implemented | Keep authoritative guild identity; alliance wins remain undecided |
| Activator items | Flares already implemented | Already covered; editor could show grant/start instructions later |
| Minimum players | Distinct eligible team gates and starter cooldown already implemented | Ours is more aligned with requested abuse controls; raw player count is not an upgrade |
| Discord lifecycle webhook configuration | Existing Discord webhook service | Configurable rich formatting could help; review privacy, rate limits and failed-delivery behavior first |
| BattlePass / combat / sitting hooks | Existing combat restrictions and private-event isolation | Evaluate actual installed companion needs; do not introduce unused optional dependencies |
| H2/MySQL/PostgreSQL/SQLite adapters | Current Hikari/SQLite persistence | Database portability only if operationally required; migration and transaction proof needed |
| Advertised asynchronous/modular runtime | Not a benchmark | Profile our actual TEST event before performance changes; Bukkit work remains on main thread |

## Original recommendation before expansion approval

1. TEST/client acceptance of guided setup and existing gameplay/reward rules.
2. Schedule editor with next-run preview, timezone and enabled/disabled visibility.
3. Reward editor with recipient/payout preview and conservative validation.
4. Stable lifecycle API and optional display configuration.

Score mode, simultaneous events, alliances, arbitrary leaderboard resets and
database changes remain proposals. The setup request does not approve new payout
rates, automated Discord posts, production deployment or importing AxKoth files.

## Expansion approved and implemented October 8

The subsequent request to add all eight recommendations supersedes the earlier
proposal status for editors, arena info/teleport, optional displays, dated
leaderboards, SCORE, lifecycle API and concurrency. All eight now have source
and local regression evidence on the expansion branch stacked on setup PR #3.
See [expansion evidence](expansion-verification.md) and [API contract](lifecycle-api.md).
The reward editor supports commands (including item-grant commands), chance
commands and existing solo/guild money; it does not import AxKoth item data or
copy its payout policy. Schedules retain our daily/rotation model rather than
introducing cron. Alliance policy, arbitrary resets and database replacement
remain unapproved. TEST/client checks remain deferred; implementation is not a
production or measured-performance claim.
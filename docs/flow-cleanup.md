# KOTH flow cleanup (SPEAR)

Base: canonical main fea6ebd, fetched 2026-10-10. No local EARS/state helpers exist in KOTH; this is the manual requirement/task/evidence record.

- FLOW-001: Staff SHALL edit one retained arena draft through Area, Rules and Review pages, with explicit save/discard and concise guidance. Hidden controls SHALL NOT react to clicks. Existing configuration, atomic/stale/busy save checks and particle ownership SHALL remain intact.
- FLOW-002: The tagged selection tool SHALL be an enchanted wooden axe. Ordinary axes SHALL remain untouched; selection SHALL NOT break blocks, drop the tool or overwrite occupied inventory slots.
- FLOW-003: EnthusiaTags /rewards SHALL expose KOTH challenges, earned claims and match results through the enabled KOTH menu provider. Tags SHALL NOT duplicate KOTH claim storage or authorize payouts. Missing/disabled/incompatible providers SHALL fail clearly, including reload and queued click races.
- FLOW-004: Player screens SHALL include consistent return paths, meaningful empty states and readable challenge criteria. Administrator tools SHALL retain permission checks. Existing command aliases SHALL remain usable; default help SHALL show a small set of primary entry points.

Proof plan: record baseline source observations (all editor controls on one page; blaze rod; separate challenges/claims commands; no Tags portal). Add focused regressions for page action boundaries, provider ownership/unavailability and navigation permission/lifecycle. No fabricated historical red/green evidence. Preserve challenge/payout/account/alliance policies and inactive thresholds.

Architecture: presentation and optional ServicesManager adapter only; domain/persistence remain authoritative and unchanged. The menu API opens only the caller's own read/claim UI; no grant, arbitrary command or player-supplied payout payload. Tags uses the real enabled provider registration without bundling KOTH API classes.

Verification: Java 21 clean test/shadowJar passed 331 cases (329 pass; two existing provider-only skips). Focused regressions exercise hidden timing/rule/wand actions, ownership, bottom inventory clicks, next-tick menu identity and permission revocation. Existing tagged/ordinary-tool and block/drop guards still pass. This is brownfield source proof, not a fabricated historical red/green cycle.

The Tags provider test first failed its enabled-owner assertion (false rather than true), then passed; Tags full verification passes 256 cases with no skips/failures, plus 12 Node tooling and 10 immutable-companion tests. The final shaded SQLite probe passed. KothMenuArtifactProbe loads the real built KOTH API and Tags JAR: signature/absence of bundled mirror, real owner-classloader lookup, caller UUID/page, disabled owner and removed registration all passed.

Manual architecture/review: only Bukkit presentation, command routing and optional owner-registered navigation adapter changed. Domain, progression SQL, eligibility, reward budgets, exclusive reservations, account/alliance and payout transitions are unchanged. Existing service shutdown unregisters the menu API. New menus defer transactions and recheck ownership/lifecycle/permissions. Save/reload failure retains the existing explicit failure message; geometry/settings drafts cannot silently replace each other.

Interactive preview: https://enthusia-koth-setup-preview.awareyak.chatgpt.site/cleanup.html (owner-private sign-in). Local 390x844 browser checks verified no horizontal overflow, at least 48px controls, setup page isolation, selection/save/review, rewards/challenges and return paths. This is a schematic using example state, not actual game rendering. Mobile owner authentication is not independently confirmed.

No server deployment/restart is authorized by this cleanup request; Java/Bedrock visual acceptance and physical reward templates remain pending TEST. Both source updates are needed for the Tags portal; old KOTH command aliases remain available during staggered upgrades.

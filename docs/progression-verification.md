# KOTH progression and operations (SPEAR)

Approved: match audit UI, shared event reward pool, durable claims, eligibility
explanations, readiness checks, suspicious-pattern reports, membership/alliance
history, reward packages/guild recognition, and an EnthusiaAdvancements KOTH tree.
Sword and helmet ownership is one recipient EVER per reward, not per season.
All progression and reward defaults remain disabled pending TEST calibration.

Base inspected: canonical main 1a3c75f; this isolated branch extends anti-abuse
PR #5. Existing worktrees and dirty Tags work were preserved. Project has no
EARS/state helpers; this requirement/evidence record is maintained manually.

- PROG-001: Only durably accepted public protected match outcomes SHALL advance
  challenges; private/admin events, raw lifecycle events, commands and advancement
  display state SHALL NOT authorize claims. Participants SHALL meet the frozen
  contribution, account and opponent policy; guild-wide online credit is forbidden.
- PROG-002: One configurable reward pool SHALL be divided across contributors,
  with integer remainder handling and no reward multiplication by account count.
  Package choice SHALL be durable and immutable after submission.
- PROG-003: Claims SHALL retain operation IDs across reconnect/reload/restart;
  LoreItems queue acceptance SHALL NOT be reported as physical inventory delivery.
  Uncertain non-idempotent payouts SHALL enter manual review, never blind replay.
- PROG-004: Exclusive ownership SHALL be reserved transactionally with challenge
  completion and SHALL NOT reset with seasons/config reload or lost items.
- PROG-005: Staff SHALL inspect matches, activity, exclusions, relation changes,
  payout status and non-punitive pattern flags. Players SHALL see eligibility and
  claim status; readiness SHALL expose unknown provider/region capabilities.
- PROG-006: Public guild member events SHALL invalidate active eligibility even
  if a player changes guild between scoring ticks. Observed changes SHALL persist.
- PROG-007: EnthusiaAdvancements SHALL project authoritative challenge progress,
  suppress its own rewards for provider-owned requirements, and reject admin grants
  for these nodes. Tags SHALL be granted only from durable earned claims.
- PROG-008: Signed tracked reward templates SHALL use EnthusiaSignature's actual
  metadata through LoreItems held-item definitions. No fake lore signature or
  invented signer UUID SHALL replace a real signature. Exact helmet texture,
  exclusive-item templates, XP amounts and enchantment choices remain TEST gates.

Baseline: completion hooks precede reward settlement; AxKOTH advancement listener
credits all online guild members; no durable challenge ownership or contributor
pool exists. Existing 282 tests are regression evidence, not a historical red run.
Companion source APIs are inspected before integration; production stays read-only.

Refine evidence (2026-10-08): 296 eKOTH tests pass; 14 new persistence/engine
regressions cover accepted ledger prerequisite, private/admin/relation exclusion,
fixed-pool totals, immutable package ownership, restart IDs, no money replay,
lifetime concurrent reservation, allied aliases, roster age, disabled policy,
blank exclusive templates and staff reconciliation. Manual review retains frozen
reward policy across completion callbacks so cleanup cannot re-enable legacy
payouts. Public guild join/remove/relation events are compile-mirrored and excluded
from the artifact; current source getter descriptors were checked.
Full Advancements renderer passes 50 tests; its display-only pilot clean Maven
verification passes 40 tests. Optional public LoreItems/Tags/Advancements contracts
are mirrored and excluded from the shaded artifact; exact server binaries are a
TEST gate. No new source-only claim is treated as deployment evidence.

Interactive preview: https://enthusia-koth-setup-preview.awareyak.chatgpt.site/progression.html
Owner-private ChatGPT login. Simulated browser visuals; in-game acceptance deferred.
Hosted source-only fallback used because bundled Sites packaging requires absent
bash; Sites deployment succeeded from pushed source e53a8cb. No server action.

# Guided arena setup: requirements and evidence

Base: canonical `main` at 1a3c75fcd9259d70a584b3559ec6898150af2225.
SPEAR: spec -> prove -> engine -> arch -> refine. No repository EARS validator
or state helper is present; this is the manual requirement/evidence record.

- SETUP-001: Staff SHALL create disabled, unpaid, unscheduled arena drafts from
  two same-world block corners using a tagged selection wand, without YAML edits.
- SETUP-002: Staff SHALL edit center, radius, duration, capture time, leave
  behavior and inventory/XP policy through an inventory editor. Save SHALL be
  explicit; closing the editor SHALL retain an unsaved session, Cancel/quit SHALL
  discard it. Drafts SHALL NOT alter events, protections, rewards or schedules.
- SETUP-003: Save SHALL reject invalid IDs, duplicate creates, unloaded worlds,
  missing corners, nonfinite/invalid geometry and capture objectives outside the
  native boundary. Existing WorldGuard bindings SHALL be preserved until staff
  explicitly select a replacement native boundary. Named bindings SHALL resolve.
- SETUP-004: Save SHALL refuse while any event or queued start exists, detect
  stale configuration, persist atomically before applying, and preserve unrelated
  options/rewards/permissions. Failed persistence SHALL NOT apply a draft.
- SETUP-005: Every editor/wand action SHALL recheck admin permission and ownership;
  clicks/drags SHALL NOT move editor items. Tagged wands SHALL NOT break/use blocks
  and SHALL NOT overwrite an occupied inventory slot.
- SETUP-006: Staff SHALL preview draft geometry privately and see actionable
  readiness errors before Save. No gameplay start/teleport is implied by preview.

Reference: supplied AxKoth 2.27.0 JAR. Static bytecode inspection of Create, Wand,
WandListeners and Editor; bundled capture/score configurations and messages.
The inspected create flow validates both corners and matching worlds; its editor
message requires a reload to apply. No AxKoth classes/assets/source are copied.
Official feature reference: https://docs.artillex-studios.com/axkoth.html.

## Proof, implementation, architecture and refinement

The prior main command surface has no arena creation/editor/wand; existing ID
definitions require configuration edits. Existing arena mutation methods call
the full reload lifecycle, which shuts down an active event. These are source
observations, not fabricated historical red/green test runs.

New application tests cover staged/nonpersistent creation, explicit save,
same-world corners, inclusive upper blocks, circle/vertical containment,
nonfinite geometry, timing, missing worlds/regions, center outside named region,
busy events/queues, stale snapshots, legacy mixed-case IDs and option preservation.
Storage tests cover reload persistence, unknown keys/rewards/schedules/audience,
zero-payout creation, failed atomic replacement cleanup, stale writes and invalid
YAML. UI tests cover permission/ownership checks, permission revocation before
next-tick execution, click/drag cancellation, stale/ordinary wand isolation,
block breaking and dropped tools. All readiness errors have language entries.

The first expanded UI run failed two tests because the fixture did not stub the
Paper 1.21.11 `Plugin.namespace()` method used by NamespacedKey. Correcting the
fixture resolved these; this is not claimed as a product red/green regression.
Manual review additionally preserved existing mixed-case IDs and invalidated
queued chat/selection callbacks when a draft is cancelled.

Final JDK21/Paper API 1.21.11 `clean test build`: **223 tests**, zero
failures/errors/skips. `git diff --check` passed. No companion API change; existing
LumaGuilds bank contracts and reward policy remain covered by the full suite.
New draft geometry/policy lives in application; Bukkit inventory, chat, wand,
WorldGuard lookup and rendering live in infrastructure. Atomic YAML replacement
is infrastructure; runtime apply occurs only after successful persistence.
Existing setup commands now also refuse active/queued events. Unsupported atomic
rename fails closed rather than downgrading to partial replacement.

Unmerged/local test artifact: `EnthusiaKOTH-0.3.0-SNAPSHOT-all.jar`.
SHA-256: `d78bcf865785bce7c09766f55cedb68056300a70383cdc7f04b86cae93681704`.

Interactive simulation: https://enthusia-koth-setup-preview.awareyak.chatgpt.site
(owner-private; ChatGPT sign-in required). Sites deployment succeeded. Browser
QA exercised name/create, corner selection, height/center readiness, busy save
refusal and successful simulated Save. At 390px and 320px viewport widths there
was no horizontal overflow; minimum button targets were 44x44px. Hosted HTTPS
sign-in screen was reached at mobile width; authenticated hosted use and an
actual phone were not tested in this browser session. Static publication used
the supported hosted-build fallback because local Sites packaging requires
`bash`, absent on this Windows host. Source commit is
`f03653054a233d14e5e41ba89445d71903f82ef9` in the separate private preview Site.

Limits: private preview is a web simulation, not Minecraft GUI/client proof.
Named WorldGuard geometry is authoritative; readiness checks its existence and
center inclusion, not every point of an arbitrary polygon. Native boundaries
validate full objective extent. Preview shows a static circle/native cuboid;
no named polygon outline or moving path animation. Rewards/schedules/advanced
family rules are deliberately outside this geometry/basic-rule editor.

Local checks are distinct from hosted review and TEST/client acceptance, which
remain pending. No server upload, activation, restart or source merge occurred.

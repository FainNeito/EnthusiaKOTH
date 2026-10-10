# TEST startup ordering

STARTUP-003: When KOTH, Tags and Advancements load, KOTH SHALL retain its optional Tags integration and AFTER ordering for Advancements without requiring Tags to enable before KOTH.

Evidence: TEST reports `KOTH -> Tags -> Advancements -> KOTH`. The Tags integration is checked on menu invocation (`getPlugin("EnthusiaTags")?.isEnabled`) and runs the rewards command; it does not require Tags during KOTH enable. Paper permits `load: OMIT` while retaining `join-classpath`: https://docs.papermc.io/paper/dev/getting-started/paper-plugins/. Current main 385a8b44a389e53c2c51bd55a9ea84d4fe406864 was fetched in an isolated worktree and combined with the existing TEST shared-menu development head c94094fe9d0f6759dcb2b5e141ce8902168d0f0a. The merge had no conflicts; TEST-only menus remain present.

Infrastructure metadata task: behavior engine changes do not apply. A metadata regression plus full suite and TEST startup establish the relevant proof. No project-local EARS/state helpers exist; this manual requirement/task/evidence record covers SPEAR. Source PR, clean artifact/hash, rollback, download-back verification and successful TEST startup are refine gates. No production activation or new GUI behavior is authorized by this task.

Local proof: the metadata regression failed before the edit under the canonical Gradle 8.10.2 wrapper. Full check/shadowJar passed: 356 cases, zero failures/errors, two optional companion skips. The separate actual provider contracts passed 24 cases with no skips; the changing TEST LoreItems provider is rechecked in the operational receipt.

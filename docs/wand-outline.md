# Wand selection outline (SPEAR)

Base: fetched canonical main b6f4a507, 2026-10-09. The wand previously produced chat only; particles required the separate timed Preview button. No project-local EARS/state helpers exist; this manual record covers the change.

- WAND-001: After the first corner is selected, the owner SHALL see that entire block outlined and a live proposed cuboid when aiming at a second block within six blocks. Bounds SHALL include the complete selected blocks.
- WAND-002: After the second corner, the owner SHALL see the selected draft cuboid automatically until saving, discarding, quitting, changing world, losing permission or shutdown. Returning to selection SHALL replace its previous outline.
- WAND-003: Rendering SHALL use owner-only particles, at most 200 distinct edge points per cuboid, twice per second, within 64 blocks of the viewer. It SHALL not write terrain/configuration, load distant chunks or change capture geometry.

Prove: source tracing confirms no wand rendering; geometry tests cover reversed, single-block and very large selections, bounds and unique point limits. Engine/arch: bounded geometry in an infrastructure renderer; controller owns task lifecycle, while draft/save policy remains unchanged. Refine: focused/full checks, manual lifecycle review and responsive simulation; in-game Java/Bedrock particle visibility remains a later TEST gate. No deployment authorized.

Verification: Java 21 clean test/shadowJar passed; after distance culling and cancelling old timed previews on wand activation, the full suite and shadow JAR passed again. 327 total, 325 passed, two provider-only skips, zero failures/errors. Four new geometry regressions cover block-inclusive bounds, edge-only/reversed selection, full world height/large size with unique bounded points, and invalid geometry. Manual review traced task replacement and owner-only rendering, cleanup on save/cancel/quit/world/permission/shutdown, and dynamic draft bounds without persistence or capture-policy changes. Existing Paper compile profile accepts the DUST API. No real server/client test or deployment occurred.

Interactive simulation: https://enthusia-koth-setup-preview.awareyak.chatgpt.site/wand.html (owner-private; ChatGPT sign-in required). Browser QA at 390x844 found scrollWidth 375; first/aim/complete/save/world-change states verified. This illustrates the implemented particles without connecting to Minecraft. Authenticated mobile access and actual client particle settings remain unverified.

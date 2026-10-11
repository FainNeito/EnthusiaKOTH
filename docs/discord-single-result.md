# One Discord result message

Base: fetched canonical main d9bb1357193cad3e853f32f19f49bfca34dd5a78, isolated codex/koth-single-terminal-message.

- HOOK-008: When an existing live message is successfully edited to the terminal result, the system shall not post a duplicate result announcement.
- HOOK-009: When no editable live message exists or its final edit fails permanently or exhausts retries, the system shall retain the enabled terminal announcement as a fallback. Disabled terminal announcements shall still finalize existing live messages without a fallback post.

SPEAR evidence is manual; no project EARS/state helper exists. Baseline and player TEST report confirm final edit plus separate result post currently duplicate winner output. Acceptance covers winner, cancellation, no-winner, concurrent ownership, rate-limit retry, missing/deleted status and bounded failure fallback. No reward, gameplay, role-ping or private-event behavior changes. Server installation/restart and real Discord acceptance of this correction remain separate.

Proof: the updated lifecycle regression suite failed three tests against the fetched baseline (winner, concurrent no-winner ownership and in-flight cancellation). After the one-line adapter fix, canonical `test shadowJar --no-daemon` passed: 378 tests, zero failures/errors, two existing skips (376 passed). New cases cover absent status, rejected final edits, rate-limit recovery/exhaustion and disabled fallback. Manual source review verified suppression occurs only after confirmed HTTP success, retaining retry limits, queue ownership and destination guards. No domain/application contracts changed.

Interactive preview: https://enthusia-koth-setup-preview.awareyak.chatgpt.site/discord.html (owner sign-in required). Start stays separate; the live message becomes the result. Disable live status to inspect the fallback. Browser simulation does not send Discord messages or prove native server behavior. Uncertain network outcomes may still produce a duplicate fallback when the final edit succeeded remotely but its response was lost; this is not a durable exactly-once outbox.

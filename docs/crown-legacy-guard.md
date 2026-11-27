# Crown legacy reward hold

- WHEN an eligible player completes the legacy `sovereign_crown` challenge,
  THE SYSTEM SHALL retain any separately configured tag but SHALL NOT reserve a
  legacy physical Crown owner or create a generic LoreItems Crown relic claim.
- WHEN an existing pending legacy Crown relic claim is redeemed, THE SYSTEM SHALL
  retain it and SHALL NOT transition it or submit it to LoreItems.
- The hold is keyed to the existing immutable challenge ID and its relic claim
  form. Other challenges and claims remain unchanged. The hold does not cancel
  already accepted provider deliveries or inspect existing physical items.
- This change does not award, exchange, transfer or recover the Crown of Sorrow.
  Those operations require a separate verified custody implementation. It does
  not imply physical uniqueness. That implementation, which keeps this hold in
  place, is tracked separately in `crown-quest-event-design.md`.

Tests exercise the pending-claim boundary and new challenge-award behavior.
The repository has no project-local EARS or SPEAR state helpers; this file records
the scoped requirements and evidence. TEST and live provider behavior are not
established by local tests.

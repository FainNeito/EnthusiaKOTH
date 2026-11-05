# Capture and victory sounds

## Requirements

- When control changes to a side, play the selected soft chime once; ordinary scoring ticks do not repeat it.
- When a side loses control, play the configured loss cue. Direct changes use the capture cue only.
- When a KOTH completes with a winner, play Champion Fanfare once. Cancellation and no-winner outcomes do not play it.
- Public cues use the configured capture-region audience and notification preferences; private cues use event participants.
- Delayed control notes require the same active event, controller, and original/current audience. Delayed victory notes recheck the original/current audience after event cleanup. Individual cues and all sounds are configurable; control changes are rate-limited per event.

## Implementation and verification boundary

The preview's two pling notes and amethyst shimmer are scheduled at ticks 0, 4, and 5; Champion Fanfare uses bell, level-up, bell and bell at ticks 0, 3, 8 and 13. The preview's browser gain fade cannot be reproduced by a single Minecraft sound packet, and server lag, distance/audio settings, resource packs and client type can change perception. The five-second default cooldown affects control cues only, not victories.

Preview asset hashes were matched against the local Minecraft assets index and sound-event mapping. Source tests cover control transitions, per-event throttling, per-layer configuration, delayed audience filtering and winning/no-winner behavior. Real server audio and Java/Bedrock behavior still require separately coordinated TEST acceptance. No production or TEST deployment is included. No project-local EARS/state helper was found; this file records the manual SPEAR requirements and evidence.

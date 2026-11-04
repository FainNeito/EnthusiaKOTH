# Rewards navigation cleanup

Requirement REWARDS-NAV-01: The KOTH rewards home SHALL present player-facing
entries to both staff and ordinary players, without arena setup or global
settings entries. Existing staff commands and their authorization stay intact.

Spec/prove: the old home explicitly appended both staff entries when the viewer
held `enthusiakoth.admin`; this exposed setup navigation inside `/rewards`.
Engine/architecture: remove only those entries and actions from the rewards
home. Staff setup and manage commands remain separate. Refine: compile and run
the existing ProgressionMenuInteractionTest; visual/native acceptance still
requires a client. No project-local EARS validator or state helper was found;
this manual record does not claim those tools passed.

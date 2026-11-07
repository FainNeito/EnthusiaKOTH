# Crown branch rollback to a pre-Crown KOTH binary

Status: procedure plus local evidence; not rehearsed on Paper or TEST. Applies to downgrading from the Crown recovery branch to `main` at `7f43049` or earlier. This covers KOTH only. Downgrading LoreItems below its Crown custody schema is a separate procedure that does not exist yet.

## What a pre-Crown binary does with Crown state

Observed by running `main`'s own `FileOperationalStateStore` against branch-format files (throwaway probe, 2026-11-07):

- `event-queue.dat`: rows in state `DISCARDED` are dropped on load. The next queue write removes them, along with any `t|` taint marker. An unacknowledged discard is a pending PLANNED-to-CANCELLED retry, so that retry is lost silently.
- `schedule-state.dat`: start claims, including PENDING ones, are kept. The `tainted=` marker is removed on the next schedule write.
- `koth_crown_*` tables: `main` never reads them, so they stay as they are. A queued Crown slot is processed as an ordinary scheduled KOTH: `main` has no binding hook, so no Crown is delivered, and LoreItems escrow keeps the original HELD.

## Before downgrading (new binary still running)

1. Stop creating Crown work. Remove all `crown-recovery.activity-occurrences` and `recovery-occurrences` and unset `crown-quest.first-final-occurrence`, then reload. Keep `crown-recovery.enabled` unchanged while draining.
2. Drain queue markers. Wait until `event-queue.dat` contains no `|DISCARDED|` rows and no READY or ACTIVATING row for a Crown occurrence ID, and until no Crown match is active. Discard acknowledgement runs from the normal queue tick.
3. Check for taint. If either file contains `t|` or `tainted=`, do not downgrade: the lossy evidence would be erased. Reconcile first, as described in `crown-quest-event-design.md`.
4. Read back the database and record the results:
   `SELECT occurrence_id,purpose,state,match_id FROM koth_crown_occurrences WHERE state IN ('PLANNED','STARTED');`
   `SELECT occurrence,recovery,generation,match_id,winner FROM koth_crown_recovery_events WHERE retired=0;`
   A STARTED Crown match or a binding with a winner means a delivery is in flight: do not downgrade until LoreItems reports that the grant has finished.
5. Stop the server, then back up `schedule-state.dat`, `event-queue.dat`, the KOTH database and the LoreItems database, and record their SHA-256 hashes. Do not delete or edit any `koth_crown_*` table.
6. Swap the JAR and start. Confirm that the number of queue rows `main` loaded equals the number of `q|` rows in the backed-up file.

## A discard marker that will not drain

The acknowledgement returns false when the occurrence row's arena or time differs from the queue row, when another activation STARTED the row, or when the database is unavailable. Do not downgrade in that state.

- Row STARTED by a different match: the marker can never change it, because that match's own completion or cancellation governs the row. With the server stopped and backups taken, an operator may record the line and then remove only that `DISCARDED` row.
- Identity mismatch (same ID, different arena or time) or a missing database: hold for staff review. Never edit `koth_crown_*` rows by hand to make the acknowledgement pass.

## Upgrading again

Old binaries may have run a Crown slot as ordinary KOTH. That slot is then PLANNED with a COMMITTED start claim and no queue row, which the missed-occurrence gate HOLDs. After the 3-day claim retention prunes the claim, the gate classifies the slot as MISSED and cancels it without an award, releasing the binding. The Crown stays with its held original throughout.

## Evidence and gaps

- Covered: the main-reader probe above; branch tests for marker persistence, failed acknowledgement replay, clear-queue refusal and restart (`KothQueueTest`); and the missed-gate behaviour after re-upgrade (`OperationalEvidenceTest`, `KothQueueTest`).
- Not covered: a Paper downgrade and re-upgrade rehearsal with real files and a held LoreItems Crown, a LoreItems downgrade, and TEST.

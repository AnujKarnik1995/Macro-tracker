# Spec — Freeze the macro green-day tally

## Problem
`MacroCalculator.successfulDays()` re-judges every logged day against the *current* Targets on
each render. Edit a band and the whole `✓ N / M` history shifts. We want each day locked to the
target that was in effect **when it was logged**, so past greens don't move.

## Approach: dated targets history (recommended)
Give the Targets tab an effective-from date and allow more than one row per macro. The band in
effect for macro *M* on day *D* is the row for *M* with the greatest `EffectiveFrom ≤ D`. Judging
becomes a pure function of (that day's macros, the target that applied that day) — re-derivable,
editable, and stored entirely in the sheet. No per-day opaque flag, no new published CSV.

### Sheet change (Targets tab)
Add column **E `EffectiveFrom`** (a date). Columns stay: `A Macro · B Lower · C Upper ·
D Severity · E EffectiveFrom`. To change a band you **append** a new dated row instead of
overwriting the old one:

```
Protein  180  210  danger  2026-06-15     <- original
Protein  190  220  danger  2026-07-20     <- tightened mid-cut; earlier days keep 180–210
```

Migration: backfill `EffectiveFrom = 2026-06-15` (CHALLENGE_START) on the existing rows. That
reproduces today's tally exactly at cutover — nothing moves until you add a *new* dated row.

Weight-loss row: same mechanism applies if you want weekly weight judgments frozen too. Out of
scope below unless you want it — say so and it's the same three lines.

### Resolver semantics
`asOf(D)` → for each macro, pick the row with max `EffectiveFrom ≤ D`; ties broken by sheet order
(last wins). If *D* precedes a macro's earliest row, that macro isn't checked that day (drops out,
consistent with today's "only macros that have a target are checked"). A **future** `EffectiveFrom`
doesn't affect past/today until that date arrives — so you can schedule a phase change in advance.

## Code touch list
- **MacroModel.kt** — add `DatedTarget(effectiveFrom: LocalDate, target: Target)` and a
  `TargetHistory` wrapping `Map<MacroType, List<DatedTarget>>` (sorted), with:
  - `asOf(date): Map<MacroType, Target>` — resolver above.
  - `current(today): Map<MacroType, Target>` — `asOf(today)`, for callers that still want one map.
- **CsvParser.kt** — `parseTargets` now reads optional col E and returns a `TargetHistory`
  (rows with no date → `effectiveFrom = LocalDate.MIN`, so they always apply). Keep a
  `current()` convenience so today's ring bands are unaffected.
- **MacroCalculator.kt** — `successfulDays(entries, history, today)` counts
  `dayIsSuccessful(it, history.asOf(it.date))`. `dayIsSuccessful` itself is **unchanged** (still
  takes a plain `Map<MacroType, Target>`); it just gets fed the day's resolved band.
- **ChartWorker.kt** — parse the history once; pass it to `successfulDays`; pass
  `history.current(today)` wherever a plain today-map is needed (today's rings).
- **ChartRenderer.kt** — unchanged; keeps drawing today's bands from the current map.

No change to the nightly reconcile / `rebuildAllSummary` — those build macro data, not judgments.

## Edge cases
- Change a band mid-week: past days in that week keep the old band; today's rings use the new one.
  The weekly *average* rings are unaffected (they don't judge success).
- Same-day change: last matching row wins.
- Pre-challenge days: none exist; if one did, it's simply not counted (no band in effect).

## Alternative: write-once snapshot flag (not recommended)
Backend writes a `Met` boolean into a new Summary column at log time, computed against the
then-current targets, and **never** recomputes it on rebuild; widget just sums the column.
Lighter widget math, but: not re-derivable (a logging fix or a change to *which* macros count can't
propagate), and `rebuildAllSummary`/reconcile must be taught write-once semantics or they'll clobber
history. Same class of opaque per-day state you rejected with Health Connect. Only worth it if the
success definition will never change.

## Recommendation
Dated targets history. Re-derivable, sheet stays the source of truth, no new CSV, and you get
scheduled/future phase changes for free. Estimated change: ~1 new small type, ~15 lines in the
parser, ~3 lines in the calculator, ~2 lines of wiring — plus the one-time column add + backfill.

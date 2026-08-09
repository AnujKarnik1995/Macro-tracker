# Local tests

Two layers, both runnable offline. No Google account, no Apps Script editor, no Android build.

## 1. Differential test — `Code.gs` under mocked Apps Script (Node)

Proves a refactor changed nothing observable.

```bash
cd backend/test
node diff-versions.js /path/to/old/Code.gs ../Code.gs
```

Exit 0 = every output identical. Exit 1 = prints the group that moved and both values.

Compares 13 groups: `computeTdee`, `typicalBurn`, `readBurn`, `readTargetConfig`, `addDays`,
`daysBetween`, the numeric guards, `parseInputDate`, `completeIntakes`, `regressionSlope`, the full
Summary contents after 11 write calls, per-date duplicate-row counts, and Summary after
`rebuildAllSummary`. Each group runs under **two date regimes** (see below).

**Fixtures.** `fixtures/summary.csv` is a **synthetic** fixture (no real data). The Tracker fixture is synthetic and
deliberately nasty — mixed `Date`/string dates in one column, two weigh-ins on one day, two burn
sessions on one day, a zero weight, and junk text in the cal/weight/burn cells. None of that exists
in the summary fixture and all of it is reachable in production.

**Why two date regimes.** Sheets hands back a date cell as a `Date` at **midnight in the spreadsheet
timezone**, and `Utilities.formatDate` converts back with that same timezone. An earlier version of
this mock did both in UTC — so the conversion cancelled out and a whole class of off-by-one-day bug
was invisible. `mock-apps-script.js` now models it properly and the suite also runs a **UTC-midnight**
regime, the shape a CSV import or a post-hoc timezone change leaves behind. Do not "simplify" that
back to UTC.

**Known sensitivity (not a regression, but load-bearing).** Under the UTC-midnight regime,
`normDate` reads every date one day early, the upsert stops matching, and rows start duplicating.
Nothing in the widget dedupes by date and `successfulDays()` counts rows — so a duplicate green day
is counted twice. See ASSUMPTIONS.md §17. If date cells ever get into that shape, the streak inflates
silently. A `verifySummaryIntegrity()` check that logs a warning on duplicate logical dates is the
cheap guard.

## 2. Golden test — the real Tracker and Form responses (Node)

Proves the Tracker→Summary and Responses→Tracker→Summary paths still reproduce the live sheet.

```bash
node golden.js ../Code.gs Tracker.csv Summary.csv FormResponses.csv [--vs old/Code.gs]
```

Checks:

1. `rebuildAllSummary(real Tracker)` vs the live Summary, cell for cell.
2. responses → Tracker → Summary vs real Tracker → Summary. Catches a payload-mapping change even
   when it alters the Tracker row count.
3. With `--vs`, both diffed against another version.

**Two differences are expected and are not bugs** — they are facts about where data lives:

- **The pre-diet anchor row is dropped.** The pre-diet anchor weigh-in exists only in Summary, never in Tracker,
  so any rebuild deletes it. Submit it as a back-dated weigh-in to make it durable.
- **The burn column is restored.** Burn lives in Tracker (and in Form responses), so clearing it in
  Summary alone is undone by the next rebuild.

## 3. Contract test — the real widget code against a Summary CSV (JVM)

`CsvParser`, `MacroModel`, `MacroCalculator`, `TdeeCalculator` and `WeightCalculator` depend only on
`java.time` and the Kotlin stdlib — no Android — so they compile and run on a plain JVM. This closes
the loop across the Sheet/app boundary instead of reasoning about it.

```bash
kotlinc app/src/main/java/com/example/macrowidget/{CsvParser,MacroModel,MacroCalculator,TdeeCalculator,WeightCalculator}.kt \
        backend/test/Driver.kt -d /tmp/out
kotlin -cp /tmp/out DriverKt backend/test/fixtures/summary.csv backend/test/fixtures/targets.csv 2026-08-09
```

Prints JSON: rows parsed, **duplicate dates**, green-day count, TDEE / avg intake / lb-per-week,
weekly averages and rates, and **per-day effective bands with the pass/fail verdict** — so a change
that moves `t_*` by half a gram shows up as an exact band string, not a summary statistic.

Use it to answer "what does the widget actually think?" rather than re-implementing its logic
elsewhere. Re-implementations drift: an earlier Python estimate of the post-repair green-day count
came out at 32 because it skipped `CsvParser`'s whole-gram rounding and applied static bands to days
that had computed centres. The real number is 39.

## Suggested workflow for a refactor

1. Copy the current `Code.gs` aside as the baseline.
2. Refactor.
3. `node diff-versions.js baseline.gs ../Code.gs` — must exit 0.
4. If Summary layout or `t_*` semantics changed, also run the Kotlin driver before and after and diff
   the `days[]` array.

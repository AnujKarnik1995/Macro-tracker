# Macro Widget (Android)

A home-screen widget that reads your diet macros from a **published Google Sheet**
and shows, in one tile:

- **Streak + countdown chips** — a running tally of "green" days and a day-countdown
  to the goal date.
- **Today** — graded bullet rows for Calories, Protein, Carbs, Fat vs. your target band.
  Each bar marks its upper bound (`≤`), and while you're below the band's midpoint shows a
  faint "to go" hint (e.g. `+66 to 160`) — informational only; it never affects green/success.
- **This week · avg** — progress rings (brighter, cooler palette) for the running
  weekly average, Sunday → today. Rings sit in one row on wide tiles and reflow to a
  2×2 grid on squarer/taller tiles, automatically on resize.

The tile has **two views**: tap the body to cycle **Today ↔ Weight**; a small **↻ button**
in the bottom-right corner refreshes. Two page dots show which view you're on.

## Screens

<table>
  <tr>
    <td align="center"><img src="docs/render_today.png" width="300" alt="Today view — Cutting Phase · Today header, graded macro bullet rows, weekly rings"></td>
    <td align="center"><img src="docs/render_weight.png" width="300" alt="Weight view — weekly-average trend with target band and daily cluster"></td>
  </tr>
  <tr>
    <td align="center"><b>Page 1 · Today</b><br>streak + countdown chips, graded macro rows, weekly rings</td>
    <td align="center"><b>Page 2 · Weight</b><br>weekly-average trend, target band, current-week weigh-ins</td>
  </tr>
</table>

> Representative renders generated from the widget's own drawing code (vector sources in `docs/*.svg`).

### Weight view
A weekly-average weight trend. Daily weigh-ins (from `Summary` column F) show as a faint
cluster on the current week and consolidate into one weekly point at week's end (Sun→Sat).
Week-over-week loss is judged against the acceptable rate band (the `Targets` "Weight" row,
e.g. 0.7–0.9 lb/wk): completed weeks in the band get a ✓, out-of-band weeks show amber. A
single green target band, anchored at last week's average − 0.7 to − 0.9, marks where the
current week should land. Reads the same two CSVs as the macro view — no extra sheet.

Color is **graded**, not snapped: inside the band = green; as you drift out it eases
toward amber and then red. Being *under* on fat is treated as danger (reds out); under
on calories/carbs/protein just goes amber.

### Streak (green-day tally)
A day counts as "successful" when **Protein, Carbs and Fat all land inside their band**
(Calories are intentionally ignored — allowed out of band). The chip shows the **total**
count of such days across your whole log; days you didn't log are simply skipped. The
tally is recomputed from the sheet each refresh, so it self-corrects and never drifts.
Daily totals are **rounded to whole units** (in `CsvParser`) before the band check, so
sub-gram noise and floating-point artifacts (e.g. 159.9 → 160) never fail a day.

### Countdown
Counts days from today to the goal date. Change the date in `ChartWorker.kt`
(`GOAL_DATE` / `GOAL_LABEL`).

### No-blink refresh
Each refresh paints the **last rendered frame instantly** (cached to disk) before the
network fetch runs, so the tile never drops to the empty card between updates. Refreshes
are also de-duplicated (a single WorkManager job) and throttled, and resize events only
fire when the size actually changes.

### Offline & retries
Fetches retry with backoff and use a conditional GET (ETag / `If-Modified-Since`), so an
unchanged sheet returns `304` and the cached body is reused. The last good CSVs are cached
per URL; if a fetch fails outright, the tile silently re-renders that cached data instead of
showing an error — it only shows the error card if nothing has ever been cached. See
`SheetCache.kt` / `SheetFetcher.kt`.

## 1. Set up two tabs in your sheet, publish each as CSV

**Tab 1 — Log** (your daily data; columns read by position):

```
Date,        SUM of Calories, SUM of Protein (g), SUM of Carbs (g), SUM of Fat (g)
2026-06-15,  1635,            146,                175,              22
```

**Tab 2 — Targets** (rows matched by keyword, so order is flexible):

```
Macro,    Lower, Upper, UnderSeverity
Calories, 2000,  2100,  mild
Protein,  145,   175,   mild
Carbs,    180,   230,   mild
Fat,      45,    65,    danger
```

For each tab: **File → Share → Publish to web → select that tab → CSV → Publish**, and
copy the link (looks like `.../pub?gid=...&single=true&output=csv`). You'll paste both.
Keep editing the sheet normally; the published CSV stays in sync (a few minutes' lag).

## 2. Build
1. Open the `MacroWidget` folder in **Android Studio** (Hedgehog or newer); let Gradle sync.
2. Run it on your phone (USB debugging) or Build → Build APK and sideload the APK.

## 3. Add the widget
1. Long-press home screen → **Widgets** → **Macro Widget** → drag it on (a 4×4 tile).
2. Paste the **Log URL** and the **Targets URL** → **Save**. These are remembered, so the
   next widget you add pre-fills them automatically — you only paste once. (To hard-default
   them permanently, set `DEFAULT_LOG_URL` / `DEFAULT_TARGETS_URL` in `WidgetPrefs.kt`.)
3. It renders. **Tap the tile** to refresh instantly; it also auto-refreshes about every
   30 min (Android's minimum for widget auto-update, and only while the device is awake).

## Weekly math
Average across the current week (most recent Sunday through today, inclusive), counting
only dates that have a row — today's running total included, missing days excluded from
the divisor.

## Where things live
- `CsvParser.kt` — parses Log (by position) and Targets (by keyword).
- `MacroCalculator.kt` — today's row, weekly average, and the successful-day tally.
- `ColorRamp.kt` — the graded daily/weekly palettes and the zone-color function.
- `ChartRenderer.kt` — draws the stacked bitmap (chips + bullet rows + bound markers + rings + legend).
- `ChartWorker.kt` — fetches both CSVs off the main thread, computes streak + countdown, falls back to last-good (stale) data on failure, and pushes the bitmap. Holds `GOAL_DATE`.
- `SheetFetcher.kt` — HTTP fetch with retry/backoff + conditional GET.
- `SheetCache.kt` — per-URL cache of the last CSV body + ETag/Last-Modified (powers 304 reuse and the stale fallback).
- `BitmapCache.kt` — persists the last frame per widget so refreshes never blink.
- `WeightCalculator.kt` — weekly-average weight, week-over-week rate, in-zone, current-week band.
- `WeightRenderer.kt` — the weight-view trend chart.
- `WidgetChrome.kt` — shared footer (page dots + refresh button) drawn on both views.
- `SheetWidgetProvider.kt` — update/resize/tap handling (page cycle + refresh), cached-frame painting, work de-dup + throttle.
- `WidgetConfigActivity.kt` — the two-URL setup screen.

## Tweaks
- Colors / grading span: `ColorRamp.kt`.
- Faster auto-refresh than 30 min: add a 15-min periodic `WorkManager` job (OS floor).
- Different column order in your Log: adjust the `order` list in `CsvParser.parseLog`.

## Note
The pure data logic (CSV parsing, date/week handling, weekly averaging, zone grading)
was unit-verified on the JVM. The Android UI layer needs a normal Android Studio build —
there's no embedded Android SDK in the authoring environment.

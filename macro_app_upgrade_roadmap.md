# Macro App — Upgrade Roadmap

## Vision
Collapse the current chain (widget + Google Form + Sheet + Apps Script + Gemini alias) into **one self-contained Android app**: local data, precision preserved (your curated food table + gram weighing), frictionless logging, an in-app interactive recipe agent, and the always-visible widget for motivation. No external hops, no subscription dependency.

## Decisions locked
- **Data:** on-device (Room/SQLite) is the source of truth; **manual export** (JSON/CSV) for backup.
- **Build:** phased (ship value early, keep running throughout).
- **Stack:** native Kotlin + Jetpack Compose; reuse existing rendering; LLM via **API** (provider TBD — Gemini API free tier is the default and is independent of the Gemini *app* subscription).

## Target architecture
```
[ Local DB: meals · daily totals · pantry · targets · food-lookup ]
            |                                   ^
            v                                   |
   in-app screens (log / dashboard / chat)      |
            |                                   |
            v                                   |
     LLM API  <— {rules + remaining macros + pantry + history}
            |
   Home-screen widget reads the SAME local DB
```
The app owns the data and calls out to the model — nothing external reaches in. That single inversion removes every failure mode from the old pipeline (extensions, pivot tables, first-tab, Keep gating, tier limits, pre-bake freshness).

## Data model (v1 sketch)
- `meals(id, datetime, mealType, details, cal, p, c, f, foodId?)`
- `foods(id, name, basis[per100g | perServing], state[raw | cooked], cal, p, c, f)` — your curated lookup table
- `pantry(id, name, qty, unit)`
- `targets(macro, lower, upper)` — the bands
- `settings(goalDate, goalLabel, …)`
- daily totals computed from `meals` (cache if needed)

## Phased plan

### Phase 0 — Foundation & migration
- [ ] Room schema + DAOs
- [ ] **Import existing history**: export Sheet "Tracker" → CSV → importer into `meals` (preserves streak + countdown history — do NOT skip, the streak depends on it)
- [ ] Seed `foods` from your existing food→macro CSV (keep basis + raw/cooked tags)
- [ ] Manual export (JSON + CSV) working from day one

### Phase 1 — Core tracker  (retires the Form → Sheet pipeline)
- [ ] Logging screen: gram-based entry against `foods` lookup; quick-add recents; (transitional: optional AI-JSON paste import)
- [ ] Today/dashboard: reuse `ChartRenderer` rings/bars — remaining, bands, streak, countdown
- [ ] Port streak/countdown computation from `ChartWorker` into the app
- [ ] Widget reads local DB instead of CSV
  - [ ] (parked #10) kill residual flicker — trivial now that there's no network frame
  - [ ] (parked) tap-to-open → launches the app
  - [ ] (parked) Jetpack Glance rewrite
  - [ ] (parked) Material You theming
- [ ] Settings: configurable goal date/label (parked) + editable target bands
- [ ] Decommission Form / Sheet / Apps Script / published CSV (replaced by local data + export)

### Phase 2 — In-app recipe agent  (retires the Gemini alias + the subscription dependency)
- [ ] Pantry screen (add/edit/remove with quantities)
- [ ] Recipe chat screen: LLM API call with `{rules + remaining + pantry + history}`; full interactive back-and-forth (swap, "out of tempeh," lighter option)
- [ ] (optional) conversational logging via function-calling ("I ate 180g tempeh" → structured save)
- [ ] Secure the API key locally (fine for a personal build; don't ship it publicly)

### Phase 3 — Polish & later
- [ ] Trends/analysis screen — recovers the "re-analyzable raw data" you valued in Sheets, now on-device
- [ ] Richer export / optional auto cloud-sync (if you later want hands-off backup)
- [ ] Animations, accessibility, performance pass

## Old → new file mapping
| Existing | Fate |
|---|---|
| `ChartRenderer.kt` | **Reuse** (app + widget) |
| `ChartWorker.kt` | Streak/countdown logic moves into app; fetch removed |
| `SheetFetcher.kt`, `SheetCache.kt` | **Removed** (no remote fetch) |
| `BitmapCache.kt` | Keep (widget anti-blink) |
| `SheetWidgetProvider.kt` | Widget reads the DB |
| `WidgetPrefs.kt`, `WidgetConfigActivity.kt` | Becomes Settings (goal date, bands) |
| `MacroCalculator.kt`, `MacroModel.kt`, `ColorRamp.kt` | **Reuse** |
| `CsvParser.kt` | Repurpose for import/export |

## Sequencing note (re: your free year)
- **Phase 1** already removes the Form/Sheet fragility.
- **Phase 2** is the one that removes the Gemini-app/subscription dependency (chat moves to the API's own free tier). If being subscription-independent before your free year ends matters, **prioritize reaching Phase 2 before then.**
- Precision is preserved from Phase 1 onward, so progress toward your goal date is unaffected by any of this.

## Open items to decide
- LLM provider for the Phase 2 chat (Gemini API free tier default; Claude / OpenAI alternatives).
- Keep a transitional AI-JSON logging path in Phase 1, or go straight to native in-app logging.

---

# Energy / Burn page — TDEE + watch (new workstream)

Its own page: a self-calibrating burn model that (optionally) drives a constant-deficit daily
target. Approved v-mock in chat.

## Page layout (decided)
- **Page 1 Today** — unchanged; the weekly-**average** block **stays here** (not moved/replaced).
- **Page 2 Energy / Burn** — NEW (this workstream).
- **Page 3 Weight** — was page 2.
- Code: `PAGE_COUNT` 2 → 3; insert Energy renderer at index 1; Weight index 1 → 2; update
  `ChartWorker` page routing, `WidgetPrefs` page, `SheetWidgetProvider` dots/cycle.

## Confirmed scope (watch data)
- **BMR (basal) + HR-based exercise/active burn** only. **Steps dropped** (noisy) — trust the
  heart-rate-derived workout burn, not step counts. RHR, HRV, sleep, SpO2, skin temp also dropped.
- Pipeline: Pixel Watch → Health Connect (on-device) → **app reads with granted permission** →
  Apps Script → **stored column(s)** in the daily sheet (basal + exercise burn), like weight.
  Post-migration → local-DB field. Health Connect + read permission confirmed OK.
- Watch burn **calibrated vs back-calc TDEE** before use (Pixel Watch overestimates — established).

## Data-model change (decided): limits move inline to the daily sheet
Supersedes the separate Targets tab + `EffectiveFrom` / `TargetHistory` resolver.
- Each **daily row stores the limits that applied that day** (cal / P / C / F). Widget reads limits
  from the row; **daily success = row values vs that row's own stored limits** → green-days frozen
  by construction, no date-range resolution needed.
- A small **rules/config** stays (not per-day): protein & fat floors, target deficit (425), intake
  floor, calibration factor, weight band. Backend uses rules + that day's TDEE/workout to **compute
  and write** the day's limit columns (materialized output; rules are the input).
- **Retires** `TargetHistory` / `EffectiveFrom` for macros (intent — frozen per-day limits — lives
  on inline). One-time **backfill**: write each historical row's limits from the target in effect
  then (from the dated Targets already set), preserving the current frozen tally.
- Likely collapses the widget's two CSVs toward **one** (daily sheet carries data + limits; rules is
  a tiny config) — fewer moving parts, aligns with the eventual local-DB move.

## Build to-dos
- [x] **TDEE calculator** — `TdeeCalculator.kt` built + JVM-verified (not yet wired to a page).
      `TDEE = avg intake − (weight slope lb/day × 3500)`; trailing 28d window, **excludes today**
      (completed days only); **least-squares regression** over weigh-ins (chosen over weekly-avg
      endpoints — smooths water noise without picking an endpoint week); min-data guard →
      "collecting"; outputs measured TDEE, lb/wk rate, and `intakeForRate()` for the target band.
- [~] **Watch ingestion** — code written, **needs on-device build/test** (no SDK here). See
      `SETUP-burn-ingestion.md`. `Code.gs` routes burn → Tracker I/J → Summary G/H (last-writer-wins);
      `HealthConnectBurnReader.kt` (active-cals aggregate + latest BMR, null=not worn) +
      `BurnUploadWorker.kt` (posts `{basal,burn,date}` to the existing Form). `Code.gs` syntax-checked.
  - [ ] on-device: grant HC perms, wire the request flow into `WidgetConfigActivity`, schedule the worker
  - [ ] calibration factor = back-calc TDEE ÷ avg watch total burn (applied to active part) — Energy-page step
  - [ ] BMR decomposition (`TDEE = BMR + active`) so adaptation (BMR falling) is separable — Energy-page step
- [~] **Inline-limits migration** — backend DONE + Node-verified: Apps Script computes per-day
      target **centers** into Summary **I–L** (`updateDailyTargets`/`updateTargetsToday`, ~14:30
      trigger via `createTargetsTrigger`). TDEE = 28-day weight-regression back-calc; carb center =
      plug vs the calorie anchor; protein/fat centers + `Floor`/`Deficit` from the dated Targets
      config; calories not gated. Works pre-watch (delta 0). Config: add `Floor` + `Deficit` rows.
  - [x] **widget read** DONE + JVM-verified: `CsvParser` parses Summary I–L + burn into `LogEntry`;
        `MacroCalculator.effectiveTargets` = per-day center ± config width, **static band fallback**
        when I–L blank; `successfulDays` + Today rings + render signature use it (carb band slides,
        protein/fat unchanged). `TargetHistory` **kept as the fallback + width source** (not retired —
        past days aren't materialized, so it still judges history). Needs Android Studio build.
- [x] **Energy page render** (page 2) — `EnergyRenderer.kt` built: TDEE hero, measured rate colored
      vs band, 0.7–0.9 gauge + current marker, action line, "measuring" state, shared footer. Runs off
      `TdeeCalculator` + existing intake/weight (no watch data needed yet). **Page routing wired**:
      `ChartWorker` 3-way dispatch (0 Today / 1 Energy / 2 Weight) + energy in render signature;
      `PAGE_COUNT` 2→3. **Needs Android Studio build** (no SDK here). Later: BMR/active split, dynamic carb band.
- [x] **Dynamic target compute** — `DynamicTargetCalculator.kt` built + JVM-verified (logic only;
      not wired). Revised to the banded-carbs model: protein/fat fixed two-sided bands (pass-through),
      **carbs band slides**, calories = anchor (not gated). `LogEntry.exerciseBurn` added
      (null = not worn, 0f = worn rest day). Success logic (`dayIsSuccessful`) unchanged.

## Dynamic constant-deficit targets (design locked)
Hold a **400–450 kcal/day deficit** by flexing intake with measured burn, so a big-workout morning
*raises* the day's allowance instead of leaving him starving.
- **Target = latest TDEE + workout_delta − 425**, then **floored at the current sheet limit** (~1650–1700).
- **workout_delta = today's HR exercise burn − typical exercise burn** (delta vs the routine already
  baked into TDEE → avoids double-counting). Steps ignored.
- **Daily, same-day, no carryover** (no banking → no "Sunday pizza").
- **Compute once and store at ~2–3 pm**, when premade lunch + morning workout are frozen.
- **Band mapping (decided):** calories is **NOT** a success metric — gating it on top of three
  macro bands over-constrains (`cal ≈ 4P+4C+9F`, 4 gates on 3 DOF → contradictions). Success stays
  the **current protein/carbs/fat two-sided band check** (`dayIsSuccessful` unchanged).
- Protein & fat bands **fixed/unchanged** day to day. **Carbs is the daily mover**: band center =
  `(calorie anchor − 4·Pmid − 9·Fmid) / 4`, keeping your current carb-band width. Calories = the
  computed **anchor** (TDEE + delta − 425, floored) that positions the carb band — context only.
- **No hard swing cap** — user sanity-checks manually; instead **flag outlier days** so he knows to look.
- Stored per-row (see inline-limits): only the **carb band** varies per day; protein/fat come from
  the (dated) static bands. Frozen + re-derivable.

## Delta parameters (resolved)
1. **Baseline window = the TDEE window** (~21–28 d trailing). "Typical exercise burn" is averaged
   over the *same* window used for the TDEE back-calc.
2. **Calibrate the delta? Deferred.** Leave the workout delta **raw for now**; revisit after ~3 weeks
   of observed weight fluctuation to judge whether a calibration factor is warranted.
3. **Delta-vs-typical: confirmed.** Adjust only by (today − typical) exercise burn; never add the
   whole workout on top of TDEE (the double-count fix).

## Rest days / no-exercise (resolved)
- **No special case in the formula.** A rest day is `E_today = 0` → `workout_delta = 0 − Ē` is
  negative → target dips, and the intake floor catches it (rest day ≈ your normal limit).
- **Crux — define `Ē` as the all-days average over the window (rest days counted as 0)**, NOT a
  workout-days-only average. Keeps it consistent with TDEE (itself an all-days average): workout
  days get the correct *positive* bump (only the above-average portion), rest days the correct
  *negative* dip. A workout-days-only baseline would under-bump workout days and over-tighten rest days.
- **Distinguish a true rest-day 0 from 'watch not worn / no sync'** via a wear/liveness signal (any
  HR or BMR record that day; steps usable as liveness only). Not worn → treat as **missing** →
  `delta = 0` → fall back to `TDEE − 425`, floored. Never let a wrist-off day falsely tighten.

---

## Removed: watch burn ingestion (Health Connect)

Step 6 above (Pixel Watch -> Health Connect -> Form) was **removed**. The permission grant never
worked reliably on-device, and nothing in the pipeline ever read the basal/BMR value it collected.
`HealthConnectBurnReader.kt`, `BurnUploadWorker.kt`, the `androidx.health.connect` dependency, all
`android.permission.health.*` permissions and the config screen's grant button are gone.

Training calories are now hand-entered through the same Google Form (`{"burn": 320}`). Basal is not
collected and is not needed: the TDEE regression back-calculates *total* expenditure from intake and
the weight trend, so a separately measured BMR would be redundant by construction.

Kept deliberately: `Summary` col G, permanently blank. Summary is parsed by column position, so
collapsing the slot would shift the per-day target columns (I-L) and re-score historical green days.


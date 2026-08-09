# Assumptions & Decisions Log

Every number in this system rests on a judgement call. Most of them were made silently. This file
is where they get written down, so a future change doesn't quietly undo a deliberate choice.

Format: **what** / **why** / **what it costs** / **how to check it**.

---

## 1. Calorie floor = 1625 kcal

**What.** `anchor = max(TDEE + workout_delta − deficit, 1625)`. The day's target never goes below this.

**Why.** Adherence, not physiology. A cut you abandon because you are hungry and miserable loses
zero pounds. The floor is a deliberate trade of some theoretical rate for a much higher chance of
finishing the 60 days.

**What it costs.** `Math.max` is **one-sided**: it can only ever raise intake, never lower it. So
every time noise pushes the TDEE estimate spuriously low, the floor catches it and you eat more
than planned — and there is no matching mechanism to catch the spuriously *high* days. The errors
only ever run one direction: more food.

**MEASURED 9 Aug 2026: the floor has never bound, and is not the constraint.** With TDEE ≈ 2250 the
correct target is ~1825 — **+200 kcal of headroom** above 1625. Every `t_cal` in the sheet is at or
above 1750; none has ever been clamped. An earlier draft of this file claimed ~70 kcal of headroom
and blamed the floor for the rate discrepancy; that rested on an under-estimated TDEE and was wrong.

The floor remains correct as written: cheap insurance that has so far cost nothing. Revisit only if
`t_cal` starts printing 1625.

**How to check.** `COUNTIF(Summary!I:I, 1625)` over the last 60 days. Currently zero.

---

## 2. Deficit = 425 kcal/day

**What.** Read from the `Targets` tab, `Deficit` row, col B.

**Why.** 425 × 7 ÷ 3500 = 0.85 lb/wk, the middle of the 0.7–0.9 goal band.

**What it costs.** Nothing directly, but note the identity: **achieved deficit = Deficit − average
workout credit**. Anything that systematically adds calories (an over-generous burn number, a
one-sided floor) subtracts from this figure one-for-one. 425 is a ceiling on performance, never a
floor.

---

## 3. 3500 kcal per pound

**What.** `KCAL_PER_LB = 3500`, used to convert the weight slope into calories.

**Why.** Standard approximation for fat tissue.

**What it costs.** Tissue lost during a cut is not pure fat — some glycogen, water, and lean mass,
which are far less energy-dense. If the true figure is nearer 3100, TDEE is over-estimated by
roughly 30–40 kcal/day at a 0.85 lb/wk loss, and you eat that much too much.

Small next to the ~195 kcal estimator error documented in *Measured state*. Not worth correcting on its own.

---

## 4. Basal / BMR is not collected — and is not needed

**What.** The watch-BMR path was removed. `Summary` col G is permanently blank.

**Why.** TDEE here is **intake-anchored**: `TDEE = avg intake − weight_slope × 3500`. That
back-calculates *total* expenditure from energy balance. Basal is already inside the answer. Adding
a separately measured BMR would be double-counting, and would reintroduce exactly the predictive-
formula error (~800 kcal/day) that this whole design exists to avoid.

**How to check.** It is arithmetically impossible for a component of expenditure to be missing from
an intake-anchored estimate. If the scale moves, the estimate accounts for it.

---

## 5. Steps / walking are not logged — and do not need to be

**What.** ~8k+ steps on 5–6 days/week. Nothing in the pipeline records this.

**Why.** Same argument as §4. Walking burns calories; those calories change the weight trend; the
weight trend feeds the TDEE estimate. **The walking is already fully counted.** Adding a step-based
calorie estimate would double-count it and inflate the target.

**Where it DOES matter.** Not as an input — as a **lever**, and given §1 the most useful one available.
Expenditure raises TDEE, which raises the target one-for-one:

| | correct target at deficit 425 |
| --- | --- |
| TDEE ≈ 2250 (measured) | 1825 |
| \+100 kcal/day walking | 1925 |
| \+200 kcal/day walking | 2025 |

Walking barely changes the *rate* — the deficit sets that. It buys **more food at the same rate**.
It is the hunger lever, not the loss lever, which on a long cut is the lever worth having.

**Caveat.** A *change* in step volume shifts true TDEE, and the estimate lags it by about half the
window. Worth logging step count as a **diagnostic column** (never as a calorie input) so a
sudden TDEE shift can be explained rather than guessed at.

---

## 6. Under-logging food does not bias the result

**What.** No correction is applied for under-reported intake.

**Why.** It cancels algebraically. If you log 2000 but eat 2200, the slope reflects the real 2200,
so the estimator returns `TDEE − 200`. The target drops by 200, you under-log by 200 against it,
and the achieved deficit is unchanged. **The target is denominated in "logged calories," not true
calories.** Consistency matters; accuracy does not.

**What it costs.** The displayed TDEE reads low by however much you under-log. It is a control
signal, not a physiological measurement — do not quote it as your real maintenance.

**What breaks it.** *Drifting* logging discipline. If accuracy changes over time, the estimator
tracks the old habit for about half a window. Consistency is the requirement.

---

## 7. Weight noise — measured, and better than assumed

**What (MEASURED 9 Aug 2026 from 26 weigh-ins, 7 Jul – 8 Aug).** Residual SD about the fitted trend
= **0.71 lb**. Residual autocorrelation: lag-1 +0.34, lag-2 +0.29, lag-3 −0.20, and no consistent
sign beyond — implied persistence **τ ≈ 1.3 days**.

**This supersedes the earlier assumed values of 1.5 lb and τ = 10 days. Both were wrong**, and wrong
in the direction that overstated the problem. The consistent weigh-in protocol is working.

**Why it matters.** This is the most consequential assumption in the system. Random day-to-day
jitter averages away; slow drift does not. Which regime you are in decides whether averaging helps:

| noise type | 7-day averaging cuts noise by |
| --- | --- |
| white / random jitter (ideal) | 2.65× |
| **measured here, τ = 1.3 days** | **1.76×** |
| drifting, τ = 10 days (the bad case) | 1.11× |

**At the measured τ, weekly averaging works.** It recovers about two thirds of the ideal benefit.
The general warning — that averaging fails against *drifting* noise — remains true, but it does not
apply to this subject's data. Weekly averages here carry roughly ±0.33 lb, which is usable.

**Consequence for estimator design.** With SD 0.71 lb rather than 1.5 lb, TDEE-estimate noise is
about four times smaller than originally modelled:

| TDEE window | weigh-ins | SD(TDEE) | SD(displayed rate) |
| --- | --- | --- | --- |
| 20 days (current) | ~15 | ±109 kcal | ±0.22 lb/wk |
| 28 days | ~22 | ±65 kcal | ±0.13 lb/wk |
| 42 days | ~33 | ±35 kcal | ±0.07 lb/wk |

Widening the window is still free and still worth doing, but it is a refinement, not a rescue. The
trajectory controller (§9) is **not** justified at this noise level.

**How to check.** Re-run the residual autocorrelation whenever the weigh-in protocol changes.

---

## 8. TDEE window = 28 days, UNWEIGHTED  *(shipped 9 Aug 2026)*

**What.** `TDEE_WINDOW_DAYS = 28` (was 20). Plain least squares — **no exponential weighting.**

**Why 28.** Endpoint leverage. In a 20-day fit the four edge weigh-ins carry ~58% of the slope and
the four middle ones ~3%, so a single water-low reading at the window edge can move TDEE by hundreds
of kcal. Measured on the 2 Aug overshoot and its return swing:

| window | 3 Aug | 8 Aug | swing | worst error vs 2250 |
| --- | --- | --- | --- | --- |
| 20 days (old) | 2443 | 2144 | 298 | 193 |
| **28 days (new)** | **2325** | **2253** | **72** | **75** |

**Why NOT weighted.** An exponentially-weighted fit was the original recommendation and was **wrong**.
Tested at 10-, 14- and 21-day half-lives across 20-, 24-, 28-, 35- and 42-day windows, it lost to
plain least squares in *every* pairing — because up-weighting recent points re-creates exactly the
endpoint leverage the longer window exists to remove. Best weighted config (28d + 21d half-life)
scored worst-error 83 and swing 140, against 75 and 72 unweighted.

**Why not 42.** 42-day unweighted is a close second (worst error 86, swing 86). 28 wins narrowly and
carries less adaptation lag. Either is fine; 20 is not.

**The tempting mistake.** "42 days is too slow to react." Window length is **not a waiting period** —
it looks *backwards* at data already in hand. With 45+ days of history a 42-day window produces a
number today. Reacting *fast* to a noisy signal is not responsiveness; it is chasing water weight,
and it is what costs 0.77 lb over 60 days.

**What it costs.** True TDEE drifts down as weight is lost, and the estimate lags by about half the
window — roughly 40 kcal/day at a 42-day window, about 0.03 lb/wk. §9 removes it.

---

## 9. Correct on position, not on rate — NOT ADOPTED

**Status: rejected on measured data.** At ±109 kcal of estimator noise (not the ±430 originally
modelled) this buys almost nothing, and it adds a second feedback loop to reason about. Recorded
here so the reasoning isn't rediscovered from scratch; revisit only if weigh-in noise degrades
substantially.

**What it would have been.** Alongside holding a fixed deficit, hold a **planned weight
line**: `D_eff = Deficit + clamp((smoothed_weight − planned_weight) × 3500/21, ±150)`.

**Why.** Asking "how fast am I losing?" from a noisy scale gives ±1.18 lb/wk on a single week —
useless for judging a 0.85 target. Asking "am I above or below my line?" gives ±0.7 lb. Position is
a far easier measurement than speed.

And a rate measurement forgets. Week-over-week comparison never notices a slow 2 lb drift, because
each individual week reads "roughly in range." Distance-from-the-line accumulates: signal grows
linearly while noise grows as a square root, so certainty *improves* the longer the drift persists.

**Note.** This uses the same smoothed weight already in use, not raw daily readings. It changes what
the smoothed number is compared *against* — an absolute planned value instead of last week's
equally noisy average.

**What it would cost.** ~+0.2 lb over 60 days at the modelled (overstated) noise level, less at the
real one, in exchange for ~70 kcal off the leanest week. Not worth the added complexity here.

---

## 10. Success is gated on protein/carbs/fat, never calories

**What.** A green day = P, C and F all in band. Calories is displayed, never graded.

**Why.** Calories is a *derived* anchor that moves daily with the TDEE estimate. Grading against a
moving, noisy target would punish the estimator's error as if it were your failure.

---

## 11. `Summary` column G stays blank

**What.** Dead column, deliberately retained.

**Why.** `Summary` is parsed **by position** (`CsvParser`, plus per-day targets in I–L). Collapsing
G shifts I–L, which re-scores historical green days and breaks the streak. Cosmetic tidiness is not
worth destroying history.

---

## 12. Health Connect removed

**What.** Watch → Health Connect → Form ingestion deleted entirely.

**Why.** The permission grant never worked reliably on-device, and Google Fit is deprecated so there
is no fallback API. Training calories are hand-entered through the Form.

**Consequences handled.** Blank burn now means "rest day, 0 kcal" rather than "unknown" — matching
the all-days baseline, so `avg(workout_delta) = 0` over the window. Multiple same-day entries are
summed rather than overwriting. Submitting a burn entry re-runs that day's target immediately, so
training logged after the 14:30 trigger still counts.

---

## Open — unverified, needs real data

| # | Assumption | Status |
| --- | --- | --- |
| 1 | TDEE | **RESOLVED 9 Aug: ≈ 2250 kcal/day** (2232 / 2247 / 2280 by three methods). May read 30–60 low — 8 logged days carry implausible protein and are likely under-logged |
| 2 | scale noise SD, persistence | **RESOLVED 9 Aug: 0.71 lb, τ ≈ 1.3 days.** Earlier 1.5 lb / τ=10d was wrong |
| 3 | adaptive thermogenesis −12 kcal/lb lost | still literature-based, unverified |
| 4 | logging accuracy consistent over time | still assumed, unverified |
| 5 | early fast-loss phase outside the window | plausible — 145.6 → 139.05 happened before 5 Jul, outside a 42-day window as of Aug |

## Measured state, 9 Aug 2026

| | |
| --- | --- |
| TDEE | **≈ 2250 kcal/day** |
| correct target at deficit 425 | **≈ 1825 kcal** |
| weight | 145.6 → 135.25 lb, −10.35 lb in ~8.7 weeks |
| floor headroom | +200 kcal — has never bound |

### The overshoot event, 2 Aug 2026

The dynamic system went live on 2 Aug. This is what happened, and it is the failure mode the
original question was actually about:

| phase | target | ate | deficit vs TDEE 2250 | rate |
| --- | --- | --- | --- | --- |
| static, 7 Jul – 1 Aug | 1750 | 1693 | −557 | **1.17 lb/wk** (too fast) |
| dynamic, 2 – 7 Aug | 2096 | 2126 | −124 | **~0.25 lb/wk** (too slow) |
| correct | **1825** | — | −425 | 0.85 lb/wk |

**Cause.** On 3 Aug the 20-day window measured a 1.45 lb/wk slope and inferred TDEE = 2443, ~195
above truth. It then prescribed up to 2312 kcal. The inflated slope came from **endpoint leverage**:
in a 20-day OLS the four edge readings carry **58%** of the slope while the four middle readings carry
3%. The window opened on two water highs (138.4, 138.8) and closed on a water low (134.2 on 2 Aug,
14.2% leverage on its own).

**The return swing.** By 8 Aug the same 20-day estimator reads TDEE ≈ 2107 — a **336 kcal round trip
in five days** on a body whose expenditure barely moved. Left alone it will now under-feed.

**A longer window would have avoided both errors:**

| window | TDEE on 3 Aug | TDEE on 8 Aug | swing | implied target |
| --- | --- | --- | --- | --- |
| 20 days (current) | 2443 | 2107 | **336** | 1682–2018 |
| 28 days | 2325 | 2227 | 98 | ~1802 |
| 35 days | 2340 | 2245 | 95 | ~1820 |

Truth is ~2250 → target ~1825. The 28- and 35-day windows land on it from both ends; the 20-day
window misses high, then low. **Window length was the primary fix, not a refinement — see §8, now
shipped.** After the fix the 8 Aug estimate is 2253 → target 1828, within 3 kcal of measured truth.

---

## 13. Detecting incomplete logs — median test, not protein  *(shipped 9 Aug 2026)*

**What.** `INTAKE_COMPLETE_FRAC = 0.65`. A day is dropped from the intake mean if its calories fall
below 65% of the **window's own median** intake. Mirrored in `TdeeCalculator.completeIntakes()`.

**Why not protein.** Protein below the target band looks like the obvious tell for a half-logged day.
It is the wrong test. Of the eight low-protein days in the data:

| date | cal | protein | verdict |
| --- | --- | --- | --- |
| 4 Jul | 2040 | 106 g | real full day, eaten badly — **keep** |
| 5 Jul | 1877 | 78 g | real full day — **keep** |
| 6 Aug | 2103 | 89 g | real full day — **keep** |
| 25 Jul | 1421 | 89 g | real full day — **keep** |
| **8 Aug** | **1059** | **62 g** | **unfinished log — drop** |

Seven of eight were full days of eating with poor protein. Excluding them would bias the intake mean
**up** and inflate TDEE — the exact error being removed. Only the calorie level identifies an
abandoned log. (An earlier draft of this file called all eight "probably incomplete." Wrong.)

**Why median, not mean.** An outlier must not be allowed to move its own threshold.

**Guard.** If the filter would drop more than 40% of the window, it is ignored and raw data is used —
the filter must never be able to gut the sample.

**Verified.** On the 8 Aug window it drops exactly one day (1059 kcal against a 1700 median) and
keeps 25 Jul's 1421. TDEE goes 2227 → 2253 against a measured 2250.

---

## 14. `t_cal`/`t_pro`/`t_carb`/`t_fat` are CENTRES, not ceilings  *(bug found 9 Aug 2026)*

**The code's contract.** `MacroCalculator.effectiveTargets()` reads each per-day target as the
**centre** of a band and rebuilds the band around it:

```kotlin
val hw = (t.upper - t.lower) / 2f
out[m] = Target(c - hw, c + hw, t.underDanger)
```

**What was actually in the cells.** For every day from 15 Jun to 1 Aug, `Summary` I–L held
`1750 / 158 / 170 / 50` — the **Upper bounds** of the `Targets` bands, entered as intended daily
ceilings. Read as centres, every graded band shifted up by half its own width:

| macro | intended band | actually graded |
| --- | --- | --- |
| protein | 145–158 | **151.5–164.5** |
| carbs | 160–170 | **165.0–175.0** |
| fat | 45–50 | **47.5–52.5** |

**Cost, over 55 fully-logged days:**

| scoring | green days |
| --- | --- |
| as the code scored it (centre ± hw) | **12** |
| after the repair below | **39** |

*(Corrected 9 Aug from an earlier estimate of 32. That figure came from a Python re-implementation
which skipped `CsvParser`'s whole-gram rounding and applied static bands to days that already had
computed centres. The real widget code, compiled and run on the JVM, says 39: **34** from 15 Jun –
1 Aug rescored against the intended static bands, plus **5** from 2–7 Aug that were already green
against their own computed centres. Verified with `backend/test/Driver.kt`.)*

Per-macro pass rate, centre-scored vs intended: protein **36% → 80%**, fat **47% → 78%**, carbs
58% → 62%. Carbs was the smallest part of it; protein and fat did the damage. Several misses were
decided by a tenth of a gram (26 Jun protein 151.4 against a 151.5 floor; 22 Jul fat 47.4 against
47.5).

**This is NOT retroactive rescoring.** Frozen-green-days worked exactly as specified — each day was
judged against the value sitting in its own row, then and now. The defect is a **semantic mismatch
between what was written into the cell and what the cell means to the reader**: ceilings were entered
where centres were expected. Nothing recalculated; the input was in the wrong units.

**Partly self-corrected — and a second half of the bug.** From 2 Aug, `updateDailyTargets` writes
genuine computed centres rather than ceilings. But it wrote them through `Math.round()`, so
`pCenter` 151.5 became **152** and `fCenter` 47.5 became **48** — shifting both bands up 0.5 g
*permanently, on every future day*.

That error is **systematic, not random**: a band whose bounds sum to an odd number always has a .5
centre (145+158 = 303; 45+50 = 95), and `Math.round` always breaks .5 upward. So every single day
would have been graded against 145.5–158.5 and 45.5–50.5. Measured cost on real data: 3 green days
out of 55, each decided by a tenth of a gram.

**Fixed 9 Aug 2026** — `updateDailyTargets` now keeps one decimal on the centres
(`Math.round(x*10)/10`). Verified end-to-end against the real sheet: writes `t_pro` 151.5 and
`t_fat` 47.5, reproducing the intended 145–158 and 45–50 bands exactly. Without this fix, the manual
repair of rows 15 Jun – 1 Aug would have been undone from the next trigger onward.

**Centres are right going forward.** Carbs is the plug that absorbs the daily flex — the intent is to
*hit* the number, not to stay under it — and protein/fat centres are fixed band midpoints. A ceiling
would be the wrong semantics for a sliding target.

**Recommended repair.** Blank `Summary` I:L for 15 Jun – 1 Aug, **or** set them to the band centres
`1687.5 / 151.5 / 165 / 47.5`. Both were run through the real widget code and give **exactly the same
39 green days** — blanking makes `effectiveTargets` fall back to `history.asOf(date)`, and the centres
reconstruct those same bands. `rebuildAllSummary` preserves I–L verbatim, so either persists.

The alternative — leave it alone on the principle that history should not be touched — is defensible.
But the values being removed were never valid targets under the reader's semantics, so blanking them
restores the intended rule rather than rewriting it.

---

## 15. Deficit: 425 today, 375 under consideration

**Current.** `Deficit = 425`, `EffectiveFrom = 2026-08-02` → 0.85 lb/wk.

**History of the number.** Originally 400 (= 0.8 lb/wk, the midpoint of the 0.7–0.9 band), moved to
425. The stated intent behind revisiting it was that daily walking adds expenditure and should buy
some slack — which argues for a *smaller* deficit, i.e. 375. The move went the other way.

**Mechanism note.** Walking does not justify a smaller deficit. Walking is already inside the measured
TDEE (energy balance captures it by construction — §5), so it changes **how much food a given deficit
allows**, not which deficit is appropriate. Deficit maps one-to-one onto rate regardless of activity.
The valid reasons to prefer 375 are lean-mass retention at 135 lb and adherence headroom.

**At the measured TDEE of 2250:**

| deficit | target intake | rate | 60-day loss | end weight |
| --- | --- | --- | --- | --- |
| 375 | 1875 | 0.75 lb/wk | 6.4 lb | 128.8 |
| 400 | 1850 | 0.80 lb/wk | 6.9 lb | 128.4 |
| 425 | 1825 | 0.85 lb/wk | 7.3 lb | 128.0 |
| *effective, static phase* | *1693* | *0.99 lb/wk* | *8.5 lb* | *126.8* |

Note the last row: the static plan was running an effective deficit near **557**, so correcting the
TDEE estimate **slows the rate whichever deficit is chosen**. The 0.99 lb/wk was accidental
over-delivery, not the plan working.

**How to change it.** Add a *second* `Deficit` row with a later `EffectiveFrom`, do not edit 425 in
place. `readTargetConfig` already selects the greatest `EffectiveFrom` ≤ the date, so a new row
changes future days only and leaves an audit trail. Same for any future adjustment.

---

## 16. Timezone cached once per execution  *(shipped 9 Aug 2026 — refactor A1)*

**What.** `normDate()` used to call `SpreadsheetApp.getActiveSpreadsheet()` +
`getSpreadsheetTimeZone()` on **every date cell**, and it runs inside every row scan. Now a lazy
module-level `sheetTz()` fetches it once per execution; `todayStr()` wraps the four places that built
today's date string inline.

**Why it mattered.** Two Sheets service round-trips per cell. On this sheet (~320 Tracker rows, ~60
Summary rows) a form submission touching a meal, a weigh-in and a burn entry hit `normDate` about
1,388 times — roughly **2,776 API calls**, dominating the script's entire runtime.

**Measured with an Apps Script service mock, running the real file both ways:**

| | before | after |
| --- | --- | --- |
| `getActiveSpreadsheet` | 1,117 | **3** |
| `getSpreadsheetTimeZone` | 1,115 | **1** |
| `getValues` (sheet reads) | 22 | 22 — unchanged |
| timezone service calls | 2,232 | **4** |

**Equivalence verified, not assumed.** Same fixture built from the real Summary export with col A as
genuine `Date` objects (which is what Sheets returns, and the only thing that exercises the changed
path):

| check | result |
| --- | --- |
| `computeTdee` / `typicalBurn` / `readBurn` on 4 dates | identical to 4 decimal places |
| append path — new date writes I:L | `[1662, 151.5, 157.2, 47.5]` both |
| update path — existing date writes I:L | `[1682, 151.5, 162.2, 47.5]` both |
| duplicate-row guard | exactly 1 row per date, both |
| Summary row count | 58 both |

**Safe to cache for a whole run.** Apps Script gives each execution a fresh global scope, so the
cache lives exactly one execution, and the spreadsheet timezone cannot change mid-execution.

**Sheet reads deliberately untouched.** `getValues` stayed at 22 — collapsing the redundant reads is
refactor A2, which is gated behind the duplicate-row hazard in §17 and not part of this change.

---

## 17. Widget contract — what `Code.gs` must not break

Audited across all seven widget source files before starting the refactor.

**Hard constraints:**

1. **Summary column positions.** The widget reads indices `0,1,2,3,4,5,7,8,9,10,11`. Column 6 is
   never touched (see §11).
2. **Dates must be written as `yyyy-MM-dd` STRINGS, never as `Date` objects.** `CsvParser.parseDate`
   accepts only ISO, `M/d/yyyy`, `yyyy/M/d`, `d/M/yyyy`. A `Date` written into col A exports in the
   sheet's *display* format, and anything like `Aug 9, 2026` fails to parse — the row is then dropped
   silently by `?: return@mapNotNull null`.
3. **Rows narrower than 5 columns are silently dropped** (`if (cols.size < 5)`).
4. **NO DUPLICATE DATE ROWS.** Nothing in the widget dedupes by date, and each consumer breaks
   differently:

   | call site | failure |
   | --- | --- |
   | `successfulDays()` → `entries.count{}` | a green day is **counted twice** — streak inflation |
   | `today()` → `firstOrNull` | the second row becomes invisible |
   | `weeklyAverage()` | averages every row in the week — skewed |
   | `TdeeCalculator.intakes` | phantom intake day, counts toward `MIN_INTAKE_DAYS` |
   | `WeightCalculator` | duplicate weight averaged into the week |

   **The upsert in `Code.gs` is the only guard.** It is an integrity mechanism, not plumbing — any
   refactor that touches it needs the duplicate assertion that §16's test now carries.

**Confirmed non-constraints (free to change):**

5. **Row order is irrelevant.** `TdeeCalculator` sorts weigh-ins, `WeightCalculator` sorts,
   `MacroCalculator` is order-agnostic. Summary does **not** need sorting.
6. **Blank and `0` are equivalent** in burn (H) and weight (F) — `num("")` → null → treated as 0.
7. **Partial I–L is handled per-macro** by `effectiveTargets`, so the 4-cell write need not be atomic.

**Hazards this audit surfaced for later passes:**

- **A4/B1 (batched Summary writes).** `updateDailySummary` writes **only A–E** and deliberately
  leaves F–L alone. A row-wide write would clobber weight, burn and the per-day targets. Safe only by
  reading the existing row from the snapshot and merging.
- **A2 (single snapshot).** Two traps: `updateDailyTargets` currently re-reads Summary *after*
  `updateBurnSummary` writes, so a shared snapshot would use a stale burn; and an appended row must be
  reflected in the in-memory array or two new dates in one submission both append — straight into
  constraint 4.
- **B2 (`aggregateTracker`).** Consolidating widens the written range from A–E/F/H to A–H. Same
  values (both derive from Tracker), but it would overwrite a hand-edit in F or H.

**Dead code found.** `DynamicTargetCalculator.kt` (~130 lines) is referenced only from inside itself
— nothing constructs `DeficitConfig` or calls `targetForDay`. It is billed as a reference
implementation but has already drifted once (the same `typicalBurn` inversion had to be fixed in two
places). Recommend deleting it; `Code.gs` is the implementation that is actually under test.

---

## 18. Refactor pass 2 — pure extractions  *(shipped 9 Aug 2026)*

Behaviour-preserving cleanup. No logic changed.

| ref | change |
| --- | --- |
| B4 | one `num(v)` helper replaces `isWeightEntry`/`isBurnEntry`/`numOrBlank` internals **and 6 open-coded `!isNaN(Number(x))` guards** |
| A5 | `dayNumber()` — integer day arithmetic; `daysBetween` is now one subtraction instead of building two `Date` objects per row |
| B6 | `installDailyTrigger(handler, hour, min)` — the two trigger creators were the same function written twice |
| B7 | `windowRows(ss, dateStr)` — `computeTdee` and `typicalBurn` shared identical window bounds and filters; now they cannot disagree about which days are in scope |
| C1 | eight long rationale blocks reduced to a one-line rule + an `ASSUMPTIONS.md` §pointer |

**Verified by differential test** (`/tmp/compare.js` pattern — run the real file both ways under
mocked Apps Script services, against a fixture built from the real Summary export plus a synthetic
Tracker containing mixed Date/string dates, two weigh-ins on one day, two burn sessions on one day,
zero and junk values):

| group | result |
| --- | --- |
| `computeTdee` / `typicalBurn` / `readBurn` / `readTargetConfig`, 7 dates each | identical |
| `addDays` ×28 — incl. DST boundaries, month and year ends | identical |
| `daysBetween` ×5 — incl. reversed and same-day spans | identical |
| numeric guards ×17 edge values | identical |
| `parseInputDate` ×8, `completeIntakes` ×3, `regressionSlope` ×2 | identical |
| Summary contents after 9 updater calls + 2 target writes | identical |
| duplicate-row count per date | identical |
| Summary after `rebuildAllSummary` | identical |

**DST check mattered.** The old `daysBetween` built two *local* `Date` objects and rounded the
millisecond gap; the new one uses `Date.UTC`. Across a DST boundary the local gap is 23 or 25 hours,
and `Math.round` was absorbing it — so the results agree, but only by accident of the rounding. The
UTC version is exact by construction rather than by luck.

**One intentional strictness change, blast radius checked.** In `updateBurnSummary` and
`rebuildAllSummary`, the old guard `a !== "" && !isNaN(Number(a))` accepted `null` as `0` (because
`Number(null) === 0`) and would have set `found = true` for it. `num()` rejects `null`. Tested across
16 value types: **`null` is the only divergence**, and `getValues()` never returns `null` — empty
cells come back as `""`. Unreachable from sheet data, and the new behaviour is the correct one.

**Line count, honestly.** 605 → 587. My earlier "~120 lines" estimate assumed B2 as well; these are
*extractions*, so function count went **up** (26 → 32) by design. The real reduction needs B2, which
merges the four separate Tracker-scan/aggregate implementations — that is pass 3, still gated behind
the duplicate-row hazard in §17.

**Deliberately not touched:** `readTargetConfig`'s numeric coercion. Switching it to `num()` would
change a blank `Floor` cell from silently meaning `0` (floor disabled) to meaning "config incomplete"
(targets skipped). That is arguably a bug fix, but it changes a failure mode rather than preserving
behaviour, so it belongs in its own change with its own decision — not smuggled into a cleanup pass.

---

## 19. Local test rig  *(added 9 Aug 2026 — `backend/test/`)*

Both layers run offline. No Google account, no Apps Script editor, no Android build.

**Layer 1 — `diff-versions.js`.** Runs two versions of `Code.gs` under mocked Apps Script services and
asserts 13 output groups are identical, in **two date regimes**. Exit 0 or a printed diff. This is the
gate for any "no behaviour change" claim.

**Layer 2 — `Driver.kt`.** `CsvParser`, `MacroModel`, `MacroCalculator`, `TdeeCalculator` and
`WeightCalculator` depend only on `java.time` + the Kotlin stdlib, so they compile and run on a plain
JVM. This executes the **real widget logic** against a Summary CSV instead of reasoning about the
contract in §17. It prints per-day effective bands with pass/fail, so a half-gram shift in `t_*` shows
as an exact band string.

**It immediately earned its keep.** Layer 2 caught that my green-day arithmetic was wrong (32 vs the
real 39 — see §14), because a re-implementation had silently omitted `CsvParser`'s whole-gram rounding.
**Do not re-implement widget logic to reason about it; compile it and run it.**

### The timezone gap it also closed

The first version of the mock built date fixtures at UTC midnight *and* formatted in UTC. The two
cancelled, so `normDate`'s timezone conversion was never exercised. Sheets actually returns a date
cell as a `Date` at **midnight in the spreadsheet timezone**. Fixed, and the suite now also runs a
**UTC-midnight** regime — the shape a CSV import or a post-hoc timezone change leaves.

**Latent bug that regime exposes.** With UTC-midnight dates and a negative-offset sheet timezone,
`normDate` reads every date **one day early**. The upsert then fails to match an existing row and
appends — so rows start duplicating, which per §17 makes `successfulDays()` count a green day twice
and inflates the streak silently. Confirmed in the mock: Summary grew by a row where it should have
updated in place, and TDEE moved 2253 → 2278 as the window shifted a day.

Not currently reachable on this sheet — `Code.gs` writes dates as `yyyy-MM-dd` strings and Sheets
parses them at sheet-timezone midnight. It becomes reachable if the spreadsheet timezone is changed
after data entry, or if a fork imports data from CSV. **Cheap guard: a `verifySummaryIntegrity()` that
logs a warning when two rows normalise to the same date.** Worth adding before open-sourcing, where
neither precondition is under your control.

---

## 20. Sheet repair verified  *(9 Aug 2026)*

Export after the repair, run through **both** codebases:

| check | result |
| --- | --- |
| green days | **12 → 39** as predicted |
| duplicate date rows | **0** — upsert integrity holds |
| static-period bands (15 Jun – 1 Aug) | protein 145–158, carbs 160–170, fat 45–50 — intended values restored |
| burn column | cleared; `typicalBurn` = 0, delta = 0 |
| deficit in force from 9 Aug | **375** via the dated row; 425 still applies to 2–8 Aug |
| today's target | `t_cal` **1878**, `t_pro` 151.5, `t_carb` **211.2**, `t_fat` 47.5 |
| TDEE | **2253** — matches the energy-balance measurement |

Two cosmetic inconsistencies left, neither changing any green/not-green verdict:

- Rows **2–7 Aug** still carry `t_pro 152` / `t_fat 48` (pre-`r1()` rounding), so their bands sit
  0.5 g high. All six days are green regardless.
- Row **8 Aug** holds `t_cal 1732`, written while the burn column still had values. Recomputing now
  would give 1848. The day was a partial log and fails either way.

---

## 21. BUG: week-over-week rate ignores missing weeks *(widget, found 9 Aug 2026)*

`WeightCalculator.kt:57`:

```kotlin
val rate = if (i > 0) round((avgs[i - 1].second - avg) * 10f) / 10f else null
```

It diffs adjacent **entries in the weekly list**, not adjacent **calendar weeks**. Weeks with no
weigh-ins are absent from that list, so a gap collapses into the next entry and the whole multi-week
loss is reported as one week's rate.

On this data: no weigh-ins between 7 Jun and 5 Jul, so a genuine 6.55 lb loss over four weeks is
displayed as **6.60 lb/wk** — and scored against the 0.7–0.9 band, so the Weight page flags it as
cutting dangerously hard.

Fix: divide by the actual week gap, `ChronoUnit.WEEKS.between(prevStart, start)`, and suppress the
rate (or mark it interpolated) when the gap exceeds one week. Needs an Android rebuild, so it is
parked — cosmetic for this cut, but it will mislead any self-hoster with gaps in their weigh-ins.

---

## 22. Refactor pass 3, part 1 — Summary-only consolidation  *(shipped 9 Aug 2026)*

Pass 3 was split. This is the half whose fixtures are real; the rest is blocked (below).

| ref | change |
| --- | --- |
| B1 | `upsertSummary(summary, rows, dateStr, col, values, appendIfMissing)` replaces **four** copies of "find the row for this date, else append" |
| A2 (part) | `summaryValues(src)` accepts a Spreadsheet *or* an already-read values array, so `updateDailyTargets` reads Summary **once** and hands the same array to `computeTdee`, `typicalBurn` and `readBurn`. Public signatures unchanged. |
| C2 | appended rows are now uniformly full-width |

**Sheet reads per operation:**

| operation | pass 2 | pass 3 |
| --- | --- | --- |
| daily 14:30 trigger | 5 | **2** |
| form submit — meal + weigh-in + burn + targets | 11 | **8** |

`upsertSummary` **mutates `rows` to match what it wrote.** That is not tidiness — it is the fix for
the A2 hazard in §17. Without it, a caller holding one snapshot across two new dates would miss on
both and append two rows for the second, and duplicate Summary rows are the one corruption the widget
cannot survive.

### Two test-rig bugs this pass exposed

**1. The mock aliased the sheet's storage.** `getValues()` returned the live backing array instead of
a copy, so a function that both `appendRow()`s and updates its own snapshot appeared to write two
rows. The differential test reported duplicates for correct code. Real Apps Script returns a detached
copy; the mock now does too. This mattered in both directions — the same aliasing could have **masked**
a genuine duplicate.

**2. Append width is invisible to the widget, and the test was comparing it.** A row appended 8-wide
vs 12-wide differed in the mock but cannot differ in production: a Sheets CSV export pads every row
to the used column count, and `CsvParser` treats a missing column and a blank column identically
(both `num()` → null → no per-day centre). Verified against the compiled parser — 57 rows parsed,
39 green days, identical bands either way. The test now pads before comparing, with that reasoning
recorded inline.

### Still blocked, and why

| ref | needs | why |
| --- | --- | --- |
| B2 `aggregateTracker` | real `Tracker` | merges four Tracker readers; the fixture is invented, so a real-world row shape could diverge silently. **This is where the line count actually drops.** |
| B3 `payloadItemToRow` | real `Form responses 1` | payload shape is inferred from the code, never observed |
| A2 (submission path) | both | needs the stale-burn ordering proven against real data, not a synthetic Tracker |

Line count is 602 (from 587) — this pass *added* a helper and comments. The reduction is B2's.

---

## 23. Refactor pass 3, part 2 — the Tracker path  *(shipped 9 Aug 2026)*

Unblocked by the real `Tracker` and `Form responses 1` exports.

| ref | change |
| --- | --- |
| B2 | `aggregateTracker(rows)` — ONE pass over Tracker aggregating every date, replacing **four** separate scans that each re-read the sheet and re-implemented the same arithmetic |
| B2 | `refreshSummary(ss, want)` — recomputes any set of dates/columns from one Tracker read + one Summary read; the three single-date updaters are now three-line wrappers |
| B3 | `payloadItemToRow(item, fallbackDate)` — single payload→row mapper, shared by `processMacroPayload` and `rebuildTrackerFromResponses` (which had diverging copies, already differing in padding width) |
| A2 | `processMacroPayload` batches its Tracker appends into one write and hands `refreshSummary`'s snapshot to `updateDailyTargets` — which removes the stale-burn hazard by construction rather than by ordering |

**Measured, on the real 399-row Tracker:**

| | pass 3a | pass 3b |
| --- | --- | --- |
| `processMacroPayload` (3-item submission) — sheet reads | 8 | **3** |
| same — writes | 6 | **4** |
| `rebuildTrackerFromResponses` — rows written | 399 | **379** |
| — junk rows | 20 | **0** |

The four separate Tracker scans were how the last-value-wins burn bug survived in
`rebuildAllSummary` after being fixed in `updateBurnSummary`. There is now one implementation.

### Behaviour change, deliberate: 20 junk rows no longer written

`payloadItemToRow` returns null for an item carrying no macros, no weight and no burn. On the real
response log that is exactly the 20 `{basal, date}` payloads from the failed Health Connect backfill
(all dated 12 Jul, basal 1448). The old code fell through to the meal branch and wrote 20 blank rows.

**Summary output is unchanged** — verified by the golden test: responses → Tracker → Summary is
cell-for-cell identical to Tracker → Summary despite the 20-row difference. Those rows never carried
information.

### Golden test results

`rebuildAllSummary(real Tracker)` reproduces the live Summary exactly — every `cal`, `p`, `c`, `f`,
`weight` and `t_*` cell across all 55 shared dates — with **two expected differences**:

1. **`2026-06-07` (145.6 lb) is dropped.** It exists only in Summary, never in Tracker. Any rebuild
   deletes it, and total-progress silently drops from −10.4 lb to −4.4 lb. Durable fix: submit it as
   a back-dated weigh-in (`{"weight": 145.6, "date": "07/06/2026"}`) so it enters via Form responses.
2. **The burn column is restored** — all 21 values including 4 Aug (454), 5 Aug (411), 7 Aug (537).

### Item 2 is operationally urgent

Clearing burn in Summary is **not durable**. The values live in `Form responses` → `Tracker` →
`Summary`, so:

| clear it here | undone by |
| --- | --- |
| Summary col H | `rebuildAllSummary` |
| Tracker col J | `rebuildTrackerFromResponses` |
| Form responses | nothing — but editing the immutable log is the wrong tool |

If `createNightlyRebuildTrigger` is installed (~00:45), the burn column returns tonight and the daily
target drops from ~1878 to ~1712. **The only durable control is a code-level switch** — the
`burn_delta_enabled` dial from `CONFIG-PROPOSAL.md`. Until that exists, check the Triggers list.

---

## 24. `BURN_DELTA_ENABLED = false` — the workout flex is off  *(shipped 9 Aug 2026)*

**What.** One constant in `Code.gs`, gating every burn path end to end:

| site | when false |
| --- | --- |
| `isBurnEntry` | returns false, so `payloadItemToRow` drops burn items — no Tracker row written |
| `aggregateTracker` | col J not read; `burnSeen` stays false |
| `typicalBurn` / `readBurn` | return 0 without reading anything |
| `updateDailyTargets` | `delta = 0`, computed without touching the burn columns |
| `rebuildAllSummary` / `refreshSummary` | write Summary col H **blank** |

**Why the flag rather than clearing cells.** Clearing col H by hand is not durable — the values live
in `Form responses` → `Tracker` → `Summary`, so `rebuildAllSummary` restores them (§23 measured 21
values coming back, including 4 Aug 454, 5 Aug 411, 7 Aug 537). A code-level switch is the only
control that survives every rebuild path.

**Why off.** The historical figures are watch "active calories" for resistance training, averaging
465 per session — roughly 2× a realistic net cost. Against an all-days baseline of 166 they produced
a ~450 kcal/day swing in the daily target (1712 on a rest day, 2162 on a training day) off a baseline
that was itself wrong. The training is already inside the measured TDEE (§5), so ignoring it loses
nothing; only the redistribution *between* days goes away, and that redistribution was being priced
off numbers that were 2× too high.

**Verified both ways.** This is the shape a deliberate behaviour change should be tested in: prove the
flag does what it claims, and prove it does *nothing else*.

| check | result |
| --- | --- |
| flag **off**: `rebuildAllSummary(real Tracker)` vs the live cleared Summary | **0 differing cells** (was 21) |
| flag **on**: full differential suite vs pass 3b | identical in all 13 groups, both date regimes |
| flag **off** vs pass 3b: which groups move | exactly 5, all burn-related — `typicalBurn`, `readBurn`, `isBurnEntry`, and the two Summary snapshots. `computeTdee`, `targetConfig`, `duplicateDates`, the date math and the guards are untouched |
| payload `[{burn:250}, {basal:1400,date:...}, {weight:135}]` | **1** Tracker row written — the weigh-in only |
| `typicalBurn` / `readBurn` on 9 Aug | 0 / 0 |
| target written for 9 Aug | `1878 / 151.5 / 211.2 / 47.5` |

**Basal is not gated — it is gone.** It was only ever written to a column nothing read, and an
intake-anchored TDEE cannot need it (§4). Payload items carrying only `basal` are dropped by
`payloadItemToRow` (§23), so there is nothing left to switch.

**Turning the flex back on** is one edit to `true`, but Tracker will be missing its burn rows by then:
run `rebuildTrackerFromResponses` to restore them from the response log, then `rebuildAllSummary`.
Nothing is lost — the response log is immutable and still holds all 21 burn entries.

**This is `burn_delta_enabled` from `CONFIG-PROPOSAL.md`, realised as a constant** rather than a
Config-sheet row. When the Config sheet lands it should move there, along with `burn_delta_gain` and
`burn_delta_cap`, which are the knobs that would let the flex come back *scaled* instead of raw.


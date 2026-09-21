# Design Log — Macro Widget

Assumptions, decisions, and open work for the Sheet-side engine and the widget that reads it.

**This file describes the system as it is now.** Git holds what it used to be. Each section is a
**topic**, not an event: when something changes, edit its section and update its date — do not append
a new one. The date in a heading means *last updated*, and a `Changed:` line carries the trail.

Three tests for any line here:

1. Would a reader break something without it? → keep
2. Would someone propose this again if it were not written down? → keep — this is what makes the
   **Rejected** subsections the most valuable content in the file
3. Does it only record that work happened? → delete; git has it

Personal figures are illustrative, not real measurements.

---

## Current state · updated 2026-08-23

Overwrite this section; never append to it.

| | |
| --- | --- |
| TDEE (42-day window) | **≈ 2172** |
| independent cross-check (whole-period energy balance) | 2224 – 2245 |
| deficit in force | **375** (dated row, from 2026-08-09) |
| anchor at that deficit | **≈ 1797** |
| floor | 1625 — headroom **~50 kcal**, the narrowest it has been |
| measured rate, 42-day | −0.84 lb/wk (goal band 0.7–0.9) |
| green days | 47 of 69 logged; longest run 11 |

**Watch:** the floor has never bound, but ~50 kcal of headroom is close enough that a further drift
in the estimate would engage it — and a bound floor stops the controller tracking without saying so.

---

# Part 1 — The measurement model

## 1. TDEE is intake-anchored · updated 2026-08-09

`TDEE = avg intake − weight_slope(lb/day) × 3500`, over a trailing window ending **yesterday**
(completed days only). This back-calculates *total* expenditure from energy balance.

**Everything is already inside it.** Basal, walking, training, fidgeting — if it burns calories it
moves the scale, and the slope sees it. This is the load-bearing property of the whole design, and
three separate temptations follow from misunderstanding it:

- **Basal/BMR is not collected.** It is already in the answer. Adding a measured BMR would
  double-count and reintroduce the ~800 kcal/day predictive-formula error the design exists to avoid.
  Payload items carrying only `basal` are dropped.
- **Steps are not logged.** ~8k+ on 5–6 days/week, recorded nowhere. Adding a step-calorie estimate
  would double-count it and inflate the target.
- **Logged training calories are not credited.** Same argument — see §16.

**Where activity does matter: as a lever, not an input.** Expenditure raises TDEE, which raises the
target one-for-one:

| | target at deficit 375 |
| --- | --- |
| TDEE ≈ 2172 (measured) | 1797 |
| \+100 kcal/day walking | 1897 |
| \+200 kcal/day walking | 1997 |

Walking barely changes the *rate* — the deficit sets that. It buys **more food at the same rate**.
It is the hunger lever, not the loss lever, which on a long cut is the lever worth having.

**Under-logging does not bias the result.** Log 2000 while eating 2200 and the slope reflects the
real 2200, so the estimator returns `TDEE − 200`, the target drops 200, and the achieved deficit is
unchanged. The target is denominated in *logged* calories, not true ones. Consistency matters;
accuracy does not.

Two consequences: the displayed TDEE reads low by however much you under-log, so **do not quote it as
your real maintenance** — it is a control signal. And *drifting* logging discipline does break it;
the estimator tracks the old habit for about half a window.

**A caveat on estimate lag.** A genuine change in activity volume shifts true TDEE, and the estimate
follows it by about half the window. Worth logging step count as a **diagnostic column** — never as a
calorie input — so a sudden shift can be explained rather than guessed at.

**How to check.** It is arithmetically impossible for a component of expenditure to be missing from
an intake-anchored estimate. If the scale moves, the estimate accounts for it.

---

## 2. TDEE window = 42 days, unweighted · updated 2026-08-23

`TDEE_WINDOW_DAYS = 42`. Plain least squares over the window's weigh-ins.

**Changed:** 20 → 28 (2026-08-09) → 42 (2026-08-23). Both increases were forced by a measured
failure, and the two failures were different.

The window has **two independent lower bounds** and must clear both.

**Bound 1 — endpoint leverage.** Short least-squares fits are dominated by their edges. In a 20-day
fit the four edge weigh-ins carry ~58% of the slope and the four middle ones ~3%, so a single
water-skewed reading at the boundary moves TDEE by hundreds of kcal.

**Bound 2 — glycogen cycle length.** A carb swing moves glycogen and its bound water within a day or
two, and the round trip runs a few weeks. A window that one cycle *fits inside* averages the
water-loading half and the water-dumping half together and reports a slope that describes neither.

**What each length does on the same data** (window ending 2026-08-21):

| window | rate | TDEE | anchor at deficit 375 |
| --- | --- | --- | --- |
| 14 days | −1.55 | 2458 | 2083 |
| 20 days | −0.89 | 2296 | 1921 |
| 28 days | **−0.62** | **2082** | **1707** ← swallowed one cycle |
| **42 days** | **−0.84** | **2172** | **1797** |
| 49 days | −0.88 | 2189 | 1814 |
| 56 days | −0.88 | 2186 | 1811 |

Every window ≥ 42 agrees within ~50 kcal; every window ≤ 28 scatters across 380. Independent
whole-period energy balance says 2224–2245, so the 28-day figure was low by 120–170.

**How bound 2 presented.** The 28-day window split cleanly into the two halves of one carb-driven
water cycle — a flat loading half (+0.07 lb/wk) and a steep dumping half (−1.43 lb/wk) — and blended
them to −0.62. Reading that as "losing too slowly," the controller cut the anchor 605 kcal in 18
days while the true rate was ~0.85.

### Rejected

- **Exponential weighting.** Tested at 10-, 14- and 21-day half-lives across 20-, 24-, 28-, 35- and
  42-day windows. It lost to plain least squares in *every* pairing, because up-weighting recent
  points re-creates exactly the endpoint leverage a long window exists to remove. Best weighted
  config scored worst-error 83 / swing 140 against 75 / 72 unweighted. **Do not add it.**
- **49 or 56 days.** They agree with 42 only because there is not yet enough history to separate
  them, and they carry more adaptation lag.
- **"42 is too slow to react."** Window length is **not a waiting period** — it looks backwards at
  data already in hand and produces a number today. Reacting fast to a signal whose fast component
  is water is not responsiveness.

**What it costs.** True TDEE drifts down as weight is lost and the estimate lags by about half the
window, ~40 kcal/day at 42 days — about 0.03 lb/wk. Cheap against a 120–170 kcal error.

**How to check.** Segment the window at any large carb change and fit each half separately. If the
halves disagree by more than ~0.5 lb/wk, the blend is measuring a water cycle, not a trend.

---

## 3. Incomplete logs: median test, not protein · updated 2026-08-09

`INTAKE_COMPLETE_FRAC = 0.65`. An intake day is dropped from the mean if its calories fall below 65%
of the **window's own median**.

**Why median, not mean.** An outlier must not be allowed to move its own threshold.

**Guard.** If the filter would drop more than 40% of the window it is ignored and raw data is used.
The filter must never be able to gut the sample.

### Rejected

**Low protein as the tell.** It is the obvious signal for a half-logged day and it is wrong. Of eight
low-protein days in the data, **seven were full days of eating with poor protein** — 2040 kcal at
106 g, 1877 at 78 g, 1421 at 89 g. Only the 1059 kcal day was an abandoned log. Excluding low-protein
days would bias the intake mean **up** and inflate TDEE, which is the exact error being removed. Only
the calorie level identifies an abandoned log.

**Known limitation.** The filter catches abandoned logs, not merely incomplete ones. A day logged at
1400 against a 1750 median survives at 0.80 and drags the mean down while true intake is unchanged.
It also compounds: a lower mean lowers the median, which lowers the cutoff.

---

## 4. Weight noise, and the weigh-in protocol · updated 2026-08-23

**Measured** from 26 weigh-ins: residual SD about the fitted trend = **0.71 lb**; autocorrelation
lag-1 +0.34, lag-2 +0.29, lag-3 −0.20, implying persistence **τ ≈ 1.3 days**. This supersedes earlier
*assumed* values of 1.5 lb and τ = 10 days, both wrong in the direction that overstated the problem.

Which noise regime you are in decides whether averaging helps:

| noise type | 7-day averaging cuts noise by |
| --- | --- |
| white / random jitter (ideal) | 2.65× |
| **measured here, τ = 1.3 days** | **1.76×** |
| drifting, τ = 10 days | 1.11× |

At the measured τ, weekly averaging recovers about two thirds of the ideal benefit. Weekly averages
carry roughly ±0.33 lb, which is usable. The general warning that averaging fails against *drifting*
noise remains true but does not apply to this data.

Estimator noise follows directly:

| window | weigh-ins | SD(TDEE) | SD(displayed rate) |
| --- | --- | --- | --- |
| 20 days | ~15 | ±109 kcal | ±0.22 lb/wk |
| 28 days | ~22 | ±65 kcal | ±0.13 lb/wk |
| 42 days | ~33 | ±35 kcal | ±0.07 lb/wk |

**The protocol: fasted, post-void, before any water, same time daily. Do not change it.** It is why
SD is 0.71 rather than 1.5, and changing it now would inject a step discontinuity and blind the
estimator for half a window.

**Its one side effect,** worth knowing rather than fixing: measuring at the daily hydration minimum
maximises sensitivity to glycogen state, because full glycogen means both more water carried and more
available to lose overnight. It did not cause the §2 failure but it sharpened both halves.

**How to check.** Re-run the residual autocorrelation whenever the protocol changes.

---

## 5. 3500 kcal per pound · updated 2026-08-09

Used to convert the weight slope into a calorie figure. Literature range is roughly 3100–3500
depending on the lean/fat composition of the loss; if the true figure were nearer 3100, TDEE would be
over-estimated by a few percent.

Not worth correcting. Note the self-cancelling identity: at steady state the achieved **rate** is
`Deficit / 3500` regardless of the true value, because an error in the constant scales the estimate
and the target in the same direction.

---

# Part 2 — The controller

## 6. Deficit = 375 kcal/day · updated 2026-08-09

Read from the `Targets` tab, `Deficit` row, col B, as dated config.

**Changed:** 425 (2026-08-02) → 375 (2026-08-09).

**The identity that makes it work:** achieved deficit = `Deficit − average overshoot`, and at steady
state the rate settles at `Deficit / 3500` lb/day = **0.75 lb/wk** at 375.

**Walking does not justify a smaller deficit.** Walking is already inside the measured TDEE (§1), so
it changes *how much food a given deficit allows*, not which deficit is appropriate. Deficit maps
one-to-one onto rate regardless of activity level.

**How to change it.** Add a *second* `Deficit` row with a later `EffectiveFrom`. Do not edit the
existing row — that would re-price every historical day against a deficit that was not in force.

---

## 7. Calorie floor = 1625 kcal · updated 2026-08-09

`anchor = max(slew(TDEE − deficit), floor)`. The day's target never goes below this.

An anti-starve stop, not a tuning dial: every time noise pushes the TDEE estimate spuriously low, the
floor catches it.

**It has never bound.** But headroom is now ~50 kcal, the narrowest it has been. A bound floor stops
the controller tracking and says nothing about it, so this is worth watching rather than assuming.

---

## 8. Target slew limit = 50 kcal/week · updated 2026-08-22

`TARGET_SLEW_KCAL_PER_WEEK = 50`. The anchor may not move further than this from the most recent
previously-written anchor, pro-rated by days actually elapsed.

**Why speed alone identifies noise.** Real expenditure changes slowly — ten pounds of loss is worth
~100–150 kcal and takes months. Anything moving faster than that is reporting measurement error, not
metabolism. Slow real drift passes the gate; scale noise does not. No other information is needed.

**It prevents the cause, not just the symptom.** The controller's actuator is carbs — the most
water-reactive macro — and its sensor is a morning scale weight. Capping how fast the anchor moves
caps how large a carb change it can prescribe, which stops it driving the glycogen swings that
corrupt its own TDEE window. That closes the loop in §2.

Simulated over 46 days (TDEE 2225, deficit 375, target eaten exactly, no further water shifts — an
assumption that flatters the ungated rows):

| configuration | target swing | avg rate | total loss |
| --- | --- | --- | --- |
| 28d, no gate | **272 kcal** | 0.74 lb/wk | 4.8 lb |
| 42d, no gate | 142 | 0.77 | 5.1 |
| 28d + gate | 136 | 0.83 | 5.4 |
| **42d + gate** | 177 | 0.81 | 5.3 |

All four converge; the gate buys a smoother ride. The simulation understates it, because it assumes
the ungated path's 205 kcal weekly jumps produce no further water movement — exactly the assumption
§2 shows to be false.

**Three details, each load-bearing:**

- **Applied before the floor.** The floor is a hard stop the gate must not soften; equally the gate
  must not be able to hold the anchor below it on the way down.
- **Pro-rated by elapsed days**, measured from the last row that *has* an anchor, so a gap in the
  sheet cannot bank up unlimited slack. Never less than one day's worth.
- **Strictly before the target date.** `updateDailyTargets` is re-entrant; measuring against the row
  being rewritten would clamp each run to its own previous output and freeze the target forever.

**Bypass.** `reseedTargetsToday()` skips the gate once. Use it after a deliberate, evidence-backed
change to the estimator — a window length, a deficit, a repaired history — so the correction lands
instead of crawling from a value already known to be wrong. **Never** to unstick a target you merely
dislike: a number you want to override in a hurry is usually the noise the gate exists to block.

**How to check.** `Logger` prints a line whenever the gate holds a value. Frequent large holds mean
the estimator upstream is noisy, and the window — not the gate — is the thing to fix.

---

## 9. `t_cal`/`t_pro`/`t_carb`/`t_fat` are CENTRES, not ceilings · updated 2026-08-09

`MacroCalculator.effectiveTargets()` reads each per-day value as the **centre** of a band and rebuilds
the band around it using the static `Targets` half-width:

```kotlin
val hw = (t.upper - t.lower) / 2f
out[m] = Target(c - hw, c + hw, t.underDanger)
```

Carbs is the plug that absorbs the daily flex — the intent is to *hit* the number, not stay under it —
and protein/fat centres are fixed band midpoints. A ceiling would be the wrong semantics for a
sliding target.

**Writing ceilings into these cells shifts every band up by half its width.** With upper bounds in
the cells, protein 145–158 grades as 151.5–164.5 and fat 45–50 as 47.5–52.5. Measured cost over 55
fully-logged days: **12 green days where 39 were earned.**

**Keep one decimal.** `updateDailyTargets` writes `Math.round(x*10)/10`, never whole grams. A band
whose bounds sum to an odd number always has a `.5` centre (145+158 = 303; 45+50 = 95), and
`Math.round` always breaks `.5` upward — shifting both bands up 0.5 g permanently, on every future
day. Measured cost: 3 green days out of 55, each decided by a tenth of a gram.

**Note what this failure was not.** Frozen green days worked exactly as specified; each day was judged
against the value in its own row. The defect was a **semantic mismatch between what was written into
the cell and what the cell means to the reader** — ceilings entered where centres were expected.
Nothing recalculated; the input was in the wrong units.

---

## 10. Success is gated on protein/carbs/fat, never calories · updated 2026-08-09

A day counts as successful when **protein, carbs and fat all land in band**. Calories are excluded
deliberately: `t_cal` is a *derived* anchor that moves daily with the TDEE estimate, so grading
against it would score the estimator rather than the eater.

Each day is scored against the target in effect *on that date* via `TargetHistory`/`EffectiveFrom`, so
editing or dating a new target never re-scores earlier days. The tally is recomputed from the sheet
each refresh, so it self-corrects and never drifts.

---

# Part 3 — What the widget shows

## 11. Weight page: window, sign convention, dated bands · updated 2026-09-20

The page plotted every week ever logged, so nothing in the layout was bounded and it decayed as the
cut ran. At the real 420×560 geometry (plot 299.4 × 392.0 px):

| weeks plotted | slot width | y span | target band |
| --- | --- | --- | --- |
| 7 | 42.8 px | 6 lb | 13.0 px |
| 26 | 11.5 px | 26.2 lb | **3.0 px** |
| 52 | 5.8 px | ~40 lb | ~2 px |

Three independent causes: **x density** (slot width divided by total weeks; 7.6 px dots touch around
week 26), **y range** (taken over all history, so the 0.2 lb target band shrinks as the cut
*succeeds* — the worst of the three, since the band is the one thing you act on), and **label
collision** (a 42.6 px value label in a 42.8 px slot, touching from week 8).

### The dials

| dial | value | where it comes from |
| --- | --- | --- |
| window | `plotWidth / (subSize × 1.7)`, clamped 6–14 (**10** at 420 px) | 1.7× the subline text is the narrowest slot that still fits the seven day-of-week positions |
| max y span | `bandLb × plotH / 8` (**9.8 lb** at 420×560) | inverted from the requirement, not tuned: the span at which a 0.2 lb band still renders 8 px |
| min y span | 4 lb | below this a stall magnifies scale noise into a cliff |
| padding | 12% of spread | 18% cost band pixels for nothing |

**The two caps interact, and the window loses.** If the chosen window's natural range exceeds the max
span, the *window* shortens a week at a time down to 6 until it fits. Only if 6 weeks still will not
fit — roughly a sustained 2 lb/wk — does the range clamp and the oldest weeks clip, anchored so the
current week and the band stay visible. Shrinking the window loses old context still readable in the
subline; clipping loses the thing you act on.

**Canvas does not clip by default.** With the span cap in play the trend can legitimately leave the
plot box, so the explicit `clipRect` is load-bearing.

**Missing weeks are real entries.** The week list is built from the **calendar**, so a week with no
weigh-ins exists with `avg = null` and week-over-week rate is null whenever either side has no data.

*Rejected:* dividing a multi-week loss by the week gap. A 4-week loss ÷ 4 is not a weekly rate — it is
an average that hides which weeks stalled, and scoring it against a 0.7–0.9 band would still be
scoring a number nobody measured. A null reads as neutral, which is the honest answer.

### Sign: delta, not loss rate · added 2026-09-20

`WeekWeight.delta`, `WeightSeries.thisWeekDelta`, `TdeeResult.lbPerWeekDelta` and the Targets
`w_delta` columns are all **scale-signed: negative means lost.** The old convention was
loss-positive, and it leaked everywhere:

- `WeightRenderer` had to negate it back for display — `signed(-it)` with a comment explaining why.
- `targetLow` was computed from `upperRate` and `targetHigh` from `lowerRate`, because "more loss"
  means "lower weight". Two fields in weight units crossed against two in loss units.
- A band that permits GAIN could not be written without a negative lower bound reading as its
  opposite — which a maintenance or break phase requires.

Under delta, `targetLow = prevWeekAvg + lowerDelta` and the crossover is gone. The Energy gauge also
had `rmin = max(0f, lo - 0.4f)`, which silently clipped any gain-permitting band to the left edge;
its axis is signed now and always contains 0, with a zero tick for reference.

Migration hazard: the sheet's I/J columns invert meaning, so `0.7, 0.9` becomes `-0.9, -0.7`
(**negate AND swap** — negating reverses the ordering). Rows and code must land together. §14.

### Bands are dated; a blank band is a declared break · added 2026-09-20

`WeightCalculator.series` took ONE `WeightTarget` and judged every week against it, so changing the
band re-coloured all of history — the hazard `TargetHistory.asOf` prevents for macros, unnoticed
only because the band had never changed. It now takes a `WeightTargetHistory` and resolves
`asOf(week.end)`: a week is judged against the band in force when it **ended**.

`DatedWeightTarget.target` is nullable. A dated Targets row with **blank** `w_delta` cells is a
declared break — a vacation, a travel block — and it still WINS the as-of lookup, so the previous
band cannot leak through it. The week is left unjudged (`inZone = null`, neutral dot) rather than
failed, and `targetLow`/`targetHigh` go null so no band is drawn. This deliberately reuses the epoch
row rather than adding a separate "break periods" concept: two mechanisms for "things change on this
date" would need a rule for what happens when they overlap.

Consequence to expect: with no band, `bandLb` is 0, so the y-axis span cap
(`bandLb * plotH / BAND_MIN_PX`) is unbounded and the window-shortening loop never fires. The chart
zooms differently across a break boundary. Acceptable — there is no band whose legibility to protect.

*Not built:* dashed dividers on page 3 at each epoch boundary. `WeightTargetHistory.entries` already
carries the dates and the renderer already has the history, so it is a drawing change only.

---

## 12. Energy page geometry is a band budget · updated 2026-08-17

`EnergyLayout` cuts the tile into a stack of disjoint **bands**. Each element draws inside exactly one
band, centred, at a type size derived from *that band's* height. One invariant: no text may exceed
`FIT_CAP = 0.80` of its band. Ink runs ~0.72 of the type size above the baseline and ~0.21 below, so
a centred line occupies at most `0.93 × 0.80 = 0.744` of its band and leaves ~13% clear at each edge.
**Adjacent bands cannot touch, at any size or aspect ratio, with no per-element tuning.**

**The failure it replaced:** position computed independently of size, so the two could disagree. The
old renderer placed elements at hand-picked fractions of the content region and treated the fraction
as a text *baseline* — with `regionTop0` set to the title's baseline rather than its bottom, so
fraction 0.0 was already inside the title. A `squeeze = 0.68` scaled the region but not the type, so
configuring a gym plan pulled every element 32% closer to a header that had not moved. Adjacent lines
anchored by unrelated rules then crossed over.

**`FIT_CAP` is applied after the legibility clamp,** not before. A `minSize` floor larger than the
band would otherwise win and let glyphs overflow — the same failure one layer down. On an absurd tile
this yields small text, which beats overlapping text.

`EnergyLayout.kt` has no Android imports, so it is asserted offline rather than eyeballed on a device.

---

## 13. Strength sessions are a checkbox, not calories · updated 2026-08-16

`{"gym":"A"}` or `{"gym":"B"}` through the existing Form. Tracker col K, Summary col M, and a training
block on the Energy page: seven day-dots, sessions remaining, and the per-week rate they demand.

**Never wired into the daily target.** The training is already inside the measured TDEE (§1), so
paying calories for a logged session double-counts it — and it re-creates the "I earned this" loop the
constant-deficit design exists to remove. The day's food does not negotiate with the day's effort. No
gym value ever reaches `updateDailyTargets`.

**A required RATE, not a count.** A raw "16 left" only falls when you train, so a skipped week is
indistinguishable from a trained one until the deadline arrives and the shortfall is unrecoverable.
`left / weeksRemaining` rises on its own every idle day: 3.0 → 3.4 → 4.1 → 5.0 across three empty
weeks on a 22-session plan.

This is also why the design survives self-report. The count can be padded; the **rate cannot be
improved by padding**, because the divisor shrinks with the calendar whatever gets typed. A false
session just moves the shortfall to the goal date. No verification was built, deliberately — the user
controls both ends, so any check is bypassable, and the honest design is one where lying has no payoff
rather than one that pretends to detect it.

**Rolling 7 days, not a calendar week.** A calendar week that starts badly is written off by Wednesday
and the counter sits dead until Monday. Which days get used is deliberately unconstrained —
clustering shows up in the dots on its own (`●●●○○○○` reads differently from `●○○●○●○`).

**A/B are a pointer, not a weekday.** The programme alternates two full-body sessions, so there is no
leg day and therefore no leg day to miss. A gap does not shuffle the rotation; the next session waits.

**No rest-day log, and no `{"gym":0}`.** Absence *is* the miss. A miss-log would be a second thing to
remember on exactly the days you are least likely to remember it, and buys nothing the empty dot does
not already show.

Grading is relative to the plan's own pace (amber at 1.07×, red at 1.34×), so colours stay meaningful
if the plan changes. `GYM_TOTAL = 0` hides the block.

---

# Part 4 — Data contracts

## 14. Column positions are frozen · updated 2026-09-20

Both sheets are parsed **by position**, by `Code.gs` and by the widget's `CsvParser` independently. A
shifted column does not throw — it silently re-scores history against the wrong number. This is the
most expensive bug class this project has had.

In `Code.gs`, every positional access goes through the frozen index maps `S` (Summary), `T` (Tracker),
`TG` (Targets), `R` (Form responses). Array indices are 0-based, Sheets ranges are 1-based; write
columns are derived with `sCol()` from the same constant the read uses, so the two cannot drift.

### Hard constraints — what `Code.gs` must not break

1. **Reserved blank slots.** Summary G/H and Tracker I/J stay blank and stay where they are. New
   columns are **appended** (this is why `gym` is at Summary M).
2. **Dates written as `yyyy-MM-dd` STRINGS, never `Date` objects.** `CsvParser.parseDate` accepts only
   ISO, `M/d/yyyy`, `yyyy/M/d`, `d/M/yyyy`. A `Date` in col A exports in the sheet's *display* format,
   and `Aug 9, 2026` fails to parse — the row is then dropped silently.
3. **Rows narrower than 5 columns are silently dropped** (`if (cols.size < 5)`).
4. **No duplicate date rows.** Nothing in the widget dedupes, and each consumer breaks differently:

   | call site | failure |
   | --- | --- |
   | `successfulDays()` | a green day is **counted twice** — streak inflation |
   | `today()` → `firstOrNull` | the second row becomes invisible |
   | `weeklyAverage()` | averages every row in the week — skewed |
   | `TdeeCalculator.intakes` | phantom intake day, counts toward `MIN_INTAKE_DAYS` |
   | `WeightCalculator` | duplicate weight averaged into the week |

   **The upsert in `Code.gs` is the only guard.** It is an integrity mechanism, not plumbing.

### Targets is ONE WIDE ROW PER CONFIG EPOCH · added 2026-09-20

Targets was row-per-macro, matched by keyword on a name column (`classifyTarget`,
`MacroType.fromName`). It is now positional like every other tab — **each row is a complete config
snapshot**, and the row that applies on a date is the one with the greatest `effective from` not
after it.

```
A cal lo   B cal hi   C pro lo   D pro hi   E carb lo  F carb hi  G fat lo  H fat hi
I wl lo    J wl hi    K deficit  L floor
M cal sev  N pro sev  O carb sev P fat sev  Q effective from
```

Three things this changed, none of them cosmetic:

1. **Blank is not `0`.** `Number("")` is `0`, and `deficit` is signable (§6) — so `0` is a legitimate
   value meaning maintenance. A half-filled row collapsing to `0` would silently prescribe
   maintenance instead of refusing to prescribe. `tgNum()` returns `null` for blank, and
   `readTargetConfig` returns `null` if any required field is blank.
2. **The undated pre-history row.** The first row carries the macro bands with a blank date and a
   blank deficit/floor. Blank date means "always applies", so days logged before the first real epoch
   are still judged against bands — without it they drop out of `dayIsSuccessful` entirely and the
   green-day tally silently changes. Blank deficit/floor means `readTargetConfig` still returns
   `null` for those days, so the controller does not prescribe against a config that did not exist.
3. **Only protein, fat, deficit and floor are read by the script.** The calorie and carb bands exist
   for the widget: calories are not graded (§10), and the carb band supplies only the ±half-width
   that `effectiveTargets` rebuilds around the computed `t_carb` centre (§9). Editing carb lo/hi does
   **not** move the carb target — that is set by the anchor. This trap is why the pair is kept as
   lo/hi rather than collapsed to a single half-width column: the widget's band arithmetic reads
   both, and a single column would have to be re-derived in two places.

### Confirmed non-constraints

- **Row order is irrelevant** in Summary and Tracker. `TdeeCalculator` and `WeightCalculator` sort;
  `MacroCalculator` is order-agnostic. Summary does not need sorting.
  **In Targets it is not**: an exact-date tie between two rows is broken by sheet order, later row
  wins, identically in `readTargetConfig` and `CsvParser.parseTargets`/`parseWeightTarget`.
- **Blank and `0` are equivalent** in weight (F) — `num("")` → null → treated as 0.
- **Partial I–L is handled per-macro** by `effectiveTargets`, so the four-cell write need not be
  atomic.

**Known live hazard.** Under a UTC-midnight date regime (the shape a CSV import or a timezone change
leaves behind), `normDate` reads every date one day early, the upsert stops matching, and rows start
duplicating — straight into constraint 4, silently inflating the streak. The differential suite runs
both date regimes for this reason. A `verifySummaryIntegrity()` that logs a warning on duplicate
logical dates is the cheap guard, not yet built.

---

## 15. Payload format · updated 2026-08-23

One JSON value in the Form's `payload` question — a single object or an array of them.

```json
{"cal": 600, "p": 40, "c": 55, "f": 18, "meal": "Lunch", "details": "chicken & rice"}
{"weight": 152.4}
{"gym": "A"}
```

**Dates:** `"date": "YYYY-MM-DD"` (preferred) or `"DD/MM/YYYY"`. `MM/DD/YYYY` is **not** supported and
must never be added — it is indistinguishable from `DD/MM/YYYY` for the first twelve days of any
month, so accepting both would misdate ~40% of entries with no way to recover the intended reading.

ISO is accepted because it is what `parseInputDate` *emits* and what Summary stores. **A parser that
rejects its own output is a trap**, and this one failed silently: an unreadable date fell through to
the submission date, so a back-dated entry landed on today. Both the intake series and the day's
completeness were then wrong, on two days at once.

**Values are case-tolerant** (`normGym` uppercases and trims, so `"a"`/`"A"`/`" b "` all work).
**Keys are not** — see §19.

**Repairing a misdated entry.** The response log is the source of truth: fix the payload text in
`Form responses 1`, then run `rebuildTrackerFromResponses()` → `rebuildAllSummary()`. `rebuildAllSummary`
preserves Summary I–L verbatim, so per-day targets survive. Editing `Tracker` or `Summary` directly
does not survive the next nightly rebuild.

---

## 16. Training burn is not counted · updated 2026-08-23

A `burn` payload field is ignored like any other unknown key. Resistance work is already inside an
intake-anchored TDEE (§1), so crediting calories for it double-counts.

**Changed:** watch → Health Connect ingestion removed (permission grant never worked reliably on
device, and Google Fit is deprecated) → hand-entered flex → flag-gated off 2026-08-09 → deleted
2026-08-23.

**Why deleted rather than left flagged.** A flag implies a decision that might be revisited. This one
will not be: the double-counting argument is structural, not a consequence of bad data, and it does
not stop being true when better burn figures arrive. Leaving it in cost a live conditional on every
ingestion path, two functions that existed only to return 0, and a triple guard around one
subtraction.

**The data was also wrong**, independently: watch "active calories" for resistance training averaged
465 per session, roughly 2× a realistic net cost, producing a ~450 kcal/day swing in the daily target
off a baseline that was itself wrong.

**Summary col H and Tracker col J stay as blank reserved slots** — see §14. Historical burn values
remain in `Form responses 1` and are simply never read.

**To resurrect:** `git log -S BURN_DELTA_ENABLED -- backend/Code.gs`. Note that re-adding it also
means re-adding the `readBurn`/`typicalBurn` groups to the differential harness, or the change is
untested.

---

## 17. Failures must be loud · updated 2026-08-23

Three separate bugs in this project shared one shape: **the system failed silently and the damage
surfaced weeks later.** Ceilings written where centres were expected (§9). An unreadable date filed
under today (§15). Every submission error caught into `Logger.log` and swallowed, so a failed
ingestion produced no row, no error and no notification.

The rule: prefer a loud failure over recoverable data to a silent one over corrupted data.

- **`processMacroPayload` logs with a stack, then re-throws.** Re-throwing marks the trigger execution
  failed, which is what makes Apps Script send its notification. Nothing is lost — the raw payload is
  already in `Form responses 1`, so `rebuildTrackerFromResponses()` recovers it once the cause is
  fixed.
- **A `date` key that is present but unparseable logs `UNPARSEABLE DATE`.** An absent date is
  legitimate; an unreadable one is not.
- **`Logger` prints whenever the slew gate holds a value** (§8).

---

# Part 5 — Rejected architectures

## 18. Correct on position, not on rate — NOT ADOPTED · updated 2026-08-09

**What it would have been.** Alongside holding a fixed deficit, hold a **planned weight line**:
`D_eff = Deficit + clamp((smoothed_weight − planned_weight) × 3500/21, ±150)`.

**The argument for it.** Asking "how fast am I losing?" from a noisy scale gives ±1.18 lb/wk on a
single week — useless for judging a 0.85 target. Asking "am I above or below my line?" gives ±0.7 lb.
Position is a far easier measurement than speed. And a rate measurement *forgets*: week-over-week
comparison never notices a slow 2 lb drift, because each week reads "roughly in range."
Distance-from-line accumulates — signal grows linearly while noise grows as a square root, so
certainty improves the longer the drift persists.

**Why rejected.** At the *measured* ±109 kcal of estimator noise — not the ±430 originally modelled —
it buys ~0.2 lb over 60 days, in exchange for a second feedback loop to reason about. The slew limit
(§8) addresses the same instability more cheaply.

**Revisit only if** weigh-in noise degrades substantially. Recorded so the reasoning is not
rediscovered from scratch.

---

# Part 6 — Open

## 19. Deferred: payload key casing · opened 2026-08-16, deferred 2026-08-23

Payload JSON **keys** are case sensitive and fail silently:

| payload | result |
| --- | --- |
| `{"Gym":"A"}` | dropped, no error |
| `{"Weight":152.4}` | dropped, no error — **loses a weigh-in that feeds the TDEE regression** |
| `{"Cal":600}` | dropped, no error |
| `{"Date":"2026-08-15"}` | **not** dropped — falls through to today, silently misdating the row |

Ranked by damage: `Date` worst (silent *wrong* data is harder to notice than missing data), then
`Weight` (skews the carb target through TDEE), then the rest (the entry just vanishes).

The §17 warning does **not** catch this: a wrong key leaves `item.date` undefined, which is
legitimately "no date supplied". This is the last silent-misdating path left.

**Agreed design, not yet built:**

1. `YYYY-MM-DD` becomes the primary documented format — chosen on typing ergonomics and because it
   sorts. `DD/MM/YYYY` stays accepted as legacy.
2. Lowercase each item's keys once at the top of `payloadItemToRow`, before any field access. ~6
   lines, no behaviour change for correctly-cased payloads, kills the whole class.

Tests: key-casing cases in `backend/test/gym-check.js`, the `"Date"` spelling in `date-check.js`.

---

## 20. Unverified assumptions · updated 2026-08-23

| # | assumption | status |
| --- | --- | --- |
| 1 | adaptive thermogenesis ≈ −12 kcal/lb lost | literature-based, unverified |
| 2 | logging accuracy is consistent over time | assumed; §1 depends on it, and *drift* is what breaks it |
| 3 | 3500 kcal/lb (§5) | literature range 3100–3500; self-cancelling on rate |
| 4 | early fast-loss phase sits outside the window | plausible, now well outside a 42-day window |

Resolved and moved into their sections: TDEE (§2), scale noise and persistence (§4).

---

## 21. Not built · updated 2026-08-23

- **`verifySummaryIntegrity()`** — warn on duplicate logical dates. Cheap guard for the §14 hazard.
- **Step count as a diagnostic column** — never as a calorie input (§1), so a sudden TDEE shift can be
  explained rather than guessed at.
- **`CONFIG-PROPOSAL.md`** — a `Config` sheet making the dials editable without a code change.

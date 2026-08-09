# Proposal: a `Config` sheet

**Status: proposal. No code changed yet.**

Goal: make phase, deadlines, and every personal number configurable — tunable by a human or an AI
agent, safe for a stranger to self-host — without breaking the current widget contract.

---

## The decisive constraint: new rows CANNOT go in `Targets`

This looked like the obvious cheap path. It is actively dangerous.

Both parsers match row names by **substring**:

| parser | matches |
| --- | --- |
| `Code.gs classifyTarget()` | `prot`, `fat`, `floor`, `deficit` |
| `MacroModel.MacroType.fromName()` | `cal`, `prot`, `carb`, `fat` |

So a config row named `kcal_per_lb` contains **`cal`** → the widget parses it as a **Calories band**
with `lower = 3500`. And because `TargetHistory` resolves a blank-date tie by *"the later sheet row
wins"*, a row added *below* the real Calories row **beats it**. Your calorie band silently becomes
3500, and nothing errors.

Same trap for `carb_band_pct` (→ `carb`), `target_body_fat` (→ `fat`), `deficit_kcal` (→ `deficit`).

The three natural names for the dials you want to add are all landmines. **Separate sheet.**

## Why a separate sheet costs nothing

`Code.gs` reads the spreadsheet directly through `SpreadsheetApp` — **it needs no published URL.**
Only the *widget* needs published CSVs, and it fetches exactly two.

Verified: `CsvParser.kt` parses macro bands and the `Weight Loss` row. It does **not** read `Floor`
or `Deficit` — those are `Code.gs`-only. (`README.md` claims the parser reads them. That is wrong
and should be corrected.)

So: engine-only dials in a new `Config` sheet → **no third URL, no app rebuild, no gid change,
nothing in the widget to update.**

## The split, by consumer

| stays in `Targets` (published, widget reads) | moves to `Config` (engine only) |
| --- | --- |
| Calories 1625–1750 — static fallback band | Floor |
| Protein 145–158 — band **and** Code.gs centre | Deficit → becomes `target_rate_lb_per_week` |
| Carbs 160–170 — supplies band half-width | every hardcoded estimator constant |
| Fat 45–50 — band **and** Code.gs centre | phase, dates, goals |
| Weight Loss 0.7–0.9 — Weight page band | |

`Targets` becomes **bands only** — which is the schema it always had. That alone fixes the "these
numbers look random" problem: `Floor` and `Deficit` looked random because they were scalars squatting
in a table built for ranges, with a meaningless empty `Upper` and `UnderSeverity`.

## Layout

Sheet `Config`, one row per dial, header in row 1:

| A `key` | B `value` | C `unit` | D `type` | E `min` | F `max` | G `default` | H `tier` | I `effective_from` | J `description` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

Matched on **exact `key`**, never substring. Unknown keys are ignored, so the sheet is
forward-compatible with a newer `Code.gs`.

### Rows, with your current values migrated

| key | value | unit | type | min | max | tier | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `schema_version` | 1 | | int | | | derived | lets a future `Code.gs` detect and migrate |
| `units` | imperial | | enum | | | basic | `imperial` \| `metric` |
| `phase` | cut | | enum | | | basic | `cut` \| `bulk` \| `maintain` |
| `phase_start` | 2026-06-08 | date | date | | | basic | |
| `goal_date` | | date | date | | | basic | blank = open-ended |
| `goal_note` | visible abs | | text | | | basic | |
| `target_rate_lb_per_week` | 0.85 | lb/wk | number | 0.25 | 1.5 | basic | **the real dial** |
| `deficit_kcal` | *(derived)* | kcal/day | number | −1000 | 1000 | derived | `rate × 500`; negative = surplus |
| `calorie_floor` | 1625 | kcal/day | number | 1200 | 2500 | basic | anti-starve clamp |
| `kcal_per_lb` | 3500 | kcal/lb | number | 3000 | 4000 | advanced | 7700 kcal/kg if metric |
| `tdee_window_days` | 28 | days | int | 14 | 60 | advanced | was hardcoded |
| `intake_complete_frac` | 0.65 | fraction | number | 0 | 0.9 | advanced | was hardcoded |
| `min_weigh_ins` | 8 | count | int | 4 | 30 | advanced | was hardcoded |
| `min_intake_days` | 10 | count | int | 5 | 40 | advanced | was hardcoded |
| `min_span_days` | 14 | days | int | 7 | 40 | advanced | was hardcoded |
| `burn_delta_enabled` | FALSE | | bool | | | basic | **already live as the `BURN_DELTA_ENABLED` constant** (ASSUMPTIONS.md §24); move it here |
| `burn_delta_gain` | 1.0 | ratio | number | 0 | 1 | advanced | scale down an over-generous burn figure |
| `burn_delta_cap` | 400 | kcal | number | 0 | 1000 | advanced | clamp on one day's flex |

### Where `425` came from

`target_rate_lb_per_week = 0.85` → `0.85 × 3500 ÷ 7` = **425**. Exactly your current Deficit.

So the two rows were never contradictory — but 0.85 is not the midpoint of your 0.7–0.9 band (0.8 →
400), and *nothing recorded that choice*. Deriving the deficit from an explicit rate makes the choice
visible and makes it impossible for the two to drift apart later.

## Four opinions

**1. `min`/`max` are the point, not decoration.** They turn guardrails into data. An agent handed
this sheet can validate before writing; a self-hoster cannot set `calorie_floor` to 400 by accident.
This is what makes the config safe to expose, and it belongs in the sheet rather than in code so a
fork can tighten it without editing `Code.gs`.

**2. `tier` keeps the surface small.** `basic` is what a normal user touches. `advanced` is for
someone who has read the source or is driving it with an agent. `derived` is computed and displayed
read-only — never hand-edited. Every extra editable dial is a support burden and a way to break a
working setup.

**3. Units are the #1 fork-breaker.** `kcal_per_lb = 3500` is pound-specific; in kg it is 7700.
Someone will paste kg weigh-ins into a sheet whose engine assumes pounds, and the TDEE estimate will
be wrong by 2.2× with no error message. Store one canonical unit internally, convert only at display.

**4. `goal_date` must NOT feed the deficit.** If a deadline flows into the target calculation, you
have shipped a machine that starves whoever picks an aggressive date. Make it an input to a
**feasibility check** instead — derived, read-only rows:

| key | tier | meaning |
| --- | --- | --- |
| `required_rate_lb_per_week` | derived | `(current − goal_weight) ÷ weeks_remaining` |
| `goal_feasible` | derived | `ok` \| `aggressive` \| `not_achievable` |

Report *"1 Oct needs 1.6 lb/wk — outside your 0.7–0.9 band; either move the date to 12 Nov or accept
a faster rate"* and let the human decide. Never silently obey the date.

This matters for you specifically: at 135 lb you are already running 0.99 lb/wk, above your own band.
A deadline-driven deficit would push you further.

## Deliberately NOT included

No sex, age, or height. The intake-anchored estimator derives total expenditure from energy balance,
so it needs no BMR formula and no demographics. **For an open-source health project, "collects no
personal characteristics" is a feature.** Add them only if you want a predictive-BMR plausibility
rail to catch an absurd TDEE estimate, and mark them optional.

## Migration

1. Create `Config`, paste the rows above. Nothing reads it yet — zero risk.
2. Add `readConfig(ss, key, dateStr)` to `Code.gs` (exact-key match, `effective_from` honoured the
   same way `readTargetConfig` already does it).
3. Point `readTargetConfig` at `Config` for floor and deficit; keep reading protein/fat centres from
   `Targets`. Fall back to the old `Targets` rows if `Config` is missing a key, so a half-migrated
   sheet still works.
4. Replace the module constants with `readConfig` lookups, keeping the current values as defaults.
5. Delete `Floor` and `Deficit` from `Targets` **last**, only after a `updateTargetsToday` run
   confirms the same `t_cal` as before.

Step 5 is the acceptance test: **same inputs must produce the same `t_cal`.** If the number moves,
the migration is wrong.

## Open question

`ChartWorker.kt` holds `GOAL_DATE` / `GOAL_LABEL` as Kotlin constants for the countdown chip — the
widget's own copy of the same idea. Consolidating that into `Config` needs a third published URL and
an app rebuild. Suggest leaving it until the next time the app is being built anyway.

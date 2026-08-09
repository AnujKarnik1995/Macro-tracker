// ===== CONFIG =====
const TRACKER_TAB = "Tracker";            // raw log: meals (A-G), weigh-ins (H), training burn (J)
const SUMMARY_TAB = "Summary";            // STATIC daily totals the widget reads
const RESPONSES_TAB = "Form responses 1"; // raw Google Form submissions (true source of truth)
const TARGETS_TAB = "Targets";            // CONFIG the widget reads: macro bands + weight band + Floor + Deficit,
                                          // dated via an EffectiveFrom column (col E). Change name here if yours differs.

// Tracker columns:  A date(0) B meal(1) C details(2) D cal(3) E p(4) F c(5) G f(6) H weight(7) I unused(8) J burn(9)
// Summary columns:  A date(0) B cal(1) C p(2) D c(3) E f(4) F weight(5) G unused(6) H burn(7)
//                   I t_cal(8) J t_pro(9) K t_carb(10) L t_fat(11)   <- per-day target CENTERS (updateDailyTargets)
// Col G/I ("unused") is a dead slot, kept BLANK on purpose — Summary is parsed by POSITION, so
// collapsing it would shift cols I-L and re-score history. ASSUMPTIONS.md §11.
const SUMMARY_HEADER = ["date", "cal", "p", "c", "f", "weight", "unused", "burn", "t_cal", "t_pro", "t_carb", "t_fat"];

// ----- TDEE / dynamic-target compute -----
// 28 not 20: in a 20-day fit the 4 edge weigh-ins carry ~58% of the slope, so one water-low reading
// at the edge swings TDEE by hundreds of kcal. Caused the 2 Aug 2026 overshoot. ASSUMPTIONS.md §8.
const TDEE_WINDOW_DAYS = 28;   // trailing window (completed days) for TDEE + typical-burn baseline
const KCAL_PER_LB = 3500;
const MIN_WEIGH_INS = 8, MIN_INTAKE_DAYS = 10, MIN_SPAN_DAYS = 14;   // data bar before targets compute
const INTAKE_COMPLETE_FRAC = 0.65;   // a day below this fraction of the window median = unfinished log

// ----- Training-burn flex: OFF -----
// false = the workout delta is switched off end to end. Burn payloads are not ingested, existing
// burn values are not read, and Summary col H is written blank — so clearing it survives a rebuild,
// which clearing the cells by hand does not (the values live in Form responses -> Tracker -> Summary).
//
// Off because the historical numbers are watch "active calories" for resistance work, averaging 465
// per session — roughly 2x a realistic net cost. Mixed with hand-entered figures they produced a
// ~450 kcal/day swing in the daily target off a baseline that was itself wrong. The training is
// already inside the measured TDEE (§5), so nothing is lost by ignoring it.
//
// Setting this back to true re-enables everything, but Tracker will be missing the burn rows: run
// rebuildTrackerFromResponses to restore them from the response log, then rebuildAllSummary.
// ASSUMPTIONS.md §24.
const BURN_DELTA_ENABLED = false;

// Basal/BMR is never processed at all — not gated, removed. It was only ever written to a column
// nothing read, and an intake-anchored TDEE cannot need it (§4). Payload items carrying only
// `basal` are dropped by payloadItemToRow.

function processMacroPayload(e) {
  if (!e || !e.values) {
    throw new Error("This script requires a form submission event to run. Do not click 'Run' in the editor.");
  }

  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);

  try {
    let data = JSON.parse(e.values[1]);
    if (!Array.isArray(data)) data = [data];

    const today = todayStr();                  // spreadsheet TZ; used when an item carries no "date"
    const newRows = [];                        // batched: one write instead of one per item
    const want = { macros: {}, weight: {}, burn: {} };

    data.forEach(item => {
      // OPTIONAL back-date: item.date "DD/MM/YYYY" (post-midnight or forgotten entries)
      const mapped = payloadItemToRow(item, today);
      if (!mapped) return;                     // nothing usable in this item
      newRows.push(mapped.row);
      if (mapped.kind === "weight")    want.weight[mapped.date] = true;
      else if (mapped.kind === "burn") want.burn[mapped.date]   = true;
      else                             want.macros[mapped.date] = true;
    });

    if (newRows.length) {
      tracker.getRange(tracker.getLastRow() + 1, 1, newRows.length, TRACKER_WIDTH).setValues(newRows);
    }

    // Every touched date recomputed from ONE Tracker read and ONE Summary read.
    const ctx = refreshSummary(ss, want);

    // A burn entry changes that day's workout delta, so re-derive its targets now rather than waiting
    // for the 14:30 trigger — training logged after 14:30 would otherwise never reach the target it
    // was meant to adjust. ctx.sum already reflects the burn just written.
    Object.keys(want.burn).forEach(d => updateDailyTargets(d, ctx));

  } catch (err) {
    Logger.log("Error processing payload: " + err);
  }
}

/** A usable number, or null. The single numeric guard for the whole file — cell values, payload
 *  fields and config cells all go through here instead of open-coding !isNaN(Number(x)). */
function num(v) {
  if (v === undefined || v === null || v === "") return null;
  const n = Number(v);
  return isNaN(n) ? null : n;
}

// Tracker is written 10 columns wide, uniformly:
//   A date  B meal  C details  D cal  E p  F c  G f  H weight  I unused  J burn
const TRACKER_WIDTH = 10;

/**
 * Turns one Form payload item into a Tracker row: {kind, date, row}, or **null** when the item
 * carries nothing usable.
 *
 * The null case is real. 20 historical payloads are `{basal, date}` from the failed Health Connect
 * backfill; basal is dead (§4), so they hold no information — yet the old code fell through to the
 * meal branch and wrote 20 blank rows into Tracker, all dated 12 Jul. They never reached Summary
 * (no macros, no weight, no burn) but they polluted the log. Now skipped.
 *
 * Single source of truth, shared by processMacroPayload and rebuildTrackerFromResponses — which
 * previously each carried their own copy, already differing in padding width.
 */
function payloadItemToRow(item, fallbackDate) {
  const d = parseInputDate(item && item.date) || fallbackDate;
  const pad = v => { const r = []; for (let i = 0; i < TRACKER_WIDTH; i++) r.push(v[i] === undefined ? "" : v[i]); return r; };

  if (isWeightEntry(item)) return { kind: "weight", date: d, row: pad([d, "Weigh-in", "", "", "", "", "", num(item.weight)]) };
  if (isBurnEntry(item))   return { kind: "burn",   date: d, row: pad([d, "Burn", "", "", "", "", "", "", "", num(item.burn)]) };

  const hasMacros = num(item && item.cal) !== null || num(item && item.p) !== null ||
                    num(item && item.c)   !== null || num(item && item.f) !== null;
  if (!hasMacros) return null;

  return { kind: "meal", date: d, row: pad([d, item.meal || "", item.details || "",
           numOrBlank(item.cal), numOrBlank(item.p), numOrBlank(item.c), numOrBlank(item.f)]) };
}

/** A payload item is a weigh-in if it carries a numeric `weight`. */
function isWeightEntry(item) { return !!item && num(item.weight) !== null; }

/** A payload item is a burn entry if it carries a numeric `burn` — the day's training calories,
 *  entered by hand through the Form. (Formerly fed by the watch; that path was removed.) */
function isBurnEntry(item) { return BURN_DELTA_ENABLED && !!item && num(item.burn) !== null; }

/** Number, or "" if absent/blank/non-numeric — for writing back into a cell. */
function numOrBlank(v) { const n = num(v); return n === null ? "" : n; }

/** Accepts an optional date string in DD/MM/YYYY (matching the form's timestamp)
 *  and returns it normalized to canonical "YYYY-MM-DD", or null if absent/invalid. */
function parseInputDate(s) {
  if (typeof s !== "string") return null;
  const mt = s.trim().match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);  // DD/MM/YYYY only
  if (!mt) return null;
  const d = +mt[1], m = +mt[2], y = +mt[3];
  const dt = new Date(y, m - 1, d);                              // reject impossible dates
  if (dt.getFullYear() !== y || dt.getMonth() !== m - 1 || dt.getDate() !== d) return null;
  const pad = n => (n < 10 ? "0" + n : "" + n);
  return y + "-" + pad(m) + "-" + pad(d);                        // canonical YYYY-MM-DD
}

/**
 * ONE pass over Tracker, aggregating every date at once. Replaces four separate scans that each
 * re-read the whole sheet and re-implemented the same arithmetic — which is how the last-value-wins
 * burn bug survived in rebuildAllSummary after being fixed in updateBurnSummary.
 *
 * Returns { "yyyy-MM-dd": {cal, p, c, f, wSum, wN, burn, burnSeen} }.
 */
function aggregateTracker(rows) {
  const agg = {};
  for (let i = 1; i < rows.length; i++) {            // skip header row
    const d = normDate(rows[i][0]);
    if (!d) continue;
    let a = agg[d];
    if (!a) a = agg[d] = { cal: 0, p: 0, c: 0, f: 0, wSum: 0, wN: 0, burn: 0, burnSeen: false };
    a.cal += num(rows[i][3]) || 0;
    a.p   += num(rows[i][4]) || 0;
    a.c   += num(rows[i][5]) || 0;
    a.f   += num(rows[i][6]) || 0;
    const w = num(rows[i][7]);                       // H weight
    if (w !== null && w > 0) { a.wSum += w; a.wN++; }
    if (BURN_DELTA_ENABLED) {
      const b = num(rows[i][9]);                     // J burn — every session for the date adds up
      if (b !== null) { a.burn += b; a.burnSeen = true; }
    }
  }
  return agg;
}

/** The aggregate for one date, or a zeroed one if Tracker has no rows for it. */
function trackerDay(agg, dateStr) {
  return agg[dateStr] || { cal: 0, p: 0, c: 0, f: 0, wSum: 0, wN: 0, burn: 0, burnSeen: false };
}

/** A date's weight, averaged and rounded to 0.1 lb, or "" when there were no weigh-ins. */
function dayWeight(a) { return a.wN > 0 ? Math.round((a.wSum / a.wN) * 10) / 10 : ""; }

/**
 * Recomputes the requested Summary cells for each date, from ONE Tracker read and ONE Summary read.
 * `want` is {macros:{date:true}, weight:{...}, burn:{...}} — only the listed column groups are
 * written, so a meal submission never disturbs weight, burn or the per-day targets.
 *
 * Returns {summary, sum}. Because upsertSummary keeps `sum` in step with what it writes, the caller
 * can pass that straight to updateDailyTargets and it cannot see stale data.
 */
function refreshSummary(ss, want) {
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const summary = sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);
  const agg = aggregateTracker(tracker.getDataRange().getValues());
  const sum = summary.getDataRange().getValues();

  Object.keys(want.macros || {}).forEach(d => {
    const a = trackerDay(agg, d);
    upsertSummary(summary, sum, d, 1, [d, a.cal, a.p, a.c, a.f], true);
  });
  Object.keys(want.weight || {}).forEach(d => {
    const a = trackerDay(agg, d);
    upsertSummary(summary, sum, d, 6, [dayWeight(a)], a.wN > 0);
  });
  Object.keys(want.burn || {}).forEach(d => {
    const a = trackerDay(agg, d);
    upsertSummary(summary, sum, d, 8, [a.burnSeen ? a.burn : ""], a.burnSeen);
  });
  return { summary: summary, sum: sum };
}

/** Recomputes the macro cells (A-E) for one date. Weight, burn and targets untouched. */
function updateDailySummary(dateStr) {
  const m = {}; m[dateStr] = true;
  refreshSummary(SpreadsheetApp.getActiveSpreadsheet(), { macros: m });
}

/** Recomputes the weight cell (F) for one date, averaging that date's weigh-ins to 0.1 lb. */
function updateWeightSummary(dateStr) {
  const m = {}; m[dateStr] = true;
  refreshSummary(SpreadsheetApp.getActiveSpreadsheet(), { weight: m });
}

/** Recomputes the burn cell (H) for one date. Multiple sessions on a date are ADDED. §12 */
function updateBurnSummary(dateStr) {
  const m = {}; m[dateStr] = true;
  refreshSummary(SpreadsheetApp.getActiveSpreadsheet(), { burn: m });
}

/**
 * The spreadsheet timezone, fetched at most ONCE per execution. normDate() runs on every date cell
 * of every row scan; fetching the tz per cell cost ~2,200 service calls per form submission.
 * Each Apps Script execution gets a fresh global scope, so the cache can't go stale.
 * ASSUMPTIONS.md §16.
 */
let _sheetTz = null;
function sheetTz() {
  if (_sheetTz === null) _sheetTz = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
  return _sheetTz;
}

/** Today in the spreadsheet timezone, as canonical "yyyy-MM-dd". */
function todayStr() {
  return Utilities.formatDate(new Date(), sheetTz(), "yyyy-MM-dd");
}

/** Normalizes a cell to "yyyy-MM-dd" whether it comes back as a Date or a string. */
function normDate(v) {
  if (v && Object.prototype.toString.call(v) === "[object Date]") {
    return Utilities.formatDate(v, sheetTz(), "yyyy-MM-dd");
  }
  return String(v).trim();
}

/**
 * Writes `values` into the Summary row for `dateStr`, starting at 1-based column `col`.
 * Missing date + appendIfMissing -> appends a full-width row with the values at that offset.
 * Missing date + !appendIfMissing -> does nothing (a weight/burn updater with nothing to record).
 *
 * `rows` is mutated to match what was written, so a caller holding one snapshot across several
 * upserts stays consistent — without that, a second new date in the same run would not see the
 * first append, would also miss, and would append a SECOND row for it. Duplicate Summary rows are
 * the one corruption the widget cannot survive: nothing there dedupes, and successfulDays() counts
 * rows, so a duplicated green day is counted twice. ASSUMPTIONS.md §17.
 */
function upsertSummary(summary, rows, dateStr, col, values, appendIfMissing) {
  for (let i = 1; i < rows.length; i++) {
    if (normDate(rows[i][0]) === dateStr) {
      summary.getRange(i + 1, col, 1, values.length).setValues([values]);
      for (let j = 0; j < values.length; j++) rows[i][col - 1 + j] = values[j];
      return true;
    }
  }
  if (!appendIfMissing) return false;
  const row = [];
  for (let j = 0; j < SUMMARY_HEADER.length; j++) row.push("");
  row[0] = dateStr;
  for (let j = 0; j < values.length; j++) row[col - 1 + j] = values[j];
  summary.appendRow(row);
  rows.push(row);
  return true;
}

/** Returns the sheet by name, creating it (with `header`) if missing or empty. */
function sheetWithHeader(ss, name, header) {
  let sh = ss.getSheetByName(name);
  if (!sh) { sh = ss.insertSheet(name); sh.appendRow(header); }
  else if (sh.getLastRow() === 0) sh.appendRow(header);
  return sh;
}

/** Run manually to recompute TODAY's macro + weight + burn cells immediately (no form submit). */
function rebuildToday() {
  const today = todayStr();
  updateDailySummary(today);
  updateWeightSummary(today);
  updateBurnSummary(today);
}

/** REPAIR TOOL: wipes Summary and rebuilds every day's macros, weight AND burn from Tracker.
 *  One clean row per date, sorted; weight averaged (0.1 lb); training burn summed. */
function rebuildAllSummary() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const agg = aggregateTracker(tracker.getDataRange().getValues());   // the same pass the updaters use

  const summary = sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);

  // Preserve any already-computed per-day targets (cols I-L) across the rebuild.
  const prior = summary.getDataRange().getValues();
  const g = x => (x === undefined ? "" : x);
  const tByDate = {};
  for (let i = 1; i < prior.length; i++) {
    const d = normDate(prior[i][0]);
    if (d) tByDate[d] = [g(prior[i][8]), g(prior[i][9]), g(prior[i][10]), g(prior[i][11])];
  }

  summary.clearContents();
  summary.appendRow(SUMMARY_HEADER);

  const dates = Object.keys(agg).sort();   // yyyy-MM-dd sorts chronologically
  if (dates.length) {
    const out = dates.map(d => {
      const a = agg[d], t = tByDate[d] || ["", "", "", ""];
      return [d, a.cal, a.p, a.c, a.f, dayWeight(a),
              "",                              // col G intentionally blank (see SUMMARY_HEADER note)
              a.burnSeen ? a.burn : "",
              t[0], t[1], t[2], t[3]];
    });
    summary.getRange(2, 1, out.length, SUMMARY_HEADER.length).setValues(out);
  }
}

/** REPAIR TOOL: re-derives Tracker (meals + weigh-ins + burn) from "Form responses", then
 *  rebuilds Summary. Use after you manually edit/correct a form response.
 *  WARNING: REPLACES Tracker's data from the responses -- direct Tracker edits are lost. */
function rebuildTrackerFromResponses() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const responses = ss.getSheetByName(RESPONSES_TAB);
  const tracker = ss.getSheetByName(TRACKER_TAB);

  const resRows = responses.getDataRange().getValues();   // [timestamp, payload, ...]
  const out = [];
  const tally = { meal: 0, weight: 0, burn: 0 };
  let unparseable = 0, empty = 0;

  for (let i = 1; i < resRows.length; i++) {              // skip header
    const payload = resRows[i][1];
    if (!payload) continue;
    let data;
    try {
      data = JSON.parse(payload);
    } catch (err) {
      unparseable++;
      Logger.log("Skipped unparseable response on row " + (i + 1) + ": " + err);
      continue;
    }
    if (!Array.isArray(data)) data = [data];
    const fallbackDate = normDate(resRows[i][0]) || todayStr();
    data.forEach(item => {
      const mapped = payloadItemToRow(item, fallbackDate);
      if (!mapped) { empty++; return; }       // e.g. the legacy {basal,date} items — no information
      out.push(mapped.row);
      tally[mapped.kind]++;
    });
  }

  // Replace Tracker's data rows, keeping the header
  const tLast = tracker.getLastRow();
  if (tLast > 1) tracker.getRange(2, 1, tLast - 1, TRACKER_WIDTH).clearContent();
  if (out.length) tracker.getRange(2, 1, out.length, TRACKER_WIDTH).setValues(out);

  rebuildAllSummary();

  Logger.log("Rebuilt from " + (resRows.length - 1) + " responses: " + tally.meal + " meals, " +
             tally.weight + " weigh-ins, " + tally.burn + " burn, " + empty +
             " items with nothing usable, " + unparseable + " unparseable payloads.");
}

// ===== DYNAMIC TARGETS (TDEE + workout → per-day carb-band center) =====

/** Manual entry point: compute + write TODAY's targets. Safe to run from the editor. */
function updateTargetsToday() {
  updateDailyTargets(todayStr());
}

/**
 * Computes the day's target CENTERS and writes Summary cols I-L (t_cal, t_pro, t_carb, t_fat):
 *   anchor = max(TDEE + (today's burn − typical burn) − deficit, floor)
 *   t_carb = (anchor − 4·protein_center − 9·fat_center) / 4        (carbs are the plug)
 *   t_pro / t_fat = fixed band centers from config; t_cal = anchor (display/context only).
 * Leaves the row's I-L untouched (widget then falls back to the static bands) if the config
 * isn't complete or TDEE isn't ready. No training logged → burn 0 → negative delta vs the
 * all-days baseline, which is correct: a rest day costs less than an average day.
 */
function updateDailyTargets(dateStr, ctx) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const summary = ctx ? ctx.summary : sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);

  // ONE Summary read, shared by all four consumers below (was four separate reads). A caller that
  // already holds a snapshot passes it in; upsertSummary keeps such snapshots in step with its
  // writes, so reusing one cannot serve stale data.
  const rows = ctx ? ctx.sum : summary.getDataRange().getValues();

  const cfg = readTargetConfig(ss, dateStr);
  const tdee = computeTdee(rows, dateStr);
  if (!cfg || tdee === null) {
    Logger.log("Targets skipped for " + dateStr + " (config incomplete or not enough data yet).");
    return;
  }

  // Workout flex, or a flat 0 when BURN_DELTA_ENABLED is false.
  const delta = BURN_DELTA_ENABLED
    ? readBurn(rows, dateStr) - typicalBurn(rows, dateStr)   // both all-days figures — like for like
    : 0;

  const anchor = Math.max(tdee + delta - cfg.deficit, cfg.floor);
  const tCarb = Math.max(0, (anchor - 4 * cfg.pCenter - 9 * cfg.fCenter) / 4);

  // ONE DECIMAL on the centres — do NOT Math.round() to whole grams. The widget rebuilds each band
  // as [centre ± halfWidth], and a .5 centre rounded up shifts the whole band up 0.5 g.
  // ASSUMPTIONS.md §14.
  const r1 = x => Math.round(x * 10) / 10;
  const vals = [Math.round(anchor), r1(cfg.pCenter), r1(tCarb), r1(cfg.fCenter)];

  upsertSummary(summary, rows, dateStr, 9, vals, true);   // cols I-L
}

/**
 * TDEE over the trailing window ending YESTERDAY (completed days only):
 *   TDEE = avg intake − weight_slope(lb/day) × 3500,  slope = least-squares over the weigh-ins.
 *
 * UNWEIGHTED, deliberately: an exponentially-weighted fit lost to plain least squares at every
 * half-life tested, because up-weighting recent points re-creates the endpoint leverage the longer
 * window exists to remove. Intake days below INTAKE_COMPLETE_FRAC of the window MEDIAN are dropped
 * as incomplete logs. Returns null if below the data bar. ASSUMPTIONS.md §8, §13.
 */
function computeTdee(src, dateStr) {
  const slice = windowRows(src, dateStr);
  if (!slice) return null;

  const startNum = dayNumber(slice.start);
  const weights = [];   // [dayIndex, weight]
  const rawIntakes = [];
  slice.rows.forEach(r => {
    const w = num(r.row[5]);      // F weight
    if (w !== null && w > 0) weights.push([dayNumber(r.date) - startNum, w]);
    const cal = num(r.row[1]);    // B cal
    if (cal !== null && cal > 0) rawIntakes.push(cal);
  });

  // Drop incomplete logs BEFORE the count check, so a half-logged day can't satisfy the data bar.
  const intakes = completeIntakes(rawIntakes);

  if (weights.length < MIN_WEIGH_INS || intakes.length < MIN_INTAKE_DAYS) return null;

  let minx = Infinity, maxx = -Infinity;
  weights.forEach(p => { minx = Math.min(minx, p[0]); maxx = Math.max(maxx, p[0]); });
  if ((maxx - minx + 1) < MIN_SPAN_DAYS) return null;

  const slope = regressionSlope(weights);             // lb per day
  const avgIntake = intakes.reduce((a, b) => a + b, 0) / intakes.length;
  return avgIntake - slope * KCAL_PER_LB;
}

/**
 * Filters out days whose logged calories are implausibly low for a COMPLETE day — a log that was
 * started and abandoned, not a genuinely light day of eating.
 *
 * Judged against the window's own MEDIAN — not a fixed number, and deliberately NOT against protein
 * (most low-protein days are real full days of eating badly; dropping them would inflate TDEE).
 * Median not mean, so an outlier can't move its own threshold. No-ops on small samples.
 * ASSUMPTIONS.md §13.
 */
function completeIntakes(cals) {
  if (cals.length < 7) return cals.slice();
  const sorted = cals.slice().sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  const median = (sorted.length % 2) ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  const cutoff = INTAKE_COMPLETE_FRAC * median;
  const kept = cals.filter(c => c >= cutoff);
  // Never let the filter gut the sample; if it would, trust the raw data instead.
  return (kept.length >= Math.max(MIN_INTAKE_DAYS, Math.ceil(cals.length * 0.6))) ? kept : cals.slice();
}

/**
 * Average training burn (col H) over the trailing window, counting EVERY calendar day in the
 * window — a blank cell is a rest day worth 0, not a day to exclude.
 *
 * Averaging only the LOGGED days (the old behaviour) made a rest day score better than a light
 * session. All-days averaging makes avg(delta) = 0 over the window by construction, which is what
 * stops the workout flex quietly eating into the deficit. ASSUMPTIONS.md §12.
 */
function typicalBurn(src, dateStr) {
  if (!BURN_DELTA_ENABLED) return 0;
  const slice = windowRows(src, dateStr);
  if (!slice || !slice.rows.length) return 0;
  let sum = 0;
  slice.rows.forEach(r => {
    const b = num(r.row[7]);         // H burn — blank counts as a real 0 (rest day)
    sum += (b === null ? 0 : b);
  });
  return sum / slice.rows.length;    // every day in the window counts, not just logged ones
}

/**
 * Summary's values, from either a Spreadsheet (reads it) or an already-read values array (returns
 * it as-is). Lets one caller read Summary ONCE and hand the same array to every consumer, without
 * changing any public signature. Returns null if Summary is missing.
 */
function summaryValues(src) {
  if (Array.isArray(src)) return src;
  const sh = src.getSheetByName(SUMMARY_TAB);
  return sh ? sh.getDataRange().getValues() : null;
}

/**
 * Summary rows inside the trailing TDEE window (ends YESTERDAY — completed days only), as
 * [{date, row}]. Shared by computeTdee and typicalBurn so the two can never disagree about which
 * days are in scope. Returns null if Summary is missing.
 */
function windowRows(src, dateStr) {
  const rows = summaryValues(src);
  if (!rows) return null;
  const end = addDays(dateStr, -1);
  const start = addDays(end, -(TDEE_WINDOW_DAYS - 1));
  const out = [];
  for (let i = 1; i < rows.length; i++) {
    const d = normDate(rows[i][0]);
    if (!d || d < start || d > end) continue;
    out.push({ date: d, row: rows[i] });
  }
  return { start: start, end: end, rows: out };
}

/**
 * The day's training burn (col H), 0 when blank — must match typicalBurn's all-days baseline or the
 * delta compares different things. A same-day entry logged later re-triggers updateDailyTargets from
 * processMacroPayload. ASSUMPTIONS.md §12.
 */
function readBurn(src, dateStr) {
  if (!BURN_DELTA_ENABLED) return 0;
  const rows = summaryValues(src);
  if (!rows) return 0;
  for (let i = 1; i < rows.length; i++) {
    if (normDate(rows[i][0]) === dateStr) {
      const b = num(rows[i][7]);
      return b === null ? 0 : b;
    }
  }
  return 0;
}

/**
 * Reads the dated config from the Targets tab as-of `dateStr` (latest EffectiveFrom ≤ date, col E;
 * blank date = always applies). Needs protein + fat band rows plus `Floor` and `Deficit` rows.
 * Protein/fat centers come from their existing bands; deficit is signable (negative = surplus for
 * a future bulk). Returns null if any required row is missing.
 */
function readTargetConfig(ss, dateStr) {
  const sh = ss.getSheetByName(TARGETS_TAB);
  if (!sh) return null;
  const rows = sh.getDataRange().getValues();   // A name, B lower, C upper, D severity, E EffectiveFrom
  const pick = {};
  for (let i = 1; i < rows.length; i++) {
    const key = classifyTarget(rows[i][0]);
    if (!key) continue;
    const eff = normDate(rows[i][4]) || "0000-00-00";   // blank = always applies
    if (eff > dateStr) continue;                         // future row, not yet in effect
    if (!pick[key] || eff >= pick[key].eff) {
      pick[key] = { lower: Number(rows[i][1]), upper: Number(rows[i][2]), eff: eff };
    }
  }
  const p = pick.protein, f = pick.fat, fl = pick.floor, de = pick.deficit;
  if (!p || !f || !fl || !de) return null;
  if (isNaN(p.lower) || isNaN(p.upper) || isNaN(f.lower) || isNaN(f.upper) ||
      isNaN(fl.lower) || isNaN(de.lower)) return null;
  return {
    pCenter: (p.lower + p.upper) / 2,
    fCenter: (f.lower + f.upper) / 2,
    floor: fl.lower,
    deficit: de.lower
  };
}

/** Maps a Targets row name to a config key. Weight/carb/other rows return null (unused here). */
function classifyTarget(raw) {
  const s = String(raw).toLowerCase();
  if (s.indexOf("prot") >= 0) return "protein";
  if (s.indexOf("fat") >= 0) return "fat";
  if (s.indexOf("floor") >= 0) return "floor";
  if (s.indexOf("deficit") >= 0) return "deficit";
  return null;
}

/** Least-squares slope of y vs x for [[x,y],...]. */
function regressionSlope(pts) {
  let mx = 0, my = 0;
  pts.forEach(p => { mx += p[0]; my += p[1]; });
  mx /= pts.length; my /= pts.length;
  let num = 0, den = 0;
  pts.forEach(p => { const dx = p[0] - mx; num += dx * (p[1] - my); den += dx * dx; });
  return den === 0 ? 0 : num / den;
}

// ----- yyyy-MM-dd date arithmetic: pure string/integer math, no timezone involved -----

/** Days since the epoch for a "yyyy-MM-dd" string. UTC, so DST can never shift the count. */
function dayNumber(dateStr) {
  const p = dateStr.split("-");
  return Math.round(Date.UTC(+p[0], +p[1] - 1, +p[2]) / 86400000);
}

function addDays(dateStr, n) {
  const dt = new Date((dayNumber(dateStr) + n) * 86400000);
  const pad = x => (x < 10 ? "0" + x : "" + x);
  return dt.getUTCFullYear() + "-" + pad(dt.getUTCMonth() + 1) + "-" + pad(dt.getUTCDate());
}

function daysBetween(a, b) { return dayNumber(b) - dayNumber(a); }

/** Replaces any existing daily trigger for `handler` with one at ~hour:minute (script timezone). */
function installDailyTrigger(handler, hour, minute) {
  ScriptApp.getProjectTriggers().forEach(t => {
    if (t.getHandlerFunction() === handler) ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger(handler).timeBased().atHour(hour).nearMinute(minute).everyDays(1).create();
  Logger.log(handler + " trigger installed (~" + hour + ":" + minute + ", script timezone).");
}

/** Run ONCE from the editor: daily ~14:30 recompute of today's targets. */
function createTargetsTrigger() { installDailyTrigger("updateTargetsToday", 14, 30); }

/** Run ONCE from the editor: nightly ~00:45 rebuild of Tracker + Summary from the form responses. */
function createNightlyRebuildTrigger() { installDailyTrigger("rebuildTrackerFromResponses", 0, 45); }

/**
 * Run ONCE from the editor to bootstrap the logging Form.
 * Creates the "Macro Log" Form (a single `payload` question that takes a JSON string), links its
 * responses to THIS spreadsheet (the `Form responses 1` tab that `RESPONSES_TAB` expects), and
 * installs the on-submit trigger to `processMacroPayload`. The submit event is spreadsheet-bound,
 * so `e.values[1]` carries the payload — matching how `processMacroPayload` reads it.
 *
 * Re-running creates ANOTHER Form; the trigger is de-duplicated but the Form is not. First run
 * prompts for Forms + trigger authorization. Prints the fill-in and edit URLs to the log.
 */
function createLoggingForm() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();

  const form = FormApp.create("Macro Log");
  form.setDescription(
    'Submit ONE JSON payload per entry. Examples:\n' +
    'meal:     {"cal":600,"p":40,"c":55,"f":18,"meal":"Lunch","details":"chicken & rice"}\n' +
    'weigh-in: {"weight":152.4}\n' +
    'Add "date":"DD/MM/YYYY" to back-date. An array of objects logs several at once.');
  form.addParagraphTextItem().setTitle("payload").setRequired(true);

  // Responses land in this spreadsheet as the "Form responses 1" tab (RESPONSES_TAB).
  form.setDestination(FormApp.DestinationType.SPREADSHEET, ss.getId());

  // Spreadsheet-bound on-submit trigger → processMacroPayload (provides e.values). De-dup first.
  ScriptApp.getProjectTriggers().forEach(t => {
    if (t.getHandlerFunction() === "processMacroPayload") ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger("processMacroPayload").forSpreadsheet(ss).onFormSubmit().create();

  Logger.log("Form created.\n  Fill in: " + form.getPublishedUrl() + "\n  Edit:    " + form.getEditUrl());
}

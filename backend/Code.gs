// ===== CONFIG =====
const TRACKER_TAB = "Tracker";            // raw log: meals (A-G), weigh-ins (H), burn (I basal / J active)
const SUMMARY_TAB = "Summary";            // STATIC daily totals the widget reads
const RESPONSES_TAB = "Form responses 1"; // raw Google Form submissions (true source of truth)
const TARGETS_TAB = "Targets";            // CONFIG the widget reads: macro bands + weight band + Floor + Deficit,
                                          // dated via an EffectiveFrom column (col E). Change name here if yours differs.

// Tracker columns:  A date(0) B meal(1) C details(2) D cal(3) E p(4) F c(5) G f(6) H weight(7) I basal(8) J burn(9)
// Summary columns:  A date(0) B cal(1) C p(2) D c(3) E f(4) F weight(5) G basal(6) H burn(7)
//                   I t_cal(8) J t_pro(9) K t_carb(10) L t_fat(11)   <- per-day target CENTERS (updateDailyTargets)
const SUMMARY_HEADER = ["date", "cal", "p", "c", "f", "weight", "basal", "burn", "t_cal", "t_pro", "t_carb", "t_fat"];

// ----- TDEE / dynamic-target compute -----
const TDEE_WINDOW_DAYS = 20;   // trailing window (completed days) for TDEE + typical-burn baseline
const KCAL_PER_LB = 3500;
const MIN_WEIGH_INS = 8, MIN_INTAKE_DAYS = 10, MIN_SPAN_DAYS = 14;   // data bar before targets compute

function processMacroPayload(e) {
  if (!e || !e.values) {
    throw new Error("This script requires a form submission event to run. Do not click 'Run' in the editor.");
  }

  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const payloadString = e.values[1];

  try {
    let data = JSON.parse(payloadString);
    if (!Array.isArray(data)) data = [data];

    // Today (spreadsheet TZ). Used when an item has no explicit "date".
    const today = Utilities.formatDate(new Date(), ss.getSpreadsheetTimeZone(), "yyyy-MM-dd");

    const mealDates = {};     // macro dates whose Summary row must be recomputed
    const weightDates = {};   // dates whose Summary weight cell must be recomputed
    const burnDates = {};     // dates whose Summary basal/burn cells must be recomputed
    data.forEach(item => {
      // OPTIONAL back-date: item.date "DD/MM/YYYY" (for post-midnight / forgotten entries)
      const rowDate = parseInputDate(item.date) || today;
      if (isWeightEntry(item)) {
        // Weigh-in row: date + weight in col H; macro columns left blank.
        tracker.appendRow([rowDate, "Weigh-in", "", "", "", "", "", Number(item.weight)]);
        weightDates[rowDate] = true;
      } else if (isBurnEntry(item)) {
        // Burn row: watch energy for the day — basal in col I, active/exercise in col J.
        tracker.appendRow([rowDate, "Burn", "", "", "", "", "", "", numOrBlank(item.basal), numOrBlank(item.burn)]);
        burnDates[rowDate] = true;
      } else {
        tracker.appendRow([
          rowDate,
          item.meal,
          item.details || "",
          item.cal,
          item.p,
          item.c,
          item.f
        ]);
        mealDates[rowDate] = true;
      }
    });

    // Recompute the static Summary for EVERY date this submission touched
    Object.keys(mealDates).forEach(d => updateDailySummary(d));
    Object.keys(weightDates).forEach(d => updateWeightSummary(d));
    Object.keys(burnDates).forEach(d => updateBurnSummary(d));

  } catch (err) {
    Logger.log("Error processing payload: " + err);
  }
}

/** A payload item is a weigh-in if it carries a numeric `weight`. */
function isWeightEntry(item) {
  return item && item.weight !== undefined && item.weight !== null && item.weight !== "" &&
         !isNaN(Number(item.weight));
}

/** A payload item is a burn entry if it carries a numeric `burn` (active kcal) and/or `basal`
 *  (BMR kcal). These come from the watch via Health Connect. */
function isBurnEntry(item) {
  const hasBurn  = item && item.burn  !== undefined && item.burn  !== null && item.burn  !== "" && !isNaN(Number(item.burn));
  const hasBasal = item && item.basal !== undefined && item.basal !== null && item.basal !== "" && !isNaN(Number(item.basal));
  return hasBurn || hasBasal;
}

/** Number, or "" if absent/blank/non-numeric. */
function numOrBlank(v) {
  return (v === undefined || v === null || v === "" || isNaN(Number(v))) ? "" : Number(v);
}

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
 * Sums Tracker macro columns for `dateStr` and upserts the macro cells (A-E) of that
 * date's Summary row. Leaves weight (F) and burn (G-H) untouched.
 */
function updateDailySummary(dateStr) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const summary = sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);

  const rows = tracker.getDataRange().getValues();
  let cal = 0, p = 0, c = 0, f = 0;
  for (let i = 1; i < rows.length; i++) {            // skip header row
    if (normDate(rows[i][0]) === dateStr) {
      cal += Number(rows[i][3]) || 0;
      p   += Number(rows[i][4]) || 0;
      c   += Number(rows[i][5]) || 0;
      f   += Number(rows[i][6]) || 0;
    }
  }

  // Upsert cols A-E only (preserve weight in F and burn in G-H)
  const sum = summary.getDataRange().getValues();
  for (let i = 1; i < sum.length; i++) {
    if (normDate(sum[i][0]) === dateStr) {
      summary.getRange(i + 1, 1, 1, 5).setValues([[dateStr, cal, p, c, f]]);
      return;
    }
  }
  summary.appendRow([dateStr, cal, p, c, f, "", "", ""]);   // weight + burn filled in by their updaters
}

/**
 * Averages Tracker weigh-in values (col H) for `dateStr` and upserts the weight cell (F)
 * of that date's Summary row, leaving the other cells untouched. Kept to 0.1 lb.
 */
function updateWeightSummary(dateStr) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const summary = sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);

  const rows = tracker.getDataRange().getValues();
  let wSum = 0, n = 0;
  for (let i = 1; i < rows.length; i++) {            // skip header row
    if (normDate(rows[i][0]) === dateStr) {
      const w = Number(rows[i][7]);                  // col H
      if (!isNaN(w) && w > 0) { wSum += w; n++; }
    }
  }

  const avg = n > 0 ? Math.round((wSum / n) * 10) / 10 : "";
  const sum = summary.getDataRange().getValues();
  for (let i = 1; i < sum.length; i++) {
    if (normDate(sum[i][0]) === dateStr) {
      summary.getRange(i + 1, 6).setValue(avg);      // col F only
      return;
    }
  }
  if (n > 0) summary.appendRow([dateStr, "", "", "", "", avg, "", ""]);  // weight-only day
}

/**
 * Picks the day's watch energy from Tracker "Burn" rows (cols I basal, J active) and upserts
 * the Summary basal/burn cells (G-H) for `dateStr`, leaving A-F untouched. Last non-blank value
 * for the date wins — a later same-day sync carries the more complete daily total.
 */
function updateBurnSummary(dateStr) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const summary = sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);

  const rows = tracker.getDataRange().getValues();
  let basal = "", burn = "", found = false;
  for (let i = 1; i < rows.length; i++) {            // skip header; iterate in order so latest wins
    if (normDate(rows[i][0]) === dateStr) {
      const b = rows[i][8], a = rows[i][9];          // cols I, J
      if (b !== "" && !isNaN(Number(b))) { basal = Number(b); found = true; }
      if (a !== "" && !isNaN(Number(a))) { burn  = Number(a); found = true; }
    }
  }

  const sum = summary.getDataRange().getValues();
  for (let i = 1; i < sum.length; i++) {
    if (normDate(sum[i][0]) === dateStr) {
      summary.getRange(i + 1, 7, 1, 2).setValues([[basal, burn]]);  // cols G-H only
      return;
    }
  }
  if (found) summary.appendRow([dateStr, "", "", "", "", "", basal, burn]);  // burn-only day
}

/** Normalizes a cell to "yyyy-MM-dd" whether it comes back as a Date or a string. */
function normDate(v) {
  if (v && Object.prototype.toString.call(v) === "[object Date]") {
    const tz = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
    return Utilities.formatDate(v, tz, "yyyy-MM-dd");
  }
  return String(v).trim();
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
  const tz = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
  const today = Utilities.formatDate(new Date(), tz, "yyyy-MM-dd");
  updateDailySummary(today);
  updateWeightSummary(today);
  updateBurnSummary(today);
}

/** REPAIR TOOL: wipes Summary and rebuilds every day's macros, weight AND burn from Tracker.
 *  One clean row per date, sorted; weight averaged (0.1 lb); basal/burn take the last value. */
function rebuildAllSummary() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);

  const rows = tracker.getDataRange().getValues();
  const agg = {};                    // dateStr -> [cal, p, c, f, wSum, wN, basal, burn]
  for (let i = 1; i < rows.length; i++) {   // skip header
    const d = normDate(rows[i][0]);
    if (!d) continue;
    if (!agg[d]) agg[d] = [0, 0, 0, 0, 0, 0, "", ""];
    agg[d][0] += Number(rows[i][3]) || 0;
    agg[d][1] += Number(rows[i][4]) || 0;
    agg[d][2] += Number(rows[i][5]) || 0;
    agg[d][3] += Number(rows[i][6]) || 0;
    const w = Number(rows[i][7]);
    if (!isNaN(w) && w > 0) { agg[d][4] += w; agg[d][5]++; }
    const b = rows[i][8], a = rows[i][9];              // last non-blank wins (rows are chronological)
    if (b !== "" && !isNaN(Number(b))) agg[d][6] = Number(b);
    if (a !== "" && !isNaN(Number(a))) agg[d][7] = Number(a);
  }

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
      const a = agg[d];
      const t = tByDate[d] || ["", "", "", ""];
      return [d, a[0], a[1], a[2], a[3], a[5] > 0 ? Math.round(a[4] / a[5] * 10) / 10 : "", a[6], a[7],
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
  const tz = ss.getSpreadsheetTimeZone();

  const resRows = responses.getDataRange().getValues();   // [timestamp, payload, ...]
  const out = [];                    // rebuilt Tracker rows (10-wide, uniform)
  let meals = 0, weighins = 0, burns = 0, skipped = 0;
  for (let i = 1; i < resRows.length; i++) {              // skip header
    const ts = resRows[i][0];
    const payload = resRows[i][1];
    if (!payload) continue;
    let data;
    try {
      data = JSON.parse(payload);
    } catch (err) {
      skipped++;
      Logger.log("Skipped unparseable response on row " + (i + 1) + ": " + err);
      continue;
    }
    if (!Array.isArray(data)) data = [data];
    const fallbackDate = normDate(ts) || Utilities.formatDate(new Date(), tz, "yyyy-MM-dd");
    data.forEach(item => {
      const rowDate = parseInputDate(item.date) || fallbackDate;
      if (isWeightEntry(item)) {
        out.push([rowDate, "Weigh-in", "", "", "", "", "", Number(item.weight), "", ""]);
        weighins++;
      } else if (isBurnEntry(item)) {
        out.push([rowDate, "Burn", "", "", "", "", "", "", numOrBlank(item.basal), numOrBlank(item.burn)]);
        burns++;
      } else {
        out.push([rowDate, item.meal, item.details || "", item.cal, item.p, item.c, item.f, "", "", ""]);
        meals++;
      }
    });
  }

  // Replace Tracker's data rows (keep the header intact); clear/write 10 columns wide
  const tLast = tracker.getLastRow();
  if (tLast > 1) tracker.getRange(2, 1, tLast - 1, 10).clearContent();
  if (out.length) tracker.getRange(2, 1, out.length, 10).setValues(out);

  rebuildAllSummary();

  Logger.log("Rebuilt from " + (resRows.length - 1) + " responses: " +
             meals + " meals, " + weighins + " weigh-ins, " + burns + " burn, " + skipped + " skipped.");
}

// ===== DYNAMIC TARGETS (TDEE + workout → per-day carb-band center) =====

/** Manual entry point: compute + write TODAY's targets. Safe to run from the editor. */
function updateTargetsToday() {
  const tz = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
  updateDailyTargets(Utilities.formatDate(new Date(), tz, "yyyy-MM-dd"));
}

/**
 * Computes the day's target CENTERS and writes Summary cols I-L (t_cal, t_pro, t_carb, t_fat):
 *   anchor = max(TDEE + (today's burn − typical burn) − deficit, floor)
 *   t_carb = (anchor − 4·protein_center − 9·fat_center) / 4        (carbs are the plug)
 *   t_pro / t_fat = fixed band centers from config; t_cal = anchor (display/context only).
 * Leaves the row's I-L untouched (widget then falls back to the static bands) if the config
 * isn't complete or TDEE isn't ready. Missing burn → no delta (fallback).
 */
function updateDailyTargets(dateStr) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const summary = sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);

  const cfg = readTargetConfig(ss, dateStr);
  const tdee = computeTdee(ss, dateStr);
  if (!cfg || tdee === null) {
    Logger.log("Targets skipped for " + dateStr + " (config incomplete or not enough data yet).");
    return;
  }

  const burnToday = readBurn(ss, dateStr);            // null = not worn / not posted → no delta
  const typical = typicalBurn(ss, dateStr);
  const delta = (burnToday === null) ? 0 : (burnToday - typical);

  const anchor = Math.max(tdee + delta - cfg.deficit, cfg.floor);
  const tCarb = Math.max(0, (anchor - 4 * cfg.pCenter - 9 * cfg.fCenter) / 4);
  const vals = [Math.round(anchor), Math.round(cfg.pCenter), Math.round(tCarb), Math.round(cfg.fCenter)];

  const sum = summary.getDataRange().getValues();
  for (let i = 1; i < sum.length; i++) {
    if (normDate(sum[i][0]) === dateStr) {
      summary.getRange(i + 1, 9, 1, 4).setValues([vals]);   // cols I-L
      return;
    }
  }
  summary.appendRow([dateStr, "", "", "", "", "", "", "", vals[0], vals[1], vals[2], vals[3]]);
}

/**
 * TDEE over the trailing window ending YESTERDAY (completed days only):
 *   TDEE = avg intake − weight_slope(lb/day) × 3500,  slope = least-squares over the weigh-ins.
 * Regression over the raw daily weigh-ins smooths water noise without hinging on endpoints.
 * Returns a number, or null if below the data bar.
 */
function computeTdee(ss, dateStr) {
  const summary = ss.getSheetByName(SUMMARY_TAB);
  if (!summary) return null;
  const rows = summary.getDataRange().getValues();
  const end = addDays(dateStr, -1);
  const start = addDays(end, -(TDEE_WINDOW_DAYS - 1));

  const weights = [];   // [dayIndex, weight]
  const intakes = [];
  for (let i = 1; i < rows.length; i++) {
    const d = normDate(rows[i][0]);
    if (!d || d < start || d > end) continue;
    const w = Number(rows[i][5]);    // F weight
    if (!isNaN(w) && w > 0) weights.push([daysBetween(start, d), w]);
    const cal = Number(rows[i][1]);  // B cal
    if (!isNaN(cal) && cal > 0) intakes.push(cal);
  }
  if (weights.length < MIN_WEIGH_INS || intakes.length < MIN_INTAKE_DAYS) return null;

  let minx = Infinity, maxx = -Infinity;
  weights.forEach(p => { minx = Math.min(minx, p[0]); maxx = Math.max(maxx, p[0]); });
  if ((maxx - minx + 1) < MIN_SPAN_DAYS) return null;

  const slope = regressionSlope(weights);             // lb per day
  const avgIntake = intakes.reduce((a, b) => a + b, 0) / intakes.length;
  return avgIntake - slope * KCAL_PER_LB;
}

/** All-worn-days average of the active-burn column (H) over the same trailing window. */
function typicalBurn(ss, dateStr) {
  const summary = ss.getSheetByName(SUMMARY_TAB);
  if (!summary) return 0;
  const rows = summary.getDataRange().getValues();
  const end = addDays(dateStr, -1);
  const start = addDays(end, -(TDEE_WINDOW_DAYS - 1));
  let sum = 0, n = 0;
  for (let i = 1; i < rows.length; i++) {
    const d = normDate(rows[i][0]);
    if (!d || d < start || d > end) continue;
    const b = rows[i][7];            // H burn (active) — blank = not worn → excluded
    if (b !== "" && b !== null && !isNaN(Number(b))) { sum += Number(b); n++; }
  }
  return n > 0 ? sum / n : 0;
}

/** Today's active-burn (col H) or null if blank (not worn / not yet posted). */
function readBurn(ss, dateStr) {
  const summary = ss.getSheetByName(SUMMARY_TAB);
  if (!summary) return null;
  const rows = summary.getDataRange().getValues();
  for (let i = 1; i < rows.length; i++) {
    if (normDate(rows[i][0]) === dateStr) {
      const b = rows[i][7];
      return (b !== "" && b !== null && !isNaN(Number(b))) ? Number(b) : null;
    }
  }
  return null;
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

/** yyyy-MM-dd date arithmetic (day math only, no timezone needed). */
function addDays(dateStr, n) {
  const p = dateStr.split("-");
  const dt = new Date(+p[0], +p[1] - 1, +p[2]);
  dt.setDate(dt.getDate() + n);
  const pad = x => (x < 10 ? "0" + x : "" + x);
  return dt.getFullYear() + "-" + pad(dt.getMonth() + 1) + "-" + pad(dt.getDate());
}
function daysBetween(a, b) {
  const pa = a.split("-"), pb = b.split("-");
  const da = new Date(+pa[0], +pa[1] - 1, +pa[2]);
  const db = new Date(+pb[0], +pb[1] - 1, +pb[2]);
  return Math.round((db - da) / 86400000);
}

/** Run ONCE from the editor to install a daily ~14:30 trigger that recomputes today's targets. */
function createTargetsTrigger() {
  ScriptApp.getProjectTriggers().forEach(t => {
    if (t.getHandlerFunction() === "updateTargetsToday") ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger("updateTargetsToday")
    .timeBased().atHour(14).nearMinute(30).everyDays(1).create();
  Logger.log("Targets trigger installed (~14:30, script timezone).");
}

/** Run ONCE from the editor (desktop) to install a daily ~00:45 trigger that auto-rebuilds
 *  Tracker + Summary from the form responses. Safe to re-run; replaces any existing one. */
function createNightlyRebuildTrigger() {
  ScriptApp.getProjectTriggers().forEach(t => {
    if (t.getHandlerFunction() === "rebuildTrackerFromResponses") ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger("rebuildTrackerFromResponses")
    .timeBased()
    .atHour(0)
    .nearMinute(45)
    .everyDays(1)
    .create();
  Logger.log("Nightly rebuild trigger installed (~00:45, script timezone).");
}

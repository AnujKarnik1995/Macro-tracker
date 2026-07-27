// ===== CONFIG =====
const TRACKER_TAB = "Tracker";            // raw log: meals (A-G) AND weigh-ins (col H = weight)
const SUMMARY_TAB = "Summary";            // STATIC daily totals: A-E macros, F = weight (widget reads THIS)
const RESPONSES_TAB = "Form responses 1"; // raw Google Form submissions (true source of truth)

// Tracker columns:  A date(0) B meal(1) C details(2) D cal(3) E p(4) F c(5) G f(6) H weight(7)
// Summary columns:  A date(0) B cal(1)  C p(2)       D c(3)   E f(4) F weight(5)

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
    data.forEach(item => {
      // OPTIONAL back-date: item.date "DD/MM/YYYY" (for post-midnight / forgotten entries)
      const rowDate = parseInputDate(item.date) || today;
      if (isWeightEntry(item)) {
        // Weigh-in row: date + weight in col H; macro columns left blank.
        tracker.appendRow([rowDate, "Weigh-in", "", "", "", "", "", Number(item.weight)]);
        weightDates[rowDate] = true;
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

  } catch (err) {
    Logger.log("Error processing payload: " + err);
  }
}

/** A payload item is a weigh-in if it carries a numeric `weight`; everything else
 *  is treated as a meal. Weigh-ins may include the same optional DD/MM/YYYY `date`. */
function isWeightEntry(item) {
  return item && item.weight !== undefined && item.weight !== null && item.weight !== "" &&
         !isNaN(Number(item.weight));
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
 * date's Summary row. Leaves the weight cell (F) untouched. Writing plain script-computed
 * numbers keeps the data readable by Gemini/CSV (formula cells come back blank).
 */
function updateDailySummary(dateStr) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const summary = sheetWithHeader(ss, SUMMARY_TAB, ["date", "cal", "p", "c", "f", "weight"]);

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

  // Upsert cols A-E only (preserve the weight cell in F)
  const sum = summary.getDataRange().getValues();
  for (let i = 1; i < sum.length; i++) {
    if (normDate(sum[i][0]) === dateStr) {
      summary.getRange(i + 1, 1, 1, 5).setValues([[dateStr, cal, p, c, f]]);
      return;
    }
  }
  summary.appendRow([dateStr, cal, p, c, f, ""]);   // weight filled in by updateWeightSummary
}

/**
 * Averages Tracker weigh-in values (col H) for `dateStr` and upserts the weight cell
 * (F) of that date's Summary row, leaving the macro cells (A-E) untouched. Kept to
 * 0.1 lb -- the 0.7-0.9 lb/wk band needs the decimal, so weight is NOT rounded whole.
 */
function updateWeightSummary(dateStr) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const summary = sheetWithHeader(ss, SUMMARY_TAB, ["date", "cal", "p", "c", "f", "weight"]);

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
  if (n > 0) summary.appendRow([dateStr, "", "", "", "", avg]);  // weight-only day
}

/** Normalizes a cell to "yyyy-MM-dd" whether it comes back as a Date or a string.
 *  Object.prototype.toString is the reliable Date check in Apps Script. */
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

/** Run manually to recompute TODAY's macro + weight cells immediately (no form submit). */
function rebuildToday() {
  const tz = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
  const today = Utilities.formatDate(new Date(), tz, "yyyy-MM-dd");
  updateDailySummary(today);
  updateWeightSummary(today);
}

/** REPAIR TOOL: wipes Summary and rebuilds every day's macros AND weight from scratch,
 *  straight from Tracker. One clean row per date, sorted; weight averaged, 0.1 lb. */
function rebuildAllSummary() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);

  const rows = tracker.getDataRange().getValues();
  const agg = {};                    // dateStr -> [cal, p, c, f, wSum, wN]
  for (let i = 1; i < rows.length; i++) {   // skip header
    const d = normDate(rows[i][0]);
    if (!d) continue;
    if (!agg[d]) agg[d] = [0, 0, 0, 0, 0, 0];
    agg[d][0] += Number(rows[i][3]) || 0;
    agg[d][1] += Number(rows[i][4]) || 0;
    agg[d][2] += Number(rows[i][5]) || 0;
    agg[d][3] += Number(rows[i][6]) || 0;
    const w = Number(rows[i][7]);
    if (!isNaN(w) && w > 0) { agg[d][4] += w; agg[d][5]++; }
  }

  const summary = sheetWithHeader(ss, SUMMARY_TAB, ["date", "cal", "p", "c", "f", "weight"]);
  summary.clearContents();
  summary.appendRow(["date", "cal", "p", "c", "f", "weight"]);

  const dates = Object.keys(agg).sort();   // yyyy-MM-dd sorts chronologically
  if (dates.length) {
    const out = dates.map(d => {
      const a = agg[d];
      return [d, a[0], a[1], a[2], a[3], a[5] > 0 ? Math.round(a[4] / a[5] * 10) / 10 : ""];
    });
    summary.getRange(2, 1, out.length, 6).setValues(out);
  }
}

/** REPAIR TOOL: re-derives Tracker (meals + weigh-ins) from "Form responses", then
 *  rebuilds Summary. Use after you manually edit/correct a form response.
 *  WARNING: REPLACES Tracker's data from the responses -- direct Tracker edits are lost. */
function rebuildTrackerFromResponses() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const responses = ss.getSheetByName(RESPONSES_TAB);
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const tz = ss.getSpreadsheetTimeZone();

  const resRows = responses.getDataRange().getValues();   // [timestamp, payload, ...]
  const out = [];                    // rebuilt Tracker rows (8-wide, uniform)
  let meals = 0, weighins = 0, skipped = 0;
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
        out.push([rowDate, "Weigh-in", "", "", "", "", "", Number(item.weight)]);
        weighins++;
      } else {
        out.push([rowDate, item.meal, item.details || "", item.cal, item.p, item.c, item.f, ""]);
        meals++;
      }
    });
  }

  // Replace Tracker's data rows (keep the header intact); clear/write 8 columns wide
  const tLast = tracker.getLastRow();
  if (tLast > 1) tracker.getRange(2, 1, tLast - 1, 8).clearContent();
  if (out.length) tracker.getRange(2, 1, out.length, 8).setValues(out);

  rebuildAllSummary();

  Logger.log("Rebuilt from " + (resRows.length - 1) + " responses: " +
             meals + " meals, " + weighins + " weigh-ins, " + skipped + " skipped.");
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

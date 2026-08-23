// ===== CONFIG =====
const TRACKER_TAB = "Tracker";            // raw log: meals (A-G), weigh-ins (H); J reserved/blank
const SUMMARY_TAB = "Summary";            // STATIC daily totals the widget reads
const RESPONSES_TAB = "Form responses 1"; // raw Google Form submissions (true source of truth)
const TARGETS_TAB = "Targets";            // CONFIG the widget reads: macro bands + weight band + Floor + Deficit,
                                          // dated via an EffectiveFrom column (col E). Change name here if yours differs.

// Tracker columns:  A date(0) B meal(1) C details(2) D cal(3) E p(4) F c(5) G f(6) H weight(7) I unused(8) J burn(9)
//                   K gym(10)  <- session label "A"/"B" for a completed strength session
// Summary columns:  A date(0) B cal(1) C p(2) D c(3) E f(4) F weight(5) G unused(6) H burn(7)
//                   I t_cal(8) J t_pro(9) K t_carb(10) L t_fat(11)   <- per-day target CENTERS (updateDailyTargets)
//                   M gym(12)  <- "A"/"B" when a session was logged that day, blank otherwise
// Summary G/H and Tracker I/J are permanently BLANK reserved slots. Do not reclaim them, and do not
// insert a column before Summary M: both sheets are parsed by POSITION, so anything that shifts
// cols I-L re-scores every historical day, silently. New columns are APPENDED. §14, §16.
const SUMMARY_HEADER = ["date", "cal", "p", "c", "f", "weight", "unused", "burn", "t_cal", "t_pro", "t_carb", "t_fat", "gym"];

// ----- Column indices: 0-based, and FROZEN -----
// Every positional access goes through these maps, so `git grep 'S\.'` finds all of them. A shifted
// column does not throw — it re-scores history against the wrong number. §14, §9.
//
// Array indices are 0-based; Sheets ranges are 1-based. Never hard-code a write column — derive it
// with sCol() from the same constant the read uses, so the two cannot drift apart.
const S = Object.freeze({          // Summary
  DATE: 0, CAL: 1, P: 2, C: 3, F: 4, WEIGHT: 5, UNUSED: 6, BURN: 7,   // UNUSED, BURN: blank, reserved
  T_CAL: 8, T_PRO: 9, T_CARB: 10, T_FAT: 11, GYM: 12
});
const T = Object.freeze({          // Tracker
  DATE: 0, MEAL: 1, DETAILS: 2, CAL: 3, P: 4, C: 5, F: 6, WEIGHT: 7, UNUSED: 8, BURN: 9, GYM: 10   // UNUSED, BURN: blank
});
const TG = Object.freeze({         // Targets
  NAME: 0, LOWER: 1, UPPER: 2, SEVERITY: 3, EFFECTIVE_FROM: 4
});
const R = Object.freeze({          // Form responses 1
  TIMESTAMP: 0, PAYLOAD: 1
});
/** 0-based array index → 1-based Sheets column. */
function sCol(i) { return i + 1; }

// ----- TDEE / dynamic-target compute -----
// The window has two lower bounds and must clear both. Short fits are dominated by their edge
// weigh-ins, so one water-skewed reading at the boundary moves TDEE by hundreds of kcal. And a
// carb-driven glycogen swing runs a few weeks; a window it fits inside averages the water-loading
// and water-dumping halves together and reports a slope that is neither. 42 is the shortest length
// clearing both. Do not shorten it. §2.
const TDEE_WINDOW_DAYS = 42;   // trailing window (completed days) for the TDEE regression
const KCAL_PER_LB = 3500;
const MIN_WEIGH_INS = 8, MIN_INTAKE_DAYS = 10, MIN_SPAN_DAYS = 14;   // data bar before targets compute
const INTAKE_COMPLETE_FRAC = 0.65;   // a day below this fraction of the window median = unfinished log

// ----- Target slew limit -----
// Cap on how fast the anchor may move, in kcal per WEEK. Real expenditure changes slowly — ten pounds
// of loss is worth ~100-150 kcal over months — so anything moving faster is measurement error, and
// its speed alone identifies it. Real drift passes the gate; scale noise does not.
//
// It also bounds how large a carb change the controller can prescribe, which keeps it from driving
// the glycogen swings that corrupt its own TDEE window.
//
// Applied BEFORE the floor, so the floor stays exact. Set to 0 to disable. §8.
const TARGET_SLEW_KCAL_PER_WEEK = 50;

// No training-burn flex: a `burn` payload field is ignored like any other unknown key. Resistance
// work is already inside an intake-anchored TDEE, so crediting calories for it double-counts.
// §16.

// ----- Strength-session logging -----
// A gym day is a CHECKBOX, not a calorie figure: {"gym":"A"} or {"gym":"B"}. It never moves a macro —
// the session is already inside the measured TDEE, and crediting calories for it re-creates the
// "I earned this" loop the constant-deficit design exists to remove. Counted for pacing only.
//
// The two labels are the alternating A/B full-body sessions. They drive the widget's "next session"
// pointer, so the rotation follows the sequence rather than the weekday. §13.
const GYM_LABELS = ["A", "B"];
// Several submissions for one date collapse to ONE session, last label winning, so a correction just
// works. Two sessions in a day is still 1 — no banking ahead.

// Basal/BMR is not collected. An intake-anchored TDEE measures total expenditure by construction, so
// a separate BMR figure would double-count. Items carrying only `basal` are dropped. §1.

function processMacroPayload(e) {
  if (!e || !e.values) {
    throw new Error("This script requires a form submission event to run. Do not click 'Run' in the editor.");
  }

  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const tracker = ss.getSheetByName(TRACKER_TAB);

  try {
    let data = JSON.parse(e.values[R.PAYLOAD]);
    if (!Array.isArray(data)) data = [data];

    const today = todayStr();                  // spreadsheet TZ; used when an item carries no "date"
    const newRows = [];                        // batched: one write instead of one per item
    const want = { macros: {}, weight: {}, gym: {} };

    data.forEach(item => {
      // OPTIONAL back-date: item.date "DD/MM/YYYY" (post-midnight or forgotten entries)
      const mapped = payloadItemToRow(item, today);
      if (!mapped) return;                     // nothing usable in this item
      newRows.push(mapped.row);
      if (mapped.kind === "weight")    want.weight[mapped.date] = true;
      else if (mapped.kind === "gym")  want.gym[mapped.date]    = true;
      else                             want.macros[mapped.date] = true;
      // A meal row can also carry a session (see payloadItemToRow) — refresh both groups.
      if (mapped.gym) want.gym[mapped.date] = true;
    });

    if (newRows.length) {
      tracker.getRange(tracker.getLastRow() + 1, 1, newRows.length, TRACKER_WIDTH).setValues(newRows);
    }

    // Every touched date recomputed from ONE Tracker read and ONE Summary read.
    refreshSummary(ss, want);

  } catch (err) {
    // LOG, THEN RE-THROW — never swallow. Re-throwing marks the trigger execution failed, which is
    // what makes Apps Script send its failure notification; a caught-and-logged error is invisible
    // until a hole turns up in the data weeks later. Nothing is lost by throwing: the raw payload is
    // already in `Form responses 1`, so rebuildTrackerFromResponses() recovers the entry once the
    // cause is fixed..
    Logger.log("Error processing payload: " + err + (err && err.stack ? "\n" + err.stack : ""));
    throw err;
  }
}

/** A usable number, or null. The single numeric guard for the whole file — cell values, payload
 *  fields and config cells all go through here instead of open-coding !isNaN(Number(x)). */
function num(v) {
  if (v === undefined || v === null || v === "") return null;
  const n = Number(v);
  return isNaN(n) ? null : n;
}

// Tracker is written 11 columns wide, uniformly:
//   A date  B meal  C details  D cal  E p  F c  G f  H weight  I unused  J burn  K gym
// Widened from 10 for the gym column. Existing 10-wide rows read back fine (index 10 comes out
// undefined -> num()/String() handle it), so no Tracker rebuild is required to adopt this.
const TRACKER_WIDTH = 11;

/**
 * Turns one Form payload item into a Tracker row: {kind, date, row}, or **null** when the item
 * carries nothing usable.
 *
 * The null case is load-bearing, not defensive: the response log contains `{basal, date}` items that
 * carry no information now that basal is dead (§1). Returning null keeps them out of Tracker instead
 * of writing blank rows.
 *
 * Single source of truth — both processMacroPayload and rebuildTrackerFromResponses go through here,
 * so the live path and the rebuild path cannot disagree about what a payload means.
 */
function payloadItemToRow(item, fallbackDate) {
  // A date that was SUPPLIED but unreadable falls back to the submission date, which misdates the
  // entry rather than rejecting it. Log it — an absent date is legitimate, an unreadable one is not.
  // §15.
  const supplied = item && item.date;
  const parsed = parseInputDate(supplied);
  if (supplied !== undefined && supplied !== null && supplied !== "" && parsed === null) {
    Logger.log('UNPARSEABLE DATE ' + JSON.stringify(supplied) + ' — entry filed under ' + fallbackDate +
               '. Accepted formats: "DD/MM/YYYY" or "YYYY-MM-DD".');
  }
  const d = parsed || fallbackDate;

  if (isWeightEntry(item)) {
    return { kind: "weight", date: d,
             row: trackerRow({ date: d, meal: "Weigh-in", weight: num(item.weight) }) };
  }

  const g = normGym(item);
  const hasMacros = num(item && item.cal) !== null || num(item && item.p) !== null ||
                    num(item && item.c)   !== null || num(item && item.f) !== null;

  // A lone {"gym":"A"} is its own row.
  if (!hasMacros) {
    return g ? { kind: "gym", date: d, gym: g,
                 row: trackerRow({ date: d, meal: "Gym " + g, gym: g }) } : null;
  }

  // Macros AND a gym flag in the SAME object ({"cal":640,...,"gym":"B"}) is a meal row that also
  // carries the session in col K — a Tracker row can hold both. Returning early on the gym branch
  // would have silently swallowed the meal, and checking macros first would have silently swallowed
  // the session; either way one of them vanishes with no error. `gym` is reported separately from
  // `kind` so the caller refreshes BOTH column groups.
  return { kind: "meal", date: d, gym: g,
           row: trackerRow({ date: d, meal: item.meal || "", details: item.details || "",
                             cal: numOrBlank(item.cal), p: numOrBlank(item.p),
                             c: numOrBlank(item.c),     f: numOrBlank(item.f), gym: g || "" }) };
}

/**
 * A full-width Tracker row, fields placed BY NAME so a miscount cannot shift later columns into the
 * wrong position. Unspecified fields are blank.
 */
function trackerRow(f) {
  const r = [];
  for (let i = 0; i < TRACKER_WIDTH; i++) r.push("");
  r[T.DATE]    = f.date === undefined ? "" : f.date;
  r[T.MEAL]    = f.meal === undefined ? "" : f.meal;
  r[T.DETAILS] = f.details === undefined ? "" : f.details;
  r[T.CAL]     = f.cal === undefined ? "" : f.cal;
  r[T.P]       = f.p === undefined ? "" : f.p;
  r[T.C]       = f.c === undefined ? "" : f.c;
  r[T.F]       = f.f === undefined ? "" : f.f;
  r[T.WEIGHT]  = f.weight === undefined ? "" : f.weight;
  r[T.BURN]    = f.burn === undefined ? "" : f.burn;
  r[T.GYM]     = f.gym === undefined ? "" : f.gym;
  return r;
}

/**
 * The session label for a payload item — "A" or "B" — or null when it carries no gym flag.
 *
 * Accepts what a human actually types: {"gym":"A"}, {"gym":"b"}, {"gym":true} and {"gym":1} all
 * mean "I trained". The bare-truthy forms cannot name a session, so they default to "A"; the
 * widget's rotation pointer then just advances from there. {"gym":false} / {"gym":0} are NOT a
 * miss-log — there is no such thing — they are simply ignored, so an absent day stays the miss.
 */
function normGym(item) {
  if (!item || item.gym === undefined || item.gym === null || item.gym === "") return null;
  const v = item.gym;
  if (v === true || v === 1 || v === "1") return GYM_LABELS[0];
  if (v === false || v === 0 || v === "0") return null;
  const s = String(v).trim().toUpperCase();
  return GYM_LABELS.indexOf(s) >= 0 ? s : GYM_LABELS[0];
}

/** A payload item is a weigh-in if it carries a numeric `weight`. */
function isWeightEntry(item) { return !!item && num(item.weight) !== null; }

/** Number, or "" if absent/blank/non-numeric — for writing back into a cell. */
function numOrBlank(v) { const n = num(v); return n === null ? "" : n; }

/** Accepts an optional date string in DD/MM/YYYY (matching the form's timestamp)
 *  and returns it normalized to canonical "YYYY-MM-DD", or null if absent/invalid. */
function parseInputDate(s) {
  if (typeof s !== "string") return null;
  const t = s.trim();

  // Two shapes, both unambiguous: DD/MM/YYYY (hand entry) and YYYY-MM-DD (what this function emits
  // and what Summary stores — a parser must accept its own output).
  //
  // MM/DD/YYYY is NOT supported and must never be added: it is indistinguishable from DD/MM/YYYY for
  // the first twelve days of any month, so accepting both would misdate ~40% of entries with no way
  // to recover the intended reading. §15.
  let d, m, y;
  let mt = t.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);           // DD/MM/YYYY
  if (mt) { d = +mt[1]; m = +mt[2]; y = +mt[3]; }
  else {
    mt = t.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);               // YYYY-MM-DD
    if (!mt) return null;
    y = +mt[1]; m = +mt[2]; d = +mt[3];
  }

  const dt = new Date(y, m - 1, d);                              // reject impossible dates
  if (dt.getFullYear() !== y || dt.getMonth() !== m - 1 || dt.getDate() !== d) return null;
  const pad = n => (n < 10 ? "0" + n : "" + n);
  return y + "-" + pad(m) + "-" + pad(d);                        // canonical YYYY-MM-DD
}

/**
 * ONE pass over Tracker, aggregating every date at once. Every consumer reads from this, so the
 * per-column arithmetic exists in exactly one place and the write paths cannot drift apart.
 *
 * Returns { "yyyy-MM-dd": {cal, p, c, f, wSum, wN, gym} }.
 */
function aggregateTracker(rows) {
  const agg = {};
  for (let i = 1; i < rows.length; i++) {            // skip header row
    const d = normDate(rows[i][T.DATE]);
    if (!d) continue;
    let a = agg[d];
    if (!a) a = agg[d] = emptyTrackerDay();
    a.cal += num(rows[i][T.CAL]) || 0;
    a.p   += num(rows[i][T.P]) || 0;
    a.c   += num(rows[i][T.C]) || 0;
    a.f   += num(rows[i][T.F]) || 0;
    const w = num(rows[i][T.WEIGHT]);
    if (w !== null && w > 0) { a.wSum += w; a.wN++; }
    // K gym — unlike burn, sessions do NOT accumulate. Two rows for one date is still one gym day;
    // the last label written wins, so a correction ({"gym":"B"} after a mis-typed "A") just works.
    const gv = normGym({ gym: rows[i][T.GYM] });
    if (gv) a.gym = gv;
  }
  return agg;
}

function emptyTrackerDay() {
  return { cal: 0, p: 0, c: 0, f: 0, wSum: 0, wN: 0, gym: "" };
}

/** The aggregate for one date, or a zeroed one if Tracker has no rows for it. */
function trackerDay(agg, dateStr) {
  return agg[dateStr] || emptyTrackerDay();
}

/** A date's weight, averaged and rounded to 0.1 lb, or "" when there were no weigh-ins. */
function dayWeight(a) { return a.wN > 0 ? Math.round((a.wSum / a.wN) * 10) / 10 : ""; }

/**
 * The Summary column groups. Each owns a contiguous run of cells starting at `col`, renders them
 * from one day's Tracker aggregate, and declares whether it may CREATE a Summary row that does not
 * exist yet. Adding a column group is one entry here.
 *
 * Key order is the write order and is load-bearing: `macros` runs first because it is the only group
 * that unconditionally creates the row, so the others find it already present.
 */
const SUMMARY_GROUPS = Object.freeze({
  macros: { col: S.DATE,   values: (a, d) => [d, a.cal, a.p, a.c, a.f], creates: function ()  { return true; } },
  weight: { col: S.WEIGHT, values: a => [dayWeight(a)],                 creates: function (a) { return a.wN > 0; } },
  // `creates` is TRUE for a session on a day with no food and no weigh-in — otherwise the row never
  // exists and the widget's rolling-7 count can never see it.
  gym:    { col: S.GYM,    values: a => [a.gym || ""],                  creates: function (a) { return !!a.gym; } }
});

/**
 * Recomputes the requested Summary cells for each date, from ONE Tracker read and ONE Summary read.
 * `want` is {macros:{date:true}, weight:{...}, ...} keyed by SUMMARY_GROUPS — only the listed groups
 * are written, so a meal submission never disturbs weight, burn or the per-day targets.
 *
 * Returns {summary, sum}. Because upsertSummary keeps `sum` in step with what it writes, the caller
 * can pass that straight to updateDailyTargets and it cannot see stale data.
 */
function refreshSummary(ss, want) {
  const tracker = ss.getSheetByName(TRACKER_TAB);
  const summary = sheetWithHeader(ss, SUMMARY_TAB, SUMMARY_HEADER);
  const agg = aggregateTracker(tracker.getDataRange().getValues());
  const sum = summary.getDataRange().getValues();

  Object.keys(SUMMARY_GROUPS).forEach(name => {
    const spec = SUMMARY_GROUPS[name];
    Object.keys(want[name] || {}).forEach(d => {
      const a = trackerDay(agg, d);
      upsertSummary(summary, sum, d, sCol(spec.col), spec.values(a, d), spec.creates(a));
    });
  });
  return { summary: summary, sum: sum };
}

/**
 * Recomputes the named column groups for ONE date — the whole set when `groups` is omitted.
 *
 * Pass every group you need in a single call. refreshSummary reads BOTH sheets in full each time it
 * runs, so calling it once per group multiplies that cost for no benefit.
 */
function refreshDate(dateStr, groups) {
  const want = {};
  (groups || Object.keys(SUMMARY_GROUPS)).forEach(g => { want[g] = {}; want[g][dateStr] = true; });
  return refreshSummary(SpreadsheetApp.getActiveSpreadsheet(), want);
}

/**
 * The spreadsheet timezone, fetched at most ONCE per execution. normDate() runs on every date cell
 * of every row scan; fetching the tz per cell cost ~2,200 service calls per form submission.
 * Each Apps Script execution gets a fresh global scope, so the cache can't go stale.
 *
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
 * rows, so a duplicated green day is counted twice. DESIGN-LOG.md §14.
 */
function upsertSummary(summary, rows, dateStr, col, values, appendIfMissing) {
  for (let i = 1; i < rows.length; i++) {
    if (normDate(rows[i][S.DATE]) === dateStr) {
      summary.getRange(i + 1, col, 1, values.length).setValues([values]);
      for (let j = 0; j < values.length; j++) rows[i][col - 1 + j] = values[j];
      return true;
    }
  }
  if (!appendIfMissing) return false;
  const row = [];
  for (let j = 0; j < SUMMARY_HEADER.length; j++) row.push("");
  row[S.DATE] = dateStr;
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

/** Run manually to recompute TODAY's macro + weight + burn + gym cells (no form submit). */
function rebuildToday() {
  refreshDate(todayStr());
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
    const d = normDate(prior[i][S.DATE]);
    if (d) tByDate[d] = [g(prior[i][S.T_CAL]), g(prior[i][S.T_PRO]), g(prior[i][S.T_CARB]), g(prior[i][S.T_FAT])];
  }

  summary.clearContents();
  summary.appendRow(SUMMARY_HEADER);

  const dates = Object.keys(agg).sort();   // yyyy-MM-dd sorts chronologically
  if (dates.length) {
    const out = dates.map(d => {
      const a = agg[d], t = tByDate[d] || ["", "", "", ""];
      return [d, a.cal, a.p, a.c, a.f, dayWeight(a),
              "",                              // col G intentionally blank (see SUMMARY_HEADER note)
              "",                              // col H — reserved blank slot (see header note)
              t[0], t[1], t[2], t[3],
              a.gym || ""];                    // col M — rebuilt from Tracker, not preserved
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
  const tally = { meal: 0, weight: 0, gym: 0 };
  let unparseable = 0, empty = 0, mealGym = 0;

  for (let i = 1; i < resRows.length; i++) {              // skip header
    const payload = resRows[i][R.PAYLOAD];
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
    const fallbackDate = normDate(resRows[i][R.TIMESTAMP]) || todayStr();
    data.forEach(item => {
      const mapped = payloadItemToRow(item, fallbackDate);
      if (!mapped) { empty++; return; }       // e.g. the legacy {basal,date} items — no information
      out.push(mapped.row);
      tally[mapped.kind]++;
      if (mapped.kind === "meal" && mapped.gym) mealGym++;   // session riding along on a meal row
    });
  }

  // Replace Tracker's data rows, keeping the header
  const tLast = tracker.getLastRow();
  if (tLast > 1) tracker.getRange(2, 1, tLast - 1, TRACKER_WIDTH).clearContent();
  if (out.length) tracker.getRange(2, 1, out.length, TRACKER_WIDTH).setValues(out);

  rebuildAllSummary();

  Logger.log("Rebuilt from " + (resRows.length - 1) + " responses: " + tally.meal + " meals, " +
             tally.weight + " weigh-ins, " +
             (tally.gym + mealGym) + " gym sessions (" + mealGym + " on meal rows), " + empty +
             " items with nothing usable, " + unparseable + " unparseable payloads.");
}

// ===== DYNAMIC TARGETS (TDEE + workout → per-day carb-band center) =====

/** Manual entry point: compute + write TODAY's targets. Safe to run from the editor. */
function updateTargetsToday() {
  updateDailyTargets(todayStr());
}

/**
 * One-shot entry point: recompute TODAY's target IGNORING the slew limit, then let the limit govern
 * from tomorrow on.
 *
 * Run ONCE after a deliberate, evidence-backed change to the estimator — a window length, a deficit,
 * a repaired history — so the correction lands instead of crawling from a value already known to be
 * wrong. Not for unsticking a target you merely dislike: a number you want to override in a hurry is
 * usually the noise the gate exists to block. §8.
 */
function reseedTargetsToday() {
  updateDailyTargets(todayStr(), null, { bypassSlew: true });
}

/**
 * Computes the day's target CENTERS and writes Summary cols I-L (t_cal, t_pro, t_carb, t_fat):
 *   anchor = max(TDEE + (today's burn − typical burn) − deficit, floor)
 *   t_carb = (anchor − 4·protein_center − 9·fat_center) / 4        (carbs are the plug)
 *   t_pro / t_fat = fixed band centers from config; t_cal = anchor (display/context only).
 * Leaves the row's I-L untouched (widget then falls back to the static bands) if the config
 * isn't complete or TDEE isn't ready. When burn flex is on and no training is logged → burn 0 →
 * negative delta vs the all-days baseline, which is correct: a rest day costs less than average.
 *
 * The anchor is slew-limited against the most recent previously-written anchor before the floor is
 * applied — see TARGET_SLEW_KCAL_PER_WEEK. Pass opts.bypassSlew to skip that gate (reseed only).
 */
function updateDailyTargets(dateStr, ctx, opts) {
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

  // Slew first, floor second. The floor is an anti-starve hard stop: the gate must not soften it,
  // and must not be able to hold the anchor below it on the way down.
  const rawAnchor = tdee - cfg.deficit;
  const slewed = (opts && opts.bypassSlew) ? rawAnchor : slewAnchor(rows, dateStr, rawAnchor);
  const anchor = Math.max(slewed, cfg.floor);
  if (slewed !== rawAnchor) {
    Logger.log("Slew limit held " + dateStr + ": " + Math.round(rawAnchor) + " -> " +
               Math.round(slewed) + " kcal (cap " + TARGET_SLEW_KCAL_PER_WEEK + "/wk).");
  }

  const tCarb = Math.max(0, (anchor - 4 * cfg.pCenter - 9 * cfg.fCenter) / 4);

  // ONE DECIMAL on the centres — do NOT Math.round() to whole grams. The widget rebuilds each band
  // as [centre ± halfWidth], so a .5 centre rounded up shifts the whole band up 0.5 g. §9.
  const r1 = x => Math.round(x * 10) / 10;
  const vals = [Math.round(anchor), r1(cfg.pCenter), r1(tCarb), r1(cfg.fCenter)];

  upsertSummary(summary, rows, dateStr, sCol(S.T_CAL), vals, true);   // cols I-L
}

/**
 * Clamps `rawAnchor` to within TARGET_SLEW_KCAL_PER_WEEK of the most recent previously-written
 * anchor, pro-rated by the number of days actually elapsed since it.
 *
 * Returns rawAnchor unchanged when the gate is disabled or there is no earlier anchor to measure
 * from: a first run must be free to land wherever the data says.
 */
function slewAnchor(src, dateStr, rawAnchor) {
  if (!TARGET_SLEW_KCAL_PER_WEEK) return rawAnchor;
  const prev = previousAnchor(src, dateStr);
  if (!prev) return rawAnchor;
  // Pro-rated by elapsed days so a gap in the sheet cannot bank up unlimited slack; never less than
  // one day's worth, so a same-day re-run is not frozen at zero movement.
  const gap = Math.max(1, daysBetween(prev.date, dateStr));
  const allow = TARGET_SLEW_KCAL_PER_WEEK * gap / 7;
  return Math.max(prev.anchor - allow, Math.min(prev.anchor + allow, rawAnchor));
}

/**
 * Most recent Summary row STRICTLY BEFORE dateStr that carries a numeric t_cal (col I), as
 * { date, anchor }, or null if there is none.
 *
 * Strictly-before is required: updateDailyTargets is re-entrant, and measuring against the row being
 * rewritten would clamp each run to its own previous output and freeze the target permanently.
 */
function previousAnchor(src, dateStr) {
  const rows = summaryValues(src);
  if (!rows) return null;
  let best = null;
  for (let i = 1; i < rows.length; i++) {
    const d = normDate(rows[i][S.DATE]);
    if (!d || d >= dateStr) continue;
    const v = num(rows[i][S.T_CAL]);
    if (v === null) continue;
    if (!best || d > best.date) best = { date: d, anchor: v };
  }
  return best;
}

/**
 * TDEE over the trailing window ending YESTERDAY (completed days only):
 *   TDEE = avg intake − weight_slope(lb/day) × 3500,  slope = least-squares over the weigh-ins.
 *
 * UNWEIGHTED — do not add exponential weighting. Up-weighting recent points re-creates the endpoint
 * leverage the long window exists to remove. Intake days below INTAKE_COMPLETE_FRAC of the window
 * MEDIAN are dropped as incomplete logs. Returns null if below the data bar. §2, §3.
 */
function computeTdee(src, dateStr) {
  const slice = windowRows(src, dateStr);
  if (!slice) return null;

  const startNum = dayNumber(slice.start);
  const weights = [];   // [dayIndex, weight]
  const rawIntakes = [];
  slice.rows.forEach(r => {
    const w = num(r.row[S.WEIGHT]);
    if (w !== null && w > 0) weights.push([dayNumber(r.date) - startNum, w]);
    const cal = num(r.row[S.CAL]);
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
 * DESIGN-LOG.md §3.
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
 * [{date, row}]. Returns null if Summary is missing.
 */
function windowRows(src, dateStr) {
  const rows = summaryValues(src);
  if (!rows) return null;
  const end = addDays(dateStr, -1);
  const start = addDays(end, -(TDEE_WINDOW_DAYS - 1));
  const out = [];
  for (let i = 1; i < rows.length; i++) {
    const d = normDate(rows[i][S.DATE]);
    if (!d || d < start || d > end) continue;
    out.push({ date: d, row: rows[i] });
  }
  return { start: start, end: end, rows: out };
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
    const key = classifyTarget(rows[i][TG.NAME]);
    if (!key) continue;
    const eff = normDate(rows[i][TG.EFFECTIVE_FROM]) || "0000-00-00";   // blank = always applies
    if (eff > dateStr) continue;                         // future row, not yet in effect
    if (!pick[key] || eff >= pick[key].eff) {
      pick[key] = { lower: Number(rows[i][TG.LOWER]), upper: Number(rows[i][TG.UPPER]), eff: eff };
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

/**
 * Run ONCE from the editor: installs the daily
 * updateTargetsToday trigger. Schedule follows the flag —
 *   burn ON  → ~14:30 (same-day workout flex needs afternoon data)
 *   burn OFF → ~03:00 (TDEE window ends yesterday; no same-day input to wait for)
 * installDailyTrigger replaces any prior trigger on the same handler, so one re-run is enough.
 */
function createTargetsTrigger() {
  installDailyTrigger("updateTargetsToday", 3, 0);
}

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
    'gym:      {"gym":"A"}   (or "B" — the session you just did)\n' +
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

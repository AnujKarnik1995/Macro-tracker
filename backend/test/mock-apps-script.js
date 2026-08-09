// Faithful-enough Apps Script service mocks so Code.gs can run under plain Node.
//
// The important fidelity detail: Sheets returns a date cell as a Date at MIDNIGHT IN THE
// SPREADSHEET TIMEZONE, and Utilities.formatDate converts back using that same timezone. An
// earlier version of this mock did both in UTC, so the conversion was never exercised and a
// whole class of off-by-one-day bug was invisible. Do not "simplify" that back.
'use strict';
const fs = require('fs');

const fmtTZ = (d, tz) => new Intl.DateTimeFormat('en-CA',
  { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);

/** A Date as Sheets would hand it back for a date-only cell in timezone `tz`. */
function sheetsDate(iso, tz) {
  const [y, m, d] = iso.split('-').map(Number);
  const utc = Date.UTC(y, m - 1, d);
  const gmt = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'longOffset' })
    .formatToParts(new Date(utc)).find(p => p.type === 'timeZoneName').value;
  const mm = gmt.match(/GMT([+-])(\d{2}):(\d{2})/);
  const mins = (mm[1] === '-' ? -1 : 1) * (+mm[2] * 60 + +mm[3]);
  return new Date(utc - mins * 60000);
}

/** A Date at UTC midnight — the PATHOLOGICAL shape a CSV import or a timezone change can leave. */
const utcMidnight = iso => { const [y, m, d] = iso.split('-').map(Number); return new Date(Date.UTC(y, m - 1, d)); };

function makeSheet(name, rows, counts) {
  return {
    _rows: rows, getName: () => name,
    // Real Apps Script returns a DETACHED COPY. Returning the live array made a caller that both
    // appendRow()s and updates its own snapshot look like it wrote two rows. Do not "optimise" this.
    getDataRange: () => ({ getValues: () => { counts.getValues++; return rows.map(r => r.slice()); } }),
    getLastRow: () => rows.length,
    getRange: (r, c, nr, nc) => ({
      setValues: v => { counts.setValues++;
        for (let i = 0; i < v.length; i++) for (let j = 0; j < v[i].length; j++) {
          while (rows.length < r + i) rows.push([]);
          rows[r - 1 + i][c - 1 + j] = v[i][j];
        } },
      setValue: v => { counts.setValue++; while (rows.length < r) rows.push([]); rows[r - 1][c - 1] = v; },
      clearContent: () => { for (let i = 0; i < (nr || 1); i++) for (let j = 0; j < (nc || 1); j++)
        if (rows[r - 1 + i]) rows[r - 1 + i][c - 1 + j] = ''; }
    }),
    appendRow: v => { counts.appendRow++; rows.push(v.slice()); },
    clearContents: () => { rows.length = 0; }
  };
}

/** Loads a Code.gs under mocked services. Returns { api, sheets, counts, fmt }. */
function loadCodeGs(codePath, sheetData, tz) {
  tz = tz || 'America/Los_Angeles';
  const counts = { getActiveSpreadsheet: 0, getSpreadsheetTimeZone: 0, getValues: 0, setValues: 0, appendRow: 0, setValue: 0 };
  const sheets = {};
  for (const n of Object.keys(sheetData)) sheets[n] = makeSheet(n, sheetData[n], counts);
  const ss = {
    getSpreadsheetTimeZone: () => { counts.getSpreadsheetTimeZone++; return tz; },
    getSheetByName: n => sheets[n] || null,
    insertSheet: n => { sheets[n] = makeSheet(n, [], counts); return sheets[n]; }
  };
  const globals = {
    SpreadsheetApp: { getActiveSpreadsheet: () => { counts.getActiveSpreadsheet++; return ss; } },
    Utilities: { formatDate: (d, z, _pattern) => fmtTZ(d, z) },   // only yyyy-MM-dd is ever requested
    Logger: { log: () => {} },
    ScriptApp: { getProjectTriggers: () => [], deleteTrigger: () => {},
      newTrigger: () => ({ timeBased: () => ({ atHour: () => ({ nearMinute: () => ({ everyDays: () => ({ create: () => {} }) }) }) }) }) }
  };
  const EXPORTS = ['computeTdee','typicalBurn','readBurn','readTargetConfig','normDate','addDays','daysBetween',
    'updateDailyTargets','updateDailySummary','updateWeightSummary','updateBurnSummary','rebuildAllSummary',
    'isWeightEntry','isBurnEntry','numOrBlank','parseInputDate','completeIntakes','regressionSlope',
    'createTargetsTrigger','todayStr','sheetTz','rebuildTrackerFromResponses','rebuildToday',
    'processMacroPayload','upsertSummary','summaryValues','num','dayNumber','windowRows'];
  const src = fs.readFileSync(codePath, 'utf8');
  const present = EXPORTS.filter(n => new RegExp('function\\s+' + n + '\\b').test(src));
  const api = new Function(...Object.keys(globals), src + '\n;return {' + present.join(',') + '};')(...Object.values(globals));
  return { api, sheets, counts, fmt: d => fmtTZ(d, tz), tz };
}

module.exports = { loadCodeGs, sheetsDate, utcMidnight, fmtTZ };

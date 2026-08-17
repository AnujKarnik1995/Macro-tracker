/**
 * Offline check for the gym-logging additions to Code.gs.
 * Loads Code.gs with the Apps Script globals stubbed and exercises the pure paths:
 * payload -> Tracker row -> aggregate -> Summary column M.
 *
 * Run: node backend/test/gym-check.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const src = fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8');

// ---- minimal Apps Script stubs -------------------------------------------------
const sandbox = {
  Logger: { log: () => {} },
  Utilities: {
    formatDate: (d) => {
      const p = n => (n < 10 ? '0' + n : '' + n);
      return d.getUTCFullYear() + '-' + p(d.getUTCMonth() + 1) + '-' + p(d.getUTCDate());
    }
  },
  SpreadsheetApp: {
    getActiveSpreadsheet: () => ({ getSpreadsheetTimeZone: () => 'UTC' })
  },
  console
};
vm.createContext(sandbox);
vm.runInContext(src, sandbox);
const G = sandbox;
// `const` declarations don't land on the sandbox object; read them by evaluating in-context.
const constant = (name) => vm.runInContext(name, sandbox);

// ---- tiny assert ---------------------------------------------------------------
let pass = 0, fail = 0;
function eq(actual, expected, label) {
  const a = JSON.stringify(actual), b = JSON.stringify(expected);
  if (a === b) { pass++; console.log('  ok   ' + label); }
  else { fail++; console.log('  FAIL ' + label + '\n         got      ' + a + '\n         expected ' + b); }
}
function section(s) { console.log('\n' + s); }

// ================================================================================
section('normGym — accepts what a human actually types');
eq(G.normGym({ gym: 'A' }), 'A', '"A"');
eq(G.normGym({ gym: 'b' }), 'B', 'lowercase "b" -> "B"');
eq(G.normGym({ gym: ' B ' }), 'B', 'whitespace trimmed');
eq(G.normGym({ gym: true }), 'A', 'true -> defaults to A');
eq(G.normGym({ gym: 1 }), 'A', '1 -> defaults to A');
eq(G.normGym({ gym: 'Z' }), 'A', 'unknown label still counts as trained');
eq(G.normGym({ gym: false }), null, 'false is NOT a miss-log, just ignored');
eq(G.normGym({ gym: 0 }), null, '0 ignored');
eq(G.normGym({ gym: '' }), null, 'empty ignored');
eq(G.normGym({}), null, 'absent');
eq(G.normGym(null), null, 'null item');

// ================================================================================
section('payloadItemToRow — routing');
const T = '2026-08-17';

const gymOnly = G.payloadItemToRow({ gym: 'A' }, T);
eq(gymOnly.kind, 'gym', 'lone gym -> kind gym');
eq(gymOnly.row.length, 11, 'row is TRACKER_WIDTH (11) wide');
eq(gymOnly.row[10], 'A', 'label lands in col K (index 10)');
eq(gymOnly.row[1], 'Gym A', 'labelled in the meal column for readability');

const meal = G.payloadItemToRow({ cal: 640, p: 48, c: 62, f: 17, meal: 'Dinner' }, T);
eq(meal.kind, 'meal', 'plain meal unchanged');
eq(meal.row[10], '', 'meal with no gym leaves col K blank');
eq(meal.gym, null, 'meal reports no gym');

// The regression this guards: one object carrying BOTH must not silently drop either half.
const combo = G.payloadItemToRow({ cal: 640, p: 48, c: 62, f: 17, gym: 'B' }, T);
eq(combo.kind, 'meal', 'macros+gym stays a meal row');
eq(combo.row[3], 640, '  macros survive');
eq(combo.gym, 'B', '  gym survives, reported separately');
eq(combo.row[10], 'B', '  and is written to col K');

const weighIn = G.payloadItemToRow({ weight: 152.4 }, T);
eq(weighIn.kind, 'weight', 'weigh-in still wins over everything');
eq(G.payloadItemToRow({ basal: 1600 }, T), null, 'legacy {basal} still dropped');
eq(G.payloadItemToRow({ gym: false }, T), null, 'gym:false with nothing else -> nothing usable');

// back-dating
eq(G.payloadItemToRow({ gym: 'A', date: '16/08/2026' }, T).date, '2026-08-16', 'back-date honoured');

// ================================================================================
section('aggregateTracker — one gym day per date, last label wins');
const header = ['date','meal','details','cal','p','c','f','weight','unused','burn','gym'];
const rows = [
  header,
  ['2026-08-15', 'Gym A', '', '', '', '', '', '', '', '', 'A'],
  ['2026-08-16', 'Lunch', '', 600, 40, 55, 18, '', '', '', ''],
  ['2026-08-17', 'Gym A', '', '', '', '', '', '', '', '', 'A'],
  ['2026-08-17', 'Gym B', '', '', '', '', '', '', '', '', 'B'],   // correction later same day
  ['2026-08-18', 'Dinner', '', 640, 48, 62, 17, '', '', '', 'B'], // combo row
  ['2026-08-19', 'Weigh-in', '', '', '', '', '', 152.4, '', ''],  // 10-wide legacy row
];
const agg = G.aggregateTracker(rows);
eq(agg['2026-08-15'].gym, 'A', 'plain gym day');
eq(agg['2026-08-16'].gym, '', 'meal-only day has no session');
eq(agg['2026-08-17'].gym, 'B', 'two entries collapse to one; LAST label wins (correction)');
eq(agg['2026-08-18'].gym, 'B', 'combo row contributes its session');
eq(agg['2026-08-18'].cal, 640, '  ...and its calories');
eq(agg['2026-08-19'].gym, '', 'legacy 10-wide row: missing col K reads as no session');
eq(agg['2026-08-19'].wN, 1, '  ...and its weigh-in still parses');

// ================================================================================
section('SUMMARY_HEADER — gym appended, target columns unmoved');
const HDR = constant('SUMMARY_HEADER');
eq(HDR.length, 13, '13 columns');
eq(HDR[8], 't_cal', 'col I still t_cal');
eq(HDR[11], 't_fat', 'col L still t_fat');
eq(HDR[12], 'gym', 'col M is gym');
eq(HDR[6], 'unused', 'col G dead slot preserved');
eq(constant('TRACKER_WIDTH'), 11, 'TRACKER_WIDTH widened to 11');

// ================================================================================
section('upsertSummary — writes col 13 without disturbing I-L');
const summaryRows = [
  HDR.slice(),
  ['2026-08-17', 1900, 140, 200, 47, 152.4, '', '', 1925, 151.5, 236, 47.5, ''],
];
const written = [];
const appended = [];
// NOTE: appendRow must NOT push into summaryRows. In Apps Script the sheet and the caller's
// values snapshot are separate stores — upsertSummary appends to the sheet and then mirrors the
// row into the snapshot itself. A harness that does both double-counts the append.
const fakeSheet = {
  getRange: (r, col, nr, nc) => ({ setValues: (v) => written.push({ r, col, v: v[0] }) }),
  appendRow: (r) => appended.push(r)
};
G.upsertSummary(fakeSheet, summaryRows, '2026-08-17', 13, ['A'], true);
eq(written[0], { r: 2, col: 13, v: ['A'] }, 'wrote col 13 of the matching row');
eq(summaryRows[1][12], 'A', 'snapshot kept in step');
eq(summaryRows[1].slice(8, 12), [1925, 151.5, 236, 47.5], 'target centres untouched');

// a session on a day with no other data must CREATE the row
const before = summaryRows.length;
G.upsertSummary(fakeSheet, summaryRows, '2026-08-20', 13, ['B'], true);
eq(summaryRows.length, before + 1, 'appended a row for a food-free gym day');
eq(appended.length, 1, '  written to the sheet exactly once');
eq(summaryRows[before][0], '2026-08-20', '  dated correctly');
eq(summaryRows[before][12], 'B', '  session in col M');
eq(summaryRows[before].length, 13, '  full width');
eq(summaryRows[before].slice(1, 12), ['','','','','','','','','','',''], '  everything else blank');

// ================================================================================
console.log('\n' + (fail === 0 ? 'ALL PASS' : 'FAILURES: ' + fail) + '  (' + pass + ' assertions)');
process.exit(fail === 0 ? 0 : 1);

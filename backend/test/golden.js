#!/usr/bin/env node
// Golden test: the Tracker -> Summary and Responses -> Tracker paths, against REAL exports.
//
//   node golden.js <Code.gs> <Tracker.csv> <Summary.csv> [FormResponses.csv] [--vs other/Code.gs]
//
// 1. rebuildAllSummary(real Tracker) must reproduce the real Summary, cell for cell.
// 2. rebuildTrackerFromResponses(real responses) -> rebuildAllSummary must give the SAME Summary
//    as the real Tracker does, even if the Tracker row count differs.
// 3. With --vs, both are also diffed against another Code.gs version.
'use strict';
const fs = require('fs'), path = require('path');
const { loadCodeGs, sheetsDate } = require('./mock-apps-script.js');
const TZ = 'America/Los_Angeles';

const args = process.argv.slice(2);
const vsIdx = args.indexOf('--vs');
const other = vsIdx >= 0 ? args[vsIdx + 1] : null;
const [CODE, TRACKER, SUMMARY, RESPONSES] = (vsIdx >= 0 ? args.slice(0, vsIdx) : args);

function parseCsv(t) {
  const out = []; let row = [], cur = '', q = false;
  for (let i = 0; i < t.length; i++) { const c = t[i];
    if (q) { if (c === '"') { if (t[i+1] === '"') { cur += '"'; i++; } else q = false; } else cur += c; }
    else if (c === '"') q = true;
    else if (c === ',') { row.push(cur); cur = ''; }
    else if (c === '\n') { row.push(cur); out.push(row); row = []; cur = ''; }
    else if (c !== '\r') cur += c; }
  if (cur || row.length) { row.push(cur); out.push(row); }
  return out.filter(r => r.some(v => v !== ''));
}
const cell = v => v === '' ? '' : (isNaN(Number(v)) ? v : Number(v));
function grid(file, width, dateCol0) {
  return parseCsv(fs.readFileSync(file, 'utf8')).map((r, i) => {
    const out = [];
    for (let j = 0; j < width; j++) { const v = r[j] === undefined ? '' : r[j];
      out.push(i === 0 ? v : (j === 0 && dateCol0 ? (v ? sheetsDate(v, TZ) : '') : cell(v))); }
    return out; });
}
function respGrid(file) {
  return parseCsv(fs.readFileSync(file, 'utf8')).map((r, i) => {
    if (i === 0) return r.slice(0, 2);
    const m = String(r[0]).match(/^(\d{2})\/(\d{2})\/(\d{4})/);
    return [m ? sheetsDate(m[3] + '-' + m[2] + '-' + m[1], TZ) : r[0], r[1]]; });
}
const H = ['date','cal','p','c','f','weight','unused','burn','t_cal','t_pro','t_carb','t_fat'];

function run(codePath, mode) {
  const sheetData = { Summary: grid(SUMMARY, 12, true), Tracker: grid(TRACKER, 10, true),
                      Targets: [['Macro','Lower','Upper']] };
  if (RESPONSES) sheetData['Form responses 1'] = respGrid(RESPONSES);
  const { api, sheets, fmt } = loadCodeGs(codePath, sheetData, TZ);
  if (mode === 'fromResponses') api.rebuildTrackerFromResponses(); else api.rebuildAllSummary();
  const k = v => (v instanceof Date) ? fmt(v) : String(v).trim();
  return {
    summary: new Map(sheets.Summary._rows.slice(1).map(r => [k(r[0]), r.map(v => v instanceof Date ? fmt(v) : v)])),
    trackerRows: sheets.Tracker._rows.length - 1
  };
}
function compare(label, A, B, aName, bName) {
  const dropped = [...A.keys()].filter(d => !B.has(d));
  const added   = [...B.keys()].filter(d => !A.keys().toArray?.().includes(d) && !A.has(d));
  let diffs = [];
  for (const d of [...A.keys()].filter(x => B.has(x)).sort())
    for (let j = 1; j < 12; j++) {
      const norm = v => v === '' || v === undefined ? '' : (typeof v === 'number' ? String(+v.toFixed(4)) : String(v));
      if (norm(A.get(d)[j]) !== norm(B.get(d)[j])) diffs.push(`${d} ${H[j]}: ${aName}=${norm(A.get(d)[j])||'(blank)'} ${bName}=${norm(B.get(d)[j])||'(blank)'}`);
    }
  console.log(`\n  ${label}`);
  console.log(`    only in ${aName}: ${dropped.length ? dropped.join(', ') : 'none'}`);
  console.log(`    only in ${bName}: ${added.length ? added.join(', ') : 'none'}`);
  console.log(`    differing cells : ${diffs.length}`);
  diffs.slice(0, 25).forEach(x => console.log('      ' + x));
  if (diffs.length > 25) console.log(`      ... ${diffs.length - 25} more`);
  return { dropped, added, diffs };
}

console.log('='.repeat(76));
console.log('GOLDEN TEST  ' + path.basename(CODE));
console.log('='.repeat(76));
const live = new Map(grid(SUMMARY, 12, true).slice(1)
  .map(r => [r[0] instanceof Date ? new Intl.DateTimeFormat('en-CA',{timeZone:TZ,year:'numeric',month:'2-digit',day:'2-digit'}).format(r[0]) : String(r[0]).trim(),
             r.map(v => v instanceof Date ? new Intl.DateTimeFormat('en-CA',{timeZone:TZ,year:'numeric',month:'2-digit',day:'2-digit'}).format(v) : v)]));

const fromTracker = run(CODE, 'fromTracker');
console.log('\n  Tracker rows in: ' + (grid(TRACKER,10,true).length - 1));
compare('1) rebuildAllSummary(real Tracker)  vs  the live Summary', live, fromTracker.summary, 'live', 'rebuild');

if (RESPONSES) {
  const fromResp = run(CODE, 'fromResponses');
  console.log('\n  Tracker rows produced from responses: ' + fromResp.trackerRows +
              '   (live Tracker has ' + (grid(TRACKER,10,true).length - 1) + ')');
  compare('2) responses -> Tracker -> Summary  vs  real Tracker -> Summary',
          fromTracker.summary, fromResp.summary, 'fromTracker', 'fromResponses');
}
if (other) {
  const a = run(other, 'fromTracker'), b = run(CODE, 'fromTracker');
  const r = compare('3) rebuildAllSummary: ' + path.basename(other) + '  vs  ' + path.basename(CODE), a.summary, b.summary, 'old', 'new');
  if (RESPONSES) {
    const a2 = run(other, 'fromResponses'), b2 = run(CODE, 'fromResponses');
    const r2 = compare('4) fromResponses: ' + path.basename(other) + '  vs  ' + path.basename(CODE), a2.summary, b2.summary, 'old', 'new');
    console.log('\n  Tracker rows: old ' + a2.trackerRows + '  new ' + b2.trackerRows +
                (a2.trackerRows !== b2.trackerRows ? '   <-- differs (junk rows skipped)' : ''));
    if (r.diffs.length || r2.diffs.length) process.exitCode = 1;
  }
}

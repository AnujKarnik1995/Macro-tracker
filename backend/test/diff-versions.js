#!/usr/bin/env node
// Differential test: run two versions of Code.gs against identical fixtures and assert that
// every observable output matches. This is the gate for any refactor claiming "no behaviour change".
//
//   node diff-versions.js <old Code.gs> <new Code.gs> [summary.csv]
//
// Exit 0 = identical. Exit 1 = something moved (the differing group is printed).
'use strict';
const fs = require('fs'), path = require('path');
const { loadCodeGs, sheetsDate, utcMidnight, fmtTZ } = require('./mock-apps-script.js');

const [OLD, NEW] = process.argv.slice(2, 4);
const SUMMARY_CSV = process.argv[4] || path.join(__dirname, 'fixtures', 'summary.csv');
if (!OLD || !NEW) { console.error('usage: node diff-versions.js <old.gs> <new.gs> [summary.csv]'); process.exit(2); }
const TZ = 'America/Los_Angeles';

function summaryRows(dateFn) {
  const lines = fs.readFileSync(SUMMARY_CSV, 'utf8').trim().split('\n');
  const out = [lines[0].split(',')];
  lines.slice(1).forEach(l => {
    const c = l.split(',');
    const row = [dateFn(c[0])];
    for (let i = 1; i < c.length; i++) row.push(c[i] === '' ? '' : (isNaN(Number(c[i])) ? c[i] : Number(c[i])));
    while (row.length < 12) row.push('');
    out.push(row);
  });
  return out;
}
// Synthetic Tracker: mixed Date/string dates, two weigh-ins in a day, two burn sessions in a day,
// zero values, junk values. None of this exists in the real export, and all of it is reachable.
function trackerRows(D) { return [
  ['date','meal','details','cal','p','c','f','weight','unused','burn'],
  [D('2026-08-08'),'Breakfast','oats',420,32,55,9,'','',''],
  [D('2026-08-08'),'Lunch','chicken',610,55,60,14,'','',''],
  ['2026-08-08','Dinner','fish',529,61,31,25,'','',''],
  [D('2026-08-08'),'Weigh-in','','','','','',135.2,'',''],
  [D('2026-08-08'),'Weigh-in','','','','','',135.4,'',''],
  [D('2026-08-08'),'Burn','','','','','','','',180],
  [D('2026-08-08'),'Burn','','','','','','','',120],
  [D('2026-08-07'),'Lunch','x',700,50,70,20,'','',''],
  [D('2026-08-07'),'Weigh-in','','','','','',0,'',''],
  [D('2026-08-07'),'Burn','','','','','','','',''],
  [D('2026-08-06'),'Snack','y','abc','','','','','',''],
  [D('2026-08-06'),'Weigh-in','','','','','','n/a','',''],
  [D('2026-08-06'),'Burn','','','','','','','','oops'],
]; }
const targetsRows = D => [['Macro','Lower','Upper','UnderSeverity','Effective From'],
  ['Calories',1625,1750,'mild',''],['Protein',145,158,'mild',''],['Carbs',160,170,'mild',''],
  ['Fat',45,50,'danger',''],['Weight Loss',0.7,0.9,'mild',''],
  ['Floor',1625,'','',D('2026-08-02')],['Deficit',425,'','',D('2026-08-02')]];

const DATES = ['2026-07-20','2026-08-01','2026-08-02','2026-08-05','2026-08-08','2026-08-09','2026-09-01'];

function observe(codePath, dateFn) {
  const { api, sheets, counts, fmt } = loadCodeGs(codePath,
    { Summary: summaryRows(dateFn), Targets: targetsRows(dateFn), Tracker: trackerRows(dateFn) }, TZ);
  const ss = { getSheetByName: n => sheets[n] };
  const r = (v, n) => v === null || v === undefined ? null : +Number(v).toFixed(n === undefined ? 6 : n);
  const o = {};
  o.computeTdee   = DATES.map(d => r(api.computeTdee(ss, d)));
  o.typicalBurn   = DATES.map(d => r(api.typicalBurn(ss, d)));
  o.readBurn      = DATES.map(d => api.readBurn(ss, d));
  o.targetConfig  = DATES.map(d => api.readTargetConfig(ss, d));
  o.addDays       = ['2026-03-07','2026-03-08','2026-11-01','2026-02-28','2026-12-31','2026-01-01']
                      .flatMap(d => [api.addDays(d,1), api.addDays(d,-1), api.addDays(d,28), api.addDays(d,-28)]);
  o.daysBetween   = [['2026-03-01','2026-03-31'],['2026-10-25','2026-11-15'],['2026-01-01','2026-12-31'],
                     ['2026-08-08','2026-08-08'],['2026-08-09','2026-08-01']].map(([a,b]) => api.daysBetween(a,b));
  o.numericGuards = [undefined,null,'',' ',0,'0',5,'5','abc',NaN,true,false,'1e3','  7 ','$5','1,5']
                      .map(v => [api.numOrBlank(v), api.isWeightEntry({weight:v}), api.isBurnEntry({burn:v})]);
  o.parseInputDate = ['9/8/2026','09/08/2026','2026-08-09','31/2/2026','','x',null,'1/1/2026'].map(v => api.parseInputDate(v));
  o.completeIntakes = [[1700,1700,1700,1700,1700,1700,1700,1059],[1700,1059],
                       [900,1700,1700,1700,1700,1700,1700,1700,1700,1700,1700,1700]].map(a => api.completeIntakes(a));
  o.regressionSlope = [r(api.regressionSlope([[0,140],[5,139],[10,138]])), r(api.regressionSlope([[3,140]]))];
  // write paths: existing date, brand-new date, and a date with no Tracker rows at all
  ['2026-08-08','2026-08-06','2026-09-15'].forEach(d => {
    api.updateDailySummary(d); api.updateWeightSummary(d); api.updateBurnSummary(d);
  });
  api.updateDailyTargets('2026-08-09');   // append path
  api.updateDailyTargets('2026-08-08');   // in-place path
  const key = v => (v instanceof Date) ? fmt(v) : String(v).trim();
  // Pad to full width before comparing. A real Sheets CSV export pads every row to the used column
  // count, so a row appended 8-wide and one appended 12-wide are indistinguishable to the widget
  // (verified against the compiled CsvParser: 57 rows parsed, 39 green days, identical bands both
  // ways). Comparing raw widths would flag that cosmetic difference forever.
  const pad = row => { const r = row.map(v => v instanceof Date ? fmt(v) : v);
                       while (r.length < 12) r.push(''); return r; };
  o.summaryAfterWrites = sheets.Summary._rows.map(pad);
  o.duplicateDates = {};
  DATES.concat(['2026-09-15','2026-08-06']).forEach(d => {
    o.duplicateDates[d] = sheets.Summary._rows.filter(row => key(row[0]) === d).length;
  });
  api.rebuildAllSummary();
  o.summaryAfterRebuild = sheets.Summary._rows.map(pad);
  return { o, counts };
}

let failed = [];
console.log('='.repeat(74));
console.log('DIFFERENTIAL TEST   ' + path.basename(OLD) + '  vs  ' + path.basename(NEW));
console.log('='.repeat(74));
for (const [label, dateFn] of [
  ['dates as Sheets returns them (midnight in sheet tz)', iso => sheetsDate(iso, TZ)],
  ['dates as UTC midnight (CSV-import / tz-change shape)', utcMidnight]
]) {
  console.log('\n  ' + label);
  const a = observe(OLD, dateFn), b = observe(NEW, dateFn);
  for (const g of Object.keys(a.o)) {
    const same = JSON.stringify(a.o[g]) === JSON.stringify(b.o[g]);
    if (!same) failed.push(label + ' / ' + g);
    console.log('    ' + g.padEnd(24) + (same ? 'identical' : '*** DIFFERS ***'));
  }
  console.log('    ' + 'service calls'.padEnd(24) +
    'getValues ' + a.counts.getValues + ' -> ' + b.counts.getValues +
    '   tz ' + (a.counts.getActiveSpreadsheet + a.counts.getSpreadsheetTimeZone) +
    ' -> ' + (b.counts.getActiveSpreadsheet + b.counts.getSpreadsheetTimeZone));
}
if (failed.length) {
  console.log('\n  FAILED: ' + failed.join(', '));
  const a = observe(OLD, iso => sheetsDate(iso, TZ)), b = observe(NEW, iso => sheetsDate(iso, TZ));
  failed.forEach(f => { const g = f.split(' / ').pop();
    console.log('\n--- ' + g + ' ---\n  old: ' + JSON.stringify(a.o[g]).slice(0,700) + '\n  new: ' + JSON.stringify(b.o[g]).slice(0,700)); });
  process.exit(1);
}
console.log('\n  ALL GROUPS IDENTICAL IN BOTH DATE REGIMES');

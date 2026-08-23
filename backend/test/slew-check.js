const fs=require('fs'), vm=require('vm'), path=require('path');
const mock=require('./mock-apps-script.js');
const ctx=(mock.makeContext?mock.makeContext():mock.context?mock.context():null);
const sandbox = ctx || Object.assign({}, mock);
if(!sandbox.Logger) sandbox.Logger={log(){}};
if(!sandbox.Utilities) sandbox.Utilities={formatDate:(d,tz,f)=>d.toISOString().slice(0,10)};
if(!sandbox.SpreadsheetApp) sandbox.SpreadsheetApp={getActiveSpreadsheet:()=>null};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'../Code.gs'),'utf8'), sandbox);
const {slewAnchor, previousAnchor, TARGET_SLEW_KCAL_PER_WEEK} = sandbox;

const H=["date","cal","p","c","f","weight","unused","burn","t_cal","t_pro","t_carb","t_fat","gym"];
const row=(d,tcal)=>{const r=new Array(13).fill("");r[0]=d;if(tcal!==null)r[8]=tcal;return r;};
let pass=0,fail=0;
function t(name,got,want){
  const ok=Math.abs(got-want)<0.01 || got===want;
  console.log(`  ${ok?'ok  ':'FAIL'}  ${name}: got ${got}, want ${want}`);
  ok?pass++:fail++;
}
console.log(`slew cap = ${TARGET_SLEW_KCAL_PER_WEEK} kcal/wk (${(TARGET_SLEW_KCAL_PER_WEEK/7).toFixed(2)}/day)\n`);

// 1. consecutive day, big upward jump -> clamped to one day's allowance
let s=[H,row("2026-08-21",1700)];
t("1-day gap, raw 2300 clamped up",      slewAnchor(s,"2026-08-22",2300), 1700+50/7);
t("1-day gap, raw 1200 clamped down",    slewAnchor(s,"2026-08-22",1200), 1700-50/7);
t("1-day gap, small move passes through",slewAnchor(s,"2026-08-22",1703), 1703);

// 2. gap in the sheet pro-rates, does not bank unlimited slack
t("7-day gap allows a full week",        slewAnchor(s,"2026-08-28",2300), 1700+50);
t("14-day gap allows two weeks",         slewAnchor(s,"2026-09-04",2300), 1700+100);

// 3. no prior anchor at all -> free landing
t("no history, unclamped",               slewAnchor([H],"2026-08-22",2300), 2300);
t("history but no t_cal, unclamped",     slewAnchor([H,row("2026-08-21",null)],"2026-08-22",2300), 2300);

// 4. re-entrancy: today's own row must be ignored (late submission re-runs the day)
let s2=[H,row("2026-08-21",1700),row("2026-08-22",1707)];
t("same-day rerun measures from 8/21",   slewAnchor(s2,"2026-08-22",2300), 1700+50/7);
t("previousAnchor skips own date",       previousAnchor(s2,"2026-08-22").date, "2026-08-21");

// 5. picks the LATEST prior anchor, not the first / not row order
let s3=[H,row("2026-08-10",2312),row("2026-08-21",1700),row("2026-08-15",1800)];
t("latest prior anchor wins (unsorted)", previousAnchor(s3,"2026-08-22").anchor, 1700);

// 6. floor interaction is the CALLER's job; slew must be able to return sub-floor
t("slew may return below floor",         slewAnchor([H,row("2026-08-21",1630)],"2026-08-22",1000), 1630-50/7);

// 7. disabled cap
sandbox.eval && null;
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail?1:0);

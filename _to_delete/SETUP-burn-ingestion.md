# Setup — watch burn ingestion (Pixel Watch → Health Connect → Sheet)

Goal: land two new daily numbers in the Sheet — **basal (BMR)** and **active/exercise burn** — via
the same Google Form pipeline everything else uses. The widget then reads them for TDEE + the
dynamic target. Nothing here can be tested in a non-Android/non-Google environment, so this is a
build-and-verify-on-device checklist. Steps are ordered; do them top to bottom.

Payload shape (one item, same form as weigh-ins):
```json
[{ "basal": 1680, "burn": 520, "date": "27/07/2026" }]
```
`date` is optional (DD/MM/YYYY, day-first); omit for today. `burn` = active kcal, `basal` = BMR kcal.
Either may be omitted if unavailable.

---

## 1. Backend (Apps Script) — already written

`backend/Code.gs` now routes burn payloads. What changed:
- New `isBurnEntry` → appends a **`Burn`** row to `Tracker` with **basal in col I, active in col J**.
- `updateBurnSummary` writes the day's values to **`Summary` col G (basal), col H (burn)** —
  last non-blank value for the date wins (a later same-day sync = the more complete total).
- `rebuildAllSummary` / `rebuildTrackerFromResponses` now carry burn (Tracker is 10 cols, Summary 8).

Do this once:
1. Paste the updated `Code.gs` into the Apps Script editor and **Save**.
2. Run **`rebuildAllSummary`** once. This rewrites `Summary` with the new 8-column header
   (`date, cal, p, c, f, weight, basal, burn`). Existing macro/weight data is preserved; basal/burn
   are blank until the watch starts posting.
3. Confirm the published `Summary` CSV now shows columns G and H. (No new publish URL needed —
   it's the same sheet.)

## 2. Google Form — reuse the existing one

The watch posts through the **same form** you use for weigh-ins (the one whose response feeds
`e.values[1]`). You need two values for the app:
- **`FORM_RESPONSE_URL`**: open the form, copy its URL, replace the trailing `/viewform` with
  `/formResponse`. It looks like `https://docs.google.com/forms/d/e/<LONG_ID>/formResponse`.
- **`ENTRY_FIELD`**: the payload question's field id. Open the live form → right-click the payload
  text box → Inspect → find `name="entry.XXXXXXXXX"`. (Or "Get pre-filled link", type anything in
  the payload field, and read `entry.XXXXXXXXX` out of the generated URL.)

Put both into `BurnUploadWorker.kt` (the `FORM_RESPONSE_URL` and `ENTRY_FIELD` constants at the
bottom). Send one manual test response with `[{"burn":500,"basal":1650}]` and confirm a `Burn` row
lands in `Tracker` and `Summary` G/H fill in.

## 3. App — Gradle dependency

In `app/build.gradle.kts`, add to `dependencies { ... }`:
```kotlin
implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
```
(Use the latest `connect-client` version available; the API used here is stable across recent
alphas. `minSdk = 26` already satisfies Health Connect's floor.)

## 4. App — manifest

Add the two read permissions at the top of `AndroidManifest.xml` (next to `INTERNET`):
```xml
<uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED" />
<uses-permission android:name="android.permission.health.READ_BASAL_METABOLIC_RATE" />
```

Health Connect also requires the app to expose a **permissions-rationale** target. Simplest for a
personal build — add this intent-filter to `WidgetConfigActivity` (or any activity):
```xml
<intent-filter>
    <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
</intent-filter>
```
And for Android 14+ (API 34), also declare the activity-alias variant if you later target the newer
Health Connect permission flow — not required at `targetSdk 34` with the alpha client, but note it
if you bump the client.

## 5. App — grant the permissions (one-time, needs a UI entry point)

Health Connect permissions are granted through its own system dialog, launched via a contract. Add
this to `WidgetConfigActivity` (it already exists as the widget's config screen):
```kotlin
private val reader by lazy { HealthConnectBurnReader(this) }

private val requestPerms = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()
) { granted ->
    // granted: Set<String> — if it containsAll(reader.permissions), you're set.
}

// call from a button / on first setup:
private fun ensureBurnAccess() {
    lifecycleScope.launch {
        if (!reader.hasAccess()) requestPerms.launch(reader.permissions)
    }
}
```
Imports: `androidx.health.connect.client.PermissionController`, `androidx.lifecycle.lifecycleScope`,
`kotlinx.coroutines.launch`. If Health Connect isn't installed, `getSdkStatus` returns
not-available and `read()` returns null — send the user to install/update Health Connect (Play
Store) in that case.

## 6. Schedule the upload

`BurnUploadWorker` reads a date and posts it. Enqueue it twice a day:
- **~2–3 pm** for *today* — this is when the dynamic target is computed, and your morning workout is
  already synced: `BurnUploadWorker.runNow(context)`.
- **~00:50** (just after the nightly rebuild) for *yesterday*, to lock in the final total:
  `BurnUploadWorker.runNow(context, LocalDate.now().minusDays(1))`.

Use a `PeriodicWorkRequest` (min interval 15 min; a daily cadence with an initial delay to the
target hour is fine) or piggyback on the existing widget `WorkManager` schedule. Re-posts are safe:
`updateBurnSummary` is last-writer-wins per date.

## 6b. One-time backfill + older data

- **Last ~30 days (automatic):** right after granting Health Connect access, call once:
  `BurnUploadWorker.runBackfill(context)`. It reads the last 30 days and posts them in a **single**
  Form submission (one array payload → up to 30 `Burn` rows). Days the watch wasn't worn are skipped;
  worn rest days post as `burn: 0`. Health Connect only retains ~30 days, so that's the ceiling.
- **Older than 30 days (manual, one-time):** submit a back-dated burn payload through the **Form**
  (not by typing into the sheet — a rebuild would wipe direct Summary edits). You can batch many days
  in one submission:
  `[{"basal":1650,"burn":480,"date":"01/05/2026"},{"basal":1655,"burn":520,"date":"02/05/2026"}]`
  It lands in Form responses, so it survives both `rebuildAllSummary` and `rebuildTrackerFromResponses`.
  After entering, run `updateTargetsToday` (or wait for the trigger) if you want those days re-scored.

## 7. Verify on device (checklist)

- [ ] Manual form response with `[{"burn":500,"basal":1650}]` → `Tracker` Burn row + `Summary` G/H.
- [ ] Grant Health Connect permissions; confirm `reader.hasAccess()` returns true.
- [ ] `BurnUploadWorker.runNow(this)` → a Burn row appears for today with plausible numbers.
- [ ] A day the watch wasn't worn → no active record → `burn` omitted → Summary H blank for that day
      (this is the "missing → fallback", not a rest-day zero).
- [ ] Calibrate sanity: the watch's daily total will read high — that's expected; calibration
      against back-calc TDEE happens later in the calculator, not here.

---

## Daily targets (Apps Script — computes Summary I–L)

The updated `Code.gs` also computes the per-day macro **target centers** into Summary cols I–L
(`t_cal, t_pro, t_carb, t_fat`). Setup:

1. **Targets tab — add two dated rows** (same shape as your macro bands: name, lower, upper,
   severity, EffectiveFrom). Put the value in the **lower** column, leave upper blank:
   - `Floor | 1700 | | | 2026-06-15`  ← intake never prescribed below this (anti-starve safety)
   - `Deficit | 425 | | | 2026-06-15` ← deficit to hold (a negative value later = surplus, for a bulk)
   Protein/fat centers come from your existing band rows — no new rows needed for those.
2. Run **`rebuildAllSummary`** once to widen Summary to the 12-column header (any existing targets
   are preserved).
3. Run **`updateTargetsToday`** from the editor to compute today's I–L now; confirm the row fills in.
4. Run **`createTargetsTrigger`** once to install the daily ~14:30 recompute (after the morning
   workout has synced, before dinner).

Behavior:
- Works **before** the watch feed exists — TDEE comes from logged calories + weight; the workout
  delta stays 0 until burn data flows into col H.
- If TDEE data is thin or the `Floor`/`Deficit` rows are missing, I–L are left blank and the widget
  falls back to the static bands (safe default).
- The compute owns I–L; the ~14:30 trigger overwrites them daily (don't hand-edit unless you
  disable the trigger).
- Tab name assumed **"Targets"** — change `TARGETS_TAB` in `Code.gs` if yours differs.

## Notes / assumptions (flagged for review)

- **Active calories vs workout-only.** `ActiveCaloriesBurnedRecord` is Fitbit's HR-based active
  energy for the whole day (workout + general movement), not workout-only. That's fine for the
  delta-vs-typical math (typical already includes your usual movement). If you ever want
  *workout-only*, we'd intersect with `ExerciseSessionRecord` time ranges — a later refinement.
- **BMR** is read as the latest `BasalMetabolicRateRecord` for the day (`inKilocaloriesPerDay`).
- **Not-worn vs rest.** No active-calories records for a date → `null` → omitted → widget reads null
  → dynamic target uses no delta (fallback). A worn day returns a real number (a true rest day is a
  small/zero value, still posted). This is the distinction the design depends on.
- Still **not built**: the inline-limits migration (per-row limit columns + backfill) and the
  Energy page render/routing. Those are the next roadmap items; the band-mapping decision gates the
  first.

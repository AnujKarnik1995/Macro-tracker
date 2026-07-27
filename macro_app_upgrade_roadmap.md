# Macro App — Upgrade Roadmap

## Vision
Collapse the current chain (widget + Google Form + Sheet + Apps Script + Gemini alias) into **one self-contained Android app**: local data, precision preserved (your curated food table + gram weighing), frictionless logging, an in-app interactive recipe agent, and the always-visible widget for motivation. No external hops, no subscription dependency.

## Decisions locked
- **Data:** on-device (Room/SQLite) is the source of truth; **manual export** (JSON/CSV) for backup.
- **Build:** phased (ship value early, keep running throughout).
- **Stack:** native Kotlin + Jetpack Compose; reuse existing rendering; LLM via **API** (provider TBD — Gemini API free tier is the default and is independent of the Gemini *app* subscription).

## Target architecture
```
[ Local DB: meals · daily totals · pantry · targets · food-lookup ]
            |                                   ^
            v                                   |
   in-app screens (log / dashboard / chat)      |
            |                                   |
            v                                   |
     LLM API  <— {rules + remaining macros + pantry + history}
            |
   Home-screen widget reads the SAME local DB
```
The app owns the data and calls out to the model — nothing external reaches in. That single inversion removes every failure mode from the old pipeline (extensions, pivot tables, first-tab, Keep gating, tier limits, pre-bake freshness).

## Data model (v1 sketch)
- `meals(id, datetime, mealType, details, cal, p, c, f, foodId?)`
- `foods(id, name, basis[per100g | perServing], state[raw | cooked], cal, p, c, f)` — your curated lookup table
- `pantry(id, name, qty, unit)`
- `targets(macro, lower, upper)` — the bands
- `settings(goalDate, goalLabel, …)`
- daily totals computed from `meals` (cache if needed)

## Phased plan

### Phase 0 — Foundation & migration
- [ ] Room schema + DAOs
- [ ] **Import existing history**: export Sheet "Tracker" → CSV → importer into `meals` (preserves streak + countdown history — do NOT skip, the streak depends on it)
- [ ] Seed `foods` from your existing food→macro CSV (keep basis + raw/cooked tags)
- [ ] Manual export (JSON + CSV) working from day one

### Phase 1 — Core tracker  (retires the Form → Sheet pipeline)
- [ ] Logging screen: gram-based entry against `foods` lookup; quick-add recents; (transitional: optional AI-JSON paste import)
- [ ] Today/dashboard: reuse `ChartRenderer` rings/bars — remaining, bands, streak, countdown
- [ ] Port streak/countdown computation from `ChartWorker` into the app
- [ ] Widget reads local DB instead of CSV
  - [ ] (parked #10) kill residual flicker — trivial now that there's no network frame
  - [ ] (parked) tap-to-open → launches the app
  - [ ] (parked) Jetpack Glance rewrite
  - [ ] (parked) Material You theming
- [ ] Settings: configurable goal date/label (parked) + editable target bands
- [ ] Decommission Form / Sheet / Apps Script / published CSV (replaced by local data + export)

### Phase 2 — In-app recipe agent  (retires the Gemini alias + the subscription dependency)
- [ ] Pantry screen (add/edit/remove with quantities)
- [ ] Recipe chat screen: LLM API call with `{rules + remaining + pantry + history}`; full interactive back-and-forth (swap, "out of tempeh," lighter option)
- [ ] (optional) conversational logging via function-calling ("I ate 180g tempeh" → structured save)
- [ ] Secure the API key locally (fine for a personal build; don't ship it publicly)

### Phase 3 — Polish & later
- [ ] Trends/analysis screen — recovers the "re-analyzable raw data" you valued in Sheets, now on-device
- [ ] Richer export / optional auto cloud-sync (if you later want hands-off backup)
- [ ] Animations, accessibility, performance pass

## Old → new file mapping
| Existing | Fate |
|---|---|
| `ChartRenderer.kt` | **Reuse** (app + widget) |
| `ChartWorker.kt` | Streak/countdown logic moves into app; fetch removed |
| `SheetFetcher.kt`, `SheetCache.kt` | **Removed** (no remote fetch) |
| `BitmapCache.kt` | Keep (widget anti-blink) |
| `SheetWidgetProvider.kt` | Widget reads the DB |
| `WidgetPrefs.kt`, `WidgetConfigActivity.kt` | Becomes Settings (goal date, bands) |
| `MacroCalculator.kt`, `MacroModel.kt`, `ColorRamp.kt` | **Reuse** |
| `CsvParser.kt` | Repurpose for import/export |

## Sequencing note (re: your free year)
- **Phase 1** already removes the Form/Sheet fragility.
- **Phase 2** is the one that removes the Gemini-app/subscription dependency (chat moves to the API's own free tier). If being subscription-independent before your free year ends matters, **prioritize reaching Phase 2 before then.**
- Precision is preserved from Phase 1 onward, so progress toward your goal date is unaffected by any of this.

## Open items to decide
- LLM provider for the Phase 2 chat (Gemini API free tier default; Claude / OpenAI alternatives).
- Keep a transitional AI-JSON logging path in Phase 1, or go straight to native in-app logging.

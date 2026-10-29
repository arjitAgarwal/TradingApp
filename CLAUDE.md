# CLAUDE.md

ALPHA (repo name PaperTrade) is an Android paper-trading app for Indian markets: NIFTY/SENSEX/BANKNIFTY futures and
options plus delivery equity, priced from real market data, traded with **virtual money only**. Nothing in the code
places a real order or moves money. Network use is read-only market data.

## Layout
- `core/`: pure Kotlin/JVM. `Engine.kt` (`PaperExchange`), `Accounting.kt`, `Models.kt` (Order, Fill, Position,
  AccountState), `MarketHours.kt` (`MarketHours.IST`), `Config.kt` (charges, margin), `IndianFormat.kt`.
  The trading rules are in `docs/RULES.md`; keep them in sync with the code.
- `app/`: Compose + Room + DataStore, MVVM, manual DI in `PaperTradeApp.kt`.
  - `data/market/`: the `MarketDataProvider` interface and `DemoProvider`, `FyersProvider` + `FyersFeed` (WebSocket),
    `UpstoxProvider` + `UpstoxFeed`, `Polling.kt`.
  - `data/repo/`: `TradingRepository` (Room transactions), `QuoteHub`, `WatchlistRepository`, `FyersAuth`, `UpstoxAuth`, `Settings`.
  - `ui/ViewModels.kt` (`AppVM`), `ui/screens/` (`MainScreen`, `TradingScreens` with Watchlist/Positions/Holdings/Orders,
    `OrderTicket`, `DetailScreens`, `FyersLoginScreen`), `ui/Format.kt` (`Fmt`).
- `docs/PROVIDERS.md` covers provider research and limits. `docs/NOTES_PAGES_3-10.txt` is the UI rework plan taken
  from a friend's annotated PDF.
- `README.md` is partly out of date (it says `:app` was never compiled and there is no WebSocket). Trust the code.

## Build and test
- Compile: `./gradlew :app:compileDebugKotlin`
- APK: `./gradlew :app:assembleDebug`, which writes `app/build/outputs/apk/debug/app-debug.apk`
- Tests: `./gradlew :core:test :app:testDebugUnitTest`
- adb: `C:/Users/Arjit/AppData/Local/Android/Sdk/platform-tools/adb.exe` (use the full path)
- `local.properties` holds SDK paths and optional FYERS app credentials. It is git-ignored; never commit it.

## Code conventions
- Money is `BigDecimal` everywhere. Never use Kotlin `/` on BigDecimal (it truncates); use `Dec.div`.
- Futures and options quantities are in lots; the lot size comes from instrument metadata and is never hardcoded.
- Dates and "today" are always IST (`MarketHours.IST`), not the device zone.
- The code is dense Compose: several statements per line with `;`, short KDoc above composables. Match the
  surrounding style.

## Market data
- Modes: DEMO (synthetic, the default), FYERS (live), Upstox (live).
- FYERS Standard plan data APIs allow 50 req/min and 5,000/day, and a day-long block follows 3 per-minute breaches.
  REST polling stays at 3 s. Live ticks come from the FYERS Data Socket (HSM binary protocol, ported from the
  fyers-apiv3 SDK).
- Upstox polls every 1 s. Its V3 feed is unit-tested only; don't test Upstox live unless asked.
- The emulator runs FYERS live; Upstox is not logged in there.

## UI rules
- The look follows Groww (the friend wants it "Groww-identical"). Never use Groww's name. The header logo is the user's
  `alpha_logo.png` (`res/drawable-nodpi`); the user chose it knowingly on 2026-10-29 although it resembles Groww's mark.
- Keep the DEMO banner in demo mode. Do not re-add the "PAPER" tag, and don't use the word "simulated" in user-facing text.
- The header row (`AppHeader`: logo, "Stocks", search, look-only swirl, avatar) came back on 2026-10-29 at the user's
  request.
- The friend's phone is 360dp wide at font scale 1.0. Verify layouts on the `Small_Phone` AVD (720x1280 @320).
  Watchlist rows stay on one line.
- Positions shows only today's book: expired contracts are hidden, closed positions show only on the day they were
  closed, and TOTAL P&L sums just the visible rows.

## Workflow
- Show emulator screenshots for UI changes and let the user verify them. Commit and push only when the user says so.
- Branches (main branch: `master`):
  - `version2`: backend and bug fixes (engine, accounting, market data, repositories). Every non-UI fix lands here first.
  - `grow`: the Groww-like UI. `Angel`: a new, different UI. Both branch from `version2` and hold only their own UI work.
  - Bring fixes into the UI branches by merging `version2` into them; never merge UI work back into `version2`.
  - When a fix is found while working on a UI branch, make it on `version2` and then merge it across.
- "Generate the apk" means: build the debug APK and copy it to `C:\Users\Arjit\Desktop\Alpha\alpha_vN.apk`, where N is
  one more than the highest version already in that folder. Never overwrite an existing APK.

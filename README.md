# Paper Trade India (Android, Kotlin, Compose)

Personal **paper-trading** app for Indian markets (equities, NIFTY/SENSEX futures and options) using real prices from a market-data provider and **virtual money only** (₹10,00,000 start). There is no code anywhere that places a real order, moves money, or talks to a brokerage trading endpoint. The only network use is read-only market data.

> **⚠ Read this first**
> 1. **Terms of use.** FYERS' API terms prohibit using its data for paper/virtual trading and charting tools without written consent (see `docs/PROVIDERS.md`). You chose to build for FYERS anyway; using it is at your own risk. The default mode is DEMO (synthetic data).
> 2. **Not built or run on a device yet.** See "Verification status".

## Verification status (honest)
| Part | Status |
|---|---|
| `:core` engine, accounting, order rules, market hours, formatting | **Compiled and 27 unit tests passed** (`./scripts_verify_core.sh`, uses the Kotlin compiler + JUnit bundled with Gradle; no network). |
| `:app` (Compose UI, Room, DataStore, FYERS/demo adapters) | **Written but never compiled.** The authoring sandbox had no Android SDK and could not reach Maven/Google repositories. Expect a few compile fixes on first build. |
| `app/src/test`, `app/src/androidTest` | Written, **never run**. |
| FYERS adapter | **Never called against the real API.** Endpoint paths, field names and symbol-master columns come from docs and must be verified (`docs/PROVIDERS.md`). |
| Debug APK | **Not produced.** |
| Live data | **Not verified.** Do not treat any price as real until you have tested it with your own FYERS credentials during market hours. |

## Build & install
1. Install Android Studio (Ladybug or newer) with JDK 17 and Android SDK 35. Open this folder.
2. Let Gradle sync (it generates the wrapper; or run `gradle wrapper --gradle-version 8.14.3`). Fix any compile errors reported on first build.
3. Enable USB debugging on your phone (Settings → About phone → tap Build number 7× → Developer options → USB debugging), connect it, choose it in Android Studio and press Run. Or run `./gradlew :app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk` with `adb install`.
4. Run tests: `./gradlew :core:test :app:testDebugUnitTest` and, with a device/emulator, `./gradlew :app:connectedDebugAndroidTest`.

## Using real prices (FYERS)
1. Open a FYERS account, create an API app at https://myapi.fyers.in/dashboard/ (note App ID, secret, redirect URI).
2. In the app: Account → Market data → "Setup (app owner, one time)": enter App ID, secret and the exact redirect URI. Only SHA-256(appId:secret) is stored. Or pre-fill them for an APK you build for someone else by adding `fyers.appId`, `fyers.secret`, `fyers.redirectUri` to `local.properties` (never committed; only the hash goes into the APK).
   Then tap **Log in to FYERS**, enter the FYERS PIN, and log in on the FYERS page inside the app. The app renews the daily access token by itself from a ~15-day refresh token; when that runs out a banner asks for one more login. `tools/fyers_token.py` still works for manual testing.
3. Switch the data mode to "FYERS (real)". Free Standard limits: 5 req/s, 50/min; the app polls every 3 s (WebSocket is not implemented).
4. Outside Mon–Fri 09:15–15:30 IST, prices are shown with their timestamp as "PREVIOUS / CACHED" and market orders are refused.

## Architecture
`core` (pure Kotlin/JVM): money types, `PaperExchange` engine, ledger, positions, charges, margin, market hours. `app`: MVVM — Compose screens → ViewModels → `TradingRepository` (Room transactions) / `QuoteHub` → `MarketDataProvider` interface (`DemoProvider`, `FyersProvider`). Manual DI in `AppContainer`. Rules: `docs/RULES.md`. Provider research, costs, limits: `docs/PROVIDERS.md`.

## Incomplete / external dependencies
- **Never compiled/run `:app`**, APK not built; instrumented tests not executed. UI tests cover navigation, chain, ticket and empty states only — order cancellation, position closure and persistence-across-restart are covered at the repository level (`TradingDatabaseTest`) but not by UI tests.
- **No WebSocket**: polling only. No tick de-dup beyond engine timestamp rules.
- **Provider**: FYERS adapter unverified live; BSE/SENSEX option chain support unknown; terms issue above; Kite/Angel/Groww adapters not written.
- Exchange **holidays** not detected (weekday holiday looks open). Quote timestamps are receipt time.
- **Small list sparklines** on Explore rows are not implemented (charts exist on index/stock screens only).
- Export/import covers wallet, orders, fills, positions, ledger, instruments — **not watchlists**.
- Open (unfilled) orders don't reserve cash/margin (re-checked at fill).
- Margin and charges are illustrative, not official. Equity short-selling/intraday is not simulated (delivery long-only). No order modification, no expiry/settlement of derivatives at expiry (expired contracts can't be traded; open positions are not auto-settled).
- Room schema v1 only; no migrations exist yet (policy and test scaffold documented in `AppDatabase`).
- Dependency versions in `gradle/libs.versions.toml` were not resolved here; bump as needed.

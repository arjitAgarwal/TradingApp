# Market-data provider evaluation (researched 17 Oct 2026)

Sources: each broker's public docs/support pages (links at the end). Prices and limits change; re-check before relying on them.

| | FYERS API v3 | Zerodha Kite Connect | Angel One SmartAPI | Groww Trade API |
|---|---|---|---|---|
| NIFTY / SENSEX index quotes | Yes (`NSE:NIFTY50-INDEX`, `BSE:SENSEX-INDEX`) | Paid plan only | Yes (per docs) | Yes (Live Data) |
| Equity quotes | Yes | Paid plan only | Yes | Yes |
| Real-time stream | WebSocket (binary protocol) | WebSocket (paid) | WebSocket V2 | Feed (NATS) |
| Futures chain | Yes (docs list a Futures Chain API) | Paid | Via instruments + quotes | Via contracts API |
| Option chain snapshot | Yes: OI, LTP, bid/ask, volume, optional Greeks, `strikecount` ≤ 50/side | Build from quotes | Greeks / PCR endpoints | Yes, with Greeks |
| Historical candles | Yes (Standard: daily 5y, intraday 1y) | Paid plan | Free | Yes |
| Cost (retail) | Free "Standard" plan (a paid "Prime" exists, price not listed in docs) | Personal plan free but **no market data**; Connect plan with data ₹500/month per support pages (older pages say ₹2000) | Free for Angel One clients | Market-data endpoints return 403 without a Live Data subscription (third-party wiki; verify) |
| Needs a broker account | Yes | Yes | Yes | Yes |
| Auth | OAuth-style auth code + app secret → daily token (expires ~06:30 IST) | Login flow + checksum with secret | Client code + PIN + TOTP | API key + TOTP/secret → daily token |

## Selected: FYERS (user decision), with a serious terms warning

The user chose to build against FYERS after being shown this warning. FYERS' *API Terms & Conditions* (https://fyers.in/terms-and-conditions-api), section 11, as summarised by our fetch of the page, say exchange data:
- may be used "only for legitimate, fair, and transparent trading";
- may **not** be used for developing charting/technical tools, **or for paper trading, gaming, and virtual trading**;
- section 12 requires FYERS' written consent for caching/databases/public display of API content.

This app is a paper-trading app with charts and a quote cache, so **using the FYERS adapter very likely breaches those terms**. Consequences are the user's to bear (API access can be revoked). Options: ask FYERS for written consent; switch provider after reading its terms; or use the DEMO provider. Terms for Kite, Angel One and Groww were **not** verified in full (Groww's general T&C mention only personal-use limits; Kite's terms page could not be fetched). The `MarketDataProvider` interface exists so switching is one new adapter class.

## FYERS facts used by the adapter
- Rate limits (Standard): Data REST 5/s, 50/min, 5,000/day. Exceeding the per-minute limit more than 3 times in a day blocks the account for the day. Our limiter caps at 4/s and 40/min.
- Data WebSocket: 100 symbols (Standard). **Not implemented**; we poll REST every 3 s and back off on errors.
- Quotes: 50 symbols per request (Standard).
- Token: access token expires daily ~06:00–06:30 IST (error codes -8, -15, -16, -17). Login also returns a ~15-day refresh token; `FyersAuth` swaps it + PIN at `POST /api/v3/validate-refresh-token` (grant_type, appIdHash, refresh_token, pin) for a new access token. Auth code exchange: `POST /api/v3/validate-authcode` (grant_type, appIdHash, code). Not yet verified live.

## Unverified assumptions (must be checked with a real account)
- Exact base URL `https://api-t1.fyers.in/data`, endpoint paths (`/quotes`, `/history`, `/options-chain-v3`) and quote field names (`lp`, `ch`, `chp`, `open_price`, `prev_close_price`, `bid`, `ask`, `volume`).
- Symbol-master CSV column positions (lot size col 3, tick 4, expiry 8, ticker 9, underlying 13, strike 15, option type 16). Rows failing validation are dropped, so a contract with unknown lot size is never tradable.
- Whether SENSEX (BSE) option chains are served by the option-chain API.
- FYERS quotes have no reliable per-tick exchange timestamp in our parsing, so `timestamp` = time the app received it. Quotes received outside Mon–Fri 09:15–15:30 IST are tagged CACHED. Exchange holidays are not detected.

Links: https://fyers.in/api-docs/v3/getting-started/plans-and-limits/ · https://fyers.in/api-docs/v3/api/market-data/option-chain/ · https://fyers.in/terms-and-conditions-api · https://support.zerodha.com/category/trading-and-markets/general-kite/kite-api/articles/what-are-the-charges-for-kite-apis · https://groww.in/trade-api/docs · https://www.angelone.in/news/product-updates/angel-one-smartapi-the-secret-to-easy-automated-algo-trading

# Paper-trading rules (implemented in `core/`, covered by unit tests)

**Money:** all arithmetic is `BigDecimal`; cash amounts rounded HALF_UP to 2 dp, prices kept to 6 dp. Never use Kotlin `/` on BigDecimal (it truncates) — use `Dec.div`.

**Units:** equity quantity = shares; futures/options quantity = lots, `multiplier` = lot size from provider metadata (never hardcoded). P&L = Δprice × qty × multiplier.

**Execution reference price:** BUY → ask, SELL → bid. If missing, LTP ± configurable slippage (bps), rounded against the trader to the tick (labelled "LTP model": LTP is not guaranteed executable).

**Market orders:** rejected if no quote, quote older than max age (default 15 s), source CACHED, or invalid. Quotes received outside market hours are CACHED, so no market fills when the market is closed.

**Limit orders:** fill when BUY ref ≤ limit / SELL ref ≥ limit, at the reference price (so gaps give price improvement, never worse than limit). Marketable limits fill at placement. Each order evaluates a given quote timestamp once; unchanged/older ticks and stale/cached quotes never fill, so a UI refresh cannot fill an order. Open orders survive market close.

**Partial fills (optional):** cap per evaluation; remainder keeps working on later fresh quotes.

**State machine:** CREATED→OPEN|REJECTED|CANCELLED; OPEN→PARTIALLY_FILLED|FILLED|CANCELLED|REJECTED; PARTIALLY_FILLED→PARTIALLY_FILLED|FILLED|CANCELLED. Terminal states are final. Fill ids are `<orderId>-F<n>` and are primary keys, so a fill applies exactly once.

**Accounting (ledger):** `cash = starting capital + Σ ledger`. Equity & options: cash moves by trade value (short option credits premium). Futures: no cash on entry; realised P&L settles to cash on reduce/close. Charges debit cash. `equity = cash + Σ(equity/options: signedQty×lot×LTP; futures: unrealised)`, and `net P&L = realised + unrealised − charges` (tested).

**Positions:** average-cost; increase re-averages; reduce realises vs average; reversal closes the old side at average and opens the remainder at the fill price. Delivery equity is long-only and shown in Holdings; futures/options show in Positions. Each exposure lives in exactly one position row, so it is never double-counted. The Positions screen shows today's book: a row's P&L (and TOTAL P&L) leaves out realised P&L and charges from fills before today (IST), so re-entering a contract closed on an earlier day starts from zero realised. An open position carried overnight still shows unrealised P&L against its average price.

**Margin (simulated, NOT broker margin):** futures 12% of notional; short options 15% of *strike* notional (uncovered short loss is not capped at premium). Orders that would leave available cash < 0 are rejected, except risk-reducing fills. Open (unfilled) orders do not reserve margin; the check repeats at fill time.

**Charges:** illustrative brokerage/STT/exchange/SEBI/stamp/GST rates in `Config.kt` — assumptions, not official; toggle in Settings.

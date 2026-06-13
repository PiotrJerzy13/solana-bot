# CLAUDE.md
This file provides guidance to Claude Code when working with code in this repository.

## Commands
```bash
# Build
mvn install

# Run modes
mvn spring-boot:run                                          # default: REST poll + paper trading
mvn spring-boot:run -Dspring-boot.run.arguments=trending    # print trending tokens and exit
mvn spring-boot:run -Dspring-boot.run.arguments="search JUP" # search a token by name/symbol
mvn spring-boot:run -Dspring-boot.run.arguments=status      # print wallet P&L summary and exit

# Environment variables (never commit real keys)
JUPITER_API_KEY=your_key          # authenticated Jupiter API (higher rate limits)
SOLANA_WS_URL=wss://your-rpc      # WebSocket event stream (Phase A) — Helius recommended
SOLANA_HTTP_URL=https://your-rpc  # HTTP RPC for getTransaction — use api.mainnet-beta.solana.com
                                  # optional: derived from SOLANA_WS_URL if not set

# Full run with all env vars
SOLANA_WS_URL=wss://mainnet.helius-rpc.com/?api-key=d772d59b-ab06-447d-bf0a-b3fcc1e86934 \  
SOLANA_HTTP_URL=https://mainnet.helius-rpc.com/?api-key=d772d59b-ab06-447d-bf0a-b3fcc1e86934 \
mvn spring-boot:run

# Tests
mvn test
mvn test -Dtest=SolanaBotApplicationTests
```
Requires Java 17. If not active: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`

## Architecture

Spring Boot is used only for build tooling and test support — `SolanaBotApplication` has a plain
`main()` that wires everything manually. No DI, no web layer.

### Data flow

```
                    ┌──────────────────────────────────────────────────┐
                    │  Detection layer                                  │
                    │                                                   │
  JupiterClient ───►  TokenPoller (REST, 60s poll)  ─┐                │
                    │                                  ├──► evaluateToken()
  SolanaWebSocket ─►  LogParser → resolveAccounts()  ─┘                │
    (Phase A)       │  → TokenResolver                                  │
                    └──────────────────────┬───────────────────────────┘
                                           │
                                           ▼
                                     TokenScorer
                                     (0–100 score)
                                           │
                                           ▼
                                     PaperWallet
                                    (virtual trades)
                                           │
                                     Phase 5 ▼
                                     LiveWallet
                                  (Jupiter Swap API)
```

### Class reference

**`JupiterClient`** — wraps Jupiter Token API V2 over `java.net.http.HttpClient`. Switches between
`lite-api.jup.ag` (free) and `api.jup.ag` (keyed) based on `JUPITER_API_KEY`. Key methods:
`getRecentTokens()`, `searchTokens(query)`, `getTrendingTokens(interval)`.

**`JupiterToken`** — Gson-mapped POJO. Nested `Audit` (mint/freeze authority, `topHoldersPercentage`)
and `FirstPool` (pool `createdAt`). `isSafeToTrade()` is the hard gate that must pass before scoring.
`getCircToTotalRatio()`, `isMintSafe()`, `hasVerifiedTag()` are key helpers.

**`TokenPoller`** — polls `/recent` every 60 s, diffs against `seenTokenIds`
(`ConcurrentHashMap.newKeySet()` — thread safe). Per new token: calls `isSafeToTrade()` first
(hard gate), then `TokenScorer`, then `PaperWallet.buy()` if score ≥ 70. `checkForNewTokens()`
also calls `paperWallet.checkExits(current)` every cycle. `handlePoolEvent(PoolEvent)` is the
WebSocket entry point — dedupes against `seenTokenIds`, calls `TokenResolver`, then `evaluateToken()`.
`printWalletStatus()` serves the `status` run mode.

**`TokenScorer`** — 0–100 score from nine weighted factors (constants must sum to 100):
`W_ORGANIC(25)`, `W_TOP_HOLDERS(20)`, `W_LIQUIDITY(15)`, `W_HOLDERS(15)`, `W_POOL_AGE(10)`,
`W_CIRC_RATIO(5)`, `W_VERIFIED(5)`, `W_MINT_SAFE(3)`, `W_FREEZE_SAFE(2)`.
Pool age uses a trapezoid curve (ramp 0–30 min, plateau 30–120 min, decay to 0 at 24 h).
Liquidity and holders use log scaling. Returns `ScoreResult` record with `print()` bar-chart,
`isBuyCandidate()` (≥ 70), `label()`.

**`PaperWallet`** — fully implemented virtual wallet. `$100` starting balance, `$10` per trade,
max 5 open positions. `buy(token, score)` and `checkExits(List<JupiterToken>)` are synchronized.
Closes positions on TP (+50%), SL (−30%), or timeout (24 h). `printSummary()` shows win rate,
total P&L, avg P&L, exit reason breakdown, trade history. Inner types: `Position` record,
`ClosedTrade` record, `ExitReason` enum (TAKE_PROFIT, STOP_LOSS, TIMEOUT, MANUAL).

**`PoolEvent`** — record: `signature`, `poolAddress`, `baseMint`, `quoteMint`,
`ProgramSource` (RAYDIUM | PUMP_FUN), `detectedAt`. `isQuoteSol()` checks
`quoteMint == SOL_MINT`. `SOL_MINT` constant = wrapped SOL address.

**`LogParser`** — stateless, thread-safe. Parses raw `logsNotification` JSON →
`Optional<Notification(signature, source)>`. Skips failed txs (err != null) and
non-matching programs. Pump.fun trigger: `"Program log: Instruction: CreateV2"`
✓ verified live mainnet 2026-06-03. Raydium trigger: `"initialize2"` — unverified.

**`SolanaWebSocketClient`** — subscribes to Raydium AMM and Pump.fun program logs via
`logsSubscribe` (`commitment:processed`). Buffers partial `onText` frames until `last=true`.
On LogParser HIT: schedules `resolveAccounts()` via `ScheduledExecutorService` (20 threads)
with 32s delay, then fires `Consumer<PoolEvent>` callback. Reconnects with exponential
back-off 1s→30s. Uses `SOLANA_HTTP_URL` env var for `getTransaction` HTTP calls
(`commitment:finalized`), derived from `SOLANA_WS_URL` if not set. Uses
`sendAsync().get(15s)` for hard timeout enforcement.
✓ Pump.fun baseMint: instruction accounts[0] → accountKeys index verified live 2026-06-07.
⚠ Pump.fun poolAddress index [2] — unverified.
⚠ Raydium account indices [3]=pool, [7]=coinMint, [8]=pcMint — unverified.
⚠ v0 transactions with address lookup tables not handled in resolveAccounts().

**`TokenResolver`** — resolves `PoolEvent.baseMint()` → `JupiterToken` via
`jupiterClient.searchTokens()`. Exact-match on `id == baseMint` only. Retries 3× with 2s
delay. By the time this is called (~33s after detection), Jupiter has typically already
indexed the token so retries rarely fire.

## Phased roadmap

| Phase | Status      | Description                                                        |
|-------|-------------|--------------------------------------------------------------------|
| 2     | ✅ Done     | Safety filters in `TokenPoller`                                    |
| 3     | ✅ Done     | `TokenScorer` weighted scoring                                     |
| 4     | ✅ Done     | `PaperWallet` paper trading — wired into `TokenPoller`             |
| A     | ✅ Done     | WebSocket detection via Solana RPC `logsSubscribe`                 |
|       |             | Pump.fun CreateV2 trigger ✓ verified. baseMint index ✓ verified.   |
|       |             | Raydium trigger + poolAddress + all Raydium indices unverified.    |
| 5     | 🔨 Next     | Real Jupiter swap execution (Jupiter Swap API)                     |
| 6     | Planned     | Monitoring, persistence, Telegram alerts                           |

## Tunable constants

All constants are at the top of their respective class with comments:

- **Safety thresholds** (`TokenPoller`): `MIN_MCAP`, `MIN_LIQUIDITY`, `MIN_HOLDERS`,
  `MIN_CIRC_RATIO`, `POLL_INTERVAL`
- **Scoring weights** (`TokenScorer`): nine `W_*` constants (must sum to 100), plus
  `LIQ_FLOOR/TARGET`, `HOLDERS_FLOOR/TARGET`, `AGE_*` window constants,
  `THRESHOLD_BUY` (70), `THRESHOLD_WATCH` (50)
- **Trade sizing and exits** (`PaperWallet`): `STARTING_BALANCE_USD` ($100),
  `TRADE_SIZE_USD` ($10), `MAX_OPEN_POSITIONS` (5), `TAKE_PROFIT_PCT` (0.5),
  `STOP_LOSS_PCT` (−0.3), `TIMEOUT_HOURS` (24)

## Important constraints

- **No Solana SDK** — all RPC calls are raw JSON-RPC over `java.net.http`. No web3j-solana,
  no sol4j.
- **Thread safety** — resolved: `seenTokenIds` is `ConcurrentHashMap.newKeySet()`;
  `PaperWallet.buy()` and `checkExits()` are synchronized.
- **Never commit secrets** — `JUPITER_API_KEY`, `SOLANA_WS_URL`, `SOLANA_HTTP_URL` come
  from environment variables only. No `.properties` files with real keys.
- **Raydium unverified** — Raydium trigger phrase and all account indices are approximate.
  Do not enable live trading for Raydium tokens until verified against real mainnet events.
- **v0 transactions** — `resolveAccounts()` only reads `accountKeys`, not `loadedAddresses`
  (address lookup tables). Transactions using ALTs will produce wrong account indices.
  Verify before enabling live trading.
- **Phase 5 prerequisite** — run paper trading for at least 48h and confirm win rate > 50%
  before enabling real swap execution.
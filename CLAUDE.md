# CLAUDE.md
This file provides guidance to Claude Code when working with code in this repository.

## Commands
```bash
# Build
mvn install

# Run modes
mvn spring-boot:run                                                  # default: REST poll + paper trading
mvn spring-boot:run -Dspring-boot.run.arguments=trending            # print trending tokens and exit
mvn spring-boot:run -Dspring-boot.run.arguments="search JUP"        # search a token by name/symbol
mvn spring-boot:run -Dspring-boot.run.arguments=status              # print wallet P&L summary and exit

# Environment variables
JUPITER_API_KEY=your_key mvn spring-boot:run                        # use authenticated Jupiter API
SOLANA_WS_URL=wss://your-rpc mvn spring-boot:run                    # enable WebSocket detection (Phase A)

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
                    ┌─────────────────────────────────────────────────┐
                    │  Detection layer                                 │
                    │                                                  │
  JupiterClient ───►  TokenPoller (REST, 60s poll)  ─┐               │
                    │                                  ├──► evaluateToken()
  SolanaWebSocket ─►  LogParser + TokenResolver      ─┘               │
    (Phase A)       │                                                  │
                    └──────────────────────┬──────────────────────────┘
                                           │
                                           ▼
                                     TokenScorer
                                     (0–100 score)
                                           │
                                           ▼
                                     PaperWallet
                                    (virtual trades)
```

### Class reference

**`JupiterClient`** — wraps Jupiter Token API V2 over `java.net.http.HttpClient`. Switches between
`lite-api.jup.ag` (free) and `api.jup.ag` (keyed) based on `JUPITER_API_KEY`. Key methods:
`getRecentTokens()`, `searchTokens(query)`, `getTrendingTokens(interval)`.

**`JupiterToken`** — Gson-mapped POJO. Nested `Audit` (mint/freeze authority, `topHoldersPercentage`)
and `FirstPool` (pool `createdAt`). `isSafeToTrade()` is the hard gate that must pass before scoring.
`getCircToTotalRatio()`, `isMintSafe()`, `hasVerifiedTag()` are key helpers.

**`TokenPoller`** — polls `/recent` every 60 s, diffs against `seenTokenIds` HashSet. Per new token:
calls `isSafeToTrade()` first (hard gate), then `TokenScorer`, then `PaperWallet.buy()` if score ≥ 70.
`checkForNewTokens()` also calls `paperWallet.checkExits(current)` every cycle so exit rules run even
when no new tokens appear. Has a `printWalletStatus()` method for the `status` run mode.

**`TokenScorer`** — 0–100 score from nine weighted factors (constants must sum to 100):
`W_ORGANIC(25)`, `W_TOP_HOLDERS(20)`, `W_LIQUIDITY(15)`, `W_HOLDERS(15)`, `W_POOL_AGE(10)`,
`W_CIRC_RATIO(5)`, `W_VERIFIED(5)`, `W_MINT_SAFE(3)`, `W_FREEZE_SAFE(2)`.
Pool age uses a trapezoid curve (ramp 0–30 min, plateau 30–120 min, decay to 0 at 24 h).
Liquidity and holders use log scaling. Returns `ScoreResult` record with `print()` bar-chart and
`isBuyCandidate()` (≥ 70), `label()`.

**`PaperWallet`** — fully implemented virtual wallet. `$100` starting balance, `$10` per trade,
max 5 open positions. `buy(token, score)` opens a position; `checkExits(List<JupiterToken>)` checks
every open position against current prices for TP (+50%), SL (−30%), or timeout (24 h).
`printSummary()` shows win rate, total P&L, avg P&L, exit reason breakdown, trade history.
Inner types: `Position` record, `ClosedTrade` record, `ExitReason` enum (TAKE_PROFIT, STOP_LOSS,
TIMEOUT, MANUAL).

### Phase A classes (in progress — see playbook)

**`SolanaWebSocketClient`** *(building)* — connects to Solana RPC WebSocket (`SOLANA_WS_URL`),
subscribes to Raydium AMM (`675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8`) and Pump.fun
(`6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P`) program logs via `logsSubscribe`. Reconnects
with exponential back-off (1s → 30s cap). Buffers partial `onText` messages until `last=true`.

**`LogParser`** *(building)* — takes a raw `logsSubscribe` JSON string and returns
`Optional<PoolEvent>`. Raydium trigger: logs contain `initialize2`. Pump.fun trigger: logs contain
`Program log: Instruction: Create`. Extracts signature and account indices from the message.
⚠ Account indices in resolveAccounts() — Raydium: [3]=pool, [7]=coinMint, [8]=pcMint;
  Pump.fun: [0]=mint, [2]=bondingCurve — UNVERIFIED, must confirm against live mainnet
  getTransaction responses before enabling real trading.

**`PoolEvent`** *(building)* — record: `signature`, `poolAddress`, `baseMint`, `quoteMint`,
`ProgramSource` (RAYDIUM | PUMP_FUN), `detectedAt`. `isQuoteSol()` helper checks native SOL mint.

**`TokenResolver`** *(building)* — resolves a `PoolEvent.baseMint()` to a `JupiterToken` via
`jupiterClient.searchTokens()`. Retries up to 3× with 2s sleep (Jupiter indexes new tokens slowly).
Matches on `id == baseMint` exactly to avoid fuzzy-match false positives.

## Phased roadmap

| Phase | Status | Description |
|-------|--------|-------------|
| 2 | ✅ Done | Safety filters in `TokenPoller` |
| 3 | ✅ Done | `TokenScorer` weighted scoring |
| 4 | ✅ Done | `PaperWallet` paper trading — wired into `TokenPoller` |
| A | ✅ Building | WebSocket detection via Solana RPC `logsSubscribe` |
| 5 | Planned | Real Jupiter swap execution (Jupiter Swap API) |
| 6 | Planned | Monitoring, persistence, Telegram alerts |

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
- **Thread safety**: resolved — seenTokenIds is ConcurrentHashMap.newKeySet(),
PaperWallet.buy() and checkExits() are synchronized.
- **Never commit secrets** — `JUPITER_API_KEY` and `SOLANA_WS_URL` must come from environment
  variables only. No `.properties` files with real keys.
- **Account indices are unverified** — `LogParser` account index positions for Raydium pool
  accounts are approximate. Do not enable live trading until they are confirmed against real mainnet
  pool creation transactions.

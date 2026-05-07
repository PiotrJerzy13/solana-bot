# Solana Trading Bot

A Java-based Solana trading bot that monitors new token listings, tracks price feeds via Pyth, and evaluates tokens using safety filters before trading.

---

## Tech Stack

- **Java 17**
- **Spring Boot 4.0.6**
- **solanaj** — Solana RPC client
- **Jupiter Token API V2** — token data and new listings
- **Pyth** — real-time on-chain price feeds
- **Gson** — JSON parsing

---

## Project Structure

```
src/main/java/com/solanabot/solana_bot/
├── SolanaBotApplication.java   # entry point, run modes
├── JupiterClient.java          # Jupiter API HTTP client
├── JupiterToken.java           # token data model + safety helpers
└── TokenPoller.java            # polls for new listings, applies filters
```

---

## Setup

**1. Clone the repo**
```bash
git clone https://github.com/YOUR_USERNAME/solana-bot.git
cd solana-bot
```

**2. Make sure you are using Java 17**
```bash
java -version
```
If not, set it:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

**3. Install dependencies**
```bash
mvn install
```

---

## Running the Bot

**Default mode — poll for new token listings:**
```bash
mvn spring-boot:run
```

**See trending tokens and exit:**
```bash
mvn spring-boot:run -Dspring-boot.run.arguments=trending
```

**Search for a specific token:**
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="search JUP"
```

---

## Optional — Jupiter API Key

Without an API key the bot uses the free lite API (rate limited).
To use the authenticated API, set the environment variable before running:

```bash
export JUPITER_API_KEY=your_key_here
mvn spring-boot:run
```

Get a key at [jup.ag](https://jup.ag).

---

## Safety Filters

When a new token is detected, `TokenPoller` runs it through these checks before flagging it as worth investigating:

| Check | Threshold |
|---|---|
| Market cap | > $10,000 |
| Liquidity | > $5,000 |
| Holder count | > 50 wallets |
| Mint authority | Must be disabled |
| Circulating supply | > 10% of total supply |

These thresholds can be adjusted in `TokenPoller.java`.

---

## Roadmap

- [x] Jupiter token poller with safety filters
- [x] Pyth price feed integration
- [ ] Paper trading engine (Phase 3)
- [ ] Jupiter swap execution (Phase 4)
- [ ] Telegram notifications
- [ ] Config file for thresholds and strategy parameters

---

## Important

This bot is built for **learning and experimentation on Solana devnet**.
Never store private keys in code or commit them to git.
Always test on devnet before considering mainnet usage.

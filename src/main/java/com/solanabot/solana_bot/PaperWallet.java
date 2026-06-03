package com.solanabot.solana_bot;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Simulates a trading wallet without spending real money.
 *
 * Tracks a virtual USD balance, open positions, and closed trades.
 * All prices come from Jupiter's USD feed, so no SOL/USD oracle needed.
 * In Phase 5 this maps 1:1 to real execution — just swap the buy/sell
 * stubs for Jupiter Swap API calls.
 *
 * Exit rules (configurable at top):
 *   • Take-profit  — close when price rises  ≥ TAKE_PROFIT_PCT
 *   • Stop-loss    — close when price falls  ≤ STOP_LOSS_PCT
 *   • Timeout      — force-close after TIMEOUT_HOURS regardless of price
 */
public class PaperWallet {

    // ── Configuration ─────────────────────────────────────────────────────────
    private static final double STARTING_BALANCE_USD = 100.0;  // virtual starting budget
    private static final double TRADE_SIZE_USD        =  10.0;  // spend per trade
    private static final int    MAX_OPEN_POSITIONS    =   5;    // max concurrent trades
    private static final double TAKE_PROFIT_PCT       =  0.50;  // close at +50%
    private static final double STOP_LOSS_PCT         = -0.30;  // close at -30%
    private static final long   TIMEOUT_HOURS         =  24;    // force-close after 24 h
    // ─────────────────────────────────────────────────────────────────────────

    private double balance;
    private final Map<String, Position>  openPositions = new LinkedHashMap<>();
    private final List<ClosedTrade>      closedTrades  = new ArrayList<>();

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public PaperWallet() {
        this.balance = STARTING_BALANCE_USD;
        System.out.printf("💼 PaperWallet started | Balance: $%.2f | Max positions: %d%n%n",
                balance, MAX_OPEN_POSITIONS);
    }

    // ── Buy ───────────────────────────────────────────────────────────────────

    /**
     * Opens a virtual position in the given token.
     * Guards: enough balance, no duplicate position, under max positions.
     */
    public synchronized boolean buy(JupiterToken token, TokenScorer.ScoreResult score) {
        String id = token.getId();

        if (openPositions.containsKey(id)) {
            System.out.println("  ⏭  Already holding " + token.getSymbol() + " — skipping");
            return false;
        }
        if (openPositions.size() >= MAX_OPEN_POSITIONS) {
            System.out.println("  ⏭  Max positions reached (" + MAX_OPEN_POSITIONS + ") — skipping");
            return false;
        }
        if (balance < TRADE_SIZE_USD) {
            System.out.println("  ⏭  Insufficient balance ($" + String.format("%.2f", balance) + ") — skipping");
            return false;
        }

        double entryPrice   = token.getUsdPrice();
        double tokensHeld   = TRADE_SIZE_USD / entryPrice;
        double takeProfit   = entryPrice * (1 + TAKE_PROFIT_PCT);
        double stopLoss     = entryPrice * (1 + STOP_LOSS_PCT);

        Position pos = new Position(
                id, token.getSymbol(), token.getName(),
                entryPrice, tokensHeld, TRADE_SIZE_USD,
                takeProfit, stopLoss,
                Instant.now(), score.total()
        );

        openPositions.put(id, pos);
        balance -= TRADE_SIZE_USD;

        System.out.printf(
                "  🟢 BUY  %-10s | $%.8f × %,.0f tokens = $%.2f | TP: $%.8f  SL: $%.8f%n",
                token.getSymbol(), entryPrice, tokensHeld, TRADE_SIZE_USD, takeProfit, stopLoss
        );
        return true;
    }

    // ── Exit checks ───────────────────────────────────────────────────────────

    /**
     * Scans a fresh batch of tokens from Jupiter for any that we hold.
     * For each held token, checks TP / SL / timeout and closes if triggered.
     *
     * Call this every poll cycle from TokenPoller, passing the same list
     * you use for new-token detection.
     */
    public synchronized void checkExits(List<JupiterToken> currentTokens) {
        if (openPositions.isEmpty()) return;

        // build a quick lookup: tokenId → latest price
        Map<String, Double> priceMap = new HashMap<>();
        for (JupiterToken t : currentTokens) {
            if (t.getId() != null) priceMap.put(t.getId(), t.getUsdPrice());
        }

        // iterate over a copy so we can remove while iterating
        for (String id : new ArrayList<>(openPositions.keySet())) {
            Position pos = openPositions.get(id);

            Double currentPrice = priceMap.get(id);
            if (currentPrice == null || currentPrice <= 0) {
                checkTimeout(pos); // price disappeared — still check age
                continue;
            }

            double pnlPct = pos.pnlPct(currentPrice);

            if (currentPrice >= pos.takeProfitPrice()) {
                close(pos, currentPrice, ExitReason.TAKE_PROFIT);
            } else if (currentPrice <= pos.stopLossPrice()) {
                close(pos, currentPrice, ExitReason.STOP_LOSS);
            } else if (pos.isTimedOut(TIMEOUT_HOURS)) {
                close(pos, currentPrice, ExitReason.TIMEOUT);
            } else {
                // still open — print live P&L
                System.out.printf(
                        "  📊 HOLD %-10s | Current: $%.8f | P&L: %+.1f%%%n",
                        pos.symbol(), currentPrice, pnlPct * 100
                );
            }
        }
    }

    // ── Manual close ──────────────────────────────────────────────────────────

    public void closeManual(String tokenId, double currentPrice) {
        Position pos = openPositions.get(tokenId);
        if (pos == null) {
            System.out.println("No open position for " + tokenId);
            return;
        }
        close(pos, currentPrice, ExitReason.MANUAL);
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    public void printOpenPositions() {
        if (openPositions.isEmpty()) {
            System.out.println("  (no open positions)");
            return;
        }
        System.out.println("\n  ┌── Open positions ──────────────────────────────────────────");
        for (Position pos : openPositions.values()) {
            System.out.printf("  │  %-10s | Entry: $%.8f | Invested: $%.2f | Age: %s | Score: %d%n",
                    pos.symbol(), pos.entryPriceUsd(), pos.usdInvested(),
                    formatAge(pos.entryTime()), pos.scoreAtEntry());
        }
        System.out.printf("  └── Balance available: $%.2f%n", balance);
    }

    public void printSummary() {
        System.out.println("\n  ╔══ PaperWallet Summary ════════════════════════════════════");
        System.out.printf ("  ║  Balance:         $%.2f (started $%.2f)%n",
                balance + unrealisedValue(), STARTING_BALANCE_USD);
        System.out.printf ("  ║  Available cash:  $%.2f%n", balance);
        System.out.printf ("  ║  Open positions:  %d  (unrealised: ~$%.2f)%n",
                openPositions.size(), unrealisedValue());
        System.out.printf ("  ║  Closed trades:   %d%n", closedTrades.size());

        if (!closedTrades.isEmpty()) {
            long wins    = closedTrades.stream().filter(t -> t.pnlUsd() > 0).count();
            long losses  = closedTrades.stream().filter(t -> t.pnlUsd() < 0).count();
            double totalPnl = closedTrades.stream().mapToDouble(ClosedTrade::pnlUsd).sum();
            double avgPnlPct = closedTrades.stream().mapToDouble(t -> t.pnlPct() * 100).average().orElse(0);
            OptionalDouble best  = closedTrades.stream().mapToDouble(t -> t.pnlPct() * 100).max();
            OptionalDouble worst = closedTrades.stream().mapToDouble(t -> t.pnlPct() * 100).min();

            long tpCount  = closedTrades.stream().filter(t -> t.reason() == ExitReason.TAKE_PROFIT).count();
            long slCount  = closedTrades.stream().filter(t -> t.reason() == ExitReason.STOP_LOSS).count();
            long toCount  = closedTrades.stream().filter(t -> t.reason() == ExitReason.TIMEOUT).count();

            System.out.printf ("  ║  Win / Loss:      %d W  /  %d L  (%.0f%% win rate)%n",
                    wins, losses, wins * 100.0 / closedTrades.size());
            System.out.printf ("  ║  Total P&L:       %+$.2f%n", totalPnl);
            System.out.printf ("  ║  Avg P&L:         %+.1f%%%n", avgPnlPct);
            System.out.printf ("  ║  Best trade:      %+.1f%%%n", best.orElse(0));
            System.out.printf ("  ║  Worst trade:     %+.1f%%%n", worst.orElse(0));
            System.out.printf ("  ║  Exit reasons:    TP=%d  SL=%d  Timeout=%d%n",
                    tpCount, slCount, toCount);

            System.out.println("  ╠══ Closed trades ══════════════════════════════════════════");
            for (ClosedTrade t : closedTrades) {
                System.out.printf(
                        "  ║  %-10s  %s → %s  %+.1f%%  %s%n",
                        t.position().symbol(),
                        FMT.format(t.position().entryTime()),
                        FMT.format(t.exitTime()),
                        t.pnlPct() * 100,
                        t.reason().label()
                );
            }
        }
        System.out.println("  ╚═══════════════════════════════════════════════════════════");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void close(Position pos, double exitPrice, ExitReason reason) {
        openPositions.remove(pos.tokenId());
        ClosedTrade trade = new ClosedTrade(pos, exitPrice, Instant.now(), reason);
        closedTrades.add(trade);

        double proceeds = pos.tokensHeld() * exitPrice;
        balance += proceeds;

        System.out.printf(
                "  %s %-10s | Exit: $%.8f | P&L: %+.1f%% (%+$.2f) | %s%n",
                reason.icon(), pos.symbol(),
                exitPrice, trade.pnlPct() * 100, trade.pnlUsd(),
                reason.label()
        );
    }

    private void checkTimeout(Position pos) {
        if (pos.isTimedOut(TIMEOUT_HOURS)) {
            // price no longer on Jupiter — exit at zero to be conservative
            close(pos, 0.0, ExitReason.TIMEOUT);
        }
    }

    private double unrealisedValue() {
        // without current prices we can only report cost basis
        return openPositions.values().stream()
                .mapToDouble(Position::usdInvested)
                .sum();
    }

    private String formatAge(Instant t) {
        long mins = Duration.between(t, Instant.now()).toMinutes();
        if (mins < 60)  return mins + "m";
        if (mins < 1440) return (mins / 60) + "h " + (mins % 60) + "m";
        return (mins / 1440) + "d";
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    public record Position(
            String  tokenId,
            String  symbol,
            String  name,
            double  entryPriceUsd,
            double  tokensHeld,
            double  usdInvested,
            double  takeProfitPrice,
            double  stopLossPrice,
            Instant entryTime,
            int     scoreAtEntry
    ) {
        public double pnlPct(double currentPrice) {
            return (currentPrice - entryPriceUsd) / entryPriceUsd;
        }

        public double pnlUsd(double currentPrice) {
            return (tokensHeld * currentPrice) - usdInvested;
        }

        public boolean isTimedOut(long maxHours) {
            return Duration.between(entryTime, Instant.now()).toHours() >= maxHours;
        }
    }

    public record ClosedTrade(
            Position   position,
            double     exitPriceUsd,
            Instant    exitTime,
            ExitReason reason
    ) {
        public double pnlUsd() {
            return (position.tokensHeld() * exitPriceUsd) - position.usdInvested();
        }

        public double pnlPct() {
            if (position.entryPriceUsd() == 0) return -1.0;
            return (exitPriceUsd - position.entryPriceUsd()) / position.entryPriceUsd();
        }
    }

    public enum ExitReason {
        TAKE_PROFIT("🟩 Take-profit", "✅"),
        STOP_LOSS  ("🟥 Stop-loss",   "🛑"),
        TIMEOUT    ("⬛ Timeout",      "⏰"),
        MANUAL     ("🔲 Manual",       "🤚");

        private final String label;
        private final String icon;

        ExitReason(String label, String icon) {
            this.label = label;
            this.icon  = icon;
        }

        public String label() { return label; }
        public String icon()  { return icon;  }
    }
}

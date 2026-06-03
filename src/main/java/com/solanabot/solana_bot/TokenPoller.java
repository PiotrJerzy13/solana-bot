package com.solanabot.solana_bot;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls Jupiter for newly listed tokens and handles WebSocket pool events.
 *
 * evaluateToken() is the single evaluation path for both detection sources:
 *   • REST poll  — checkForNewTokens() called every 60 s
 *   • WebSocket  — handlePoolEvent() called by SolanaWebSocketClient callback
 *
 * seenTokenIds uses ConcurrentHashMap.newKeySet() so both threads can safely
 * add/query without external synchronization.
 */
public class TokenPoller {

    // ── Safety filter thresholds — tune to your risk tolerance ───────────────
    private static final double MIN_MCAP       = 10_000;
    private static final double MIN_LIQUIDITY  = 5_000;
    private static final int    MIN_HOLDERS    = 50;
    private static final double MIN_CIRC_RATIO = 0.1;
    private static final int    POLL_INTERVAL  = 60_000;   // ms

    private final JupiterClient jupiterClient;
    private final PaperWallet   paperWallet;
    private final TokenScorer   scorer         = new TokenScorer();
    private final TokenResolver tokenResolver;

    // ConcurrentHashMap-backed set: add() is atomic, safe from REST + WS threads
    private final Set<String> seenTokenIds = ConcurrentHashMap.newKeySet();

    public TokenPoller(JupiterClient jupiterClient, PaperWallet paperWallet) {
        this.jupiterClient  = jupiterClient;
        this.paperWallet    = paperWallet;
        this.tokenResolver  = new TokenResolver(jupiterClient);
    }

    // ── REST polling loop ─────────────────────────────────────────────────────

    public void start() throws InterruptedException {
        System.out.println("═══════════════════════════════════════");
        System.out.println("  Solana Token Poller — Starting up");
        System.out.println("═══════════════════════════════════════");

        List<JupiterToken> initial = jupiterClient.getRecentTokens();
        initial.forEach(t -> seenTokenIds.add(t.getId()));
        System.out.println("✓ Loaded " + seenTokenIds.size() + " existing recent tokens");
        System.out.println("✓ Watching for new listings every " + (POLL_INTERVAL / 1000) + "s...\n");

        while (true) {
            Thread.sleep(POLL_INTERVAL);
            checkForNewTokens();
        }
    }

    private void checkForNewTokens() {
        List<JupiterToken> current = jupiterClient.getRecentTokens();

        if (current.isEmpty()) {
            System.out.println("⚠️  Empty response from Jupiter — will retry next cycle");
            return;
        }

        List<JupiterToken> newTokens = current.stream()
                .filter(t -> t.getId() != null && seenTokenIds.add(t.getId()))
                .toList();

        if (newTokens.isEmpty()) {
            System.out.println("○ No new tokens since last check");
        } else {
            System.out.println("──────────────────────────────────────");
            System.out.println("🆕 " + newTokens.size() + " new token(s) detected!");
            System.out.println("──────────────────────────────────────");

            for (JupiterToken token : newTokens) {
                printTokenDetails(token);
                evaluateToken(token);
                System.out.println();
            }
        }

        // Run exit checks every cycle so TP/SL/timeout fire even when no new tokens appear
        paperWallet.checkExits(current);
    }

    // ── WebSocket detection path ──────────────────────────────────────────────

    /**
     * Called by SolanaWebSocketClient on the resolver thread pool when a new
     * Raydium or Pump.fun pool creation is detected.
     * Dedupes, resolves baseMint → JupiterToken, then calls evaluateToken().
     */
    public void handlePoolEvent(PoolEvent event) {
        System.out.println("⚡ WS event: " + event.source() + " | " + event.signature().substring(0, 12));
        String baseMint = event.baseMint();


        // Atomic add: returns false if the mint was already in the set
        if (!seenTokenIds.add(baseMint)) return;

        System.out.printf("%n⚡ Pool event [%s] %s... sig: %s...%n",
                event.source(),
                baseMint.substring(0, 8),
                event.signature().substring(0, 12));

        Optional<JupiterToken> tokenOpt = tokenResolver.resolve(event);
        if (tokenOpt.isEmpty()) {
            System.out.printf("  ⚠️  Could not resolve %s... — skipping%n", baseMint.substring(0, 8));
            return;
        }

        JupiterToken token = tokenOpt.get();
        seenTokenIds.add(token.getId()); // id == baseMint, but add defensively

        printTokenDetails(token);
        evaluateToken(token);
        System.out.println();
    }

    // ── Token display ─────────────────────────────────────────────────────────

    private void printTokenDetails(JupiterToken token) {
        System.out.println("Name:       " + token.getName() + " (" + token.getSymbol() + ")");
        System.out.println("Address:    " + token.getId());
        System.out.printf ("Price:      $%.8f%n", token.getUsdPrice());
        System.out.printf ("Market Cap: $%,.0f%n", token.getMcap());
        System.out.printf ("Liquidity:  $%,.0f%n", token.getLiquidity());
        System.out.println("Holders:    " + String.format("%,d", token.getHolderCount()));
        System.out.printf ("Circ/Total: %.1f%%%n", token.getCircToTotalRatio() * 100);
        System.out.println("Verified:   " + (token.hasVerifiedTag() ? "✓ Yes" : "✗ No"));

        if (token.getAudit() != null) {
            System.out.println("Mint Auth:  " + (token.isMintSafe()
                    ? "✓ Disabled (safe)" : "⚠️  Active (risk)"));
            System.out.println("Freeze Auth:" + (token.getAudit().isFreezeAuthorityDisabled()
                    ? "✓ Disabled (safe)" : "⚠️  Active (risk)"));
            System.out.printf ("Top Holders:%.2f%% of supply%n",
                    token.getAudit().getTopHoldersPercentage());
        }

        if (token.getWebsite()   != null) System.out.println("Website:    " + token.getWebsite());
        if (token.getTwitter()   != null) System.out.println("Twitter:    " + token.getTwitter());
        if (token.getFirstPool() != null) System.out.println("Pool Created: " + token.getFirstPool().getCreatedAt());
    }

    // ── Safety evaluation + scoring ───────────────────────────────────────────

    private void evaluateToken(JupiterToken token) {
        if (!token.isSafeToTrade()) {
            System.out.println("  ❌ Failed mandatory safety checks — skipping score");
            return;
        }

        TokenScorer.ScoreResult result = scorer.score(token);
        result.print(token.getSymbol());

        if (result.isBuyCandidate()) {
            System.out.println("  → Paper buy");
            paperWallet.buy(token, result);
        } else if (result.total() >= TokenScorer.THRESHOLD_WATCH) {
            System.out.println("  → Added to watchlist");
        }
    }

    // ── Trending tokens ───────────────────────────────────────────────────────

    public void printTrending(String interval) {
        System.out.println("\n═══ Trending tokens (" + interval + ") ═══");
        List<JupiterToken> trending = jupiterClient.getTrendingTokens(interval);

        if (trending.isEmpty()) {
            System.out.println("No trending data available");
            return;
        }

        for (int i = 0; i < Math.min(trending.size(), 10); i++) {
            JupiterToken t = trending.get(i);
            System.out.printf(
                    "%2d. %-12s | $%-12.6f | MCap: $%15.0f | Liq: $%12.0f%n",
                    i + 1, t.getSymbol(), t.getUsdPrice(), t.getMcap(), t.getLiquidity()
            );
        }
    }

    // ── Wallet status (used by "status" run mode) ─────────────────────────────

    public void printWalletStatus() {
        paperWallet.printOpenPositions();
        paperWallet.printSummary();
    }
}

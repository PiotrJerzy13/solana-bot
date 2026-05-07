package com.solanabot.solana_bot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Polls Jupiter for newly listed tokens and applies safety filters.
 * This is the core of Phase 2 — detecting opportunities before acting on them.
 */
public class TokenPoller {

    // ── Safety filter thresholds — tune these to your risk tolerance ──
    private static final double MIN_MCAP       = 10_000;   // minimum $10k market cap
    private static final double MIN_LIQUIDITY  = 5_000;    // minimum $5k liquidity
    private static final int    MIN_HOLDERS    = 50;        // minimum 50 holders
    private static final double MIN_CIRC_RATIO = 0.1;      // at least 10% in circulation
    private static final int    POLL_INTERVAL  = 60_000;   // poll every 60 seconds

    private final JupiterClient jupiterClient;
    private final Set<String> seenTokenIds = new HashSet<>();

    public TokenPoller(JupiterClient jupiterClient) {
        this.jupiterClient = jupiterClient;
    }

    // ── Main polling loop ─────────────────────────────────────

    public void start() throws InterruptedException {
        System.out.println("═══════════════════════════════════════");
        System.out.println("  Solana Token Poller — Starting up");
        System.out.println("═══════════════════════════════════════");

        // first run — silently populate known tokens
        List<JupiterToken> initial = jupiterClient.getRecentTokens();
        initial.forEach(t -> seenTokenIds.add(t.getId()));
        System.out.println("✓ Loaded " + seenTokenIds.size() + " existing recent tokens");
        System.out.println("✓ Watching for new listings every " + (POLL_INTERVAL / 1000) + "s...\n");

        while (true) {
            Thread.sleep(POLL_INTERVAL);
            checkForNewTokens();
        }
    }

    // ── One poll cycle ────────────────────────────────────────

    private void checkForNewTokens() {
        List<JupiterToken> current = jupiterClient.getRecentTokens();

        if (current.isEmpty()) {
            System.out.println("⚠️  Empty response from Jupiter — will retry next cycle");
            return;
        }

        List<JupiterToken> newTokens = current.stream()
                .filter(t -> t.getId() != null && !seenTokenIds.contains(t.getId()))
                .toList();

        if (newTokens.isEmpty()) {
            System.out.println("○ No new tokens since last check");
            return;
        }

        System.out.println("──────────────────────────────────────");
        System.out.println("🆕 " + newTokens.size() + " new token(s) detected!");
        System.out.println("──────────────────────────────────────");

        for (JupiterToken token : newTokens) {
            seenTokenIds.add(token.getId());
            printTokenDetails(token);
            evaluateToken(token);
            System.out.println();
        }
    }

    // ── Token display ─────────────────────────────────────────

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
            System.out.println("Mint Auth:  " + (token.isMintSafe() ? "✓ Disabled (safe)" : "⚠️  Active (risk)"));
            System.out.println("Freeze Auth:" + (token.getAudit().isFreezeAuthorityDisabled() ? "✓ Disabled (safe)" : "⚠️  Active (risk)"));
            System.out.printf ("Top Holders:%.2f%% of supply%n", token.getAudit().getTopHoldersPercentage());
        }

        if (token.getWebsite() != null)  System.out.println("Website:    " + token.getWebsite());
        if (token.getTwitter() != null)  System.out.println("Twitter:    " + token.getTwitter());
        if (token.getFirstPool() != null) System.out.println("Pool Created: " + token.getFirstPool().getCreatedAt());
    }

    // ── Safety evaluation ─────────────────────────────────────

    private void evaluateToken(JupiterToken token) {
        System.out.println("\n--- Safety Check ---");

        boolean passedMcap      = check("MCap > $" + String.format("%,.0f", MIN_MCAP),
                token.getMcap() >= MIN_MCAP);
        boolean passedLiquidity = check("Liquidity > $" + String.format("%,.0f", MIN_LIQUIDITY),
                token.getLiquidity() >= MIN_LIQUIDITY);
        boolean passedHolders   = check("Holders > " + MIN_HOLDERS,
                token.getHolderCount() >= MIN_HOLDERS);
        boolean passedMint      = check("Mint authority disabled",
                token.isMintSafe());
        boolean passedCirc      = check("Circ supply > " + (int)(MIN_CIRC_RATIO * 100) + "% of total",
                token.getCircToTotalRatio() >= MIN_CIRC_RATIO);

        boolean allPassed = passedMcap && passedLiquidity && passedHolders
                && passedMint && passedCirc;

        System.out.println();
        if (allPassed) {
            System.out.println("✅ PASSED all safety checks — worth investigating");
            // → this is where you'd trigger a Jupiter swap in Phase 4
        } else {
            System.out.println("❌ FAILED safety checks — skipping");
        }
    }

    private boolean check(String label, boolean passed) {
        System.out.println("  " + (passed ? "✓" : "✗") + " " + label);
        return passed;
    }

    // ── Trending tokens ───────────────────────────────────────

    /**
     * Prints the top trending tokens for a given time interval.
     * Useful for manual monitoring.
     * @param interval one of: 5m, 1h, 6h, 24h
     */
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
}

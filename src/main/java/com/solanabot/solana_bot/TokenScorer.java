package com.solanabot.solana_bot;

import java.time.Duration;
import java.time.Instant;

/**
 * Scores a JupiterToken 0–100 based on weighted signals.
 *
 * Each factor produces a 0.0–1.0 ratio which is multiplied by its weight.
 * Weights sum to exactly 100, so the final total is a true percentage.
 *
 * Tune the constants at the top to adjust your risk profile.
 */
public class TokenScorer {

    // ── Weights (must sum to 100) ─────────────────────────────────────────────
    private static final double W_ORGANIC      = 25; // Jupiter's own quality signal
    private static final double W_TOP_HOLDERS  = 20; // whale / rug-pull concentration risk
    private static final double W_LIQUIDITY    = 15; // tradability depth
    private static final double W_HOLDERS      = 15; // community distribution
    private static final double W_POOL_AGE     = 10; // freshness window for sniping
    private static final double W_CIRC_RATIO   =  5; // hidden supply risk
    private static final double W_VERIFIED     =  5; // Jupiter verification badge
    private static final double W_MINT_SAFE    =  3; // mint authority disabled
    private static final double W_FREEZE_SAFE  =  2; // freeze authority disabled
    // ─────────────────────────────────────────────── total = 100

    // ── Liquidity thresholds ──────────────────────────────────────────────────
    private static final double LIQ_FLOOR  =   5_000; // $5k  → 0 pts
    private static final double LIQ_TARGET = 100_000; // $100k → full pts (log scale)

    // ── Holder count thresholds ───────────────────────────────────────────────
    private static final double HOLDERS_FLOOR  =    50; // 50    → 0 pts
    private static final double HOLDERS_TARGET = 1_000; // 1,000 → full pts (log scale)

    // ── Top-holder concentration ──────────────────────────────────────────────
    private static final double TOP_HOLD_SAFE =  30; // ≤ 30% → full pts
    private static final double TOP_HOLD_ZERO =  90; // ≥ 90% → 0 pts (linear decay between)

    // ── Pool age window (minutes) ─────────────────────────────────────────────
    // Shape: ramp-up → plateau → decay
    private static final double AGE_RAMP_END  =   30; // 0–30 min: still very risky, ramp up
    private static final double AGE_PEAK_END  =  120; // 30–120 min: sweetspot plateau
    private static final double AGE_DECAY_END = 1440; // 120–1440 min: opportunity fading

    // ── Score thresholds for action labels ───────────────────────────────────
    public static final int THRESHOLD_BUY   = 70; // score ≥ 70 → worth a paper buy
    public static final int THRESHOLD_WATCH = 50; // score ≥ 50 → keep watching

    // ─────────────────────────────────────────────────────────────────────────

    public ScoreResult score(JupiterToken token) {
        double fOrganic     = scoreOrganic(token);
        double fTopHolders  = scoreTopHolders(token);
        double fLiquidity   = scoreLiquidity(token);
        double fHolders     = scoreHolders(token);
        double fPoolAge     = scorePoolAge(token);
        double fCircRatio   = scoreCircRatio(token);
        double fVerified    = token.hasVerifiedTag()  ? 1.0 : 0.0;
        double fMintSafe    = token.isMintSafe()      ? 1.0 : 0.0;
        double fFreezeSafe  = isFreezeSafe(token)     ? 1.0 : 0.0;

        double raw =
                fOrganic    * W_ORGANIC     +
                        fTopHolders * W_TOP_HOLDERS +
                        fLiquidity  * W_LIQUIDITY   +
                        fHolders    * W_HOLDERS     +
                        fPoolAge    * W_POOL_AGE    +
                        fCircRatio  * W_CIRC_RATIO  +
                        fVerified   * W_VERIFIED    +
                        fMintSafe   * W_MINT_SAFE   +
                        fFreezeSafe * W_FREEZE_SAFE;

        int total = (int) Math.round(Math.min(100, raw));

        return new ScoreResult(
                total,
                fOrganic, fTopHolders, fLiquidity, fHolders,
                fPoolAge, fCircRatio, fVerified, fMintSafe, fFreezeSafe
        );
    }

    // ── Factor functions (each returns 0.0–1.0) ───────────────────────────────

    /**
     * Jupiter's organicScore is already 0–100; we just normalise it.
     */
    private double scoreOrganic(JupiterToken token) {
        return clamp(token.getOrganicScore() / 100.0);
    }

    /**
     * Lower top-holder concentration = safer.
     * ≤ 30% → 1.0, linear decay to 0 at 90%.
     */
    private double scoreTopHolders(JupiterToken token) {
        if (token.getAudit() == null) return 0.0;
        double pct = token.getAudit().getTopHoldersPercentage();
        if (pct <= TOP_HOLD_SAFE) return 1.0;
        if (pct >= TOP_HOLD_ZERO) return 0.0;
        return 1.0 - ((pct - TOP_HOLD_SAFE) / (TOP_HOLD_ZERO - TOP_HOLD_SAFE));
    }

    /**
     * Logarithmic scaling so small improvements at the low end still matter.
     * $5k → 0, $100k → 1.0.
     */
    private double scoreLiquidity(JupiterToken token) {
        double liq = token.getLiquidity();
        if (liq <= LIQ_FLOOR)  return 0.0;
        if (liq >= LIQ_TARGET) return 1.0;
        return Math.log(liq / LIQ_FLOOR) / Math.log(LIQ_TARGET / LIQ_FLOOR);
    }

    /**
     * Logarithmic scaling for holder count.
     * 50 → 0, 1,000 → 1.0.
     */
    private double scoreHolders(JupiterToken token) {
        double h = token.getHolderCount();
        if (h <= HOLDERS_FLOOR)  return 0.0;
        if (h >= HOLDERS_TARGET) return 1.0;
        return Math.log(h / HOLDERS_FLOOR) / Math.log(HOLDERS_TARGET / HOLDERS_FLOOR);
    }

    /**
     * Trapezoid curve: ramp up in first 30 min (still fresh, slightly risky),
     * plateau 30–120 min (sweetspot), then decay to 0 at 24 h (stale for sniper).
     */
    private double scorePoolAge(JupiterToken token) {
        if (token.getFirstPool() == null || token.getFirstPool().getCreatedAt() == null) {
            return 0.0;
        }
        double ageMin = poolAgeMinutes(token.getFirstPool().getCreatedAt());
        if (ageMin < 0)                 return 0.0;
        if (ageMin < AGE_RAMP_END)      return ageMin / AGE_RAMP_END;
        if (ageMin <= AGE_PEAK_END)     return 1.0;
        if (ageMin >= AGE_DECAY_END)    return 0.0;
        return 1.0 - ((ageMin - AGE_PEAK_END) / (AGE_DECAY_END - AGE_PEAK_END));
    }

    /**
     * Linear: 10% circulating → 0, 100% → 1.0.
     * Penalises tokens with large hidden/locked supply.
     */
    private double scoreCircRatio(JupiterToken token) {
        double r = token.getCircToTotalRatio();
        if (r <= 0.10) return 0.0;
        if (r >= 1.00) return 1.0;
        return (r - 0.10) / 0.90;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isFreezeSafe(JupiterToken token) {
        return token.getAudit() != null && token.getAudit().isFreezeAuthorityDisabled();
    }

    private double poolAgeMinutes(String createdAt) {
        try {
            return Duration.between(Instant.parse(createdAt), Instant.now()).toMinutes();
        } catch (Exception e) {
            return -1;
        }
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record ScoreResult(
            int    total,
            double organic,
            double topHolders,
            double liquidity,
            double holders,
            double poolAge,
            double circRatio,
            double verified,
            double mintSafe,
            double freezeSafe
    ) {
        /** Print a per-factor breakdown to the terminal. */
        public void print(String symbol) {
            System.out.println("\n  ┌── Score breakdown: " + symbol + " ──");
            printLine("Organic score",  organic,    W_ORGANIC);
            printLine("Top holders",    topHolders, W_TOP_HOLDERS);
            printLine("Liquidity",      liquidity,  W_LIQUIDITY);
            printLine("Holders",        holders,    W_HOLDERS);
            printLine("Pool age",       poolAge,    W_POOL_AGE);
            printLine("Circ ratio",     circRatio,  W_CIRC_RATIO);
            printLine("Verified",       verified,   W_VERIFIED);
            printLine("Mint safe",      mintSafe,   W_MINT_SAFE);
            printLine("Freeze safe",    freezeSafe, W_FREEZE_SAFE);
            System.out.println("  ├─────────────────────────────────");
            System.out.printf ("  │  TOTAL  %3d / 100   %s%n", total, label());
            System.out.println("  └─────────────────────────────────");
        }

        private static void printLine(String name, double factor, double weight) {
            double earned = factor * weight;
            String bar = "█".repeat((int)(factor * 10)) + "░".repeat(10 - (int)(factor * 10));
            System.out.printf("  │  %-14s %s  %4.1f / %2.0f%n", name, bar, earned, weight);
        }

        /** One-line action label based on score thresholds. */
        public String label() {
            if (total >= THRESHOLD_BUY)   return "🔥 BUY candidate";
            if (total >= THRESHOLD_WATCH) return "👀 Watch";
            return "❌ Skip";
        }

        public boolean isBuyCandidate() {
            return total >= THRESHOLD_BUY;
        }
    }
}

package com.solanabot.solana_bot;

import lombok.Getter;
import lombok.ToString;
import java.util.List;

@Getter
@ToString(onlyExplicitlyIncluded = true)
public class JupiterToken {

    private String id;
    private String name;
    private String symbol;
    private String icon;
    private int decimals;
    private String twitter;
    private String website;
    private String dev;
    private double circSupply;
    private double totalSupply;
    private String tokenProgram;
    private int holderCount;
    private double fdv;
    private double mcap;
    private double usdPrice;
    private double liquidity;
    private List<String> tags;
    private boolean isVerified;
    private FirstPool firstPool;
    private Audit audit;
    private double organicScore;
    private String organicScoreLabel;

    @Getter
    public static class FirstPool {
        private String id;
        private String createdAt;
    }

    @Getter
    public static class Audit {
        private boolean mintAuthorityDisabled;
        private boolean freezeAuthorityDisabled;
        private double topHoldersPercentage;
    }

    // ── Helper methods ──

    public boolean isSafeToTrade() {
        return mcap > 10_000
                && holderCount > 50
                && liquidity > 5_000
                && isMintSafe()
                && getCircToTotalRatio() > 0.1;
    }

    public boolean isMintSafe() {
        return audit != null && audit.isMintAuthorityDisabled();
    }

    public double getCircToTotalRatio() {
        if (totalSupply == 0) return 0;
        return circSupply / totalSupply;
    }

    public boolean hasVerifiedTag() {
        return tags != null && tags.contains("verified");
    }

    @Override
    public String toString() {
        return String.format(
                "[%s] %s (%s) | Price: $%.6f | MCap: $%,.0f | Holders: %,d | Liquidity: $%,.0f",
                id != null ? id.substring(0, 8) + "..." : "N/A",
                name, symbol,
                usdPrice, mcap, holderCount, liquidity
        );
    }
}
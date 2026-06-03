package com.solanabot.solana_bot;

import java.time.Instant;

public record PoolEvent(
        String        signature,
        String        poolAddress,
        String        baseMint,
        String        quoteMint,
        ProgramSource source,
        Instant       detectedAt
) {
    static final String SOL_MINT = "So11111111111111111111111111111111111111112";

    public enum ProgramSource { RAYDIUM, PUMP_FUN }

    public boolean isQuoteSol() {
        return SOL_MINT.equals(quoteMint);
    }
}

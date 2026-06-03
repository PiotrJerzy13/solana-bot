package com.solanabot.solana_bot;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a PoolEvent's baseMint address to a JupiterToken.
 *
 * Jupiter indexes newly created tokens with a delay of several seconds,
 * so this class retries with back-off before giving up.
 */
public class TokenResolver {

    private static final int  MAX_RETRIES    = 3;
    private static final long RETRY_DELAY_MS = 2_000;

    private final JupiterClient jupiterClient;

    public TokenResolver(JupiterClient jupiterClient) {
        this.jupiterClient = jupiterClient;
    }

    /**
     * Searches Jupiter for the token whose id exactly equals event.baseMint().
     * Retries up to 3× with a 2 s delay between attempts.
     * Exact-match on id prevents fuzzy-search false positives.
     */
    public Optional<JupiterToken> resolve(PoolEvent event) {
        String baseMint = event.baseMint();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            List<JupiterToken> results = jupiterClient.searchTokens(baseMint);

            Optional<JupiterToken> match = results.stream()
                    .filter(t -> baseMint.equals(t.getId()))
                    .findFirst();

            if (match.isPresent()) return match;

            if (attempt < MAX_RETRIES) {
                System.out.printf("⚡ %s... not yet indexed, retry %d/%d%n",
                        baseMint.substring(0, 8), attempt, MAX_RETRIES);
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }

        System.out.printf("⚡ %s... not found after %d attempts — skipping%n",
                baseMint.substring(0, 8), MAX_RETRIES);
        return Optional.empty();
    }
}

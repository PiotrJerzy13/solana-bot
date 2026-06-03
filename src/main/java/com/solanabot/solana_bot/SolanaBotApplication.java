package com.solanabot.solana_bot;

/**
 * Entry point for the Solana trading bot.
 *
 * Run modes:
 *   java -jar solana-bot.jar            → start token poller (default)
 *   java -jar solana-bot.jar trending   → print trending tokens and exit
 *   java -jar solana-bot.jar search JUP → search for a specific token and exit
 *
 * Optional environment variable:
 *   JUPITER_API_KEY=your_key → uses paid API with higher rate limits
 */
public class SolanaBotApplication {

	public static void main(String[] args) throws Exception {

		String apiKey = System.getenv("JUPITER_API_KEY");
		String wsUrl  = System.getenv("SOLANA_WS_URL");

		JupiterClient client      = new JupiterClient(apiKey);
		PaperWallet   paperWallet = new PaperWallet();
		TokenPoller   poller      = new TokenPoller(client, paperWallet);

		if (apiKey != null && !apiKey.isBlank()) {
			System.out.println("✓ Using authenticated Jupiter API");
		} else {
			System.out.println("ℹ Using lite (unauthenticated) Jupiter API");
		}

		// ── Argument-based run modes ──────────────────────────
		String mode = args.length > 0 ? args[0].toLowerCase() : "poll";

		switch (mode) {

			case "trending" -> {
				poller.printTrending("5m");
				poller.printTrending("1h");
				poller.printTrending("24h");
			}

			case "search" -> {
				String query = args.length > 1 ? args[1] : "SOL";
				System.out.println("\nSearching for: " + query);
				client.searchTokens(query)
						.stream()
						.limit(5)
						.forEach(System.out::println);
			}

			case "status" -> poller.printWalletStatus();

			default -> {
				if (wsUrl != null && !wsUrl.isBlank()) {
					System.out.println("⚡ WebSocket detection enabled: " + wsUrl);
					new SolanaWebSocketClient(wsUrl, poller::handlePoolEvent).connect();
				}

				poller.printTrending("1h");
				System.out.println();
				poller.start();
			}
		}
	}
}
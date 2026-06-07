package com.solanabot.solana_bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Connects to a Solana RPC WebSocket and subscribes to logsSubscribe for
 * Raydium AMM and Pump.fun pool creation events.
 *
 * On each detected event: resolves baseMint/quoteMint via getTransaction (HTTP),
 * builds a PoolEvent, and fires the callback on a resolver thread.
 *
 * connect() returns immediately — the reconnect loop runs in a daemon thread.
 * Reconnects with exponential back-off: 1 s doubling up to 30 s.
 */
public class SolanaWebSocketClient {

    private static final String RAYDIUM_PROGRAM  = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8";
    private static final String PUMP_FUN_PROGRAM = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P";

    private static final long BACKOFF_MIN_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 30_000;

    private final String wsUrl;
    private final String httpUrl;
    private final Consumer<PoolEvent> callback;
    private final LogParser logParser = new LogParser();
    private final HttpClient httpClient;

    // Scheduled pool — schedule() returns immediately, thread only consumed when task fires
    private final java.util.concurrent.ScheduledExecutorService resolverPool =
            Executors.newScheduledThreadPool(20, r -> {
                Thread t = new Thread(r, "pool-resolver");
                t.setDaemon(true);
                return t;
            });

    public SolanaWebSocketClient(String wsUrl, Consumer<PoolEvent> callback) {
        this.wsUrl    = wsUrl;
        String envHttp = System.getenv("SOLANA_HTTP_URL");
        this.httpUrl  = (envHttp != null && !envHttp.isBlank()) ? envHttp
                      : wsUrl.replace("wss://", "https://").replace("ws://", "http://");
        this.callback = callback;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Starts the WebSocket connection loop in a background daemon thread.
     * Returns immediately.
     */
    public void connect() {
        Thread t = new Thread(this::connectLoop, "solana-ws");
        t.setDaemon(true);
        t.start();
    }

    // ── Connection loop ───────────────────────────────────────────────────────

    private void connectLoop() {
        long backoffMs = BACKOFF_MIN_MS;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                System.out.println("⚡ Connecting to Solana WebSocket...");
                WsListener listener = new WsListener();

                WebSocket ws = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(wsUrl), listener)
                        .join();

                sendSubscribe(ws, RAYDIUM_PROGRAM);
                sendSubscribe(ws, PUMP_FUN_PROGRAM);
                System.out.println("⚡ Subscribed to Raydium AMM + Pump.fun logs");

                backoffMs = BACKOFF_MIN_MS;
                listener.awaitClose();
                System.out.println("⚡ WebSocket disconnected");

            } catch (Exception e) {
                System.err.println("⚡ WS connection error: " + e.getMessage());
            }

            System.out.printf("⚡ Reconnecting in %.0fs...%n", backoffMs / 1000.0);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
        }
    }

    private void sendSubscribe(WebSocket ws, String programId) {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"logsSubscribe\"," +
                "\"params\":[{\"mentions\":[\"" + programId + "\"]}," +
                "{\"commitment\":\"processed\"}]}";
        ws.sendText(req, true);
    }

    // ── Message handling ──────────────────────────────────────────────────────

    private void handleMessage(String json) {
        Optional<LogParser.Notification> parsed = logParser.parse(json);
        if (parsed.isPresent()) {
            System.out.println("[WS-2] LogParser HIT: " + parsed.get().source()
                    + " sig=" + parsed.get().signature().substring(0, 12));
        }
        parsed.ifPresent(notification -> {
            try {
                resolverPool.schedule(() -> {
                    System.out.println("[WS-2b] resolver thread executing for sig=" + notification.signature().substring(0, 12));
                    try {
                        Optional<PoolEvent> event = resolveAccounts(
                                notification.signature(), notification.source());
                        String bm = event.isPresent() ? event.get().baseMint() : "";
                        System.out.println("[WS-4] resolveAccounts result: " + (event.isPresent()
                                ? "✓ baseMint=" + bm.substring(0, Math.min(8, bm.length()))
                                : "✗ empty — callback NOT fired"));
                        event.ifPresent(callback);
                    } catch (Throwable t) {
                        System.out.println("[WS-ERR] " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }, 32, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                System.out.println("[WS-REJECTED] pool full, dropping event for sig="
                        + notification.signature().substring(0, 12));
            }
        });
    }

    // ── getTransaction → extract baseMint / quoteMint / poolAddress ───────────

    private Optional<PoolEvent> resolveAccounts(String signature, PoolEvent.ProgramSource source) {
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getTransaction\"," +
                    "\"params\":[\"" + signature + "\"," +
                    "{\"encoding\":\"json\",\"maxSupportedTransactionVersion\":0,\"commitment\":\"finalized\"}]}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(httpUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            System.out.println("[WS-HTTP-URL] " + httpUrl);
            HttpResponse<String> response;
            try {
                response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .get(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                System.out.println("[WS-TIMEOUT] getTransaction timed out after 15s for sig="
                        + signature.substring(0, 12));
                return Optional.empty();
            }

            System.out.println("[WS-3] getTransaction HTTP " + response.statusCode()
                    + " body[0:200]=" + response.body().substring(0, Math.min(200, response.body().length())));

            JsonObject root     = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement resultEl = root.get("result");
            if (resultEl == null || resultEl.isJsonNull()) {
                System.out.println("[WS-3] result is null — tx not found or RPC limitation");
                return Optional.empty();
            }

            JsonObject message = resultEl.getAsJsonObject()
                    .getAsJsonObject("transaction")
                    .getAsJsonObject("message");

            JsonArray accountKeys  = message.getAsJsonArray("accountKeys");
            JsonArray instructions = message.getAsJsonArray("instructions");

            String targetProgram = (source == PoolEvent.ProgramSource.RAYDIUM)
                    ? RAYDIUM_PROGRAM : PUMP_FUN_PROGRAM;

            int programIdx = -1;
            for (int i = 0; i < accountKeys.size(); i++) {
                if (targetProgram.equals(accountKeys.get(i).getAsString())) {
                    programIdx = i;
                    break;
                }
            }
            if (programIdx < 0) {
                System.out.println("[WS-3] program not found in accountKeys (count=" + accountKeys.size() + ")");
                return Optional.empty();
            }

            JsonObject targetInstr = null;
            for (JsonElement el : instructions) {
                JsonObject instr = el.getAsJsonObject();
                if (instr.get("programIdIndex").getAsInt() == programIdx) {
                    targetInstr = instr;
                    break;
                }
            }
            if (targetInstr == null) {
                System.out.println("[WS-3] no instruction with programIdIndex=" + programIdx);
                return Optional.empty();
            }

            JsonArray accounts = targetInstr.getAsJsonArray("accounts");

            String baseMint, quoteMint, poolAddress;

            if (source == PoolEvent.ProgramSource.RAYDIUM) {
                // ⚠ Approximate Raydium V4 initialize2 indices — verify vs mainnet
                if (accounts.size() < 9) return Optional.empty();
                poolAddress = accountKeys.get(accounts.get(3).getAsInt()).getAsString();
                baseMint    = accountKeys.get(accounts.get(7).getAsInt()).getAsString();
                quoteMint   = accountKeys.get(accounts.get(8).getAsInt()).getAsString();
            } else {
                // Pump.fun CreateV2: [0]=mint, [2]=bondingCurve, quote=SOL
                if (accounts.size() < 3) return Optional.empty();
                baseMint    = accountKeys.get(accounts.get(0).getAsInt()).getAsString();
                quoteMint   = PoolEvent.SOL_MINT;
                poolAddress = accountKeys.get(accounts.get(2).getAsInt()).getAsString();
            }

            return Optional.of(new PoolEvent(
                    signature, poolAddress, baseMint, quoteMint, source, Instant.now()));

        } catch (Exception e) {
            System.out.println("[WS-ERR-INNER] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.err.println("[WS-ERR-INNER] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buffer     = new StringBuilder();
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeLatch.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("⚡ WS error: " + error.getMessage());
            closeLatch.countDown();
        }

        void awaitClose() throws InterruptedException {
            closeLatch.await();
        }
    }
}

package com.solanabot.solana_bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Optional;

/**
 * Parses raw Solana logsNotification JSON strings from a logsSubscribe WebSocket stream.
 * Stateless and thread-safe — safe to share across threads.
 */
public class LogParser {

    private static final String RAYDIUM_PROGRAM  = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8";
    private static final String PUMP_FUN_PROGRAM = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P";

    // Triggers that identify a new pool creation within the logs array
    private static final String RAYDIUM_TRIGGER  = "initialize2";
    private static final String PUMP_FUN_TRIGGER = "Program log: Instruction: Create";

    /**
     * Returns a Notification if the message is a successful pool-creation event
     * for Raydium AMM or Pump.fun. Returns empty for subscription confirmations,
     * failed transactions, non-matching programs, and parse errors.
     */
    public Optional<Notification> parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            JsonElement methodElem = root.get("method");
            if (methodElem == null || !"logsNotification".equals(methodElem.getAsString())) {
                // methodElem==null means it's a subscription confirmation ({"result":id}) — don't log those
                if (methodElem != null) System.out.println("[LP] skip: method=" + methodElem.getAsString());
                return Optional.empty();
            }

            JsonObject value = root
                    .getAsJsonObject("params")
                    .getAsJsonObject("result")
                    .getAsJsonObject("value");

            // Skip failed transactions
            if (!value.get("err").isJsonNull()) {
                System.out.println("[LP] skip: failed tx sig=" + value.get("signature").getAsString().substring(0, 12));
                return Optional.empty();
            }

            String     signature = value.get("signature").getAsString();
            JsonArray  logs      = value.getAsJsonArray("logs");

            PoolEvent.ProgramSource source = null;
            for (JsonElement logElem : logs) {
                String log = logElem.getAsString();

                // Identify the calling program from the first "invoke" log line
                if (source == null) {
                    if      (log.contains(RAYDIUM_PROGRAM))  source = PoolEvent.ProgramSource.RAYDIUM;
                    else if (log.contains(PUMP_FUN_PROGRAM)) source = PoolEvent.ProgramSource.PUMP_FUN;
                }

                if (source == PoolEvent.ProgramSource.RAYDIUM  && log.contains(RAYDIUM_TRIGGER)) {
                    return Optional.of(new Notification(signature, source));
                }
                if (source == PoolEvent.ProgramSource.PUMP_FUN && log.contains(PUMP_FUN_TRIGGER)) {
                    return Optional.of(new Notification(signature, source));
                }
            }
            if (source == PoolEvent.ProgramSource.PUMP_FUN && logs.toString().contains("Instruction: Create")) System.out.println("[LP] PUMP_FUN instruction logs: " + java.util.stream.StreamSupport.stream(logs.spliterator(), false).map(JsonElement::getAsString).filter(l -> l.contains("Instruction:")).collect(java.util.stream.Collectors.toList()));
            System.out.println("[LP] no trigger: source=" + source + " logs=" + logs.size()
                    + " last=\"" + (logs.size() > 0 ? logs.get(logs.size()-1).getAsString() : "") + "\"");
            return Optional.empty();

        } catch (Exception e) {
            System.out.println("[LP] parse error: " + e);
            return Optional.empty();
        }
    }

    public record Notification(String signature, PoolEvent.ProgramSource source) {}
}

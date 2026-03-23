package com.joragaka.ptdialogue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side dialogue history manager.
 * Saves per-player history in <world>/dialoguehistory/<player>.json
 * and syncs it to the client on join.
 */
public class HistoryManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** In-memory cache: playerName (lowercase) → list of entries */
    private static final Map<String, List<HistorySyncPayload.Entry>> cache = new ConcurrentHashMap<>();

    public static void register() {
        // Register S2C payload
        PayloadTypeRegistry.playS2C().register(HistorySyncPayload.ID, HistorySyncPayload.CODEC);

        // On player join — send their history on the next server tick
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            server.execute(() -> sendHistoryToPlayer(player, server));
        });
    }

    /**
     * Record a new dialogue entry for a player and immediately persist + sync.
     * Called from DialogueCommand when /dialogue is executed.
     */
    public static void record(ServerPlayerEntity player, MinecraftServer server,
                               String icon, String name, int color, String message, String skinUuid) {
        String playerName = player.getGameProfile().name();
        long ts = System.currentTimeMillis();
        HistorySyncPayload.Entry entry = new HistorySyncPayload.Entry(icon, name, color, message, ts, skinUuid);

        // Add to in-memory cache
        List<HistorySyncPayload.Entry> entries = cache.computeIfAbsent(
                playerName.toLowerCase(), k -> {
                    // First time — load from disk
                    Path file = getPlayerFile(server, playerName);
                    return new ArrayList<>(loadFromDisk(file));
                });
        entries.add(entry);

        // Persist to disk
        saveToDisk(getPlayerFile(server, playerName), entries);

        // Send incremental update to client
        if (!player.isDisconnected()) {
            ServerPlayNetworking.send(player, new HistorySyncPayload(List.of(entry), false));
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static void sendHistoryToPlayer(ServerPlayerEntity player, MinecraftServer server) {
        String playerName = player.getGameProfile().name();
        Path file = getPlayerFile(server, playerName);
        System.out.println("[PTDialogue] History file path: " + file.toAbsolutePath());

        // Load from disk and put in cache
        List<HistorySyncPayload.Entry> entries = new ArrayList<>(loadFromDisk(file));
        cache.put(playerName.toLowerCase(), entries);

        System.out.println("[PTDialogue] Sending " + entries.size()
                + " history entries to " + playerName);
        if (entries.isEmpty()) return;
        ServerPlayNetworking.send(player, new HistorySyncPayload(entries, true));
    }

    private static Path getHistoryDir(MinecraftServer server) {
        // getSavePath(ROOT) returns the world root folder (e.g. saves/WorldName/)
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        // ROOT is often "<world>/." — normalize() resolves the dot, giving us the world folder directly
        return worldRoot.resolve("dialoguehistory");
    }

    private static Path getPlayerFile(MinecraftServer server, String playerName) {
        String safe = playerName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return getHistoryDir(server).resolve(safe + ".json");
    }

    private static List<HistorySyncPayload.Entry> loadFromDisk(Path file) {
        List<HistorySyncPayload.Entry> list = new ArrayList<>();
        if (!Files.exists(file)) return list;
        try {
            String json = Files.readString(file);
            try {
                var root = JsonParser.parseString(json);
                if (!root.isJsonArray()) return list;
                for (var je : root.getAsJsonArray()) {
                    try {
                        var o     = je.getAsJsonObject();
                        String icon  = o.has("icon")    ? o.get("icon").getAsString()    : "";
                        String name  = o.has("name")    ? o.get("name").getAsString()    : "";
                        int    color = o.has("color")   ? o.get("color").getAsInt()      : 0xFFFFFF;
                        String msg   = o.has("message") ? o.get("message").getAsString() : "";
                        long   ts    = o.has("ts")      ? o.get("ts").getAsLong()        : 0L;
                        String skinUuid = o.has("skinUuid") ? o.get("skinUuid").getAsString() : null;
                        list.add(new HistorySyncPayload.Entry(icon, name, color, msg, ts, skinUuid));
                    } catch (Exception ignored) {}
                }
                return list;
            } catch (Exception parseEx) {
                // Try to repair common truncation cases: if array was cut, try to close it at last '}' and append ']'
                String s = json;
                String repaired = null;
                if (s != null && s.startsWith("[")) {
                    int lastObj = s.lastIndexOf('}');
                    if (lastObj > 0) {
                        String cand = s.substring(0, lastObj + 1) + "]";
                        try {
                            var root2 = JsonParser.parseString(cand);
                            if (root2.isJsonArray()) repaired = cand;
                        } catch (Exception ignored) {}
                    }
                }

                // If not repaired yet, progressively trim the tail until it parses (limit iterations)
                if (repaired == null) {
                    int maxTrim = Math.min(1000, s.length());
                    for (int trim = 1; trim <= maxTrim; trim++) {
                        try {
                            String cand = s.substring(0, s.length() - trim);
                            var root2 = JsonParser.parseString(cand);
                            if (root2.isJsonArray()) { repaired = cand; break; }
                        } catch (Exception ignored) {
                        }
                    }
                }

                if (repaired != null) {
                    // parse repaired content and rewrite the file atomically
                    try {
                        var root = JsonParser.parseString(repaired).getAsJsonArray();
                        for (var je : root) {
                            try {
                                var o     = je.getAsJsonObject();
                                String icon  = o.has("icon")    ? o.get("icon").getAsString()    : "";
                                String name  = o.has("name")    ? o.get("name").getAsString()    : "";
                                int    color = o.has("color")   ? o.get("color").getAsInt()      : 0xFFFFFF;
                                String msg   = o.has("message") ? o.get("message").getAsString() : "";
                                long   ts    = o.has("ts")      ? o.get("ts").getAsLong()        : 0L;
                                String skinUuid = o.has("skinUuid") ? o.get("skinUuid").getAsString() : null;
                                list.add(new HistorySyncPayload.Entry(icon, name, color, msg, ts, skinUuid));
                            } catch (Exception ignored) {}
                        }

                        // rewrite cleaned file atomically
                        try {
                            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                            Files.writeString(tmp, GSON.toJson(root));
                            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            System.out.println("[PTDialogue] Repaired history file: " + file.toAbsolutePath());
                        } catch (Exception ex) {
                            // If atomic move not supported, try non-atomic replace
                            try {
                                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                                Files.writeString(tmp, GSON.toJson(root));
                                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception ex2) {
                                System.err.println("[PTDialogue] Failed to rewrite repaired history file: " + ex2.getMessage());
                            }
                        }

                    } catch (Exception ex) {
                        System.err.println("[PTDialogue] Failed to parse repaired history for " + file.getFileName());
                    }
                } else {
                    System.err.println("[PTDialogue] Could not recover corrupted history file: " + file.getFileName());
                }
            }
        } catch (IOException ex) {
            System.err.println("[PTDialogue] Failed to load history for " + file.getFileName()
                    + ": " + ex.getMessage());
        }
        return list;
    }

    private static void saveToDisk(Path file, List<HistorySyncPayload.Entry> entries) {
        try {
            Files.createDirectories(file.getParent());
            JsonArray arr = new JsonArray();
            for (var e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("icon",    e.icon());
                o.addProperty("name",    e.name());
                o.addProperty("color",   e.color());
                o.addProperty("message", e.message());
                o.addProperty("ts",      e.timestamp());
                // If entry contains skinUuid include it in persisted JSON
                if (e instanceof HistorySyncPayload.Entry he) {
                    try { if (he.skinUuid() != null) o.addProperty("skinUuid", he.skinUuid()); } catch (Throwable ignored) {}
                }
                arr.add(o);
            }
            // write atomically via temp file and move
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(arr));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) {
                // fallback if atomic move unsupported
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            System.err.println("[PTDialogue] Failed to save history for " + file.getFileName()
                    + ": " + ex.getMessage());
        }
    }
}

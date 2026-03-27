package com.joragaka.ptdialogue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HistoryManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, List<HistorySyncPayload.Entry>> cache = new ConcurrentHashMap<>();

    public static void record(ServerPlayer player, MinecraftServer server,
                               String icon, String name, int color, String message, String skinUuid) {
        String playerName = player.getGameProfile().getName();
        long ts = System.currentTimeMillis();
        HistorySyncPayload.Entry entry = new HistorySyncPayload.Entry(icon, name, color, message, ts, skinUuid);

        List<HistorySyncPayload.Entry> entries = cache.computeIfAbsent(
                playerName.toLowerCase(), k -> {
                    Path file = getPlayerFile(server, playerName);
                    return new ArrayList<>(loadFromDisk(file));
                });
        entries.add(entry);

        saveToDisk(getPlayerFile(server, playerName), entries);

        if (!player.hasDisconnected()) {
            ModNetworking.sendToPlayer(new HistorySyncPayload(List.of(entry), false), player);
        }
    }

    public static void sendHistoryToPlayer(ServerPlayer player, MinecraftServer server) {
        String playerName = player.getGameProfile().getName();
        Path file = getPlayerFile(server, playerName);

        List<HistorySyncPayload.Entry> entries = new ArrayList<>(loadFromDisk(file));
        cache.put(playerName.toLowerCase(), entries);

        if (entries.isEmpty()) return;
        ModNetworking.sendToPlayer(new HistorySyncPayload(entries, true), player);
    }

    private static Path getHistoryDir(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return worldRoot.resolve("dialoguehistory");
    }

    private static Path getPlayerFile(MinecraftServer server, String playerName) {
        String safe = playerName.replaceAll("[\\/:*?\"<>|]", "_");
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

                if (repaired == null) {
                    int maxTrim = Math.min(1000, s.length());
                    for (int trim = 1; trim <= maxTrim; trim++) {
                        try {
                            String cand = s.substring(0, s.length() - trim);
                            var root2 = JsonParser.parseString(cand);
                            if (root2.isJsonArray()) { repaired = cand; break; }
                        } catch (Exception ignored) {}
                    }
                }

                if (repaired != null) {
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

                        try {
                            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                            Files.writeString(tmp, GSON.toJson(root));
                            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        } catch (Exception ex) {
                            try {
                                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                                Files.writeString(tmp, GSON.toJson(root));
                                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException ignored) {}
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
                try { if (e.skinUuid() != null) o.addProperty("skinUuid", e.skinUuid()); } catch (Throwable ignored) {}
                arr.add(o);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(arr));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {}
    }
}

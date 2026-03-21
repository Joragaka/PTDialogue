package com.joragaka.ptdialogue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Server-side manager that:
 * 1. Sends all custom icons from config/ptloreicons/ to connecting clients
 * 2. Downloads & caches player head textures via Mojang API
 * 3. Distributes head textures to all clients
 */
public class IconSyncManager {

    private static final String ICONS_FOLDER = "ptloreicons";
    private static final String HEADS_SUBFOLDER = ".skincache/heads";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Nicknames of players whose heads have been cached on this server */
    private static final Set<String> knownPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Tracks known icon files: relative path → MD5 hash. Used to detect new/changed files. */
    private static final Map<String, String> knownIconHashes = new ConcurrentHashMap<>();

    /** Reference to the server instance for broadcasting */
    private static volatile MinecraftServer serverInstance;

    /** Periodic scanner for icon folder changes */
    private static ScheduledExecutorService iconWatcherExecutor;

    /** Interval in seconds between folder scans */
    private static final int SCAN_INTERVAL_SECONDS = 5;

    public static void register() {
        // Register the S2C payload type
        PayloadTypeRegistry.playS2C().register(IconSyncPayload.ID, IconSyncPayload.CODEC);
        // Register the C2S payload type so server recognizes IconRequestPayload structure
        PayloadTypeRegistry.playC2S().register(IconRequestPayload.ID, IconRequestPayload.CODEC);

        // When a player joins, send all icons + all cached heads
        // Small delay to ensure client networking is fully initialized
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

            // Store server reference for periodic broadcasting
            serverInstance = server;

            // Start periodic icon folder scanner if not already running
            startIconWatcher(server);

            // Also fetch and cache this player's head if not already done
            String name = player.getGameProfile().name();
            if (knownPlayers.add(name.toLowerCase())) {
                fetchAndCacheHead(name, server);
            }

            // Delay sync by 1 second to ensure client networking is fully initialized
            server.execute(() -> {
                // Schedule with delay using a simple approach
                CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    server.execute(() -> {
                        if (player.isDisconnected()) return;

                        // Enforce server-side mod requirement: if the client does not support our mod's
                        // networking channel, disconnect them with an explanatory message.
                        try {
                            boolean hasMod = ServerPlayNetworking.canSend(player, IconSyncPayload.ID);
                            if (!hasMod) {
                                System.out.println("[PTDialogue] Disconnecting player " + name + " — missing PTDialogue mod.");
                                player.networkHandler.disconnect(net.minecraft.text.Text.literal("You must install the PTDialogue mod to join this server."));
                                return;
                            }
                        } catch (Throwable t) {
                            // If canSend isn't available for some reason, fall back to allowing join and logging
                            System.err.println("[PTDialogue] Warning: failed to check client mod presence: " + t.getMessage());
                        }

                        System.out.println("[PTDialogue] Sending icons and heads to " + name);
                        sendAllIcons(player);
                        sendAllCachedHeads(player);
                    });
                });
            });
        });

        // Handle client requests for re-sending a specific icon
        ServerPlayNetworking.registerGlobalReceiver(IconRequestPayload.ID,
                (payload, context) -> {
                    try {
                        String relativePath = payload.path();
                        Path file = getIconsDir().resolve(relativePath.replace('/', java.io.File.separatorChar));
                        if (!Files.exists(file) || !Files.isRegularFile(file)) return;
                        byte[] data = Files.readAllBytes(file);
                        String md5 = computeMd5(data);
                        ServerPlayerEntity player = context.player();
                        context.server().execute(() ->
                                ServerPlayNetworking.send(player, new IconSyncPayload(relativePath, data, md5)));
                    } catch (Exception e) {
                        System.err.println("[PTDialogue] Failed to handle icon request: " + e.getMessage());
                    }
                });

        // Stop the watcher when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            stopIconWatcher();
            serverInstance = null;
        });
    }

    // ─────────────────── Periodic icon folder scanning ───────────────────

    private static synchronized void startIconWatcher(MinecraftServer server) {
        if (iconWatcherExecutor != null && !iconWatcherExecutor.isShutdown()) return;

        // Build initial snapshot of known icons
        buildIconSnapshot();

        iconWatcherExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PTDialogue-IconWatcher");
            t.setDaemon(true);
            return t;
        });

        iconWatcherExecutor.scheduleWithFixedDelay(() -> {
            try {
                scanForChangedIcons();
            } catch (Exception e) {
                System.err.println("[PTDialogue] Icon watcher error: " + e.getMessage());
            }
        }, SCAN_INTERVAL_SECONDS, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);

        System.out.println("[PTDialogue] Started icon folder watcher (every " + SCAN_INTERVAL_SECONDS + "s)");
    }

    private static synchronized void stopIconWatcher() {
        if (iconWatcherExecutor != null) {
            iconWatcherExecutor.shutdownNow();
            iconWatcherExecutor = null;
            knownIconHashes.clear();
            System.out.println("[PTDialogue] Stopped icon folder watcher");
        }
    }

    /**
     * Build initial snapshot of all icon files and their hashes.
     */
    private static void buildIconSnapshot() {
        knownIconHashes.clear();
        Path iconsDir = getIconsDir();
        if (!Files.isDirectory(iconsDir)) return;

        try {
            collectIconHashes(iconsDir, "", knownIconHashes);
        } catch (Exception e) {
            System.err.println("[PTDialogue] Failed to build icon snapshot: " + e.getMessage());
        }
    }

    private static void collectIconHashes(Path currentDir, String prefix, Map<String, String> target) throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.equals(".skincache")) continue;

                if (Files.isDirectory(entry)) {
                    String subPrefix = prefix.isEmpty() ? fileName : prefix + "/" + fileName;
                    collectIconHashes(entry, subPrefix, target);
                } else if (fileName.toLowerCase().endsWith(".png")) {
                    String relativePath = prefix.isEmpty() ? fileName : prefix + "/" + fileName;
                    byte[] data = Files.readAllBytes(entry);
                    target.put(relativePath, computeMd5(data));
                }
            }
        }
    }

    /**
     * Scan the icons folder for new or changed files and broadcast them to all players.
     */
    private static void scanForChangedIcons() {
        MinecraftServer server = serverInstance;
        if (server == null) return;

        // Don't scan if no players are online
        if (server.getPlayerManager().getPlayerList().isEmpty()) return;

        Path iconsDir = getIconsDir();
        if (!Files.isDirectory(iconsDir)) return;

        Map<String, String> currentHashes = new ConcurrentHashMap<>();
        try {
            collectIconHashes(iconsDir, "", currentHashes);
        } catch (Exception e) {
            return;
        }

        // Find new or changed files
        List<String> changedPaths = new ArrayList<>();
        for (Map.Entry<String, String> entry : currentHashes.entrySet()) {
            String oldHash = knownIconHashes.get(entry.getKey());
            if (oldHash == null || !oldHash.equals(entry.getValue())) {
                changedPaths.add(entry.getKey());
            }
        }

        // Find deleted files (currently we don't handle deletion sync, just track)
        Set<String> removedPaths = new HashSet<>(knownIconHashes.keySet());
        removedPaths.removeAll(currentHashes.keySet());

        // Update snapshot
        knownIconHashes.clear();
        knownIconHashes.putAll(currentHashes);

        if (changedPaths.isEmpty()) return;

        // Broadcast changed icons to all online players
        server.execute(() -> {
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) return;

            for (String relativePath : changedPaths) {
                Path file = iconsDir.resolve(relativePath.replace('/', java.io.File.separatorChar));
                try {
                    byte[] data = Files.readAllBytes(file);
                    String md5 = computeMd5(data);
                    IconSyncPayload payload = new IconSyncPayload(relativePath, data, md5);

                    for (ServerPlayerEntity player : players) {
                        ServerPlayNetworking.send(player, payload);
                    }
                    System.out.println("[PTDialogue] Hot-synced icon to all players: " + relativePath + " (" + data.length + " bytes)");
                } catch (Exception e) {
                    System.err.println("[PTDialogue] Failed to hot-sync icon '" + relativePath + "': " + e.getMessage());
                }
            }
        });
    }

    // ─────────────────── Icon syncing ───────────────────

    private static void sendAllIcons(ServerPlayerEntity player) {
        Path iconsDir = getIconsDir();
        if (!Files.isDirectory(iconsDir)) return;

        try {
            sendDirectoryRecursive(player, iconsDir, "");
        } catch (Exception e) {
            System.err.println("[PTDialogue] Failed to send icons to " + player.getGameProfile().name() + ": " + e.getMessage());
        }
    }

    private static void sendDirectoryRecursive(ServerPlayerEntity player, Path currentDir, String prefix) throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                // Skip .skincache folder (heads are sent separately)
                if (fileName.equals(".skincache")) continue;

                if (Files.isDirectory(entry)) {
                    String subPrefix = prefix.isEmpty() ? fileName : prefix + "/" + fileName;
                    sendDirectoryRecursive(player, entry, subPrefix);
                } else if (fileName.toLowerCase().endsWith(".png")) {
                    String relativePath = prefix.isEmpty() ? fileName : prefix + "/" + fileName;
                    byte[] data = Files.readAllBytes(entry);
                    String md5 = computeMd5(data);
                    ServerPlayNetworking.send(player, new IconSyncPayload(relativePath, data, md5));
                    System.out.println("[PTDialogue] Sent icon to client: " + relativePath + " (" + data.length + " bytes)");
                }
            }
        }
    }

    private static void sendAllCachedHeads(ServerPlayerEntity player) {
        Path headsDir = getHeadsDir();
        if (!Files.isDirectory(headsDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(headsDir, "*.png")) {
            for (Path headFile : stream) {
                String nick = headFile.getFileName().toString().replace(".png", "");
                byte[] data = Files.readAllBytes(headFile);
                String md5 = computeMd5(data);
                // Heads are synced with a special prefix so client knows it's a head
                ServerPlayNetworking.send(player,
                        new IconSyncPayload(".heads/" + nick + ".png", data, md5));
            }
        } catch (Exception e) {
            System.err.println("[PTDialogue] Failed to send cached heads to " + player.getGameProfile().name() + ": " + e.getMessage());
        }
    }

    // ─────────────────── Head fetching ───────────────────

    private static void fetchAndCacheHead(String playerName, MinecraftServer server) {
        String key = playerName.toLowerCase();
        Path skinFile = getHeadsDir().resolve(key + ".png");

        // If already cached on disk, just broadcast
        if (Files.exists(skinFile)) {
            broadcastHead(key, server);
            return;
        }

        // Fetch from Mojang API async
        CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Get UUID
                HttpResponse<String> uuidResp = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                                .timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (uuidResp.statusCode() != 200) return;

                String uuid = JsonParser.parseString(uuidResp.body())
                        .getAsJsonObject().get("id").getAsString();

                // Step 2: Get profile → skin URL
                HttpResponse<String> profileResp = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                                .timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (profileResp.statusCode() != 200) return;

                String skinUrl = extractSkinUrl(profileResp.body());
                if (skinUrl == null) return;

                // Step 3: Download raw skin PNG
                HttpResponse<byte[]> skinResp = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(skinUrl))
                                .timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (skinResp.statusCode() != 200) return;

                byte[] skinPng = skinResp.body();

                // Step 4: Save raw skin to disk (client will composite)
                Files.createDirectories(skinFile.getParent());
                Files.write(skinFile, skinPng);

                System.out.println("[PTDialogue] Cached skin for head: " + playerName);

                // Step 5: Broadcast to all online players
                server.execute(() -> broadcastHead(key, server));

            } catch (Exception e) {
                System.err.println("[PTDialogue] Failed to fetch head for " + playerName + ": " + e.getMessage());
            }
        });
    }

    private static void broadcastHead(String key, MinecraftServer server) {
        Path headFile = getHeadsDir().resolve(key + ".png");
        if (!Files.exists(headFile)) return;

        try {
            byte[] data = Files.readAllBytes(headFile);
            String md5 = computeMd5(data);
            IconSyncPayload payload = new IconSyncPayload(".heads/" + key + ".png", data, md5);

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send(player, payload);
            }
        } catch (Exception e) {
            System.err.println("[PTDialogue] Failed to broadcast head: " + e.getMessage());
        }
    }

    // ─────────────────── Head compositing ───────────────────
    // Server sends raw skin PNG — client handles compositing.
    // This avoids AWT dependency on server (java.desktop module may be missing).

    private static String extractSkinUrl(String profileJson) {
        try {
            JsonObject json = JsonParser.parseString(profileJson).getAsJsonObject();
            var properties = json.getAsJsonArray("properties");
            if (properties == null) return null;
            for (var prop : properties) {
                var obj = prop.getAsJsonObject();
                if (!"textures".equals(obj.get("name").getAsString())) continue;
                String decoded = new String(Base64.getDecoder().decode(obj.get("value").getAsString()));
                JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) {
                    return textures.getAsJsonObject("SKIN").get("url").getAsString();
                }
            }
        } catch (Exception e) {
            System.err.println("[PTDialogue] Failed to parse skin URL: " + e.getMessage());
        }
        return null;
    }

    // ─────────────────── Paths ───────────────────

    private static Path getIconsDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir()
                .resolve(ICONS_FOLDER);
    }

    private static Path getHeadsDir() {
        return getIconsDir().resolve(HEADS_SUBFOLDER);
    }

    // ─────────────────── Utils ───────────────────

    static String computeMd5(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

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
 * 1. Sends all custom icons from config/ptlore/ptdialogue/ to connecting clients
 * 2. Downloads & caches player head textures via Mojang API
 * 3. Distributes head textures to all clients
 */
public class IconSyncManager {

    private static final String HEADS_SUBFOLDER = ".skincache/heads";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Nicknames of players whose heads have been cached on this server */
    // теперь используем ключи в форме: lowercased-name или uuid
    private static final Set<String> knownPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Tracks known icon files: relative path → MD5 hash. Used to detect new/changed files. */
    private static final Map<String, String> knownIconHashes = new ConcurrentHashMap<>();

    /** Reference to the server instance for broadcasting */
    private static volatile MinecraftServer serverInstance;

    /** Periodic scanner for icon folder changes */
    private static ScheduledExecutorService iconWatcherExecutor;

    /** Dedicated executor for IO and network background tasks to avoid common-pool starvation */
    private static final java.util.concurrent.ExecutorService IO_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PTDialogue-IO");
        t.setDaemon(true);
        return t;
    });

    /** Interval in seconds between folder scans */
    private static final int SCAN_INTERVAL_SECONDS = 5;

    /** Limit number of icons/heads to send at join to avoid blocking server tick */
    private static final int MAX_ICONS_PER_JOIN = 50;

    // Emergency switch: disable automatic sending of all icons/heads on player join to prevent server freezes.
    // Set to true to re-enable (use after profiling/optimizations).
    private static volatile boolean AUTO_SEND_ON_JOIN = false;

    // Global emergency switch: completely disable server-side icon/head sync and background workers.
    // Set to false to re-enable once performance fixes are validated.
    private static volatile boolean DISABLE_SERVER_SYNC = true;

    public static void register() {
        // Register payload types always — client needs these registered to create receivers
        PayloadTypeRegistry.playS2C().register(IconSyncPayload.ID, IconSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(IconRequestPayload.ID, IconRequestPayload.CODEC);

        if (DISABLE_SERVER_SYNC) {
            System.out.println("[PTDialogue] Server-side icon sync DISABLED (emergency). Background tasks won't start.");
        }

        // Server-side logic (join handler, watcher, lifecycle) only when not disabled
        if (!DISABLE_SERVER_SYNC) {
            // When a player joins, send all icons + all cached heads
            // Small delay to ensure client networking is fully initialized
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                ServerPlayerEntity player = handler.getPlayer();

                // Store server reference for periodic broadcasting
                serverInstance = server;

                // Start periodic icon folder scanner if not already running
                // start watcher asynchronously (initial snapshot will be built in background)
                startIconWatcher();

                // Also fetch and cache this player's head if not already done
                String profileName = null;
                try { profileName = player.getGameProfile().name(); } catch (Throwable ignored) {}
                final String displayName = (profileName == null || profileName.isEmpty()) ? player.getName().getString() : profileName;
                String uuidKey;
                try { uuidKey = player.getUuid().toString().toLowerCase(); } catch (Throwable ignored) { uuidKey = ""; }
                String nameKey = profileName == null ? "" : profileName.toLowerCase();

                if (!uuidKey.isEmpty() && knownPlayers.add(uuidKey)) {
                    fetchAndCacheHeadByUuid(uuidKey, server);
                }
                if (!nameKey.isEmpty() && knownPlayers.add(nameKey)) {
                    fetchAndCacheHead(nameKey, server);
                }

                // Short server-thread check, then start background preparation + delayed send
                server.execute(() -> {
                    if (player.isDisconnected()) return;
                    try {
                        boolean hasMod = ServerPlayNetworking.canSend(player, IconSyncPayload.ID);
                        if (!hasMod) {
                            System.out.println("[PTDialogue] Disconnecting player " + displayName + " — missing PTDialogue mod.");
                            player.networkHandler.disconnect(net.minecraft.text.Text.literal("You must install the PTDialogue mod to join this server."));
                            return;
                        }
                    } catch (Throwable t) {
                        System.err.println("[PTDialogue] Warning: failed to check client mod presence: " + t.getMessage());
                    }

                    // Start a background task that waits a bit (allow client to initialize) then prepares and sends icons
                    if (AUTO_SEND_ON_JOIN) {
                        CompletableFuture.runAsync(() -> {
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                            sendAllIconsAsync(player);
                            sendAllCachedHeadsAsync(player);
                        }, IO_EXECUTOR);
                    } else {
                        System.out.println("[PTDialogue] AUTO_SEND_ON_JOIN is disabled — skipping icon/head sync for " + displayName);
                    }
                  });
            });


            // Stop the watcher when server stops
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
                stopIconWatcher();
                serverInstance = null;
                try { IO_EXECUTOR.shutdownNow(); } catch (Throwable ignored) {}
            });
        }
    }

    // Asynchronous variant: read all icon files in a background thread and send payloads on the server thread
    private static void sendAllIconsAsync(ServerPlayerEntity player) {
        CompletableFuture.runAsync(() -> {
            Path iconsDir = getIconsDir();
            if (!Files.isDirectory(iconsDir)) return;
            List<IconSyncPayload> toSend = new ArrayList<>();
            try {
                collectFilesRecursive(iconsDir, "", toSend);
            } catch (Exception e) {
                System.err.println("[PTDialogue] Failed to prepare icons for " + player.getGameProfile().name() + ": " + e.getMessage());
                return;
            }
            List<IconSyncPayload> sendSlice = toSend;
            if (toSend.size() > MAX_ICONS_PER_JOIN) {
                System.out.println("[PTDialogue] Limiting icons sent on join to " + MAX_ICONS_PER_JOIN + " (available=" + toSend.size() + ")");
                sendSlice = new ArrayList<>(toSend.subList(0, MAX_ICONS_PER_JOIN));
            }
            MinecraftServer srv = serverInstance;
            if (srv == null) return;
            // Batch-send: schedule a single runnable on server thread that sends all payloads to the player
            final List<IconSyncPayload> finalSend = sendSlice;
            srv.execute(() -> {
                try {
                    if (player.isDisconnected()) return;
                    for (IconSyncPayload payload : finalSend) {
                        try {
                            ServerPlayNetworking.send(player, payload);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    System.err.println("[PTDialogue] Failed to send prepared icons to " + player.getGameProfile().name() + ": " + t.getMessage());
                }
            });
        }, IO_EXECUTOR);
    }

    private static void collectFilesRecursive(Path baseDir, String prefix, List<IconSyncPayload> out) throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.equals(".skincache")) continue;
                if (Files.isDirectory(entry)) {
                    String subPrefix = prefix.isEmpty() ? fileName : prefix + "/" + fileName;
                    collectFilesRecursive(entry, subPrefix, out);
                } else if (fileName.toLowerCase().endsWith(".png")) {
                    String relativePath = prefix.isEmpty() ? fileName : prefix + "/" + fileName;
                    byte[] data = Files.readAllBytes(entry);
                    String md5 = computeMd5(data);
                    out.add(new IconSyncPayload(relativePath, data, md5));
                }
            }
        }
    }

    // Asynchronous variant for cached heads
    private static void sendAllCachedHeadsAsync(ServerPlayerEntity player) {
        CompletableFuture.runAsync(() -> {
             Path headsDir = getHeadsDir();
             if (!Files.isDirectory(headsDir)) return;
             List<IconSyncPayload> toSend = new ArrayList<>();
             try (DirectoryStream<Path> stream = Files.newDirectoryStream(headsDir, "*.png")) {
                 for (Path headFile : stream) {
                     String nick = headFile.getFileName().toString().replace(".png", "");
                     byte[] data = Files.readAllBytes(headFile);
                     String md5 = computeMd5(data);
                     toSend.add(new IconSyncPayload(".heads/" + nick + ".png", data, md5));
                 }
             } catch (Exception e) {
                 System.err.println("[PTDialogue] Failed to prepare cached heads for " + player.getGameProfile().name() + ": " + e.getMessage());
                 return;
             }
            List<IconSyncPayload> sendSlice = toSend;
            if (toSend.size() > MAX_ICONS_PER_JOIN) sendSlice = new ArrayList<>(toSend.subList(0, MAX_ICONS_PER_JOIN));
             MinecraftServer srv = serverInstance;
             if (srv == null) return;
             final List<IconSyncPayload> finalSend = sendSlice;
             srv.execute(() -> {
                try {
                    if (player.isDisconnected()) return;
                    for (IconSyncPayload payload : finalSend) {
                        try { ServerPlayNetworking.send(player, payload); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    System.err.println("[PTDialogue] Failed to send prepared cached heads to " + player.getGameProfile().name() + ": " + t.getMessage());
                }
            });
        }, IO_EXECUTOR);
    }

    // ─────────────────── Periodic icon folder scanning ───────────────────

    private static synchronized void startIconWatcher() {
        if (iconWatcherExecutor != null && !iconWatcherExecutor.isShutdown()) return;

        // Build initial snapshot of known icons
        // Build initial snapshot asynchronously to avoid blocking server tick
        CompletableFuture.runAsync(() -> buildIconSnapshot(), IO_EXECUTOR);

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


        // Update snapshot
        knownIconHashes.clear();
        knownIconHashes.putAll(currentHashes);

        if (changedPaths.isEmpty()) return;

        // Read changed files in this watcher thread (IO off server), then schedule sends on server thread
        List<IconSyncPayload> payloads = new ArrayList<>();
        for (String relativePath : changedPaths) {
            Path file = iconsDir.resolve(relativePath.replace('/', java.io.File.separatorChar));
            try {
                byte[] data = Files.readAllBytes(file);
                String md5 = computeMd5(data);
                payloads.add(new IconSyncPayload(relativePath, data, md5));
            } catch (Exception e) {
                System.err.println("[PTDialogue] Failed to read changed icon '" + relativePath + "': " + e.getMessage());
            }
        }

        if (payloads.isEmpty()) return;

        server.execute(() -> {
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) return;
            for (IconSyncPayload payload : payloads) {
                for (ServerPlayerEntity player : players) {
                    try {
                        if (player.isDisconnected()) continue;
                        ServerPlayNetworking.send(player, payload);
                    } catch (Throwable ignored) {}
                }
                System.out.println("[PTDialogue] Hot-synced icon to all players: " + payload.path() + " (" + payload.data().length + " bytes)");
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
        }, IO_EXECUTOR);
    }

    private static void broadcastHead(String key, MinecraftServer server) {
        Path headFile = getHeadsDir().resolve(key + ".png");
        if (!Files.exists(headFile)) return;

        // Read file in IO executor, then schedule sending on server thread
        CompletableFuture.runAsync(() -> {
            try {
                byte[] data = Files.readAllBytes(headFile);
                String md5 = computeMd5(data);
                IconSyncPayload payload = new IconSyncPayload(".heads/" + key + ".png", data, md5);
                // schedule send
                server.execute(() -> {
                    try {
                        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                            try { ServerPlayNetworking.send(player, payload); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable t) {
                        System.err.println("[PTDialogue] Failed to broadcast head on server thread: " + t.getMessage());
                    }
                });
            } catch (Exception e) {
                System.err.println("[PTDialogue] Failed to read head file for broadcast: " + e.getMessage());
            }
        }, IO_EXECUTOR);
    }

    // Check whether a cached head exists for the given key (lowercased name or uuid)
    public static boolean hasHead(String key) {
        try {
            Path headFile = getHeadsDir().resolve(key.toLowerCase() + ".png");
            return Files.exists(headFile) && Files.isRegularFile(headFile);
        } catch (Exception e) {
            return false;
        }
    }

    // Send a single cached head to a specific player (if present)
    public static void sendHeadToPlayer(String key, ServerPlayerEntity player) {
        Path headFile = getHeadsDir().resolve(key.toLowerCase() + ".png");
        if (!Files.exists(headFile) || !Files.isRegularFile(headFile)) return;
        CompletableFuture.runAsync(() -> {
            try {
                byte[] data = Files.readAllBytes(headFile);
                String md5 = computeMd5(data);
                IconSyncPayload payload = new IconSyncPayload(".heads/" + key.toLowerCase() + ".png", data, md5);
                // schedule send on server thread
                try {
                    var srv = serverInstance;
                    if (srv == null) return;
                    srv.execute(() -> {
                        try { ServerPlayNetworking.send(player, payload); } catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {}
            } catch (Exception e) {
                System.err.println("[PTDialogue] Failed to prepare head for player: " + e.getMessage());
            }
        }, IO_EXECUTOR);
    }

    // Public helper: ensure a player's head is cached (async fetch) — safe to call repeatedly.
    public static void ensureHeadCached(String playerName, MinecraftServer server) {
        if (playerName == null || playerName.isEmpty() || server == null) return;
        String key = playerName.toLowerCase();
        if (hasHead(key)) return;
        // fetchAndCacheHead is async and will broadcast when done
        fetchAndCacheHead(playerName, server);
    }
    // ─────────────────── Head compositing ───────────────────

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
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        Path configDir = loader.getConfigDir();

        // config/ptlore/ptdialogue/
        return configDir.resolve("ptlore").resolve("ptdialogue");
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

    // Public helper: get the preferred head key for a player (uuid preferred, fallback to name)
    public static String getHeadKeyForPlayer(ServerPlayerEntity player) {
        if (player == null) return "";
        try {
            String uuidKey = player.getUuid().toString().toLowerCase();
            if (!uuidKey.isBlank()) return uuidKey;
        } catch (Throwable ignored) {}
        try {
            String name = player.getGameProfile().name();
            if (name != null && !name.isBlank()) return name.toLowerCase();
        } catch (Throwable ignored) {}
        return "";
    }

    // Public helper: ensure a specific player's head is cached by uuid (if possible)
    public static void ensureHeadCachedForPlayer(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) return;
        String key = getHeadKeyForPlayer(player);
        if (key.isEmpty()) return;
        if (hasHead(key)) return;

        // If we got a uuid-like key (no dashes), try fetch by uuid first
        if (key.matches("[0-9a-f]{32}")) {
            fetchAndCacheHeadByUuid(key, server);
        } else {
            fetchAndCacheHead(key, server);
        }
    }

    // Fetch head directly by uuid (expects compact 32 hex chars)
    private static void fetchAndCacheHeadByUuid(String uuid32, MinecraftServer server) {
        String key = uuid32.toLowerCase();
        Path skinFile = getHeadsDir().resolve(key + ".png");

        if (Files.exists(skinFile)) {
            broadcastHead(key, server);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // Query sessionserver profile by uuid (with dashes inserted)
                String dashed = uuid32;
                if (uuid32.length() == 32) {
                    dashed = uuid32.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
                }

                HttpResponse<String> profileResp = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + dashed))
                                .timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (profileResp.statusCode() != 200) return;

                String skinUrl = extractSkinUrl(profileResp.body());
                if (skinUrl == null) return;

                HttpResponse<byte[]> skinResp = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(skinUrl))
                                .timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (skinResp.statusCode() != 200) return;

                byte[] skinPng = skinResp.body();
                Files.createDirectories(skinFile.getParent());
                Files.write(skinFile, skinPng);

                System.out.println("[PTDialogue] Cached skin for uuid head: " + uuid32);
                server.execute(() -> broadcastHead(key, server));
            } catch (Exception e) {
                System.err.println("[PTDialogue] Failed to fetch head for uuid " + uuid32 + ": " + e.getMessage());
            }
        }, IO_EXECUTOR);
    }
}

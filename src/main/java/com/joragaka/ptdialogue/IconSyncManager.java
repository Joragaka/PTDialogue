package com.joragaka.ptdialogue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

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

public class IconSyncManager {

    private static final String HEADS_SUBFOLDER = ".skincache/heads";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final Set<String> knownPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, String> knownIconHashes = new ConcurrentHashMap<>();
    private static volatile MinecraftServer serverInstance;
    private static ScheduledExecutorService iconWatcherExecutor;

    private static final java.util.concurrent.ExecutorService IO_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PTDialogue-IO");
        t.setDaemon(true);
        return t;
    });

    private static final int SCAN_INTERVAL_SECONDS = 5;
    private static final int MAX_ICONS_PER_JOIN = 50;
    private static volatile boolean AUTO_SEND_ON_JOIN = true;
    private static volatile boolean DISABLE_SERVER_SYNC = false;

    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (DISABLE_SERVER_SYNC) return;

        serverInstance = server;
        startIconWatcher();

        String profileName = null;
        try { profileName = player.getGameProfile().getName(); } catch (Throwable ignored) {}
        String uuidKey;
        try { uuidKey = player.getUUID().toString().toLowerCase(); } catch (Throwable ignored) { uuidKey = ""; }
        String nameKey = profileName == null ? "" : profileName.toLowerCase();

        if (!uuidKey.isEmpty() && knownPlayers.add(uuidKey)) {
            fetchAndCacheHeadByUuid(uuidKey, server);
        }
        if (!nameKey.isEmpty() && knownPlayers.add(nameKey)) {
            fetchAndCacheHead(nameKey, server);
        }

        if (AUTO_SEND_ON_JOIN) {
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                sendAllIconsAsync(player);
                sendAllCachedHeadsAsync(player);
            }, IO_EXECUTOR);
        }
    }

    public static void onServerStopping() {
        stopIconWatcher();
        serverInstance = null;
        try { IO_EXECUTOR.shutdownNow(); } catch (Throwable ignored) {}
    }

    private static void sendAllIconsAsync(ServerPlayer player) {
        CompletableFuture.runAsync(() -> {
            Path iconsDir = getIconsDir();
            if (!Files.isDirectory(iconsDir)) return;
            List<IconSyncPayload> toSend = new ArrayList<>();
            try {
                collectFilesRecursive(iconsDir, "", toSend);
            } catch (Exception e) {
                return;
            }
            List<IconSyncPayload> sendSlice = toSend;
            if (toSend.size() > MAX_ICONS_PER_JOIN) {
                sendSlice = new ArrayList<>(toSend.subList(0, MAX_ICONS_PER_JOIN));
            }
            MinecraftServer srv = serverInstance;
            if (srv == null) return;
            final List<IconSyncPayload> finalSend = sendSlice;
            srv.execute(() -> {
                try {
                    if (player.hasDisconnected()) return;
                    for (IconSyncPayload payload : finalSend) {
                        try { ModNetworking.sendToPlayer(payload, player); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
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

    private static void sendAllCachedHeadsAsync(ServerPlayer player) {
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
                 return;
             }
            List<IconSyncPayload> sendSlice = toSend;
            if (toSend.size() > MAX_ICONS_PER_JOIN) sendSlice = new ArrayList<>(toSend.subList(0, MAX_ICONS_PER_JOIN));
             MinecraftServer srv = serverInstance;
             if (srv == null) return;
             final List<IconSyncPayload> finalSend = sendSlice;
             srv.execute(() -> {
                try {
                    if (player.hasDisconnected()) return;
                    for (IconSyncPayload payload : finalSend) {
                        try { ModNetworking.sendToPlayer(payload, player); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            });
        }, IO_EXECUTOR);
    }

    private static synchronized void startIconWatcher() {
        if (iconWatcherExecutor != null && !iconWatcherExecutor.isShutdown()) return;
        CompletableFuture.runAsync(IconSyncManager::buildIconSnapshot, IO_EXECUTOR);
        iconWatcherExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PTDialogue-IconWatcher");
            t.setDaemon(true);
            return t;
        });
        iconWatcherExecutor.scheduleWithFixedDelay(() -> {
            try { scanForChangedIcons(); } catch (Exception ignored) {}
        }, SCAN_INTERVAL_SECONDS, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static synchronized void stopIconWatcher() {
        if (iconWatcherExecutor != null) {
            iconWatcherExecutor.shutdownNow();
            iconWatcherExecutor = null;
            knownIconHashes.clear();
        }
    }

    private static void buildIconSnapshot() {
        knownIconHashes.clear();
        Path iconsDir = getIconsDir();
        if (!Files.isDirectory(iconsDir)) return;
        try { collectIconHashes(iconsDir, "", knownIconHashes); } catch (Exception ignored) {}
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

    private static void scanForChangedIcons() {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;

        Path iconsDir = getIconsDir();
        if (!Files.isDirectory(iconsDir)) return;

        Map<String, String> currentHashes = new ConcurrentHashMap<>();
        try { collectIconHashes(iconsDir, "", currentHashes); } catch (Exception e) { return; }

        List<String> changedPaths = new ArrayList<>();
        for (Map.Entry<String, String> entry : currentHashes.entrySet()) {
            String oldHash = knownIconHashes.get(entry.getKey());
            if (oldHash == null || !oldHash.equals(entry.getValue())) {
                changedPaths.add(entry.getKey());
            }
        }

        knownIconHashes.clear();
        knownIconHashes.putAll(currentHashes);

        if (changedPaths.isEmpty()) return;

        List<IconSyncPayload> payloads = new ArrayList<>();
        for (String relativePath : changedPaths) {
            Path file = iconsDir.resolve(relativePath.replace('/', java.io.File.separatorChar));
            try {
                byte[] data = Files.readAllBytes(file);
                String md5 = computeMd5(data);
                payloads.add(new IconSyncPayload(relativePath, data, md5));
            } catch (Exception ignored) {}
        }

        if (payloads.isEmpty()) return;

        server.execute(() -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            for (IconSyncPayload payload : payloads) {
                for (ServerPlayer player : players) {
                    try {
                        if (player.hasDisconnected()) continue;
                        ModNetworking.sendToPlayer(payload, player);
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static void fetchAndCacheHead(String playerName, MinecraftServer server) {
        String key = playerName.toLowerCase();
        Path skinFile = getHeadsDir().resolve(key + ".png");

        if (Files.exists(skinFile)) {
            broadcastHead(key, server);
            return;
        }

        try {
            ServerPlayer online = server.getPlayerList().getPlayerByName(playerName);
            if (online != null) {
                if (trySaveSkinFromPlayerProfile(online, skinFile)) {
                    server.execute(() -> broadcastHead(key, server));
                    return;
                }
            }
        } catch (Throwable ignored) {}

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> uuidResp = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                                .timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (uuidResp.statusCode() != 200) return;

                String uuid = JsonParser.parseString(uuidResp.body())
                        .getAsJsonObject().get("id").getAsString();

                HttpResponse<String> profileResp = HTTP.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
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

                server.execute(() -> broadcastHead(key, server));
            } catch (Exception ignored) {}
        }, IO_EXECUTOR);
    }

    private static void broadcastHead(String key, MinecraftServer server) {
        Path headFile = getHeadsDir().resolve(key + ".png");
        if (!Files.exists(headFile)) return;

        CompletableFuture.runAsync(() -> {
            try {
                byte[] data = Files.readAllBytes(headFile);
                String md5 = computeMd5(data);
                IconSyncPayload payload = new IconSyncPayload(".heads/" + key + ".png", data, md5);
                server.execute(() -> {
                    try {
                        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                            try { ModNetworking.sendToPlayer(payload, player); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                });
            } catch (Exception ignored) {}
        }, IO_EXECUTOR);
    }

    public static boolean hasHead(String key) {
        try {
            Path headFile = getHeadsDir().resolve(key.toLowerCase() + ".png");
            return Files.exists(headFile) && Files.isRegularFile(headFile);
        } catch (Exception e) {
            return false;
        }
    }

    public static void sendHeadToPlayer(String key, ServerPlayer player) {
        Path headFile = getHeadsDir().resolve(key.toLowerCase() + ".png");
        if (!Files.exists(headFile) || !Files.isRegularFile(headFile)) return;
        CompletableFuture.runAsync(() -> {
            try {
                byte[] data = Files.readAllBytes(headFile);
                String md5 = computeMd5(data);
                IconSyncPayload payload = new IconSyncPayload(".heads/" + key.toLowerCase() + ".png", data, md5);
                try {
                    var srv = serverInstance;
                    if (srv == null) return;
                    srv.execute(() -> {
                        try { ModNetworking.sendToPlayer(payload, player); } catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {}
            } catch (Exception ignored) {}
        }, IO_EXECUTOR);
    }

    public static void ensureHeadCached(String playerName, MinecraftServer server) {
        if (playerName == null || playerName.isEmpty() || server == null) return;
        String key = playerName.toLowerCase();
        if (hasHead(key)) return;
        fetchAndCacheHead(playerName, server);
    }

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
        } catch (Exception ignored) {}
        return null;
    }

    private static Path getIconsDir() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        return configDir.resolve("ptlore").resolve("ptdialogue");
    }

    private static Path getHeadsDir() {
        return getIconsDir().resolve(HEADS_SUBFOLDER);
    }

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

    private static void fetchAndCacheHeadByUuid(String uuid32, MinecraftServer server) {
        String key = uuid32.toLowerCase();
        Path skinFile = getHeadsDir().resolve(key + ".png");

        if (Files.exists(skinFile)) {
            broadcastHead(key, server);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
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

                server.execute(() -> broadcastHead(key, server));
            } catch (Exception ignored) {}
        }, IO_EXECUTOR);
    }

    private static boolean trySaveSkinFromPlayerProfile(ServerPlayer player, Path outputFile) {
        try {
            Object gp = player.getGameProfile();
            if (gp == null) return false;

            Object propertiesObj = null;
            String[] tryNames = new String[] {"getProperties", "properties", "getPropertyMap", "getPropertySet"};
            for (String mname : tryNames) {
                try {
                    var m = gp.getClass().getMethod(mname);
                    propertiesObj = m.invoke(gp);
                    if (propertiesObj != null) break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (propertiesObj == null) {
                try {
                    var f = gp.getClass().getField("properties");
                    propertiesObj = f.get(gp);
                } catch (NoSuchFieldException ignored) {}
            }

            if (propertiesObj == null) return false;

            java.util.Collection<?> propsCollection = null;
            if (propertiesObj instanceof java.util.Map) {
                propsCollection = ((java.util.Map<?,?>)propertiesObj).values();
            } else {
                try {
                    var valuesM = propertiesObj.getClass().getMethod("values");
                    Object vals = valuesM.invoke(propertiesObj);
                    if (vals instanceof java.util.Collection) propsCollection = (java.util.Collection<?>) vals;
                } catch (NoSuchMethodException ignored) {}
            }

            if (propsCollection == null) {
                if (propertiesObj instanceof java.lang.Iterable) {
                    java.util.ArrayList<Object> tmp = new java.util.ArrayList<>();
                    for (Object o : (java.lang.Iterable<?>)propertiesObj) tmp.add(o);
                    propsCollection = tmp;
                }
            }

            if (propsCollection == null || propsCollection.isEmpty()) return false;

            for (Object prop : propsCollection) {
                if (prop == null) continue;
                String name = null;
                String value = null;
                try {
                    var gm = prop.getClass().getMethod("getName");
                    name = (String) gm.invoke(prop);
                } catch (NoSuchMethodException ignored) {
                    try {
                        var gm = prop.getClass().getMethod("name");
                        name = (String) gm.invoke(prop);
                    } catch (NoSuchMethodException ignored2) {}
                }
                try {
                    var gm2 = prop.getClass().getMethod("getValue");
                    value = (String) gm2.invoke(prop);
                } catch (NoSuchMethodException ignored) {
                    try {
                        var gm2 = prop.getClass().getMethod("value");
                        value = (String) gm2.invoke(prop);
                    } catch (NoSuchMethodException ignored2) {}
                }
                if (name == null || value == null) continue;
                if (!"textures".equals(name)) continue;

                try {
                    String decoded = new String(java.util.Base64.getDecoder().decode(value));
                    JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
                    if (textures != null && textures.has("SKIN")) {
                        String url = textures.getAsJsonObject("SKIN").get("url").getAsString();
                        HttpResponse<byte[]> response = HTTP.send(
                                HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                        if (response.statusCode() == 200) {
                            byte[] skinPng = response.body();
                            Files.createDirectories(outputFile.getParent());
                            Files.write(outputFile, skinPng);
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }
}

package com.joragaka.ptdialogue.client;

import com.joragaka.ptdialogue.IconSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side handler for icon/head sync packets from the server.
 * Saves received PNGs to disk and registers them as GPU textures.
 * Protects server-synced files from client-side modification by verifying MD5 hashes.
 */
public class IconSyncHandler {

    /** Maps relative path → expected MD5 hash (from server). Files with wrong hash get restored. */
    private static final Map<String, String> serverHashes = new ConcurrentHashMap<>();
    /** Maps relative path -> last request timestamp to avoid spamming server. */
    private static final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
    private static final long REQUEST_COOLDOWN_MS = 5_000;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(IconSyncPayload.ID, (payload, context) -> {
            String path = payload.path();
            byte[] data = payload.data();
            String md5 = payload.md5();

            context.client().execute(() -> handleIconSync(path, data, md5));
        });
        // Register C2S request sender (nothing to register for receiving - requests are sent)
    }

    /**
     * Ask the server to re-send a specific icon. Fire-and-forget.
     */
    public static void requestIconFromServer(String relativePath) {
        try {
            String key = relativePath.replace('\\', '/').toLowerCase();
            long now = System.currentTimeMillis();
            Long last = lastRequestTime.get(key);
            if (last != null && (now - last) < REQUEST_COOLDOWN_MS) return;
            lastRequestTime.put(key, now);

            var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client == null || client.getNetworkHandler() == null) return;
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new com.joragaka.ptdialogue.IconRequestPayload(key));
        } catch (Throwable t) {
            // best-effort
        }
    }

    private static void handleIconSync(String path, byte[] data, String md5) {
        try {
            if (path.startsWith(".heads/")) {
                handleHeadSync(path, data);
            } else {
                // Custom icon from server's ptlore/ptdialogue/
                handleCustomIconSync(path, data, md5);
            }
        } catch (Exception e) {
            System.err.println("[ptdialogue] IconSync failed for '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Handle a custom icon synced from server.
     * Saves to config/ptlore/ptdialogue/ and stores the MD5 hash for tamper protection.
     */
    private static void handleCustomIconSync(String relativePath, byte[] data, String md5) throws Exception {
        Path iconsDir = getIconsDir();
        Path targetFile = iconsDir.resolve(relativePath.replace('/', java.io.File.separatorChar));

        // Validate path doesn't escape icons directory
        if (!targetFile.normalize().startsWith(iconsDir.normalize())) {
            System.err.println("[ptdialogue] Rejected icon sync with suspicious path: " + relativePath);
            return;
        }

        Files.createDirectories(targetFile.getParent());

        // Check if file already exists with correct hash
        if (Files.exists(targetFile)) {
            byte[] existing = Files.readAllBytes(targetFile);
            if (computeMd5(existing).equals(md5)) {
                serverHashes.put(relativePath.toLowerCase(), md5);
                return;
            }
        }

        // Write new file
        Files.write(targetFile, data);
        serverHashes.put(relativePath.toLowerCase(), md5);

        // Load texture directly from the byte data (instant, no disk re-read needed)
        CustomIconCache.evictAndReloadFromBytes(relativePath, data);
    }

    /**
     * Handle a player head texture synced from server.
     * Receives raw skin PNG, composites head client-side, saves and registers.
     */
    private static void handleHeadSync(String path, byte[] data) {
        // path format: ".heads/playername.png"
        String nick = path.substring(".heads/".length()).replace(".png", "").toLowerCase();

        // Use SkinCache to composite and register the head
        SkinCache.compositeAndRegisterHead(nick, data);
    }

    /**
     * Verify a local icon file against the server's expected hash.
     * If tampered, restore from server data.
     * Called by CustomIconCache on each access.
     */
    public static boolean isServerManaged(String relativePath) {
        return serverHashes.containsKey(relativePath.replace('\\', '/').toLowerCase());
    }

    /**
     * Check if a server-managed file has been tampered with.
     * Returns true if file is valid (hash matches or not server-managed).
     */
    public static boolean validateFile(String relativePath, Path filePath) {
        String key = relativePath.replace('\\', '/').toLowerCase();
        String expectedMd5 = serverHashes.get(key);
        if (expectedMd5 == null) return true; // not server-managed

        try {
            if (!Files.exists(filePath)) return false;
            byte[] data = Files.readAllBytes(filePath);
            return computeMd5(data).equals(expectedMd5);
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────── Paths ───────────────────

    private static Path getIconsDir() {
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        Path configDir = loader.getConfigDir();
        return configDir.resolve("ptlore").resolve("ptdialogue");
    }


    private static String computeMd5(byte[] data) {
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

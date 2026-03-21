package com.joragaka.ptdialogue.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Loads and caches custom PNG icons from config/ptlore/ptdialogue/.
 * Hot-reloads files that change on disk.
 */
public class CustomIconCache {

    private static final String NAMESPACE = "ptdialogue";
    private static final Map<String, Identifier> textureCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastModifiedCache = new ConcurrentHashMap<>();

    // Lazily resolved icons dir
    private static volatile Path iconsDir;

    public static boolean isCustomIcon(String icon) {
        return icon != null && icon.toLowerCase().endsWith(".png");
    }

    public static Identifier getIconTextureId(String relativePath) {
        String key = relativePath.replace('\\', '/').toLowerCase();
        Path iconFile = getIconsDir().resolve(relativePath.replace('\\', '/'));

        if (!Files.exists(iconFile) || !Files.isRegularFile(iconFile)) {
            evictIfPresent(key);
            // If server manages this file, ask the server to resend it
            if (IconSyncHandler.isServerManaged(key)) {
                IconSyncHandler.requestIconFromServer(key);
            }
            return null;
        }

        // Tamper protection: if server-managed, verify hash
        if (IconSyncHandler.isServerManaged(key) && !IconSyncHandler.validateFile(key, iconFile)) {
            // File was tampered — evict so server will re-sync on next connection
            evictIfPresent(key);
            IconSyncHandler.requestIconFromServer(key);
            return null;
        }

        // Check modification time for hot-reload
        try {
            long currentMod = Files.getLastModifiedTime(iconFile).toMillis();
            Long cachedMod = lastModifiedCache.get(key);
            if (cachedMod != null && cachedMod != currentMod) {
                evictIfPresent(key);
            }
        } catch (Exception ignored) {}

        Identifier cached = textureCache.get(key);
        if (cached != null) return cached;

        // Synchronous load (local file — instant)
        return loadIconSync(key, iconFile);
    }

    private static Identifier loadIconSync(String key, Path filePath) {
        try {
            long lastMod = Files.getLastModifiedTime(filePath).toMillis();
            try (InputStream is = Files.newInputStream(filePath)) {
                NativeImage image = NativeImage.read(is);
                MinecraftClient client = MinecraftClient.getInstance();
                String idPath = "customicon/" + key.replaceAll("[^a-z0-9/._-]", "_");
                Identifier textureId = Identifier.of(NAMESPACE, idPath);

                try { client.getTextureManager().destroyTexture(textureId); } catch (Exception ignored) {}

                Supplier<String> nameSupplier = () -> "ptdialogue_icon_" + key;
                client.getTextureManager().registerTexture(textureId,
                        new NativeImageBackedTexture(nameSupplier, image));
                textureCache.put(key, textureId);
                lastModifiedCache.put(key, lastMod);
                return textureId;
            }
        } catch (Exception e) {
            System.err.println("[ptdialogue] Failed to load icon '" + key + "': " + e.getMessage());
            return null;
        }
    }

    private static void evictIfPresent(String key) {
        Identifier old = textureCache.remove(key);
        lastModifiedCache.remove(key);
        if (old != null) {
            try { MinecraftClient.getInstance().getTextureManager().destroyTexture(old); } catch (Exception ignored) {}
        }
    }

    /**
     * Force evict and reload an icon. Used by server sync after writing new file.
     * Ensures texture registration happens on the render thread.
     */
    public static void evictAndReload(String relativePath) {
        String key = relativePath.replace('\\', '/').toLowerCase();
        Path iconFile = getIconsDir().resolve(relativePath.replace('\\', '/'));

        MinecraftClient client = MinecraftClient.getInstance();
        Runnable doReload = () -> {
            evictIfPresent(key);
            if (Files.exists(iconFile)) {
                loadIconSync(key, iconFile);
            }
        };

        if (client.isOnThread()) {
            doReload.run();
        } else {
            client.execute(doReload);
        }
    }

    /**
     * Evict old texture and load directly from byte array (no disk re-read).
     * Used for instant sync when server sends icon data.
     */
    public static void evictAndReloadFromBytes(String relativePath, byte[] pngData) {
        String key = relativePath.replace('\\', '/').toLowerCase();

        MinecraftClient client = MinecraftClient.getInstance();
        Runnable doReload = () -> {
            evictIfPresent(key);
            try {
                NativeImage image = NativeImage.read(pngData);
                String idPath = "customicon/" + key.replaceAll("[^a-z0-9/._-]", "_");
                Identifier textureId = Identifier.of(NAMESPACE, idPath);

                try { client.getTextureManager().destroyTexture(textureId); } catch (Exception ignored) {}

                Supplier<String> nameSupplier = () -> "ptdialogue_icon_" + key;
                client.getTextureManager().registerTexture(textureId,
                        new NativeImageBackedTexture(nameSupplier, image));
                textureCache.put(key, textureId);
                lastModifiedCache.put(key, System.currentTimeMillis());
            } catch (Exception e) {
                System.err.println("[ptdialogue] Failed to load icon from bytes '" + key + "': " + e.getMessage());
            }
        };

        if (client.isOnThread()) {
            doReload.run();
        } else {
            client.execute(doReload);
        }
    }

    private static Path getIconsDir() {
        Path dir = iconsDir;
        if (dir == null) {
            dir = findIconsDir();
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            iconsDir = dir;
        }
        return dir;
    }

    private static Path findIconsDir() {
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        Path configDir = loader.getConfigDir();
        return configDir.resolve("ptlore").resolve("ptdialogue");
    }
}

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

    private static Identifier makeId(String namespace, String path) throws Exception {
        // Try reflection-friendly construction to handle different mappings where Identifier constructors may be non-public
        try {
            java.lang.reflect.Constructor<?> ctor = Identifier.class.getDeclaredConstructor(String.class, String.class);
            ctor.setAccessible(true);
            return (Identifier) ctor.newInstance(namespace, path);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = Identifier.class.getDeclaredMethod("tryParse", String.class);
            m.setAccessible(true);
            Object o = m.invoke(null, namespace + ":" + path);
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}
        try {
            // fallback to single-arg constructor if present
            java.lang.reflect.Constructor<?> ctor = Identifier.class.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            return (Identifier) ctor.newInstance(namespace + ":" + path);
        } catch (Throwable ignored) {}
        // Last resort: call public constructor if available
        try {
            return new Identifier(namespace + ":" + path);
        } catch (Throwable t) {
            throw new RuntimeException("Unable to create Identifier for " + namespace + ":" + path, t);
        }
    }

    private static Identifier createId(String namespace, String path) {
        try { return makeId(namespace, path); } catch (Throwable t) { return null; }
    }

    private static Identifier createId(String combined) {
        try {
            // try parsing combined string
            java.lang.reflect.Method m = Identifier.class.getDeclaredMethod("tryParse", String.class);
            m.setAccessible(true);
            Object o = m.invoke(null, combined);
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}
        try { return new Identifier(combined); } catch (Throwable ignored) {}
        return null;
    }

    private static Identifier safeIdentifier(String ns, String path) {
        Identifier id = createId(ns, path);
        if (id == null) id = createId(ns + ":" + path);
        if (id == null) throw new RuntimeException("Failed to build Identifier for: " + ns + ":" + path);
        return id;
    }

    private static Identifier safeIdentifier(String combined) {
        Identifier id = createId(combined);
        if (id == null) throw new RuntimeException("Failed to build Identifier: " + combined);
        return id;
    }

    private static Identifier loadIconSync_internal(String key, Path filePath) throws Exception {
        long lastMod = Files.getLastModifiedTime(filePath).toMillis();
        try (InputStream is = Files.newInputStream(filePath)) {
            NativeImage image = NativeImage.read(is);
            MinecraftClient client = MinecraftClient.getInstance();
            String idPath = "customicon/" + key.replaceAll("[^a-z0-9/._-]", "_");
            Identifier textureId = safeIdentifier(NAMESPACE, idPath);

            try { client.getTextureManager().destroyTexture(textureId); } catch (Exception ignored) {}

            // Register texture using a reflection-friendly helper to support multiple mappings
            registerNativeImageTexture(client, textureId, image);
            textureCache.put(key, textureId);
            lastModifiedCache.put(key, lastMod);
            return textureId;
        }
    }

    private static Identifier loadIconSync(String key, Path filePath) {
        try {
            return loadIconSync_internal(key, filePath);
        } catch (Exception e) {
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
                Identifier textureId = safeIdentifier(NAMESPACE, idPath);

                try { client.getTextureManager().destroyTexture(textureId); } catch (Exception ignored) {}

                registerNativeImageTexture(client, textureId, image);
                textureCache.put(key, textureId);
                lastModifiedCache.put(key, System.currentTimeMillis());
            } catch (Exception e) {
            }
        };

        if (client.isOnThread()) {
            doReload.run();
        } else {
            client.execute(doReload);
        }
    }

    // Reflection-friendly registration: try common constructors/fields for NativeImageBackedTexture across mappings
    private static void registerNativeImageTexture(MinecraftClient client, Identifier id, NativeImage image) throws Exception {
        Object textureObj = null;
        Class<?> nibClass = net.minecraft.client.texture.NativeImageBackedTexture.class;
        try {
            // Try constructor (NativeImage)
            try {
                java.lang.reflect.Constructor<?> c = nibClass.getConstructor(net.minecraft.client.texture.NativeImage.class);
                textureObj = c.newInstance(image);
            } catch (NoSuchMethodException ignored) {
                // try constructor (int,int,boolean)
                try {
                    java.lang.reflect.Constructor<?> c2 = nibClass.getConstructor(int.class, int.class, boolean.class);
                    // create instance and then try to set 'image' field
                    Object inst = c2.newInstance(image.getWidth(), image.getHeight(), Boolean.TRUE);
                    try {
                        java.lang.reflect.Field f = nibClass.getDeclaredField("image");
                        f.setAccessible(true);
                        f.set(inst, image);
                        textureObj = inst;
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        // ignore and fallback
                    }
                } catch (NoSuchMethodException ignored2) {
                    // try constructor (Supplier,String) or (Supplier,NativeImage) if present
                    for (var ctor : nibClass.getConstructors()) {
                        var params = ctor.getParameterTypes();
                        if (params.length == 2) {
                            try {
                                if (params[0] == java.util.function.Supplier.class && params[1] == net.minecraft.client.texture.NativeImage.class) {
                                    textureObj = ctor.newInstance((Supplier<String>)() -> id.toString(), image);
                                    break;
                                }
                            } catch (Throwable ignored3) {}
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // fallthrough to later attempt
        }

        if (textureObj == null) {
            // Last resort: instantiate via default constructor and set 'image' field if possible
            try {
                Object inst = nibClass.getDeclaredConstructor().newInstance();
                try {
                    java.lang.reflect.Field f = nibClass.getDeclaredField("image");
                    f.setAccessible(true);
                    f.set(inst, image);
                    textureObj = inst;
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }

        if (textureObj == null) {
            // As a hard fallback, register a texture using the provided NativeImage via the direct constructor if available
            // This will throw if unavailable, which the caller handles.
            try {
                client.getTextureManager().registerTexture(safeIdentifier(NAMESPACE, "fallback/" + System.identityHashCode(image)), new NativeImageBackedTexture(image));
            } catch (Throwable t) {
                // final fallback: attempt to register via reflection on the TextureManager
                try {
                    Object tm = client.getTextureManager();
                    for (java.lang.reflect.Method mm : tm.getClass().getMethods()) {
                        if (!mm.getName().equals("registerTexture")) continue;
                        if (mm.getParameterCount() != 2) continue;
                        try {
                            mm.invoke(tm, id, textureObj);
                            return;
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            return;
        }

        // register reflectively
        try {
            Object tm = client.getTextureManager();
            try {
                // Try typed call first
                java.lang.reflect.Method reg = tm.getClass().getMethod("registerTexture", Identifier.class, textureObj.getClass());
                reg.invoke(tm, id, textureObj);
                return;
            } catch (Throwable ignored) {}
            // Fallback: find any registerTexture method with 2 params and invoke
            for (java.lang.reflect.Method mm : tm.getClass().getMethods()) {
                if (!mm.getName().equals("registerTexture")) continue;
                if (mm.getParameterCount() != 2) continue;
                try {
                    mm.invoke(tm, id, textureObj);
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // final fallback: try original typed registration
            try { client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image)); } catch (Throwable ignored) {}
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

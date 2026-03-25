package com.joragaka.ptdialogue.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.List;
import java.util.ArrayList;

/**
 * Fetches player skins via Mojang API, composites face+hat into a single
 * small head texture, and caches only that head PNG on disk.
 */
public class SkinCache {

    private static final Map<String, Identifier> headTextureCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final Map<String, String> cachedSkinUrls = new ConcurrentHashMap<>();
    private static final Map<String, String> uuidCache = new ConcurrentHashMap<>();
    // reverse lookup: uuid -> latest known username
    private static final Map<String, String> uuidToName = new ConcurrentHashMap<>();
    private static final Set<String> loadingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Separate lock for direct-URL downloads so they aren't blocked by fetchSkinAsync (Mojang pipeline)
    private static final Set<String> directUrlLoadingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Track recent failures to avoid repeated attempts/log spam when skins can't be loaded
    private static final Map<String, Long> failureTimestamps = new ConcurrentHashMap<>();
    private static final long FAILURE_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes
    // Rate-limit tryRegisterHeadFromPlayerListEntry — skip expensive reflection for already-cached keys
    private static final Map<String, Long> pleRegisterTimestamps = new ConcurrentHashMap<>();
    private static final long PLE_REGISTER_COOLDOWN_MS = 2000; // 2 seconds
    private static final String NAMESPACE = "ptdialogue";

    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 min

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    private static final int FACE_OUT = 16;
    private static final int HAT_OUT = 18;

    // Lazily resolved config dir
    private static volatile Path cacheDir;
    // Listeners waiting for a head texture to be available for a given player key
    private static final Map<String, List<Consumer<Identifier>>> headListeners = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> headOverlayMap = new ConcurrentHashMap<>();

    // Keep last seen in-memory skin hash per key to detect changes
    private static final Map<String, Integer> skinImageHash = new ConcurrentHashMap<>();

    // Keep last seen skin texture location (Identifier or URL string) per key to detect changes
    private static final Map<String, String> skinTextureLocation = new ConcurrentHashMap<>();

    /** Return last seen skin texture location string (Identifier#toString() or URL), or null if unknown. */
    public static String getLastKnownSkinTextureLocation(String playerKey) {
        if (playerKey == null) return null;
        return skinTextureLocation.get(playerKey.toLowerCase());
    }

    /** Set last known skin texture location string for player key. */
    public static void setLastKnownSkinTextureLocation(String playerKey, String loc) {
        if (playerKey == null) return;
        if (loc == null) skinTextureLocation.remove(playerKey.toLowerCase());
        else skinTextureLocation.put(playerKey.toLowerCase(), loc);
    }

    /**
     * Try to probe a skin texture location string from the provided object.
     * Returns Identifier.toString() or a URL string when found, otherwise null.
     */
    public static String probeSkinTextureLocationFromObject(Object obj) {
        if (obj == null) return null;
        try {
            // If it's an Identifier directly
            if (obj instanceof net.minecraft.util.Identifier) return obj.toString();
            // If it's a String directly (possibly a URL or identifier)
            if (obj instanceof String) return (String) obj;

            Class<?> c = obj.getClass();
            // Inspect no-arg methods first
            for (var m : c.getMethods()) {
                try {
                    if (m.getParameterCount() != 0) continue;
                    Class<?> rt = m.getReturnType();
                    Object val = null;
                    if (rt == net.minecraft.util.Identifier.class) {
                        try { val = m.invoke(obj); } catch (Throwable ignored) {}
                        if (val != null) return val.toString();
                    } else if (rt == String.class) {
                        try { val = m.invoke(obj); } catch (Throwable ignored) {}
                        if (val instanceof String) {
                            String s = (String) val;
                            if (!s.isEmpty()) return s;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // Inspect fields as fallback
            for (var f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    if (v instanceof net.minecraft.util.Identifier) return v.toString();
                    if (v instanceof String) {
                        String s = (String) v;
                        if (!s.isEmpty()) return s;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Compute a simple hash from NativeImage pixels (fast, not cryptographically strong).
     */
    private static int computeNativeImageHash(net.minecraft.client.texture.NativeImage img) {
        if (img == null) return 0;
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            int hash = 1;
            // sample some pixels rather than entire image for speed
            int sx = Math.max(1, w / 8);
            int sy = Math.max(1, h / 8);
            for (int y = 0; y < h; y += sy) {
                for (int x = 0; x < w; x += sx) {
                    int p = nativeGetPixel(img, x, y);
                    hash = 31 * hash + p;
                }
            }
            return hash;
        } catch (Throwable t) {
            return System.identityHashCode(img);
        }
    }

    // ──────────────────────────── public API ────────────────────────────

    public static void preload(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        if (playerName.toLowerCase().endsWith(".png")) return;

        String key = playerName.toLowerCase();

        // If we recently failed to load this skin, skip attempts until cooldown expires
        Long lastFail = failureTimestamps.get(key);
        if (lastFail != null && (System.currentTimeMillis() - lastFail) < FAILURE_COOLDOWN_MS) return;

        Long ts = cacheTimestamps.get(key);
        if (ts != null && (System.currentTimeMillis() - ts) < CACHE_TTL_MS && headTextureCache.containsKey(key)) {
            return;
        }

        if (!headTextureCache.containsKey(key)) {
            loadHeadFromDiskCache(key);
        }

        if (loadingSet.add(key)) {
            fetchSkinAsync(key, playerName);
        }
    }

    /**
     * Ensure head texture exists (async). If already present, callback is invoked synchronously on caller thread.
     * Otherwise the callback will be invoked on the client thread once the head texture is registered.
     */
    public static void ensureHeadTexture(String playerName, java.util.function.Consumer<Identifier> callback) {
        if (playerName == null) {
            if (callback != null) callback.accept(null);
            return;
        }
        String key = playerName.toLowerCase();
        Identifier existing = headTextureCache.get(key);
        if (existing != null) {
            if (callback != null) callback.accept(existing);
            return;
        }

        // Add listener
        headListeners.compute(key, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            if (callback != null) list.add(callback);
            return list;
        });

        // Trigger fetch (if not already loading)
        if (loadingSet.add(key)) {
            fetchSkinAsync(key, playerName);
        }
    }

    public static Identifier getHeadTextureId(String playerName) {
        if (playerName == null) return null;
        String key = playerName.toLowerCase();

        // If we recently failed to load this skin, avoid retrying immediately
        Long lastFail = failureTimestamps.get(key);
        if (lastFail != null && (System.currentTimeMillis() - lastFail) < FAILURE_COOLDOWN_MS) {
            return headTextureCache.get(key);
        }

        Identifier cached = headTextureCache.get(key);
        if (cached != null) {
            Long ts = cacheTimestamps.get(key);
            if ((ts == null || System.currentTimeMillis() - ts >= CACHE_TTL_MS) && loadingSet.add(key)) {
                fetchSkinAsync(key, playerName);
            }
            return cached;
        }

        loadHeadFromDiskCache(key);
        cached = headTextureCache.get(key);
        if (cached != null) {
            if (loadingSet.add(key)) {
                fetchSkinAsync(key, playerName);
            }
            return cached;
        }

        if (loadingSet.add(key)) {
            fetchSkinAsync(key, playerName);
        }
        return null;
    }

    /**
     * Inject a head texture from external source (e.g. server sync).
     * Called by IconSyncHandler when a head is received from server.
     */
    public static void injectHeadTexture(String nick, Identifier textureId) {
        String key = nick.toLowerCase();
        headTextureCache.put(key, textureId);
        cacheTimestamps.put(key, System.currentTimeMillis());
        // Clear any recorded failure for this key — we now have a texture
        failureTimestamps.remove(key);
    }

    /**
     * Download skin PNG from a direct URL (e.g. from Skin Restorer) and register a composited head.
     * Only downloads when the URL differs from the last cached value for this key.
     * Safe to call every tick — skips if URL unchanged and head is already registered.
     */
    public static void tryRegisterHeadFromDirectUrl(String skinUrl, String key) {
        if (skinUrl == null || key == null || skinUrl.isEmpty()) return;
        String k = key.toLowerCase();
        String prev = cachedSkinUrls.get(k);
        if (skinUrl.equals(prev) && headTextureCache.containsKey(k)) return; // nothing new
        if (!directUrlLoadingSet.add(k)) return; // already downloading via direct URL
        cachedSkinUrls.put(k, skinUrl);
        try {
            HTTP_CLIENT.sendAsync(
                    HttpRequest.newBuilder().uri(URI.create(skinUrl)).timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            ).thenAccept(resp -> {
                try {
                    if (resp != null && resp.statusCode() == 200) {
                        byte[] pngBytes = resp.body();
                        NativeImage headImage = processAndSaveHead(k, pngBytes);
                        if (headImage != null) {
                            registerHeadTexture(k, headImage);
                            cacheTimestamps.put(k, System.currentTimeMillis());
                            failureTimestamps.remove(k);
                        }
                    }
                } finally {
                    directUrlLoadingSet.remove(k);
                }
            }).exceptionally(ex -> {
                directUrlLoadingSet.remove(k);
                return null;
            });
        } catch (Throwable t) {
            loadingSet.remove(k);
        }
    }

    /**
     * Immediately composite a head with overlay for the given PlayerListEntry by reading
     * Minecraft's already-downloaded skin from the HttpTexture disk cache.
     * No HTTP request — the skin file is already on disk since Minecraft downloaded it when
     * the SkinTextures object was created. Saves the composited head PNG and registers GPU texture.
     */
    public static void compositeHeadImmediately(net.minecraft.client.network.PlayerListEntry ple, String playerName) {
        if (ple == null || playerName == null) return;
        String key = playerName.toLowerCase();
        try {
            // Get the skin texture Identifier from SkinTextures by scanning all no-arg Identifier methods.
            // Use reflection to avoid compile-time dependency on mappings that may not have getSkinTextures().
            Object st = null;
            try {
                java.lang.reflect.Method gm = ple.getClass().getMethod("getSkinTextures");
                st = gm.invoke(ple);
            } catch (NoSuchMethodException nsme) {
                st = null;
            } catch (Throwable ignored) {}
            if (st == null) return;
            Identifier skinId = null;
            for (java.lang.reflect.Method m : st.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != Identifier.class) continue;
                try {
                    Object v = m.invoke(st);
                    if (v instanceof Identifier) { skinId = (Identifier) v; break; }
                } catch (Throwable ignored) {}
            }
            if (skinId == null) return;

            // Look up the texture in TextureManager and find the HttpTexture cache file
            var client = MinecraftClient.getInstance();
            if (client == null) return;
            var tex = client.getTextureManager().getTexture(skinId);
            if (tex == null) return;

            // Walk class hierarchy to find a File field (HttpTexture stores skin PNG in a cache file)
            Class<?> cls = tex.getClass();
            while (cls != null && cls != Object.class) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (!java.io.File.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        java.io.File cacheFile = (java.io.File) f.get(tex);
                        if (cacheFile == null || !cacheFile.exists() || cacheFile.length() == 0) continue;
                        byte[] skinBytes = Files.readAllBytes(cacheFile.toPath());
                        NativeImage head = processAndSaveHead(key, skinBytes);
                        if (head != null) {
                            registerHeadTexture(key, head);
                            cacheTimestamps.put(key, System.currentTimeMillis());
                            failureTimestamps.remove(key);
                        }
                        return;
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Force a refresh of the given player's head texture by invalidating local caches
     * and triggering the normal fetch pipeline. Useful when the local player's skin
     * changes at runtime and we need to refresh the displayed head immediately.
     */
    public static void forceRefresh(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        String key = playerName.toLowerCase();

        // Capture and remove any registered texture id so UI will stop using it immediately
        Identifier existing = headTextureCache.remove(key);
        // Also remove any username -> texture mappings that point to the same id
        try {
            for (var entry : new ArrayList<>(headTextureCache.entrySet())) {
                if (entry.getKey() != null && entry.getKey().equals(key)) continue;
                if (existing != null && existing.equals(entry.getValue())) {
                    headTextureCache.remove(entry.getKey());
                }
            }
        } catch (Throwable ignored) {}

        cacheTimestamps.remove(key);
        cachedSkinUrls.remove(key);
        failureTimestamps.remove(key);
        skinImageHash.remove(key);
        headOverlayMap.remove(key);

        // Delete disk cache so the old skin is not immediately reloaded on next render
        try { Files.deleteIfExists(getHeadDiskCachePath(key)); } catch (Throwable ignored) {}

        // Ensure the actual GPU texture is destroyed on the client thread so the old icon is freed
        if (existing != null) {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                Runnable destroy = () -> {
                    try { client.getTextureManager().destroyTexture(existing); } catch (Throwable ignored) {}
                };
                if (client != null) {
                    if (client.isOnThread()) destroy.run();
                    else client.execute(destroy);
                }
            } catch (Throwable ignored) {}
        }

        // Try to immediately register a head from PlayerListEntry (covers SkinRestorer in-memory case).
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                var ple = client.getNetworkHandler().getPlayerListEntry(playerName);
                if (ple != null) {
                    try {
                        boolean ok = tryRegisterHeadFromPlayerListEntry(ple, playerName);
                        if (ok) {
                            return; // done — we registered a new head from in-memory texture
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // Otherwise start fetching the new skin (if not already running)
        if (loadingSet.add(key)) {
            fetchSkinAsync(key, playerName);
        }
    }

    /**
     * Composite a head from raw skin PNG bytes, save to disk, register GPU texture.
     * Used by IconSyncHandler when receiving raw skin from server.
     * Returns the composited NativeImage, or null on failure.
     */
    public static NativeImage compositeAndRegisterHead(String nick, byte[] skinPngBytes) {
        String key = nick.toLowerCase();
        NativeImage head = processAndSaveHead(key, skinPngBytes);
        if (head == null) return null;
        registerHeadTexture(key, head);
        failureTimestamps.remove(key);
        return head;
    }

    /**
     * Check whether the cached/composited head for the given player key included an overlay layer.
     * Returns false if unknown or overlay not present.
     */
    public static boolean headHasOverlay(String playerName) {
        if (playerName == null) return false;
        Boolean b = headOverlayMap.get(playerName.toLowerCase());
        return b != null && b.booleanValue();
    }

    /**
     * Copy overlay-flag from one key to another (both keys are normalized lower-case internally).
     * Useful when server sends a head keyed by UUID but the client resolves the player by name.
     */
    public static void copyOverlayFlag(String fromKey, String toKey) {
        if (fromKey == null || toKey == null) return;
        try {
            Boolean v = headOverlayMap.get(fromKey.toLowerCase());
            if (v != null) headOverlayMap.put(toKey.toLowerCase(), v);
        } catch (Throwable ignored) {}
    }

    // ──────────────────────── disk cache ────────────────────────

    private static Path getCacheDir() {
        Path dir = cacheDir;
        if (dir == null) {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            Path configDir = loader.getConfigDir();
            dir = configDir.resolve("ptlore").resolve("ptdialogue").resolve(".skincache").resolve("heads");
            cacheDir = dir;
        }
        return dir;
    }

    private static Path getHeadDiskCachePath(String key) {
        return getCacheDir().resolve(key + ".png");
    }

    private static void loadHeadFromDiskCache(String key) {
        Path cached = getHeadDiskCachePath(key);
        if (!Files.exists(cached)) return;

        try {
            byte[] pngBytes = Files.readAllBytes(cached);
            NativeImage headImage = NativeImage.read(pngBytes);
            if (headImage == null) return;
            // If the cached file is a raw skin (larger than head size), it's an old-format file.
            // Delete it so the fresh composited version is fetched on next load.
            if (headImage.getWidth() != HAT_OUT || headImage.getHeight() != HAT_OUT) {
                headImage.close();
                try { Files.delete(cached); } catch (Throwable ignored) {}
                return;
            }
            registerHeadTexture(key, headImage);
            cacheTimestamps.put(key, System.currentTimeMillis());
            failureTimestamps.remove(key);
        } catch (Exception e) {
            failureTimestamps.put(key, System.currentTimeMillis());
        }
    }

    // ──────────────────── head compositing ────────────────────

    /**
     * Composites face + hat overlay from a skin PNG into a single 18×18 NativeImage.
     * Face 8×8 scaled to 16×16, centered at (1,1).
     * Hat 8×8 scaled to 18×18, covering full canvas.
     * Uses only NativeImage (no AWT) for compatibility with Minecraft's bundled JRE.
     */
    private static NativeImage compositeHead(String key, byte[] skinPngBytes) {
        try {
            NativeImage skin = NativeImage.read(skinPngBytes);
            if (skin == null) return null;
            // Compute and remember a lightweight hash of the raw skin image so we can detect changes
            try {
                int skinHash = computeNativeImageHash(skin);
                if (key != null) skinImageHash.put(key.toLowerCase(), skinHash);
            } catch (Throwable ignored) {}

            int skinW = skin.getWidth();
            int skinH = skin.getHeight();
            boolean hasOverlay = false;
            if (skinW >= 64 && skinH >= 64) {
                // scan the standard overlay region (40..47, 8..15) for any non-transparent pixel
                outer: for (int yy = 8; yy < 16; yy++) {
                    for (int xx = 40; xx < 48; xx++) {
                        int p = nativeGetPixel(skin, xx, yy);
                        int a = (p >>> 24) & 0xFF;
                        if (a != 0) { hasOverlay = true; break outer; }
                    }
                }
            }
            // record overlay presence for this key
            try { if (key != null) headOverlayMap.put(key.toLowerCase(), hasOverlay); } catch (Throwable ignored) {}

            NativeImage head = new NativeImage(HAT_OUT, HAT_OUT, true);

            // Step 1: Draw face 8×8 → 16×16 at offset (1,1)
            for (int y = 0; y < FACE_OUT; y++) {
                for (int x = 0; x < FACE_OUT; x++) {
                    // nearest-neighbor: map (0..15) → (0..7)
                    int srcX = x * 8 / FACE_OUT;
                    int srcY = y * 8 / FACE_OUT;
                    int pixel = nativeGetPixel(skin, 8 + srcX, 8 + srcY);
                    nativeSetPixel(head, 1 + x, 1 + y, pixel);
                }
            }

            // Step 2: Draw hat overlay 8×8 → 18×18 over entire canvas (alpha blend)
            for (int y = 0; y < HAT_OUT; y++) {
                for (int x = 0; x < HAT_OUT; x++) {
                    int srcX = x * 8 / HAT_OUT;
                    int srcY = y * 8 / HAT_OUT;
                    int hatPixel = nativeGetPixel(skin, 40 + srcX, 8 + srcY);
                    int hatA = (hatPixel >>> 24) & 0xFF;

                    if (hatA == 0) continue; // fully transparent — skip

                    if (hatA == 255) {
                        // Fully opaque — overwrite
                        nativeSetPixel(head, x, y, hatPixel);
                    } else {
                        // Alpha blend hat over existing pixel
                        int base = nativeGetPixel(head, x, y);
                        nativeSetPixel(head, x, y, alphaBlend(base, hatPixel));
                    }
                }
            }

            skin.close();
            return head;
        } catch (Exception e) {
            // Log once and mark failure; avoid repeated stack spam
            // Use failure map key unknown here, caller will mark failure when appropriate
            return null;
        }
    }

    /** Alpha-blend foreground over background (both ARGB). */
    private static int alphaBlend(int bg, int fg) {
        int fa = (fg >>> 24) & 0xFF;
        int fr = (fg >> 16) & 0xFF, fgG = (fg >> 8) & 0xFF, fb = fg & 0xFF;
        int ba = (bg >>> 24) & 0xFF;
        int br = (bg >> 16) & 0xFF, bgG = (bg >> 8) & 0xFF, bb = bg & 0xFF;

        float af = fa / 255f;
        int ra = Math.max(ba, fa);
        int rr = (int)(fr * af + br * (1 - af));
        int rg = (int)(fgG * af + bgG * (1 - af));
        int rb = (int)(fb * af + bb * (1 - af));

        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    /** Composite head from skin bytes, save the composited head PNG to disk, return NativeImage for GPU. */
    private static NativeImage processAndSaveHead(String key, byte[] skinPngBytes) {
        NativeImage head = compositeHead(key, skinPngBytes);
        if (head == null) return null;
        saveComposedHeadToDisk(key, head);
        return head;
    }

    /** Save a composited head NativeImage as PNG to the disk cache. */
    private static void saveComposedHeadToDisk(String key, NativeImage head) {
        try {
            Path path = getHeadDiskCachePath(key);
            Files.createDirectories(path.getParent());
            int w = head.getWidth(), h = head.getHeight();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    // NativeImage stores pixels as ABGR; BufferedImage.setRGB expects ARGB — swap R and B
                    int abgr = nativeGetPixel(head, x, y);
                    int a = (abgr >> 24) & 0xFF;
                    int b = (abgr >> 16) & 0xFF;
                    int g = (abgr >> 8) & 0xFF;
                    int r = abgr & 0xFF;
                    bi.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            try (var os = Files.newOutputStream(path)) {
                ImageIO.write(bi, "PNG", os);
            }
        } catch (Throwable ignored) {
            failureTimestamps.put(key, System.currentTimeMillis());
        }
    }


    /**
     * Check whether the given in-memory skin NativeImage differs from the on-disk cached PNG for key.
     * Returns true if file missing or hashes differ, false if equal.
     */
    private static boolean isSkinDifferentFromDisk(String key, net.minecraft.client.texture.NativeImage skin) {
        if (key == null || skin == null) return true;
        try {
            Path p = getHeadDiskCachePath(key.toLowerCase());
            if (!Files.exists(p)) return true;
            try {
                byte[] bytes = Files.readAllBytes(p);
                net.minecraft.client.texture.NativeImage disk = net.minecraft.client.texture.NativeImage.read(bytes);
                if (disk == null) return true;
                try {
                    int dh = computeNativeImageHash(disk);
                    int sh = computeNativeImageHash(skin);
                    return dh != sh;
                } finally {
                    try { disk.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                return true;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    // ──────────────── texture registration ─────────────

    // Helper: create Identifier reflectively (avoid compile-time constructor calls)
    private static Identifier createIdentifierReflective(String namespace, String path) {
        try {
            Class<?> idClass = net.minecraft.util.Identifier.class;
            try {
                java.lang.reflect.Constructor<?> ctor = idClass.getDeclaredConstructor(String.class, String.class);
                ctor.setAccessible(true);
                return (Identifier) ctor.newInstance(namespace, path);
            } catch (Throwable ignored) {}
            // try single-string constructor
            try {
                java.lang.reflect.Constructor<?> ctor2 = idClass.getDeclaredConstructor(String.class);
                ctor2.setAccessible(true);
                return (Identifier) ctor2.newInstance(namespace + ":" + path);
            } catch (Throwable ignored) {}
            // try static parser
            try {
                java.lang.reflect.Method m = idClass.getMethod("tryParse", String.class);
                Object o = m.invoke(null, namespace + ":" + path);
                if (o instanceof Identifier) return (Identifier) o;
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        // last resort: attempt using public constructor (may not exist in some mappings)
        try {
            return new Identifier(namespace + ":" + path);
        } catch (Throwable ignored) {}
        throw new RuntimeException("Unable to construct Identifier: " + namespace + ":" + path);
    }

    // NativeImage pixel helpers using only reflection (avoid direct calls)
    private static java.lang.reflect.Method nativeGetPixelMethod = null;
    private static java.lang.reflect.Method nativeSetPixelMethod = null;

    private static int nativeGetPixel(net.minecraft.client.texture.NativeImage img, int x, int y) {
        if (img == null) return 0;
        try {
            if (nativeGetPixelMethod == null) {
                Class<?> c = net.minecraft.client.texture.NativeImage.class;
                for (var m : c.getMethods()) {
                    if (m.getParameterCount() == 2 && m.getReturnType() == int.class) {
                        var ps = m.getParameterTypes();
                        if (ps[0] == int.class && ps[1] == int.class) {
                            nativeGetPixelMethod = m;
                            break;
                        }
                    }
                }
            }
            if (nativeGetPixelMethod != null) return (int) nativeGetPixelMethod.invoke(img, x, y);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void nativeSetPixel(net.minecraft.client.texture.NativeImage img, int x, int y, int color) {
        if (img == null) return;
        try {
            if (nativeSetPixelMethod == null) {
                Class<?> c = net.minecraft.client.texture.NativeImage.class;
                for (var m : c.getMethods()) {
                    if (m.getParameterCount() == 3 && m.getReturnType() == void.class) {
                        var ps = m.getParameterTypes();
                        if (ps[0] == int.class && ps[1] == int.class && ps[2] == int.class) {
                            nativeSetPixelMethod = m;
                            break;
                        }
                    }
                }
            }
            if (nativeSetPixelMethod != null) nativeSetPixelMethod.invoke(img, x, y, color);
        } catch (Throwable ignored) {}
    }

    /**
     * Check whether a Minecraft skin NativeImage has any non-transparent pixels
     * in the hat/overlay region (x=40..47, y=8..15 in 64x64 skin).
     */
    private static boolean nativeImageHasOverlay(net.minecraft.client.texture.NativeImage img) {
        if (img == null) return false;
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w < 48 || h < 16) return false;
            for (int y = 8; y < 16; y++) {
                for (int x = 40; x < 48; x++) {
                    int pixel = nativeGetPixel(img, x, y);
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha > 0) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // Register head texture using reflection-safe helper
    private static void registerHeadTexture(String key, NativeImage headImage) {
        MinecraftClient client = MinecraftClient.getInstance();
        Identifier textureId = null;
        try {
            textureId = createIdentifierReflective(NAMESPACE, "skin_head/" + key);
        } catch (Throwable ignored) {}
        if (textureId == null) textureId = new Identifier(NAMESPACE + ":skin_head/" + key);

        final Identifier finalTextureId = textureId;
        Runnable register = () -> {
            try {
                try { client.getTextureManager().destroyTexture(finalTextureId); } catch (Exception ignored) {}
                try {
                    // Use reflection-based registration which handles various mappings
                    registerNativeImageTexture(client, finalTextureId, headImage);
                } catch (Throwable t) {
                    // fallback: try typed constructor registration (may fail on some mappings)
                    try { client.getTextureManager().registerTexture(finalTextureId, new NativeImageBackedTexture(headImage)); } catch (Throwable ignored) {}
                }
                headTextureCache.put(key, finalTextureId);
                // successful registration -> clear failure mark
                failureTimestamps.remove(key);
                // If we know a username for this uuid, also map username -> same texture id
                try {
                    String knownName = uuidToName.get(key);
                    if (knownName != null && !knownName.isEmpty()) {
                        headTextureCache.put(knownName.toLowerCase(), finalTextureId);
                        cacheTimestamps.put(knownName.toLowerCase(), System.currentTimeMillis());
                    }
                } catch (Throwable ignored) {}
                // Debug: one-time notice that head texture was registered
                // Notify listeners waiting for this head
                List<Consumer<Identifier>> listeners = headListeners.remove(key);
                if (listeners != null) {
                    for (var c : listeners) {
                        try { c.accept(finalTextureId); } catch (Throwable ignored) {}
                    }
                }
            } catch (Exception e) {
                // register failed -> mark failure so we won't spam retries
                failureTimestamps.put(key, System.currentTimeMillis());
            }
        };
        if (client.isOnThread()) register.run();
        else client.execute(register);
    }

    /**
     * Composite a head from an already-loaded NativeImage (skin) and return the head image.
     * Similar logic to compositeHead(byte[]), but avoids re-reading PNG bytes.
     */
    private static NativeImage compositeHeadFromNativeImage(NativeImage skin, String key) {
        if (skin == null) return null;
        try {
            // Compute and remember a lightweight hash of the raw skin image so we can detect changes
            try {
                int skinHash = computeNativeImageHash(skin);
                if (key != null) skinImageHash.put(key.toLowerCase(), skinHash);
            } catch (Throwable ignored) {}
            int skinW = skin.getWidth();
            int skinH = skin.getHeight();
            boolean hasOverlay = false;
            if (skinW >= 64 && skinH >= 64) {
                outer: for (int yy = 8; yy < 16; yy++) {
                    for (int xx = 40; xx < 48; xx++) {
                        int p = nativeGetPixel(skin, xx, yy);
                        int a = (p >>> 24) & 0xFF;
                        if (a != 0) { hasOverlay = true; break outer; }
                    }
                }
            }
            try { if (key != null) headOverlayMap.put(key.toLowerCase(), hasOverlay); } catch (Throwable ignored) {}

            NativeImage head = new NativeImage(HAT_OUT, HAT_OUT, true);
            // face
            for (int y = 0; y < FACE_OUT; y++) {
                for (int x = 0; x < FACE_OUT; x++) {
                    int srcX = x * 8 / FACE_OUT;
                    int srcY = y * 8 / FACE_OUT;
                    int pixel = nativeGetPixel(skin, 8 + srcX, 8 + srcY);
                    nativeSetPixel(head, 1 + x, 1 + y, pixel);
                }
            }
            // hat — always attempt overlay pass (no-op if fully transparent)
            for (int y = 0; y < HAT_OUT; y++) {
                for (int x = 0; x < HAT_OUT; x++) {
                    int srcX = x * 8 / HAT_OUT;
                    int srcY = y * 8 / HAT_OUT;
                    int hatPixel = nativeGetPixel(skin, 40 + srcX, 8 + srcY);
                    int hatA = (hatPixel >>> 24) & 0xFF;
                    if (hatA == 0) continue;
                    if (hatA == 255) nativeSetPixel(head, x, y, hatPixel);
                    else {
                        int base = nativeGetPixel(head, x, y);
                        nativeSetPixel(head, x, y, alphaBlend(base, hatPixel));
                    }
                }
            }
            return head;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Try to detect whether the provided object (NativeImage or wrapper) contains a skin overlay region
     * with any non-transparent pixels. Returns Boolean.TRUE if overlay detected, Boolean.FALSE if not,
     * or null if no image could be found.
     */
    public static Boolean probeNativeImageHasOverlayFromObject(Object obj) {
        if (obj == null) return null;
        try {
            // If object itself is a NativeImage
            if (obj instanceof net.minecraft.client.texture.NativeImage) {
                return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) obj);
            }
            // If object is NativeImageBackedTexture
            if (obj instanceof net.minecraft.client.texture.NativeImageBackedTexture) {
                try {
                    java.lang.reflect.Method m = net.minecraft.client.texture.NativeImageBackedTexture.class.getMethod("getImage");
                    Object o = m.invoke(obj);
                    if (o instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) o);
                } catch (NoSuchMethodException nsme) {
                    try {
                        java.lang.reflect.Field f = net.minecraft.client.texture.NativeImageBackedTexture.class.getDeclaredField("image");
                        f.setAccessible(true);
                        Object o = f.get(obj);
                        if (o instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) o);
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }

            Class<?> c = obj.getClass();
            // Inspect fields for NativeImage or NativeImageBackedTexture
            for (var f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    if (v instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) v);
                    if (v instanceof net.minecraft.client.texture.NativeImageBackedTexture) {
                        try {
                            java.lang.reflect.Method m = net.minecraft.client.texture.NativeImageBackedTexture.class.getMethod("getImage");
                            Object o = m.invoke(v);
                            if (o instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) o);
                        } catch (NoSuchMethodException nsme) {
                            try {
                                java.lang.reflect.Field fi = net.minecraft.client.texture.NativeImageBackedTexture.class.getDeclaredField("image");
                                fi.setAccessible(true);
                                Object o = fi.get(v);
                                if (o instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) o);
                            } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }

            // Inspect methods that may return NativeImage or NativeImageBackedTexture
            for (var m : c.getMethods()) {
                try {
                    if (m.getParameterCount() != 0) continue;
                    var rt = m.getReturnType();
                    if (rt == net.minecraft.client.texture.NativeImage.class || rt == net.minecraft.client.texture.NativeImageBackedTexture.class) {
                        try {
                            Object val = m.invoke(obj);
                            if (val == null) continue;
                            if (val instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) val);
                            if (val instanceof net.minecraft.client.texture.NativeImageBackedTexture) {
                                try {
                                    java.lang.reflect.Method mm = net.minecraft.client.texture.NativeImageBackedTexture.class.getMethod("getImage");
                                    Object o = mm.invoke(val);
                                    if (o instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) o);
                                } catch (NoSuchMethodException nsme) {
                                    try {
                                        java.lang.reflect.Field fi = net.minecraft.client.texture.NativeImageBackedTexture.class.getDeclaredField("image");
                                        fi.setAccessible(true);
                                        Object o = fi.get(val);
                                        if (o instanceof net.minecraft.client.texture.NativeImage) return nativeImageHasOverlay((net.minecraft.client.texture.NativeImage) o);
                                    } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Try to register a head texture for `key` using an existing skin texture already loaded in
     * the client's TextureManager (identified by skinTextureId). Returns true on success (i.e. when
     * a new head was registered or an existing mapping was confirmed).
     */
    public static boolean tryRegisterHeadFromTexture(net.minecraft.util.Identifier skinTextureId, String key) {
        if (skinTextureId == null || key == null) return false;
        try {
            var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client == null) return false;
            var tex = client.getTextureManager().getTexture(skinTextureId);
            if (tex == null) return false;
            // Common case: NativeImageBackedTexture which exposes a NativeImage we can read
            try {
                if (tex instanceof net.minecraft.client.texture.NativeImageBackedTexture) {
                    net.minecraft.client.texture.NativeImageBackedTexture nib = (net.minecraft.client.texture.NativeImageBackedTexture) tex;
                    net.minecraft.client.texture.NativeImage skin = null;
                    // Use reflection to access the backing NativeImage (getImage may be present)
                    try {
                        java.lang.reflect.Method m = net.minecraft.client.texture.NativeImageBackedTexture.class.getMethod("getImage");
                        Object o = m.invoke(nib);
                        if (o instanceof net.minecraft.client.texture.NativeImage) skin = (net.minecraft.client.texture.NativeImage) o;
                    } catch (NoSuchMethodException nsme) {
                        try {
                            java.lang.reflect.Field f = net.minecraft.client.texture.NativeImageBackedTexture.class.getDeclaredField("image");
                            f.setAccessible(true);
                            Object o = f.get(nib);
                            if (o instanceof net.minecraft.client.texture.NativeImage) skin = (net.minecraft.client.texture.NativeImage) o;
                        } catch (Throwable ignored) {}
                    }

                    if (skin == null) return false;

                    int hash = computeNativeImageHash(skin);
                    Integer prev = skinImageHash.get(key.toLowerCase());
                    boolean currentHasOverlay = nativeImageHasOverlay(skin);
                    Boolean knownOverlay = headOverlayMap.get(key.toLowerCase());
                    if (prev != null && prev.intValue() == hash && headTextureCache.containsKey(key.toLowerCase()) && (knownOverlay != null && knownOverlay.booleanValue() == currentHasOverlay)) {
                        // nothing changed (both pixel hash and overlay presence match)
                        return true;
                    }

                    NativeImage head = compositeHeadFromNativeImage(skin, key);
                    if (head == null) return false;
                    // Force save the in-memory skin to disk so any external readers see the updated PNG
                    try { saveComposedHeadToDisk(key, head); } catch (Throwable ignored) {}
                    // register (will destroy previous texture id with same name)
                    registerHeadTexture(key, head);
                    cacheTimestamps.put(key.toLowerCase(), System.currentTimeMillis());
                    failureTimestamps.remove(key.toLowerCase());
                    skinImageHash.put(key.toLowerCase(), hash);
                    return true;
                }
            } catch (Throwable ignored) {}

            // Also, if texture is some other implementation, inspect its fields for a NativeImage or NativeImageBackedTexture
            try {
                Class<?> tc = tex.getClass();
                for (var f : tc.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object fv = f.get(tex);
                        if (fv == null) continue;
                        if (fv instanceof net.minecraft.client.texture.NativeImageBackedTexture) {
                            net.minecraft.client.texture.NativeImageBackedTexture nib = (net.minecraft.client.texture.NativeImageBackedTexture) fv;
                            try {
                                java.lang.reflect.Method m = net.minecraft.client.texture.NativeImageBackedTexture.class.getMethod("getImage");
                                Object o = m.invoke(nib);
                                if (o instanceof net.minecraft.client.texture.NativeImage) {
                                    var skin = (net.minecraft.client.texture.NativeImage) o;
                                    int hash = computeNativeImageHash(skin);
                                    Integer prev = skinImageHash.get(key.toLowerCase());
                                    boolean currentHasOverlay = nativeImageHasOverlay(skin);
                                    Boolean knownOverlay = headOverlayMap.get(key.toLowerCase());
                                    if (prev != null && prev.intValue() == hash && headTextureCache.containsKey(key.toLowerCase()) && (knownOverlay != null && knownOverlay.booleanValue() == currentHasOverlay)) return true;
                                    NativeImage head = compositeHeadFromNativeImage(skin, key);
                                    if (head == null) return false;
                                    // Force save the in-memory skin to disk so any external readers see the updated PNG
                                    try { saveComposedHeadToDisk(key, head); } catch (Throwable ignored) {}
                                    registerHeadTexture(key, head);
                                    cacheTimestamps.put(key.toLowerCase(), System.currentTimeMillis());
                                    failureTimestamps.remove(key.toLowerCase());
                                    skinImageHash.put(key.toLowerCase(), hash);
                                    return true;
                                }
                            } catch (NoSuchMethodException nsme) {
                                try {
                                    java.lang.reflect.Field fi = net.minecraft.client.texture.NativeImageBackedTexture.class.getDeclaredField("image");
                                    fi.setAccessible(true);
                                    Object o = fi.get(nib);
                                    if (o instanceof net.minecraft.client.texture.NativeImage) {
                                        var skin = (net.minecraft.client.texture.NativeImage) o;
                                        NativeImage head = compositeHeadFromNativeImage(skin, key);
                                        if (head == null) return false;
                                        // Force save the in-memory skin to disk so any external readers see the updated PNG
                                        try { saveComposedHeadToDisk(key, head); } catch (Throwable ignored) {}
                                        registerHeadTexture(key, head);
                                        cacheTimestamps.put(key.toLowerCase(), System.currentTimeMillis());
                                        failureTimestamps.remove(key.toLowerCase());
                                        // compute hash for this extracted skin before storing in map
                                        try {
                                            int hash = computeNativeImageHash(skin);
                                            skinImageHash.put(key.toLowerCase(), hash);
                                        } catch (Throwable ignored) {}
                                        return true;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        } else if (fv instanceof net.minecraft.client.texture.NativeImage) {
                            var skin = (net.minecraft.client.texture.NativeImage) fv;
                            int hash = computeNativeImageHash(skin);
                            Integer prev = skinImageHash.get(key.toLowerCase());
                            boolean currentHasOverlay = nativeImageHasOverlay(skin);
                            Boolean knownOverlay = headOverlayMap.get(key.toLowerCase());
                            if (prev != null && prev.intValue() == hash && headTextureCache.containsKey(key.toLowerCase()) && (knownOverlay != null && knownOverlay.booleanValue() == currentHasOverlay)) return true;
                            NativeImage head = compositeHeadFromNativeImage(skin, key);
                            if (head == null) return false;
                            // Force save the in-memory skin to disk
                            try { saveComposedHeadToDisk(key, head); } catch (Throwable ignored) {}
                            registerHeadTexture(key, head);
                            cacheTimestamps.put(key.toLowerCase(), System.currentTimeMillis());
                            failureTimestamps.remove(key.toLowerCase());
                            skinImageHash.put(key.toLowerCase(), hash);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // Last resort: Minecraft's HttpTexture stores the downloaded skin PNG in a local File cache.
            // Read that file directly — no HTTP request needed, skin is already on disk.
            try {
                Class<?> cls = tex.getClass();
                while (cls != null && cls != Object.class) {
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        if (!java.io.File.class.isAssignableFrom(f.getType())) continue;
                        try {
                            f.setAccessible(true);
                            java.io.File cacheFile = (java.io.File) f.get(tex);
                            if (cacheFile == null || !cacheFile.exists() || cacheFile.length() == 0) continue;
                            byte[] bytes = Files.readAllBytes(cacheFile.toPath());
                            NativeImage head = processAndSaveHead(key.toLowerCase(), bytes);
                            if (head == null) continue;
                            registerHeadTexture(key.toLowerCase(), head);
                            cacheTimestamps.put(key.toLowerCase(), System.currentTimeMillis());
                            failureTimestamps.remove(key.toLowerCase());
                            return true;
                        } catch (Throwable ignored) {}
                    }
                    cls = cls.getSuperclass();
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Try to extract a skin texture Identifier from a PlayerListEntry (or its SkinTextures) using reflection
     * and register a composited head for the given key. Returns true on success.
     */
    public static boolean tryRegisterHeadFromPlayerListEntry(net.minecraft.client.network.PlayerListEntry ple, String key) {
        if (ple == null || key == null) return false;
        // Skip expensive per-frame reflection if we already have a cached texture for this key
        // and the method was called recently. Always allow through if head is not yet cached.
        String normKey = key.toLowerCase();
        if (headTextureCache.containsKey(normKey)) {
            Long last = pleRegisterTimestamps.get(normKey);
            if (last != null && System.currentTimeMillis() - last < PLE_REGISTER_COOLDOWN_MS) return true;
        }
        pleRegisterTimestamps.put(normKey, System.currentTimeMillis());
        try {
            // Primary: extract skin URL from SkinTextures by scanning all no-arg String methods.
            // SkinTextures is a record with a textureUrl() accessor that holds the direct skin PNG URL.
            // Skin Restorer always populates this. We scan methods rather than calling by name to
            // avoid NoSuchMethodException if the method is remapped in intermediary/production.
            try {
                Object st = null;
                try { java.lang.reflect.Method gm = ple.getClass().getMethod("getSkinTextures"); st = gm.invoke(ple); } catch (NoSuchMethodException nsme) { st = null; } catch (Throwable ignored) {}
                if (st != null) {
                    for (java.lang.reflect.Method m : st.getClass().getMethods()) {
                        if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                        try {
                            Object v = m.invoke(st);
                            if (v instanceof String && ((String) v).startsWith("http")) {
                                tryRegisterHeadFromDirectUrl((String) v, key);
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            // Secondary: reflect over PlayerListEntry fields/methods
            try {
                Object st = null;
                try { java.lang.reflect.Method gm = ple.getClass().getMethod("getSkinTextures"); st = gm.invoke(ple); } catch (NoSuchMethodException nsme) { st = null; } catch (Throwable ignored) {}
                if (st != null) {
                    if (tryRegisterFromObjectFields(st, key)) return true;
                }
            } catch (Throwable ignored) {}
            // Last resort: reflect over PlayerListEntry fields/methods
            try {
                if (tryRegisterFromObjectFields(ple, key)) return true;
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Inspect arbitrary object's methods/fields to find an Identifier/String/NativeImage/NativeImageBackedTexture
     * that can be used to register a head texture for the given key.
     * Returns true when a head was successfully registered.
     */
    private static boolean tryRegisterFromObjectFields(Object obj, String key) {
        if (obj == null || key == null) return false;
        try {
            Class<?> c = obj.getClass();
            // try methods first
            for (var m : c.getMethods()) {
                try {
                    if (m.getParameterCount() != 0) continue;
                    Object val = null;
                    try { val = m.invoke(obj); } catch (Throwable ignored) { continue; }
                    if (val == null) continue;

                    if (val instanceof net.minecraft.util.Identifier) {
                        if (tryRegisterHeadFromTexture((net.minecraft.util.Identifier) val, key)) return true;
                        continue;
                    }

                    if (val instanceof String) {
                        String s = (String) val;
                        if (s.startsWith("http")) { tryRegisterHeadFromDirectUrl(s, key); return true; }
                        if (s.contains(":")) {
                            try { if (tryRegisterHeadFromTexture(new net.minecraft.util.Identifier(s), key)) return true; } catch (Throwable ignored) {}
                        }
                        continue;
                    }

                    if (val instanceof net.minecraft.client.texture.NativeImageBackedTexture) {
                        try {
                            java.lang.reflect.Method gm = net.minecraft.client.texture.NativeImageBackedTexture.class.getMethod("getImage");
                            Object o = gm.invoke(val);
                            if (o instanceof net.minecraft.client.texture.NativeImage) {
                                NativeImage skin = (NativeImage) o;
                                NativeImage head = compositeHeadFromNativeImage(skin, key);
                                if (head == null) return false;
                                try { saveComposedHeadToDisk(key, head); } catch (Throwable ignored) {}
                                registerHeadTexture(key, head);
                                cacheTimestamps.put(key.toLowerCase(), System.currentTimeMillis());
                                failureTimestamps.remove(key.toLowerCase());
                                try { skinImageHash.put(key.toLowerCase(), computeNativeImageHash(skin)); } catch (Throwable ignored) {}
                                return true;
                            }
                        } catch (NoSuchMethodException nsme) {
                            try {
                                java.lang.reflect.Field fi = net.minecraft.client.texture.NativeImageBackedTexture.class.getDeclaredField("image");
                                fi.setAccessible(true);
                                Object o = fi.get(val);
                                if (o instanceof net.minecraft.client.texture.NativeImage) {
                                    NativeImage skin = (NativeImage) o;
                                    NativeImage head = compositeHeadFromNativeImage(skin, key);
                                    if (head == null) return false;
                                    try { saveComposedHeadToDisk(key, head); } catch (Throwable ignored) {}
                                    registerHeadTexture(key, head);
                                    cacheTimestamps.put(key.toLowerCase(), System.currentTimeMillis());
                                    failureTimestamps.remove(key.toLowerCase());
                                    return true;
                                }
                            } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // Public stub used by client to request a gentle refresh without destroying the current texture immediately.
    public static void requestHeadRefreshPreserveCurrent(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        String key = playerName.toLowerCase();
        if (loadingSet.add(key)) fetchSkinAsync(key, playerName);
    }

    // Reflection-based texture registration similar to CustomIconCache.registerNativeImageTexture
    private static void registerNativeImageTexture(MinecraftClient client, Identifier id, net.minecraft.client.texture.NativeImage image) throws Exception {
        if (client == null || id == null || image == null) return;
        Class<?> nibClass = net.minecraft.client.texture.NativeImageBackedTexture.class;
        Object textureObj = null;
        try {
            // try constructor NativeImage
            try {
                java.lang.reflect.Constructor<?> ctor = nibClass.getConstructor(net.minecraft.client.texture.NativeImage.class);
                textureObj = ctor.newInstance(image);
            } catch (NoSuchMethodException ignored) {
                // try various fallbacks
                for (var ctor : nibClass.getConstructors()) {
                    var pts = ctor.getParameterTypes();
                    if (pts.length == 1 && pts[0] == net.minecraft.client.texture.NativeImage.class) {
                        try { textureObj = ctor.newInstance(image); break; } catch (Throwable ignored2) {}
                    }
                    if (pts.length == 3 && pts[0] == int.class && pts[1] == int.class) {
                        try { textureObj = ctor.newInstance(image.getWidth(), image.getHeight(), Boolean.TRUE); break; } catch (Throwable ignored2) {}
                    }
                    if (pts.length == 2 && pts[0] == java.util.function.Supplier.class) {
                        try { textureObj = ctor.newInstance((Supplier<String>)() -> id.toString(), image); break; } catch (Throwable ignored2) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (textureObj == null) {
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

        Object tm = client.getTextureManager();
        // try to register via reflection
        if (textureObj != null) {
            for (var m : tm.getClass().getMethods()) {
                if (!m.getName().equals("registerTexture")) continue;
                if (m.getParameterCount() != 2) continue;
                try {
                    m.invoke(tm, id, textureObj);
                    return;
                } catch (Throwable ignored) {}
            }
        }
        // final fallback: try typed registration (may fail on some mappings)
        try {
            client.getTextureManager().registerTexture(id, new net.minecraft.client.texture.NativeImageBackedTexture(image));
        } catch (Throwable ignored) {}
    }

    private static void fetchSkinAsync(String key, String playerName) {
        if (key == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                // Step 1: name → UUID
                HttpResponse<String> uuidResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                        .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (uuidResp.statusCode() != 200) {
                    failureTimestamps.put(key, System.currentTimeMillis());
                    return;
                }
                String uuid = JsonParser.parseString(uuidResp.body()).getAsJsonObject().get("id").getAsString();
                uuidCache.put(key, uuid);

                // Step 2: UUID → skin URL via sessionserver
                String dashed = uuid.length() == 32
                    ? uuid.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")
                    : uuid;
                HttpResponse<String> profileResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + dashed))
                        .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (profileResp.statusCode() != 200) {
                    failureTimestamps.put(key, System.currentTimeMillis());
                    return;
                }
                String skinUrl = extractSkinUrl(profileResp.body());
                if (skinUrl == null) {
                    failureTimestamps.put(key, System.currentTimeMillis());
                    return;
                }
                cachedSkinUrls.put(key, skinUrl);

                // Step 3: download skin PNG
                HttpResponse<byte[]> skinResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(skinUrl)).timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                if (skinResp.statusCode() != 200) {
                    failureTimestamps.put(key, System.currentTimeMillis());
                    return;
                }

                // Step 4: composite head & register
                NativeImage head = processAndSaveHead(key, skinResp.body());
                if (head != null) {
                    registerHeadTexture(key, head);
                    cacheTimestamps.put(key, System.currentTimeMillis());
                    failureTimestamps.remove(key);
                } else {
                    failureTimestamps.put(key, System.currentTimeMillis());
                }
            } catch (Exception e) {
                failureTimestamps.put(key, System.currentTimeMillis());
            } finally {
                loadingSet.remove(key);
            }
        });
    }

    /** Parse a Mojang session-server profile JSON and extract the skin URL. */
    private static String extractSkinUrl(String profileJson) {
        try {
            var props = JsonParser.parseString(profileJson).getAsJsonObject().getAsJsonArray("properties");
            for (var el : props) {
                var prop = el.getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString())) {
                    String decoded = new String(Base64.getDecoder().decode(prop.get("value").getAsString()));
                    var textures = JsonParser.parseString(decoded).getAsJsonObject()
                        .getAsJsonObject("textures");
                    if (textures != null && textures.has("SKIN")) {
                        return textures.getAsJsonObject("SKIN").get("url").getAsString();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}


package com.joragaka.ptdialogue.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

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
    // Track recent failures to avoid repeated attempts/log spam when skins can't be loaded
    private static final Map<String, Long> failureTimestamps = new ConcurrentHashMap<>();
    private static final long FAILURE_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes
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
     * Composite a head from raw skin PNG bytes, save to disk, register GPU texture.
     * Used by IconSyncHandler when receiving raw skin from server.
     * Returns the composited NativeImage, or null on failure.
     */
    public static NativeImage compositeAndRegisterHead(String nick, byte[] skinPngBytes) {
        String key = nick.toLowerCase();
        NativeImage head = processAndSaveHead(key, skinPngBytes);
        if (head != null) {
            registerHeadTexture(key, head);
            failureTimestamps.remove(key);
        }
        return head;
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
            byte[] skinPngBytes = Files.readAllBytes(cached);
            NativeImage headImage = compositeHead(skinPngBytes);
            if (headImage == null) return;
            registerHeadTexture(key, headImage);
            cacheTimestamps.put(key, System.currentTimeMillis());
            failureTimestamps.remove(key);
        } catch (Exception e) {
            // Record failure but avoid spamming logs on repeated failures
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
    private static NativeImage compositeHead(byte[] skinPngBytes) {
        try {
            NativeImage skin = NativeImage.read(skinPngBytes);
            if (skin == null) return null;

            int skinW = skin.getWidth();
            int skinH = skin.getHeight();
            boolean hasOverlay = (skinW >= 64 && skinH >= 64);

            NativeImage head = new NativeImage(HAT_OUT, HAT_OUT, true);

            // Step 1: Draw face 8×8 → 16×16 at offset (1,1)
            for (int y = 0; y < FACE_OUT; y++) {
                for (int x = 0; x < FACE_OUT; x++) {
                    // nearest-neighbor: map (0..15) → (0..7)
                    int srcX = x * 8 / FACE_OUT;
                    int srcY = y * 8 / FACE_OUT;
                    int pixel = skin.getColorArgb(8 + srcX, 8 + srcY);
                    head.setColorArgb(1 + x, 1 + y, pixel);
                }
            }

            // Step 2: Draw hat overlay 8×8 → 18×18 over entire canvas (alpha blend)
            if (hasOverlay) {
                for (int y = 0; y < HAT_OUT; y++) {
                    for (int x = 0; x < HAT_OUT; x++) {
                        int srcX = x * 8 / HAT_OUT;
                        int srcY = y * 8 / HAT_OUT;
                        int hatPixel = skin.getColorArgb(40 + srcX, 8 + srcY);
                        int hatA = (hatPixel >>> 24) & 0xFF;

                        if (hatA == 0) continue; // fully transparent — skip

                        if (hatA == 255) {
                            // Fully opaque — overwrite
                            head.setColorArgb(x, y, hatPixel);
                        } else {
                            // Alpha blend hat over existing pixel
                            int base = head.getColorArgb(x, y);
                            head.setColorArgb(x, y, alphaBlend(base, hatPixel));
                        }
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

    /** Composite head from skin bytes, save raw skin to disk, return NativeImage for GPU. */
    private static NativeImage processAndSaveHead(String key, byte[] skinPngBytes) {
        NativeImage head = compositeHead(skinPngBytes);
        if (head == null) return null;

        // Save raw skin PNG to disk (compositing happens on load)
        try {
            Path path = getHeadDiskCachePath(key);
            Files.createDirectories(path.getParent());
            Files.write(path, skinPngBytes);
        } catch (Exception e) {
            // don't spam logs on IO save failure; mark as failure so we don't retry constantly
            failureTimestamps.put(key, System.currentTimeMillis());
        }

        return head;
    }

    // ──────────────── texture registration ─────────────

    private static void registerHeadTexture(String key, NativeImage headImage) {
        MinecraftClient client = MinecraftClient.getInstance();
        Identifier textureId = Identifier.of(NAMESPACE, "skin_head/" + key);
        Runnable register = () -> {
            try {
                try { client.getTextureManager().destroyTexture(textureId); } catch (Exception ignored) {}
                Supplier<String> name = () -> "ptdialogue_head_" + key;
                client.getTextureManager().registerTexture(textureId,
                        new NativeImageBackedTexture(name, headImage));
                headTextureCache.put(key, textureId);
                // successful registration -> clear failure mark
                failureTimestamps.remove(key);
                // If we know a username for this uuid, also map username -> same texture id
                try {
                    String knownName = uuidToName.get(key);
                    if (knownName != null && !knownName.isEmpty()) {
                        headTextureCache.put(knownName.toLowerCase(), textureId);
                        cacheTimestamps.put(knownName.toLowerCase(), System.currentTimeMillis());
                    }
                } catch (Throwable ignored) {}
                // Notify listeners waiting for this head
                List<Consumer<Identifier>> listeners = headListeners.remove(key);
                if (listeners != null) {
                    for (var c : listeners) {
                        try { c.accept(textureId); } catch (Throwable ignored) {}
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

    // ──────────────────── Mojang API pipeline ────────────────────

    private static void fetchSkinAsync(String key, String playerName) {
        // If a recent failure exists, skip fetch to avoid repeated spamming
        Long lastFail = failureTimestamps.get(key);
        if (lastFail != null && (System.currentTimeMillis() - lastFail) < FAILURE_COOLDOWN_MS) {
            loadingSet.remove(key);
            return;
        }
        CompletableFuture<String> uuidFuture;
        String cachedUuid = uuidCache.get(key);
        if (cachedUuid != null) {
            uuidFuture = CompletableFuture.completedFuture(cachedUuid);
        } else {
            // If playerName looks like a UUID (contains '-' or is 32 hex chars), use it directly and skip name->uuid lookup
            String maybe = playerName == null ? "" : playerName.trim();
            boolean looksLikeUuid = maybe.contains("-") || maybe.length() == 32;
            if (looksLikeUuid) {
                uuidFuture = CompletableFuture.completedFuture(maybe);
            } else {
                uuidFuture = HTTP_CLIENT.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                                .timeout(REQUEST_TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                ).thenApply(resp -> {
                    if (resp.statusCode() != 200) return null;
                    String uuid = JsonParser.parseString(resp.body()).getAsJsonObject().get("id").getAsString();
                    uuidCache.put(key, uuid);
                    return uuid;
                });
            }
        }

        uuidFuture
        .thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.completedFuture((String) null);
            return HTTP_CLIENT.sendAsync(
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                            .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            ).thenApply(resp -> {
                if (resp.statusCode() != 200) return null;
                try {
                    // Store mapping uuid -> current username if available in profile
                    var json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    if (json.has("name")) {
                        String name = json.get("name").getAsString();
                        uuidToName.put(uuid.toLowerCase(), name);
                    }
                } catch (Exception ignored) {}
                return extractSkinUrl(resp.body());
            });
        })
        .thenCompose(skinUrl -> {
            if (skinUrl == null) return CompletableFuture.completedFuture((byte[]) null);
            String prev = cachedSkinUrls.get(key);
            if (skinUrl.equals(prev) && headTextureCache.containsKey(key)) {
                cacheTimestamps.put(key, System.currentTimeMillis());
                return CompletableFuture.completedFuture((byte[]) null);
            }
            cachedSkinUrls.put(key, skinUrl);
            return HTTP_CLIENT.sendAsync(
                    HttpRequest.newBuilder().uri(URI.create(skinUrl)).timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            ).thenApply(resp -> resp.statusCode() == 200 ? resp.body() : null);
        })
        .thenAccept(pngBytes -> {
            if (pngBytes == null) {
                // No bytes -> either nothing changed or fetch failed
                if (!headTextureCache.containsKey(key)) {
                    // mark failure to avoid repeated retries
                    failureTimestamps.put(key, System.currentTimeMillis());
                }
                loadingSet.remove(key);
                return;
            }
            try {
                NativeImage headImage = processAndSaveHead(key, pngBytes);
                if (headImage == null) { loadingSet.remove(key); return; }

                MinecraftClient.getInstance().execute(() -> {
                    try {
                        Identifier headId = Identifier.of(NAMESPACE, "skin_head/" + key);
                        try { MinecraftClient.getInstance().getTextureManager().destroyTexture(headId); } catch (Exception ignored) {}
                        MinecraftClient.getInstance().getTextureManager().registerTexture(headId,
                                new NativeImageBackedTexture(() -> "ptdialogue_head_" + key, headImage));
                        headTextureCache.put(key, headId);
                        cacheTimestamps.put(key, System.currentTimeMillis());
                        failureTimestamps.remove(key);
                        // If we know a username for this uuid, also map username -> same texture id
                        try {
                            String knownName = uuidToName.get(key);
                            if (knownName != null && !knownName.isEmpty()) {
                                headTextureCache.put(knownName.toLowerCase(), headId);
                                cacheTimestamps.put(knownName.toLowerCase(), System.currentTimeMillis());
                            }
                        } catch (Throwable ignored) {}
                        // Notify listeners waiting for this head
                        List<Consumer<Identifier>> listeners = headListeners.remove(key);
                        if (listeners != null) {
                            for (var c : listeners) {
                                try { c.accept(headId); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Exception e) {
                        failureTimestamps.put(key, System.currentTimeMillis());
                    }
                    loadingSet.remove(key);
                });
            } catch (Exception e) {
                failureTimestamps.put(key, System.currentTimeMillis());
                loadingSet.remove(key);
            }
        })
        .exceptionally(ex -> {
            // Record failure but avoid spamming full stack trace
            failureTimestamps.put(key, System.currentTimeMillis());
            loadingSet.remove(key);
             return null;
         });
    }

    private static String extractSkinUrl(String profileJson) {
        try {
            JsonObject json = JsonParser.parseString(profileJson).getAsJsonObject();
            var properties = json.getAsJsonArray("properties");
            if (properties == null) return null;
            for (var prop : properties) {
                var propObj = prop.getAsJsonObject();
                if (!"textures".equals(propObj.get("name").getAsString())) continue;
                String decoded = new String(Base64.getDecoder().decode(propObj.get("value").getAsString()));
                JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) {
                    return textures.getAsJsonObject("SKIN").get("url").getAsString();
                }
            }
        } catch (Exception e) {
            // parsing failed — caller will record a failure and avoid spamming
        }
        return null;
    }
}

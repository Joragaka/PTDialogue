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
import java.util.function.Supplier;

/**
 * Fetches player skins via Mojang API, composites face+hat into a single
 * small head texture, and caches only that head PNG on disk.
 */
public class SkinCache {

    private static final Map<String, Identifier> headTextureCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final Map<String, String> cachedSkinUrls = new ConcurrentHashMap<>();
    private static final Map<String, String> uuidCache = new ConcurrentHashMap<>();
    private static final Set<String> loadingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
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

    // ──────────────────────────── public API ────────────────────────────

    public static void preload(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        if (playerName.toLowerCase().endsWith(".png")) return;

        String key = playerName.toLowerCase();

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

    public static Identifier getHeadTextureId(String playerName) {
        if (playerName == null) return null;
        String key = playerName.toLowerCase();

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
        } catch (Exception e) {
            System.err.println("[ptdialogue] Failed to load head from disk: " + e.getMessage());
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
            System.err.println("[ptdialogue] compositeHead failed: " + e.getMessage());
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
            System.err.println("[ptdialogue] Failed to save head to disk: " + e.getMessage());
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
            } catch (Exception e) {
                System.err.println("[ptdialogue] Failed to register head texture: " + e.getMessage());
            }
        };
        if (client.isOnThread()) register.run();
        else client.execute(register);
    }

    // ──────────────────── Mojang API pipeline ────────────────────

    private static void fetchSkinAsync(String key, String playerName) {
        CompletableFuture<String> uuidFuture;
        String cachedUuid = uuidCache.get(key);
        if (cachedUuid != null) {
            uuidFuture = CompletableFuture.completedFuture(cachedUuid);
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

        uuidFuture
        .thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.completedFuture((String) null);
            return HTTP_CLIENT.sendAsync(
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                            .timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            ).thenApply(resp -> resp.statusCode() == 200 ? extractSkinUrl(resp.body()) : null);
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
            if (pngBytes == null) { loadingSet.remove(key); return; }
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
                    } catch (Exception e) {
                        System.err.println("[ptdialogue] Failed to register head: " + e.getMessage());
                    }
                    loadingSet.remove(key);
                });
            } catch (Exception e) {
                System.err.println("[ptdialogue] Failed to create head: " + e.getMessage());
                loadingSet.remove(key);
            }
        })
        .exceptionally(ex -> {
            System.err.println("[ptdialogue] Skin fetch failed for " + playerName + ": " + ex.getMessage());
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
            System.err.println("[ptdialogue] Failed to parse profile JSON: " + e.getMessage());
        }
        return null;
    }
}

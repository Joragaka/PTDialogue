package com.joragaka.ptdialogue.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.function.Supplier;

/**
 * Generates and caches a black-and-purple checkerboard "missing texture"
 */
public class MissingTextureHelper {

    private static final Identifier TEXTURE_ID = new Identifier("ptdialogue", "missing_texture");
    private static boolean registered = false;

    /**
     * Returns the Identifier of the missing texture.
     * On first call, generates and registers a 16x16 black-purple checkerboard.
     */
    public static Identifier getTextureId() {
        if (!registered) {
            registerTexture();
        }
        return TEXTURE_ID;
    }

    private static synchronized void registerTexture() {
        if (registered) return;
        registered = true;

        // 16x16 checkerboard: 2x2 pixel blocks of black (0xFF000000) and magenta (0xFFF800F8)
        int size = 16;
        NativeImage image = new NativeImage(size, size, false);

        int black   = 0xFF000000; // ABGR: fully opaque black
        int magenta = 0xFFF800F8; // ABGR: fully opaque magenta/purple

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                // 8x8 checkerboard pattern (each square is 8 pixels)
                boolean isBlack = ((px / 8) + (py / 8)) % 2 == 0;
                // Use reflection-friendly helper if available
                try { image.setColor(px, py, isBlack ? black : magenta); } catch (Throwable ignored) { try { java.lang.reflect.Method m = image.getClass().getMethod("setColorArgb", int.class, int.class, int.class); m.invoke(image, px, py, isBlack ? black : magenta); } catch (Throwable ignored2) { try { java.lang.reflect.Method m2 = image.getClass().getMethod("setArgb", int.class, int.class, int.class); m2.invoke(image, px, py, isBlack ? black : magenta); } catch (Throwable ignored3) {} } }
            }
        }

        MinecraftClient client = MinecraftClient.getInstance();
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        client.getTextureManager().registerTexture(TEXTURE_ID, texture);
    }
}
